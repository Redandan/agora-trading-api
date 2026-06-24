param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireFresh,
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

function Get-FieldValue {
    param(
        [string]$Text,
        [string]$Name,
        [string]$Default = "N/A"
    )

    $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($Name))=(?<value>.+?)\s*$")
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups["value"].Value.Trim()
}

function Convert-ToNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A") {
        return $null
    }
    $clean = $Value.Trim().TrimEnd("%")
    $number = 0.0
    if ([double]::TryParse($clean, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        return $number
    }
    return $null
}

function Convert-ToPacketInt {
    param([object]$Value)
    if ($Value -is [array]) {
        $Value = $Value[-1]
    }
    if ($null -eq $Value) {
        return 0
    }
    return [int][double]$Value
}

function Convert-ToPacketScalar {
    param([object]$Value)
    if ($Value -is [array]) {
        return $Value[-1]
    }
    return $Value
}

function Add-MissingRequirement {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    if (-not $List.Contains($Value)) {
        [void]$List.Add($Value)
    }
}

$sourcePath = Join-Path (Get-Location) $SourceLog
if ([System.IO.Path]::IsPathRooted($SourceLog)) {
    $sourcePath = $SourceLog
}

if (-not (Test-Path -LiteralPath $sourcePath)) {
    $packet = [ordered]@{
        status = "BLOCKED_SOURCE_LOG_MISSING"
        sourceLog = $SourceLog
        requiredEvidence = @("fresh issue #7 read-only SSH smoke log")
        notAuthorization = "local packet only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, orders, OCO modification, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
    Write-Host "issue7_filter_block_false_kill_packet=$($packet | ConvertTo-Json -Compress -Depth 5)"
    Write-Host "issue7_filter_block_false_kill_status=BLOCKED_SOURCE_LOG_MISSING"
    Write-Host "issue7_filter_block_false_kill_review_allowed=false"
    Write-Host "issue7_live_relaxation_allowed=false"
    Write-Host "notAuthorization=$($packet.notAuthorization)"
    if ($RequireBlocked) {
        exit 0
    }
    throw "Issue #7 source log missing: $sourcePath"
}

$sourceItem = Get-Item -LiteralPath $sourcePath
$ageMinutes = [math]::Round(((Get-Date) - $sourceItem.LastWriteTime).TotalMinutes, 2)
$freshnessStatus = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
if ($RequireFresh -and $freshnessStatus -ne "FRESH") {
    throw "Issue #7 source log is stale: ageMinutes=$ageMinutes maxAgeMinutes=$MaxAgeMinutes"
}

$text = Get-Content -Raw -LiteralPath $sourcePath
foreach ($marker in @(
        "scope=READ_ONLY",
        "Filter Block False-Kill Summary:",
        "False-Kill Source Ranking:",
        "DataFreshnessGuard RCA:",
        "Replayable Candidate Evidence:",
        "issue7_live_relaxation_allowed=false",
        "notAuthorization="
    )) {
    if ($text -notmatch [regex]::Escape($marker)) {
        throw "Issue #7 source log missing marker: $marker"
    }
}

$totalRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_total_rows" -Default "0")
$maturedRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_matured_rows" -Default "0")
$falseKillRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_false_kill_rows" -Default "0")
$correctBlockRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_correct_block_rows" -Default "0")
$falseKillPct = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_false_kill_pct")
$avgForward = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_avg_forward_24h_pct")
$dfRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_rows" -Default "0")
$dfFalseKillRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_false_kill_rows" -Default "0")
$dfCorrectRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_correct_block_rows" -Default "0")
$dfFalseKillPct = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_false_kill_pct")
$dfAvgForward = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_avg_forward_24h_pct")
$dfReplayableRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_complete_replayable_candidate_rows" -Default "0")
$dfPreviewOnlyRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_preview_only_input_rows" -Default "0")
$dfTraceOnlyRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_trace_only_rows" -Default "0")
$replayInputStage = Get-FieldValue -Text $text -Name "replay_input_stage" -Default "UNKNOWN"
$dfStaleMin = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_stale_minutes_min")
$dfStaleAvg = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_stale_minutes_avg")
$dfStaleMax = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_stale_minutes_max")
$dfThresholdAvg = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_threshold_minutes_avg")
$dfNearMissRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_near_miss_rows" -Default "0")
$dfRecoverableGraceRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_recoverable_grace_rows" -Default "0")
$dfSevereStaleRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_severe_stale_rows" -Default "0")
$dfProxyActionableRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_proxy_actionable_rows" -Default "0")
$guardOptimizationVerdict = Get-FieldValue -Text $text -Name "data_freshness_guard_optimization_verdict" -Default "UNKNOWN"
$collectorStatusCounts = Get-FieldValue -Text $text -Name "collector_status_counts" -Default "N/A"
$hardGatePreviewStatusCounts = Get-FieldValue -Text $text -Name "hard_gate_preview_status_counts" -Default "N/A"
$replayRequiredNextActionCounts = Get-FieldValue -Text $text -Name "replay_required_next_action_counts" -Default "N/A"
if ($replayInputStage -eq "UNKNOWN" -and $dfRows -gt 0 -and $dfReplayableRows -le 0 -and $dfPreviewOnlyRows -le 0 -and $dfTraceOnlyRows -le 0) {
    $replayInputStage = "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
}
$sourceRecommendation = Get-FieldValue -Text $text -Name "issue7_recommendation"
$missingEvidenceRaw = Get-FieldValue -Text $text -Name "live_relaxation_missing_evidence" -Default "[]"

$ranking = New-Object System.Collections.Generic.List[object]
foreach ($match in [regex]::Matches($text, "(?m)^\s*-\s+blocker=(?<blocker>\S+)\s+rows=(?<rows>\d+)\s+falseKillRows=(?<false>\d+)\s+correctBlockRows=(?<correct>\d+)\s+falseKillPct=(?<pct>[+-]?[0-9.]+)%\s+avgForward24h=(?<avg>[+-]?[0-9.]+)%.*?replayableCandidateRows=(?<replay>\d+)")) {
    [void]$ranking.Add([ordered]@{
            blocker = $match.Groups["blocker"].Value
            rows = [int]$match.Groups["rows"].Value
            falseKillRows = [int]$match.Groups["false"].Value
            correctBlockRows = [int]$match.Groups["correct"].Value
            falseKillPct = [double]$match.Groups["pct"].Value
            avgForward24hPct = [double]$match.Groups["avg"].Value
            replayableCandidateRows = [int]$match.Groups["replay"].Value
        })
}

$graceCounterfactuals = New-Object System.Collections.Generic.List[object]
foreach ($match in [regex]::Matches($text, "(?m)^\s*-\s+candidate=(?<candidate>\S+)\s+releaseRows=(?<rows>\d+)\s+falseKillReleased=(?<false>\d+)\s+correctBlockReleased=(?<correct>\d+)\s+avgReleasedForward24h=(?<avg>N/A|[+-]?[0-9.]+%)")) {
    $avgValue = $null
    if ($match.Groups["avg"].Value -ne "N/A") {
        $avgValue = Convert-ToNumber $match.Groups["avg"].Value
    }
    [void]$graceCounterfactuals.Add([ordered]@{
            candidate = $match.Groups["candidate"].Value
            releaseRows = [int]$match.Groups["rows"].Value
            falseKillReleased = [int]$match.Groups["false"].Value
            correctBlockReleased = [int]$match.Groups["correct"].Value
            avgReleasedForward24hPct = $avgValue
        })
}

$missingRequirements = New-Object System.Collections.Generic.List[string]
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 source log"
}
if ($totalRows -le 0 -or $maturedRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "matured BTCUSDT 1h FILTER_BLOCK sample"
}
if ($dfRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshnessGuard rows in review window"
}
if ($dfReplayableRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "complete replayable DataFreshness rows"
    Add-MissingRequirement -List $missingRequirements -Value "liveSignalId or replayCandidateId"
    Add-MissingRequirement -List $missingRequirements -Value "entry/TP/SL plan"
    Add-MissingRequirement -List $missingRequirements -Value "evaluated EV snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "OCO plan snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "duplicate/daily-cap/exposure/event-risk hard-gate snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "counterfactual replay removing only DataFreshnessGuard"
}

