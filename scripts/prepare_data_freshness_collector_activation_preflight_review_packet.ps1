param(
    [string]$ReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
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

if ([string]::IsNullOrWhiteSpace($ReadinessLogPath)) { throw "ReadinessLogPath is required." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for DataFreshness collector activation preflight arguments."
}

$decisionScript = Join-Path $PSScriptRoot "prepare_data_freshness_replay_collector_activation_packet.ps1"
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing DataFreshness collector activation decision packet script: $decisionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for DataFreshness collector activation preflight." }

$decisionArgs = @(
    "-ReadinessLogPath", $ReadinessLogPath,
    "-Symbol", $Symbol,
    "-RequireDecisionReady"
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
$decisionJson = Get-LastPrefixedValue -Text $decisionText -Prefix "data_freshness_collector_activation_packet="
$decisionPacket = $null
if (-not [string]::IsNullOrWhiteSpace($decisionJson)) {
    $decisionPacket = $decisionJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($decisionExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "DataFreshness collector activation decision packet completed" }
if ($null -eq $decisionPacket) { Add-MissingRequirement -List $missingRequirements -Value "data_freshness_collector_activation_packet valid JSON" }

$sourceStatus = ""
$sourceDecision = ""
$sourceReadinessStatus = ""
$sourceReplayInputStage = ""
$sourceCompleteReplayRows = -1
if ($null -ne $decisionPacket) {
    $sourceStatus = [string]$decisionPacket.status
    $sourceDecision = [string]$decisionPacket.operatorDecision
    $sourceReadinessStatus = [string]$decisionPacket.sourceReadinessStatus
    $sourceReplayInputStage = [string]$decisionPacket.replayInputStage
    $sourceCompleteReplayRows = [int]$decisionPacket.completeReplayableCandidateRows
}

if ($sourceStatus -ne "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness collector activation decision packet ready"
}
if ($sourceDecision -notin @("PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW", "REVIEW_DISABLED_COLLECTOR_TRACE_ONLY", "COLLECT_EVALUATED_GATE_SNAPSHOTS_NEXT")) {
    Add-MissingRequirement -List $missingRequirements -Value "source collector activation operator decision is review-only"
}
if ($sourceReadinessStatus -notin @("PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS", "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE", "PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS")) {
    Add-MissingRequirement -List $missingRequirements -Value "source readiness status is collector-evidence blocker"
}
if ($sourceReplayInputStage -notin @("PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE", "COLLECTOR_DISABLED_TRACE_ONLY", "PREVIEW_ONLY_NOT_REPLAYABLE")) {
    Add-MissingRequirement -List $missingRequirements -Value "source replay input stage is collector-evidence stage"
}
if ($sourceCompleteReplayRows -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "source complete replayable candidate rows remain zero before activation preflight"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to a separate evidence-only collector activation review; require explicit deploy/env authorization before any production change."
} else {
    "Refresh the DataFreshness collector activation decision packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    sourceDecisionPacket = "prepare_data_freshness_replay_collector_activation_packet.ps1"
    sourceDecisionPacketStatus = $sourceStatus
    sourceOperatorDecision = $sourceDecision
    sourceReadinessStatus = $sourceReadinessStatus
    sourceReplayInputStage = $sourceReplayInputStage
    sourceCompleteReplayableCandidateRows = $sourceCompleteReplayRows
    preflightDecision = if ($ready) { "PREPARE_REVIEW_ONLY_EVIDENCE_COLLECTOR_ACTIVATION" } else { "REFRESH_SOURCE_DECISION_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        evidenceOnlyCollectorReviewAllowed = $true
        collectorActivationAllowed = $false
        deployOrEnvChangeAllowed = $false
        dataFreshnessPolicyRelaxationAllowed = $false
        dataFreshnessShadowReviewAllowed = $false
        liveTradingAllowed = $false
        stagedAddExecutionAllowed = $false
        tinyLiveExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        externalBackfillOrImportAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source DataFreshness collector activation decision packet is ready",
        "source decision is review-only evidence collector activation",
        "source readiness remains a collector-evidence blocker",
        "complete_replayable_candidate_rows=0 before activation",
        "collector_activation_allowed=false",
        "deploy_or_env_change_allowed=false"
    )
    requiredBeforeAnyFutureActivation = @(
        "separate explicit production env authorization for TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
        "separate explicit deploy authorization if runtime behavior changes",
        "rollback criteria and stop conditions accepted by operator",
        "post-change read-only verification plan",
        "confirmation that collector remains evidence-only and DataFreshnessGuard stays terminal"
    )
    explicitNonAuthorizations = @(
        "does not activate DataFreshness replay collector",
        "does not deploy",
        "does not change production env",
        "does not relax DataFreshnessGuard",
        "does not allow DataFreshness shadow review",
        "does not enable live trading",
        "does not enable staged-add or TinyLive execution",
        "does not enable scheduler",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not send Telegram",
        "does not run external backfill or import"
    )
    sourceDecisionPacketSummary = $decisionPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness collector activation preflight review packet only; does not activate collector, deploy, change production env, relax DataFreshnessGuard, allow DataFreshness shadow review, enable live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[data-freshness-collector-activation-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_data_freshness_replay_collector_activation_packet.ps1 only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $decisionText
Write-Host "source_decision_packet=prepare_data_freshness_replay_collector_activation_packet.ps1 exitCode=$decisionExitCode"
Write-Host "source_decision_packet_status=$sourceStatus"
Write-Host "source_collector_activation_operator_decision=$sourceDecision"
Write-Host "source_readiness_status=$sourceReadinessStatus"
Write-Host "source_replay_input_stage=$sourceReplayInputStage"
Write-Host "source_complete_replayable_candidate_rows=$sourceCompleteReplayRows"
Write-Host "data_freshness_collector_activation_preflight_decision=$($packet.preflightDecision)"
Write-Host "evidence_only_collector_review_allowed=true"
Write-Host "collector_activation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "data_freshness_policy_relaxation_allowed=false"
Write-Host "data_freshness_shadow_review_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "tiny_live_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("data_freshness_collector_activation_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("data_freshness_collector_activation_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "data_freshness_collector_activation_preflight_status=$status"
Write-Host "data_freshness_collector_activation_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness collector activation preflight review packet only; does not activate collector, deploy, change production env, relax DataFreshnessGuard, allow DataFreshness shadow review, enable live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[data-freshness-collector-activation-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "DataFreshness collector activation preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
