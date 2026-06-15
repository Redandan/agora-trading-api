param(
    [int]$Port = 18084,
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Stop-ProcessTree {
    param([int]$RootPid)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootPid" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -RootPid $child.ProcessId
    }

    $process = Get-Process -Id $RootPid -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $RootPid -Force -ErrorAction SilentlyContinue
    }
}

function Assert-LogContains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Description
    )

    if (-not (Select-String -Path $Path -Pattern $Pattern -Quiet)) {
        throw "Local smoke log missing expected evidence: $Description. pattern=$Pattern stdout=$Path"
    }
}

function Assert-LogNotContains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Description
    )

    $match = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if ($match) {
        throw "Local smoke log contains forbidden evidence: $Description. match=$($match.Line) stdout=$Path"
    }
}

function Invoke-McpTool {
    param(
        [string]$Url,
        [string]$ToolName,
        [hashtable]$Arguments = @{}
    )

    $body = @{
        jsonrpc = "2.0"
        id = "local-smoke-$ToolName"
        method = "tools/call"
        params = @{
            name = $ToolName
            arguments = $Arguments
        }
    } | ConvertTo-Json -Depth 8 -Compress

    $response = Invoke-WebRequest `
        -Uri $Url `
        -Method Post `
        -UseBasicParsing `
        -TimeoutSec 30 `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer local-smoke-mcp" } `
        -Body $body

    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw "Local smoke MCP $ToolName failed with HTTP $($response.StatusCode). url=$Url"
    }
    if ($response.Content -notmatch '"content"\s*:') {
        throw "Local smoke MCP $ToolName response missing content array. url=$Url"
    }
    return $response.Content
}

function Assert-McpContentContains {
    param(
        [string]$Content,
        [string]$Pattern,
        [string]$Description
    )

    if ($Content -notmatch $Pattern) {
        throw "Local smoke MCP response missing expected evidence: $Description. pattern=$Pattern content=$Content"
    }
}

function Assert-McpToolsPresent {
    param(
        [string]$Url,
        [string[]]$RequiredTools
    )

    $body = @{
        jsonrpc = "2.0"
        id = "local-smoke-tools-list"
        method = "tools/list"
        params = @{}
    } | ConvertTo-Json -Depth 8 -Compress

    $response = Invoke-WebRequest `
        -Uri $Url `
        -Method Post `
        -UseBasicParsing `
        -TimeoutSec 30 `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer local-smoke-mcp" } `
        -Body $body

    $parsed = $response.Content | ConvertFrom-Json
    $toolNames = @($parsed.result.tools | ForEach-Object { $_.name } | Sort-Object -Unique)
    $missing = @($RequiredTools | Where-Object { $toolNames -notcontains $_ })
    if ($missing.Count -gt 0) {
        throw "Local smoke MCP tools/list missing required parity tool(s): $($missing -join ', ')"
    }
}

function Assert-HttpStatus {
    param(
        [string]$Url,
        [int]$ExpectedStatus,
        [hashtable]$Headers = @{},
        [string]$Description
    )

    $actualStatus = $null
    try {
        $response = Invoke-WebRequest `
            -Uri $Url `
            -UseBasicParsing `
            -TimeoutSec 10 `
            -Headers $Headers
        $actualStatus = [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -ne $null) {
            $actualStatus = [int]$_.Exception.Response.StatusCode
        } else {
            throw "Local smoke HTTP request failed before status check: $Description. url=$Url error=$($_.Exception.Message)"
        }
    }

    if ($actualStatus -ne $ExpectedStatus) {
        throw "Local smoke HTTP status mismatch: $Description. expected=$ExpectedStatus actual=$actualStatus url=$Url"
    }
}

