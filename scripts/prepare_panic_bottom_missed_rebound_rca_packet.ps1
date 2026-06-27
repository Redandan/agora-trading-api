param(
    [string]$ProfitCandidateLogPath = "target/profit-review/panic-bottom-profit-candidate-review-latest.log",
    [string]$SignalEvalNoBuyLogPath = "target/profit-review/panic-bottom-signal-eval-no-buy-latest.log",
    [string]$BuyLikeProgressionLogPath = "target/profit-review/panic-bottom-buy-like-progression-latest.log",
    [string]$PanicBottomContextLogPath = "target/profit-review/panic-bottom-context-latest.log",
    [string]$Strategy574NearThresholdShadowLogPath = "target/profit-review/strategy574-near-threshold-shadow-observation-latest.log",
    [int]$MaxAgeMinutes = 720,
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-LogTextAndFreshness {
    param([string]$PathValue, [string]$Label, [bool]$Required)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { throw "$Label path is required." }
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        if ($Required) { throw "$Label log not found: $resolved" }
        return [pscustomobject]@{
            Path = $resolved
            AgeMinutes = $null
            Freshness = "MISSING"
            Text = ""
        }
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    return [pscustomobject]@{
        Path = $resolved
        AgeMinutes = $age
        Freshness = if ($age -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

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
    $options = [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [System.Text.RegularExpressions.RegexOptions]::Multiline
    $matches = [regex]::Matches($Text, $Pattern, $options)
    if ($matches.Count -eq 0) { return "" }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function First-NonEmpty {
    param([string[]]$Values)
    foreach ($value in $Values) {
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    return ""
}

function Get-IntValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A") { return 0 }
    $match = [regex]::Match($Value, "-?\d+")
    if (-not $match.Success) { return 0 }
    $parsed = 0
    if ([int]::TryParse($match.Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A" -or $Value -eq "NULL") { return $null }
    $parsed = [decimal]0
    if ([decimal]::TryParse($Value, [System.Globalization.NumberStyles]::Any, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function New-Layer {
    param([string]$Layer, [string]$Status, [string[]]$Evidence, [string]$NextAction)
    return [pscustomobject]@{
        layer = $Layer
        status = $Status
        evidence = @($Evidence)
        nextAction = $NextAction
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 4320) { throw "MaxAgeMinutes must be between 1 and 4320." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for panic-bottom missed rebound RCA packet."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$profit = Get-LogTextAndFreshness -PathValue $ProfitCandidateLogPath -Label "Profit candidate review" -Required $true
$signalEval = Get-LogTextAndFreshness -PathValue $SignalEvalNoBuyLogPath -Label "Signal-eval no-buy generation" -Required $true
$buyLike = Get-LogTextAndFreshness -PathValue $BuyLikeProgressionLogPath -Label "Buy-like candidate progression" -Required $true
$panic = Get-LogTextAndFreshness -PathValue $PanicBottomContextLogPath -Label "Panic-bottom context" -Required $true
$nearThreshold = Get-LogTextAndFreshness -PathValue $Strategy574NearThresholdShadowLogPath -Label "Strategy574 near-threshold shadow observation" -Required $false

$profitRecommendation = Get-LastPrefixedValue -Text $profit.Text -Prefix "profit_candidate_review_recommendation="
$monthlyPnlTotalUsdt = Get-LastPrefixedValue -Text $profit.Text -Prefix "monthlyPnlTotalUsdt="
$expectedValueGateAcceptance = Get-LastPrefixedValue -Text $profit.Text -Prefix "expectedValueGateAcceptance="
$missedOpportunityStatus = Get-LastPrefixedValue -Text $profit.Text -Prefix "missedOpportunityStatus="
$falseBlockRiskCount = Get-IntValue -Value (Get-LastPrefixedValue -Text $profit.Text -Prefix "falseBlockRiskCount=")
$suspiciousNoBuyCount = Get-IntValue -Value (Get-LastPrefixedValue -Text $profit.Text -Prefix "suspiciousNoBuyCount=")
$nearBuyTruthTableRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $profit.Text -Prefix "nearBuyTruthTableRows=")
$highForwardReturnNoBuyCount = Get-IntValue -Value (First-NonEmpty @(
        (Get-LastPrefixedValue -Text $profit.Text -Prefix "highForwardReturnNoBuyCount="),
        (Get-LastRegexValue -Text $profit.Text -Pattern '"highForwardReturnNoBuyCount"\s*:\s*(\d+)')
    ))
$profitHasOcoPreflightFailed = $profit.Text -match "OCO_PREFLIGHT_FAILED"
$profitHasNoCurrentBuyCandidate = $profit.Text -match "NO_CURRENT_BUY_CANDIDATE"
$profitHasNearBuyThreshold = $profit.Text -match "WATCH_SIGNAL_NEAR_BUY_THRESHOLD"
$profitHasDataFreshnessGuard = $profit.Text -match "DataFreshnessGuard"

$signalRecommendation = Get-LastPrefixedValue -Text $signalEval.Text -Prefix "signal_eval_no_buy_generation_recommendation="
$signalEvalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "signal_eval_rows=")
$buyLikeSignalEvalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "buy_like_signal_eval_rows=")
$noBuySignalEvalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "no_buy_signal_eval_rows=")
$executionHoldRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "execution_hold_rows=")
$noThresholdHitRows = Get-IntValue -Value (Get-LastRegexValue -Text $signalEval.Text -Pattern '^\s*-\s+no_threshold_hit=(\d+)')

$thresholdRows = @()
$thresholdPattern = "^\s*-\s+strategy=(?<strategy>\d+)\s+interval=(?<interval>\S+)\s+indicator=(?<indicator>\S+)\s+count=(?<count>\d+)\s+avg_mih_value=(?<value>-?[0-9.]+)\s+avg_buy_threshold=(?<threshold>-?[0-9.]+)\s+avg_buy_gap=(?<avgGap>-?[0-9.]+)\s+min_buy_gap=(?<minGap>-?[0-9.]+)"
foreach ($line in ($signalEval.Text -split "`r?`n")) {
    $match = [regex]::Match($line, $thresholdPattern)
    if (-not $match.Success) { continue }
    if ([int]$match.Groups["strategy"].Value -ne 574) { continue }
    if ($match.Groups["interval"].Value -ne "1h") { continue }
    $thresholdRows += [pscustomobject]@{
        strategyId = 574
        intervalCode = "1h"
        indicator = $match.Groups["indicator"].Value
        count = [int]$match.Groups["count"].Value
        avgMihValue = Get-DecimalValue -Value $match.Groups["value"].Value
        avgBuyThreshold = Get-DecimalValue -Value $match.Groups["threshold"].Value
        avgBuyGap = Get-DecimalValue -Value $match.Groups["avgGap"].Value
        minBuyGap = Get-DecimalValue -Value $match.Groups["minGap"].Value
        sourceLine = $line.Trim()
    }
}

$selectedThreshold = @($thresholdRows | Sort-Object @{ Expression = { if ($null -eq $_.minBuyGap) { [decimal]999999 } else { [math]::Abs([decimal]$_.minBuyGap) } } }, @{ Expression = { -1 * $_.count } } | Select-Object -First 1)
$strategy574MinBuyGap = if ($selectedThreshold.Count -gt 0) { $selectedThreshold[0].minBuyGap } else { $null }
$strategy574ThresholdNearMiss = $false
if ($null -ne $strategy574MinBuyGap) {
    $strategy574ThresholdNearMiss = ([decimal]$strategy574MinBuyGap -ge 0 -and [decimal]$strategy574MinBuyGap -le 2)
}

$buyLikeRecommendation = Get-LastPrefixedValue -Text $buyLike.Text -Prefix "buy_like_candidate_progression_recommendation="
$buyLikeCandidateRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "buy_like_candidate_rows=")
$noTerminalFollowupRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "no_terminal_followup_rows=")
$filterBlockFollowupRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "filter_block_followup_rows=")
$entrySkipFollowupRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "entry_skip_followup_rows=")
$signalBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "signal_buy_rows=")
$autotradeFollowupRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "autotrade_followup_rows=")
$entryDedupFollowup = $entrySkipFollowupRows -gt 0 -or $buyLike.Text -match "ENTRY_SKIP:EntryDedup"
$dataFreshnessFollowup = $buyLike.Text -match "FILTER_BLOCK:DataFreshnessGuard"

$panicBoundary = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_boundary="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"boundary"\s*:\s*"([^"]+)"')
)
$panicScore = Get-IntValue -Value (First-NonEmpty @(
        (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_score="),
        (Get-LastRegexValue -Text $panic.Text -Pattern '"panicBottomScore"\s*:\s*(\d+)')
    ))
$panicPhase = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_phase="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"phase"\s*:\s*"([^"]+)"')
)
$panicSuggestedAction = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_suggested_action="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"suggestedAction"\s*:\s*"([^"]+)"')
)
$panicConfirmedDeployBlocked = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_confirmed_deploy_blocked="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"confirmedDeployBlocked"\s*:\s*(true|false)')
)
$panicConfirmedDeployBlockReason = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_confirmed_deploy_block_reason="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"confirmedDeployBlockReason"\s*:\s*"([^"]+)"')
)
$panicDownWaveCount = Get-IntValue -Value (First-NonEmpty @(
        (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_down_wave_count="),
        (Get-LastRegexValue -Text $panic.Text -Pattern '"downWaveCount"\s*:\s*(\d+)')
    ))
$panicLargestDrawdownPct = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_largest_drawdown_pct="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"largestDrawdownPct"\s*:\s*(-?[0-9.]+)')
)
$panicRetestLowStatus = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_retest_low_status="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"retestLowStatus"\s*:\s*"([^"]+)"')
)
$fearGreedValue = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_fear_greed_latest_value="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"latestValue"\s*:\s*(\d+)')
)
$fearGreedClassification = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_fear_greed_classification="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"classification"\s*:\s*"([^"]+)"')
)
$fearGreedFreshness = Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_fear_greed_freshness="
$priceVs200WmaPct = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_price_vs_200wma_pct="),
    (Get-LastRegexValue -Text $panic.Text -Pattern '"priceVs200wmaPct"\s*:\s*(-?[0-9.]+)')
)
$trend1h = Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_1h_trend_status="
$trend4h = Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_4h_trend_status="
$panicOcoGuardStatus = Get-LastPrefixedValue -Text $panic.Text -Prefix "panic_bottom_context_oco_guard_status="

$nearThresholdRecommendation = Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "strategy574_near_threshold_shadow_recommendation="
$nearThresholdRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "near_threshold_rows=")
$nearThresholdFalsePositiveRatePct = Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "false_positive_rate_pct="
$nearThresholdAvgForwardReturnPct = First-NonEmpty @(
    (Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "avg_forward_return_pct="),
    (Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "avg_24h_return_pct=")
)
$nearThresholdAvgNetReturnPct = Get-LastPrefixedValue -Text $nearThreshold.Text -Prefix "avg_net_return_pct="
$nearThresholdFalsePositiveRiskHigh = $nearThresholdRecommendation -eq "STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH"

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($log in @(
        @("profit candidate review log is FRESH", $profit.Freshness),
        @("signal-eval no-buy generation log is FRESH", $signalEval.Freshness),
        @("buy-like candidate progression log is FRESH", $buyLike.Freshness),
        @("panic-bottom context log is FRESH", $panic.Freshness)
    )) {
    if ($log[1] -ne "FRESH") { Add-Unique -List $missingRequirements -Value $log[0] }
}
if ($panicBoundary -ne "READ_ONLY") { Add-Unique -List $missingRequirements -Value "panic-bottom context boundary is READ_ONLY" }
if ($panicScore -le 0 -and [string]::IsNullOrWhiteSpace($panicPhase)) { Add-Unique -List $missingRequirements -Value "panic-bottom context score or phase present" }
if ($signalEvalRows -le 0) { Add-Unique -List $missingRequirements -Value "recent SIGNAL_EVAL rows present" }
if ($strategy574ThresholdNearMiss -eq $false -and $profitHasNearBuyThreshold -eq $false) { Add-Unique -List $missingRequirements -Value "strategy574 near-threshold evidence present" }
if ([string]::IsNullOrWhiteSpace($missedOpportunityStatus)) { Add-Unique -List $missingRequirements -Value "missed opportunity status present" }
if ([string]::IsNullOrWhiteSpace($buyLikeRecommendation) -and $buyLikeCandidateRows -le 0) { Add-Unique -List $missingRequirements -Value "buy-like progression classification present" }

$layers = [System.Collections.Generic.List[object]]::new()
$recommendations = [System.Collections.Generic.List[string]]::new()
$rankedBlockers = [System.Collections.Generic.List[string]]::new()

$signalLayerStatus = if ($strategy574ThresholdNearMiss) {
    "STRATEGY574_1H_THRESHOLD_NEAR_MISS"
} elseif ($noThresholdHitRows -gt 0) {
    "SIGNAL_EVAL_THRESHOLDS_NOT_HIT"
} elseif ($buyLikeSignalEvalRows -gt 0) {
    "BUY_LIKE_SIGNAL_EVAL_PRESENT"
} else {
    "SIGNAL_CONTEXT_INCONCLUSIVE"
}
if ($strategy574ThresholdNearMiss) {
    Add-Unique -List $recommendations -Value "REVIEW_PANIC_BOTTOM_THRESHOLD_GAP_NOT_LIVE"
    Add-Unique -List $rankedBlockers -Value "SIGNAL_THRESHOLD_NEAR_MISS"
}
$signalEvidence = @(
    "signal_eval_rows=$signalEvalRows",
    "buy_like_signal_eval_rows=$buyLikeSignalEvalRows",
    "no_buy_signal_eval_rows=$noBuySignalEvalRows",
    "execution_hold_rows=$executionHoldRows",
    "no_threshold_hit_rows=$noThresholdHitRows",
    "strategy574_min_buy_gap=$strategy574MinBuyGap",
    "signal_eval_no_buy_generation_recommendation=$signalRecommendation"
)
$layers.Add((New-Layer -Layer "signal_threshold" -Status $signalLayerStatus -Evidence $signalEvidence -NextAction "Review strategy 574 threshold-gap rows as shadow evidence only; do not change thresholds from this packet."))

$buyLikeLayerStatus = if ($buyLikeCandidateRows -le 0) {
    "WAIT_FOR_BUY_LIKE_CANDIDATE"
} elseif ($signalBuyRows -eq 0 -and $autotradeFollowupRows -eq 0 -and $noTerminalFollowupRows -gt 0) {
    "BUY_LIKE_NO_TERMINAL_FOLLOWUP"
} elseif ($entryDedupFollowup) {
    "BUY_LIKE_ENTRY_DEDUP_SKIP_PRESENT"
} elseif ($signalBuyRows + $autotradeFollowupRows -gt 0) {
    "BUY_LIKE_HAS_TERMINAL_FOLLOWUP"
} else {
    "BUY_LIKE_PIPELINE_MIXED_REVIEW"
}
if ($buyLikeCandidateRows -le 0) { Add-Unique -List $recommendations -Value "WAIT_FOR_BUY_LIKE_CANDIDATE" }
if ($buyLikeLayerStatus -in @("BUY_LIKE_NO_TERMINAL_FOLLOWUP", "BUY_LIKE_ENTRY_DEDUP_SKIP_PRESENT")) {
    Add-Unique -List $rankedBlockers -Value $buyLikeLayerStatus
}
$buyLikeEvidence = @(
    "buy_like_candidate_rows=$buyLikeCandidateRows",
    "no_terminal_followup_rows=$noTerminalFollowupRows",
    "entry_skip_followup_rows=$entrySkipFollowupRows",
    "filter_block_followup_rows=$filterBlockFollowupRows",
    "signal_buy_rows=$signalBuyRows",
    "autotrade_followup_rows=$autotradeFollowupRows",
    "buy_like_candidate_progression_recommendation=$buyLikeRecommendation"
)
$layers.Add((New-Layer -Layer "buy_like_continuity" -Status $buyLikeLayerStatus -Evidence $buyLikeEvidence -NextAction "Review candidate-to-terminal continuity before any entry-filter or live execution change."))

$entryFilterStatus = if ($entryDedupFollowup) {
    "ENTRY_DEDUP_EXISTING_EXPOSURE_SKIP"
} elseif ($dataFreshnessFollowup -or $profitHasDataFreshnessGuard) {
    "DATAFRESHNESS_OR_FILTER_EVIDENCE_PRESENT"
} elseif ($filterBlockFollowupRows -gt 0) {
    "FILTER_BLOCK_FOLLOWUP_PRESENT"
} else {
    "NO_FILTER_BLOCK_DOMINANT_IN_CURRENT_PACKET"
}
if ($entryDedupFollowup) { Add-Unique -List $rankedBlockers -Value "ENTRY_DEDUP_EXISTING_EXPOSURE" }
$entryFilterEvidence = @(
    "entry_dedup_followup=$($entryDedupFollowup.ToString().ToLowerInvariant())",
    "data_freshness_followup=$($dataFreshnessFollowup.ToString().ToLowerInvariant())",
    "profit_has_data_freshness_guard=$($profitHasDataFreshnessGuard.ToString().ToLowerInvariant())",
    "filter_block_followup_rows=$filterBlockFollowupRows"
)
$layers.Add((New-Layer -Layer "entry_dedup_data_freshness_filter" -Status $entryFilterStatus -Evidence $entryFilterEvidence -NextAction "Keep EntryDedup/DataFreshness/live policy unchanged; collect row-level replay evidence before any policy experiment."))

$ocoBlocked = $profitHasOcoPreflightFailed -or $panicConfirmedDeployBlockReason -eq "OCO_ABNORMAL_OR_1H_4H_TRENDING_BEARISH" -or $panicOcoGuardStatus -eq "ABNORMAL"
$ocoLayerStatus = if ($ocoBlocked) { "OCO_PREFLIGHT_OR_TREND_GUARD_BLOCKED" } else { "OCO_PREFLIGHT_NOT_PRIMARY_OR_NOT_PRESENT" }
if ($ocoBlocked) {
    Add-Unique -List $recommendations -Value "BLOCKED_OCO_PREFLIGHT"
    Add-Unique -List $rankedBlockers -Value "OCO_PREFLIGHT_OR_TREND_GUARD"
}
$ocoEvidence = @(
    "profit_has_oco_preflight_failed=$($profitHasOcoPreflightFailed.ToString().ToLowerInvariant())",
    "panic_confirmed_deploy_blocked=$panicConfirmedDeployBlocked",
    "panic_confirmed_deploy_block_reason=$panicConfirmedDeployBlockReason",
    "panic_oco_guard_status=$panicOcoGuardStatus",
    "trend_1h=$trend1h",
    "trend_4h=$trend4h"
)
$layers.Add((New-Layer -Layer "oco_preflight_and_trend_guard" -Status $ocoLayerStatus -Evidence $ocoEvidence -NextAction "Treat SCOUT_PRE_POSITION/WATCH labels as operator review only while OCO or 1h/4h trend guard blocks confirmed deploy."))

$executionLayerStatus = "EXECUTION_DISABLED_BY_PACKET_BOUNDARY"
$executionEvidence = @(
    "order_allowed=false",
    "live_policy_change_allowed=false",
    "position_or_oco_mutation_allowed=false",
    "grid_mutation_allowed=false",
    "telegram_send_allowed=false",
    "scheduler_enablement_allowed=false"
)
$layers.Add((New-Layer -Layer "execution_live_boundary" -Status $executionLayerStatus -Evidence $executionEvidence -NextAction "Any live relaxation, pre-position execution, order, scheduler, deploy, or env change requires a separate authorization and a separate post-change read-only verification plan."))

if ($panicSuggestedAction -eq "SCOUT_PRE_POSITION") { Add-Unique -List $recommendations -Value "REVIEW_SCOUT_PRE_POSITION_EVIDENCE_NOT_LIVE" }
if ($nearThresholdFalsePositiveRiskHigh) { Add-Unique -List $recommendations -Value "KEEP_STRATEGY574_THRESHOLD_RELAXATION_BLOCKED" }
Add-Unique -List $recommendations -Value "KEEP_ENTRY_DEDUP_DATAFRESHNESS_LIVE_POLICY_UNCHANGED"

$primaryRootCause = if ($strategy574ThresholdNearMiss -and $ocoBlocked) {
    "SIGNAL_THRESHOLD_NEAR_MISS_WITH_OCO_OR_TREND_GUARD_BLOCKER"
} elseif ($strategy574ThresholdNearMiss) {
    "SIGNAL_THRESHOLD_NEAR_MISS_BEFORE_BUY"
} elseif ($buyLikeLayerStatus -eq "BUY_LIKE_NO_TERMINAL_FOLLOWUP") {
    "BUY_LIKE_PIPELINE_DROPPED_BEFORE_TERMINAL_EVENT"
} elseif ($entryDedupFollowup) {
    "ENTRY_DEDUP_EXISTING_EXPOSURE_BLOCKED_BUY_LIKE_FOLLOWUP"
} else {
    "MIXED_REVIEW_REQUIRED_NOT_EXECUTION_FAILURE"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this RCA packet to issue #17; use it to decide the next read-only experiment lane, not to relax live policy."
} else {
    "Refresh the missing or stale read-only source logs, then rerun this packet before using it as issue #17 evidence."
}

$packet = [pscustomobject]@{
    packetType = "PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET"
    status = $status
    symbol = $Symbol
    issue = "#17"
    primaryRootCause = $primaryRootCause
    sourceLogs = [pscustomobject]@{
        profitCandidateReview = [pscustomobject]@{ path = $profit.Path; freshness = $profit.Freshness; ageMinutes = $profit.AgeMinutes }
        signalEvalNoBuyGeneration = [pscustomobject]@{ path = $signalEval.Path; freshness = $signalEval.Freshness; ageMinutes = $signalEval.AgeMinutes }
        buyLikeCandidateProgression = [pscustomobject]@{ path = $buyLike.Path; freshness = $buyLike.Freshness; ageMinutes = $buyLike.AgeMinutes }
        panicBottomContext = [pscustomobject]@{ path = $panic.Path; freshness = $panic.Freshness; ageMinutes = $panic.AgeMinutes }
        strategy574NearThresholdShadowObservation = [pscustomobject]@{ path = $nearThreshold.Path; freshness = $nearThreshold.Freshness; ageMinutes = $nearThreshold.AgeMinutes }
    }
    panicBottomContext = [pscustomobject]@{
        boundary = $panicBoundary
        score = $panicScore
        phase = $panicPhase
        suggestedAction = $panicSuggestedAction
        confirmedDeployBlocked = $panicConfirmedDeployBlocked
        confirmedDeployBlockReason = $panicConfirmedDeployBlockReason
        downWaveCount = $panicDownWaveCount
        largestDrawdownPct = $panicLargestDrawdownPct
        retestLowStatus = $panicRetestLowStatus
        fearGreedValue = $fearGreedValue
        fearGreedClassification = $fearGreedClassification
        fearGreedFreshness = $fearGreedFreshness
        priceVs200WmaPct = $priceVs200WmaPct
        trend1h = $trend1h
        trend4h = $trend4h
        ocoGuardStatus = $panicOcoGuardStatus
    }
    missedOpportunity = [pscustomobject]@{
        profitCandidateReviewRecommendation = $profitRecommendation
        monthlyPnlTotalUsdt = $monthlyPnlTotalUsdt
        expectedValueGateAcceptance = $expectedValueGateAcceptance
        missedOpportunityStatus = $missedOpportunityStatus
        suspiciousNoBuyCount = $suspiciousNoBuyCount
        falseBlockRiskCount = $falseBlockRiskCount
        highForwardReturnNoBuyCount = $highForwardReturnNoBuyCount
        nearBuyTruthTableRows = $nearBuyTruthTableRows
        noCurrentBuyCandidate = $profitHasNoCurrentBuyCandidate
        ocoPreflightFailed = $profitHasOcoPreflightFailed
    }
    strategy574ThresholdGap = [pscustomobject]@{
        nearMiss = $strategy574ThresholdNearMiss
        selected = if ($selectedThreshold.Count -gt 0) { $selectedThreshold[0] } else { $null }
        nearThresholdShadowRecommendation = $nearThresholdRecommendation
        nearThresholdRows = $nearThresholdRows
        falsePositiveRatePct = $nearThresholdFalsePositiveRatePct
        avgForwardReturnPct = $nearThresholdAvgForwardReturnPct
        avgNetReturnPct = $nearThresholdAvgNetReturnPct
        thresholdRelaxationAllowed = $false
    }
    blockerLayerClassification = @($layers)
    rankedBlockers = @($rankedBlockers)
    recommendations = @($recommendations)
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        orderAllowed = $false
        livePolicyChangeAllowed = $false
        strategyThresholdChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        positionOrOcoMutationAllowed = $false
        gridMutationAllowed = $false
        fundOrEarnMutationAllowed = $false
        telegramSendAllowed = $false
        schedulerEnablementAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only panic-bottom missed rebound RCA packet only; does not authorize live trading, strategy threshold changes, EntryDedup/DataFreshness/live policy relaxation, pre-position execution, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, scheduler enablement, or external backfill/import"
}

Write-Host "[panic-bottom-missed-rebound-rca-packet] read-only packet"
Write-Host "scope=READ_ONLY; reuses existing saved read-only logs only; no SSH fresh run, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_profit_candidate_log_path=$($profit.Path)"
Write-Host "source_profit_candidate_log_freshness=$($profit.Freshness)"
Write-Host "source_signal_eval_no_buy_log_path=$($signalEval.Path)"
Write-Host "source_signal_eval_no_buy_log_freshness=$($signalEval.Freshness)"
Write-Host "source_buy_like_progression_log_path=$($buyLike.Path)"
Write-Host "source_buy_like_progression_log_freshness=$($buyLike.Freshness)"
Write-Host "source_panic_bottom_context_log_path=$($panic.Path)"
Write-Host "source_panic_bottom_context_log_freshness=$($panic.Freshness)"
Write-Host "source_strategy574_near_threshold_shadow_log_path=$($nearThreshold.Path)"
Write-Host "source_strategy574_near_threshold_shadow_log_freshness=$($nearThreshold.Freshness)"
Write-Host "panic_bottom_context_boundary=$panicBoundary"
Write-Host "panic_bottom_score=$panicScore"
Write-Host "panic_bottom_phase=$panicPhase"
Write-Host "panic_bottom_suggested_action=$panicSuggestedAction"
Write-Host "panic_bottom_confirmed_deploy_blocked=$panicConfirmedDeployBlocked"
Write-Host "panic_bottom_confirmed_deploy_block_reason=$panicConfirmedDeployBlockReason"
Write-Host "panic_bottom_down_wave_count=$panicDownWaveCount"
Write-Host "panic_bottom_largest_drawdown_pct=$panicLargestDrawdownPct"
Write-Host "panic_bottom_retest_low_status=$panicRetestLowStatus"
Write-Host "panic_bottom_fear_greed_value=$fearGreedValue"
Write-Host "panic_bottom_fear_greed_classification=$fearGreedClassification"
Write-Host "panic_bottom_price_vs_200wma_pct=$priceVs200WmaPct"
Write-Host "profit_candidate_review_recommendation=$profitRecommendation"
Write-Host "missed_opportunity_status=$missedOpportunityStatus"
Write-Host "suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "false_block_risk_count=$falseBlockRiskCount"
Write-Host "high_forward_return_no_buy_count=$highForwardReturnNoBuyCount"
Write-Host "near_buy_truth_table_rows=$nearBuyTruthTableRows"
Write-Host "signal_eval_rows=$signalEvalRows"
Write-Host "buy_like_signal_eval_rows=$buyLikeSignalEvalRows"
Write-Host "no_buy_signal_eval_rows=$noBuySignalEvalRows"
Write-Host "execution_hold_rows=$executionHoldRows"
Write-Host "no_threshold_hit_rows=$noThresholdHitRows"
Write-Host "strategy574_threshold_gap_row_count=$(@($thresholdRows).Count)"
Write-Host "strategy574_min_buy_gap=$strategy574MinBuyGap"
Write-Host "strategy574_threshold_near_miss=$($strategy574ThresholdNearMiss.ToString().ToLowerInvariant())"
Write-Host "strategy574_near_threshold_shadow_recommendation=$nearThresholdRecommendation"
Write-Host "strategy574_near_threshold_false_positive_rate_pct=$nearThresholdFalsePositiveRatePct"
Write-Host "strategy574_threshold_relaxation_allowed=false"
Write-Host "buy_like_candidate_rows=$buyLikeCandidateRows"
Write-Host "no_terminal_followup_rows=$noTerminalFollowupRows"
Write-Host "entry_skip_followup_rows=$entrySkipFollowupRows"
Write-Host "filter_block_followup_rows=$filterBlockFollowupRows"
Write-Host "signal_buy_rows=$signalBuyRows"
Write-Host "autotrade_followup_rows=$autotradeFollowupRows"
Write-Host "entry_dedup_followup=$($entryDedupFollowup.ToString().ToLowerInvariant())"
Write-Host "data_freshness_followup=$($dataFreshnessFollowup.ToString().ToLowerInvariant())"
Write-Host "oco_preflight_blocked=$($ocoBlocked.ToString().ToLowerInvariant())"
Write-Host "panic_bottom_missed_rebound_primary_root_cause=$primaryRootCause"
Write-Host ("panic_bottom_missed_rebound_blocker_layers=" + (ConvertTo-Json -Compress -Depth 8 @($layers)))
Write-Host ("panic_bottom_missed_rebound_ranked_blockers=" + (ConvertTo-Json -Compress @($rankedBlockers)))
Write-Host ("panic_bottom_missed_rebound_recommendations=" + (ConvertTo-Json -Compress @($recommendations)))
Write-Host "order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "strategy_threshold_change_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "fund_or_earn_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host ("panic_bottom_missed_rebound_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("panic_bottom_missed_rebound_rca_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "panic_bottom_missed_rebound_rca_status=$status"
Write-Host "panic_bottom_missed_rebound_next_action=$nextAction"
Write-Host "notAuthorization=read-only panic-bottom missed rebound RCA packet only; does not authorize live trading, strategy threshold changes, EntryDedup/DataFreshness/live policy relaxation, pre-position execution, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, scheduler enablement, or external backfill/import"
Write-Host "[panic-bottom-missed-rebound-rca-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Panic-bottom missed rebound RCA packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