$status = "READY_FOR_REPLAYABLE_CANDIDATE_REVIEW_NOT_LIVE"
if ($freshnessStatus -ne "FRESH") {
    $status = "BLOCKED_SOURCE_LOG_STALE"
} elseif ($totalRows -le 0 -or $maturedRows -le 0) {
    $status = "BLOCKED_NO_FILTER_BLOCK_SAMPLE"
} elseif ($dfRows -le 0) {
    $status = "BLOCKED_NO_DATAFRESHNESS_SAMPLE"
} elseif ($replayInputStage -eq "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE") {
    $status = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
} elseif ($replayInputStage -eq "COLLECTOR_TRACE_ONLY_NOT_REPLAYABLE") {
    $status = "BLOCKED_COLLECTOR_TRACE_ONLY_REPLAY_SNAPSHOTS_MISSING"
} elseif ($replayInputStage -eq "PREVIEW_ONLY_NOT_REPLAYABLE") {
    $status = "BLOCKED_PREVIEW_ONLY_REPLAY_SNAPSHOTS_NOT_EVALUATED"
} elseif ($dfReplayableRows -le 0) {
    $status = "BLOCKED_DATAFRESHNESS_REPLAY_SNAPSHOTS_MISSING"
}

if ($RequireBlocked -and $status -notlike "BLOCKED_*") {
    throw "Issue #7 packet was expected to stay blocked, got $status"
}

