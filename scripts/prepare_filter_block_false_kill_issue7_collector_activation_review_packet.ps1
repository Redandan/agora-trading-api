param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [string]$ObservationLog = "target/profit-review/issue7-df-replay-observation-latest.log",
    [string]$ReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
    [string]$Symbol = "BTCUSDT",
    [int]$MaxAgeMinutes = 180,
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
    if ($List -notcontains $Value) { [void]$List.Add($Value) }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for issue #7 collector review arguments."
}

$handoffScript = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_operator_handoff.ps1"
if (-not (Test-Path -LiteralPath $handoffScript)) {
    throw "Missing issue #7 operator handoff script: $handoffScript"
}

$handoffOutput = & $handoffScript `
    -SourceLog $SourceLog `
    -ObservationLog $ObservationLog `
    -ReadinessLogPath $ReadinessLogPath `
    -Symbol $Symbol `
    -MaxAgeMinutes $MaxAgeMinutes `
    *>&1
$handoffText = ($handoffOutput | Out-String -Width 4096)
$handoffJson = Get-LastPrefixedValue -Text $handoffText -Prefix "issue7_operator_handoff_packet="
$handoffPacket = $null
if (-not [string]::IsNullOrWhiteSpace($handoffJson)) {
    $handoffPacket = $handoffJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($null -eq $handoffPacket) {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 operator handoff packet valid JSON"
}

$handoffStatus = ""
$handoffDecision = ""
$closeAllowed = $false
$collectorReviewAllowed = $false
if ($null -ne $handoffPacket) {
    $handoffStatus = [string]$handoffPacket.status
    $handoffDecision = [string]$handoffPacket.handoffDecision
    $closeAllowed = [bool]$handoffPacket.closeAllowed
    $collectorReviewAllowed = [bool]$handoffPacket.evidenceOnlyCollectorReviewAllowed
}

if ($handoffStatus -ne "READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE") {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 handoff ready for evidence collector review"
}
if ($handoffDecision -ne "PREPARE_SEPARATE_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW") {
    Add-MissingRequirement -List $missingRequirements -Value "handoff decision prepares separate evidence collector activation review"
}
if ($closeAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 must remain open before collector activation review"
}
if (-not $collectorReviewAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "evidence-only collector review allowed by preflight"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ISSUE7_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$decision = if ($ready) { "REVIEW_SEPARATE_EVIDENCE_ONLY_COLLECTOR_ENV_DIFF" } else { "REFRESH_ISSUE7_HANDOFF_EVIDENCE" }
$nextAction = if ($ready) {
    "Review this packet before any separate production env/deploy authorization; keep issue #7 open until fresh replayable rows prove the close criteria."
} else {
    "Refresh issue #7 operator handoff before preparing collector activation review."
}

$packet = [ordered]@{
    packetType = "ISSUE7_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_PACKET"
    status = $status
    decision = $decision
    symbol = $Symbol
    issueCloseAllowed = $false
    liveRelaxationAllowed = $false
    collectorActivationAllowed = $false
    deployOrEnvChangeAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    sourceHandoffStatus = $handoffStatus
    sourceHandoffDecision = $handoffDecision
    sourceFalseKillPct = if ($null -ne $handoffPacket) { $handoffPacket.sourceFalseKillPct } else { $null }
    sourceActionableFalseKillPct = if ($null -ne $handoffPacket) { $handoffPacket.sourceActionableFalseKillPct } else { $null }
    sourceExpectedValueProjectedActionableFalseKillPctAfterReview = if ($null -ne $handoffPacket) { $handoffPacket.sourceExpectedValueProjectedActionableFalseKillPctAfterReview } else { $null }
    sourceReplayCandidateRows = if ($null -ne $handoffPacket) { $handoffPacket.sourceReplayCandidateRows } else { $null }
    sourceCompleteReplayableCandidateRows = if ($null -ne $handoffPacket) { $handoffPacket.sourceCompleteReplayableCandidateRows } else { $null }
    proposedSeparateEnvDiff = @(
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
    )
    requiredDisabledEnv = @(
        "TRADING_OKX_ENABLED=false",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
        "TRADING_OCO_POLLER_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "TRADING_FUNDING_ARB_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false"
    )
    requiredBeforeAnyFutureActivation = @(
        "separate explicit production env authorization",
        "separate explicit deploy/restart authorization if runtime env changes",
        "operator accepts rollback criteria and stop conditions",
        "DataFreshnessGuard remains terminal",
        "collector remains evidence-only and must not create live signals, orders, OCO, Telegram, or policy changes"
    )
    postChangeReadOnlyVerification = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1 *> target\profit-review\issue7-df-replay-observation-latest.log",
        ".\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -ReviewDays 14 -ReplayIdDays 3 -Limit 200",
        ".\scripts\prepare_filter_block_false_kill_issue7_close_readiness.ps1 -RequireBlocked",
        ".\scripts\prepare_filter_block_false_kill_issue7_operator_handoff.ps1 -RequireActionable"
    )
    requiredBeforeIssueClose = @(
        "fresh DataFreshnessGuard terminal rows after collector-enabled runtime",
        "stable replayCandidateId rows",
        "complete replayable candidate snapshots",
        "entry/TP/SL candidate plan",
        "EV snapshot",
        "OCO preflight snapshot",
        "hard-gate snapshots",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]"
    )
    stopConditions = @(
        "runtime ERROR appears after collector activation",
        "collector row lacks replayCandidateId",
        "collector creates a live signal",
        "collector sends Telegram",
        "collector places or modifies order/OCO",
        "collector changes policy/scheduler/live flags",
        "DB/grid/fund/Earn/exchange/external backfill mutation appears",
        "complete_replayable_candidate_rows remains 0 after fresh DataFreshnessGuard rows are observed"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only issue #7 evidence collector activation review packet only; does not authorize issue closure, collector activation, deploy, production env changes, DataFreshnessGuard relaxation, DataFreshness shadow review, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[issue7-evidence-collector-activation-review] read-only packet"
Write-Host "scope=READ_ONLY; invokes issue #7 operator handoff only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host $handoffText
Write-Host "issue7_evidence_collector_activation_review_packet=$($packet | ConvertTo-Json -Compress -Depth 12)"
Write-Host "issue7_evidence_collector_activation_review_status=$status"
Write-Host "issue7_evidence_collector_activation_review_decision=$decision"
Write-Host "issue7_close_allowed=false"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "collector_activation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "proposed_separate_env_diff=TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
Write-Host "required_before_issue_close=stable replayCandidateId rows; complete replayable candidate snapshots; entry/TP/SL; EV; OCO; hard-gate snapshots; missing_counterfactual_fields=[]"
Write-Host "issue7_evidence_collector_activation_review_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_evidence_collector_activation_review_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and -not $ready) {
    throw "Issue #7 evidence collector activation review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
