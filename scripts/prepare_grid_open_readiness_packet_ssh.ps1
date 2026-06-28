param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [decimal]$CandidateHalfWidthPct = 0,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

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
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) {
    throw "LookbackHours must be between 24 and 720."
}
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) {
    throw "CandidateLookbackHours must be between 72 and 720."
}
if ($GridCount -lt 4 -or $GridCount -gt 24) {
    throw "GridCount must be between 4 and 24."
}
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) {
    throw "PerLevelUsdt must be between 5 and 1000."
}
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) {
    throw "StopOutPct must be between 1 and 20."
}
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) {
    throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
MCP_URL="http://127.0.0.1:${PORT}/api/mcp"
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export MCP_URL MCP_KEY SYMBOL='__SYMBOL__' LOOKBACK_HOURS='__LOOKBACK_HOURS__' CANDIDATE_LOOKBACK_HOURS='__CANDIDATE_LOOKBACK_HOURS__' GRID_COUNT='__GRID_COUNT__' PER_LEVEL_USDT='__PER_LEVEL_USDT__' STOP_OUT_PCT='__STOP_OUT_PCT__' CANDIDATE_HALF_WIDTH_PCT='__CANDIDATE_HALF_WIDTH_PCT__' REQUIRE_READY='__REQUIRE_READY__' ENV_FILE='__ENVFILE__'

python3 - <<'PY'
import csv
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

mcp_url = os.environ["MCP_URL"]
mcp_key = os.environ["MCP_KEY"]
symbol = os.environ["SYMBOL"]
lookback_hours = int(os.environ["LOOKBACK_HOURS"])
candidate_lookback_hours = int(os.environ["CANDIDATE_LOOKBACK_HOURS"])
grid_count = int(os.environ["GRID_COUNT"])
per_level_usdt = float(os.environ["PER_LEVEL_USDT"])
stop_out_pct = float(os.environ["STOP_OUT_PCT"])
candidate_half_width_pct = float(os.environ["CANDIDATE_HALF_WIDTH_PCT"])
require_ready = os.environ.get("REQUIRE_READY", "false").lower() == "true"
env_file = os.environ["ENV_FILE"]

def call_tool(name, arguments=None):
    payload = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments or {}
        }
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        mcp_url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + mcp_key
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{name} HTTP {exc.code}: {body[:500]}")
    message = json.loads(body)
    if "error" in message:
        raise RuntimeError(f"{name} JSON-RPC error: {message['error']}")
    result = message.get("result", {})
    content = result.get("content", [])
    texts = []
    for item in content:
        if isinstance(item, dict) and item.get("type") == "text":
            texts.append(str(item.get("text", "")))
    text = "\n".join(texts)
    stripped = text.strip()
    if stripped.startswith('"') and stripped.endswith('"'):
        try:
            decoded = json.loads(stripped)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def read_env():
    values = {}
    with open(env_file, "r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip().strip('"').strip("'")
            values[key.strip()] = value
    return values

def parse_mysql_jdbc(url):
    if not url.startswith("jdbc:mysql://"):
        raise RuntimeError("SPRING_DATASOURCE_URL must be a jdbc:mysql URL for read-only candidate replay")
    without_prefix = url[len("jdbc:mysql://"):]
    without_query = without_prefix.split("?", 1)[0]
    host_port, database = without_query.split("/", 1)
    if database != "agora_market":
        raise RuntimeError("refusing to query unexpected database: " + database)
    if ":" in host_port:
        host, port = host_port.rsplit(":", 1)
    else:
        host, port = host_port, "3306"
    if not port.isdigit():
        raise RuntimeError("invalid database port in SPRING_DATASOURCE_URL: " + port)
    return host, port, database

def run_mysql(values, sql):
    required = ["SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"]
    missing = [key for key in required if not values.get(key)]
    if missing:
        raise RuntimeError("missing datasource keys for read-only candidate replay: " + ",".join(missing))
    host, port, database = parse_mysql_jdbc(values["SPRING_DATASOURCE_URL"])
    env = os.environ.copy()
    env["MYSQL_PWD"] = values["SPRING_DATASOURCE_PASSWORD"]
    cmd = [
        "mysql",
        "--batch",
        "--raw",
        "--skip-column-names",
        "-h", host,
        "-P", port,
        "-u", values["SPRING_DATASOURCE_USERNAME"],
        database,
        "-e", sql,
    ]
    proc = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def sql_escape(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def env_bool(values, key):
    value = values.get(key, "")
    if value == "":
        return "MISSING"
    return "true" if value.lower() == "true" else "false"

def env_present_any(values, keys):
    for key in keys:
        if values.get(key, ""):
            return "SET"
    return "EMPTY"

def extract_first(pattern, text, default=""):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def unique_append(items, value):
    if value and value not in items:
        items.append(value)

def to_float(value):
    try:
        return float(str(value))
    except Exception:
        return None

def pct_change(first, last):
    if first is None or last is None or first == 0:
        return None
    return (last - first) / first * 100.0

def trend_direction(value):
    if value is None:
        return "INSUFFICIENT_DATA"
    if value >= 3.0:
        return "UP_STRONG"
    if value >= 1.0:
        return "UP"
    if value <= -3.0:
        return "DOWN_STRONG"
    if value <= -1.0:
        return "DOWN"
    return "SIDEWAYS"

def trend_gate_review(trend_value, trend_pct_value, atr_pct_value, candidate):
    status = "CLEAR_TREND_REGIME"
    clear_condition = "trend=SIDEWAYS with complete candidate replay evidence"
    required_evidence = []
    if trend_value in ("DOWN_STRONG", "UP_STRONG", "DOWN", "UP"):
        status = "BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE"
        required_evidence = [
            "trend=SIDEWAYS on the open-readiness lookback, or",
            "separate written operator trend-regime override with capped capital and stop conditions",
        ]
    elif trend_value in ("UNKNOWN", "INSUFFICIENT_DATA") or candidate.get("candidatePlanStatus") == "INSUFFICIENT_KLINE_REPLAY_BARS":
        status = "BLOCKED_TREND_EVIDENCE_INSUFFICIENT"
        required_evidence = ["at least 72 replayable okx 1h md_kline rows and current grid-trend MCP evidence"]
    return {
        "status": status,
        "trend": trend_value,
        "trendPct": trend_pct_value,
        "atrPct": atr_pct_value,
        "clearCondition": clear_condition,
        "requiredEvidence": required_evidence,
        "operatorOverrideAllowed": status == "BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE",
        "notAuthorization": "trend gate review is read-only; override evidence is not grid/live/order approval",
    }

def event_risk_gate_review(risk_level):
    status = "CLEAR_EVENT_RISK_R0" if risk_level == "R0" else "BLOCKED_EVENT_RISK_NOT_R0"
    return {
        "status": status,
        "riskLevel": risk_level,
        "clearCondition": "riskLevel=R0 from getEventRiskControlStatus",
        "requiredEvidence": [] if risk_level == "R0" else [
            "event-risk returns R0 on deployed server-local MCP, or",
            "separate written event-risk operating override for grid open review",
        ],
        "operatorOverrideAllowed": risk_level != "R0",
        "notAuthorization": "event-risk gate review is read-only; override evidence is not grid/live/order approval",
    }

def okx_gate_review(flags):
    enabled = flags.get("TRADING_OKX_ENABLED") == "true"
    credentials_ready = all(flags.get(key) == "SET" for key in (
        "TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"))
    status = "CLEAR_OKX_ENABLED" if enabled and credentials_ready else "BLOCKED_OKX_ENV_AUTHORIZATION_REQUIRED"
    required = []
    if not enabled:
        required.append("separate operator authorization for TRADING_OKX_ENABLED=true")
    if not credentials_ready:
        required.append("TRADING_OKX_API_KEY/SECRET_KEY/PASSPHRASE present")
    return {
        "status": status,
        "tradingOkxEnabled": flags.get("TRADING_OKX_ENABLED"),
        "credentialsReady": credentials_ready,
        "clearCondition": "TRADING_OKX_ENABLED=true with OKX credentials present, after trend/event-risk gates are reviewable",
        "requiredEvidence": required,
        "operatorAuthorizationRequired": not enabled,
        "notAuthorization": "OKX gate review is read-only; does not authorize changing production env or placing orders",
    }

def build_candidate_plan(values, sym):
    sql = f"""
SELECT DATE_FORMAT(open_time, '%Y-%m-%dT%H:%i:%s'), open_price, high_price, low_price, close_price
FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE symbol = '{sql_escape(sym)}'
  AND interval_code = '1h'
  AND source = 'okx'
ORDER BY open_time DESC
LIMIT {candidate_lookback_hours}
"""
    rows = run_mysql(values, sql)
    bars = []
    for row in reversed(rows):
        if len(row) < 5:
            continue
        close = to_float(row[4])
        high = to_float(row[2])
        low = to_float(row[3])
        if close is None or high is None or low is None or close <= 0:
            continue
        bars.append({"time": row[0], "high": high, "low": low, "close": close})

    if len(bars) < 72:
        return {
            "candidatePlanComplete": False,
            "candidatePlanStatus": "INSUFFICIENT_KLINE_REPLAY_BARS",
            "replayRows": len(bars),
            "missingFields": ["at least 72 okx 1h md_kline rows"],
        }

    current = bars[-1]["close"]
    trend_pct = pct_change(bars[0]["close"], bars[-1]["close"])
    direction = trend_direction(trend_pct)
    recent = bars[-24:]
    atr_pct = sum(((bar["high"] - bar["low"]) / bar["close"] * 100.0) for bar in recent) / len(recent)
    half_width_pct = max(2.5, min(8.0, atr_pct * 6.0))
    if direction in ("UP_STRONG", "DOWN_STRONG"):
        half_width_pct = max(half_width_pct, min(10.0, abs(trend_pct or 0.0) * 1.25))
    if candidate_half_width_pct > 0:
        half_width_pct = candidate_half_width_pct
    lower = current * (1.0 - half_width_pct / 100.0)
    upper = current * (1.0 + half_width_pct / 100.0)
    step = (upper - lower) / max(1, grid_count - 1)
    step_pct = step / current * 100.0
    stop_low = lower * (1.0 - stop_out_pct / 100.0)
    stop_high = upper * (1.0 + stop_out_pct / 100.0)
    inside = sum(1 for bar in bars if bar["low"] >= lower and bar["high"] <= upper)
    touched = sum(1 for bar in bars if bar["high"] >= lower and bar["low"] <= upper)
    stop_breaks = sum(1 for bar in bars if bar["low"] < stop_low or bar["high"] > stop_high)
    inside_pct = inside / len(bars) * 100.0
    touched_pct = touched / len(bars) * 100.0
    replay_score = max(0.0, min(100.0, inside_pct - stop_breaks * 2.0 - (20.0 if direction in ("UP_STRONG", "DOWN_STRONG") else 0.0)))
    status = "GRID_CANDIDATE_PLAN_READY_NOT_OPEN_APPROVAL"
    if direction in ("UP_STRONG", "DOWN_STRONG"):
        status = "GRID_CANDIDATE_PLAN_BLOCKED_BY_TREND_REGIME"
    if stop_breaks > 0:
        status = "GRID_CANDIDATE_PLAN_REVIEW_STOP_BREAKS"

    missing = []
    if per_level_usdt < 5.0:
        missing.append("perLevelUsdt >= exchange min notional")
    return {
        "candidatePlanComplete": len(missing) == 0,
        "candidatePlanStatus": status,
        "candidateSymbol": sym,
        "replayRows": len(bars),
        "replayStart": bars[0]["time"],
        "replayEnd": bars[-1]["time"],
        "entryReferencePrice": round(current, 2),
        "candidateLower": round(lower, 2),
        "candidateUpper": round(upper, 2),
        "gridCount": grid_count,
        "perLevelUsdt": round(per_level_usdt, 2),
        "candidateCapitalUsdt": round(per_level_usdt * grid_count, 2),
        "candidateHalfWidthPct": round(half_width_pct, 4),
        "candidateHalfWidthSource": "explicit" if candidate_half_width_pct > 0 else "auto_atr_trend",
        "stepPct": round(step_pct, 4),
        "stopOutPct": round(stop_out_pct, 4),
        "stopLow": round(stop_low, 2),
        "stopHigh": round(stop_high, 2),
        "trend": direction,
        "trendPct": round(trend_pct, 4) if trend_pct is not None else None,
        "atrPct": round(atr_pct, 4),
        "insidePct": round(inside_pct, 2),
        "touchedPct": round(touched_pct, 2),
        "stopBreakRows": stop_breaks,
        "replayScore": round(replay_score, 2),
        "missingFields": missing,
        "notAuthorization": "read-only candidate plan only; not createGrid input authorization and not grid/live/order approval",
    }

env_values = read_env()
candidate_plan_error = ""
try:
    candidate_plan = build_candidate_plan(env_values, symbol)
except Exception as exc:
    candidate_plan = {
        "candidatePlanComplete": False,
        "candidatePlanStatus": "GRID_CANDIDATE_PLAN_UNAVAILABLE",
        "missingFields": ["read-only md_kline replay query completed"],
    }
    candidate_plan_error = str(exc)

grid_trend = call_tool("getGridTrendAdjustmentReview", {"symbol": symbol, "lookbackHours": lookback_hours})
grid_list = call_tool("listGrids", {})
alignment = call_tool("getGridPriceAlignment", {})
exposure = call_tool("getCurrentExposure", {})
event_risk = call_tool("getEventRiskControlStatus", {"symbol": symbol})

required_markers = [
    "boundary=READ_ONLY",
    "mutationAllowed=false",
    "orderAllowed=false",
    "gridMutationAllowed=false",
    "schedulerChangeAllowed=false",
    "telegramSendAllowed=false",
]
missing_markers = [marker for marker in required_markers if marker not in grid_trend]

recommendation = extract_first(r"recommendation=([A-Z0-9_:-]+)", grid_trend, "UNKNOWN")
trend = extract_first(r"trend=([A-Z0-9_:-]+)", grid_trend, "UNKNOWN")
trend_pct = extract_first(r"trendPct=([-+0-9.]+%?)", grid_trend, "UNKNOWN")
atr_pct = extract_first(r"atrPct=([-+0-9.]+%?)", grid_trend, "UNKNOWN")
event_risk_level = extract_first(r"riskLevel=([A-Z0-9_:-]+)", event_risk, "UNKNOWN")

def count_unique_grid_status(text, status):
    ids = set()
    for line in text.splitlines():
        if status not in line:
            continue
        match = re.search(r"#(\d+)", line)
        if match:
            ids.add(match.group(1))
    return len(ids)

def classify_sell_failed_lines(text):
    dust = []
    material = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("SELL_FAILED L"):
            continue
        if "class=dust_failure" in stripped:
            dust.append(stripped)
        else:
            material.append(stripped)
    return dust, material

active_grid_count = count_unique_grid_status(grid_list, "ACTIVE")
paused_grid_count = count_unique_grid_status(grid_list, "PAUSED")
closed_grid_count = count_unique_grid_status(grid_list, "CLOSED")
dust_sell_failed_lines, material_sell_failed_lines = classify_sell_failed_lines(grid_list)

grid_flags = {
    "TRADING_GRID_ENABLED": env_bool(env_values, "TRADING_GRID_ENABLED"),
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED": env_bool(env_values, "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED"),
    "GRID_RECOVERY_ENABLED": env_bool(env_values, "GRID_RECOVERY_ENABLED"),
    "TRADING_OKX_ENABLED": env_bool(env_values, "TRADING_OKX_ENABLED"),
    "OKX_EARN_TOPUP_ENABLED": env_bool(env_values, "OKX_EARN_TOPUP_ENABLED"),
    "TRADING_OKX_API_KEY": env_present_any(env_values, ["TRADING_OKX_API_KEY", "OKX_API_KEY"]),
    "TRADING_OKX_SECRET_KEY": env_present_any(env_values, ["TRADING_OKX_SECRET_KEY", "OKX_SECRET_KEY"]),
    "TRADING_OKX_PASSPHRASE": env_present_any(env_values, ["TRADING_OKX_PASSPHRASE", "OKX_PASSPHRASE"]),
    "TELEGRAM_BOT_TOKEN": env_present_any(env_values, ["TELEGRAM_BOT_TOKEN"]),
}

blockers = []
warnings = []
required_evidence = []

for marker in missing_markers:
    unique_append(blockers, "GRID_TREND_READ_ONLY_MARKER_MISSING")
    unique_append(required_evidence, marker)

if active_grid_count > 0:
    unique_append(blockers, "ACTIVE_GRID_EXISTS_REVIEW_BEFORE_OPENING_NEW_GRID")
if paused_grid_count > 0:
    unique_append(warnings, "PAUSED_GRID_EXISTS_REVIEW_BEFORE_OPENING_NEW_GRID")
if material_sell_failed_lines:
    unique_append(blockers, "HISTORICAL_GRID_SELL_FAILED_RECONCILIATION_REQUIRED")
    unique_append(required_evidence, "material historical SELL_FAILED levels reconciled")
elif dust_sell_failed_lines:
    unique_append(warnings, "HISTORICAL_GRID_DUST_SELL_FAILED_REVIEW_NOT_BLOCKING")
if recommendation == "NO_ACTION_NO_ACTIVE_GRID":
    if not candidate_plan.get("candidatePlanComplete", False):
        unique_append(blockers, "NO_REPLAYABLE_GRID_CANDIDATE_PLAN")
        unique_append(required_evidence, "explicit grid candidate range, spacing, capital, stop, and trend-regime rationale")
if trend in ("DOWN", "DOWN_STRONG", "UP_STRONG"):
    unique_append(blockers, "GRID_UNFAVORABLE_TREND_REGIME_" + trend)
    unique_append(required_evidence, "sideways or explicitly reviewed trend-regime evidence")
if event_risk_level != "R0":
    unique_append(blockers, "EVENT_RISK_NOT_R0")
    unique_append(required_evidence, "event-risk R0 or separate operator risk override")
if grid_flags["TRADING_GRID_ENABLED"] == "true":
    unique_append(blockers, "TRADING_GRID_ENABLED_ALREADY_TRUE")
if grid_flags["TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED"] == "true":
    unique_append(blockers, "GRID_AUTO_REBALANCE_SCHEDULER_ALREADY_TRUE")
if grid_flags["GRID_RECOVERY_ENABLED"] == "true":
    unique_append(warnings, "GRID_RECOVERY_ENABLED_REVIEW_SEPARATELY")
if grid_flags["TRADING_OKX_ENABLED"] != "true":
    unique_append(blockers, "TRADING_OKX_ENABLED_FALSE")
    unique_append(required_evidence, "separate operator authorization for TRADING_OKX_ENABLED=true before any grid order path")
for key in ("TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"):
    if grid_flags[key] != "SET":
        unique_append(blockers, "OKX_KEYS_MISSING")
if grid_flags["OKX_EARN_TOPUP_ENABLED"] == "true":
    unique_append(blockers, "OKX_EARN_TOPUP_ENABLED_MUST_REMAIN_FALSE_FOR_GRID_OPEN_REVIEW")
if grid_flags["TELEGRAM_BOT_TOKEN"] != "SET":
    unique_append(warnings, "TELEGRAM_ALERTING_UNAVAILABLE")

zero_grid_exposure_markers = (
    "Grid: $0.00",
    "Grid $0.00",
    "Grid \u6700\u5927\u66dd\u96aa(\u5168 level \u586b\u6eff): $0.00",
    "\u6d3b\u8e8d Grid: 0 \u500b",
)
alignment_clear_markers = ("\u7121 ACTIVE Grid", "No ACTIVE Grid")

if (not any(marker in exposure for marker in zero_grid_exposure_markers)
        and "active grid: 0" not in exposure.lower()):
    unique_append(warnings, "GRID_EXPOSURE_REVIEW_REQUIRED")
if not any(marker in alignment for marker in alignment_clear_markers):
    unique_append(warnings, "GRID_ALIGNMENT_REVIEW_REQUIRED")

trend_gate = trend_gate_review(trend, trend_pct, atr_pct, candidate_plan)
event_risk_gate = event_risk_gate_review(event_risk_level)
okx_gate = okx_gate_review(grid_flags)
operator_authorization_required = []
if trend_gate["operatorOverrideAllowed"]:
    operator_authorization_required.append("optional trend-regime override if not waiting for SIDEWAYS")
if event_risk_gate["operatorOverrideAllowed"]:
    operator_authorization_required.append("optional event-risk operating override if not waiting for R0")
operator_authorization_required.extend([
    "TRADING_OKX_ENABLED=true production env diff",
    "TRADING_GRID_ENABLED=true production env diff",
    "createGrid with reviewed candidate range/capital/stop",
    "post-enable read-only health, runtime-log, exposure, grid alignment, and MCP verification",
])

status = "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION" if not blockers else "BLOCKED_GRID_OPEN_READINESS_NOT_MUTATION"
next_action = "Prepare separate operator env/order authorization packet only after blockers are empty." if not blockers else "Resolve listed blockers with read-only evidence before any grid enablement request."
packet = {
    "packetType": "GRID_OPEN_READINESS_PACKET",
    "scope": "READ_ONLY",
    "symbol": symbol,
    "lookbackHours": lookback_hours,
    "mcpUrl": mcp_url,
    "activeGridCount": active_grid_count,
    "pausedGridCount": paused_grid_count,
    "closedGridCount": closed_grid_count,
    "recommendation": recommendation,
    "trend": trend,
    "trendPct": trend_pct,
    "atrPct": atr_pct,
    "eventRiskLevel": event_risk_level,
    "candidatePlan": candidate_plan,
    "historicalDustSellFailedCount": len(dust_sell_failed_lines),
    "historicalMaterialSellFailedCount": len(material_sell_failed_lines),
    "gateReview": {
        "trendGate": trend_gate,
        "eventRiskGate": event_risk_gate,
        "okxGate": okx_gate,
    },
    "operatorAuthorizationRequired": operator_authorization_required,
    "gridRuntimeFlags": grid_flags,
    "blockers": blockers,
    "requiredEvidence": required_evidence,
    "warnings": warnings,
    "status": status,
    "notAuthorization": "read-only grid open readiness only; does not create/pause/resume/close/rebalance grid, enable scheduler, place orders, send Telegram, mutate DB/OCO/grid/fund/Earn/exchange, deploy, restart, or change production env"
}

print("[grid-open-readiness] read-only packet")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol}")
print(f"lookbackHours={lookback_hours}")
print(f"active_grid_count={active_grid_count}")
print(f"paused_grid_count={paused_grid_count}")
print(f"closed_grid_count={closed_grid_count}")
print(f"grid_trend_recommendation={recommendation}")
print(f"trend={trend}")
print(f"trendPct={trend_pct}")
print(f"atrPct={atr_pct}")
print(f"event_risk_level={event_risk_level}")
print("grid_candidate_plan=" + json.dumps(candidate_plan, sort_keys=True, separators=(",", ":")))
if candidate_plan_error:
    print("grid_candidate_plan_error=" + candidate_plan_error)
print(f"historical_dust_sell_failed_count={len(dust_sell_failed_lines)}")
print(f"historical_material_sell_failed_count={len(material_sell_failed_lines)}")
print("grid_open_gate_review=" + json.dumps({
    "trendGate": trend_gate,
    "eventRiskGate": event_risk_gate,
    "okxGate": okx_gate,
}, sort_keys=True, separators=(",", ":")))
print("grid_open_operator_authorization_required=" + json.dumps(operator_authorization_required, separators=(",", ":")))
print("grid_runtime_flags=" + json.dumps(grid_flags, sort_keys=True, separators=(",", ":")))
print("grid_open_readiness_blockers=" + json.dumps(blockers, separators=(",", ":")))
print("grid_open_readiness_required_evidence=" + json.dumps(required_evidence, separators=(",", ":")))
print("grid_open_readiness_warnings=" + json.dumps(warnings, separators=(",", ":")))
print("grid_open_readiness_packet=" + json.dumps(packet, sort_keys=True, separators=(",", ":")))
print(f"grid_open_readiness_status={status}")
print(f"grid_open_readiness_next_action={next_action}")
print("notAuthorization=read-only grid open readiness only; does not create/pause/resume/close/rebalance grid, enable scheduler, place orders, send Telegram, mutate DB/OCO/grid/fund/Earn/exchange, deploy, restart, or change production env")
print("[grid-open-readiness] read-only check complete")

if require_ready and status != "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION":
    raise SystemExit(f"Grid open readiness is not ready: {status}; blockers={blockers}")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__LOOKBACK_HOURS__", [string]$LookbackHours)
$remoteScript = $remoteScript.Replace("__CANDIDATE_LOOKBACK_HOURS__", [string]$CandidateLookbackHours)
$remoteScript = $remoteScript.Replace("__GRID_COUNT__", [string]$GridCount)
$remoteScript = $remoteScript.Replace("__PER_LEVEL_USDT__", $PerLevelUsdt.ToString([System.Globalization.CultureInfo]::InvariantCulture))
$remoteScript = $remoteScript.Replace("__STOP_OUT_PCT__", $StopOutPct.ToString([System.Globalization.CultureInfo]::InvariantCulture))
$remoteScript = $remoteScript.Replace("__CANDIDATE_HALF_WIDTH_PCT__", $CandidateHalfWidthPct.ToString([System.Globalization.CultureInfo]::InvariantCulture))
$remoteScript = $remoteScript.Replace("__REQUIRE_READY__", $RequireReady.ToString().ToLowerInvariant())

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid open readiness packet failed with exit code $LASTEXITCODE"
}
