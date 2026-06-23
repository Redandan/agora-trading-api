param(
    [string]$Strategy574GateLogPath = "target/profit-review/strategy574-signal-review-gate-refresh.log",
    [string]$TinyLiveLossRcaLogPath = "target/profit-review/tiny-live-loss-rca-refresh.log",
    [string]$NearThresholdShadowObservationLogPath = "target/profit-review/strategy574-near-threshold-shadow-observation-latest.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [string]$Side = "LONG",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return "" }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Get-LastRegexValue {
    param([string]$Text, [string]$Pattern)
    $matches = [regex]::Matches($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-LogTextAndFreshness {
    param([string]$PathValue, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { throw "$Label path is required." }
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) { throw "$Label log not found: $resolved" }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    return [pscustomobject]@{
        Path = $resolved
        AgeMinutes = $age
        Freshness = if ($age -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function ConvertFrom-JsonArrayOrMarker {
    param(
        [string]$Raw,
        [string]$ParseFailureMarker
    )
    if ([string]::IsNullOrWhiteSpace($Raw) -or $Raw.Trim() -eq "[]") {
        return @()
    }
    try {
        $parsed = $Raw | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $parsed) { return @() }
        return @($parsed)
    } catch {
        return @($ParseFailureMarker)
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for strategy574 TinyLive governance packet arguments."
}
if ([string]::IsNullOrWhiteSpace($Side) -or $Side.Length -gt 32 -or $Side -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Side contains unsupported characters for strategy574 TinyLive governance packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$strategyLog = Get-LogTextAndFreshness -PathValue $Strategy574GateLogPath -Label "Strategy574 gate"
$tinyLog = Get-LogTextAndFreshness -PathValue $TinyLiveLossRcaLogPath -Label "TinyLive loss RCA"
$nearThresholdLog = $null
$nearThresholdResolvedPath = Resolve-RepoPath -PathValue $NearThresholdShadowObservationLogPath
if (Test-Path -LiteralPath $nearThresholdResolvedPath) {
    $nearThresholdLog = Get-LogTextAndFreshness -PathValue $NearThresholdShadowObservationLogPath -Label "Strategy574 near-threshold shadow observation"
}

$strategyGateStatus = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_signal_review_gate_status="
$strategyNextAction = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_signal_review_next_action="
$originDeltaStatus = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "origin_delta_status="
$nearBuy = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_near_buy="
$governanceTooStrict = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "governance_too_strict_7d_or_14d="
$shortWindowInsufficient = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "short_window_insufficient_data="
$dataFreshnessClean = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "data_freshness_current_clean="
$terminalReason = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_terminal_reason="
$policyRecommendation = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_policy_change_recommendation="
$deployRequired = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "deploy_required_before_strategy574_review="
$shadowObservationAllowed = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "shadow_observation_review_allowed="
$strategyMissingRaw = Get-LastPrefixedValue -Text $strategyLog.Text -Prefix "strategy574_review_missing_requirements="

$hardStopDetected = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  hardStopDetected="
$autoApprovalEligible = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  autoApprovalEligible="
$autoApprovalMode = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  autoApprovalMode="
$autoApprovalBlockers = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  autoApprovalBlockers="
$triggerEnabled = Get-LastRegexValue -Text $tinyLog.Text -Pattern "^\s+triggerEnabled=([^\s]+)"
$triggerDryRun = Get-LastRegexValue -Text $tinyLog.Text -Pattern "triggerDryRun=([^\s]+)"
$executionEligible = Get-LastRegexValue -Text $tinyLog.Text -Pattern "^\s+executionEligible=([^\s]+)"
$wouldExecute = Get-LastRegexValue -Text $tinyLog.Text -Pattern "wouldExecute=([^\s]+)"
$terminalBlockers = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  terminalBlockers="
$executedAutonomousTrades = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  executedAutonomousTrades="
$successfulOcoAttachRate = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  successfulOcoAttachRate="
$ocoProtectionEffectiveness = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  OCOProtectionEffectiveness="
$completedTinyLiveSamples = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  completedTinyLiveSamples="
$falsePositiveCount = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  falsePositiveCount="
$dailyLossBudgetBreached = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  dailyLossBudgetBreached="
$canEnableProduction = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  canEnableProduction="
$canIncreaseDailyCap = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  canIncreaseDailyCap="
$rolloutBlockers = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  rolloutBlockers="
$missingTinyFieldsRaw = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  missing_tiny_live_fields="
$missedOverallStatus = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  missedOverallStatus="
$suspiciousNoBuyCount = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  suspiciousNoBuyCount="
$falseBlockRiskCount = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  falseBlockRiskCount="
$recommendedFix = Get-LastPrefixedValue -Text $tinyLog.Text -Prefix "  recommendedFix="

$nearThresholdRecommendation = ""
$nearThresholdRows = ""
$nearThresholdReviewableForwardRows = ""
$nearThresholdFalsePositiveRows = ""
$nearThresholdFalsePositiveRatePct = ""
$nearThresholdAvgForwardReturnPct = ""
$nearThresholdAvgNetReturnPct = ""
$nearThresholdTpHitRows = ""
$nearThresholdSlHitRows = ""
$nearThresholdAmbiguousSameBarRows = ""
$nearThresholdOcoPreflightStatus = ""
$nearThresholdEvidenceStatus = if ($null -eq $nearThresholdLog) { "NOT_COLLECTED" } elseif ($nearThresholdLog.Freshness -ne "FRESH") { "STALE" } else { "FRESH" }
if ($null -ne $nearThresholdLog) {
    $nearThresholdRecommendation = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "strategy574_near_threshold_shadow_recommendation="
    $nearThresholdRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "near_threshold_rows="
    $nearThresholdReviewableForwardRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "reviewable_forward_rows="
    $nearThresholdFalsePositiveRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "false_positive_rows="
    $nearThresholdFalsePositiveRatePct = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "false_positive_rate_pct="
    $nearThresholdAvgForwardReturnPct = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "avg_forward_return_pct="
    $nearThresholdAvgNetReturnPct = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "avg_net_return_pct="
    $nearThresholdTpHitRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "tp_hit_rows="
    $nearThresholdSlHitRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "sl_hit_rows="
    $nearThresholdAmbiguousSameBarRows = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "ambiguous_same_bar_rows="
    $nearThresholdOcoPreflightStatus = Get-LastPrefixedValue -Text $nearThresholdLog.Text -Prefix "oco_preflight_status="
}

$strategyMissing = ConvertFrom-JsonArrayOrMarker -Raw $strategyMissingRaw -ParseFailureMarker "strategy574_review_missing_requirements JSON parse failed"
$missingTinyFields = ConvertFrom-JsonArrayOrMarker -Raw $missingTinyFieldsRaw -ParseFailureMarker "missing_tiny_live_fields JSON parse failed"

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($strategyLog.Freshness -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "strategy574 gate log is FRESH" }
if ($tinyLog.Freshness -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "TinyLive loss RCA log is FRESH" }
foreach ($pair in @(
        @("strategy574_signal_review_gate_status", $strategyGateStatus),
        @("strategy574_terminal_reason", $terminalReason),
        @("strategy574_policy_change_recommendation", $policyRecommendation),
        @("hardStopDetected", $hardStopDetected),
        @("autoApprovalEligible", $autoApprovalEligible),
        @("completedTinyLiveSamples", $completedTinyLiveSamples),
        @("falsePositiveCount", $falsePositiveCount),
        @("canEnableProduction", $canEnableProduction),
        @("missing_tiny_live_fields", $missingTinyFieldsRaw)
    )) {
    if ([string]::IsNullOrWhiteSpace([string]$pair[1])) { Add-MissingRequirement -List $missingRequirements -Value "$($pair[0]) present" }
}

$evidenceUsable = $missingRequirements.Count -eq 0
$tinyLiveRolloutReady = $canEnableProduction -eq "true"
$tinyLiveExecutionReady = $executionEligible -like "true*" -and $autoApprovalEligible -eq "true" -and $wouldExecute -like "*true*"
$hardStopClear = $hardStopDetected -eq "false" -and @($missingTinyFields).Count -eq 0
$nearThresholdFalsePositiveRiskHigh = $nearThresholdRecommendation -eq "STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH"
$nearThresholdAlphaNotProven = $nearThresholdRecommendation -in @("STRATEGY574_NEAR_THRESHOLD_ALPHA_NOT_PROVEN", "STRATEGY574_NEAR_THRESHOLD_LOW_FORWARD_SAMPLE", "STRATEGY574_NEAR_THRESHOLD_NO_RECENT_ROWS")
$reviewAllowed = $evidenceUsable
$status = if ($reviewAllowed) { "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$primaryDecision = if ($reviewAllowed) { "PREPARE_STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW" } else { "REFRESH_STRATEGY574_TINY_LIVE_EVIDENCE" }
$riskPosture = if ($nearThresholdFalsePositiveRiskHigh) {
    "BLOCKED_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH"
} elseif ($nearThresholdAlphaNotProven) {
    "BLOCKED_NEAR_THRESHOLD_ALPHA_NOT_PROVEN"
} elseif ($dataFreshnessClean -ne "true") {
    "BLOCKED_FIX_CURRENT_DATA_FRESHNESS"
} elseif (-not $tinyLiveRolloutReady) {
    "BLOCKED_TINY_LIVE_ROLLOUT_NOT_READY"
} elseif (-not $tinyLiveExecutionReady) {
    "BLOCKED_TINY_LIVE_EXECUTION_NOT_READY"
} else {
    "REVIEW_ONLY_READY_NOT_LIVE_APPROVAL"
}
$nextAction = if ($reviewAllowed) {
    "Attach this governance packet to operator review; keep hard gates and require separate explicit authorization before any live/TinyLive/order/scheduler/env change."
} else {
    "Refresh strategy 574 gate and TinyLive loss RCA logs before using this governance packet."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    side = $Side
    primaryDecision = $primaryDecision
    riskPosture = $riskPosture
    sourceLogs = [pscustomobject]@{
        strategy574GateLogPath = $strategyLog.Path
        strategy574GateLogAgeMinutes = $strategyLog.AgeMinutes
        strategy574GateLogFreshness = $strategyLog.Freshness
        tinyLiveLossRcaLogPath = $tinyLog.Path
        tinyLiveLossRcaLogAgeMinutes = $tinyLog.AgeMinutes
        tinyLiveLossRcaLogFreshness = $tinyLog.Freshness
        nearThresholdShadowObservationLogPath = if ($null -ne $nearThresholdLog) { $nearThresholdLog.Path } else { $nearThresholdResolvedPath }
        nearThresholdShadowObservationLogAgeMinutes = if ($null -ne $nearThresholdLog) { $nearThresholdLog.AgeMinutes } else { $null }
        nearThresholdShadowObservationLogFreshness = $nearThresholdEvidenceStatus
    }
    strategy574Evidence = [pscustomobject]@{
        gateStatus = $strategyGateStatus
        originDeltaStatus = $originDeltaStatus
        nearBuy = $nearBuy
        governanceTooStrict7dOr14d = $governanceTooStrict
        shortWindowInsufficientData = $shortWindowInsufficient
        dataFreshnessCurrentClean = $dataFreshnessClean
        terminalReason = $terminalReason
        policyChangeRecommendation = $policyRecommendation
        deployRequiredBeforeReview = $deployRequired
        shadowObservationReviewAllowed = $shadowObservationAllowed
        missingRequirements = @($strategyMissing)
        nextAction = $strategyNextAction
    }
    tinyLiveEvidence = [pscustomobject]@{
        hardStopDetected = $hardStopDetected
        hardStopClear = $hardStopClear
        autoApprovalEligible = $autoApprovalEligible
        autoApprovalMode = $autoApprovalMode
        autoApprovalBlockers = $autoApprovalBlockers
        triggerEnabled = $triggerEnabled
        triggerDryRun = $triggerDryRun
        executionEligible = $executionEligible
        wouldExecute = $wouldExecute
        terminalBlockers = $terminalBlockers
        executedAutonomousTrades = $executedAutonomousTrades
        successfulOcoAttachRate = $successfulOcoAttachRate
        ocoProtectionEffectiveness = $ocoProtectionEffectiveness
        completedTinyLiveSamples = $completedTinyLiveSamples
        falsePositiveCount = $falsePositiveCount
        dailyLossBudgetBreached = $dailyLossBudgetBreached
        canEnableProduction = $canEnableProduction
        canIncreaseDailyCap = $canIncreaseDailyCap
        rolloutBlockers = $rolloutBlockers
        missingTinyLiveFields = @($missingTinyFields)
    }
    opportunityContext = [pscustomobject]@{
        missedOverallStatus = $missedOverallStatus
        suspiciousNoBuyCount = $suspiciousNoBuyCount
        falseBlockRiskCount = $falseBlockRiskCount
        recommendedFix = $recommendedFix
    }
    nearThresholdShadowObservationEvidence = [pscustomobject]@{
        evidenceStatus = $nearThresholdEvidenceStatus
        recommendation = $nearThresholdRecommendation
        nearThresholdRows = $nearThresholdRows
        reviewableForwardRows = $nearThresholdReviewableForwardRows
        falsePositiveRows = $nearThresholdFalsePositiveRows
        falsePositiveRatePct = $nearThresholdFalsePositiveRatePct
        avgForwardReturnPct = $nearThresholdAvgForwardReturnPct
        avgNetReturnPct = $nearThresholdAvgNetReturnPct
        tpHitRows = $nearThresholdTpHitRows
        slHitRows = $nearThresholdSlHitRows
        ambiguousSameBarRows = $nearThresholdAmbiguousSameBarRows
        ocoPreflightStatus = $nearThresholdOcoPreflightStatus
        thresholdRelaxationAllowed = $false
        interpretation = if ($nearThresholdFalsePositiveRiskHigh) { "negative evidence for strategy574 threshold relaxation in the current window" } elseif ($nearThresholdAlphaNotProven) { "insufficient or negative alpha evidence for strategy574 threshold relaxation" } elseif ($nearThresholdEvidenceStatus -eq "FRESH") { "fresh near-threshold shadow observation evidence available for review" } else { "near-threshold shadow observation evidence not fresh or not collected" }
    }
    requiredSeparateAuthorizations = @(
        "deploy runtime changes",
        "change production env",
        "enable TinyLive auto execution or disable dry-run",
        "enable scheduler mutation",
        "place live orders or pre-buy",
        "send Telegram",
        "relax EntryDedup/DataFreshness/live policy",
        "modify OCO/grid/fund/Earn/exchange state"
    )
    forbiddenActions = @(
        "deploy",
        "change production env",
        "enable live trading",
        "execute TinyLive orders",
        "enable scheduler mutation",
        "place orders",
        "modify or cancel OCO",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state",
        "relax EntryDedup/DataFreshness/live policy"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy574/TinyLive governance operator packet only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, external backfill/import, or policy relaxation"
}

Write-Host "[strategy574-tiny-live-governance-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; reuses existing strategy574 gate and TinyLive loss RCA logs only; no SSH fresh run, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_strategy574_gate_log_path=$($strategyLog.Path)"
Write-Host "source_strategy574_gate_log_freshness=$($strategyLog.Freshness)"
Write-Host "source_tiny_live_loss_rca_log_path=$($tinyLog.Path)"
Write-Host "source_tiny_live_loss_rca_log_freshness=$($tinyLog.Freshness)"
Write-Host "source_strategy574_near_threshold_shadow_observation_log_path=$nearThresholdResolvedPath"
Write-Host "source_strategy574_near_threshold_shadow_observation_log_freshness=$nearThresholdEvidenceStatus"
Write-Host "strategy574_signal_review_gate_status=$strategyGateStatus"
Write-Host "strategy574_terminal_reason=$terminalReason"
Write-Host "strategy574_policy_change_recommendation=$policyRecommendation"
Write-Host "strategy574_data_freshness_current_clean=$dataFreshnessClean"
Write-Host "strategy574_shadow_observation_review_allowed=$shadowObservationAllowed"
Write-Host "tiny_live_hard_stop_detected=$hardStopDetected"
Write-Host "tiny_live_hard_stop_clear=$($hardStopClear.ToString().ToLowerInvariant())"
Write-Host "tiny_live_auto_approval_eligible=$autoApprovalEligible"
Write-Host "tiny_live_execution_eligible=$executionEligible"
Write-Host "tiny_live_can_enable_production=$canEnableProduction"
Write-Host "tiny_live_completed_samples=$completedTinyLiveSamples"
Write-Host "tiny_live_false_positive_count=$falsePositiveCount"
Write-Host "tiny_live_rollout_blockers=$rolloutBlockers"
Write-Host "missed_opportunity_status=$missedOverallStatus"
Write-Host "suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "false_block_risk_count=$falseBlockRiskCount"
Write-Host "strategy574_near_threshold_evidence_status=$nearThresholdEvidenceStatus"
Write-Host "strategy574_near_threshold_shadow_recommendation=$nearThresholdRecommendation"
Write-Host "strategy574_near_threshold_false_positive_rate_pct=$nearThresholdFalsePositiveRatePct"
Write-Host "strategy574_near_threshold_avg_forward_return_pct=$nearThresholdAvgForwardReturnPct"
Write-Host "strategy574_near_threshold_avg_net_return_pct=$nearThresholdAvgNetReturnPct"
Write-Host "strategy574_near_threshold_threshold_relaxation_allowed=false"
Write-Host "strategy574_tiny_live_primary_decision=$primaryDecision"
Write-Host "strategy574_tiny_live_risk_posture=$riskPosture"
Write-Host "strategy574_tiny_live_governance_review_allowed=$($reviewAllowed.ToString().ToLowerInvariant())"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host ("strategy574_tiny_live_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy574_tiny_live_governance_operator_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "strategy574_tiny_live_governance_status=$status"
Write-Host "strategy574_tiny_live_governance_next_action=$nextAction"
Write-Host "notAuthorization=read-only strategy574/TinyLive governance operator packet only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, external backfill/import, or policy relaxation"
Write-Host "[strategy574-tiny-live-governance-operator-packet] read-only check complete"

if ($RequireReady -and -not $reviewAllowed) {
    throw "Strategy574/TinyLive governance operator packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
