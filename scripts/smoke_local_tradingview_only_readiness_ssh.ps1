param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$LocalTradingViewStrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$LocalTradingViewDays = 90,
    [string]$LocalTradingViewSource = "okx",
    [switch]$RequireReady,
    [switch]$RequireCurrentCandidate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) {
    throw "LocalTradingViewStrategyId must be between 1 and 999999999."
}

if ($LocalTradingViewDays -lt 7 -or $LocalTradingViewDays -gt 730) {
    throw "LocalTradingViewDays must be between 7 and 730."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16
Assert-SmokeTokenSafe -Name "LocalTradingViewSource" -Value $LocalTradingViewSource -MaxLength 32

$scriptDir = $PSScriptRoot

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return $null
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-JsonArrayText {
    param([string]$Text, [string]$Prefix)

    $value = Get-LastPrefixedValue -Text $Text -Prefix $Prefix
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $null
    }
    try {
        $null = $value | ConvertFrom-Json -ErrorAction Stop
        return $value
    } catch {
        return $null
    }
}

function Test-JsonArrayTextEmpty {
    param([string]$Value)

    return -not [string]::IsNullOrWhiteSpace($Value) -and $Value.Trim() -eq "[]"
}

function Get-LocalGitDiffFiles {
    param([string]$BaseCommit, [string]$HeadCommit)

    if ([string]::IsNullOrWhiteSpace($BaseCommit) -or [string]::IsNullOrWhiteSpace($HeadCommit)) {
        return @()
    }
    if ($BaseCommit -notmatch "^[0-9a-f]{7,40}$" -or $HeadCommit -notmatch "^[0-9a-f]{7,40}$") {
        return @()
    }

    $safeDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path.Replace("\", "/")
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $files = & git -c "safe.directory=$safeDirectory" diff --name-only $BaseCommit $HeadCommit 2>$null
        if ($LASTEXITCODE -ne 0) {
            return @()
        }
        return @($files | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    } catch {
        return @()
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Test-DocsToolingOnlyPath {
    param([string]$Path)

    return $Path -eq "README.md" -or $Path -like "docs/*" -or $Path -like "scripts/*"
}

function Get-ReadOnlyFailureClassification {
    param(
        [int]$ExitCode,
        [string]$OutputText
    )

    if ($ExitCode -eq 255 -and $OutputText -match "Permission denied \(publickey\)|Permission denied") {
        return "SSH_AUTH_FAILED"
    }
    if ($ExitCode -eq 255 -and $OutputText -match "Connection timed out|Connection refused|Could not resolve hostname|No route to host|Operation timed out") {
        return "SSH_CONNECT_FAILED"
    }
    if ($OutputText -match "ssh:|remote command failed") {
        return "SSH_COMMAND_FAILED"
    }
    return "READ_ONLY_SMOKE_FAILED"
}

function Assert-ReadOnlyCommandSucceeded {
    param(
        [string]$Name,
        [int]$ExitCode,
        [object[]]$Output
    )

    if ($ExitCode -eq 0) {
        return
    }

    $outputText = ($Output -join "`n")
    $classification = Get-ReadOnlyFailureClassification -ExitCode $ExitCode -OutputText $outputText
    Write-Host "local_tradingview_only_error=$classification"
    Write-Host "local_tradingview_only_error_detail=$Name failed before LOCAL_TRADINGVIEW-only evidence could be collected"
    Write-Host "local_tradingview_only_error_boundary=not complete LOCAL_TRADINGVIEW-only evidence; fix SSH access, key selection, or the failing read-only smoke and rerun"
    Write-Host 'local_tradingview_only_blockers=["LOCAL_TRADINGVIEW_ONLY_EVIDENCE_UNAVAILABLE"]'
    Write-Host "local_tradingview_only_verdict=NO_EVIDENCE"
    throw "$Name failed with exit code $ExitCode ($classification); LOCAL_TRADINGVIEW-only evidence was not collected."
}

function Invoke-ReadOnlySmoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    Write-Host ""
    Write-Host "===== BEGIN $Name ====="
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for read-only smoke invocation"
    }

    $processArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath) + $Arguments
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source @processArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output | ForEach-Object { Write-Host $_ }
    Write-Host "===== END $Name exit=$exitCode ====="
    Assert-ReadOnlyCommandSucceeded -Name $Name -ExitCode $exitCode -Output $output
    return ($output -join "`n")
}

