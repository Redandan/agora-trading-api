param(
    [string]$Path = (Join-Path $PSScriptRoot "..\.env.trading.secrets.example")
)

$ErrorActionPreference = "Stop"
$resolved = (Resolve-Path -LiteralPath $Path).Path
$values = [ordered]@{}

foreach ($line in Get-Content -LiteralPath $resolved) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) {
        continue
    }
    $separator = $trimmed.IndexOf("=")
    if ($separator -lt 1) {
        throw "Invalid environment template line: $line"
    }
    $key = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1)
    if ($values.Contains($key)) {
        throw "Duplicate environment template key: $key"
    }
    $values[$key] = $value
}

$requiredKeys = @(
    "TRADING_MCP_KEY",
    "AGORA_MARKET_BASE_URL",
    "AGORA_MARKET_INTERNAL_API_KEY",
    "AGORA_MARKET_INTERNAL_TIMEOUT_MS",
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_JPA_HIBERNATE_DDL_AUTO",
    "SPRING_FLYWAY_ENABLED",
    "SPRING_FLYWAY_TABLE",
    "SPRING_FLYWAY_BASELINE_ON_MIGRATE",
    "SPRING_FLYWAY_BASELINE_VERSION",
    "TRADINGVIEW_LOCAL_ENABLED",
    "TRADINGVIEW_LOCAL_STRATEGY_ID",
    "TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS",
    "TRADINGVIEW_LOCAL_ALLOWED_INTERVALS",
    "TRADINGVIEW_LOCAL_ALLOWED_SOURCES",
    "TRADINGVIEW_LOCAL_EXECUTION_MODE",
    "TRADING_BTC_DONCHIAN_SHADOW_MODE",
    "TRADING_OKX_ENABLED",
    "TRADING_OCO_POLLER_ENABLED",
    "TRADING_OKX_API_KEY",
    "TRADING_OKX_SECRET_KEY",
    "TRADING_OKX_PASSPHRASE"
)
foreach ($key in $requiredKeys) {
    if (-not $values.Contains($key)) {
        throw "Environment template missing required key: $key"
    }
}

$safeDefaults = [ordered]@{
    SPRING_JPA_HIBERNATE_DDL_AUTO = "validate"
    SPRING_FLYWAY_ENABLED = "true"
    SPRING_FLYWAY_TABLE = "trading_flyway_schema_history"
    SPRING_FLYWAY_BASELINE_ON_MIGRATE = "true"
    SPRING_FLYWAY_BASELINE_VERSION = "1"
    TRADINGVIEW_LOCAL_ENABLED = "false"
    TRADINGVIEW_LOCAL_STRATEGY_ID = "485"
    TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS = "BTCUSDT"
    TRADINGVIEW_LOCAL_ALLOWED_INTERVALS = "1d"
    TRADINGVIEW_LOCAL_ALLOWED_SOURCES = "binance"
    TRADINGVIEW_LOCAL_EXECUTION_MODE = "BTC_BASE_PAPER"
    TRADING_BTC_DONCHIAN_SHADOW_MODE = "OFF"
    TRADING_OKX_ENABLED = "false"
    TRADING_OCO_POLLER_ENABLED = "false"
}
foreach ($key in $safeDefaults.Keys) {
    if (-not $values.Contains($key) -or $values[$key] -ne $safeDefaults[$key]) {
        throw "Environment template key must default to $($safeDefaults[$key]): $key"
    }
}

foreach ($key in @(
    "TRADING_MCP_KEY",
    "AGORA_MARKET_INTERNAL_API_KEY",
    "TRADING_INTERNAL_API_KEY",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "TRADING_OKX_API_KEY",
    "TRADING_OKX_SECRET_KEY",
    "TRADING_OKX_PASSPHRASE",
    "TELEGRAM_BOT_TOKEN",
    "TELEGRAM_CHANNEL_ID"
)) {
    if ($values[$key] -ne "") {
        throw "Secret-like environment template key must stay empty: $key"
    }
}

if ($values["AGORA_MARKET_BASE_URL"] -ne "https://agoramarketapi.purrtechllc.com") {
    throw "AGORA_MARKET_BASE_URL must use the stable AgoraMarketAPI dependency"
}
if ($values["AGORA_MARKET_INTERNAL_TIMEOUT_MS"] -ne "3000") {
    throw "AGORA_MARKET_INTERNAL_TIMEOUT_MS must remain bounded at 3000"
}
if ($values["SPRING_DATASOURCE_URL"] -notmatch "^jdbc:mysql://[^/]+/agora_market(\?|$)") {
    throw "SPRING_DATASOURCE_URL must point to the shared agora_market database"
}

Write-Output "Environment template validation passed: $($values.Count) keys"
