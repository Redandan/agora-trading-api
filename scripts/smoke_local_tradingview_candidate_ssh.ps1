param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$StrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1d",
    [int]$Days = 90,
    [string]$Source = "binance",
    [switch]$RequireCurrentCandidate,
    [switch]$RequireDryRunArmed,
    [switch]$RequireBtcBaseLiveMicroArmed,
    [switch]$RequireLiveMicroArmed,
    [switch]$RequireOcoLifecycleTracked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}

if ($Days -lt 7 -or $Days -gt 730) {
    throw "Days must be between 7 and 730."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16
Assert-McpSmokeTokenSafe -Name "Source" -Value $Source -MaxLength 32

$remoteScriptTemplate = @'
set -euo pipefail
cd '__APP_DIR__'

PORT=$(cat app.port)
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENV_FILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY ENV_FILE='__ENV_FILE__' STRATEGY_ID='__STRATEGY_ID__' SYMBOL='__SYMBOL__' INTERVAL_CODE='__INTERVAL_CODE__' DAYS='__DAYS__' SOURCE='__SOURCE__' REQUIRE_CURRENT='__REQUIRE_CURRENT__' REQUIRE_DRY_RUN_ARMED='__REQUIRE_DRY_RUN_ARMED__' REQUIRE_BTC_BASE_LIVE_MICRO_ARMED='__REQUIRE_BTC_BASE_LIVE_MICRO_ARMED__' REQUIRE_LIVE_MICRO_ARMED='__REQUIRE_LIVE_MICRO_ARMED__' REQUIRE_OCO_LIFECYCLE_TRACKED='__REQUIRE_OCO_LIFECYCLE_TRACKED__'
python3 - <<'PY'
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
env_file = os.environ["ENV_FILE"]
strategy_id = int(os.environ["STRATEGY_ID"])
symbol = os.environ["SYMBOL"].upper()
interval_code = os.environ["INTERVAL_CODE"].lower()
days = int(os.environ["DAYS"])
source = os.environ["SOURCE"].lower()
require_current = os.environ.get("REQUIRE_CURRENT", "").lower() == "true"
require_dry_run_armed = os.environ.get("REQUIRE_DRY_RUN_ARMED", "").lower() == "true"
require_btc_base_live_micro_armed = os.environ.get("REQUIRE_BTC_BASE_LIVE_MICRO_ARMED", "").lower() == "true"
require_live_micro_armed = os.environ.get("REQUIRE_LIVE_MICRO_ARMED", "").lower() == "true"
require_oco_lifecycle_tracked = os.environ.get("REQUIRE_OCO_LIFECYCLE_TRACKED", "").lower() == "true"

def read_env_key(key, default=""):
    try:
        with open(env_file, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == key:
                    return v.strip().strip('"').strip("'")
    except FileNotFoundError:
        return "MISSING_ENV_FILE"
    return default

def bool_env(key, default=False):
    value = read_env_key(key, "true" if default else "false").strip().lower()
    return value in ("1", "true", "yes", "y", "on")

def int_env(key, default):
    try:
        return int(str(read_env_key(key, str(default))).strip())
    except Exception:
        return default

def float_env(key, default):
    try:
        return float(str(read_env_key(key, str(default))).strip())
    except Exception:
        return default

def csv_tokens(value, normalize_as_symbol=False):
    if value is None or str(value).strip() == "":
        return []
    tokens = []
    for token in str(value).split(","):
        normalized = normalize_symbol(token) if normalize_as_symbol else token.strip().lower()
        if normalized:
            tokens.append(normalized)
    return tokens

def normalize_symbol(raw):
    if raw is None or str(raw).strip() == "":
        return ""
    value = str(raw).strip().upper()
    colon = value.rfind(":")
    if colon >= 0 and colon + 1 < len(value):
        value = value[colon + 1:]
    return value.replace("-", "").replace("/", "").replace("_", "")

def is_allowed(value, csv_value, normalize_as_symbol=False):
    allowed = csv_tokens(csv_value, normalize_as_symbol=normalize_as_symbol)
    if not allowed:
        return True
    normalized = normalize_symbol(value) if normalize_as_symbol else str(value or "").strip().lower()
    return bool(normalized) and normalized in allowed

def format_bool(value):
    return str(bool(value)).lower()

def parse_iso_datetime(value):
    if value is None or value == "N/A":
        return None
    text = str(value).strip()
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    return None

def mysql_datetime(value):
    dt = parse_iso_datetime(value)
    return None if dt is None else dt.strftime("%Y-%m-%d %H:%M:%S")

def sql_quote(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"

def parse_mysql_jdbc(url):
    if not url or url == "MISSING_ENV_FILE":
        return None
    match = re.match(r"^jdbc:mysql://([^:/?]+)(?::([0-9]+))?/([^?;]+)", url.strip())
    if not match:
        return None
    return {
        "host": match.group(1),
        "port": match.group(2) or "3306",
        "database": match.group(3).split("/", 1)[0],
    }

def mysql_scalar(query):
    jdbc = parse_mysql_jdbc(read_env_key("SPRING_DATASOURCE_URL", ""))
    user = read_env_key("SPRING_DATASOURCE_USERNAME", "")
    password = read_env_key("SPRING_DATASOURCE_PASSWORD", "")
    if not jdbc or not user:
        raise RuntimeError("datasource env is incomplete or unsupported")
    command = [
        "mysql",
        "--batch",
        "--raw",
        "--skip-column-names",
        "-h",
        jdbc["host"],
        "-P",
        str(jdbc["port"]),
        "-u",
        user,
        jdbc["database"],
        "-e",
        query,
    ]
    child_env = os.environ.copy()
    child_env["MYSQL_PWD"] = password
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=child_env, timeout=20)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout or "mysql exited non-zero").strip())
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    return lines[0] if lines else "0"

