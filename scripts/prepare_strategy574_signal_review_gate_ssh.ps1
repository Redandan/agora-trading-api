param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Get-LastPrefixedValue {
    param(
        [string]$Text,
        [string]$Prefix
    )
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Invoke-LocalSmoke {
    param(
        [string]$ScriptName,
        [string[]]$Arguments
    )

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy 574 gate."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [PSCustomObject]@{
        Text = ($output | Out-String)
        ExitCode = $exitCode
    }
}

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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "Side" -Value $Side

$origin = Invoke-LocalSmoke -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)
$governance = Invoke-LocalSmoke -ScriptName "smoke_strategy574_signal_governance_ssh.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$StrategyId,
    "-Side", $Side
)

$originDelta = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_delta_status="
$nearBuy = Get-LastPrefixedValue -Text $governance.Text -Prefix "  strategy574_near_buy="
$governanceTooStrict = Get-LastPrefixedValue -Text $governance.Text -Prefix "  governance_too_strict_7d_or_14d="
$shortWindowInsufficient = Get-LastPrefixedValue -Text $governance.Text -Prefix "  short_window_insufficient_data="
$dataFreshnessClean = Get-LastPrefixedValue -Text $governance.Text -Prefix "  data_freshness_current_clean="
$terminalReason = Get-LastPrefixedValue -Text $governance.Text -Prefix "  strategy574_terminal_reason="
$policyRecommendation = Get-LastPrefixedValue -Text $governance.Text -Prefix "  policy_change_recommendation="

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($origin.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "origin-delta classifier completed"
}
if ($governance.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy 574 governance smoke completed"
}
if ($originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($dataFreshnessClean -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "current DataFreshness clean"
}
if ($nearBuy -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "current strategy 574 near-BUY evidence"
}
if ($terminalReason -ne "WAIT_BUY_THRESHOLD_CROSS") {
    Add-MissingRequirement -List $missingRequirements -Value "wait for threshold-cross evidence"
}
if ($policyRecommendation -ne "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    Add-MissingRequirement -List $missingRequirements -Value "hard-gates-preserved observation recommendation"
}
foreach ($required in @(
        "current BUY candidate",
        "OCO preflight pass",
        "EV pass sample",
        "post-trade OCO protection evidence"
    )) {
    Add-MissingRequirement -List $missingRequirements -Value $required
}

$deployRequired = $originDelta -eq "RUNTIME_DRIFT"
$shadowObservationAllowed = $false
$gateStatus = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun this gate."
if ($origin.ExitCode -ne 0 -or $governance.ExitCode -ne 0) {
    $gateStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only SSH smoke collection before drawing a strategy 574 conclusion."
} elseif ($deployRequired) {
    $gateStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun strategy 574 signal review gate."
} elseif ($policyRecommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS" -and $terminalReason -eq "WAIT_BUY_THRESHOLD_CROSS") {
    $shadowObservationAllowed = $true
    $gateStatus = "READY_FOR_OBSERVATION_REVIEW_NOT_ORDER"
    $nextAction = "Continue high-frequency read-only observation until BUY threshold and hard gates pass; do not pre-buy."
} elseif ($terminalReason -eq "FIX_CURRENT_DATA_FRESHNESS_FIRST") {
    $gateStatus = "BLOCKED_FIX_CURRENT_DATA_FRESHNESS"
    $nextAction = "Fix current DataFreshness before reviewing strategy 574 exploration."
} else {
    $gateStatus = "WAIT_BUY_THRESHOLD_CROSS"
    $nextAction = "Keep hard gates and continue read-only observation."
}

Write-Host "[strategy574-signal-review-gate] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs origin-delta classifier and strategy 574 signal-governance smoke only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_strategy574_signal_governance_ssh.ps1"
Write-Host "origin_delta_classifier=smoke_live_origin_delta_local.ps1"
Write-Host "origin_delta_exit_code=$($origin.ExitCode)"
Write-Host "strategy574_governance_exit_code=$($governance.ExitCode)"
Write-Host "origin_delta_status=$originDelta"
Write-Host "strategyId=$StrategyId"
Write-Host "symbol=$Symbol"
Write-Host "side=$Side"
Write-Host "strategy574_near_buy=$nearBuy"
Write-Host "governance_too_strict_7d_or_14d=$governanceTooStrict"
Write-Host "short_window_insufficient_data=$shortWindowInsufficient"
Write-Host "data_freshness_current_clean=$dataFreshnessClean"
Write-Host "strategy574_terminal_reason=$terminalReason"
Write-Host "strategy574_policy_change_recommendation=$policyRecommendation"
Write-Host "deploy_required_before_strategy574_review=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "shadow_observation_review_allowed=$($shadowObservationAllowed.ToString().ToLowerInvariant())"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host ("strategy574_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "strategy574_signal_review_gate_status=$gateStatus"
Write-Host "strategy574_signal_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only gate only; does not authorize pre-buying, TinyLive order execution, DataFreshnessGuard relaxation, EntryDedup relaxation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[strategy574-signal-review-gate] read-only check complete"

if ($RequireReady -and -not $shadowObservationAllowed) {
    throw "Strategy 574 signal review gate is not ready: $gateStatus; missing=$(@($missingRequirements) -join '; ')"
}
