param(
    [string]$EvidenceLogPath = "target/profit-review/strategy508-tradeplan-skip-forward-monitor.tsv",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [decimal]$ProposedMinRiskReward = 0.49,
    [decimal]$ProposedMaxStopLossPct = 0.121,
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

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Convert-DecimalOrNull {
    param([object]$Value)
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text) -or $text -eq "NULL") { return $null }
    $parsed = [decimal]0
    $style = [System.Globalization.NumberStyles]::Float
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ([decimal]::TryParse($text, $style, $culture, [ref]$parsed)) { return $parsed }
    return $null
}

function Parse-ForwardRows {
    param([string]$Text)
    $rows = [System.Collections.Generic.List[object]]::new()
    $lines = @($Text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $header = $null
    foreach ($line in $lines) {
        $normalizedLine = $line.TrimStart([char]0xFEFF)
        if ($normalizedLine.StartsWith("audit_id`t")) {
            $header = $normalizedLine.Split([char]9)
            continue
        }
        if ($null -eq $header) { continue }
        if ($normalizedLine.StartsWith("section`t") -or $normalizedLine -eq "section") { continue }
        $cols = $normalizedLine.Split([char]9)
        if ($cols.Count -lt $header.Count) { continue }
        $map = @{}
        for ($i = 0; $i -lt $header.Count; $i++) {
            $map[$header[$i]] = $cols[$i]
        }
        if ($map["audit_id"] -notmatch "^[0-9]+$") {
            continue
        }
        $rows.Add([pscustomobject]@{
            auditId = $map["audit_id"]
            eventTimeUtc = $map["event_time_utc"]
            entry = Convert-DecimalOrNull $map["entry"]
            tp = Convert-DecimalOrNull $map["tp"]
            sl = Convert-DecimalOrNull $map["sl"]
            tpPct = Convert-DecimalOrNull $map["tp_pct"]
            slPctActual = Convert-DecimalOrNull $map["sl_pct_actual"]
            riskReward = Convert-DecimalOrNull $map["rr"]
            minRiskReward = Convert-DecimalOrNull $map["min_rr"]
            maxStopLossPct = Convert-DecimalOrNull $map["max_sl_pct"]
            forwardBars = Convert-DecimalOrNull $map["forward_bars"]
            forwardMaxUpPct = Convert-DecimalOrNull $map["forward_max_up_pct"]
            forwardMaxDownPct = Convert-DecimalOrNull $map["forward_max_down_pct"]
            latestForwardCloseRetPct = Convert-DecimalOrNull $map["latest_forward_close_ret_pct"]
            tpTouch = $map["tp_touch"]
            slTouch = $map["sl_touch"]
            reason = $map["reason"]
        })
    }
    return @($rows)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}
if ($ProposedMinRiskReward -lt 0.05 -or $ProposedMinRiskReward -gt 5.0) {
    throw "ProposedMinRiskReward must be between 0.05 and 5.0."
}
if ($ProposedMaxStopLossPct -lt 0.005 -or $ProposedMaxStopLossPct -gt 0.50) {
    throw "ProposedMaxStopLossPct must be between 0.005 and 0.50."
}

Assert-PathTokenSafe -Name "EvidenceLogPath" -Value $EvidenceLogPath
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$resolvedEvidencePath = Resolve-RepoPath -PathValue $EvidenceLogPath
if (-not (Test-Path -LiteralPath $resolvedEvidencePath)) {
    throw "Strategy 508 trade-plan quality evidence log not found: $resolvedEvidencePath"
}

$evidenceItem = Get-Item -LiteralPath $resolvedEvidencePath
$evidenceAgeMinutes = [math]::Round(((Get-Date) - $evidenceItem.LastWriteTime).TotalMinutes, 2)
$evidenceFresh = $evidenceAgeMinutes -le $MaxAgeMinutes
$evidenceText = Get-Content -Raw -LiteralPath $resolvedEvidencePath
$rows = @(Parse-ForwardRows -Text $evidenceText)

$missing = [System.Collections.Generic.List[string]]::new()
if (-not $evidenceFresh) { Add-Missing -List $missing -Value "trade-plan quality evidence fresh within $MaxAgeMinutes minutes" }
if ($rows.Count -lt 1) { Add-Missing -List $missing -Value "at least one Strategy 508 TradePlanQualityGate skip row" }
if ($ProposedMinRiskReward -lt 0.45) { Add-Missing -List $missing -Value "proposed min R:R keeps a narrow >=0.45 floor" }
if ($ProposedMaxStopLossPct -gt 0.15) { Add-Missing -List $missing -Value "proposed max SL remains <=15% narrow disaster-stop cap" }

$maxObservedSlPct = $null
$minObservedRiskReward = $null
$reviewableRows = 0
foreach ($row in $rows) {
    if ($null -ne $row.slPctActual) {
        $slDecimal = [decimal]$row.slPctActual / 100
        if ($null -eq $maxObservedSlPct -or $slDecimal -gt $maxObservedSlPct) { $maxObservedSlPct = $slDecimal }
    }
    if ($null -ne $row.riskReward) {
        if ($null -eq $minObservedRiskReward -or $row.riskReward -lt $minObservedRiskReward) {
            $minObservedRiskReward = $row.riskReward
        }
    }
    if ($null -ne $row.riskReward -and $null -ne $row.slPctActual) {
        $reviewableRows++
    }
}

if ($reviewableRows -lt 1) {
    Add-Missing -List $missing -Value "review rows include parsed RR and SL percent"
}
if ($null -ne $minObservedRiskReward -and $minObservedRiskReward -lt $ProposedMinRiskReward) {
    Add-Missing -List $missing -Value "proposed min R:R allows the reviewed Strategy 508 +6/-12 samples"
}
if ($null -ne $maxObservedSlPct -and $maxObservedSlPct -gt $ProposedMaxStopLossPct) {
    Add-Missing -List $missing -Value "proposed max SL allows the reviewed Strategy 508 disaster-stop samples"
}

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_STRATEGY508_TRADE_PLAN_QUALITY_GATE_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_REQUIREMENTS_MISSING"
}
$decision = if ($ready) {
    "PRESENT_EXACT_SET_STRATEGY_FLAGS_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
} else {
    "REFRESH_STRATEGY508_TRADE_PLAN_QUALITY_EVIDENCE_BEFORE_AUTHORIZATION"
}