def mysql_count(query):
    return int(mysql_scalar(query))

def extract_order_rows(preview_text):
    rows = []
    pattern = re.compile(r"^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]+)\s+close=([0-9.]+)\s+signal=([A-Z_]+)\s+reason=([^ ]+)\s+qty=([0-9.]+)\s+label=([^ ]+)", re.MULTILINE)
    for match in pattern.finditer(preview_text or ""):
        rows.append({
            "bar": match.group(1),
            "close": match.group(2),
            "signal": match.group(3),
            "reason": match.group(4),
            "qty": match.group(5),
            "label": match.group(6),
        })
    return rows

def to_decimal(value):
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None

def decimal_text(value):
    if value is None:
        return "N/A"
    return format(value, "f")

def call_tool(name, arguments=None, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": f"local-tv-candidate-{name}",
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments or {},
        },
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    data = json.loads(raw)
    if data.get("error"):
        raise RuntimeError(data["error"])
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        text = content[0].get("text") or ""
    else:
        text = json.dumps(result, ensure_ascii=False)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def safe_search(description, pattern, text):
    try:
        return re.search(pattern, text, re.MULTILINE)
    except re.error as exc:
        print(f"FAIL: invalid regex for {description}; pattern={pattern!r}; error={exc}", file=sys.stderr)
        sys.exit(1)

def require(description, pattern, text):
    if not safe_search(description, pattern, text):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        print(f"FAIL_PAYLOAD: {compact(text, 1200)}", file=sys.stderr)
        sys.exit(1)

def field(pattern, text, default="N/A"):
    match = safe_search(f"field {pattern}", pattern, text)
    return match.group(1).strip() if match else default

def to_int(value):
    try:
        return int(str(value).strip())
    except Exception:
        return None

def compact(text, limit=900):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

print("[local-tradingview-candidate] read-only server-local MCP smoke")
print("scope=READ_ONLY; calls previewScoreBuyTradingViewOrders, runScoreBuyTradingViewParityBacktest, and runScoreBuyTradingViewBtcBaseBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.")
print(f"url={url} strategyId={strategy_id} symbol={symbol} intervalCode={interval_code} days={days} source={source}")

args = {
    "strategyId": strategy_id,
    "symbol": symbol,
    "intervalCode": interval_code,
    "days": days,
    "source": source,
    "limit": 20,
}
preview = call_tool("previewScoreBuyTradingViewOrders", args)
backtest_args = dict(args)
backtest_args["feeRate"] = 0.001
backtest_args["limit"] = 10
backtest = call_tool("runScoreBuyTradingViewParityBacktest", backtest_args)
btc_base_args = dict(args)
btc_base_args["baseBuyNotionalUsdt"] = 10.0
btc_base_args["maxBaseExposureUsdt"] = 250.0
btc_base_args["takeProfitReducePct"] = 0.0
btc_base_args["takeProfitReduceFraction"] = 0.0
btc_base_args["emergencyDrawdownPct"] = 0.12
btc_base_args["emergencyReduceFraction"] = 0.0
btc_base_args["feeRate"] = 0.001
btc_base_args["limit"] = 10
btc_base = call_tool("runScoreBuyTradingViewBtcBaseBacktest", btc_base_args)

require("preview heading", r"SCORE_BUY TradingView order-intent preview", preview)
require("preview replay history marker", r"replayHistoryComplete=(?:true|false)", preview)
require("backtest heading", r"SCORE_BUY TradingView parity backtest", backtest)
require("backtest non-persistence marker", r"bt_backtest_result", backtest)
require("BTC_BASE shadow heading", r"SCORE_BUY TradingView BTC_BASE shadow backtest", btc_base)
require("BTC_BASE read-only boundary", r"boundary=READ_ONLY", btc_base)
require("BTC_BASE non-authorization marker", r"notAuthorization=read-only BTC_BASE shadow report only", btc_base)

