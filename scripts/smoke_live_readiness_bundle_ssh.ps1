param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [string]$IntervalCode = "1h",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$TinyLiveDays = 30,
    [int]$SignalExecutionDays = 5,
    [int]$SignalBlockedDays = 7,
    [int]$SignalAccuracyDays = 14,
    [switch]$RequireReady
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

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}

if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) {
    throw "RuntimeEvidenceMinutes must be between 60 and 43200."
}

if ($TinyLiveDays -lt 1 -or $TinyLiveDays -gt 90 `
        -or $SignalExecutionDays -lt 1 -or $SignalExecutionDays -gt 90 `
        -or $SignalBlockedDays -lt 1 -or $SignalBlockedDays -gt 90 `
        -or $SignalAccuracyDays -lt 1 -or $SignalAccuracyDays -gt 90) {
    throw "TinyLiveDays and signal day windows must be between 1 and 90."
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
Assert-SmokeTokenSafe -Name "Side" -Value $Side -MaxLength 16
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$scriptDir = $PSScriptRoot

function Invoke-ReadOnlySmoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [hashtable]$Arguments
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    Write-Host ""
    Write-Host "===== BEGIN $Name ====="
    $output = & $scriptPath @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    Write-Host "===== END $Name exit=$exitCode ====="
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode"
    }
    return ($output -join "`n")
}

$common = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $AppDir
    EnvFile = $EnvFile
}

Write-Host "[live-readiness-bundle] read-only SSH smoke bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed."
Write-Host "symbol=$Symbol strategyId=$StrategyId side=$Side interval=$IntervalCode"

$audit = Invoke-ReadOnlySmoke -Name "live-readiness-audit" -ScriptName "audit_live_readiness_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
    })
$background = Invoke-ReadOnlySmoke -Name "live-background-automation" -ScriptName "smoke_live_background_automation_ssh.ps1" -Arguments $common
$runtimeEvidence = Invoke-ReadOnlySmoke -Name "runtime-evidence-rca" -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        StrategyId = $StrategyId
        Side = $Side
        Minutes = $RuntimeEvidenceMinutes
    })
$tinyLive = Invoke-ReadOnlySmoke -Name "tiny-live-loss-rca" -ScriptName "smoke_tiny_live_loss_rca_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        StrategyId = $StrategyId
        Side = $Side
        Days = $TinyLiveDays
    })
$signal = Invoke-ReadOnlySmoke -Name "signal-correctness" -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        ExecutionDays = $SignalExecutionDays
        BlockedDays = $SignalBlockedDays
        AccuracyDays = $SignalAccuracyDays
    })
$mcpParity = Invoke-ReadOnlySmoke -Name "mcp-parity" -ScriptName "smoke_mcp_parity_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        IntervalCode = $IntervalCode
    })

$blockers = [System.Collections.Generic.List[string]]::new()
if ($audit -match "verdict=NOT_READY") {
    $blockers.Add("LIVE_READINESS_NOT_READY")
}
if ($background -match "HIGH_RISK_BACKGROUND_AUTOMATION_TRUE" -or $background -match "NOT_READY_BACKGROUND_AUTOMATION_REVIEW") {
    $blockers.Add("BACKGROUND_AUTOMATION_REVIEW")
}
if ($runtimeEvidence -match "diagnosis=CONFIG_DISABLED") {
    $blockers.Add("RUNTIME_EVIDENCE_CONFIG_DISABLED")
}
if ($runtimeEvidence -match "shadowIntentCount=0") {
    $blockers.Add("RUNTIME_EVIDENCE_NO_SHADOW_INTENT")
}
if ($tinyLive -match "hardStopDetected=true" -or $tinyLive -match "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES") {
    $blockers.Add("TINY_LIVE_LOSS_HARD_STOP")
}
if ($signal -match "REVIEW_POLICY_GAPS") {
    $blockers.Add("SIGNAL_POLICY_REVIEW_GAPS")
}
if ($mcpParity -notmatch "\[mcp-parity-ssh\] OK") {
    $blockers.Add("MCP_PARITY_NOT_PROVEN")
}

$uniqueBlockers = @($blockers | Select-Object -Unique)
Write-Host ""
Write-Host "[live-readiness-bundle] summary"
Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress $uniqueBlockers))
if ($uniqueBlockers.Count -eq 0) {
    Write-Host "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
} else {
    Write-Host "bundle_verdict=NOT_READY"
    Write-Host "next_action=Do not enable live; address or separately authorize the listed blockers, then rerun this bundle."
    if ($RequireReady) {
        throw "Live readiness bundle is not ready: $($uniqueBlockers -join ', ')"
    }
}
Write-Host "[live-readiness-bundle] read-only check complete"
