param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireReady
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

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for DataFreshness replay blocker preflight arguments."
}

$decisionScript = Join-Path $PSScriptRoot "prepare_data_freshness_replay_blocker_decision_packet.ps1"
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing DataFreshness replay blocker decision packet script: $decisionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for DataFreshness replay blocker preflight." }

$decisionArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-RequireBlocked"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $decisionOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $decisionScript @decisionArgs 2>&1
    $decisionExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$decisionText = ($decisionOutput | Out-String -Width 4096)
$decisionJson = Get-LastPrefixedValue -Text $decisionText -Prefix "data_freshness_replay_blocker_decision_packet="
$decisionPacket = $null
if (-not [string]::IsNullOrWhiteSpace($decisionJson)) {
    $decisionPacket = $decisionJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($decisionExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "DataFreshness replay blocker decision packet completed" }
if ($null -eq $decisionPacket) { Add-MissingRequirement -List $missingRequirements -Value "data_freshness_replay_blocker_decision_packet valid JSON" }

$sourceStatus = ""
$sourceMatrixFreshness = ""
$sourceLaneStatus = ""
$sourceReadyForOperatorReview = $true
$sourceCompleteReplayRows = -1
$sourceShadowCandidateAllowed = ""
$sourceBlockerDecision = ""
if ($null -ne $decisionPacket) {
    $sourceStatus = [string]$decisionPacket.status
    $sourceMatrixFreshness = [string]$decisionPacket.sourceMatrixFreshnessStatus
    $sourceLaneStatus = [string]$decisionPacket.laneStatus
    $sourceReadyForOperatorReview = [bool]$decisionPacket.readyForOperatorReview
    $sourceCompleteReplayRows = [int]$decisionPacket.completeReplayableCandidateRows
    $sourceShadowCandidateAllowed = [string]$decisionPacket.shadowCandidateReviewAllowed
    $sourceBlockerDecision = [string]$decisionPacket.blockerDecision
}

if ($sourceStatus -ne "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness replay blocker decision packet ready"
}
if ($sourceMatrixFreshness -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($sourceLaneStatus -ne "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE" -and $sourceLaneStatus -ne "BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING") {
    Add-MissingRequirement -List $missingRequirements -Value "source data-freshness-replay lane remains blocked"
}
if ($sourceReadyForOperatorReview) {
    Add-MissingRequirement -List $missingRequirements -Value "source data-freshness-replay lane is not operator-review-ready"
}
if ($sourceCompleteReplayRows -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "source complete replayable candidate rows remain zero"
}
if ($sourceShadowCandidateAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "source shadow candidate review remains disallowed"
}
if ($sourceBlockerDecision -ne "WAIT_FOR_REPLAYABLE_CANDIDATE_EVIDENCE") {
    Add-MissingRequirement -List $missingRequirements -Value "source blocker decision is wait for replayable evidence"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to a DataFreshness replay blocker review; wait for fresh replayCandidateId rows and complete replayable snapshots before any DataFreshness shadow, policy, or live decision."
} else {
    "Refresh the DataFreshness replay blocker decision packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    sourceDecisionPacket = "prepare_data_freshness_replay_blocker_decision_packet.ps1"
    sourceDecisionPacketStatus = $sourceStatus
    sourceMatrixFreshnessStatus = $sourceMatrixFreshness
    sourceLaneStatus = $sourceLaneStatus
    sourceReadyForOperatorReview = $sourceReadyForOperatorReview
    sourceCompleteReplayableCandidateRows = $sourceCompleteReplayRows
    sourceShadowCandidateReviewAllowed = $sourceShadowCandidateAllowed
    preflightDecision = if ($ready) { "PREPARE_REVIEW_ONLY_DATAFRESHNESS_REPLAY_BLOCKER_REVIEW" } else { "REFRESH_SOURCE_DECISION_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        dataFreshnessPolicyRelaxationAllowed = $false
        dataFreshnessShadowReviewAllowed = $false
        collectorActivationAllowed = $false
        liveTradingAllowed = $false
        stagedAddExecutionAllowed = $false
        tinyLiveExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        externalBackfillOrImportAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source DataFreshness replay blocker decision packet is ready",
        "source matrix freshness is FRESH",
        "source data-freshness-replay lane remains blocked",
        "complete_replayable_candidate_rows=0",
        "shadow_candidate_review_allowed=false",
        "review scope is wait/refresh only and does not relax DataFreshnessGuard"
    )
    requiredBeforeAnyFutureShadowOrMutation = @(
        "fresh DataFreshnessGuard terminal rows after replay-id runtime",
        "replayCandidateId linked to the terminal decision",
        "explicit entry/TP/SL candidate snapshot",
        "EV snapshot and OCO plan or explicit OCO infeasibility proof",
        "hard-gate replay that removes only DataFreshnessGuard",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]",
        "separate operator approval for evidence-only collector activation, deploy, env, policy, or live changes"
    )
    explicitNonAuthorizations = @(
        "does not relax DataFreshnessGuard",
        "does not allow DataFreshness shadow review",
        "does not activate replay collector",
        "does not enable live trading",
        "does not enable staged-add or TinyLive execution",
        "does not enable scheduler",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not deploy",
        "does not change production env",
        "does not send Telegram",
        "does not run external backfill or import"
    )
    sourceDecisionPacketSummary = $decisionPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness replay blocker preflight review packet only; does not authorize DataFreshnessGuard relaxation, DataFreshness shadow review, collector activation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[data-freshness-replay-blocker-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_data_freshness_replay_blocker_decision_packet.ps1 only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $decisionText
Write-Host "source_decision_packet=prepare_data_freshness_replay_blocker_decision_packet.ps1 exitCode=$decisionExitCode"
Write-Host "source_decision_packet_status=$sourceStatus"
Write-Host "source_matrix_freshness_status=$sourceMatrixFreshness"
Write-Host "source_data_freshness_replay_lane_status=$sourceLaneStatus"
Write-Host "source_data_freshness_replay_ready_for_operator_review=$($sourceReadyForOperatorReview.ToString().ToLowerInvariant())"
Write-Host "source_complete_replayable_candidate_rows=$sourceCompleteReplayRows"
Write-Host "source_shadow_candidate_review_allowed=$sourceShadowCandidateAllowed"
Write-Host "data_freshness_replay_blocker_preflight_decision=$($packet.preflightDecision)"
Write-Host "data_freshness_policy_relaxation_allowed=false"
Write-Host "data_freshness_shadow_review_allowed=false"
Write-Host "collector_activation_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "tiny_live_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("data_freshness_replay_blocker_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("data_freshness_replay_blocker_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "data_freshness_replay_blocker_preflight_status=$status"
Write-Host "data_freshness_replay_blocker_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness replay blocker preflight review packet only; does not authorize DataFreshnessGuard relaxation, DataFreshness shadow review, collector activation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[data-freshness-replay-blocker-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "DataFreshness replay blocker preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