data_end = field(r"dataEnd=([0-9T:\-]+)", preview)
data_close = field(r"dataClose=([0-9T:\-]+)", preview)
coverage = field(r"coverage=([^ \r\n]+)", preview)
coverage_warning = field(r"coverageWarning=([^ \r\n]+)", preview)
trailing_gap_hours = field(r"trailingGapHours=([0-9]+)", preview)
trailing_close_gap_hours = field(r"trailingCloseGapHours=([0-9]+)", preview)
freshness_status = field(r"freshnessStatus=([^ \r\n]+)", preview)
replay_history_required = field(r"replayHistoryRequired=([^ \r\n]+)", preview)
replay_history_complete = field(r"replayHistoryComplete=([^ \r\n]+)", preview)
replay_start = field(r"replayStart=([^ \r\n]+)", preview)
replay_data_start = field(r"replayDataStart=([^ \r\n]+)", preview)
replay_data_end = field(r"replayDataEnd=([^ \r\n]+)", preview)
order_bars = field(r"orderBars=([0-9]+)", preview)
order_intents = field(r"orderIntents=([0-9]+)", preview)
first_order_at = field(r"firstOrderAt=([^ \r\n]+)", preview)
last_order_at = field(r"lastOrderAt=([^ \r\n]+)", preview)
final_mark = field(r"finalMark=([0-9T:\-]+)", backtest)
net_pnl = field(r"netPnl:\s*([-+0-9.]+) USDT", backtest)
total_return = field(r"totalReturn:\s*([-+0-9.]+%)", backtest)

order_intent_count = to_int(order_intents) or 0
if replay_history_complete != "true":
    current_status = "INCOMPLETE_REPLAY_HISTORY"
elif order_intent_count <= 0:
    current_status = "NO_TRADINGVIEW_ORDER_INTENTS"
elif last_order_at == data_end and data_end != "N/A":
    current_status = "HAS_CURRENT_BUY_CANDIDATE"
else:
    current_status = "NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS"

primary = read_env_key("TRADING_SIGNAL_SOURCE_PRIMARY", "TRADINGVIEW").strip().upper().replace("-", "_")
local_enabled = bool_env("TRADINGVIEW_LOCAL_ENABLED", False)
max_signal_age_hours = read_env_key("TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS", "72")
mode = read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MODE", "LEGACY").strip().upper().replace("-", "_")
legacy_execution_enabled = bool_env("TRADINGVIEW_LOCAL_EXECUTION_ENABLED", False)
legacy_execution_dry_run = bool_env("TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN", True)
legacy_live_order_enabled = bool_env("TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED", False)
oco_poller_enabled = bool_env("TRADING_OCO_POLLER_ENABLED", False)
position_exit_manager_enabled = bool_env("POSITION_EXIT_MANAGER_ENABLED", False)

if mode == "OFF":
    effective_execution_enabled = False
    effective_execution_dry_run = True
    effective_live_order_enabled = False
elif mode == "DRY_RUN":
    effective_execution_enabled = True
    effective_execution_dry_run = True
    effective_live_order_enabled = False
elif mode == "BTC_BASE_DRY_RUN":
    effective_execution_enabled = True
    effective_execution_dry_run = True
    effective_live_order_enabled = False
elif mode == "BTC_BASE_LIVE_MICRO":
    effective_execution_enabled = True
    effective_execution_dry_run = False
    effective_live_order_enabled = True
elif mode == "LIVE_MICRO":
    effective_execution_enabled = True
    effective_execution_dry_run = False
    effective_live_order_enabled = True
else:
    mode = "LEGACY"
    effective_execution_enabled = legacy_execution_enabled
    effective_execution_dry_run = legacy_execution_dry_run
    effective_live_order_enabled = legacy_live_order_enabled

local_evaluator_active = primary == "LOCAL_TRADINGVIEW" and local_enabled
dry_run_armed = local_evaluator_active and effective_execution_enabled and effective_execution_dry_run and not effective_live_order_enabled
btc_base_dry_run_armed = local_evaluator_active and mode == "BTC_BASE_DRY_RUN" and dry_run_armed
btc_base_live_micro_armed = local_evaluator_active and mode == "BTC_BASE_LIVE_MICRO" and effective_execution_enabled and not effective_execution_dry_run and effective_live_order_enabled
live_micro_armed = local_evaluator_active and mode == "LIVE_MICRO" and effective_execution_enabled and not effective_execution_dry_run and effective_live_order_enabled
execution_path_armed = dry_run_armed or btc_base_live_micro_armed or live_micro_armed
oco_lifecycle_tracked = (not live_micro_armed) or oco_poller_enabled
if live_micro_armed and oco_poller_enabled:
    oco_lifecycle_status = "TRACKED_BY_OCO_POLLER"
