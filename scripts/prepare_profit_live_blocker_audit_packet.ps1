param(
    [string]$PriorityDecisionLogPath = "target/profit-review/profit-operator-priority-decision-brief-latest.log",
    [string]$TrailingDryRunLogPath = "target/profit-review/trailing-stop-dry-run-operator-decision-packet-latest.log",
    [string]$Strategy485RiskLogPath = "target/profit-review/strategy485-risk-reduction-operator-decision-packet-latest.log",
    [string]$Strategy485RiskEscalationLogPath = "target/profit-review/strategy485-risk-escalation-brief-latest.log",
    [string]$EntryDedupLogPath = "target/profit-review/entry-dedup-semantics-operator-decision-packet-latest.log",
    [string]$DataFreshnessReplayBlockerLogPath = "target/profit-review/data-freshness-replay-blocker-decision-packet-latest.log",
    [string]$DataFreshnessCollectorLogPath = "target/profit-review/data-freshness-replay-collector-activation-packet-latest.log",
    [string]$TpSlOcoLogPath = "target/profit-review/tp-sl-oco-feasibility-operator-packet-latest.log",
    [string]$Strategy574TinyLivePreflightLogPath = "target/profit-review/strategy574-tiny-live-governance-preflight-review-packet-latest.log",
    [string]$GovernanceRelaxationPreflightLogPath = "target/profit-review/governance-relaxation-preflight-review-packet-latest.log",
    [string]$GovernanceRelaxationReviewLogPath = "target/profit-review/governance-relaxation-review-packet-latest.log",
    [int]$MaxAgeMinutes = 360,
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireAuditReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Read-Lane {
    param(
        [string]$Lane,
        [string]$PathValue,
        [string]$StatusPrefix,
        [string]$PacketPrefix,
        [string[]]$ReadyStatuses,
        [string[]]$BlockedStatusHints,
        [string]$FallbackNextAction
    )

    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{
            lane = $Lane
            sourceLogPath = $resolved
            sourceLogFreshness = "MISSING"
            sourceLogAgeMinutes = $null
            sourceStatus = ""
            classification = "EVIDENCE_MISSING"
            readyForOperatorReview = $false
            liveReady = $false
            missingRequirements = @("source log missing")
            nextAction = "Refresh or generate the read-only $Lane packet before live review."
        }
    }

    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    $freshness = if ($age -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    $text = Get-Content -Raw -LiteralPath $resolved
    $sourceStatus = Get-LastPrefixedValue -Text $text -Prefix $StatusPrefix
    $packetJson = Get-LastPrefixedValue -Text $text -Prefix $PacketPrefix
    $packet = $null
    $packetMissing = @()
    if (-not [string]::IsNullOrWhiteSpace($packetJson)) {
        try {
            $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
        } catch {
            $packetMissing = @("packet JSON parse failed")
        }
    } else {
        $packetMissing = @("packet JSON missing")
    }

    $packetMissingRequirements = if ($null -ne $packet -and $null -ne $packet.missingRequirements) {
        @($packet.missingRequirements)
    } else {
        @()
    }
    $nextAction = if ($null -ne $packet -and -not [string]::IsNullOrWhiteSpace([string]$packet.nextAction)) {
        [string]$packet.nextAction
    } else {
        $FallbackNextAction
    }

    $classification = "BLOCKED_OR_NOT_READY"
    if ($freshness -ne "FRESH") {
        $classification = "STALE_EVIDENCE"
    } elseif ([string]::IsNullOrWhiteSpace($sourceStatus)) {
        $classification = "EVIDENCE_INCOMPLETE"
    } elseif ($sourceStatus -in $ReadyStatuses) {
        $classification = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
    } else {
        foreach ($hint in $BlockedStatusHints) {
            if ($sourceStatus -like "*$hint*") {
                $classification = "BLOCKED_REVIEW_ONLY"
                break
            }
        }
    }

    $allMissing = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @($packetMissing + $packetMissingRequirements)) { Add-Unique -List $allMissing -Value ([string]$value) }
    if ($freshness -ne "FRESH") { Add-Unique -List $allMissing -Value "source log stale" }
    if ([string]::IsNullOrWhiteSpace($sourceStatus)) { Add-Unique -List $allMissing -Value "source status missing" }

    return [pscustomobject]@{
        lane = $Lane
        sourceLogPath = $resolved
        sourceLogFreshness = $freshness
        sourceLogAgeMinutes = $age
        sourceStatus = $sourceStatus
        classification = $classification
        readyForOperatorReview = ($classification -eq "READY_FOR_OPERATOR_REVIEW_NOT_LIVE")
        liveReady = $false
        missingRequirements = @($allMissing)
        nextAction = $nextAction
    }
}

