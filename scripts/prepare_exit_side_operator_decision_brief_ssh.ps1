param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
    [switch]$RequireDecisionReady
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
        throw "$Name contains unsupported characters for exit-side decision brief arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for exit-side operator decision brief."
    }

    Write-Host "[exit-side-operator-decision-brief] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[exit-side-operator-decision-brief] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
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
if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$exitPacketResult = Invoke-ReadOnlyScript -ScriptName "prepare_exit_side_profit_review_packet_ssh.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit",
    "-RequireReady"
)

$exitStatus = Get-LastPrefixedValue -Text $exitPacketResult.Text -Prefix "exit_side_profit_review_packet_status="
$exitPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $exitPacketResult.Text -Prefix "exit_side_profit_review_packet=")
$missingRequirements = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $exitPacketResult.Text -Prefix "exit_side_profit_review_missing_requirements=")

$trailingAcceptance = if ($null -ne $exitPacket) { [string]$exitPacket.trailingAcceptance } else { "N/A" }
$trailingImprovementPct = if ($null -ne $exitPacket) { [string]$exitPacket.trailingImprovementPct } else { "N/A" }
$trailingDeltaPnl = if ($null -ne $exitPacket) { [string]$exitPacket.trailingDeltaPnl } else { "N/A" }
$ocoHealthOk = if ($null -ne $exitPacket) { [string]$exitPacket.strategy485OcoHealthOk } else { "N/A" }
$negativeEvCount = if ($null -ne $exitPacket) { [string]$exitPacket.strategy485NegativeEvPositionCount } else { "N/A" }
$closeOrModifyCount = if ($null -ne $exitPacket) { [string]$exitPacket.strategy485CloseOrModifySuggestionCount } else { "N/A" }

$recommendations = [System.Collections.Generic.List[object]]::new()
$recommendations.Add([pscustomobject]@{
    candidate = "TRAILING_STOP_EXIT_POLICY_REVIEW"
    status = if ($trailingAcceptance -eq "PASS") { "REVIEW_READY_NOT_LIVE" } else { "NOT_READY" }
    evidence = @("trailing_stop_acceptance=$trailingAcceptance", "trailing_stop_improvement_pct=$trailingImprovementPct", "trailing_stop_delta_pnl=$trailingDeltaPnl")
    reviewQuestion = "Should trailing-stop exit policy be reviewed for a separately authorized dry-run/live rollout plan?"
    nextAction = "Review acceptance evidence and scheduler/strategy opt-in scope separately; do not enable trailing from this brief."
})
$recommendations.Add([pscustomobject]@{
    candidate = "STRATEGY485_AGED_NEGATIVE_EV_POSITION_REVIEW"
    status = if ($negativeEvCount -ne "N/A" -and $negativeEvCount -ne "0") { "REVIEW_READY_NOT_MUTATION" } else { "WATCH_ONLY" }
    evidence = @("strategy485_oco_health_ok=$ocoHealthOk", "strategy485_negative_ev_position_count=$negativeEvCount", "strategy485_close_or_modify_suggestion_count=$closeOrModifyCount")
    reviewQuestion = "Should aged negative-EV strategy 485 positions be reviewed under a separate risk-reduction authorization path?"
    nextAction = "Review OCO health, EV, timeout, and TP-stretch evidence separately; do not close positions or modify OCO from this brief."
})

$decisionStatus = "NOT_READY"
$primaryRecommendation = "COLLECT_EXIT_SIDE_EVIDENCE"
if ($exitPacketResult.ExitCode -ne 0 -or $null -eq $exitPacket) {
    $decisionStatus = "NO_EVIDENCE"
    $primaryRecommendation = "FIX_EXIT_SIDE_PACKET_COLLECTION"
} elseif ($exitStatus -eq "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION") {
    $decisionStatus = "READY_FOR_OPERATOR_DECISION_NOT_MUTATION"
    $primaryRecommendation = "PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW"
}

$brief = [pscustomobject]@{
    packetType = "EXIT_SIDE_OPERATOR_DECISION_BRIEF"
    status = $decisionStatus
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_exit_side_profit_review_packet_ssh.ps1"
    sourcePacketExitCode = $exitPacketResult.ExitCode
    exitSidePacketStatus = $exitStatus
    primaryRecommendation = $primaryRecommendation
    reviewRecommendations = @($recommendations)
    separateAuthorizationsRequired = @(
        "enable trailing scheduler or trailing live mode",
        "change strategy opt-in or exit policy",
        "close any strategy 485 position",
        "modify or cancel OCO",
        "change production env or deploy runtime changes"
    )
    doNotActions = @(
        "do not enable live trading from this brief",
        "do not enable trailing scheduler from this brief",
        "do not close positions from this brief",
        "do not modify OCO from this brief",
        "do not relax EntryDedup/DataFreshness/live policy from this brief",
        "do not deploy or change production env from this brief"
    )
    evidenceSummary = @{
        trailingStopAcceptance = $trailingAcceptance
        trailingStopImprovementPct = $trailingImprovementPct
        trailingStopDeltaPnl = $trailingDeltaPnl
        strategy485OcoHealthOk = $ocoHealthOk
        strategy485NegativeEvPositionCount = $negativeEvCount
        strategy485CloseOrModifySuggestionCount = $closeOrModifyCount
    }
    missingRequirements = @($missingRequirements)
    sourceExitSidePacket = $exitPacket
    nextAction = if ($decisionStatus -eq "READY_FOR_OPERATOR_DECISION_NOT_MUTATION") { "Attach this decision brief and the exit-side packet to a separate operator review; keep every mutation behind separate explicit authorization." } else { "Resolve missing exit-side evidence and rerun this brief." }
    notAuthorization = "read-only exit-side operator decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
}

Write-Host "[exit-side-operator-decision-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes prepare_exit_side_profit_review_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_exit_side_profit_review_packet_ssh.ps1 exitCode=$($exitPacketResult.ExitCode)"
Write-Host "exit_side_profit_review_packet_status=$exitStatus"
Write-Host "exit_side_operator_primary_recommendation=$primaryRecommendation"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "strategy485_oco_health_ok=$ocoHealthOk"
Write-Host "strategy485_negative_ev_position_count=$negativeEvCount"
Write-Host "strategy485_close_or_modify_suggestion_count=$closeOrModifyCount"
Write-Host ("exit_side_operator_review_recommendations=" + (ConvertTo-Json -Compress -Depth 8 @($recommendations)))
Write-Host ("exit_side_operator_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 12 $brief))
Write-Host "exit_side_operator_decision_brief_status=$decisionStatus"
Write-Host "exit_side_operator_decision_next_action=$($brief.nextAction)"
Write-Host "notAuthorization=read-only exit-side operator decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
Write-Host "[exit-side-operator-decision-brief] read-only check complete"

if ($RequireDecisionReady -and $decisionStatus -ne "READY_FOR_OPERATOR_DECISION_NOT_MUTATION") {
    throw "Exit-side operator decision brief is not ready: status=$decisionStatus missing=$(@($missingRequirements) -join '; ')"
}
