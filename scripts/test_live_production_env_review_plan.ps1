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

    $pattern = '(?ms)^## ' + [regex]::Escape($Heading) + '.*?```powershell\s+(.*?)```'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Could not find powershell command block under heading '$Heading'."
    }
    $match.Groups[1].Value
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
    'high_risk_background_automation_true=\[\]',
    'smoke_runtime_evidence_rca_ssh\.ps1` no longer reports `CONFIG_DISABLED`',
    'shadowIntentCount` becomes greater than 0',
    'orderSentEvidence=0',
    'Runtime logs remain free of order placement, OCO modification, live exchange\s+writes, grid/fund/Earn operations, Telegram sends, unexpected scheduler\s+execution, external backfill/import, and DB mutation'
)

$latestSnapshotMarkers = @(
    'Latest recorded read-only bundle snapshot',
    'snapshotType=RECORDED_HISTORICAL_EVIDENCE',
    'observedAt=2026-06-19T09:11\+08:00',
    'originMainCommit=12219d6867ec2761f8a8fcae2a5ad78299523904',
    'Latest refreshed read-only bundle evidence supersedes the earlier 10:59',
    'both remain historical evidence',
    'become stale again',
    'observedAt=2026-06-19T11:12\+08:00',
    'originMainCommit=514a3d1e0cf800e1c84a83368ad69ae6193cc32d',
    'eventRisk=riskLevel=R0',
    'runtimeLog=FAIL',
    'runtimeLogBlocker=RUNTIME_HEALTH_OR_LOG_NOT_CLEAN',
    'TelegramServiceImpl',
    'ExecutionEventScheduler',
    'backgroundHighRiskFlags=',
    'runtimeEvidence=CONFIG_DISABLED shadowIntentCount=0 orderSentEvidence=0',
    'tinyLive=hardStopDetected=true canEnableProduction=false completedTinyLiveSamples=2 falsePositiveCount=2',
    'signalPolicy=governanceMode=TOO_STRICT missedOpportunityOverallStatus=WARN',
    'RUNTIME_HEALTH_OR_LOG_NOT_CLEAN'
)

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
        'DEPLOYED_RUNTIME_NOT_CURRENT',
        'LIVE_READINESS_EVIDENCE_UNAVAILABLE',
        'bundle_verdict=NO_EVIDENCE',
        'origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN',
        'originMainCommit=',
        'records the value observed',
        'later docs or guardrail commits can legitimately advance',
        'bundle_verdict=NOT_READY',
        'stale\s+live-review evidence only',
        'stop the review and fix SSH access',
        'failing read-only smoke',
        'separately\s+authorized\s+deploy',
        'server worktree/runtime to `origin/main`',
        'rerun the full live-readiness bundle',
        'runtime-log blocker and `BACKGROUND_AUTOMATION_REVIEW` must be reviewed\s+together',
        'Telegram/ExecutionEvent notification\s+paths',
        'remaining Telegram/ExecutionEvent notification error has separate written\s+authorization and rollback evidence',
        'full-bundle\s+`bundle_blockers=\[\]`',
        '`live_review_packet_allowed=true`',
        '`deploy_required_before_live_review=false`',
        '`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`',
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

Write-Host "[live-production-env-review-plan-test] OK"
