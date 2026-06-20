Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Get-CommandBlock {
    param(
        [string]$Text,
        [string]$Heading
    )

    $sectionPattern = '(?ms)^## ' + [regex]::Escape($Heading) + '\s+(.*?)(?=^## |\z)'
    $sectionMatch = [regex]::Match($Text, $sectionPattern)
    if (-not $sectionMatch.Success) {
        throw "Could not find section '$Heading'."
    }
    $matches = [regex]::Matches($sectionMatch.Groups[1].Value, '```powershell\s+(.*?)```', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($matches.Count -eq 0) {
        throw "Could not find powershell command block under heading '$Heading'."
    }
    @(
        $matches |
            ForEach-Object { $_.Groups[1].Value }
    ) -join "`n"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$proposalPath = Join-Path $repoRoot "docs/live-production-env-review-proposal.md"
$proposalText = Get-Content -Raw -LiteralPath $proposalPath

$requiredEvidence = Get-CommandBlock -Text $proposalText -Heading "Required Evidence Before Review"
$postAuthorization = Get-CommandBlock -Text $proposalText -Heading "Post-Authorization Verification"

$mustStayDisabled = @(
    "TRADING_OKX_ENABLED=false",
    "TRADING_OCO_POLLER_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "TRAILING_STOP_ENABLED=false",
    "POSITION_EXIT_MANAGER_ENABLED=false",
    "TRADING_GRID_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "TRADING_FUNDING_ARB_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED=false",
    "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED=false",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION=false",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE=false"
)

$backgroundAutomationReviewFlags = @(
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false",
    "TRADING_DAILY_TG_REPORT_ENABLED=false",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED=false"
)

$rollbackCriteria = @(
    '`orderSentEvidence` is greater than 0',
    'Any order/OCO/grid/fund/Earn/Telegram/live exchange write appears in logs',
    'External backfill/import jobs run unexpectedly',
    'Public MCP becomes externally offered as a service surface',
    'DB migration, Flyway baseline regeneration, extra-table cleanup, or table\s+drops are attempted',
    '`audit_live_readiness_ssh\.ps1` reports new blockers outside the planned\s+evidence collection scope'
)

$evidenceOnlyExpectedResults = @(
    '`order_capable_flags` remain false',
    'order_capable_flags_true=\[\]',
    'smoke_live_background_automation_ssh\.ps1 -RequireClear` exits 0',
    'background_automation_true=\[\]',
    'high_risk_background_automation_true=\[\]',
    'missing_background_automation_flags=\[\]',
    'background_automation_blockers=\[\]',
    'backgroundAutomationClear=true',
    'smoke_runtime_evidence_rca_ssh\.ps1 -RequireReady` exits 0',
    'diagnosis=CANONICAL_SHADOW_READY',
    'shadowIntentCount > 0',
    'orderSentEvidence=0',
    'smoke_tiny_live_loss_rca_ssh\.ps1 -RequireClear` exits 0',
    'hardStopDetected=false',
    'canEnableProduction=true',
    'missing_tiny_live_fields=\[\]',
    'smoke_signal_correctness_ssh\.ps1 -RequireClear` exits 0',
    'signalPolicyClear=true',
    'missing_signal_policy_fields=\[\]',
    '7d governance drift is not `TOO_STRICT`, `TOO_LOOSE`, or\s+`INSUFFICIENT_DATA`',
    'Missed-opportunity `overallStatus=PASS`',
    'smoke_mcp_parity_ssh\.ps1` exits 0',
    'MCP parity output includes `required_tools=\[\.\.\.\]`',
    'MCP parity output includes `missing_required_tools=\[\]`',
    'MCP parity output includes `\[mcp-parity-ssh\] OK`',
    'hard-gate smoke exiting non-zero means the review remains blocked',
    'Runtime logs remain free of order placement, OCO modification, live exchange\s+writes, grid/fund/Earn operations, Telegram sends, unexpected scheduler\s+execution, external backfill/import, and DB mutation'
)

$latestSnapshotMarkers = @(
    'Latest attached read-only bundle snapshot',
    'snapshotType=ATTACHED_READ_ONLY_EVIDENCE',
    'not a currentness\s+claim after later docs, scripts, or runtime commits',
    'rerun',
    'smoke_live_deployment_metadata_ssh\.ps1',
    'smoke_live_readiness_bundle_ssh\.ps1',
    'fresh output',
    'observedAt=2026-06-20T20:28\+08:00',
    'serverCommit=ef6253a4ecff7c27a2e709f226e166389700a82d',
    'deployedCommit=ef6253a4ecff7c27a2e709f226e166389700a82d',
    'origin_metadata_status=CURRENT_ORIGIN_MAIN',
    'originMainCommit=ef6253a4ecff7c27a2e709f226e166389700a82d',
    'attached snapshot superseded earlier stale 2026-06-19 and 2026-06-20',
    'attached runtime currentness was clean',
    'Do not chase docs-only deploy commits',
    'authoritative currentness evidence is the freshly rerun metadata and full\s+bundle output',
    'refreshType=DEPLOYMENT_METADATA_ONLY',
    'metadata_blockers=\[\]',
    'bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY',
    'metadata-only',
    'live_review_packet_allowed=false',
    'deploy_required_before_live_review=false',
    'eventRisk=riskLevel=R0',
    'runtimeLog=PASS',
    'runtimeLogErrors=0',
    'runtimeLogWarnBaselineTotal=13',
    'missing_readiness_detail_fields=\[\]',
    '2026-06-20T10:16\+08:00',
    'app-20260618T070102Z-port8084\.log',
    'TelegramServiceImpl',
    'ExecutionEventScheduler',
    'backgroundHighRiskFlags=',
    'backgroundAutomationClear=false',
    'backgroundAutomationBlockers=\["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE","BACKGROUND_AUTOMATION_TRUE"\]',
    'mcpParityRequiredTools=required_tools=\[\.\.\.\]',
    'mcpParityMissingTools=missing_required_tools=\[\]',
    'mcpParityOk=\[mcp-parity-ssh\] OK toolCount=305 required=35',
    'bundle_blocker_summary=present',
    'runtimeEvidence=CONFIG_DISABLED shadowIntentCount=0 orderSentEvidence=0',
    'tinyLive=hardStopDetected=true canEnableProduction=false completedTinyLiveSamples=2 falsePositiveCount=2',
    'signalPolicy=governanceMode=TOO_STRICT missedOpportunityOverallStatus=WARN',
    'LIVE_READINESS_NOT_READY'
)

Assert-Contains -Name "required evidence preflight hard gate" -Text $requiredEvidence -Pattern ([regex]::Escape(".\scripts\prepare_live_env_review_packet.ps1 -RequireReady"))
Assert-Contains -Name "required evidence live packet preflight hard gate" -Text $requiredEvidence -Pattern ([regex]::Escape(".\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady"))

foreach ($scriptName in @(
        "audit_live_readiness_ssh.ps1",
        "smoke_live_background_automation_ssh.ps1",
        "smoke_runtime_evidence_rca_ssh.ps1",
        "smoke_tiny_live_loss_rca_ssh.ps1",
        "smoke_signal_correctness_ssh.ps1",
        "smoke_mcp_parity_ssh.ps1",
        "smoke_live_readiness_bundle_ssh.ps1"
    )) {
    Assert-Contains -Name "required evidence commands" -Text $requiredEvidence -Pattern ([regex]::Escape(".\scripts\$scriptName"))
    Assert-Contains -Name "post-authorization commands" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\$scriptName"))
}

Assert-Contains -Name "post-authorization background hard gate" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear"))
Assert-Contains -Name "post-authorization runtime hard gate" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"))
Assert-Contains -Name "post-authorization tiny-live hard gate" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear"))
Assert-Contains -Name "post-authorization signal-policy hard gate" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear"))
Assert-Contains -Name "post-authorization live packet preflight hard gate" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady"))

foreach ($flag in $mustStayDisabled) {
    Assert-Contains -Name "must-stay-disabled live flag" -Text $proposalText -Pattern ([regex]::Escape($flag))
}

foreach ($flag in $backgroundAutomationReviewFlags) {
    Assert-Contains -Name "background automation review flag" -Text $proposalText -Pattern ([regex]::Escape($flag))
}

foreach ($pattern in $rollbackCriteria) {
    Assert-Contains -Name "rollback criteria" -Text $proposalText -Pattern $pattern
}

foreach ($pattern in $evidenceOnlyExpectedResults) {
    Assert-Contains -Name "evidence-only expected result" -Text $proposalText -Pattern $pattern
}

foreach ($pattern in $latestSnapshotMarkers) {
    Assert-Contains -Name "latest live-readiness snapshot marker" -Text $proposalText -Pattern $pattern
}

if ($proposalText -match 'bundle_blockers=\[[^\]]*EVENT_RISK_NOT_BASELINE') {
    throw "latest live-readiness snapshot must not list EVENT_RISK_NOT_BASELINE when eventRisk=riskLevel=R0 is present"
}

foreach ($pattern in @(
        'metadata-only output is still not live-readiness evidence',
        'smoke_live_deployment_metadata_ssh.ps1',
        'whether there is a deploy/currentness blocker',
        'full bundle\s+remains `NOT_READY`',
        'bundle_verdict=NO_EVIDENCE',
        'origin_metadata_status=CURRENT_ORIGIN_MAIN',
        'originMainCommit=',
        'bundle_verdict=NOT_READY',
        'historical RCA',
        'stop the review and fix SSH access',
        'failing read-only smoke',
        'If `origin/main`, server worktree, or deployed\s+runtime changes again',
        'rerun deployment metadata and the full bundle',
        'stale 2026-06-20T10:16+08:00 runtime-log failure',
        'was no longer the current blocker',
        'Telegram/ExecutionEvent\s+notification\s+paths',
        'strict read-only runtime-log smoke',
        'ERROR category ...',
        'ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH',
        'EVENT_SCAN_NOTIFICATION_ENABLED',
        'EXECUTION_EVENT_ENABLED',
        'Telegram send\s+health',
        'remaining Telegram/ExecutionEvent notification error has separate written\s+authorization and rollback evidence',
        'full-bundle\s+`bundle_blockers=\[\]`',
        '`live_review_packet_allowed=true`',
        '`deploy_required_before_live_review=false`',
        '`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`',
        '`packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`',
        '`packet_missing_requirements=[]`',
        'not authorization'
    )) {
    if ($pattern -match '\\s') {
        Assert-Contains -Name "production env review proposal currentness boundary" -Text $proposalText -Pattern $pattern
    } else {
        Assert-Contains -Name "production env review proposal currentness boundary" -Text $proposalText -Pattern ([regex]::Escape($pattern))
    }
}

Assert-Contains -Name "post-authorization bundle blocker expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`DEPLOYED_RUNTIME_NOT_CURRENT`'
Assert-Contains -Name "post-authorization runtime health blocker expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`RUNTIME_HEALTH_OR_LOG_NOT_CLEAN`'
Assert-Contains -Name "post-authorization no-evidence blocker expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`LIVE_READINESS_EVIDENCE_UNAVAILABLE`'
Assert-Contains -Name "post-authorization no-evidence verdict expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`bundle_verdict=NO_EVIDENCE`'
Assert-Contains -Name "post-authorization live packet preflight result" -Text $proposalText -Pattern 'prepare_live_review_packet_ssh\.ps1 -RequireReady` exits 0'
Assert-Contains -Name "post-authorization live packet preflight status" -Text $proposalText -Pattern 'packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED'
Assert-Contains -Name "post-authorization live packet preflight requirements" -Text $proposalText -Pattern 'packet_missing_requirements=\[\]'

Write-Host "[live-production-env-review-plan-test] OK"
