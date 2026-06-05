Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $templatePath = ".env.trading.secrets.example"
    if (-not (Test-Path $templatePath)) {
        throw "Env template missing: $templatePath"
    }

    $templateKeys = [ordered]@{}
    Get-Content $templatePath | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) {
            return
        }
        if ($line -notmatch "^([A-Z0-9_]+)=(.*)$") {
            throw "Invalid env template line: $_"
        }
        $templateKeys[$Matches[1]] = $Matches[2]
    }

    $requiredByScripts = [ordered]@{}
    foreach ($scriptPath in @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh")) {
        $content = Get-Content -Raw $scriptPath
        [regex]::Matches($content, "require_env_key\s+([A-Z0-9_]+)") | ForEach-Object {
            $key = $_.Groups[1].Value
            if (-not $requiredByScripts.Contains($key)) {
                $requiredByScripts[$key] = New-Object System.Collections.Generic.List[string]
            }
            $requiredByScripts[$key].Add($scriptPath)
        }
    }

    foreach ($key in $requiredByScripts.Keys) {
        if (-not $templateKeys.Contains($key)) {
            throw "Env template missing required key from server scripts: $key used by $($requiredByScripts[$key] -join ', ')"
        }
    }

    foreach ($key in @("AGORA_MARKET_INTERNAL_TIMEOUT_MS", "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_FLYWAY_ENABLED", "PORT")) {
        if (-not $templateKeys.Contains($key)) {
            throw "Env template missing deploy default key: $key"
        }
    }

    $safeDefaults = [ordered]@{
        TRADING_SCHEDULER_POOL_SIZE = "4"
        META_CONTROL_STARTUP_BACKFILL_COINALYZE_ENABLED = "false"
        META_CONTROL_STARTUP_BACKFILL_COMPOSITE_INDICATOR_ENABLED = "false"
        META_CONTROL_STARTUP_BACKFILL_DEX_FLOW_ENABLED = "false"
        META_CONTROL_STARTUP_BACKFILL_HYPERLIQUID_FUNDING_ENABLED = "false"
        META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED = "false"
        META_CONTROL_INDICATOR_HISTORY_ENABLED = "false"
        META_CONTROL_BTC_PRICE_MOVE_INDICATOR_ENABLED = "false"
        META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED = "false"
        META_CONTROL_ATTRIBUTION_ENABLED = "false"
        META_CONTROL_AUDIT_ENABLED = "false"
        KLINE_PRUNING_ENABLED = "false"
        TRADING_EPHEMERAL_CLEANUP_ENABLED = "false"
        META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED = "false"
        META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED = "false"
        META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED = "false"
        META_CONTROL_ML_PROTECTION_ENABLED = "false"
        META_CONTROL_ML_EDGE_WATCHER_ENABLED = "false"
        META_CONTROL_ML_AUTORETRAIN_ENABLED = "false"
        META_CONTROL_DAILY_ML_DIGEST_ENABLED = "false"
        META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED = "false"
        META_CONTROL_SCORECARD_DIGEST_ENABLED = "false"
        META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED = "false"
        META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED = "false"
        WICK_CAPTURE_SHADOW_ENABLED = "false"
        WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED = "false"
        SHADOW_CLEANUP_ENABLED = "false"
        TRADING_DAILY_TG_REPORT_ENABLED = "false"
        TRADING_AUTONOMOUS_DIGEST_ENABLED = "false"
        TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED = "false"
        TRADING_AUTONOMOUS_DIGEST_SEVERE_SCAN_ENABLED = "false"
        TRADING_AUTONOMOUS_DIGEST_SNAPSHOT_REFRESH_ENABLED = "false"
        TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED = "false"
        TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED = "false"
        TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED = "false"
        TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED = "false"
        EVENT_SCAN_NOTIFICATION_ENABLED = "false"
        EVENT_SCAN_NOTIFICATION_DRY_RUN = "true"
        EXECUTION_EVENT_ENABLED = "false"
        EXECUTION_EVENT_NOTIFICATION_DRY_RUN = "true"
        TRADING_BTC_PRICE_MOVE_ALERT_ENABLED = "false"
        TRADING_GEMINI_ADVISOR_ENABLED = "false"
        TRADING_GEMINI_ADVISOR_FLIP_DETECTOR_ENABLED = "false"
        TRADING_GEMINI_ADVISOR_STALENESS_DETECTOR_ENABLED = "false"
        TRADING_EXPLORATION_MONITOR_ENABLED = "false"
        TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED = "false"
        TRADING_EXPLORATION_LOOP_ENABLED = "false"
        TRADING_EXPLORATION_LOOP_TELEGRAM_ENABLED = "false"
        TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED = "false"
        TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED = "false"
        TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION = "false"
        TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE = "false"
        MARKET_SIGNAL_RISK_CARD_ENABLED = "false"
        MARKET_SIGNAL_RISK_CARD_DRY_RUN = "true"
        SIGNAL_VERIFICATION_SCHEDULER_ENABLED = "false"
        AGORA_ALPHA_TRACKER_ENABLED = "false"
        MARKET_LIQUIDATION_WS_ENABLED = "false"
        MARKET_WS_AUTO_SUBSCRIBE_ENABLED = "false"
        MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED = "false"
        POLYMARKET_MONITOR_ENABLED = "false"
        TRADING_WAI_ENABLED = "false"
        TRADING_GRID_ENABLED = "false"
        TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED = "false"
        GRID_RECOVERY_ENABLED = "false"
        TRADING_FUNDING_ARB_ENABLED = "false"
        TRADING_SHORT_SQUEEZE_ALERT_ENABLED = "false"
        TRADING_SHORT_SQUEEZE_ALERT_TAKER_BUY_COLLECTOR_ENABLED = "false"
        TRADING_OKX_ENABLED = "false"
        TRADING_OCO_POLLER_ENABLED = "false"
        TRADING_BINANCE_ENABLED = "false"
        TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED = "false"
        TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN = "true"
        TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED = "false"
        TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN = "true"
        TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED = "false"
        TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN = "true"
        TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED = "false"
        TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN = "true"
        TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED = "false"
        TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED = "false"
        MCP_GUARDIAN_LIVE_ACTIONS_ENABLED = "false"
        TRADING_RUNTIME_EVIDENCE_ENABLED = "false"
        TRADING_DISCOVERY_AI_SUGGESTIONS_ENABLED = "false"
        OKX_EARN_TOPUP_ENABLED = "false"
        TRAILING_STOP_ENABLED = "false"
        TRAILING_STOP_DRY_RUN = "true"
        POSITION_EXIT_MANAGER_ENABLED = "false"
        POSITION_EXIT_MANAGER_DRY_RUN = "true"
    }

    foreach ($key in $safeDefaults.Keys) {
        if (-not $templateKeys.Contains($key)) {
            throw "Env template missing optional safety key: $key"
        }
        if ($templateKeys[$key] -ne $safeDefaults[$key]) {
            throw "Env template optional safety key must default to $($safeDefaults[$key]): $key"
        }
    }

    foreach ($key in @("TRADING_ADMIN_KEY", "TRADING_MCP_KEY", "AGORA_MARKET_INTERNAL_API_KEY", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE", "TRADING_BINANCE_API_KEY", "TRADING_BINANCE_SECRET_KEY", "TELEGRAM_BOT_TOKEN", "GEMINI_API_KEY", "GROQ_API_KEY", "ANTHROPIC_API_KEY", "JINA_API_KEY", "TRADING_MARKET_DATA_COINALYZE_API_KEY", "EXTERNAL_COINGECKO_DEMO_API_KEY", "EXTERNAL_FRED_API_KEY", "EXTERNAL_ETHERSCAN_API_KEY", "EXTERNAL_ALCHEMY_API_KEY", "EXTERNAL_THEGRAPH_API_KEY", "EXCHANGE_RATE_COINMARKETCAP_API_KEY")) {
        if ($templateKeys[$key] -ne "") {
            throw "Secret-like env template key must stay empty: $key"
        }
    }

    if ($templateKeys["AGORA_MARKET_BASE_URL"] -ne "http://127.0.0.1:8082") {
        throw "AGORA_MARKET_BASE_URL should point at local AgoraMarketAPI dependency in the template"
    }
    if ($templateKeys["SPRING_JPA_HIBERNATE_DDL_AUTO"] -ne "update") {
        throw "Template must keep temporary bootstrap-only schema mode until Flyway baseline exists"
    }
    if ($templateKeys["SPRING_FLYWAY_ENABLED"] -ne "false") {
        throw "Template must keep Flyway disabled until baseline exists"
    }
    if ($templateKeys["PORT"] -ne "8084") {
        throw "Template must default to the blue-green 8084 port"
    }

    $ignore = Get-Content -Raw ".gitignore"
    if ($ignore -notmatch "(?m)^!\.env\.trading\.secrets\.example$") {
        throw ".gitignore must allow tracking .env.trading.secrets.example"
    }

    Write-Host "[env-template] OK $templatePath covers $($requiredByScripts.Keys.Count) required server env key(s)"
} finally {
    Pop-Location
}
