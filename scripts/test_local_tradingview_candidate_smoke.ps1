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
        "currentCandidateStatus",
        "HAS_CURRENT_BUY_CANDIDATE",
        "NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS",
        "localTradingViewExecutionDryRunArmed",
        "localTradingViewLiveMicroArmed",
        "localTradingViewExecutionPathArmed",
        "localTradingViewOcoLifecycleTracked",
        "localTradingViewOcoLifecycleStatus",
        "orderSentAllowed=false",
        "liveOrderMutationAllowed=false",
        "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE",
        "LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED",
        "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED",
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED",
        "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_OBSERVATION_NOT_LIVE",
        "READY_FOR_LOCAL_TRADINGVIEW_LIVE_MICRO_ARMED_REVIEW_NOT_MUTATION",
        "BLOCKED_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED",
        "WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE",
        "RequireCurrentCandidate",
        "RequireDryRunArmed",
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
        "TRADING_OCO_POLLER_ENABLED")) {
    Assert-Contains -Name "local TradingView candidate smoke" -Pattern $pattern
}

if ($text -match "placeMarketBuy|placeOco|tools/call.*execute|sendAlert") {
    throw "local TradingView candidate smoke must stay read-only and must not call order/OCO/Telegram write paths"
}

Write-Host "[local-tradingview-candidate-smoke-test] OK"