$reviewAllowed = $status -eq "READY_FOR_REPLAYABLE_CANDIDATE_REVIEW_NOT_LIVE"
$rankingArray = @()
foreach ($item in $ranking) {
    $rankingArray += $item
}
$missingRequirementsArray = @()
foreach ($item in $missingRequirements) {
    $missingRequirementsArray += $item
}
$graceCounterfactualArray = @()
foreach ($item in $graceCounterfactuals) {
    $graceCounterfactualArray += $item
}
$packet = [ordered]@{
    status = $status
    sourceLog = $SourceLog
    sourceLogFreshness = $freshnessStatus
    sourceLogAgeMinutes = $ageMinutes
    reviewAllowed = $reviewAllowed
    liveRelaxationAllowed = $false
    totalRows = (Convert-ToPacketInt $totalRows)
    maturedRows = (Convert-ToPacketInt $maturedRows)
    falseKillRows = (Convert-ToPacketInt $falseKillRows)
    correctBlockRows = (Convert-ToPacketInt $correctBlockRows)
    falseKillPct = (Convert-ToPacketScalar $falseKillPct)
    avgForward24hPct = (Convert-ToPacketScalar $avgForward)
    dataFreshnessRows = (Convert-ToPacketInt $dfRows)
    dataFreshnessFalseKillRows = (Convert-ToPacketInt $dfFalseKillRows)
    dataFreshnessCorrectBlockRows = (Convert-ToPacketInt $dfCorrectRows)
    dataFreshnessFalseKillPct = (Convert-ToPacketScalar $dfFalseKillPct)
    dataFreshnessAvgForward24hPct = (Convert-ToPacketScalar $dfAvgForward)
    dataFreshnessReplayableRows = (Convert-ToPacketInt $dfReplayableRows)
    dataFreshnessPreviewOnlyRows = (Convert-ToPacketInt $dfPreviewOnlyRows)
    dataFreshnessTraceOnlyRows = (Convert-ToPacketInt $dfTraceOnlyRows)
    replayInputStage = $replayInputStage
    dataFreshnessStaleMinutesMin = (Convert-ToPacketScalar $dfStaleMin)
    dataFreshnessStaleMinutesAvg = (Convert-ToPacketScalar $dfStaleAvg)
    dataFreshnessStaleMinutesMax = (Convert-ToPacketScalar $dfStaleMax)
    dataFreshnessThresholdMinutesAvg = (Convert-ToPacketScalar $dfThresholdAvg)
    dataFreshnessNearMissRows = (Convert-ToPacketInt $dfNearMissRows)
    dataFreshnessRecoverableGraceRows = (Convert-ToPacketInt $dfRecoverableGraceRows)
    dataFreshnessSevereStaleRows = (Convert-ToPacketInt $dfSevereStaleRows)
    dataFreshnessProxyActionableRows = (Convert-ToPacketInt $dfProxyActionableRows)
    dataFreshnessGuardOptimizationVerdict = $guardOptimizationVerdict
    dataFreshnessGraceCounterfactuals = $graceCounterfactualArray
    collectorStatusCounts = $collectorStatusCounts
    hardGatePreviewStatusCounts = $hardGatePreviewStatusCounts
    replayRequiredNextActionCounts = $replayRequiredNextActionCounts
    sourceRecommendation = $sourceRecommendation
    sourceMissingEvidence = $missingEvidenceRaw
    falseKillSourceRanking = $rankingArray
    missingRequirements = $missingRequirementsArray
    safeNextAction = "Reduce future false-kill pressure by proving current kline freshness and collecting complete replayable DataFreshness rows; do not relax severe stale/outage rows, and review small grace only if near-miss rows have replay snapshots plus acceptable correct-block leakage."
    notAuthorization = "local issue #7 packet only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, orders, OCO modification, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "issue7_filter_block_false_kill_packet=$($packet | ConvertTo-Json -Compress -Depth 8)"
Write-Host "issue7_filter_block_false_kill_status=$status"
Write-Host "issue7_filter_block_false_kill_review_allowed=$($reviewAllowed.ToString().ToLowerInvariant())"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "issue7_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_safe_next_action=$($packet.safeNextAction)"
Write-Host "notAuthorization=$($packet.notAuthorization)"
