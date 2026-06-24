param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [string]$ObservationLog = "target/profit-review/issue7-df-replay-observation-latest.log",
    [string]$ReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
    [string]$RuntimeEvidenceLog = "",
    [string]$Symbol = "BTCUSDT",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-ToNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A") { return $null }
    $clean = $Value.Trim().TrimEnd("%")
    $number = 0.0
    if ([double]::TryParse($clean, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        return $number
    }
    return $null
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if (-not $List.Contains($Value)) { [void]$List.Add($Value) }
}

function Read-OptionalText {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $resolved)) { return "" }
    return Get-Content -Raw -LiteralPath $resolved
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for issue #7 post-activation status arguments."
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
$closeScript = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_close_readiness.ps1"
if (-not (Test-Path -LiteralPath $closeScript)) {
    throw "Missing issue #7 close-readiness script: $closeScript"
}

$closeOutput = & $closeScript -SourceLog $SourceLog -ObservationLog $ObservationLog -MaxAgeMinutes $MaxAgeMinutes *>&1
$closeText = ($closeOutput | Out-String -Width 4096)
$closeJson = Get-LastPrefixedValue -Text $closeText -Prefix "issue7_close_readiness_packet="
$closePacket = $null
if (-not [string]::IsNullOrWhiteSpace($closeJson)) {
    $closePacket = $closeJson | ConvertFrom-Json -ErrorAction Stop
}
if ($null -eq $closePacket) {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 close-readiness packet valid JSON"
}

