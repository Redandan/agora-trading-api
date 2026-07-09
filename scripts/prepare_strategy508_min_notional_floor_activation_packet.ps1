param(
    [string]$EvidenceLogPath = "target/profit-review/strategy508-first-entry-readiness-current.log",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [decimal]$FloorMaxRiskUsdt = 6.25,
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Convert-DecimalOrNull {
    param([object]$Value)
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text) -or $text -eq "N/A") { return $null }
    $parsed = [decimal]0
    $style = [System.Globalization.NumberStyles]::Float
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ([decimal]::TryParse($text, $style, $culture, [ref]$parsed)) { return $parsed }
    return $null
}

function Convert-JsonStringArray {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return @() }
    try {
        $parsed = $Value | ConvertFrom-Json -ErrorAction Stop
        $rows = [System.Collections.Generic.List[string]]::new()
        foreach ($item in @($parsed)) {
            if ($null -ne $item -and -not [string]::IsNullOrWhiteSpace([string]$item)) {
                $rows.Add([string]$item)
            }
        }
        return @($rows)
    } catch {
        return @()
    }
}

function Test-TrueValue {
    param([string]$Value)
    return $Value.Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

function Find-FirstDecimalPair {
    param([string]$Text, [string]$Pattern)
    $match = [System.Text.RegularExpressions.Regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) { return $null }
    [pscustomobject]@{
        First = Convert-DecimalOrNull $match.Groups[1].Value
        Second = Convert-DecimalOrNull $match.Groups[2].Value
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}
if ($FloorMaxRiskUsdt -le 0 -or $FloorMaxRiskUsdt -gt 1000) {
    throw "FloorMaxRiskUsdt must be between 0 and 1000."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

Assert-PathTokenSafe -Name "EvidenceLogPath" -Value $EvidenceLogPath
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$resolvedEvidencePath = Resolve-RepoPath -PathValue $EvidenceLogPath
if (-not (Test-Path -LiteralPath $resolvedEvidencePath)) {
    throw "Strategy 508 first-entry readiness evidence log not found: $resolvedEvidencePath"
}

$evidenceItem = Get-Item -LiteralPath $resolvedEvidencePath
$evidenceAgeMinutes = [math]::Round(((Get-Date) - $evidenceItem.LastWriteTime).TotalMinutes, 2)
$evidenceFresh = $evidenceAgeMinutes -le $MaxAgeMinutes
$evidenceText = Get-Content -Raw -LiteralPath $resolvedEvidencePath

$signalSourceGate = Get-LastPrefixedValue -Text $evidenceText -Prefix "strategy508_signal_source_gate=" -Default "UNKNOWN"
$entryDedupPassText = Get-LastPrefixedValue -Text $evidenceText -Prefix "entry_dedup_first_entry_pass=" -Default "false"
$openPositionGate = Get-LastPrefixedValue -Text $evidenceText -Prefix "auto_trade_open_position_gate=" -Default "UNKNOWN"
$latestSignalStatus = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_signal_status=" -Default "UNKNOWN"
$latestFilterReason = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_signal_filter_reason=" -Default ""
$latestEvAppliesText = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_ev_gate_applies_to_latest_signal=" -Default "false"
$latestEvStatus = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_ev_gate_status=" -Default "UNKNOWN"
$floorEnabledText = Get-LastPrefixedValue -Text $evidenceText -Prefix "position_sizing_min_notional_floor_enabled=" -Default "false"
$floorMaxRiskText = Get-LastPrefixedValue -Text $evidenceText -Prefix "position_sizing_min_notional_floor_max_risk_usdt=" -Default ""
$sizingStatus = Get-LastPrefixedValue -Text $evidenceText -Prefix "first_entry_position_sizing_status=" -Default "UNKNOWN"
$sizingLinesJson = Get-LastPrefixedValue -Text $evidenceText -Prefix "first_entry_position_sizing_lines=" -Default "[]"
$blockersJson = Get-LastPrefixedValue -Text $evidenceText -Prefix "strategy508_first_entry_blockers=" -Default "[]"
$conclusion = Get-LastPrefixedValue -Text $evidenceText -Prefix "strategy508_first_entry_conclusion=" -Default "UNKNOWN"
$latestEntryText = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_signal_entry_price=" -Default ""
$latestSlText = Get-LastPrefixedValue -Text $evidenceText -Prefix "latest_signal_suggested_sl=" -Default ""

$entryDedupPass = Test-TrueValue $entryDedupPassText
$latestEvApplies = Test-TrueValue $latestEvAppliesText
$floorEnabled = Test-TrueValue $floorEnabledText
$configuredFloorMaxRisk = Convert-DecimalOrNull $floorMaxRiskText
$sizingLines = Convert-JsonStringArray $sizingLinesJson
$blockers = Convert-JsonStringArray $blockersJson
$allSizingEvidence = ($latestFilterReason + "`n" + ($sizingLines -join "`n") + "`n" + $sizingLinesJson)

$riskPair = Find-FirstDecimalPair -Text $allSizingEvidence -Pattern 'risk-sized notional\s+\$?([0-9]+(?:\.[0-9]+)?)\s+below min\s+\$?([0-9]+(?:\.[0-9]+)?)'
$rawRiskSizedUsdt = $null
$minNotionalUsdt = $null
if ($null -ne $riskPair) {
    $rawRiskSizedUsdt = $riskPair.First
    $minNotionalUsdt = $riskPair.Second
}

$slDistancePct = $null
$slMatch = [System.Text.RegularExpressions.Regex]::Match($allSizingEvidence, "slDistancePct=([0-9]+(?:\.[0-9]+)?)%", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
if ($slMatch.Success) {
    $slDistancePct = Convert-DecimalOrNull $slMatch.Groups[1].Value
}
if ($null -eq $slDistancePct) {
    $entryPrice = Convert-DecimalOrNull $latestEntryText
    $slPrice = Convert-DecimalOrNull $latestSlText
    if ($null -ne $entryPrice -and $entryPrice -gt 0 -and $null -ne $slPrice) {
        $slDistancePct = [math]::Round([decimal]([math]::Abs([double]($entryPrice - $slPrice)) * 100.0 / [double]$entryPrice), 4)
    }
}

$estimatedFloorRiskUsdt = $null
if ($null -ne $minNotionalUsdt -and $null -ne $slDistancePct) {
    $estimatedFloorRiskUsdt = [math]::Round([decimal]($minNotionalUsdt * $slDistancePct / 100), 4)
}

$allowedSizingBlockers = @("BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL", "LATEST_SIGNAL_BLOCKED_POSITION_SIZING")
$otherBlockers = @()
foreach ($blocker in @($blockers)) {
    if ($allowedSizingBlockers -notcontains $blocker) { $otherBlockers += $blocker }
}

$missing = [System.Collections.Generic.List[string]]::new()
if (-not $evidenceFresh) { Add-Missing -List $missing -Value "first-entry readiness evidence fresh within $MaxAgeMinutes minutes" }
if ($signalSourceGate -notmatch "ACTIVE_FOR_STRATEGY$") { Add-Missing -List $missing -Value "Strategy 508 signal source active" }
if (-not $entryDedupPass) { Add-Missing -List $missing -Value "EntryDedup first-entry gate pass" }
if ($openPositionGate -ne "PASS") { Add-Missing -List $missing -Value "AutoTrade open-position gate pass" }
if ($latestEvStatus -match "^BLOCK_" -and $latestEvApplies) { Add-Missing -List $missing -Value "latest EV gate is not an active blocker for latest signal" }
if ($floorEnabled) { Add-Missing -List $missing -Value "production min-notional floor currently disabled before activation" }
if ($null -eq $configuredFloorMaxRisk) {
    Add-Missing -List $missing -Value "configured min-notional floor max risk present"
} elseif ([math]::Abs([double]($configuredFloorMaxRisk - $FloorMaxRiskUsdt)) -gt 0.0001) {
    Add-Missing -List $missing -Value "configured min-notional floor max risk matches requested cap"
}
if ($sizingStatus -ne "BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL") { Add-Missing -List $missing -Value "position sizing blocker is risk-sized below min-notional" }
if ($latestSignalStatus -ne "LATEST_SIGNAL_BLOCKED_POSITION_SIZING") { Add-Missing -List $missing -Value "latest signal blocked only at position sizing" }
if ($blockers -notcontains "BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL") { Add-Missing -List $missing -Value "first-entry blockers include min-notional sizing blocker" }
if ($blockers -notcontains "LATEST_SIGNAL_BLOCKED_POSITION_SIZING") { Add-Missing -List $missing -Value "first-entry blockers include latest signal position-sizing blocker" }
if (@($otherBlockers).Count -gt 0) { Add-Missing -List $missing -Value "no non-sizing first-entry blockers remain" }
if ($conclusion -ne "FIRST_ENTRY_BLOCKED_REVIEW_REQUIRED") { Add-Missing -List $missing -Value "first-entry conclusion is blocked review required" }
if ($null -eq $rawRiskSizedUsdt -or $rawRiskSizedUsdt -le 0) { Add-Missing -List $missing -Value "raw risk-sized notional parsed from evidence" }
if ($null -eq $minNotionalUsdt -or $minNotionalUsdt -le 0) { Add-Missing -List $missing -Value "min notional parsed from evidence" }
if ($null -ne $rawRiskSizedUsdt -and $null -ne $minNotionalUsdt -and $rawRiskSizedUsdt -ge $minNotionalUsdt) {
    Add-Missing -List $missing -Value "raw risk-sized notional is below min notional"
}
if ($null -eq $slDistancePct -or $slDistancePct -le 0) { Add-Missing -List $missing -Value "SL distance parsed from sizing evidence" }
if ($null -eq $estimatedFloorRiskUsdt) {
    Add-Missing -List $missing -Value "floor-sized SL risk can be estimated"
} elseif ($estimatedFloorRiskUsdt -gt $FloorMaxRiskUsdt) {
    Add-Missing -List $missing -Value "floor-sized SL risk <= max risk cap"
}

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_REQUIREMENTS_MISSING"
}
$decision = if ($ready) {
    "PRESENT_EXACT_DEPLOY_ENV_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
} else {
    "REFRESH_STRATEGY508_FIRST_ENTRY_READINESS_BEFORE_AUTHORIZATION"
}

$envDiff = @(
    "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=true",
    ("TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT={0:N2}" -f $FloorMaxRiskUsdt)
)
$rollbackEnvDiff = @(
    "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=false"
)
$exactAuthorizationText = "I explicitly authorize deploying the Strategy 508 min-notional floor code and setting TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=true and TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT=$($FloorMaxRiskUsdt.ToString('0.##')) for agora-trading-api production, then restarting and running read-only post-deploy checks. I understand this is a live sizing policy change that may turn future Strategy 508 BUY candidates from skip into a minimum-notional live order subject to remaining gates."
$rollbackAuthorizationText = "I explicitly authorize rolling back the Strategy 508 min-notional floor by setting TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=false for agora-trading-api production, then restarting and running read-only verification."

$packet = [ordered]@{
    packetType = "STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    evidenceLogPath = $EvidenceLogPath
    evidenceAgeMinutes = $evidenceAgeMinutes
    evidenceFresh = $evidenceFresh
    readinessEvidence = [ordered]@{
        signalSourceGate = $signalSourceGate
        entryDedupFirstEntryPass = $entryDedupPass
        autoTradeOpenPositionGate = $openPositionGate
        latestEvGateAppliesToLatestSignal = $latestEvApplies
        latestEvGateStatus = $latestEvStatus
        latestSignalStatus = $latestSignalStatus
        firstEntryPositionSizingStatus = $sizingStatus
        firstEntryConclusion = $conclusion
        firstEntryBlockers = @($blockers)
        otherBlockers = @($otherBlockers)
    }
    floorSizingEvidence = [ordered]@{
        rawRiskSizedUsdt = $rawRiskSizedUsdt
        minNotionalUsdt = $minNotionalUsdt
        slDistancePct = $slDistancePct
        estimatedFloorRiskUsdt = $estimatedFloorRiskUsdt
        floorMaxRiskUsdt = $FloorMaxRiskUsdt
        configuredFloorEnabled = $floorEnabled
        configuredFloorMaxRiskUsdt = $configuredFloorMaxRisk
    }
    proposedEnvDiff = @($envDiff)
    rollbackEnvDiff = @($rollbackEnvDiff)
    exactAuthorizationText = $exactAuthorizationText
    rollbackAuthorizationText = $rollbackAuthorizationText
    preDeployReadOnlyChecks = @(
        ".\scripts\verify_local.ps1",
        ".\scripts\smoke_strategy508_first_entry_readiness_ssh.ps1 | Tee-Object target/profit-review/strategy508-first-entry-readiness-current.log",
        ".\scripts\prepare_strategy508_min_notional_floor_activation_packet.ps1 -RequireReady"
    )
    postDeployReadOnlyChecks = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_strategy508_first_entry_readiness_ssh.ps1",
        "Confirm first_entry_position_sizing_status=PASS_MIN_NOTIONAL_FLOOR_APPLIED for the same latest candidate, or document that no fresh 508 candidate exists before expecting a live order."
    )
    missingRequirements = @($missing)
    activationAuthorizationReviewReady = $ready
    activationExecutionAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    restartAllowed = $false
    livePolicyChangeAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbMutationAllowed = $false
    exchangeMutationAllowed = $false
    nextAction = if ($ready) { "Present exactAuthorizationText to the operator. This packet does not deploy, edit env, restart, or trade." } else { "Refresh Strategy 508 first-entry readiness evidence and rerun this packet before requesting production activation authorization." }
    notAuthorization = "read-only Strategy 508 min-notional floor activation packet only; prepares exact operator text but does not deploy, change production env, restart, enable live policy, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[strategy508-min-notional-floor-activation-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved Strategy 508 first-entry readiness evidence only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host ("strategy508_min_notional_floor_activation_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "strategy508_min_notional_floor_activation_status=$status"
Write-Host "strategy508_min_notional_floor_activation_decision=$decision"
Write-Host "strategy508_min_notional_floor_activation_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "strategy508_min_notional_floor_signal_source_gate=$signalSourceGate"
Write-Host "strategy508_min_notional_floor_entry_dedup_first_entry_pass=$($entryDedupPass.ToString().ToLowerInvariant())"
Write-Host "strategy508_min_notional_floor_auto_trade_open_position_gate=$openPositionGate"
Write-Host "strategy508_min_notional_floor_latest_ev_gate_status=$latestEvStatus"
Write-Host "strategy508_min_notional_floor_first_entry_position_sizing_status=$sizingStatus"
Write-Host "strategy508_min_notional_floor_raw_risk_sized_usdt=$rawRiskSizedUsdt"
Write-Host "strategy508_min_notional_floor_min_notional_usdt=$minNotionalUsdt"
Write-Host "strategy508_min_notional_floor_sl_distance_pct=$slDistancePct"
Write-Host "strategy508_min_notional_floor_estimated_floor_risk_usdt=$estimatedFloorRiskUsdt"
Write-Host "strategy508_min_notional_floor_max_risk_usdt=$FloorMaxRiskUsdt"
Write-Host ("strategy508_min_notional_floor_activation_env_diff=" + ($envDiff -join ";"))
Write-Host ("strategy508_min_notional_floor_rollback_env_diff=" + ($rollbackEnvDiff -join ";"))
Write-Host "strategy508_min_notional_floor_activation_authorization_text=$exactAuthorizationText"
Write-Host ("strategy508_min_notional_floor_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host "activation_execution_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "restart_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[strategy508-min-notional-floor-activation-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy 508 min-notional floor activation packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
