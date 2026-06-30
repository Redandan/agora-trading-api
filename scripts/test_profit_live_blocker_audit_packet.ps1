Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) {
        throw "$Name unexpectedly contained pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_live_blocker_audit_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-live-blocker-audit-packet] read-only audit",
        "scope=READ_ONLY",
        "PROFIT_LIVE_BLOCKER_AUDIT_PACKET",
        "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT",
        "BLOCKED_REFRESH_EVIDENCE_BEFORE_LIVE_REVIEW",
        "profit_live_blocker_audit_packet",
        "profit_live_blocker_audit_status",
        "GovernanceRelaxationReviewLogPath",
        "Strategy485RiskEscalationLogPath",
        "strategy485-risk-escalation",
        "strategy485_risk_escalation_brief_status=",
        "strategy485_risk_escalation_brief_packet=",
        "NoActionStatuses",
        "NO_ACTION_REQUIRED_NOT_LIVE",
        "NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE",
        "profit_live_blocker_no_action_count",
        "Read-EntryDedupLane",
        "entry_dedup_operator_decision_brief_status=",
        "entry_dedup_operator_decision_brief_packet=",
        "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE",
        "legacy_entry_dedup_semantics_operator_decision_packet",
        "READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION",
        "sourceFallback",
        "profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only profit live blocker audit only",
        "RequireAuditReady"
    )) {
    Assert-Contains -Name "profit live blocker audit marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_live_blocker_audit_packet.ps1",
        "PROFIT_LIVE_BLOCKER_AUDIT_PACKET",
        "profit_live_blocker_audit_packet",
        "NOT_READY_FOR_LIVE_ENABLEMENT"
    )) {
    Assert-Contains -Name "docs mention profit live blocker audit" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-live-blocker-audit-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    function Write-PacketLog {
        param(
            [string]$Name,
            [string]$StatusPrefix,
            [string]$Status,
            [string]$PacketPrefix,
            [object]$Packet
        )
        $path = Join-Path $tempDir $Name
        Set-Content -LiteralPath $path -Encoding UTF8 -Value @(
            "$StatusPrefix$Status",
            ($PacketPrefix + (ConvertTo-Json -Compress -Depth 8 $Packet))
        )
        return $path
    }

    $priority = Write-PacketLog -Name "priority.log" -StatusPrefix "profit_operator_priority_decision_brief_status=" -Status "READY_FOR_OPERATOR_DECISION_NOT_LIVE" -PacketPrefix "profit_operator_priority_decision_brief_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review ranked operator decisions." })
    $trailing = Write-PacketLog -Name "trailing.log" -StatusPrefix "trailing_stop_dry_run_operator_decision_status=" -Status "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE" -PacketPrefix "trailing_stop_dry_run_operator_decision_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review dry-run trailing." })
    $strategy485 = Write-PacketLog -Name "strategy485.log" -StatusPrefix "strategy485_risk_reduction_operator_decision_status=" -Status "READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION" -PacketPrefix "strategy485_risk_reduction_operator_decision_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review shadow risk reduction." })
    $strategy485Escalation = Write-PacketLog -Name "strategy485-escalation.log" -StatusPrefix "strategy485_risk_escalation_brief_status=" -Status "READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION" -PacketPrefix "strategy485_risk_escalation_brief_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review severe paper-loss strategy485 risk." })
    $entryDedup = Write-PacketLog -Name "entry.log" -StatusPrefix "entry_dedup_operator_decision_brief_status=" -Status "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE" -PacketPrefix "entry_dedup_operator_decision_brief_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review fresh EntryDedup SSH shadow evidence." })
    $dfBlocker = Write-PacketLog -Name "df-blocker.log" -StatusPrefix "data_freshness_replay_blocker_decision_status=" -Status "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE" -PacketPrefix "data_freshness_replay_blocker_decision_packet=" -Packet ([pscustomobject]@{ missingRequirements = @("complete_replayable_candidate_rows=0"); nextAction = "Wait for replayable rows." })
    $dfCollector = Write-PacketLog -Name "df-collector.log" -StatusPrefix "data_freshness_collector_activation_status=" -Status "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE" -PacketPrefix "data_freshness_collector_activation_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review evidence-only collector activation." })
    $tpSlOco = Write-PacketLog -Name "tp-sl-oco.log" -StatusPrefix "tp_sl_oco_feasibility_status=" -Status "READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION" -PacketPrefix "tp_sl_oco_feasibility_operator_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review TP/SL/OCO feasibility." })
    $strategy574 = Write-PacketLog -Name "strategy574.log" -StatusPrefix "strategy574_tiny_live_governance_preflight_status=" -Status "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE" -PacketPrefix "strategy574_tiny_live_governance_preflight_review_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "Review blocked Strategy574/TinyLive governance." })
    $missingGovernancePreflight = Join-Path $tempDir "governance-preflight-missing.log"
    $governanceReview = Write-PacketLog -Name "governance-review.log" -StatusPrefix "governance_relaxation_review_packet_status=" -Status "NO_EVIDENCE" -PacketPrefix "governance_relaxation_review_packet=" -Packet ([pscustomobject]@{ missingRequirements = @("DataFreshness current snapshot clean", "governance relaxation candidates present"); nextAction = "Fix read-only governance relaxation evidence collection before drafting a review packet." })

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit live blocker audit test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -PriorityDecisionLogPath $priority `
            -TrailingDryRunLogPath $trailing `
            -Strategy485RiskLogPath $strategy485 `
            -Strategy485RiskEscalationLogPath $strategy485Escalation `
            -EntryDedupLogPath $entryDedup `
            -DataFreshnessReplayBlockerLogPath $dfBlocker `
            -DataFreshnessCollectorLogPath $dfCollector `
            -TpSlOcoLogPath $tpSlOco `
            -Strategy574TinyLivePreflightLogPath $strategy574 `
            -GovernanceRelaxationPreflightLogPath $missingGovernancePreflight `
            -GovernanceRelaxationReviewLogPath $governanceReview `
            -RequireAuditReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "profit live blocker audit failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "profit_live_blocker_audit_lane_count=10",
            "profit_live_blocker_ready_review_count=9",
            "profit_live_blocker_no_action_count=0",
            "profit_live_blocker_missing_evidence_count=0",
            "profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT",
            "profit_live_blocker_audit_status=BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT",
            '"packetType":"PROFIT_LIVE_BLOCKER_AUDIT_PACKET"',
            '"liveReadinessConclusion":"NOT_READY_FOR_LIVE_ENABLEMENT"',
            '"lane":"strategy485-risk-escalation"',
            '"sourceStatus":"READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION"',
            '"lane":"data-freshness-replay-blocker"',
            '"lane":"strategy574-tiny-live-governance"',
            '"lane":"governance-relaxation"',
            '"readyReviewCount":9',
            '"sourceFallback":"governance_relaxation_review_packet"',
            '"sourceStatus":"NO_EVIDENCE"',
            '"classification":"BLOCKED_REVIEW_ONLY"',
            '"liveReady":false',
            "tiny_live_order_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "deploy_or_env_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only profit live blocker audit only"
        )) {
        Assert-Contains -Name "profit live blocker audit temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit live blocker audit unexpectedly invoked SSH or a fresh child run:`n$text"
    }

    $governancePreflightNoAction = Write-PacketLog -Name "governance-preflight-noaction.log" -StatusPrefix "governance_relaxation_preflight_status=" -Status "NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE" -PacketPrefix "governance_relaxation_preflight_review_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "No governance relaxation candidate is present; keep policy unchanged and continue no-buy attention review." })
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -PriorityDecisionLogPath $priority `
            -TrailingDryRunLogPath $trailing `
            -Strategy485RiskLogPath $strategy485 `
            -Strategy485RiskEscalationLogPath $strategy485Escalation `
            -EntryDedupLogPath $entryDedup `
            -DataFreshnessReplayBlockerLogPath $dfBlocker `
            -DataFreshnessCollectorLogPath $dfCollector `
            -TpSlOcoLogPath $tpSlOco `
            -Strategy574TinyLivePreflightLogPath $strategy574 `
            -GovernanceRelaxationPreflightLogPath $governancePreflightNoAction `
            -GovernanceRelaxationReviewLogPath $governanceReview `
            -RequireAuditReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "profit live blocker audit governance no-action case failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "profit_live_blocker_audit_lane_count=10",
            "profit_live_blocker_ready_review_count=9",
            "profit_live_blocker_no_action_count=1",
            "profit_live_blocker_audit_status=BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT",
            '"lane":"governance-relaxation"',
            '"sourceStatus":"NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE"',
            '"classification":"NO_ACTION_REQUIRED_NOT_LIVE"',
            '"noActionRequired":true',
            '"readyReviewCount":9',
            '"noActionCount":1',
            '"blockedCount":0',
            '"primaryBlockers":["separate explicit operator authorization is required before any live/order/scheduler/env/Telegram/policy mutation"]'
        )) {
        Assert-Contains -Name "profit live blocker audit governance no-action temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    Assert-NotContains -Name "profit live blocker audit governance no-action blocker" -Text $text -Pattern ([regex]::Escape("governance-relaxation: BLOCKED_REVIEW_ONLY"))
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit live blocker audit governance no-action unexpectedly invoked SSH or a fresh child run:`n$text"
    }

    $strategy485EscalationNoAction = Write-PacketLog -Name "strategy485-escalation-noaction.log" -StatusPrefix "strategy485_risk_escalation_brief_status=" -Status "NO_POSITION_RISK_ACTION" -PacketPrefix "strategy485_risk_escalation_brief_packet=" -Packet ([pscustomobject]@{ missingRequirements = @(); nextAction = "No Strategy485 negative-EV position or close/modify suggestion exists." })
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -PriorityDecisionLogPath $priority `
            -TrailingDryRunLogPath $trailing `
            -Strategy485RiskLogPath $strategy485 `
            -Strategy485RiskEscalationLogPath $strategy485EscalationNoAction `
            -EntryDedupLogPath $entryDedup `
            -DataFreshnessReplayBlockerLogPath $dfBlocker `
            -DataFreshnessCollectorLogPath $dfCollector `
            -TpSlOcoLogPath $tpSlOco `
            -Strategy574TinyLivePreflightLogPath $strategy574 `
            -GovernanceRelaxationPreflightLogPath $missingGovernancePreflight `
            -GovernanceRelaxationReviewLogPath $governanceReview `
            -RequireAuditReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "profit live blocker audit no-action case failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "profit_live_blocker_audit_lane_count=10",
            "profit_live_blocker_ready_review_count=8",
            "profit_live_blocker_no_action_count=1",
            "profit_live_blocker_audit_status=BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT",
            '"lane":"strategy485-risk-escalation"',
            '"sourceStatus":"NO_POSITION_RISK_ACTION"',
            '"classification":"NO_ACTION_REQUIRED_NOT_LIVE"',
            '"noActionRequired":true',
            '"readyReviewCount":8',
            '"noActionCount":1',
            '"blockedCount":1',
            '"lane":"governance-relaxation"',
            '"classification":"BLOCKED_REVIEW_ONLY"'
        )) {
        Assert-Contains -Name "profit live blocker audit no-action temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    Assert-NotContains -Name "profit live blocker audit no-action primary blockers" -Text $text -Pattern ([regex]::Escape("strategy485-risk-escalation: BLOCKED_REVIEW_ONLY"))
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit live blocker audit no-action unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) { Remove-Item -LiteralPath $tempDir -Recurse -Force }
}

Write-Host "[profit-live-blocker-audit-packet-test] OK"
