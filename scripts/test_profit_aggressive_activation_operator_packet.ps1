Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_aggressive_activation_operator_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-aggressive-activation-operator-packet] read-only packet",
        "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET",
        "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
        "BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING",
        "HIGH_RISK_MICRO_LIVE_PROBE",
        "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH",
        "EVIDENCE_ONLY_ACCELERATOR",
        "NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE",
        "DATAFRESHNESS_REPLAY_ROWS_MISSING",
        "MaxProbeNotionalUsdt",
        "profit_aggressive_activation_status",
        "profit_aggressive_activation_options",
        "profit_aggressive_activation_required_authorization_texts",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "telegram_send_allowed=false",
        "db_grid_fund_earn_exchange_mutation_allowed=false",
        "notAuthorization=read-only aggressive activation operator packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "aggressive activation script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram|ssh ") {
    throw "aggressive activation packet must not write files or invoke SSH/MCP/trading mutation calls"
}

foreach ($marker in @(
        "prepare_profit_aggressive_activation_operator_packet.ps1",
        "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET",
        "HIGH_RISK_MICRO_LIVE_PROBE",
        "EVIDENCE_ONLY_ACCELERATOR",
        "order_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention aggressive activation packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local runs aggressive activation packet test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_aggressive_activation_operator_packet.ps1"

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for aggressive activation packet test" }

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-aggressive-activation-" + [guid]::NewGuid().ToString("N"))
$authLog = Join-Path $tempDir "auth.log"
$quickLog = Join-Path $tempDir "quick.log"
$nextLog = Join-Path $tempDir "next.log"

try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

    $authPacket = [pscustomobject]@{
        packetType = "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET"
        status = "READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE"
        authorizationRequestReady = $true
        liveReadinessConclusion = "NOT_READY_FOR_LIVE_ENABLEMENT"
        nextAuthorizationRequired = "TRAILING_STOP_DRY_RUN_REVIEW_AUTHORIZATION"
    }
    $quickPacket = [pscustomobject]@{
        packetType = "PROFIT_OPERATOR_QUICK_STATUS"
        status = "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"
        nextExecutionStatus = [pscustomobject]@{
            status = "TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION"
            route = "TRAILING_STOP_DRY_RUN_OBSERVATION"
            uniqueBlocker = "NO_OPEN_OCO_POSITIONS"
            openOcoPositions = "0"
            dataFreshnessReplayCandidateIdRows = "0"
            dataFreshnessCompleteReplayableCandidateRows = "0"
        }
    }
    $nextPacket = [pscustomobject]@{
        packetType = "PROFIT_NEXT_EXECUTION_BLOCKER_PACKET"
        status = "TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION"
        profitRoute = "TRAILING_STOP_DRY_RUN_OBSERVATION"
        uniqueBlocker = "NO_OPEN_OCO_POSITIONS"
    }

    Set-Content -LiteralPath $authLog -Encoding UTF8 -Value @(
        ("profit_operator_authorization_request_packet=" + (ConvertTo-Json -Compress -Depth 8 $authPacket)),
        "profit_operator_authorization_request_status=READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE"
    )
    Set-Content -LiteralPath $quickLog -Encoding UTF8 -Value @(
        ("profit_operator_quick_status_packet=" + (ConvertTo-Json -Compress -Depth 8 $quickPacket)),
        "profit_operator_quick_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"
    )
    Set-Content -LiteralPath $nextLog -Encoding UTF8 -Value @(
        ("profit_next_execution_blocker_packet=" + (ConvertTo-Json -Compress -Depth 8 $nextPacket)),
        "profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION"
    )

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -AuthorizationRequestLogPath $authLog `
        -QuickStatusLogPath $quickLog `
        -NextExecutionLogPath $nextLog `
        -MaxProbeNotionalUsdt 10 `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "aggressive activation packet ready case failed:`n$text"
    }

    foreach ($marker in @(
            "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
            "profit_aggressive_activation_authorization_request_ready=true",
            "profit_aggressive_activation_next_execution_route=TRAILING_STOP_DRY_RUN_OBSERVATION",
            "profit_aggressive_activation_next_execution_unique_blocker=NO_OPEN_OCO_POSITIONS",
            "profit_aggressive_activation_data_freshness_replay_candidate_id_rows=0",
            "HIGH_RISK_MICRO_LIVE_PROBE",
            "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH",
            "EVIDENCE_ONLY_ACCELERATOR",
            "NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE",
            "DATAFRESHNESS_REPLAY_ROWS_MISSING",
            "order_allowed=false",
            "notAuthorization=read-only aggressive activation operator packet only"
        )) {
        Assert-Contains -Name "aggressive activation ready output" -Text $text -Pattern ([regex]::Escape($marker))
    }

    $packetJson = Get-LastPrefixedValue -Text $text -Prefix "profit_aggressive_activation_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployOrEnvChangeAllowed -or [bool]$packet.livePolicyChangeAllowed) {
        throw "aggressive activation packet must keep mutation flags false"
    }
    if (@($packet.aggressiveOptions).Count -ne 3) {
        throw "aggressive activation packet should include exactly three options"
    }
    if (@($packet.riskBlockers) -notcontains "NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE") {
        throw "aggressive activation packet should surface no-open-OCO risk blocker"
    }

    $missingOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -AuthorizationRequestLogPath (Join-Path $tempDir "missing-auth.log") `
        -QuickStatusLogPath $quickLog `
        -NextExecutionLogPath $nextLog 2>&1
    $missingExitCode = $LASTEXITCODE
    $missingText = ($missingOutput | Out-String -Width 4096)
    if ($missingExitCode -ne 0) {
        throw "aggressive activation missing non-require case should not fail:`n$missingText"
    }
    foreach ($marker in @(
            "profit_aggressive_activation_status=BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING",
            "profit operator authorization request log present",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "aggressive activation missing output" -Text $missingText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-aggressive-activation-operator-packet-test] OK"