function Add-If {
    param(
        [System.Collections.Generic.List[string]]$List,
        [bool]$Condition,
        [string]$Value
    )
    if ($Condition -and -not $List.Contains($Value)) {
        $List.Add($Value)
    }
}

Write-Host "[local-tradingview-only-readiness] read-only SSH smoke"
Write-Host "scope=READ_ONLY; invokes metadata, audit, background automation, and LOCAL_TRADINGVIEW candidate smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, deploy, restart, or external backfill/import state changed."
Write-Host "legacy_tiny_scorebuy_runtime_evidence_not_evaluated=true"
Write-Host "symbol=$Symbol localTradingViewStrategyId=$LocalTradingViewStrategyId interval=$LocalTradingViewIntervalCode days=$LocalTradingViewDays source=$LocalTradingViewSource"

$commonArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir)
$metadataText = Invoke-ReadOnlySmoke -Name "deployment-metadata" -ScriptName "smoke_live_deployment_metadata_ssh.ps1" -Arguments $commonArgs
$auditText = Invoke-ReadOnlySmoke -Name "live-readiness-audit-local-tradingview-scope" -ScriptName "audit_live_readiness_ssh.ps1" -Arguments ($commonArgs + @("-EnvFile", $EnvFile, "-Symbol", $Symbol))
$backgroundText = Invoke-ReadOnlySmoke -Name "live-background-automation-local-tradingview-scope" -ScriptName "smoke_live_background_automation_ssh.ps1" -Arguments ($commonArgs + @("-EnvFile", $EnvFile))
$candidateText = Invoke-ReadOnlySmoke -Name "local-tradingview-candidate" -ScriptName "smoke_local_tradingview_candidate_ssh.ps1" -Arguments ($commonArgs + @(
        "-EnvFile", $EnvFile,
        "-StrategyId", [string]$LocalTradingViewStrategyId,
        "-Symbol", $Symbol,
        "-IntervalCode", $LocalTradingViewIntervalCode,
        "-Days", [string]$LocalTradingViewDays,
        "-Source", $LocalTradingViewSource
    ))

$metadataStatus = Get-LastPrefixedValue -Text $metadataText -Prefix "deployment_metadata_status="
$originStatus = Get-LastPrefixedValue -Text $metadataText -Prefix "origin_metadata_status="
$metadataCurrent = Get-LastPrefixedValue -Text $metadataText -Prefix "metadata_current="
$worktreeCommit = Get-LastPrefixedValue -Text $metadataText -Prefix "worktreeCommit="
$originMainCommit = Get-LastPrefixedValue -Text $metadataText -Prefix "originMainCommit="
$originDeltaFiles = @()
$metadataEffectiveStatus = $metadataStatus
$metadataEffectiveCurrent = $metadataCurrent
$docsToolingOnlyOriginDrift = $false
if ($metadataStatus -eq "CURRENT" -and $originStatus -ne "CURRENT_ORIGIN_MAIN" -and $metadataCurrent -ne "true") {
    $originDeltaFiles = @(Get-LocalGitDiffFiles -BaseCommit $worktreeCommit -HeadCommit $originMainCommit)
    $runtimeDeltaFiles = @($originDeltaFiles | Where-Object { -not (Test-DocsToolingOnlyPath -Path $_) })
    if ($originDeltaFiles.Count -gt 0 -and $runtimeDeltaFiles.Count -eq 0) {
        $metadataEffectiveStatus = "DOCS_TOOLING_ONLY_DRIFT"
        $metadataEffectiveCurrent = "true"
        $docsToolingOnlyOriginDrift = $true
    }
}
$auditVerdict = Get-LastPrefixedValue -Text $auditText -Prefix "verdict="
$riskLevel = Get-LastPrefixedValue -Text $auditText -Prefix "riskLevel="
$runtimeLogStatus = Get-LastPrefixedValue -Text $auditText -Prefix "runtime_log_status="
$liveMicroAuthorized = Get-LastPrefixedValue -Text $auditText -Prefix "local_tradingview_live_micro_authorized="
$unexpectedOrderFlagsText = Get-JsonArrayText -Text $auditText -Prefix "order_capable_flags_unexpected="
$acceptedOrderFlagsText = Get-JsonArrayText -Text $auditText -Prefix "order_capable_flags_accepted="
$backgroundClear = Get-LastPrefixedValue -Text $backgroundText -Prefix "backgroundAutomationClear="
$backgroundVerdict = Get-LastPrefixedValue -Text $backgroundText -Prefix "verdict="
$backgroundUnreviewedText = Get-JsonArrayText -Text $backgroundText -Prefix "background_automation_unreviewed_true="
$backgroundHighRiskText = Get-JsonArrayText -Text $backgroundText -Prefix "high_risk_background_automation_true="
$currentCandidateStatus = Get-LastPrefixedValue -Text $candidateText -Prefix "  currentCandidateStatus="
$dataEnd = Get-LastPrefixedValue -Text $candidateText -Prefix "  dataEnd="
$dataClose = Get-LastPrefixedValue -Text $candidateText -Prefix "  dataClose="
$lastOrderAt = Get-LastPrefixedValue -Text $candidateText -Prefix "  lastOrderAt="
$freshnessStatus = Get-LastPrefixedValue -Text $candidateText -Prefix "  freshnessStatus="
$coverage = Get-LastPrefixedValue -Text $candidateText -Prefix "  coverage="
$readiness = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewReadiness="
$executionMode = Get-LastPrefixedValue -Text $candidateText -Prefix "  executionMode="
$evaluatorActive = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewEvaluatorActive="
$executionPathArmed = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewExecutionPathArmed="
$dryRunArmed = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewExecutionDryRunArmed="
$liveMicroArmed = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewLiveMicroArmed="
$ocoTracked = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewOcoLifecycleTracked="
$ocoStatus = Get-LastPrefixedValue -Text $candidateText -Prefix "  localTradingViewOcoLifecycleStatus="
$candidateBlockersText = Get-JsonArrayText -Text $candidateText -Prefix "  local_tradingview_blockers="