elif live_micro_armed:
    oco_lifecycle_status = "NOT_TRACKED_OCO_POLLER_DISABLED"
elif btc_base_live_micro_armed:
    oco_lifecycle_status = "NOT_REQUIRED_BTC_BASE_LIVE_MICRO_NO_OCO"
else:
    oco_lifecycle_status = "NOT_REQUIRED_UNLESS_LIVE_MICRO_ARMED"

allowed_symbols = read_env_key("TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS", "BTCUSDT")
allowed_intervals = read_env_key("TRADINGVIEW_LOCAL_ALLOWED_INTERVALS", "1d")
allowed_sources = read_env_key("TRADINGVIEW_LOCAL_ALLOWED_SOURCES", "")
scope_allowed = is_allowed(symbol, allowed_symbols, normalize_as_symbol=True) and is_allowed(interval_code, allowed_intervals)
source_allowed = is_allowed(source, allowed_sources)
okx_auto_trade_enabled = bool_env("TRADING_OKX_ENABLED", False)
okx_private_credentials_configured = (
    read_env_key("TRADING_OKX_API_KEY", "").strip() != ""
    and read_env_key("TRADING_OKX_SECRET_KEY", "").strip() != ""
    and read_env_key("TRADING_OKX_PASSPHRASE", "").strip() != ""
)
execution_max_orders_per_bar = int_env("TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_BAR", 1)
execution_max_orders_per_day = int_env("TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_DAY", 1)
execution_max_open_positions = int_env("TRADINGVIEW_LOCAL_EXECUTION_MAX_OPEN_POSITIONS", 1)
default_notional = to_decimal(read_env_key("TRADINGVIEW_LOCAL_DEFAULT_NOTIONAL_USDT", "10.0")) or Decimal("10.0")
max_notional = to_decimal(read_env_key("TRADINGVIEW_LOCAL_MAX_NOTIONAL_USDT", "10.0")) or Decimal("10.0")
effective_notional = min(default_notional, max_notional)
exchange_min_notional = Decimal("10.0")
notional_accepted = effective_notional > 0 and effective_notional <= max_notional and effective_notional >= exchange_min_notional
take_profit_pct = to_decimal(read_env_key("TRADINGVIEW_LOCAL_EXECUTION_TAKE_PROFIT_PCT", "0.0300")) or Decimal("0.0300")
stop_loss_pct = to_decimal(read_env_key("TRADINGVIEW_LOCAL_EXECUTION_STOP_LOSS_PCT", "0.1200")) or Decimal("0.1200")
max_signal_age_hours_value = int_env("TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS", 72)
btc_base_max_exposure = to_decimal(read_env_key("TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT", "250.0")) or Decimal("250.0")