function Read-GovernanceRelaxationLane {
    $preflightPath = Resolve-RepoPath -PathValue $GovernanceRelaxationPreflightLogPath
    if (Test-Path -LiteralPath $preflightPath) {
        return Read-Lane -Lane "governance-relaxation" -PathValue $GovernanceRelaxationPreflightLogPath -StatusPrefix "governance_relaxation_preflight_status=" -PacketPrefix "governance_relaxation_preflight_review_packet=" -ReadyStatuses @("READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY", "NO_EVIDENCE") -FallbackNextAction "Refresh governance relaxation packet before considering policy review."
    }

    $reviewLane = Read-Lane -Lane "governance-relaxation" -PathValue $GovernanceRelaxationReviewLogPath -StatusPrefix "governance_relaxation_review_packet_status=" -PacketPrefix "governance_relaxation_review_packet=" -ReadyStatuses @("REVIEW_REQUIRED_NOT_POLICY_CHANGE", "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY", "NO_EVIDENCE") -FallbackNextAction "Refresh governance relaxation review evidence before considering policy review."
    if ($reviewLane.classification -eq "EVIDENCE_MISSING") {
        return $reviewLane
    }

    $reviewLane | Add-Member -NotePropertyName sourceFallback -NotePropertyValue "governance_relaxation_review_packet" -Force
    return $reviewLane
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit live blocker audit arguments."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$lanes = @(
    Read-Lane -Lane "profit-priority" -PathValue $PriorityDecisionLogPath -StatusPrefix "profit_operator_priority_decision_brief_status=" -PacketPrefix "profit_operator_priority_decision_brief_packet=" -ReadyStatuses @("READY_FOR_OPERATOR_DECISION_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Refresh the profit priority decision brief."
    Read-Lane -Lane "trailing-stop-dry-run" -PathValue $TrailingDryRunLogPath -StatusPrefix "trailing_stop_dry_run_operator_decision_status=" -PacketPrefix "trailing_stop_dry_run_operator_decision_packet=" -ReadyStatuses @("READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Keep trailing as dry-run operator review only."
    Read-Lane -Lane "strategy485-risk-reduction" -PathValue $Strategy485RiskLogPath -StatusPrefix "strategy485_risk_reduction_operator_decision_status=" -PacketPrefix "strategy485_risk_reduction_operator_decision_packet=" -ReadyStatuses @("READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Keep strategy485 risk reduction as shadow/operator review only."
    Read-Lane -Lane "strategy485-risk-escalation" -PathValue $Strategy485RiskEscalationLogPath -StatusPrefix "strategy485_risk_escalation_brief_status=" -PacketPrefix "strategy485_risk_escalation_brief_packet=" -ReadyStatuses @("READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY", "NO_EVIDENCE") -FallbackNextAction "Refresh strategy485 risk escalation evidence after the exit-side decision brief."
    Read-Lane -Lane "entry-dedup-semantics" -PathValue $EntryDedupLogPath -StatusPrefix "entry_dedup_semantics_operator_decision_status=" -PacketPrefix "entry_dedup_semantics_operator_decision_packet=" -ReadyStatuses @("READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Keep EntryDedup semantics as shadow review only."
    Read-Lane -Lane "data-freshness-replay-blocker" -PathValue $DataFreshnessReplayBlockerLogPath -StatusPrefix "data_freshness_replay_blocker_decision_status=" -PacketPrefix "data_freshness_replay_blocker_decision_packet=" -ReadyStatuses @("READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Collect replayable DataFreshness rows before policy review."
    Read-Lane -Lane "data-freshness-collector-activation" -PathValue $DataFreshnessCollectorLogPath -StatusPrefix "data_freshness_collector_activation_status=" -PacketPrefix "data_freshness_collector_activation_packet=" -ReadyStatuses @("READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Prepare separate evidence-only collector activation review before any env change."
    Read-Lane -Lane "tp-sl-oco-feasibility" -PathValue $TpSlOcoLogPath -StatusPrefix "tp_sl_oco_feasibility_status=" -PacketPrefix "tp_sl_oco_feasibility_operator_packet=" -ReadyStatuses @("READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Keep TP/SL/OCO feasibility as review-only."
    Read-Lane -Lane "strategy574-tiny-live-governance" -PathValue $Strategy574TinyLivePreflightLogPath -StatusPrefix "strategy574_tiny_live_governance_preflight_status=" -PacketPrefix "strategy574_tiny_live_governance_preflight_review_packet=" -ReadyStatuses @("READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE") -BlockedStatusHints @("BLOCKED", "PENDING", "NOT_READY") -FallbackNextAction "Keep Strategy574/TinyLive governance as blocked review-only evidence."
    Read-GovernanceRelaxationLane
)

$readyReviewCount = @($lanes | Where-Object { $_.classification -eq "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" }).Count
$blockedCount = @($lanes | Where-Object { $_.classification -in @("BLOCKED_REVIEW_ONLY", "BLOCKED_OR_NOT_READY") }).Count
$missingCount = @($lanes | Where-Object { $_.classification -eq "EVIDENCE_MISSING" }).Count
$staleCount = @($lanes | Where-Object { $_.classification -eq "STALE_EVIDENCE" }).Count
$incompleteCount = @($lanes | Where-Object { $_.classification -eq "EVIDENCE_INCOMPLETE" }).Count

$globalMissing = [System.Collections.Generic.List[string]]::new()
foreach ($lane in $lanes) {
    if ($lane.classification -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
        Add-Unique -List $globalMissing -Value ("{0}: {1}" -f $lane.lane, $lane.classification)
    }
}
Add-Unique -List $globalMissing -Value "separate explicit operator authorization is required before any live/order/scheduler/env/Telegram/policy mutation"

$status = if ($missingCount -gt 0 -or $staleCount -gt 0 -or $incompleteCount -gt 0) {
    "BLOCKED_REFRESH_EVIDENCE_BEFORE_LIVE_REVIEW"
} else {
    "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_LIVE_BLOCKER_AUDIT_PACKET"
    status = $status
    symbol = $Symbol
    liveReadinessConclusion = "NOT_READY_FOR_LIVE_ENABLEMENT"
    laneCount = @($lanes).Count
    readyReviewCount = $readyReviewCount
    blockedCount = $blockedCount
    missingEvidenceCount = $missingCount
    staleEvidenceCount = $staleCount
    incompleteEvidenceCount = $incompleteCount
    lanes = @($lanes)
    primaryBlockers = @($globalMissing)
    allowedActions = @(
        "operator review",
        "read-only evidence refresh",
        "shadow/dry-run design discussion"
    )
    forbiddenActions = @(
        "enable live trading",
        "enable scheduler",
        "place orders",
        "execute TinyLive",
        "send Telegram",
        "modify or cancel OCO",
        "close positions",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($globalMissing)
    nextAction = "Use this audit to choose the next read-only evidence refresh; do not treat any review-ready lane as live enablement approval."
    notAuthorization = "read-only profit live blocker audit only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-live-blocker-audit-packet] read-only audit"
Write-Host "scope=READ_ONLY; reads existing local profit-review logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "profit_live_blocker_audit_lane_count=$(@($lanes).Count)"
Write-Host "profit_live_blocker_ready_review_count=$readyReviewCount"
Write-Host "profit_live_blocker_missing_evidence_count=$missingCount"
Write-Host "profit_live_blocker_stale_evidence_count=$staleCount"
Write-Host "profit_live_blocker_incomplete_evidence_count=$incompleteCount"
Write-Host "profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("profit_live_blocker_audit_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($lanes)))
Write-Host ("profit_live_blocker_audit_missing_requirements=" + (ConvertTo-Json -Compress @($globalMissing)))
Write-Host ("profit_live_blocker_audit_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_live_blocker_audit_status=$status"
Write-Host "profit_live_blocker_audit_next_action=$($packet.nextAction)"
Write-Host "notAuthorization=read-only profit live blocker audit only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[profit-live-blocker-audit-packet] read-only check complete"

if ($RequireAuditReady -and ($status -notin @("BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT", "BLOCKED_REFRESH_EVIDENCE_BEFORE_LIVE_REVIEW"))) {
    throw "Profit live blocker audit did not produce a blocker status: $status"
}