$blockers = [System.Collections.Generic.List[string]]::new()
$healthWarnings = [System.Collections.Generic.List[string]]::new()
Add-If -List $blockers -Condition ($metadataEffectiveStatus -notin @("CURRENT", "DOCS_TOOLING_ONLY_DRIFT") -or $metadataEffectiveCurrent -ne "true") -Value "DEPLOYED_RUNTIME_NOT_CURRENT"
Add-If -List $healthWarnings -Condition $docsToolingOnlyOriginDrift -Value "DOCS_TOOLING_ONLY_DRIFT_NOT_DEPLOYED"
Add-If -List $healthWarnings -Condition ($runtimeLogStatus -ne "PASS") -Value "RUNTIME_LOG_NOT_CLEAN"
Add-If -List $healthWarnings -Condition ($riskLevel -ne "R0") -Value "EVENT_RISK_NOT_BASELINE"
Add-If -List $blockers -Condition (-not (Test-JsonArrayTextEmpty -Value $unexpectedOrderFlagsText)) -Value "ORDER_CAPABLE_FLAGS_REVIEW"
Add-If -List $blockers -Condition ($backgroundClear -ne "true" -or $backgroundVerdict -notin @("OK_BACKGROUND_AUTOMATION_DISABLED", "OK_BACKGROUND_AUTOMATION_REVIEWED")) -Value "BACKGROUND_AUTOMATION_REVIEW"
Add-If -List $blockers -Condition (-not (Test-JsonArrayTextEmpty -Value $backgroundUnreviewedText)) -Value "BACKGROUND_AUTOMATION_REVIEW"
Add-If -List $blockers -Condition (-not (Test-JsonArrayTextEmpty -Value $backgroundHighRiskText)) -Value "BACKGROUND_AUTOMATION_REVIEW"
Add-If -List $blockers -Condition ($evaluatorActive -ne "true") -Value "LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE"
Add-If -List $blockers -Condition ($executionPathArmed -ne "true") -Value "LOCAL_TRADINGVIEW_EXECUTION_NOT_ARMED"
Add-If -List $blockers -Condition ($executionMode -eq "LIVE_MICRO" -and $liveMicroAuthorized -ne "true") -Value "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ACCEPTED"
Add-If -List $blockers -Condition ($executionMode -eq "LIVE_MICRO" -and $liveMicroArmed -ne "true") -Value "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED"
Add-If -List $blockers -Condition ($ocoTracked -ne "true") -Value "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED"
Add-If -List $blockers -Condition ($coverage -notin @("OK", "WARN")) -Value "LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK"
if ([string]::IsNullOrWhiteSpace($candidateBlockersText)) {
    Add-If -List $blockers -Condition $true -Value "LOCAL_TRADINGVIEW_CANDIDATE_BLOCKERS_MISSING"
} elseif (-not (Test-JsonArrayTextEmpty -Value $candidateBlockersText)) {
    foreach ($candidateBlocker in @($candidateBlockersText | ConvertFrom-Json -ErrorAction Stop)) {
        Add-If -List $blockers -Condition ($candidateBlocker -ne "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE") -Value ([string]$candidateBlocker)
    }
}
Add-If -List $blockers -Condition ($currentCandidateStatus -ne "HAS_CURRENT_BUY_CANDIDATE") -Value "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE"

