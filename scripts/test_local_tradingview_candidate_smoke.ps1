Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "smoke_local_tradingview_candidate_ssh.ps1"
$text = Get-Content -Raw -LiteralPath $scriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )
    if ($text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

foreach ($pattern in @(
        "read-only server-local MCP smoke",
        "scope=READ_ONLY",
        "previewScoreBuyTradingViewOrders",
        "runScoreBuyTradingViewParityBacktest",
        "runScoreBuyTradingViewBtcBaseBacktest",
        "SCORE_BUY TradingView BTC_BASE shadow backtest",
        "notAuthorization=read-only BTC_BASE shadow report only",
        "currentCandidateStatus",
        "INCOMPLETE_REPLAY_HISTORY",
        "HAS_CURRENT_BUY_CANDIDATE",
        "NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS",
        "localTradingViewExecutionDryRunArmed",
        "localTradingViewBtcBaseDryRunArmed",
        "localTradingViewBtcBaseLiveMicroArmed",
        "localTradingViewLiveMicroArmed",
        "localTradingViewExecutionPathArmed",
        "localTradingViewOcoLifecycleTracked",
        "localTradingViewOcoLifecycleStatus",
        "Local TradingView Pre-Execution Gates",
        "localTradingViewPreExecutionEvidenceStatus",
        "localTradingViewPreExecutionReadiness",
        "local_tradingview_pre_execution_blockers",
        "localTradingViewScopeAllowed",
        "localTradingViewSourceAllowed",
        "localTradingViewOkxAutoTradeEnabled",
        "localTradingViewOkxPrivateCredentialsConfigured",
        "localTradingViewNotionalAccepted",
        "localTradingViewExchangeMinNotionalUsdt",
        "localTradingViewOcoPlanValid",
        "localTradingViewSignalStale",
        "localTradingViewDailyCapAvailable",
        "localTradingViewOpenPositionCapAvailable",
        "localTradingViewOpenExactPositionExists",
        "localTradingViewDuplicateBarExists",
        "localTradingViewBarCapAllowsAtLeastOne",
        "SPRING_DATASOURCE_URL",
        "bt_live_signal",
        "UTC_DATE",
        "orderSentAllowed=false",
        "liveOrderMutationAllowed=false",
        "replayHistoryRequired",
        "replayHistoryComplete",
        "replayDataStart",
        "LOCAL_TRADINGVIEW_REPLAY_HISTORY_INCOMPLETE",
        "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE",
        "LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED",
        "LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED",
        "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED",
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED",
        "LOCAL_TRADINGVIEW_PRE_EXECUTION_DB_EVIDENCE_UNAVAILABLE",
        "LOCAL_TRADINGVIEW_SCOPE_NOT_ALLOWLISTED",
        "LOCAL_TRADINGVIEW_SOURCE_NOT_ALLOWLISTED",
        "LOCAL_TRADINGVIEW_OKX_DISABLED",
        "LOCAL_TRADINGVIEW_OKX_PRIVATE_CREDENTIALS_MISSING",
        "LOCAL_TRADINGVIEW_NOTIONAL_BELOW_MINIMUM",
        "LOCAL_TRADINGVIEW_DAILY_CAP_REACHED",
        "LOCAL_TRADINGVIEW_OPEN_POSITION_CAP_REACHED",
        "LOCAL_TRADINGVIEW_OPEN_POSITION_EXISTS",
        "LOCAL_TRADINGVIEW_BTC_BASE_EXPOSURE_CAP_REACHED",
        "LOCAL_TRADINGVIEW_DUPLICATE_BAR",
        "LOCAL_TRADINGVIEW_SIGNAL_STALE",
        "LOCAL_TRADINGVIEW_INVALID_OCO_PLAN",
        "BLOCKED_LOCAL_TRADINGVIEW_PRE_EXECUTION_GATES",
        "BLOCKED_LOCAL_TRADINGVIEW_REPLAY_HISTORY_INCOMPLETE",
        "READY_PRE_EXECUTION_GATES",
        "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_OBSERVATION_NOT_LIVE",
        "READY_FOR_LOCAL_TRADINGVIEW_BTC_BASE_DRY_RUN_OBSERVATION_NOT_LIVE",
        "READY_FOR_LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_ARMED_REVIEW_NOT_MUTATION",
        "READY_FOR_LOCAL_TRADINGVIEW_LIVE_MICRO_ARMED_REVIEW_NOT_MUTATION",
        "BLOCKED_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED",
        "WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE",
        "RequireCurrentCandidate",
        "RequireDryRunArmed",
        "RequireBtcBaseLiveMicroArmed",
        "RequireLiveMicroArmed",
        "RequireOcoLifecycleTracked",
        "Assert-RemotePathSafe",
        "Assert-SshHostSafe",
        "Assert-McpSmokeTokenSafe",
        "BatchMode=yes",
        "bash -s",
        "http://127\.0\.0\.1:\{os\.environ\['PORT'\]\}/api/mcp",
        "TRADING_MCP_KEY",
        "TRADING_SIGNAL_SOURCE_PRIMARY",
        "TRADINGVIEW_LOCAL_EXECUTION_MODE",
        "BTC_BASE_DRY_RUN",
        "BTC_BASE_LIVE_MICRO",
        "TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT",
        "TRADING_OCO_POLLER_ENABLED")) {
    Assert-Contains -Name "local TradingView candidate smoke" -Pattern $pattern
}

if ($text -match "placeMarketBuy|placeOco|tools/call.*execute|sendAlert") {
    throw "local TradingView candidate smoke must stay read-only and must not call order/OCO/Telegram write paths"
}

Write-Host "[local-tradingview-candidate-smoke-test] OK"