$notes = "Strategy 508 TradingView parity +6% TP / -12% disaster SL narrow TradePlanQualityGate override after read-only review"
$mcpCall = "setStrategyFlags(strategyId=$StrategyId, tradePlanQualityGateEnabled=true, tradePlanMinRiskReward=$($ProposedMinRiskReward.ToString('0.###')), tradePlanMaxStopLossPct=$($ProposedMaxStopLossPct.ToString('0.###')), notes='$notes')"
$rollbackCall = "setStrategyFlags(strategyId=$StrategyId, tradePlanQualityGateEnabled=true, tradePlanMinRiskReward=1.0, tradePlanMaxStopLossPct=0.08, notes='Rollback Strategy 508 TradePlanQualityGate to default RR>=1.0 and SL<=8% after review')"
$exactAuthorizationText = "I explicitly authorize updating Strategy 508 config_json through the trading MCP setStrategyFlags write path with tradePlanQualityGateEnabled=true, tradePlanMinRiskReward=$($ProposedMinRiskReward.ToString('0.###')), and tradePlanMaxStopLossPct=$($ProposedMaxStopLossPct.ToString('0.###')). I understand this is a Strategy 508-specific live strategy config change that may allow future +6% TP / -12% SL TradingView-parity BUY candidates to proceed to remaining AutoTrade gates; it does not authorize manual orders, EntryDedup/EV/OCO/grid relaxation, or global quality-gate disablement."

