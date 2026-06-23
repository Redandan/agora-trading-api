param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-ReviewItem {
    param([object[]]$Items, [string]$Lane)
    $match = @($Items | Where-Object { [string]$_.lane -eq $Lane } | Select-Object -First 1)
    if ($match.Count -lt 1) { return $null }
    return $match[0]
}

function Get-MarkerValue {
    param([object[]]$Markers, [string]$Name, [string]$Default = "")
    $prefix = "$Name="
    $line = @($Markers | ForEach-Object { [string]$_ } | Where-Object { $_.StartsWith($prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($prefix.Length).Trim()
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for DataFreshness replay blocker decision packet arguments."
}

$reviewDir = if ([System.IO.Path]::IsPathRooted($ReviewOutputDir)) {
    $ReviewOutputDir
} else {
    Join-Path (Split-Path -Parent $PSScriptRoot) $ReviewOutputDir
}
$latestPointer = Join-Path $reviewDir "latest-profit-operator-matrix.path"
if (-not (Test-Path -LiteralPath $latestPointer)) {
    throw "Missing latest profit operator matrix pointer: $latestPointer"
}

$matrixPath = (Get-Content -Raw -LiteralPath $latestPointer).Trim()
if ([string]::IsNullOrWhiteSpace($matrixPath)) {
    throw "Latest profit operator matrix pointer is empty: $latestPointer"
}
if (-not [System.IO.Path]::IsPathRooted($matrixPath)) {
    $matrixPath = Join-Path (Split-Path -Parent $PSScriptRoot) $matrixPath
}
if (-not (Test-Path -LiteralPath $matrixPath)) {
    throw "Latest profit operator matrix file not found: $matrixPath"
}

$matrixFile = Get-Item -LiteralPath $matrixPath
$matrixAgeMinutes = [math]::Round(((Get-Date) - $matrixFile.LastWriteTime).TotalMinutes, 2)
$matrixFreshness = if ($matrixAgeMinutes -le $MatrixMaxAgeMinutes) { "FRESH" } else { "STALE" }
$matrixText = Get-Content -Raw -LiteralPath $matrixPath
$matrixJson = Get-LastPrefixedValue -Text $matrixText -Prefix "profit_operator_review_matrix_packet="
$matrixPacket = $null
if (-not [string]::IsNullOrWhiteSpace($matrixJson)) {
    $matrixPacket = $matrixJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($null -eq $matrixPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_review_matrix_packet valid JSON" }
if ($matrixFreshness -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH" }

$matrixStatus = ""
$readinessStatus = ""
$evidenceWatchStatus = ""
$dataFreshnessShadowStatus = ""
$dataFreshnessLane = $null
if ($null -ne $matrixPacket) {
    $matrixStatus = [string]$matrixPacket.status
    $readinessStatus = [string]$matrixPacket.readinessStatus
    $evidenceWatchStatus = [string]$matrixPacket.evidenceWatchStatus
    $dataFreshnessShadowStatus = [string]$matrixPacket.dataFreshnessShadowCandidateStatus
    $dataFreshnessLane = Get-ReviewItem -Items @($matrixPacket.reviewItems) -Lane "data-freshness-replay"
}

if ($matrixStatus -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "profit operator matrix has review-ready items"
}
if ($null -eq $dataFreshnessLane) {
    Add-MissingRequirement -List $missingRequirements -Value "data-freshness-replay lane present"
}

$laneStatus = if ($null -ne $dataFreshnessLane) { [string]$dataFreshnessLane.status } else { "" }
$laneReady = if ($null -ne $dataFreshnessLane) { [bool]$dataFreshnessLane.readyForOperatorReview } else { $false }
$evidenceMarkers = if ($null -ne $dataFreshnessLane) { @($dataFreshnessLane.evidenceMarkers) } else { @() }
$laneMissing = if ($null -ne $dataFreshnessLane) { @($dataFreshnessLane.missingRequirements) } else { @() }

$counterfactualEvidenceClass = Get-MarkerValue -Markers $evidenceMarkers -Name "counterfactual_evidence_class" -Default (Get-MarkerValue -Markers $evidenceMarkers -Name "counterfactualEvidenceClass" -Default "")
$replayInputStage = Get-MarkerValue -Markers $evidenceMarkers -Name "replay_input_stage" -Default ""
$collectorStatusCounts = Get-MarkerValue -Markers $evidenceMarkers -Name "collector_status_counts" -Default ""
$hardGatePreviewStatusCounts = Get-MarkerValue -Markers $evidenceMarkers -Name "hard_gate_preview_status_counts" -Default ""
$replayInputNextAction = Get-MarkerValue -Markers $evidenceMarkers -Name "replay_input_next_action" -Default ""
$completeReplayRowsRaw = Get-MarkerValue -Markers $evidenceMarkers -Name "complete_replayable_candidate_rows" -Default "0"
$previewOnlyRowsRaw = Get-MarkerValue -Markers $evidenceMarkers -Name "preview_only_input_rows" -Default "0"
$missingCounterfactualFieldsRaw = Get-MarkerValue -Markers $evidenceMarkers -Name "missing_counterfactual_fields" -Default "[]"
$shadowCandidateAllowedRaw = Get-MarkerValue -Markers $evidenceMarkers -Name "shadow_candidate_review_allowed" -Default "false"
$profitWatchReason = Get-MarkerValue -Markers $evidenceMarkers -Name "profit_evidence_watch_reason" -Default ""
$profitWatchRecommendation = Get-MarkerValue -Markers $evidenceMarkers -Name "profit_evidence_watch_replay_recommendation" -Default ""

$completeReplayRows = 0
[void][int]::TryParse($completeReplayRowsRaw, [ref]$completeReplayRows)
$previewOnlyRows = 0
[void][int]::TryParse($previewOnlyRowsRaw, [ref]$previewOnlyRows)

if ($laneStatus -ne "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE" -and $laneStatus -ne "BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING") {
    Add-MissingRequirement -List $missingRequirements -Value "data-freshness-replay lane is a replay blocker"
}
if ($laneReady) {
    Add-MissingRequirement -List $missingRequirements -Value "data-freshness-replay lane remains not operator-review-ready"
}
if ($completeReplayRows -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "complete replayable rows are still blocked"
}

$readyAsBlocker = $missingRequirements.Count -eq 0
$status = if ($readyAsBlocker) { "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($readyAsBlocker) {
    "Attach this blocker packet to operator review; wait for fresh replayCandidateId rows and complete replayable snapshots before any DataFreshness shadow or policy decision."
} else {
    "Refresh the read-only profit operator matrix before using this DataFreshness replay blocker decision packet."
}

$packet = [pscustomobject]@{
    packetType = "DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_PACKET"
    status = $status
    symbol = $Symbol
    sourceMatrixPath = $matrixPath
    sourceMatrixFreshnessStatus = $matrixFreshness
    sourceMatrixAgeMinutes = $matrixAgeMinutes
    sourceMatrixStatus = $matrixStatus
    readinessStatus = $readinessStatus
    evidenceWatchStatus = $evidenceWatchStatus
    dataFreshnessShadowCandidateStatus = $dataFreshnessShadowStatus
    lane = "data-freshness-replay"
    laneStatus = $laneStatus
    readyForOperatorReview = $laneReady
    counterfactualEvidenceClass = $counterfactualEvidenceClass
    replayInputStage = $replayInputStage
    collectorStatusCounts = $collectorStatusCounts
    hardGatePreviewStatusCounts = $hardGatePreviewStatusCounts
    replayInputNextAction = $replayInputNextAction
    completeReplayableCandidateRows = $completeReplayRows
    previewOnlyInputRows = $previewOnlyRows
    missingCounterfactualFields = $missingCounterfactualFieldsRaw
    shadowCandidateReviewAllowed = $shadowCandidateAllowedRaw
    profitEvidenceWatchReason = $profitWatchReason
    profitEvidenceWatchReplayRecommendation = $profitWatchRecommendation
    blockerDecision = "WAIT_FOR_REPLAYABLE_CANDIDATE_EVIDENCE"
    operatorDecisionChoices = @(
        "accept blocker and wait for fresh replayCandidateId rows",
        "request fresh read-only SSH profit matrix refresh",
        "reject any DataFreshness policy relaxation or shadow review based on historical proxy rows"
    )
    evidenceChecklist = @(
        "source matrix freshness is FRESH",
        "data-freshness-replay lane is blocked",
        "counterfactual evidence class is not complete replayable candidate snapshot",
        "complete_replayable_candidate_rows=0",
        "missing counterfactual fields include liveSignalId, replayCandidateId, entry/TP/SL plan, EV snapshot, OCO plan, and complete replayable rows",
        "shadow_candidate_review_allowed=false"
    )
    requiredBeforeShadowReview = @(
        "fresh DataFreshnessGuard terminal rows after replay-id runtime",
        "replayCandidateId linked to the terminal decision",
        "explicit entry/TP/SL candidate snapshot",
        "EV snapshot",
        "OCO plan or explicit OCO infeasibility proof",
        "hard-gate replay that removes only DataFreshnessGuard",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]"
    )
    forbiddenActions = @(
        "relax DataFreshnessGuard",
        "enable live trading",
        "enable staged-add or tiny-live execution",
        "place orders",
        "modify or cancel OCO",
        "change production env",
        "deploy",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    evidenceMarkers = @($evidenceMarkers)
    laneMissingRequirements = @($laneMissing)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness replay blocker operator decision packet only; does not authorize DataFreshnessGuard relaxation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or strategy/filter changes"
}

Write-Host "[data-freshness-replay-blocker-decision-packet] read-only packet"
Write-Host "scope=READ_ONLY; reuses latest profit operator matrix only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_matrix_path=$matrixPath"
Write-Host "source_matrix_freshness_status=$matrixFreshness"
Write-Host "source_matrix_age_minutes=$matrixAgeMinutes"
Write-Host "profit_operator_review_matrix_status=$matrixStatus"
Write-Host "profit_readiness_brief_status=$readinessStatus"
Write-Host "profit_evidence_watch_status=$evidenceWatchStatus"
Write-Host "data_freshness_shadow_candidate_packet_status=$dataFreshnessShadowStatus"
Write-Host "data_freshness_replay_lane_status=$laneStatus"
Write-Host "data_freshness_replay_ready_for_operator_review=$($laneReady.ToString().ToLowerInvariant())"
Write-Host "counterfactual_evidence_class=$counterfactualEvidenceClass"
Write-Host "replay_input_stage=$replayInputStage"
Write-Host "collector_status_counts=$collectorStatusCounts"
Write-Host "hard_gate_preview_status_counts=$hardGatePreviewStatusCounts"
Write-Host "replay_input_next_action=$replayInputNextAction"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "preview_only_input_rows=$previewOnlyRows"
Write-Host "missing_counterfactual_fields=$missingCounterfactualFieldsRaw"
Write-Host "shadow_candidate_review_allowed=$shadowCandidateAllowedRaw"
Write-Host "data_freshness_policy_relaxation_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("data_freshness_replay_blocker_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("data_freshness_replay_blocker_decision_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "data_freshness_replay_blocker_decision_status=$status"
Write-Host "data_freshness_replay_blocker_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness replay blocker operator decision packet only; does not authorize DataFreshnessGuard relaxation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or strategy/filter changes"
Write-Host "[data-freshness-replay-blocker-decision-packet] read-only check complete"

if ($RequireBlocked -and -not $readyAsBlocker) {
    throw "DataFreshness replay blocker decision packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