$blockingWithoutCandidate = @($blockers | Where-Object { $_ -ne "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE" })
if ($blockingWithoutCandidate.Count -gt 0) {
    $status = "BLOCKED"
    $nextAction = "Fix LOCAL_TRADINGVIEW-only blockers before treating the next TradingView parity BUY as executable."
} elseif ($currentCandidateStatus -eq "HAS_CURRENT_BUY_CANDIDATE") {
    $status = "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED"
    $nextAction = "A current LOCAL_TRADINGVIEW parity BUY candidate is present and the LOCAL_TRADINGVIEW live-micro path is armed; inspect execution/audit rows immediately after the live evaluator tick. This smoke itself remains read-only."
} else {
    $status = "WAIT_BUY"
    $nextAction = "Wait for the latest closed bar to emit a LOCAL_TRADINGVIEW parity BUY, then rerun this LOCAL_TRADINGVIEW-only smoke."
}

Write-Host ""
Write-Host "[local-tradingview-only-readiness] summary"
Write-Host "deployment_metadata_status=$metadataStatus"
Write-Host "origin_metadata_status=$originStatus"
Write-Host "metadata_current=$metadataCurrent"
Write-Host "deployment_metadata_effective_status=$metadataEffectiveStatus"
Write-Host "metadata_effective_current=$metadataEffectiveCurrent"
Write-Host ("origin_delta_files=" + (ConvertTo-Json -Compress @($originDeltaFiles)))
Write-Host "audit_verdict=$auditVerdict"
Write-Host "riskLevel=$riskLevel"
Write-Host "runtime_log_status=$runtimeLogStatus"
Write-Host "order_capable_flags_accepted=$acceptedOrderFlagsText"
Write-Host "order_capable_flags_unexpected=$unexpectedOrderFlagsText"
Write-Host "backgroundAutomationClear=$backgroundClear"
Write-Host "background_verdict=$backgroundVerdict"
Write-Host "background_automation_unreviewed_true=$backgroundUnreviewedText"
Write-Host "local_tradingview_current_candidate_status=$currentCandidateStatus"
Write-Host "local_tradingview_data_end=$dataEnd"
Write-Host "local_tradingview_data_close=$dataClose"
Write-Host "local_tradingview_last_order_at=$lastOrderAt"
Write-Host "local_tradingview_freshness_status=$freshnessStatus"
Write-Host "local_tradingview_coverage=$coverage"
Write-Host "local_tradingview_readiness=$readiness"
Write-Host "local_tradingview_execution_mode=$executionMode"
Write-Host "local_tradingview_evaluator_active=$evaluatorActive"
Write-Host "local_tradingview_dry_run_armed=$dryRunArmed"
Write-Host "local_tradingview_live_micro_armed=$liveMicroArmed"
Write-Host "local_tradingview_execution_path_armed=$executionPathArmed"
Write-Host "local_tradingview_oco_lifecycle_tracked=$ocoTracked"
Write-Host "local_tradingview_oco_lifecycle_status=$ocoStatus"
Write-Host "local_tradingview_only_status=$status"
Write-Host ("local_tradingview_only_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host ("local_tradingview_only_health_warnings=" + (ConvertTo-Json -Compress @($healthWarnings)))
Write-Host "local_tradingview_only_legacy_blockers_excluded=true"
Write-Host "local_tradingview_only_verdict=$status"
Write-Host "next_action=$nextAction"
Write-Host "notAuthorization=read-only LOCAL_TRADINGVIEW-only readiness evidence; does not authorize production env mutation, live trading policy changes, manual order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB writes, scheduler changes, deploy, restart, or external backfill/import"
Write-Host "[local-tradingview-only-readiness] read-only check complete"

if ($RequireCurrentCandidate -and $currentCandidateStatus -ne "HAS_CURRENT_BUY_CANDIDATE") {
    throw "LOCAL_TRADINGVIEW current BUY candidate is required but not present."
}
if ($RequireReady -and $status -ne "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED") {
    throw "LOCAL_TRADINGVIEW-only readiness is not ready: status=$status blockers=$((ConvertTo-Json -Compress @($blockers)))"
}
