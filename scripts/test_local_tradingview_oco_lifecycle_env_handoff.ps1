Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_local_tradingview_oco_lifecycle_env_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"
$tradingViewPath = Join-Path $repoRoot "docs/tradingview-webhook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET",
        "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION",
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY",
        "REQUEST_EXACT_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_AUTHORIZATION",
        "RUN_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_POST_ENV_VERIFICATION",
        "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW",
        "TRADINGVIEW_LOCAL_ENABLED=true",
        "TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO",
        "TRADING_OCO_POLLER_ENABLED=true",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "exactOcoLifecycleAuthorizationText",
        "exactDeployCommand",
        "deploy_ssh.ps1 -Branch main",
        "requiredPostEnvReadOnlyVerification",
        "smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked",
        "smoke_strategy485_position_risk_ssh.ps1",
        "audit_live_readiness_ssh.ps1",
        "smoke_live_readiness_bundle_ssh.ps1",
        "prepare_oco_sync_reconciliation_packet_ssh.ps1",
        "rollbackPlan",
        "set TRADING_OCO_POLLER_ENABLED=false",
        "currentCandidateRequiredForEnvHandoff = `$false",
        "AllowDirtyLocalWorktreeForReplay",
        "localTradingViewOcoLifecycleEnvRequestAllowed = `$false",
        "read-only LOCAL_TRADINGVIEW OCO lifecycle env handoff packet only",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "liveOrderMutationAllowed = `$false",
        "ocoMutationAllowed = `$false",
        "positionMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "fundOrEarnMutationAllowed = `$false",
        "dbMutationAllowed = `$false",
        "exchangeMutationAllowed = `$false",
        "orderAllowed = `$false",
        "telegramSendAllowed = `$false",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe"
    )) {
    Assert-Contains -Name "local TradingView OCO lifecycle env handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "placeMarketBuy",
        "placeOco",
        "modifyOco(",
        "sendTelegram",
        "mcp_write_status=OK",
        "git reset --hard"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "local TradingView OCO lifecycle env handoff must not contain mutation marker: $forbidden"
    }
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $remediationPath
    Get-Content -Raw -LiteralPath $tradingViewPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_local_tradingview_oco_lifecycle_env_handoff.ps1",
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET",
        "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION",
        "TRADING_OCO_POLLER_ENABLED=true",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "exact OCO lifecycle authorization",
        "smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked"
    )) {
    Assert-Contains -Name "local TradingView OCO lifecycle env handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "local TradingView OCO lifecycle env handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_local_tradingview_oco_lifecycle_env_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("local-tv-oco-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $readyLog = Join-Path $tempDir "candidate-ready-for-oco-env.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke
scope=READ_ONLY; calls previewScoreBuyTradingViewOrders and runScoreBuyTradingViewParityBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.

Current Candidate:
  currentCandidateStatus=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS
  dataEnd=2026-07-02T16:00
  dataClose=2026-07-03T16:00
  lastOrderAt=2026-06-30T16:00
  orderBars=6
  orderIntents=9
  coverage=OK
  trailingGapHours=17
  coverageWarning=NONE

Local TradingView Execution Guards:
  primary=LOCAL_TRADINGVIEW
  localEnabled=true
  executionMode=LIVE_MICRO
  effectiveExecutionEnabled=true
  effectiveExecutionDryRun=false
  effectiveLiveOrderEnabled=true
  localTradingViewEvaluatorActive=true
  localTradingViewExecutionDryRunArmed=false
  localTradingViewLiveMicroArmed=true
  localTradingViewExecutionPathArmed=true
  tradingOcoPollerEnabled=false
  positionExitManagerEnabled=false
  localTradingViewOcoLifecycleTracked=false
  localTradingViewOcoLifecycleStatus=NOT_TRACKED_OCO_POLLER_DISABLED
  orderSentAllowed=false
  liveOrderMutationAllowed=false

Blocker Classification:
  local_tradingview_blockers=["LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE","LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED"]
  localTradingViewReadiness=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE
"@ | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -SourceLog $readyLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "local_tradingview_oco_lifecycle_env_handoff_packet=",
            '"packetType":"LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET"',
            "local_tradingview_oco_lifecycle_env_handoff_status=READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION",
            "local_tradingview_oco_lifecycle_env_handoff_decision=REQUEST_EXACT_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_AUTHORIZATION",
            "source_candidate_smoke_status=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE",
            "current_candidate_status=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS",
            "current_candidate_required_for_env_handoff=false",
            "local_tradingview_execution_mode=LIVE_MICRO",
            "local_tradingview_live_micro_armed=true",
            "local_tradingview_execution_path_armed=true",
            "trading_oco_poller_enabled=false",
            "position_exit_manager_enabled=false",
            "local_tradingview_oco_lifecycle_tracked=false",
            "local_tradingview_oco_lifecycle_status=NOT_TRACKED_OCO_POLLER_DISABLED",
            "local_tradingview_oco_required_env_diff=.*TRADING_OCO_POLLER_ENABLED=true",
            "local_tradingview_oco_required_env_diff=.*POSITION_EXIT_MANAGER_ENABLED=false",
            "local_tradingview_oco_env_flags_must_remain_disabled=.*POSITION_EXIT_MANAGER_ENABLED=false",
            "local_tradingview_oco_env_flags_not_changed_by_this_packet=.*TRADING_OKX_ENABLED",
            "local_tradingview_oco_post_env_read_only_verification=.*smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked",
            "local_tradingview_oco_post_env_read_only_verification=.*smoke_strategy485_position_risk_ssh.ps1",
            "local_tradingview_oco_rollback_plan=.*set TRADING_OCO_POLLER_ENABLED=false",
            "local_tradingview_oco_exact_authorization_text=I authorize production env diff for LOCAL_TRADINGVIEW LIVE_MICRO OCO lifecycle tracking only",
            "local_tradingview_oco_lifecycle_env_request_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_order_mutation_allowed=false",
            "oco_mutation_allowed=false",
            "position_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "fund_or_earn_mutation_allowed=false",
            "db_mutation_allowed=false",
            "exchange_mutation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only LOCAL_TRADINGVIEW OCO lifecycle env handoff packet only"
        )) {
        Assert-Contains -Name "ready local TradingView OCO lifecycle env handoff replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready local TradingView OCO lifecycle env handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $activeLog = Join-Path $tempDir "candidate-already-tracked.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke
scope=READ_ONLY; calls previewScoreBuyTradingViewOrders and runScoreBuyTradingViewParityBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.

Current Candidate:
  currentCandidateStatus=HAS_CURRENT_BUY_CANDIDATE
  dataEnd=2026-07-03T16:00
  dataClose=2026-07-04T16:00
  lastOrderAt=2026-07-03T16:00
  orderBars=7
  orderIntents=10
  coverage=OK
  trailingGapHours=1
  coverageWarning=NONE

Local TradingView Execution Guards:
  primary=LOCAL_TRADINGVIEW
  localEnabled=true
  executionMode=LIVE_MICRO
  effectiveExecutionEnabled=true
  effectiveExecutionDryRun=false
  effectiveLiveOrderEnabled=true
  localTradingViewEvaluatorActive=true
  localTradingViewExecutionDryRunArmed=false
  localTradingViewLiveMicroArmed=true
  localTradingViewExecutionPathArmed=true
  tradingOcoPollerEnabled=true
  positionExitManagerEnabled=false
  localTradingViewOcoLifecycleTracked=true
  localTradingViewOcoLifecycleStatus=TRACKED_BY_OCO_POLLER
  orderSentAllowed=false
  liveOrderMutationAllowed=false

Blocker Classification:
  local_tradingview_blockers=[]
  localTradingViewReadiness=READY_FOR_LOCAL_TRADINGVIEW_LIVE_MICRO_CURRENT_BUY_REVIEW_NOT_ORDER
"@ | Set-Content -LiteralPath $activeLog -Encoding UTF8

    $activeOutput = & $scriptPath -SourceLog $activeLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $activeText = $activeOutput -join "`n"
    Assert-Contains -Name "active local TradingView OCO lifecycle tracked" -Text $activeText -Pattern "local_tradingview_oco_lifecycle_env_handoff_status=LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY"
    Assert-Contains -Name "active local TradingView OCO lifecycle decision" -Text $activeText -Pattern "local_tradingview_oco_lifecycle_env_handoff_decision=RUN_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_POST_ENV_VERIFICATION"
    Assert-Contains -Name "active local TradingView current candidate" -Text $activeText -Pattern "current_candidate_status=HAS_CURRENT_BUY_CANDIDATE"
    Assert-Contains -Name "active local TradingView oco lifecycle tracked" -Text $activeText -Pattern "local_tradingview_oco_lifecycle_tracked=true"

    $notReadyLog = Join-Path $tempDir "candidate-not-ready.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke

Current Candidate:
  currentCandidateStatus=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS
  dataEnd=2026-07-01T16:00
  lastOrderAt=2026-06-30T16:00
  orderBars=5
  orderIntents=8
  coverage=OK
  trailingGapHours=41
  coverageWarning=NONE

Local TradingView Execution Guards:
  primary=TRADINGVIEW
  localEnabled=false
  executionMode=LEGACY
  effectiveExecutionEnabled=false
  effectiveLiveOrderEnabled=false
  localTradingViewEvaluatorActive=false
  localTradingViewLiveMicroArmed=false
  localTradingViewExecutionPathArmed=false
  tradingOcoPollerEnabled=false
  positionExitManagerEnabled=false
  localTradingViewOcoLifecycleTracked=false
  localTradingViewOcoLifecycleStatus=NOT_TRACKED_NOT_LIVE_MICRO

Blocker Classification:
  local_tradingview_blockers=["LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE","LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED"]
  localTradingViewReadiness=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE
"@ | Set-Content -LiteralPath $notReadyLog -Encoding UTF8

    $notReadyOutput = & $scriptPath -SourceLog $notReadyLog -AllowDirtyLocalWorktreeForReplay *>&1
    $notReadyText = $notReadyOutput -join "`n"
    Assert-Contains -Name "not-ready local TradingView OCO lifecycle handoff status" -Text $notReadyText -Pattern "local_tradingview_oco_lifecycle_env_handoff_status=NOT_READY_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF"
    Assert-Contains -Name "not-ready local TradingView primary requirement" -Text $notReadyText -Pattern "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW"
    Assert-Contains -Name "not-ready local TradingView live-micro requirement" -Text $notReadyText -Pattern "TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[local-tradingview-oco-lifecycle-env-handoff-test] OK"
