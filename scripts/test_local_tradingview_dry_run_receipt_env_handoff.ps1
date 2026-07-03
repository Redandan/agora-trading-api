Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_local_tradingview_dry_run_receipt_env_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_PACKET",
        "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION",
        "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY",
        "REQUEST_EXACT_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_AUTHORIZATION",
        "RUN_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_OBSERVATION",
        "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW",
        "TRADINGVIEW_LOCAL_ENABLED=true",
        "TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN",
        "TRADINGVIEW_LOCAL_EXECUTION_ENABLED=false",
        "TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN=true",
        "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false",
        "exactOperatorAuthorizationText",
        "exactDeployCommand",
        "deploy_ssh.ps1 -Branch main",
        "requiredPostEnvReadOnlyVerification",
        "smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed",
        "smoke_local_tradingview_candidate_ssh.ps1 -RequireCurrentCandidate -RequireDryRunArmed",
        "audit_live_readiness_ssh.ps1",
        "smoke_live_readiness_bundle_ssh.ps1",
        "rollbackPlan",
        "set TRADINGVIEW_LOCAL_EXECUTION_MODE=LEGACY",
        "currentCandidateRequiredForEnvHandoff = `$false",
        "AllowDirtyLocalWorktreeForReplay",
        "read-only LOCAL_TRADINGVIEW dry-run receipt env handoff packet only",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "liveOrderMutationAllowed = `$false",
        "ocoMutationAllowed = `$false",
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
    Assert-Contains -Name "local TradingView dry-run receipt env handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "placeMarketBuy",
        "placeOco",
        "modifyOco(",
        "forceClosePosition",
        "sendTelegram",
        "mcp_write_status=OK",
        "git reset --hard"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "local TradingView dry-run receipt env handoff must not contain mutation marker: $forbidden"
    }
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_local_tradingview_dry_run_receipt_env_handoff.ps1",
        "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_PACKET",
        "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION",
        "TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN",
        "exact operator authorization",
        "smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed"
    )) {
    Assert-Contains -Name "local TradingView dry-run receipt env handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "local TradingView dry-run receipt env handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_local_tradingview_dry_run_receipt_env_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("local-tv-dry-run-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $readyLog = Join-Path $tempDir "candidate-ready-for-env.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke
scope=READ_ONLY; calls previewScoreBuyTradingViewOrders and runScoreBuyTradingViewParityBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.

Current Candidate:
  currentCandidateStatus=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS
  dataEnd=2026-07-01T16:00
  lastOrderAt=2026-06-30T16:00
  firstOrderAt=2026-05-28T16:00
  orderBars=5
  orderIntents=8
  coverage=OK
  trailingGapHours=41
  coverageWarning=NONE

Local TradingView Execution Guards:
  primary=LOCAL_TRADINGVIEW
  localEnabled=true
  executionMode=LEGACY
  effectiveExecutionEnabled=false
  effectiveExecutionDryRun=true
  effectiveLiveOrderEnabled=false
  localTradingViewEvaluatorActive=true
  localTradingViewExecutionDryRunArmed=false
  orderSentAllowed=false
  liveOrderMutationAllowed=false

Parity Backtest Summary:
  finalMark=2026-07-01T16:00
  netPnlUsdt=-107.73
  totalReturn=-0.90%

Blocker Classification:
  local_tradingview_blockers=["LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE","LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED"]
  localTradingViewReadiness=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE
"@ | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -SourceLog $readyLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "local_tradingview_dry_run_receipt_env_handoff_packet=",
            '"packetType":"LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_PACKET"',
            "local_tradingview_dry_run_receipt_env_handoff_status=READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION",
            "local_tradingview_dry_run_receipt_env_handoff_decision=REQUEST_EXACT_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_AUTHORIZATION",
            "source_candidate_smoke_status=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE",
            "current_candidate_status=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS",
            "current_candidate_required_for_env_handoff=false",
            "local_tradingview_execution_mode=LEGACY",
            "local_tradingview_dry_run_armed=false",
            "local_tradingview_required_env_state=.*TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN",
            "local_tradingview_required_env_state=.*TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false",
            "local_tradingview_env_flags_must_remain_disabled=.*POSITION_EXIT_MANAGER_ENABLED=false",
            "local_tradingview_env_flags_not_changed_by_this_packet=.*TRADING_OKX_ENABLED",
            "local_tradingview_post_env_read_only_verification=.*smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed",
            "local_tradingview_post_env_read_only_verification=.*smoke_local_tradingview_candidate_ssh.ps1 -RequireCurrentCandidate -RequireDryRunArmed",
            "local_tradingview_rollback_plan=.*set TRADINGVIEW_LOCAL_EXECUTION_MODE=LEGACY",
            "local_tradingview_exact_authorization_text=I authorize production env state TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_order_mutation_allowed=false",
            "oco_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "fund_or_earn_mutation_allowed=false",
            "db_mutation_allowed=false",
            "exchange_mutation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only LOCAL_TRADINGVIEW dry-run receipt env handoff packet only"
        )) {
        Assert-Contains -Name "ready local TradingView dry-run receipt env handoff replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready local TradingView dry-run receipt env handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $activeLog = Join-Path $tempDir "candidate-already-armed.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke
scope=READ_ONLY; calls previewScoreBuyTradingViewOrders and runScoreBuyTradingViewParityBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.

Current Candidate:
  currentCandidateStatus=HAS_CURRENT_BUY_CANDIDATE
  dataEnd=2026-07-02T16:00
  lastOrderAt=2026-07-02T16:00
  firstOrderAt=2026-05-28T16:00
  orderBars=6
  orderIntents=9
  coverage=OK
  trailingGapHours=17
  coverageWarning=NONE

Local TradingView Execution Guards:
  primary=LOCAL_TRADINGVIEW
  localEnabled=true
  executionMode=DRY_RUN
  effectiveExecutionEnabled=true
  effectiveExecutionDryRun=true
  effectiveLiveOrderEnabled=false
  localTradingViewEvaluatorActive=true
  localTradingViewExecutionDryRunArmed=true
  orderSentAllowed=false
  liveOrderMutationAllowed=false

Parity Backtest Summary:
  finalMark=2026-07-02T16:00
  netPnlUsdt=-89.12
  totalReturn=-0.74%

Blocker Classification:
  local_tradingview_blockers=[]
  localTradingViewReadiness=READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_OBSERVATION_NOT_LIVE
"@ | Set-Content -LiteralPath $activeLog -Encoding UTF8

    $activeOutput = & $scriptPath -SourceLog $activeLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $activeText = $activeOutput -join "`n"
    Assert-Contains -Name "active local TradingView handoff not needed" -Text $activeText -Pattern "local_tradingview_dry_run_receipt_env_handoff_status=LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY"
    Assert-Contains -Name "active local TradingView observation decision" -Text $activeText -Pattern "local_tradingview_dry_run_receipt_env_handoff_decision=RUN_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_OBSERVATION"
    Assert-Contains -Name "active local TradingView current candidate" -Text $activeText -Pattern "current_candidate_status=HAS_CURRENT_BUY_CANDIDATE"
    Assert-Contains -Name "active local TradingView dry-run armed" -Text $activeText -Pattern "local_tradingview_dry_run_armed=true"

    $notReadyLog = Join-Path $tempDir "candidate-not-ready.log"
    @"
[local-tradingview-candidate] read-only server-local MCP smoke

Current Candidate:
  currentCandidateStatus=NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS
  dataEnd=2026-07-01T16:00
  lastOrderAt=2026-06-30T16:00
  firstOrderAt=2026-05-28T16:00
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
  effectiveExecutionDryRun=true
  effectiveLiveOrderEnabled=false
  localTradingViewEvaluatorActive=false
  localTradingViewExecutionDryRunArmed=false

Blocker Classification:
  local_tradingview_blockers=["LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE","LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED"]
  localTradingViewReadiness=WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE
"@ | Set-Content -LiteralPath $notReadyLog -Encoding UTF8

    $notReadyOutput = & $scriptPath -SourceLog $notReadyLog -AllowDirtyLocalWorktreeForReplay *>&1
    $notReadyText = $notReadyOutput -join "`n"
    Assert-Contains -Name "not-ready local TradingView handoff status" -Text $notReadyText -Pattern "local_tradingview_dry_run_receipt_env_handoff_status=NOT_READY_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF"
    Assert-Contains -Name "not-ready local TradingView primary requirement" -Text $notReadyText -Pattern "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW"
    Assert-Contains -Name "not-ready local TradingView enabled requirement" -Text $notReadyText -Pattern "TRADINGVIEW_LOCAL_ENABLED=true"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[local-tradingview-dry-run-receipt-env-handoff-test] OK"
