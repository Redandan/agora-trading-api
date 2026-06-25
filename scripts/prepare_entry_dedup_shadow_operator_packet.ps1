param(
    [string]$CandidateFlowLogPath = "target/profit-review/profit-candidate-flow-review-goal-14d.log",
    [string]$BuyLikeProgressionLogPath = "target/profit-review/buy-like-candidate-progression-goal-14d.log",
    [string]$EntryDedupDecisionLogPath = "target/profit-review/entry-dedup-operator-decision-goal-508-1h.log",
    [string]$Strategy574GateLogPath = "target/profit-review/strategy574-signal-review-gate-goal.log",
    [string]$SignalCorrectnessLogPath = "target/profit-review/signal-correctness-current-freshness-goal.log",
    [int]$MaxAgeMinutes = 240,
    [string]$Symbol = "BTCUSDT",
    [int]$EntryDedupStrategyId = 508,
    [string]$EntryDedupIntervalCode = "1h",
    [int]$Strategy574Id = 574,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-RelativeOrRootedPathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Read-FreshLog {
    param([string]$Name, [string]$Path, [int]$MaxAge)
    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $ageMinutes = [Math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $Path
        ResolvedPath = $resolved
        AgeMinutes = $ageMinutes
        Fresh = $ageMinutes -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-FirstRegexGroup {
    param([string]$Text, [string]$Pattern)
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success -or $match.Groups.Count -lt 2) { return "" }
    return $match.Groups[1].Value.Trim()
}

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([string]$Value)
    $parsed = [decimal]0
    if ([decimal]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return [decimal]0
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-BlockerRanking {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $sectionMatch = [regex]::Match(
        $Text,
        "buy_like_followup_classification:\s*(?<section>(?:\r?\n\s*-\s+[^\r\n]+)+)",
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $sectionMatch.Success) { return @() }
    $section = $sectionMatch.Groups["section"].Value
    foreach ($match in [regex]::Matches($section, "^\s*-\s+([^=\r\n]+)=(\d+)\s*$", [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        $name = $match.Groups[1].Value.Trim()
        if ($name -notmatch "^(ENTRY_SKIP|NO_TERMINAL_FOLLOWUP)") { continue }
        $items.Add([pscustomobject]@{
            blockerFamily = $name
            rows = [int]$match.Groups[2].Value
        })
    }
    return @($items | Sort-Object -Property @{ Expression = "rows"; Descending = $true }, blockerFamily)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ($EntryDedupStrategyId -lt 1 -or $EntryDedupStrategyId -gt 1000000) { throw "EntryDedupStrategyId must be between 1 and 1000000." }
if ($Strategy574Id -lt 1 -or $Strategy574Id -gt 1000000) { throw "Strategy574Id must be between 1 and 1000000." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "EntryDedupIntervalCode" -Value $EntryDedupIntervalCode
foreach ($path in @($CandidateFlowLogPath, $BuyLikeProgressionLogPath, $EntryDedupDecisionLogPath, $Strategy574GateLogPath, $SignalCorrectnessLogPath)) {
    Assert-RelativeOrRootedPathSafe -Name "LogPath" -Value $path
}

$candidateFlow = Read-FreshLog -Name "candidate-flow" -Path $CandidateFlowLogPath -MaxAge $MaxAgeMinutes
$buyLike = Read-FreshLog -Name "buy-like-progression" -Path $BuyLikeProgressionLogPath -MaxAge $MaxAgeMinutes
$entryDedup = Read-FreshLog -Name "entry-dedup-decision" -Path $EntryDedupDecisionLogPath -MaxAge $MaxAgeMinutes
$strategy574 = Read-FreshLog -Name "strategy574-gate" -Path $Strategy574GateLogPath -MaxAge $MaxAgeMinutes
$signalCorrectness = Read-FreshLog -Name "signal-correctness-current-freshness" -Path $SignalCorrectnessLogPath -MaxAge $MaxAgeMinutes

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($candidateFlow, $buyLike, $entryDedup, $strategy574, $signalCorrectness)) {
    if (-not $log.Fresh) {
        Add-Unique -List $missingRequirements -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$candidateFlowStatus = Get-LastPrefixedValue -Text $candidateFlow.Text -Prefix "profit_candidate_flow_review_status="
$candidateFlowNextAction = Get-LastPrefixedValue -Text $candidateFlow.Text -Prefix "profit_candidate_flow_next_action="
$buyLikeRecommendation = Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  buy_like_candidate_progression_recommendation="
if ([string]::IsNullOrWhiteSpace($buyLikeRecommendation)) {
    $buyLikeRecommendation = Get-LastPrefixedValue -Text $candidateFlow.Text -Prefix "buy_like_candidate_progression_recommendation="
}
$buyLikeRows = Get-IntValue (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  buy_like_candidate_rows=")
$entrySkipRows = Get-IntValue (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  entry_skip_followup_rows=")
$signalBuyRows = Get-IntValue (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  signal_buy_rows=")
$autotradeRows = Get-IntValue (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  autotrade_followup_rows=")
$filterBlockRows = Get-IntValue (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  filter_block_followup_rows=")
$blockerRanking = @(Get-BlockerRanking -Text $buyLike.Text)

$entryDedupStatus = Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "entry_dedup_operator_decision_brief_status="
$entryDedupPrimaryRecommendation = Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "entry_dedup_operator_primary_recommendation="
$entryDedupSkipRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "entry_dedup_skip_rows=")
$positive24hRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "positive_24h_rows=")
$negative24hRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "negative_24h_rows=")
$avg24hReturnPct = Get-DecimalValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "avg_24h_return_pct=")
$tpHitRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "tp_hit_rows=")
$slHitRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "sl_hit_rows=")
$ambiguousRows = Get-IntValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "ambiguous_same_bar_rows=")
$avgNetReturnPct = Get-DecimalValue (Get-LastPrefixedValue -Text $entryDedup.Text -Prefix "avg_net_return_pct=")

$strategy574Status = Get-LastPrefixedValue -Text $strategy574.Text -Prefix "strategy574_signal_review_gate_status="
$strategy574Recommendation = Get-LastPrefixedValue -Text $strategy574.Text -Prefix "strategy574_policy_change_recommendation="
$strategy574NearBuy = Get-LastPrefixedValue -Text $strategy574.Text -Prefix "strategy574_near_buy="
$strategy574DataFreshnessClean = Get-LastPrefixedValue -Text $strategy574.Text -Prefix "data_freshness_current_clean="
$strategy574MissingRequirements = Get-LastPrefixedValue -Text $strategy574.Text -Prefix "strategy574_review_missing_requirements="
$dataFreshnessCurrentStatus = Get-FirstRegexGroup -Text $signalCorrectness.Text -Pattern "dataFreshnessCurrentStatus=([^ ]+)"
$dataFreshnessAcceptance = Get-FirstRegexGroup -Text $signalCorrectness.Text -Pattern "acceptance=([^ ]+)"

if ($candidateFlowStatus -ne "READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE") {
    Add-Unique -List $missingRequirements -Value "candidate-flow status is READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE"
}
if ($buyLikeRows -lt 1 -or $entrySkipRows -lt 1) {
    Add-Unique -List $missingRequirements -Value "BUY-like candidates primarily route to entry skip"
}
if ($entryDedupStatus -ne "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE") {
    Add-Unique -List $missingRequirements -Value "EntryDedup operator decision brief ready"
}
if ($entryDedupSkipRows -lt 1 -or $positive24hRows -lt 1 -or $tpHitRows -lt 1) {
    Add-Unique -List $missingRequirements -Value "EntryDedup positive skip evidence present"
}
if ($strategy574Status -notmatch "^BLOCKED_") {
    Add-Unique -List $missingRequirements -Value "strategy574 gate remains explicitly blocked"
}
if ($strategy574Recommendation -ne "DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE") {
    Add-Unique -List $missingRequirements -Value "strategy574 policy recommendation keeps live/DataFreshness/EntryDedup relaxation blocked"
}
if ([string]::IsNullOrWhiteSpace($dataFreshnessCurrentStatus)) {
    Add-Unique -List $missingRequirements -Value "current DataFreshness RCA status present"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$primaryDecision = if ($ready) { "PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_REVIEW" } else { "REFRESH_OR_COMPLETE_READ_ONLY_EVIDENCE" }
$nextAction = if ($ready) {
    "Attach this packet to a separate review-only EntryDedup/ShadowExecutionIntent operator review; keep live policy, scheduler, order, OCO, deploy, and production env unchanged."
} else {
    "Refresh the listed read-only evidence logs or resolve missing markers before operator review."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_PACKET"
    status = $status
    symbol = $Symbol
    primaryDecision = $primaryDecision
    sourceLogs = [pscustomobject]@{
        candidateFlow = $CandidateFlowLogPath
        buyLikeProgression = $BuyLikeProgressionLogPath
        entryDedupDecision = $EntryDedupDecisionLogPath
        strategy574Gate = $Strategy574GateLogPath
        signalCorrectness = $SignalCorrectnessLogPath
    }
    sourceLogFreshness = @(@($candidateFlow, $buyLike, $entryDedup, $strategy574, $signalCorrectness) | ForEach-Object {
        [pscustomobject]@{ name = $_.Name; ageMinutes = $_.AgeMinutes; fresh = $_.Fresh }
    })
    candidateFlow = [pscustomobject]@{
        status = $candidateFlowStatus
        recommendation = $buyLikeRecommendation
        buyLikeCandidateRows = $buyLikeRows
        entrySkipFollowupRows = $entrySkipRows
        filterBlockFollowupRows = $filterBlockRows
        signalBuyRows = $signalBuyRows
        autotradeFollowupRows = $autotradeRows
        blockerRanking = @($blockerRanking)
        nextAction = $candidateFlowNextAction
    }
    entryDedup508 = [pscustomobject]@{
        strategyId = $EntryDedupStrategyId
        intervalCode = $EntryDedupIntervalCode
        status = $entryDedupStatus
        primaryRecommendation = $entryDedupPrimaryRecommendation
        skipRows = $entryDedupSkipRows
        positive24hRows = $positive24hRows
        negative24hRows = $negative24hRows
        avg24hReturnPct = $avg24hReturnPct
        tpHitRows = $tpHitRows
        slHitRows = $slHitRows
        ambiguousSameBarRows = $ambiguousRows
        avgNetReturnPct = $avgNetReturnPct
    }
    strategy574 = [pscustomobject]@{
        strategyId = $Strategy574Id
        gateStatus = $strategy574Status
        nearBuy = $strategy574NearBuy
        dataFreshnessCurrentClean = $strategy574DataFreshnessClean
        policyRecommendation = $strategy574Recommendation
        missingRequirements = $strategy574MissingRequirements
        role = "blocked context; not part of EntryDedup mutation approval"
    }
    currentDataFreshness = [pscustomobject]@{
        status = $dataFreshnessCurrentStatus
        acceptance = $dataFreshnessAcceptance
        interpretation = "NO_CURRENT_SAMPLE blocks freshness clearance; it is not proof that DataFreshnessGuard can be relaxed."
    }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        livePolicyChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
    }
    requiredBeforeAnyMutation = @(
        "fresh read-only rerun immediately before operator decision",
        "ExpectedValueGate pass-like evidence",
        "EventRiskControl clear or separately approved",
        "duplicate-hash and same-candidate replay protection",
        "daily cap and max-loss budget evidence",
        "OCO feasibility with exact route and lower-timeframe or exchange-side proof",
        "current DataFreshness sample is CLEAN if DataFreshness/live policy is in scope",
        "separate explicit authorization for any deploy, env change, live policy, scheduler, order, OCO, Telegram, DB, exchange, grid, fund, Earn, or external backfill mutation"
    )
    operatorDecisionChoices = @(
        "approve review-only EntryDedup/ShadowExecutionIntent operator packet",
        "request a fresh read-only rerun",
        "reject or defer and keep current policy unchanged"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup/ShadowExecutionIntent operator packet only; does not authorize live trading, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[entry-dedup-shadow-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved local evidence logs only; no SSH, GitHub, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "candidate_flow_status=$candidateFlowStatus"
Write-Host "buy_like_candidate_progression_recommendation=$buyLikeRecommendation"
Write-Host "buy_like_candidate_rows=$buyLikeRows"
Write-Host "entry_skip_followup_rows=$entrySkipRows"
Write-Host "filter_block_followup_rows=$filterBlockRows"
Write-Host "signal_buy_rows=$signalBuyRows"
Write-Host "autotrade_followup_rows=$autotradeRows"
Write-Host ("entry_skip_blocker_ranking=" + (ConvertTo-Json -Compress -Depth 5 @($blockerRanking)))
Write-Host "entry_dedup_operator_decision_brief_status=$entryDedupStatus"
Write-Host "entry_dedup_strategy_id=$EntryDedupStrategyId"
Write-Host "entry_dedup_interval_code=$EntryDedupIntervalCode"
Write-Host "entry_dedup_skip_rows=$entryDedupSkipRows"
Write-Host "entry_dedup_positive_24h_rows=$positive24hRows"
Write-Host "entry_dedup_negative_24h_rows=$negative24hRows"
Write-Host "entry_dedup_avg_24h_return_pct=$avg24hReturnPct"
Write-Host "entry_dedup_tp_hit_rows=$tpHitRows"
Write-Host "entry_dedup_sl_hit_rows=$slHitRows"
Write-Host "entry_dedup_avg_net_return_pct=$avgNetReturnPct"
Write-Host "strategy574_signal_review_gate_status=$strategy574Status"
Write-Host "strategy574_policy_change_recommendation=$strategy574Recommendation"
Write-Host "data_freshness_current_status=$dataFreshnessCurrentStatus"
Write-Host "data_freshness_current_acceptance=$dataFreshnessAcceptance"
Write-Host ("entry_dedup_shadow_operator_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_shadow_operator_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "entry_dedup_shadow_operator_packet_status=$status"
Write-Host "entry_dedup_shadow_operator_next_action=$nextAction"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup/ShadowExecutionIntent operator packet only; does not authorize live trading, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[entry-dedup-shadow-operator-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup/ShadowExecutionIntent operator packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