$readinessPath = Resolve-RepoPath $ReadinessLogPath
$readinessText = ""
$readinessFreshness = "MISSING"
$readinessAgeMinutes = $null
if (-not (Test-Path -LiteralPath $readinessPath)) {
    Add-MissingRequirement -List $missingRequirements -Value "fresh DataFreshness replay evidence readiness log"
} else {
    $readinessItem = Get-Item -LiteralPath $readinessPath
    $readinessAgeMinutes = [math]::Round(((Get-Date) - $readinessItem.LastWriteTime).TotalMinutes, 2)
    $readinessFreshness = if ($readinessAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    if ($readinessFreshness -ne "FRESH") {
        Add-MissingRequirement -List $missingRequirements -Value "fresh DataFreshness replay evidence readiness log"
    }
    $readinessText = Get-Content -Raw -LiteralPath $readinessPath
}

foreach ($marker in @(
        "data_freshness_replay_evidence_readiness_status=",
        "data_freshness_replay_candidate_id_recommendation=",
        "replay_candidate_id_rows=",
        "complete_replayable_candidate_rows=",
        "notAuthorization="
    )) {
    if (-not [string]::IsNullOrWhiteSpace($readinessText) -and $readinessText -notmatch [regex]::Escape($marker)) {
        Add-MissingRequirement -List $missingRequirements -Value "DataFreshness readiness marker: $marker"
    }
}

$runtimeText = Read-OptionalText -Path $RuntimeEvidenceLog
$runtimeEvidenceProvided = -not [string]::IsNullOrWhiteSpace($runtimeText)
$runtimeMarkers = [ordered]@{
    collectorEnabled = $runtimeText -match "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
    okxDisabled = $runtimeText -match "TRADING_OKX_ENABLED=false"
    tinyLiveDisabled = $runtimeText -match "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false"
    eventScanDisabled = $runtimeText -match "EVENT_SCAN_NOTIFICATION_ENABLED=false"
    executionEventDisabled = $runtimeText -match "EXECUTION_EVENT_ENABLED=false"
    ocoDisabled = $runtimeText -match "TRADING_OCO_POLLER_ENABLED=false"
    mcpGuardianDisabled = $runtimeText -match "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false"
    gridDisabled = $runtimeText -match "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false"
    fundingDisabled = $runtimeText -match "TRADING_FUNDING_ARB_ENABLED=false"
    earnDisabled = $runtimeText -match "OKX_EARN_TOPUP_ENABLED=false"
}
$collectorRuntimeState = if (-not $runtimeEvidenceProvided) {
    "NOT_VERIFIED_BY_PACKET"
} elseif (@($runtimeMarkers.Values | Where-Object { -not $_ }).Count -eq 0) {
    "EVIDENCE_ONLY_COLLECTOR_ACTIVE"
} else {
    "RUNTIME_ENV_NOT_EVIDENCE_ONLY"
}

if ($runtimeEvidenceProvided -and $collectorRuntimeState -ne "EVIDENCE_ONLY_COLLECTOR_ACTIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "runtime env is evidence-only collector state"
}

$recentDataStaleSkipCount = Convert-ToNumber (Get-LastPrefixedValue -Text $runtimeText -Prefix "recent_data_stale_skip_count=" -Default "N/A")
$readinessStatus = Get-LastPrefixedValue -Text $readinessText -Prefix "data_freshness_replay_evidence_readiness_status=" -Default "UNKNOWN"
$candidateRecommendation = Get-LastPrefixedValue -Text $readinessText -Prefix "data_freshness_replay_candidate_id_recommendation=" -Default "UNKNOWN"
$replayCandidateRows = Convert-ToNumber (Get-LastPrefixedValue -Text $readinessText -Prefix "replay_candidate_id_rows=" -Default "0")
$completeReplayableRows = Convert-ToNumber (Get-LastPrefixedValue -Text $readinessText -Prefix "complete_replayable_candidate_rows=" -Default "0")
$latestRowTime = Get-LastPrefixedValue -Text $readinessText -Prefix "latest_data_freshness_row_time=" -Default "UNKNOWN"
$rows1d = Convert-ToNumber (Get-LastPrefixedValue -Text $readinessText -Prefix "data_freshness_rows_1d=" -Default "0")
$rows3d = Convert-ToNumber (Get-LastPrefixedValue -Text $readinessText -Prefix "data_freshness_rows_3d=" -Default "0")
$rows7d = Convert-ToNumber (Get-LastPrefixedValue -Text $readinessText -Prefix "data_freshness_rows_7d=" -Default "0")
$missingCounterfactualFields = Get-LastPrefixedValue -Text $readinessText -Prefix "missing_counterfactual_fields=" -Default "[]"

$closeAllowed = ($null -ne $closePacket -and [bool]$closePacket.closeAllowed)
$deploymentRuntimeCurrent = -not (
    $readinessStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE" -or
    $candidateRecommendation -eq "DEPLOYED_RUNTIME_NOT_CURRENT"
)
$freshPostCollectorRowsObserved = (($replayCandidateRows -as [double]) -gt 0)
$completeRowsObserved = (($completeReplayableRows -as [double]) -gt 0)

if (-not $deploymentRuntimeCurrent) {
    Add-MissingRequirement -List $missingRequirements -Value "deployment_runtime_current_for_replay_id=true"
}
if (-not $freshPostCollectorRowsObserved) {
    Add-MissingRequirement -List $missingRequirements -Value "fresh post-collector DataFreshnessGuard terminal rows"
}
if (-not $completeRowsObserved) {
    Add-MissingRequirement -List $missingRequirements -Value "complete replayable post-collector rows"
}
if ($missingCounterfactualFields -ne "[]" -and -not [string]::IsNullOrWhiteSpace($missingCounterfactualFields)) {
    Add-MissingRequirement -List $missingRequirements -Value "missing_counterfactual_fields=[]"
}

$remainingBlocker = "UNKNOWN"
if ($closeAllowed) {
    $remainingBlocker = "NONE"
} elseif ($collectorRuntimeState -eq "RUNTIME_ENV_NOT_EVIDENCE_ONLY") {
    $remainingBlocker = "RUNTIME_ENV_NOT_EVIDENCE_ONLY"
} elseif (-not $deploymentRuntimeCurrent) {
    $remainingBlocker = "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
} elseif (-not $freshPostCollectorRowsObserved) {
    $remainingBlocker = "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
} elseif (-not $completeRowsObserved) {
    $remainingBlocker = "POST_COLLECTOR_REPLAY_ROWS_INCOMPLETE"
} elseif ($missingCounterfactualFields -ne "[]") {
    $remainingBlocker = "COUNTERFACTUAL_FIELDS_STILL_MISSING"
} else {
    $remainingBlocker = "ISSUE7_CLOSE_READINESS_STILL_BLOCKED"
}

$status = if ($closeAllowed) {
    "READY_TO_CLOSE_NOT_LIVE_RELAXATION"
} elseif ($remainingBlocker -eq "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
    "BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS"
} elseif ($remainingBlocker -eq "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
} elseif ($remainingBlocker -eq "POST_COLLECTOR_REPLAY_ROWS_INCOMPLETE" -or $remainingBlocker -eq "COUNTERFACTUAL_FIELDS_STILL_MISSING") {
    "BLOCKED_POST_COLLECTOR_REPLAY_EVIDENCE_INCOMPLETE"
} elseif ($remainingBlocker -eq "RUNTIME_ENV_NOT_EVIDENCE_ONLY") {
    "BLOCKED_RUNTIME_ENV_NOT_EVIDENCE_ONLY"
} else {
    "BLOCKED_NOT_CLOSABLE_REPLAY_EVIDENCE_MISSING"
}

$nextAction = if ($closeAllowed) {
    "Issue #7 can be closed as evidence-ready/review-only; do not relax live policy without separate approval."
} elseif ($remainingBlocker -eq "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
    "Keep the evidence-only collector active and rerun read-only verification after a fresh DataFreshnessGuard terminal row appears."
} elseif ($remainingBlocker -eq "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    "Request separate push/deploy authorization, deploy the current runtime, then rerun read-only replay observation before waiting for fresh replay rows."
} else {
    "Keep issue #7 open and refresh the read-only replay evidence bundle; do not relax DataFreshnessGuard."
}

$packet = [ordered]@{
    packetType = "ISSUE7_COLLECTOR_POST_ACTIVATION_STATUS_PACKET"
    status = $status
    symbol = $Symbol
    closeAllowed = $closeAllowed
    liveRelaxationAllowed = $false
    collectorActivationAllowed = $false
    deployOrEnvChangeAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    collectorRuntimeState = $collectorRuntimeState
    runtimeEvidenceLog = $RuntimeEvidenceLog
    runtimeEvidenceProvided = $runtimeEvidenceProvided
    runtimeEvidenceOnlyMarkers = $runtimeMarkers
    recentDataStaleSkipCount = $recentDataStaleSkipCount
    sourceCloseReadinessStatus = if ($null -ne $closePacket) { $closePacket.status } else { "UNKNOWN" }
    readinessLog = $ReadinessLogPath
    readinessLogFreshness = $readinessFreshness
    readinessLogAgeMinutes = $readinessAgeMinutes
    readinessStatus = $readinessStatus
    candidateRecommendation = $candidateRecommendation
    deploymentRuntimeCurrentForReplayId = $deploymentRuntimeCurrent
    replayCandidateRows = $replayCandidateRows
    completeReplayableCandidateRows = $completeReplayableRows
    latestDataFreshnessRowTime = $latestRowTime
    dataFreshnessRows1d = $rows1d
    dataFreshnessRows3d = $rows3d
    dataFreshnessRows7d = $rows7d
    missingCounterfactualFields = $missingCounterfactualFields
    remainingBlocker = $remainingBlocker
    remainingBlockerDetail = if ($remainingBlocker -eq "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
        "collector is evidence-only active when runtime evidence is supplied, but replayCandidateRows=0 and completeReplayableCandidateRows=0"
    } elseif ($remainingBlocker -eq "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
        "local HEAD differs from deployed replay evidence runtime; deploy current runtime before evaluating post-activation replay rows"
    } else {
        "see missingRequirements"
    }
    requiredBeforeIssueClose = @(
        "fresh post-collector DataFreshnessGuard terminal rows",
        "stable replayCandidateId rows",
        "entry/TP/SL candidate plan",
        "EV snapshot",
        "OCO preflight snapshot",
        "hard-gate snapshots",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]"
    )
    readOnlyVerification = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1 *> target\profit-review\issue7-df-replay-observation-latest.log",
        ".\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -ReviewDays 14 -ReplayIdDays 3 -Limit 200",
        ".\scripts\prepare_filter_block_false_kill_issue7_close_readiness.ps1 -RequireBlocked",
        ".\scripts\prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1 -RequireBlocked"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only issue #7 collector post-activation status packet only; does not close issue #7, deploy, change production env, relax DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable scheduler, place orders, modify OCO, close positions, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[issue7-collector-post-activation-status] read-only packet"
Write-Host "scope=READ_ONLY; reuses saved evidence logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, nginx, GitHub, or policy state changed."
Write-Host $closeText
Write-Host "issue7_collector_post_activation_status_packet=$($packet | ConvertTo-Json -Compress -Depth 12)"
Write-Host "issue7_collector_post_activation_status=$status"
Write-Host "issue7_close_allowed=$($closeAllowed.ToString().ToLowerInvariant())"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "collector_activation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "collector_runtime_state=$collectorRuntimeState"
Write-Host "deployment_runtime_current_for_replay_id=$($deploymentRuntimeCurrent.ToString().ToLowerInvariant())"
Write-Host "fresh_post_collector_data_freshness_rows_observed=$($freshPostCollectorRowsObserved.ToString().ToLowerInvariant())"
Write-Host "complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host "issue7_remaining_blocker=$remainingBlocker"
Write-Host "issue7_collector_post_activation_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_collector_post_activation_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireBlocked -and $closeAllowed) {
    throw "Issue #7 post-activation status was expected to stay blocked, got closeAllowed=true"
}
