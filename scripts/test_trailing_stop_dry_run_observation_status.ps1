Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsBeforeSsh {
    param([string[]]$Arguments, [string]$ExpectedPattern)

    $script = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_observation_status_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing dry-run observation status test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -eq 0) {
        throw "trailing dry-run observation status accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing dry-run observation status did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing dry-run observation status reached SSH before local input guard:`n$text"
    }
}

function Write-PostOptInLog {
    param([string]$Path, [string]$Status, [string]$OpenOcoPositions)

    $packet = [pscustomobject]@{
        packetType = "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET"
        status = $Status
        symbol = "BTCUSDT"
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = if ($Status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") { "true" } else { "false" }
        currentGlobalDryRun = "true"
        currentOpenOcoPositions = $OpenOcoPositions
        expectedOptInStrategyId = 574
        expectedStrategyOptIn = $Status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY"
        alreadyActiveDryRun = $Status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY"
        missingRequirements = @()
    }
    Set-Content -LiteralPath $Path -Encoding UTF8 -Value @(
        "trailing_stop_post_opt_in_readiness_status=$Status",
        ("trailing_stop_post_opt_in_readiness_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet))
    )
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_observation_status_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$planPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $planPath
) -join "`n"

foreach ($marker in @(
        "[trailing-stop-dry-run-observation-status] read-only packet",
        "TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET",
        "ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE",
        "ACTIVE_OPEN_OCO_SAMPLE_AVAILABLE",
        "BLOCKED_TRAILING_DRY_RUN_NOT_ACTIVE",
        "NO_OPEN_OCO_POSITIONS",
        "sampleCollectionBlockedBy",
        "observationPreconditionsReady",
        "observationSampleReady",
        "requiredBeforeLivePromotion",
        "productionEnvChangeAllowed = `$false",
        "orderAllowed = `$false",
        "ocoMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe",
        "notAuthorization=read-only trailing-stop dry-run observation status packet only"
    )) {
    Assert-Contains -Name "trailing dry-run observation status script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "setTrailingStopOptIn(",
        "createGrid",
        "placeOrder",
        "modifyOco",
        "closePosition"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing dry-run observation status must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_trailing_stop_dry_run_observation_status_ssh.ps1",
        "TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET",
        "ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE",
        "NO_OPEN_OCO_POSITIONS",
        "dry-run observation evidence",
        "read-only observation status"
    )) {
    Assert-Contains -Name "docs mention trailing dry-run observation status" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLog = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-dry-run-observation-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    Write-PostOptInLog -Path $tempLog -Status "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY" -OpenOcoPositions "0"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing dry-run observation status replay test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLog $tempLog -ExpectedOptInStrategyId 574 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "trailing dry-run observation status failed active no-sample replay:`n$text"
    }
    foreach ($marker in @(
            "source_post_opt_in_readiness_status=TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY",
            "trailing_stop_acceptance=PASS",
            "trailing_stop_improvement_pct=52.753%",
            "trailing_stop_dry_run_observation_current_global_enabled=true",
            "trailing_stop_dry_run_observation_current_global_dry_run=true",
            "trailing_stop_dry_run_observation_current_open_oco_positions=0",
            "trailing_stop_dry_run_observation_preconditions_ready=true",
            "trailing_stop_dry_run_observation_sample_ready=false",
            "trailing_stop_dry_run_observation_sample_collection_blocked_by=NO_OPEN_OCO_POSITIONS",
            "trailing_stop_dry_run_observation_unique_blocker=NO_OPEN_OCO_POSITIONS",
            "trailing_stop_dry_run_observation_status=ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE",
            '"packetType":"TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET"',
            '"status":"ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE"',
            '"sampleCollectionBlockedBy":"NO_OPEN_OCO_POSITIONS"',
            '"observationPreconditionsReady":true',
            '"observationSampleReady":false',
            '"orderAllowed":false',
            '"ocoMutationAllowed":false',
            '"gridMutationAllowed":false',
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only trailing-stop dry-run observation status packet only"
        )) {
        Assert-Contains -Name "trailing dry-run observation active waiting replay" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing dry-run observation source-log replay unexpectedly invoked SSH:`n$text"
    }

    Write-PostOptInLog -Path $tempLog -Status "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY" -OpenOcoPositions "2"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $sampleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLog $tempLog -ExpectedOptInStrategyId 574 -RequireReady 2>&1
        $sampleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $sampleText = ($sampleOutput | Out-String -Width 4096)
    if ($sampleExitCode -ne 0) {
        throw "trailing dry-run observation status failed sample-available replay:`n$sampleText"
    }
    foreach ($marker in @(
            "trailing_stop_dry_run_observation_current_open_oco_positions=2",
            "trailing_stop_dry_run_observation_sample_ready=true",
            "trailing_stop_dry_run_observation_sample_collection_blocked_by=NONE",
            "trailing_stop_dry_run_observation_unique_blocker=NONE",
            "trailing_stop_dry_run_observation_status=ACTIVE_OPEN_OCO_SAMPLE_AVAILABLE"
        )) {
        Assert-Contains -Name "trailing dry-run observation sample replay" -Text $sampleText -Pattern ([regex]::Escape($marker))
    }

    Write-PostOptInLog -Path $tempLog -Status "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -OpenOcoPositions "0"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLog $tempLog -ExpectedOptInStrategyId 574 2>&1
        $blockedExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $blockedText = ($blockedOutput | Out-String -Width 4096)
    if ($blockedExitCode -ne 0) {
        throw "trailing dry-run observation blocked replay should be inspectable without RequireReady:`n$blockedText"
    }
    foreach ($marker in @(
            "trailing_stop_dry_run_observation_preconditions_ready=false",
            "trailing_stop_dry_run_observation_sample_collection_blocked_by=TRAILING_DRY_RUN_NOT_ACTIVE_OR_NOT_VERIFIED",
            "trailing_stop_dry_run_observation_unique_blocker=TRAILING_DRY_RUN_NOT_ACTIVE_OR_NOT_VERIFIED",
            "trailing_stop_dry_run_observation_status=BLOCKED_TRAILING_DRY_RUN_NOT_ACTIVE"
        )) {
        Assert-Contains -Name "trailing dry-run observation blocked replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempLog) {
        Remove-Item -LiteralPath $tempLog -Force
    }
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "999") `
    -ExpectedPattern "Limit must be between 1 and 500"

Write-Host "[trailing-stop-dry-run-observation-status-test] OK"