order_rows = extract_order_rows(preview)
current_order_rows = [row for row in order_rows if row["bar"] == data_end]
current_bar_order_intent_count = len(current_order_rows)
bar_cap_allows_at_least_one = execution_max_orders_per_bar >= 1 and (
    current_status != "HAS_CURRENT_BUY_CANDIDATE" or current_bar_order_intent_count >= 1
)
current_row = current_order_rows[0] if current_order_rows else None
entry_price = to_decimal(current_row["close"]) if current_row else None
tp_price = None
sl_price = None
if entry_price is not None:
    tp_price = (entry_price * (Decimal("1") + take_profit_pct)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    sl_price = (entry_price * (Decimal("1") - stop_loss_pct)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
oco_plan_valid = (
    entry_price is not None
    and tp_price is not None
    and sl_price is not None
    and entry_price > 0
    and tp_price > entry_price
    and sl_price > 0
    and sl_price < entry_price
)

signal_reference = parse_iso_datetime(data_close if data_close != "N/A" else data_end)
signal_age_hours = None
if signal_reference is not None:
    signal_age_hours = max(0, int((datetime.now(timezone.utc) - signal_reference).total_seconds() // 3600))
signal_age_check_enabled = max_signal_age_hours_value > 0
signal_stale = signal_age_check_enabled and signal_age_hours is not None and signal_age_hours > max_signal_age_hours_value

pre_execution_evidence_status = "OK"
pre_execution_db_error = ""
orders_today = "N/A"
open_same_strategy_symbol = "N/A"
open_exact_position_count = "N/A"
duplicate_bar_live_signal_count = "N/A"
btc_base_open_exposure = "N/A"
try:
    normalized_symbol = normalize_symbol(symbol)
    symbol_expr = "UPPER(REPLACE(REPLACE(REPLACE(SUBSTRING_INDEX(symbol, ':', -1), '-', ''), '/', ''), '_', ''))"
    orders_today = mysql_count(
        "SELECT COUNT(*) FROM bt_live_signal "
        f"WHERE strategy_id = {strategy_id} AND auto_traded = 1 AND created_at >= UTC_DATE()"
    )
    open_same_strategy_symbol = mysql_count(
        "SELECT COUNT(*) FROM bt_live_signal "
        f"WHERE strategy_id = {strategy_id} AND auto_traded = 1 AND exit_time IS NULL "
        f"AND {symbol_expr} = {sql_quote(normalized_symbol)}"
    )
    open_exact_position_count = mysql_count(
        "SELECT COUNT(*) FROM bt_live_signal "
        f"WHERE strategy_id = {strategy_id} AND auto_traded = 1 AND exit_time IS NULL "
        f"AND {symbol_expr} = {sql_quote(normalized_symbol)} "
        f"AND interval_code = {sql_quote(interval_code)} "
        "AND (side IS NULL OR UPPER(side) = 'LONG')"
    )
    btc_base_open_exposure = mysql_scalar(
        "SELECT COALESCE(SUM(COALESCE(actual_entry_price, entry_price) * traded_qty), 0) "
        "FROM bt_live_signal "
        f"WHERE strategy_id = {strategy_id} AND auto_traded = 1 AND exit_time IS NULL "
        f"AND {symbol_expr} = {sql_quote(normalized_symbol)} "
        "AND COALESCE(filter_reason, '') LIKE 'LOCAL_TRADINGVIEW_BTC_BASE:%'"
    )
    duplicate_reference = mysql_datetime(data_end)
    if duplicate_reference is not None:
        duplicate_bar_live_signal_count = mysql_count(
            "SELECT COUNT(*) FROM bt_live_signal "
            f"WHERE strategy_id = {strategy_id} "
            f"AND symbol = {sql_quote(symbol)} "
            f"AND interval_code = {sql_quote(interval_code)} "
            f"AND bar_open_time = {sql_quote(duplicate_reference)} "
            "AND notified_at IS NOT NULL"
        )
except Exception as exc:
    pre_execution_evidence_status = "DB_EVIDENCE_UNAVAILABLE"
    pre_execution_db_error = str(exc)

daily_cap_available = isinstance(orders_today, int) and orders_today < execution_max_orders_per_day
open_position_cap_available = isinstance(open_same_strategy_symbol, int) and open_same_strategy_symbol < execution_max_open_positions
open_exact_position_exists = isinstance(open_exact_position_count, int) and open_exact_position_count > 0
duplicate_bar_exists = isinstance(duplicate_bar_live_signal_count, int) and duplicate_bar_live_signal_count > 0
btc_base_open_exposure_value = to_decimal(btc_base_open_exposure) if btc_base_open_exposure != "N/A" else None
btc_base_exposure_after_order = (
    btc_base_open_exposure_value + effective_notional
    if btc_base_open_exposure_value is not None and effective_notional is not None
    else None
)
btc_base_exposure_cap_available = (
    btc_base_exposure_after_order is not None
    and btc_base_exposure_after_order <= btc_base_max_exposure
)

pre_execution_blockers = []
btc_base_shadow_mode = mode == "BTC_BASE_DRY_RUN"
btc_base_live_mode = mode == "BTC_BASE_LIVE_MICRO"
btc_base_no_oco_mode = btc_base_shadow_mode or btc_base_live_mode
if pre_execution_evidence_status != "OK":
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_PRE_EXECUTION_DB_EVIDENCE_UNAVAILABLE")
if not scope_allowed:
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_SCOPE_NOT_ALLOWLISTED")
if not source_allowed:
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_SOURCE_NOT_ALLOWLISTED")
if not btc_base_shadow_mode and not okx_auto_trade_enabled:
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_OKX_DISABLED")
if not btc_base_shadow_mode and not okx_private_credentials_configured:
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_OKX_PRIVATE_CREDENTIALS_MISSING")
if not btc_base_shadow_mode and not notional_accepted:
    pre_execution_blockers.append("LOCAL_TRADINGVIEW_NOTIONAL_BELOW_MINIMUM")
if pre_execution_evidence_status == "OK" and not btc_base_shadow_mode:
    if not daily_cap_available:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_DAILY_CAP_REACHED")
    if btc_base_live_mode and not btc_base_exposure_cap_available:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_BTC_BASE_EXPOSURE_CAP_REACHED")
    if not btc_base_live_mode and not open_position_cap_available:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_OPEN_POSITION_CAP_REACHED")
    if not btc_base_live_mode and open_exact_position_exists:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_OPEN_POSITION_EXISTS")
if current_status == "HAS_CURRENT_BUY_CANDIDATE":
    if not bar_cap_allows_at_least_one:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_BAR_CAP_REACHED")
    if current_row is None:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_PRE_EXECUTION_CANDIDATE_PRICE_MISSING")
    if signal_stale:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_SIGNAL_STALE")
    if btc_base_live_mode and entry_price is not None and entry_price <= 0:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_INVALID_ENTRY_PRICE")
    if not btc_base_no_oco_mode and not oco_plan_valid:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_INVALID_OCO_PLAN")
    if not btc_base_shadow_mode and duplicate_bar_exists:
        pre_execution_blockers.append("LOCAL_TRADINGVIEW_DUPLICATE_BAR")

if replay_history_complete != "true":
    pre_execution_readiness = "BLOCKED_INCOMPLETE_REPLAY_HISTORY"
elif pre_execution_blockers:
    pre_execution_readiness = "BLOCKED_PRE_EXECUTION_GATES"
elif current_status != "HAS_CURRENT_BUY_CANDIDATE":
    pre_execution_readiness = "WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE"
else:
    pre_execution_readiness = "READY_PRE_EXECUTION_GATES"

blockers = []
if replay_history_complete != "true":
    blockers.append("LOCAL_TRADINGVIEW_REPLAY_HISTORY_INCOMPLETE")
elif current_status != "HAS_CURRENT_BUY_CANDIDATE":
    blockers.append("LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
if not local_evaluator_active:
    blockers.append("LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE")
if mode == "BTC_BASE_LIVE_MICRO":
    if not btc_base_live_micro_armed:
        blockers.append("LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED")
elif mode == "LIVE_MICRO":
    if not live_micro_armed:
        blockers.append("LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED")
    elif not oco_poller_enabled:
        blockers.append("LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED")
elif mode in ("DRY_RUN", "BTC_BASE_DRY_RUN") or require_dry_run_armed:
    if not dry_run_armed:
        blockers.append("LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED")
elif not execution_path_armed:
    blockers.append("LOCAL_TRADINGVIEW_EXECUTION_NOT_ARMED")
if require_dry_run_armed and not dry_run_armed and "LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED" not in blockers:
    blockers.append("LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED")
if require_btc_base_live_micro_armed and not btc_base_live_micro_armed and "LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED" not in blockers:
    blockers.append("LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_NOT_ARMED")
if coverage not in ("OK", "WARN"):
    blockers.append("LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK")
for blocker in pre_execution_blockers:
    if blocker not in blockers:
        blockers.append(blocker)

print("")
print("Current Candidate:")
print(f"  currentCandidateStatus={current_status}")
print(f"  dataEnd={data_end}")
print(f"  dataClose={data_close}")
print(f"  lastOrderAt={last_order_at}")
print(f"  firstOrderAt={first_order_at}")
print(f"  orderBars={order_bars}")
print(f"  orderIntents={order_intents}")
print(f"  coverage={coverage}")
print(f"  trailingGapHours={trailing_gap_hours}")
print(f"  trailingCloseGapHours={trailing_close_gap_hours}")
print(f"  freshnessStatus={freshness_status}")
print(f"  coverageWarning={coverage_warning}")
print(f"  replayHistoryRequired={replay_history_required}")
print(f"  replayHistoryComplete={replay_history_complete}")
print(f"  replayStart={replay_start}")
print(f"  replayDataStart={replay_data_start}")
print(f"  replayDataEnd={replay_data_end}")

print("")
print("Local TradingView Execution Guards:")
print(f"  primary={primary}")
print(f"  localEnabled={str(local_enabled).lower()}")
print(f"  executionMode={mode}")
print(f"  maxSignalAgeHours={max_signal_age_hours}")
print(f"  effectiveExecutionEnabled={str(effective_execution_enabled).lower()}")
print(f"  effectiveExecutionDryRun={str(effective_execution_dry_run).lower()}")
print(f"  effectiveLiveOrderEnabled={str(effective_live_order_enabled).lower()}")
print(f"  localTradingViewEvaluatorActive={str(local_evaluator_active).lower()}")
print(f"  localTradingViewExecutionDryRunArmed={str(dry_run_armed).lower()}")
print(f"  localTradingViewBtcBaseDryRunArmed={str(btc_base_dry_run_armed).lower()}")
print(f"  localTradingViewBtcBaseLiveMicroArmed={str(btc_base_live_micro_armed).lower()}")
print(f"  localTradingViewLiveMicroArmed={str(live_micro_armed).lower()}")
print(f"  localTradingViewExecutionPathArmed={str(execution_path_armed).lower()}")
print(f"  tradingOcoPollerEnabled={str(oco_poller_enabled).lower()}")
print(f"  positionExitManagerEnabled={str(position_exit_manager_enabled).lower()}")
print(f"  localTradingViewOcoLifecycleTracked={str(oco_lifecycle_tracked).lower()}")
print(f"  localTradingViewOcoLifecycleStatus={oco_lifecycle_status}")
print("  orderSentAllowed=false")
print("  liveOrderMutationAllowed=false")

print("")
print("Local TradingView Pre-Execution Gates:")
print(f"  localTradingViewPreExecutionEvidenceStatus={pre_execution_evidence_status}")
print(f"  localTradingViewPreExecutionDbError={compact(pre_execution_db_error, 240)}")
print(f"  localTradingViewAllowedSymbols={allowed_symbols}")
print(f"  localTradingViewAllowedIntervals={allowed_intervals}")
print(f"  localTradingViewAllowedSources={allowed_sources}")
print(f"  localTradingViewScopeAllowed={format_bool(scope_allowed)}")
print(f"  localTradingViewSourceAllowed={format_bool(source_allowed)}")
print(f"  localTradingViewOkxAutoTradeEnabled={format_bool(okx_auto_trade_enabled)}")
print(f"  localTradingViewOkxPrivateCredentialsConfigured={format_bool(okx_private_credentials_configured)}")
print(f"  localTradingViewDefaultNotionalUsdt={decimal_text(default_notional)}")
print(f"  localTradingViewMaxNotionalUsdt={decimal_text(max_notional)}")
print(f"  localTradingViewEffectiveNotionalUsdt={decimal_text(effective_notional)}")
print(f"  localTradingViewExchangeMinNotionalUsdt={decimal_text(exchange_min_notional)}")
print(f"  localTradingViewNotionalAccepted={format_bool(notional_accepted)}")
print(f"  localTradingViewTakeProfitPct={decimal_text(take_profit_pct)}")
print(f"  localTradingViewStopLossPct={decimal_text(stop_loss_pct)}")
print(f"  localTradingViewExecutionEntryPrice={decimal_text(entry_price)}")
print(f"  localTradingViewExecutionTpPrice={decimal_text(tp_price)}")
print(f"  localTradingViewExecutionSlPrice={decimal_text(sl_price)}")
print(f"  localTradingViewOcoPlanValid={format_bool(oco_plan_valid) if current_status == 'HAS_CURRENT_BUY_CANDIDATE' else 'N/A'}")
print(f"  localTradingViewSignalAgeCheckEnabled={format_bool(signal_age_check_enabled)}")
print(f"  localTradingViewSignalAgeHours={signal_age_hours if signal_age_hours is not None else 'N/A'}")
print(f"  localTradingViewSignalStale={format_bool(signal_stale)}")
print(f"  localTradingViewOrdersToday={orders_today}")
print(f"  localTradingViewMaxOrdersPerDay={execution_max_orders_per_day}")
print(f"  localTradingViewDailyCapAvailable={format_bool(daily_cap_available)}")
print(f"  localTradingViewOpenSameStrategySymbol={open_same_strategy_symbol}")
print(f"  localTradingViewMaxOpenPositions={execution_max_open_positions}")
print(f"  localTradingViewOpenPositionCapAvailable={format_bool(open_position_cap_available)}")
print(f"  localTradingViewOpenSameStrategySymbolIntervalLong={open_exact_position_count}")
print(f"  localTradingViewOpenExactPositionExists={format_bool(open_exact_position_exists)}")
print(f"  localTradingViewBtcBaseMaxExposureUsdt={decimal_text(btc_base_max_exposure)}")
print(f"  localTradingViewBtcBaseOpenExposureUsdt={decimal_text(btc_base_open_exposure_value)}")
print(f"  localTradingViewBtcBaseExposureAfterOrderUsdt={decimal_text(btc_base_exposure_after_order)}")
print(f"  localTradingViewBtcBaseExposureCapAvailable={format_bool(btc_base_exposure_cap_available)}")
print(f"  localTradingViewDuplicateBarLiveSignalCount={duplicate_bar_live_signal_count}")
print(f"  localTradingViewDuplicateBarExists={format_bool(duplicate_bar_exists)}")
print(f"  localTradingViewCurrentBarOrderIntentCount={current_bar_order_intent_count}")
print(f"  localTradingViewMaxOrdersPerBar={execution_max_orders_per_bar}")
print(f"  localTradingViewBarCapAllowsAtLeastOne={format_bool(bar_cap_allows_at_least_one)}")
print(f"  localTradingViewPreExecutionReadiness={pre_execution_readiness}")
print("  local_tradingview_pre_execution_blockers=" + json.dumps(pre_execution_blockers))

print("")
print("Parity Backtest Summary:")
print(f"  finalMark={final_mark}")
print(f"  netPnlUsdt={net_pnl}")
print(f"  totalReturn={total_return}")

print("")
print("BTC_BASE Shadow Summary:")
print("  " + compact(btc_base))

print("")
print("Blocker Classification:")
print("  local_tradingview_blockers=" + json.dumps(blockers))
if replay_history_complete != "true":
    readiness = "BLOCKED_LOCAL_TRADINGVIEW_REPLAY_HISTORY_INCOMPLETE"
elif pre_execution_blockers:
    readiness = "BLOCKED_LOCAL_TRADINGVIEW_PRE_EXECUTION_GATES"
elif current_status != "HAS_CURRENT_BUY_CANDIDATE":
    readiness = "WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE"
elif btc_base_dry_run_armed:
    readiness = "READY_FOR_LOCAL_TRADINGVIEW_BTC_BASE_DRY_RUN_OBSERVATION_NOT_LIVE"
elif dry_run_armed:
    readiness = "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_OBSERVATION_NOT_LIVE"
elif btc_base_live_micro_armed:
    readiness = "READY_FOR_LOCAL_TRADINGVIEW_BTC_BASE_LIVE_MICRO_ARMED_REVIEW_NOT_MUTATION"
elif live_micro_armed and not oco_poller_enabled:
    readiness = "BLOCKED_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED"
elif live_micro_armed:
    readiness = "READY_FOR_LOCAL_TRADINGVIEW_LIVE_MICRO_ARMED_REVIEW_NOT_MUTATION"
else:
    readiness = "BLOCKED_LOCAL_TRADINGVIEW_EXECUTION_NOT_ARMED"
print(f"  localTradingViewReadiness={readiness}")
if replay_history_complete != "true":
    next_action = "Keep live disabled; complete the authorized Binance replay-history import before using parity BUY evidence."
elif pre_execution_blockers:
    next_action = "Fix LOCAL_TRADINGVIEW pre-execution blockers before treating the next parity BUY as executable."
elif current_status != "HAS_CURRENT_BUY_CANDIDATE":
    next_action = "Wait for the latest closed bar to emit a TradingView parity BUY intent, then rerun this smoke before any live plan."
elif btc_base_dry_run_armed:
    next_action = "Review BTC_BASE_DRY_RUN shadow accumulation evidence only; this smoke is not live approval."
elif dry_run_armed:
    next_action = "Review DRY_RUN evidence only; this smoke is not live approval."
elif btc_base_live_micro_armed:
    next_action = "Review BTC_BASE_LIVE_MICRO armed evidence; OCO is intentionally not required for this mode."
elif live_micro_armed and not oco_poller_enabled:
    next_action = "Do not rely on LIVE_MICRO buys until OCO close detection is reviewed and separately authorized."
elif live_micro_armed:
    next_action = "Review LIVE_MICRO armed evidence only; this smoke is not live approval."
else:
    next_action = "Fix LOCAL_TRADINGVIEW execution mode/flags before treating the candidate as executable."
print("  nextAction=" + next_action)

print("")
print("Preview Sample:")
print("  " + compact(preview))

if require_current and current_status != "HAS_CURRENT_BUY_CANDIDATE":
    print("FAIL: current TradingView parity BUY candidate is required but not present.", file=sys.stderr)
    sys.exit(2)
if require_dry_run_armed and not dry_run_armed:
    print("FAIL: Local TradingView dry-run execution receipt path is required but not armed.", file=sys.stderr)
    sys.exit(3)
if require_btc_base_live_micro_armed and not btc_base_live_micro_armed:
    print("FAIL: Local TradingView BTC_BASE_LIVE_MICRO execution path is required but not armed.", file=sys.stderr)
    sys.exit(6)
if require_live_micro_armed and not live_micro_armed:
    print("FAIL: Local TradingView LIVE_MICRO execution path is required but not armed.", file=sys.stderr)
    sys.exit(4)
if require_oco_lifecycle_tracked and not oco_lifecycle_tracked:
    print("FAIL: Local TradingView OCO lifecycle close detection is required but not tracked.", file=sys.stderr)
    sys.exit(5)

print("[local-tradingview-candidate] OK read-only check complete")
PY
'@

$remoteScript = $remoteScriptTemplate
$remoteScript = $remoteScript.Replace("__APP_DIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENV_FILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__STRATEGY_ID__", [string]$StrategyId)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__INTERVAL_CODE__", $IntervalCode)
$remoteScript = $remoteScript.Replace("__DAYS__", [string]$Days)
$remoteScript = $remoteScript.Replace("__SOURCE__", $Source)
$remoteScript = $remoteScript.Replace("__REQUIRE_CURRENT__", $RequireCurrentCandidate.IsPresent.ToString())
$remoteScript = $remoteScript.Replace("__REQUIRE_DRY_RUN_ARMED__", $RequireDryRunArmed.IsPresent.ToString())
$remoteScript = $remoteScript.Replace("__REQUIRE_BTC_BASE_LIVE_MICRO_ARMED__", $RequireBtcBaseLiveMicroArmed.IsPresent.ToString())
$remoteScript = $remoteScript.Replace("__REQUIRE_LIVE_MICRO_ARMED__", $RequireLiveMicroArmed.IsPresent.ToString())
$remoteScript = $remoteScript.Replace("__REQUIRE_OCO_LIFECYCLE_TRACKED__", $RequireOcoLifecycleTracked.IsPresent.ToString())

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "local TradingView candidate SSH smoke failed with exit code $LASTEXITCODE"
}
