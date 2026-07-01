param(
    [string]$AuthorizationRequestLogPath = "target/profit-review/profit-operator-authorization-request-latest.log",
    [string]$QuickStatusLogPath = "target/profit-review/profit-operator-quick-status-latest.log",
    [string]$NextExecutionLogPath = "target/profit-review/profit-next-execution-blocker-packet-latest.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [decimal]$MaxProbeNotionalUsdt = 10,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Convert-ToBool {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim() -match "^(?i:true|1|yes)$"
}

function Read-PacketLog {
    param([string]$PathValue, [string]$Prefix, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    $freshness = "MISSING"
    $ageMinutes = $null
    $text = ""
    $packet = $null
    if (-not [string]::IsNullOrWhiteSpace($resolved) -and (Test-Path -LiteralPath $resolved)) {
        $item = Get-Item -LiteralPath $resolved
        $ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
        $freshness = if ($ageMinutes -le $MaxAge) { "FRESH" } else { "STALE" }
        $text = Get-Content -Raw -LiteralPath $resolved
        $packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix $Prefix)
    }
    return [pscustomobject]@{
        Path = $resolved
        Freshness = $freshness
        AgeMinutes = $ageMinutes
        Text = $text
        Packet = $packet
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for aggressive activation packet arguments."
}
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) { throw "MaxProbeNotionalUsdt must be between 0 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$authSource = Read-PacketLog -PathValue $AuthorizationRequestLogPath -Prefix "profit_operator_authorization_request_packet=" -MaxAge $MaxAgeMinutes
$quickSource = Read-PacketLog -PathValue $QuickStatusLogPath -Prefix "profit_operator_quick_status_packet=" -MaxAge $MaxAgeMinutes
$nextSource = Read-PacketLog -PathValue $NextExecutionLogPath -Prefix "profit_next_execution_blocker_packet=" -MaxAge $MaxAgeMinutes

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$riskBlockers = [System.Collections.Generic.List[string]]::new()

if ($authSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit operator authorization request log present" }
if ($authSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit operator authorization request log fresh" }
if ($authSource.Freshness -ne "MISSING" -and $null -eq $authSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_operator_authorization_request_packet valid JSON" }

if ($quickSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit operator quick status log fresh" }
if ($quickSource.Freshness -ne "MISSING" -and $null -eq $quickSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_operator_quick_status_packet valid JSON" }
if ($nextSource.Freshness -eq "MISSING" -and $quickSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit next-execution blocker evidence present" }
if ($nextSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit next-execution blocker log fresh" }
if ($nextSource.Freshness -ne "MISSING" -and $null -eq $nextSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_next_execution_blocker_packet valid JSON" }

$authPacket = $authSource.Packet
$quickPacket = $quickSource.Packet
$nextPacket = $nextSource.Packet

$authorizationReady = Convert-ToBool (Get-PropertyValue -Object $authPacket -Name "authorizationRequestReady")
$liveReadinessConclusion = Get-PropertyValue -Object $authPacket -Name "liveReadinessConclusion"
$nextAuthorizationRequired = Get-PropertyValue -Object $authPacket -Name "nextAuthorizationRequired"
if ($null -ne $authPacket -and -not $authorizationReady) { Add-Unique -List $riskBlockers -Value "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY" }
if ($null -ne $authPacket -and $liveReadinessConclusion -ne "NOT_READY_FOR_LIVE_ENABLEMENT") { Add-Unique -List $riskBlockers -Value "LIVE_READINESS_CONCLUSION_UNEXPECTED" }

$nextExecution = $null
if ($null -ne $quickPacket -and $null -ne $quickPacket.PSObject.Properties["nextExecutionStatus"]) {
    $nextExecution = $quickPacket.nextExecutionStatus
} elseif ($null -ne $nextPacket) {
    $nextExecution = $nextPacket
}

$nextExecutionStatus = Get-PropertyValue -Object $nextExecution -Name "status"
if ([string]::IsNullOrWhiteSpace($nextExecutionStatus)) { $nextExecutionStatus = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_blocker_status=" }
$nextExecutionRoute = Get-PropertyValue -Object $nextExecution -Name "route"
if ([string]::IsNullOrWhiteSpace($nextExecutionRoute)) { $nextExecutionRoute = Get-PropertyValue -Object $nextExecution -Name "profitRoute" }
if ([string]::IsNullOrWhiteSpace($nextExecutionRoute)) { $nextExecutionRoute = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_route=" }
$nextExecutionBlocker = Get-PropertyValue -Object $nextExecution -Name "uniqueBlocker"
if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { $nextExecutionBlocker = Get-PropertyValue -Object $nextExecution -Name "unique_blocker" }
if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { $nextExecutionBlocker = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_unique_blocker=" }
$openOcoPositions = Get-PropertyValue -Object $nextExecution -Name "openOcoPositions"
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-PropertyValue -Object $nextExecution -Name "currentOpenOcoPositions" }
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_open_oco_positions=" }
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_open_oco_positions=" }
$replayCandidateRows = Get-PropertyValue -Object $nextExecution -Name "dataFreshnessReplayCandidateIdRows"
if ([string]::IsNullOrWhiteSpace($replayCandidateRows)) { $replayCandidateRows = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows=" }
if ([string]::IsNullOrWhiteSpace($replayCandidateRows)) { $replayCandidateRows = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "data_freshness_replay_candidate_id_rows=" }
$completeReplayableRows = Get-PropertyValue -Object $nextExecution -Name "dataFreshnessCompleteReplayableCandidateRows"
if ([string]::IsNullOrWhiteSpace($completeReplayableRows)) { $completeReplayableRows = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_data_freshness_complete_replayable_candidate_rows=" }
if ([string]::IsNullOrWhiteSpace($completeReplayableRows)) { $completeReplayableRows = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "data_freshness_complete_replayable_candidate_rows=" }

if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { Add-Unique -List $missingEvidence -Value "current profit next-execution blocker identified" }
if ($nextExecutionBlocker -eq "NO_OPEN_OCO_POSITIONS") { Add-Unique -List $riskBlockers -Value "NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE" }
if ($replayCandidateRows -eq "0" -or $completeReplayableRows -eq "0") { Add-Unique -List $riskBlockers -Value "DATAFRESHNESS_REPLAY_ROWS_MISSING" }

$microProbeRiskAcceptedText = "I explicitly authorize HIGH_RISK_MICRO_LIVE_PROBE for $Symbol with maxNotionalUsdt=$MaxProbeNotionalUsdt, maxOrders=1, no policy relaxation, no grid/fund/Earn actions, immediate rollback on any unexpected order/OCO/Telegram/exchange/DB mutation, and I accept that this can lose money."
$gridRiskAcceptedText = "I explicitly authorize SEPARATE_GRID10_ORDER_PATH_REVIEW for $Symbol with existing-grid activation risk accepted, TRADING_OKX_ENABLED=true reviewed separately, and no createGrid/order execution until the grid authorization bundle and post-env verification are current."
$evidenceAcceleratorText = "I authorize EVIDENCE_ONLY_ACCELERATOR for ${Symbol}: runtime/DataFreshness shadow evidence collection may be reviewed, while TRADING_OKX_ENABLED=false and all live/order/OCO/grid/fund/Earn/Telegram mutations remain disabled."

$microProbeProposedEnvDiff = @(
    "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
    "TRADING_OKX_ENABLED=true",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false"
)
$microProbeRiskAcceptanceConditions = @(
    "operator accepts real-money loss risk up to the full maxNotionalUsdt probe",
    "single probe order only; maxOrders=1 and maxNotionalUsdt=$MaxProbeNotionalUsdt",
    "current BUY/scout candidate, OCO/EV/event-risk gates, and live-readiness bundle must be fresh",
    "no EntryDedup/DataFreshness/live policy relaxation is allowed for the probe",
    "rollback starts immediately on unexpected order/OCO/grid/fund/Earn/Telegram/exchange/DB mutation"
)
$gridProposedEnvDiff = @(
    "TRADING_OKX_ENABLED=true",
    "TRADING_GRID_ENABLED=true",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$gridRiskAcceptanceConditions = @(
    "operator accepts existing active Grid #10 order-path activation risk",
    "capital cap is limited to maxCapitalUsdt=10 unless a separate cap override is approved",
    "createGrid/order execution is still blocked until current grid authorization bundle is ready",
    "trend/event risk blockers remain hard blockers unless separately reviewed",
    "rollback disables TRADING_OKX_ENABLED and grid background automation on any abnormal post-env smoke"
)
$evidenceProposedEnvDiff = @(
    "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
    "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
    "TRADING_OKX_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false"
)
$evidenceRiskAcceptanceConditions = @(
    "operator accepts this path cannot generate profit directly because orders remain disabled",
    "runtime/DataFreshness evidence collection is the only approved purpose",
    "TRADING_OKX_ENABLED and all TinyLive/ScoreBuy/OCO/grid/fund/Earn/Telegram mutation paths must remain false",
    "orderSentEvidence must stay 0; any non-zero order evidence triggers rollback review",
    "collector output is evidence for a later live relaxation decision, not live relaxation itself"
)

$microProbePostEnvReadOnlyVerificationCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
    ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
    ".\scripts\smoke_tiny_live_loss_rca_ssh.ps1",
    ".\scripts\smoke_tiny_live_post_trade_ssh.ps1 -Symbol $Symbol -StrategyId 574 -Side LONG",
    ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
)
$microProbeKillSwitchEnvDiff = @(
    "TRADING_OKX_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$microProbeRollbackCommands = @(
    "apply the micro-probe killSwitchEnvDiff through the approved deploy runbook",
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1"
)
$gridPostEnvReadOnlyVerificationCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -RequireBoardReady",
    ".\scripts\watch_grid_open_readiness_ssh.ps1",
    ".\scripts\prepare_grid_post_env_verification_plan_ssh.ps1 -AcceptAlreadyAppliedEnvDiff -RequirePlanReady",
    ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -RequireVerificationReady",
    ".\scripts\smoke_grid_post_open_ssh.ps1 -GridId 10 -Symbol $Symbol",
    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
)
$gridKillSwitchEnvDiff = @(
    "TRADING_OKX_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$gridRollbackCommands = @(
    "apply the grid killSwitchEnvDiff through the approved deploy runbook",
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1",
    ".\scripts\smoke_grid_post_open_ssh.ps1 -GridId 10 -Symbol $Symbol"
)
$evidencePostEnvReadOnlyVerificationCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
    ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
)
$evidenceKillSwitchEnvDiff = @(
    "TRADING_RUNTIME_EVIDENCE_ENABLED=false",
    "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
    "TRADING_OKX_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$evidenceRollbackCommands = @(
    "apply the evidence-only killSwitchEnvDiff through the approved deploy runbook",
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1",
    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
)

$aggressiveOptions = @(
    [pscustomobject]@{
        optionId = "HIGH_RISK_MICRO_LIVE_PROBE"
        priority = 1
        risk = "HIGH"
        recommendedNow = $false
        status = "BLOCKED_UNTIL_EXPLICIT_OPERATOR_CONFIRMATION_AND_CURRENT_BUY_OCO_EV_GATES"
        maxNotionalUsdt = $MaxProbeNotionalUsdt
        maxOrders = 1
        acceptsKnownBlockers = @($riskBlockers)
        proposedEnvDiff = @($microProbeProposedEnvDiff)
        riskAcceptanceConditions = @($microProbeRiskAcceptanceConditions)
        requiredBeforeExecution = @(
            "exact operator confirmation text",
            "fresh live-readiness bundle",
            "current BUY/scout candidate",
            "OCO preflight pass or explicit no-OCO risk acceptance",
            "EV snapshot pass",
            "event risk R0/R1",
            "runtime evidence enabled and orderSentEvidence=0 before probe",
            "kill switch and rollback env diff prepared"
        )
        postEnvReadOnlyVerificationCommands = @($microProbePostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($microProbeKillSwitchEnvDiff)
        rollbackCommands = @($microProbeRollbackCommands)
        confirmationText = $microProbeRiskAcceptedText
    },
    [pscustomobject]@{
        optionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
        priority = 2
        risk = "MEDIUM_HIGH"
        recommendedNow = $false
        status = "SEPARATE_GRID_AUTHORIZATION_AND_POST_ENV_VERIFICATION_REQUIRED"
        maxCapitalUsdt = [math]::Max([decimal]10, $MaxProbeNotionalUsdt)
        proposedEnvDiff = @($gridProposedEnvDiff)
        riskAcceptanceConditions = @($gridRiskAcceptanceConditions)
        requiredBeforeExecution = @(
            "fresh grid open blocker priority board",
            "grid authorization bundle ready",
            "TRADING_OKX_ENABLED=true env diff explicitly accepted",
            "existing active grid order-path activation risk explicitly accepted",
            "post-env read-only verification plan ready"
        )
        postEnvReadOnlyVerificationCommands = @($gridPostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($gridKillSwitchEnvDiff)
        rollbackCommands = @($gridRollbackCommands)
        confirmationText = $gridRiskAcceptedText
    },
    [pscustomobject]@{
        optionId = "EVIDENCE_ONLY_ACCELERATOR"
        priority = 3
        risk = "MEDIUM"
        recommendedNow = $true
        status = "RECOMMENDED_AGGRESSIVE_NON_ORDER_STEP"
        proposedEnvDiff = @($evidenceProposedEnvDiff)
        riskAcceptanceConditions = @($evidenceRiskAcceptanceConditions)
        requiredBeforeExecution = @(
            "separate runtime/DataFreshness evidence-only env review",
            "TRADING_OKX_ENABLED=false",
            "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
            "scheduler/order/OCO/grid/fund/Earn/Telegram mutation disabled",
            "post-env read-only verification commands accepted"
        )
        postEnvReadOnlyVerificationCommands = @($evidencePostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($evidenceKillSwitchEnvDiff)
        rollbackCommands = @($evidenceRollbackCommands)
        confirmationText = $evidenceAcceleratorText
    }
)

$orderCapableBlockers = [System.Collections.Generic.List[string]]::new()
foreach ($blocker in @($riskBlockers)) { Add-Unique -List $orderCapableBlockers -Value $blocker }
if ($liveReadinessConclusion -ne "READY_FOR_LIVE_ENABLEMENT") { Add-Unique -List $orderCapableBlockers -Value "LIVE_READINESS_NOT_READY" }
if (-not $authorizationReady) { Add-Unique -List $orderCapableBlockers -Value "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY" }
Add-Unique -List $orderCapableBlockers -Value "CURRENT_BUY_OCO_EV_GATES_NOT_CONFIRMED"
Add-Unique -List $orderCapableBlockers -Value "SEPARATE_EXACT_OPERATOR_AUTHORIZATION_REQUIRED"

$selectedAggressivePath = "EVIDENCE_ONLY_ACCELERATOR"
$selectedAggressiveReason = "Orders remain blocked; this is the fastest aggressive step that improves live-readiness evidence without enabling exchange mutation."
$mostAggressiveOrderCapableCandidate = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
$orderCapableExecutionNowAllowed = $false

$aggressiveExecutionQueue = @(
    [pscustomobject]@{
        rank = 1
        optionId = "EVIDENCE_ONLY_ACCELERATOR"
        actionClass = "EVIDENCE_ONLY_ENV_REVIEW"
        recommendedNow = $true
        orderCapable = $false
        executionNowAllowed = $false
        whyFirst = "Fastest aggressive path that can reduce live-readiness uncertainty while keeping TRADING_OKX_ENABLED=false."
        requiredAuthorizationText = $evidenceAcceleratorText
        postEnvReadOnlyVerificationCommands = @($evidencePostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($evidenceKillSwitchEnvDiff)
    },
    [pscustomobject]@{
        rank = 2
        optionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
        actionClass = "ORDER_CAPABLE_GRID_REVIEW"
        recommendedNow = $false
        orderCapable = $true
        executionNowAllowed = $false
        whyNotNow = "Needs fresh grid authorization bundle, post-env read-only verification, and separate acceptance of existing active Grid #10 order-path activation risk."
        blockers = @("GRID_AUTHORIZATION_BUNDLE_NOT_VERIFIED_IN_THIS_PACKET", "POST_ENV_GRID_VERIFICATION_NOT_CURRENT", "SEPARATE_EXACT_OPERATOR_AUTHORIZATION_REQUIRED")
        requiredAuthorizationText = $gridRiskAcceptedText
        postEnvReadOnlyVerificationCommands = @($gridPostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($gridKillSwitchEnvDiff)
    },
    [pscustomobject]@{
        rank = 3
        optionId = "HIGH_RISK_MICRO_LIVE_PROBE"
        actionClass = "ORDER_CAPABLE_MICRO_LIVE_PROBE"
        recommendedNow = $false
        orderCapable = $true
        executionNowAllowed = $false
        whyNotNow = "Live-readiness is not ready and current BUY/OCO/EV gates are not confirmed."
        blockers = @($orderCapableBlockers)
        requiredAuthorizationText = $microProbeRiskAcceptedText
        postEnvReadOnlyVerificationCommands = @($microProbePostEnvReadOnlyVerificationCommands)
        killSwitchEnvDiff = @($microProbeKillSwitchEnvDiff)
    }
)

$packetReady = $missingEvidence.Count -eq 0
$status = if ($packetReady) {
    "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING"
}
$decision = if ($packetReady) {
    "REVIEW_HIGH_RISK_MICRO_PROBE_OR_EVIDENCE_ACCELERATOR_SEPARATELY"
} else {
    "REFRESH_PROFIT_SOURCE_EVIDENCE_BEFORE_AGGRESSIVE_REVIEW"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    authorizationRequestReady = $authorizationReady
    liveReadinessConclusion = $liveReadinessConclusion
    nextAuthorizationRequired = $nextAuthorizationRequired
    nextExecutionStatus = $nextExecutionStatus
    nextExecutionRoute = $nextExecutionRoute
    nextExecutionUniqueBlocker = $nextExecutionBlocker
    openOcoPositions = $openOcoPositions
    dataFreshnessReplayCandidateIdRows = $replayCandidateRows
    dataFreshnessCompleteReplayableCandidateRows = $completeReplayableRows
    aggressiveOptions = @($aggressiveOptions)
    selectedAggressivePath = $selectedAggressivePath
    selectedAggressiveReason = $selectedAggressiveReason
    mostAggressiveOrderCapableCandidate = $mostAggressiveOrderCapableCandidate
    orderCapableExecutionNowAllowed = $orderCapableExecutionNowAllowed
    orderCapableBlockers = @($orderCapableBlockers)
    aggressiveExecutionQueue = @($aggressiveExecutionQueue)
    proposedEnvDiffPlan = [pscustomobject]@{
        highRiskMicroLiveProbe = @($microProbeProposedEnvDiff)
        grid10ExistingActiveGridOrderPath = @($gridProposedEnvDiff)
        evidenceOnlyAccelerator = @($evidenceProposedEnvDiff)
    }
    riskAcceptanceConditions = [pscustomobject]@{
        highRiskMicroLiveProbe = @($microProbeRiskAcceptanceConditions)
        grid10ExistingActiveGridOrderPath = @($gridRiskAcceptanceConditions)
        evidenceOnlyAccelerator = @($evidenceRiskAcceptanceConditions)
    }
    postEnvReadOnlyVerificationPlan = [pscustomobject]@{
        highRiskMicroLiveProbe = @($microProbePostEnvReadOnlyVerificationCommands)
        grid10ExistingActiveGridOrderPath = @($gridPostEnvReadOnlyVerificationCommands)
        evidenceOnlyAccelerator = @($evidencePostEnvReadOnlyVerificationCommands)
    }
    killSwitchPlan = [pscustomobject]@{
        highRiskMicroLiveProbe = @($microProbeKillSwitchEnvDiff)
        grid10ExistingActiveGridOrderPath = @($gridKillSwitchEnvDiff)
        evidenceOnlyAccelerator = @($evidenceKillSwitchEnvDiff)
    }
    rollbackCommands = [pscustomobject]@{
        highRiskMicroLiveProbe = @($microProbeRollbackCommands)
        grid10ExistingActiveGridOrderPath = @($gridRollbackCommands)
        evidenceOnlyAccelerator = @($evidenceRollbackCommands)
    }
    primaryRecommendation = "Prefer $selectedAggressivePath now; review $mostAggressiveOrderCapableCandidate as the next order-capable candidate, but keep orderCapableExecutionNowAllowed=false until its fresh bundle and exact authorization clear."
    riskBlockers = @($riskBlockers)
    missingEvidence = @($missingEvidence)
    requiredExplicitAuthorizationTexts = @($microProbeRiskAcceptedText, $gridRiskAcceptedText, $evidenceAcceleratorText)
    rollbackCriteria = @(
        "any unexpected order/OCO/grid/fund/Earn/Telegram/exchange/DB mutation",
        "orderSentEvidence > 0 before a probe is explicitly authorized",
        "live-readiness bundle reports new blocker",
        "runtime logs show new errors",
        "daily loss or hard-stop gate trips",
        "OCO health abnormal without explicit acceptance"
    )
    allowedActions = @("operator review", "read-only evidence refresh", "prepare separate exact authorization")
    forbiddenActions = @(
        "enable live trading from this packet",
        "enable scheduler from this packet",
        "place orders from this packet",
        "execute TinyLive from this packet",
        "send Telegram from this packet",
        "modify or cancel OCO from this packet",
        "change production env from this packet",
        "deploy from this packet",
        "relax EntryDedup/DataFreshness/live policy from this packet"
    )
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    deployOrEnvChangeAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only aggressive activation operator packet only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-aggressive-activation-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads existing local profit authorization, quick-status, and next-execution logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "profit_aggressive_activation_status=$status"
Write-Host "profit_aggressive_activation_decision=$decision"
Write-Host "profit_aggressive_activation_authorization_request_ready=$(([string]$authorizationReady).ToLowerInvariant())"
Write-Host "profit_aggressive_activation_live_readiness_conclusion=$liveReadinessConclusion"
Write-Host "profit_aggressive_activation_next_authorization_required=$nextAuthorizationRequired"
Write-Host "profit_aggressive_activation_next_execution_status=$nextExecutionStatus"
Write-Host "profit_aggressive_activation_next_execution_route=$nextExecutionRoute"
Write-Host "profit_aggressive_activation_next_execution_unique_blocker=$nextExecutionBlocker"
Write-Host "profit_aggressive_activation_open_oco_positions=$openOcoPositions"
Write-Host "profit_aggressive_activation_data_freshness_replay_candidate_id_rows=$replayCandidateRows"
Write-Host "profit_aggressive_activation_data_freshness_complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host ("profit_aggressive_activation_options=" + (ConvertTo-Json -Compress -Depth 10 @($aggressiveOptions)))
Write-Host "profit_aggressive_activation_selected_path=$selectedAggressivePath"
Write-Host "profit_aggressive_activation_order_capable_candidate=$mostAggressiveOrderCapableCandidate"
Write-Host "profit_aggressive_activation_order_capable_execution_now_allowed=$(([string]$orderCapableExecutionNowAllowed).ToLowerInvariant())"
Write-Host ("profit_aggressive_activation_order_capable_blockers=" + (ConvertTo-Json -Compress @($orderCapableBlockers)))
Write-Host ("profit_aggressive_activation_execution_queue=" + (ConvertTo-Json -Compress -Depth 10 @($aggressiveExecutionQueue)))
Write-Host ("profit_aggressive_activation_proposed_env_diff_plan=" + (ConvertTo-Json -Compress -Depth 10 $packet.proposedEnvDiffPlan))
Write-Host ("profit_aggressive_activation_risk_acceptance_conditions=" + (ConvertTo-Json -Compress -Depth 10 $packet.riskAcceptanceConditions))
Write-Host ("profit_aggressive_activation_post_env_read_only_verification_plan=" + (ConvertTo-Json -Compress -Depth 10 $packet.postEnvReadOnlyVerificationPlan))
Write-Host ("profit_aggressive_activation_kill_switch_plan=" + (ConvertTo-Json -Compress -Depth 10 $packet.killSwitchPlan))
Write-Host ("profit_aggressive_activation_rollback_commands=" + (ConvertTo-Json -Compress -Depth 10 $packet.rollbackCommands))
Write-Host ("profit_aggressive_activation_risk_blockers=" + (ConvertTo-Json -Compress @($riskBlockers)))
Write-Host ("profit_aggressive_activation_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("profit_aggressive_activation_required_authorization_texts=" + (ConvertTo-Json -Compress @($packet.requiredExplicitAuthorizationTexts)))
Write-Host ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=read-only aggressive activation operator packet only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[profit-aggressive-activation-operator-packet] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Profit aggressive activation operator packet is not ready: $status; missingEvidence=$(@($missingEvidence) -join '; ')"
}