$packet = [ordered]@{
    packetType = "STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    evidenceLogPath = $EvidenceLogPath
    evidenceAgeMinutes = $evidenceAgeMinutes
    evidenceFresh = $evidenceFresh
    reviewedRows = @($rows)
    reviewedRowCount = $rows.Count
    reviewableRowCount = $reviewableRows
    observed = [ordered]@{
        minRiskReward = $minObservedRiskReward
        maxStopLossPct = $maxObservedSlPct
    }
    proposedConfigDiff = [ordered]@{
        tradePlanQualityGateEnabled = $true
        tradePlanMinRiskReward = $ProposedMinRiskReward
        tradePlanMaxStopLossPct = $ProposedMaxStopLossPct
    }
    rollbackConfigDiff = [ordered]@{
        tradePlanQualityGateEnabled = $true
        tradePlanMinRiskReward = 1.0
        tradePlanMaxStopLossPct = 0.08
    }
    exactMcpCall = $mcpCall
    rollbackMcpCall = $rollbackCall
    exactAuthorizationText = $exactAuthorizationText
    preWriteReadOnlyChecks = @(
        ".\scripts\smoke_strategy508_first_entry_readiness_ssh.ps1",
        ".\scripts\prepare_post_fix_strategy_monitoring_packet_ssh.ps1 -LocalTradingViewWatchMaxAttempts 1 -LocalTradingViewWatchSleepSeconds 0",
        ".\scripts\prepare_strategy508_trade_plan_quality_gate_review_packet.ps1 -RequireReady"
    )
    postWriteReadOnlyChecks = @(
        ".\scripts\smoke_strategy508_first_entry_readiness_ssh.ps1",
        ".\scripts\prepare_post_fix_strategy_monitoring_packet_ssh.ps1 -LocalTradingViewWatchMaxAttempts 1 -LocalTradingViewWatchSleepSeconds 0",
        "On the next fresh Strategy 508 BUY, confirm blocker is no longer TradePlanQualityGate before expecting a live order."
    )
    missingRequirements = @($missing)
    operatorReviewReady = $ready
    strategyConfigMutationAllowed = $false
    mcpWriteAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    restartAllowed = $false
    livePolicyChangeAllowed = $false
    entryDedupPolicyChangeAllowed = $false
    evPolicyChangeAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbMutationAllowed = $false
    exchangeMutationAllowed = $false
    nextAction = if ($ready) { "Present exactAuthorizationText and exactMcpCall to the operator. This packet itself does not write strategy config." } else { "Refresh Strategy 508 TradePlanQualityGate forward evidence and rerun this packet before requesting strategy config authorization." }
    notAuthorization = "read-only Strategy 508 TradePlanQualityGate review packet only; prepares exact MCP/config diff but does not call MCP write tools, mutate DB, change production env, deploy, restart, enable live policy, relax EntryDedup/EV/OCO/grid, place orders, send Telegram, or mutate exchange/fund/Earn state"
}

Write-Host "[strategy508-trade-plan-quality-gate-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved Strategy 508 TradePlanQualityGate evidence only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host ("strategy508_trade_plan_quality_gate_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "strategy508_trade_plan_quality_gate_review_status=$status"
Write-Host "strategy508_trade_plan_quality_gate_review_decision=$decision"
Write-Host "strategy508_trade_plan_quality_gate_operator_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "strategy508_trade_plan_quality_gate_reviewed_rows=$($rows.Count)"
Write-Host "strategy508_trade_plan_quality_gate_reviewable_rows=$reviewableRows"
Write-Host "strategy508_trade_plan_quality_gate_observed_min_rr=$minObservedRiskReward"
Write-Host "strategy508_trade_plan_quality_gate_observed_max_sl_pct=$maxObservedSlPct"
Write-Host "strategy508_trade_plan_quality_gate_proposed_min_rr=$ProposedMinRiskReward"
Write-Host "strategy508_trade_plan_quality_gate_proposed_max_sl_pct=$ProposedMaxStopLossPct"
Write-Host "strategy508_trade_plan_quality_gate_exact_mcp_call=$mcpCall"
Write-Host "strategy508_trade_plan_quality_gate_rollback_mcp_call=$rollbackCall"
Write-Host "strategy508_trade_plan_quality_gate_authorization_text=$exactAuthorizationText"
Write-Host ("strategy508_trade_plan_quality_gate_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host "strategy_config_mutation_allowed=false"
Write-Host "mcp_write_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "restart_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "ev_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[strategy508-trade-plan-quality-gate-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy 508 TradePlanQualityGate review packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