$repo = Resolve-Path "$PSScriptRoot\.."
$healthUrl = "http://127.0.0.1:$Port/api/trading/actuator/health"
$mcpUrl = "http://127.0.0.1:$Port/api/trading/mcp"
$internalReportUrl = "http://127.0.0.1:$Port/api/trading/internal/reports/current"
$logDir = Join-Path $repo "logs\local-smoke"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format "yyyyMMddTHHmmss"
$stdout = Join-Path $logDir "smoke-$stamp.out.log"
$stderr = Join-Path $logDir "smoke-$stamp.err.log"

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    throw "Port $Port is already listening. Choose another port with -Port."
}

$mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue)
if (-not $mvn) {
    $mvn = Get-Command mvn -ErrorAction Stop
}

$process = $null
$previousEnv = @{}
$envOverrides = @{
    AGORA_MARKET_INTERNAL_API_KEY = ""
    AGORA_MARKET_BASE_URL = "http://127.0.0.1:0"
    AGORA_MARKET_INTERNAL_TIMEOUT_MS = "3000"
    SPRING_DATASOURCE_URL = "jdbc:h2:mem:trading-local-smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
    SPRING_DATASOURCE_USERNAME = "sa"
    SPRING_DATASOURCE_PASSWORD = ""
    SPRING_JPA_HIBERNATE_DDL_AUTO = "create-drop"
    SPRING_FLYWAY_ENABLED = "false"
    TRADING_SCHEDULER_POOL_SIZE = "1"
    TRADING_CORS_ALLOWED_ORIGINS = "http://localhost:*,http://127.0.0.1:*"
    META_CONTROL_ML_SQL_SCHEMA = "agora_market"
    META_CONTROL_ML_SQL_SIGNAL_SCORER_TRAINING_TABLE = "bt_signal_training_v8_mat"
    META_CONTROL_ML_SQL_WEEKLY_RETRAIN_TRAINING_VIEW = "vw_signal_training_v2"
    TRADING_MCP_KEY = "local-smoke-mcp"
    MCP_API_KEY = "local-smoke-mcp"
    MCP_OPS_KEY = "local-smoke-mcp"
    TELEGRAM_BOT_TOKEN = ""
    TELEGRAM_CHANNEL_ID = ""
    TELEGRAM_BOT_CHANNEL_ID = ""
    TRADING_OKX_ENABLED = "false"
    TRADING_OCO_POLLER_ENABLED = "false"
    TRADING_OKX_API_KEY = ""
    TRADING_OKX_SECRET_KEY = ""
    TRADING_OKX_PASSPHRASE = ""
    TRADING_BINANCE_ENABLED = "false"
    TRADING_BINANCE_API_KEY = ""
    TRADING_BINANCE_SECRET_KEY = ""
    GEMINI_API_KEY = ""
    GROQ_API_KEY = ""
    ANTHROPIC_API_KEY = ""
    JINA_API_KEY = ""
    TRADING_MARKET_DATA_COINALYZE_API_KEY = ""
    EXTERNAL_COINGECKO_DEMO_API_KEY = ""
    EXTERNAL_FRED_API_KEY = ""
    EXTERNAL_ETHERSCAN_API_KEY = ""
    EXTERNAL_ALCHEMY_API_KEY = ""
    EXTERNAL_THEGRAPH_API_KEY = ""
    EXCHANGE_RATE_COINMARKETCAP_API_KEY = ""
    META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED = "false"
    META_CONTROL_SCORECARD_DIGEST_ENABLED = "false"
    META_CONTROL_STARTUP_BACKFILL_COINALYZE_ENABLED = "false"
    META_CONTROL_STARTUP_BACKFILL_COMPOSITE_INDICATOR_ENABLED = "false"
    META_CONTROL_STARTUP_BACKFILL_DEX_FLOW_ENABLED = "false"
    META_CONTROL_STARTUP_BACKFILL_HYPERLIQUID_FUNDING_ENABLED = "false"
    META_CONTROL_ATTRIBUTION_ENABLED = "false"
    META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED = "false"
    META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED = "false"
    META_CONTROL_INDICATOR_HISTORY_ENABLED = "false"
    META_CONTROL_BTC_PRICE_MOVE_INDICATOR_ENABLED = "false"
    META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED = "false"
    META_CONTROL_AUDIT_ENABLED = "false"
    KLINE_PRUNING_ENABLED = "false"
    TRADING_EPHEMERAL_CLEANUP_ENABLED = "false"
    META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED = "false"
    META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED = "false"
    META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED = "false"
    META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED = "false"
    META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED = "false"
    META_CONTROL_ML_PROTECTION_ENABLED = "false"
    META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD = "false"
    META_CONTROL_ML_SHADOW_ENABLED = "false"
    META_CONTROL_ML_EDGE_WATCHER_ENABLED = "false"
    META_CONTROL_ML_AUTORETRAIN_ENABLED = "false"
    META_CONTROL_DAILY_ML_DIGEST_ENABLED = "false"
    TRADING_GEMINI_ADVISOR_ENABLED = "false"
    TRADING_GEMINI_ADVISOR_FLIP_DETECTOR_ENABLED = "false"
    TRADING_GEMINI_ADVISOR_STALENESS_DETECTOR_ENABLED = "false"
    TRADING_LONG_AI_FILTER_ENABLED = "false"
    TRADING_SHORT_AI_FILTER_ENABLED = "false"
    TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED = "false"
    TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED = "false"
    TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED = "false"
    TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED = "false"
    EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED = "false"
    MARKET_WS_AUTO_SUBSCRIBE_ENABLED = "false"
    MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED = "false"
    MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS = "okx"
    MARKET_LIQUIDATION_WS_ENABLED = "false"
    TRADING_KLINE_DIVERGENCE_ENABLED = "false"
    OKX_EARN_TOPUP_ENABLED = "false"
    POLYMARKET_MONITOR_ENABLED = "false"
    TRADING_EXPLORATION_MONITOR_ENABLED = "false"
    TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED = "false"
    TRADING_EXPLORATION_LOOP_ENABLED = "false"
    TRADING_EXPLORATION_LOOP_TELEGRAM_ENABLED = "false"
    TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED = "false"
    TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED = "false"
    TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION = "false"
    TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE = "false"
    TRADING_AUTONOMOUS_DIGEST_ENABLED = "false"
    TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED = "false"
    TRADING_AUTONOMOUS_DIGEST_SEVERE_SCAN_ENABLED = "false"
    TRADING_AUTONOMOUS_DIGEST_SNAPSHOT_REFRESH_ENABLED = "false"
    TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED = "false"
    TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED = "false"
    TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED = "false"
    TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN = "true"
    TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED = "false"
    TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN = "true"
    TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED = "false"
    TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN = "true"
    TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED = "false"
    TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED = "false"
    EVENT_SCAN_NOTIFICATION_ENABLED = "false"
    EVENT_SCAN_NOTIFICATION_DRY_RUN = "true"
    EXECUTION_EVENT_ENABLED = "false"
    EXECUTION_EVENT_NOTIFICATION_DRY_RUN = "true"
    WICK_CAPTURE_SHADOW_ENABLED = "false"
    WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED = "false"
    SHADOW_CLEANUP_ENABLED = "false"
    GRID_RECOVERY_ENABLED = "false"
    TRADING_DAILY_TG_REPORT_ENABLED = "false"
    TRADING_BTC_PRICE_MOVE_ALERT_ENABLED = "false"
    MARKET_SIGNAL_RISK_CARD_ENABLED = "false"
    MARKET_SIGNAL_RISK_CARD_DRY_RUN = "true"
    TRADING_WAI_ENABLED = "false"
    TRADING_GRID_ENABLED = "false"
    TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED = "false"
    TRADING_FUNDING_ARB_ENABLED = "false"
    TRAILING_STOP_ENABLED = "false"
    TRAILING_STOP_DRY_RUN = "true"
    TRADING_SHORT_SQUEEZE_ALERT_ENABLED = "false"
    TRADING_SHORT_SQUEEZE_ALERT_TAKER_BUY_COLLECTOR_ENABLED = "false"
    SIGNAL_VERIFICATION_SCHEDULER_ENABLED = "false"
    AGORA_ALPHA_TRACKER_ENABLED = "false"
    AI_STRATEGY_DISCOVERY_ENABLED = "false"
    TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED = "false"
    TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED = "false"
    TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED = "false"
    TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN = "true"
    MCP_GUARDIAN_LIVE_ACTIONS_ENABLED = "false"
    TRADING_RUNTIME_EVIDENCE_ENABLED = "false"
    TRADING_DISCOVERY_AI_SUGGESTIONS_ENABLED = "false"
    POSITION_EXIT_MANAGER_ENABLED = "false"
    POSITION_EXIT_MANAGER_DRY_RUN = "true"
}
Push-Location $repo
try {
    Write-Host "[smoke] starting local-smoke profile on port $Port"
    foreach ($name in $envOverrides.Keys) {
        $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $envOverrides[$name], "Process")
    }
    $bootArguments = @(
        "--server.port=$Port",
        "--agora-market.base-url=http://127.0.0.1:0",
        "--agora-market.internal-api-key=",
        "--agora-market.timeout-ms=3000",
        "--spring.datasource.url=jdbc:h2:mem:trading-local-smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--spring.jpa.hibernate.ddl-auto=create-drop",
        "--spring.flyway.enabled=false",
        "--spring.task.scheduling.pool.size=1",
        "--app.cors.allowed-origins=http://localhost:*,http://127.0.0.1:*",
        "--meta-control.ml.sql.schema=agora_market",
        "--meta-control.ml.sql.signal-scorer-training-table=bt_signal_training_v8_mat",
        "--meta-control.ml.sql.weekly-retrain-training-view=vw_signal_training_v2",
        "--mcp.api-key=local-smoke-mcp",
        "--mcp.ops-key=local-smoke-mcp",
        "--telegram.bot.token=",
        "--telegram.bot.channel-id=",
        "--trading.okx.api-key=",
        "--trading.okx.secret-key=",
        "--trading.okx.passphrase=",
        "--trading.oco-poller.enabled=false",
        "--trading.binance.api-key=",
        "--trading.binance.secret-key=",
        "--gemini.api.key=",
        "--groq.api.key=",
        "--anthropic.api.key=",
        "--jina.api.key=",
        "--trading.market-data.coinalyze.api-key=",
        "--external.coingecko.demo-api-key=",
        "--external.fred.api-key=",
        "--external.etherscan.api-key=",
        "--external.alchemy.api-key=",
        "--external.thegraph.api-key=",
        "--exchange-rate.coinmarketcap.api-key=",
        "--meta-control.attention-weekly-digest.enabled=false",
        "--meta-control.scorecard-digest.enabled=false",
        "--meta-control.startup-backfill.coinalyze.enabled=false",
        "--meta-control.startup-backfill.composite-indicator.enabled=false",
        "--meta-control.startup-backfill.dex-flow.enabled=false",
        "--meta-control.startup-backfill.hyperliquid-funding.enabled=false",
        "--meta-control.attribution.enabled=false",
        "--meta-control.ml-materialized-refresh.startup-check-enabled=false",
        "--meta-control.hourly-orchestrator.enabled=false",
        "--meta-control.indicator-history.enabled=false",
        "--meta-control.btc-price-move-indicator.enabled=false",
        "--meta-control.etf-pressure.refresh-enabled=false",
        "--meta-control.audit.enabled=false",
        "--kline-pruning.enabled=false",
        "--trading.ephemeral-cleanup.enabled=false",
        "--meta-control.composite-indicator.scheduler-enabled=false",
        "--meta-control.market-indicator-attention.enabled=false",
        "--meta-control.market-flip-detector.enabled=false",
        "--meta-control.market-flip.analysis-enabled=false",
        "--meta-control.market-flip.auto-escalate-enabled=false",
        "--meta-control.ml-protection.enabled=false",
        "--meta-control.ml-protection.auto-kill-secondary-load=false",
        "--meta-control.ml-shadow.enabled=false",
        "--meta-control.ml-edge-watcher.enabled=false",
        "--meta-control.ml-autoretrain.enabled=false",
        "--meta-control.daily-ml-digest.enabled=false",
        "--trading.gemini-advisor.enabled=false",
        "--trading.gemini-advisor.flip-detector-enabled=false",
        "--trading.gemini-advisor.staleness-detector-enabled=false",
        "--trading.long-ai-filter.enabled=false",
        "--trading.short-ai-filter.enabled=false",
        "--trading.ensemble-preview.live-market-reads-enabled=false",
        "--trading.market-data-mcp.live-sentiment-enabled=false",
        "--trading.market-data-mcp.external-health-probes-enabled=false",
        "--trading.market-data-mcp.external-backfills-enabled=false",
        "--event-risk-control.status-notify-enabled=false",
        "--market.ws.auto-subscribe.enabled=false",
        "--market.ws.auto-subscribe.warm-up-enabled=false",
        "--market.ws.auto-subscribe.providers=okx",
        "--market.liquidation-ws.enabled=false",
        "--trading.kline-divergence.enabled=false",
        "--okx.earn-topup.enabled=false",
        "--polymarket.monitor.enabled=false",
        "--trading.exploration.monitor.enabled=false",
        "--trading.exploration.monitor.telegram.enabled=false",
        "--trading.exploration.loop.enabled=false",
        "--trading.exploration.loop.telegram.enabled=false",
        "--trading.exploration.loop.production.enabled=false",
        "--trading.exploration.rollout.auto-enabled=false",
        "--trading.exploration.rollout.allow-production-promotion=false",
        "--trading.exploration.rollout.allow-cap-increase=false",
        "--trading.autonomous.digest.enabled=false",
        "--trading.autonomous.digest.telegram-enabled=false",
        "--trading.autonomous.digest.severe-scan-enabled=false",
        "--trading.autonomous.digest.snapshot-refresh-enabled=false",
        "--trading.score-buy.forming-day.notification.enabled=false",
        "--trading.score-buy.forming-day.notification.telegram-enabled=false",
        "--trading.score-buy.pre-position.execution.enabled=false",
        "--trading.score-buy.pre-position.execution.dry-run=true",
        "--trading.score-buy.confirmed-deploy.execution.enabled=false",
        "--trading.score-buy.confirmed-deploy.execution.dry-run=true",
        "--trading.score-buy.post-scout-add.execution.enabled=false",
        "--trading.score-buy.post-scout-add.execution.dry-run=true",
        "--trading.score-buy.post-scout-add.notification.enabled=false",
        "--trading.score-buy.post-scout-add.notification.telegram-enabled=false",
        "--event-scan.notification.enabled=false",
        "--event-scan.notification.dry-run=true",
        "--execution-event.enabled=false",
        "--execution-event.notification-dry-run=true",
        "--wick-capture.shadow.enabled=false",
        "--wick-capture.shadow.bootstrap-enabled=false",
        "--shadow-cleanup.enabled=false",
        "--grid.recovery.enabled=false",
        "--trading.daily-tg-report.enabled=false",
        "--trading.btc-price-move-alert.enabled=false",
        "--market-signal.risk-card.enabled=false",
        "--market-signal.risk-card.dry-run=true",
        "--trading.wai.enabled=false",
        "--trading.grid.enabled=false",
        "--trading.grid.auto-rebalance-scheduler.enabled=false",
        "--trading.funding-arb.enabled=false",
        "--trailing-stop.enabled=false",
        "--trailing-stop.dry-run=true",
        "--trading.short-squeeze-alert.enabled=false",
        "--trading.short-squeeze-alert.taker-buy-collector-enabled=false",
        "--signal-verification.scheduler.enabled=false",
        "--agora.alpha-tracker.enabled=false",
        "--ai.strategy.discovery.enabled=false",
        "--trading.live-signal.retry-notification.enabled=false",
        "--trading.event-calendar.freshness-notification-enabled=false",
        "--trading.tiny-live.auto-execution.enabled=false",
        "--trading.tiny-live.auto-execution.dry-run=true",
        "--mcp.guardian-live-actions-enabled=false",
        "--trading.runtime-evidence.enabled=false",
        "--trading.discovery.ai-suggestions.enabled=false",
        "--position-exit-manager.enabled=false",
        "--position-exit-manager.dry-run=true"
    )
    $args = @(
        "spring-boot:run",
        "-Dspring-boot.run.profiles=local-smoke",
        "-Dspring-boot.run.useTestClasspath=true",
        "-Dspring-boot.run.arguments=`"$($bootArguments -join ' ')`""
    )
    $process = Start-Process `
        -FilePath $mvn.Source `
        -ArgumentList $args `
        -WorkingDirectory $repo `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $ready = $false
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "Local smoke process exited before health passed. stdout=$stdout stderr=$stderr"
        }

        try {
            $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    if (-not $ready) {
        throw "Timed out waiting for $healthUrl. stdout=$stdout stderr=$stderr"
    }

    Start-Sleep -Milliseconds 500
    Assert-LogContains -Path $stdout -Pattern 'profile is active: "local-smoke"' -Description "local-smoke profile is active"
    Assert-LogContains -Path $stdout -Pattern "Scheduling disabled for local-smoke profile" -Description "local-smoke scheduling is disabled"
    Assert-LogContains -Path $stdout -Pattern "jdbc:h2:mem:trading-local-smoke" -Description "local-smoke uses in-memory H2 database"
    Assert-LogContains -Path $stdout -Pattern "Auto-trade enabled\s*:\s*false" -Description "OKX auto-trade is disabled"
    Assert-LogContains -Path $stdout -Pattern "API Key configured\s*:\s*false" -Description "OKX API key is cleared for local-smoke"
    Assert-LogContains -Path $stdout -Pattern "OCO poller disabled.*private WS skipped" -Description "OKX private WebSocket is skipped by OCO poller guard"
    Assert-LogContains -Path $stdout -Pattern "AGORA_MARKET_INTERNAL_API_KEY not configured; using static fallback rates" -Description "exchange-rate client uses static fallback"
    Assert-LogContains -Path $stdout -Pattern "startup check disabled" -Description "ML materialized refresh startup check is disabled"
    Assert-LogContains -Path $stdout -Pattern "AiTaskRouter.*initialized with 0 providers" -Description "local-smoke does not initialize external AI providers"
    Assert-LogContains -Path $stdout -Pattern "Jina embedding client initialised: enabled=false" -Description "local-smoke does not enable external Jina embeddings"
    Assert-LogContains -Path $stdout -Pattern "MarketWS.*auto-subscribe config: enabled=false" -Description "local-smoke does not enable public market WS auto-subscribe"
    Assert-LogContains -Path $stdout -Pattern "OkxLiqWS.*disabled by market\.liquidation-ws\.enabled=false" -Description "local-smoke does not enable OKX liquidation WebSocket"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(Auto subscribed via|Warming up MarketSignalCache)" -Description "local-smoke must not auto-subscribe public market WebSockets or warm market cache"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(Attribution/startup|MlMatRefresh.*start refresh|MlMatRefresh.*kicking off initial refresh)" -Description "local-smoke must not schedule attribution startup backfill or refresh ML materialized data"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(DexFlowBackfill|HLFundingBackfill|CoinalyzeBackfill|CMIBackfill)" -Description "local-smoke must not run startup market-data backfills"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(Trading buffer topped from Earn|Simple Earn)" -Description "local-smoke must not top up from OKX Earn"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(PolymarketMonitor.*(fatal|digest|snapshot)|Polymarket.*HIGH|backfill4h)" -Description "local-smoke must not run Polymarket monitor jobs"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(modifyOco|state .*->|sl .*->)" -Description "local-smoke must not modify trailing-stop OCO state"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(ShortSqueezeAlert.*FIRED|SpotTakerBuy.*15m taker buy|SpotTakerBuy.*collect failed)" -Description "local-smoke must not run short-squeeze alert or taker-buy collection"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(order placed|placing order|submitted order|send telegram|sent telegram|connected to private|private ws connected|auto-execution enabled|auto-trade enabled\s*:\s*true)" -Description "local-smoke must not place orders, send notifications, connect private trading WS, or enable auto execution"

    Assert-HttpStatus -Url $internalReportUrl -ExpectedStatus 401 -Description "internal report gateway rejects missing service key"
    Assert-HttpStatus -Url $internalReportUrl -ExpectedStatus 200 -Headers @{ "X-Internal-Api-Key" = "local-smoke-internal-key" } -Description "internal report gateway serves current report with service key"
    Start-Sleep -Milliseconds 200
    Assert-LogContains -Path $stdout -Pattern "OKX private API credentials are not configured; current report omits account balances" -Description "current report degrades explicitly when OKX private credentials are empty"
    Assert-LogNotContains -Path $stdout -Pattern "OKX signing failed" -Description "current report must not attempt OKX private signing when credentials are empty"

    [void](Invoke-McpTool -Url $mcpUrl -ToolName "getMcpRegistryVersion")
    Assert-McpToolsPresent -Url $mcpUrl -RequiredTools @(
        "getMcpRegistryVersion",
        "getMcpAuthProbe",
        "listSchedulerTasks",
        "listStrategies",
        "runBacktest",
        "listGrids",
        "getOpenPositions",
        "getSystemHealth",
        "getMarketSentiment",
        "getCollectionFreshness",
        "diagnoseDataFreshnessGuardBlocks",
        "getReport",
        "getTradingManagerDigest",
        "getMlLimits",
        "listRuntimeDecisionEvidence",
        "getScoreBuyFormingDayStatus",
        "listExecutionEvents",
        "getGuardianSnapshot",
        "listFundingArb",
        "getEarnBalance",
        "previewEnsembleScore",
        "listAiProviders",
        "listAiTasks"
    )

    $sentimentGuard = Invoke-McpTool -Url $mcpUrl -ToolName "getMarketSentiment" -Arguments @{ symbol = "BTCUSDT" }
    Assert-McpContentContains -Content $sentimentGuard -Pattern "TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true" -Description "live sentiment MCP tools are disabled by default"

    $healthGuard = Invoke-McpTool -Url $mcpUrl -ToolName "getSystemHealth"
    Assert-McpContentContains -Content $healthGuard -Pattern "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true" -Description "external MCP health probes are disabled by default"

    $backfillGuard = Invoke-McpTool -Url $mcpUrl -ToolName "backfillOkxKlines" -Arguments @{ symbol = "BTCUSDT"; intervalCode = "1h"; days = 1 }
    Assert-McpContentContains -Content $backfillGuard -Pattern "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true" -Description "external MCP backfills are disabled by default"

    $dataFreshnessRca = Invoke-McpTool -Url $mcpUrl -ToolName "diagnoseDataFreshnessGuardBlocks" -Arguments @{ days = 1; symbol = "BTCUSDT"; limit = 5 }
    Assert-McpContentContains -Content $dataFreshnessRca -Pattern "boundary: READ_ONLY" -Description "DataFreshnessGuard RCA stays read-only in local smoke"
    Assert-McpContentContains -Content $dataFreshnessRca -Pattern "acceptance: PASS_NO_CURRENT_SAMPLE|acceptance: PASS_RCA_CLASSIFIED" -Description "DataFreshnessGuard RCA returns an explicit acceptance marker"

    Write-Host "[smoke] OK $healthUrl"
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-ProcessTree -RootPid $process.Id
    }
    foreach ($name in $envOverrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
    Pop-Location
}
