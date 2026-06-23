param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [string]$IntervalCode = "1h",
    [int]$ReviewDays = 7,
    [int]$ForwardHours = 24,
    [int]$ShortForwardHours = 4,
    [decimal]$NearThresholdMaxGap = 2,
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [int]$Limit = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) { throw "ReviewDays must be between 1 and 30." }
if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) { throw "ForwardHours must be between 1 and 168." }
if ($ShortForwardHours -lt 1 -or $ShortForwardHours -gt 72) { throw "ShortForwardHours must be between 1 and 72." }
if ($ShortForwardHours -gt $ForwardHours) { throw "ShortForwardHours must be less than or equal to ForwardHours." }
if ($NearThresholdMaxGap -lt 0 -or $NearThresholdMaxGap -gt 20) { throw "NearThresholdMaxGap must be between 0 and 20." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($Limit -lt 1 -or $Limit -gt 100) { throw "Limit must be between 1 and 100." }

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
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._:-]*$") {
        throw "$Name contains unsupported characters for strategy574 near-threshold shadow smoke."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
STRATEGY_ID='__STRATEGY_ID__'
INTERVAL_CODE='__INTERVAL_CODE__'
REVIEW_DAYS='__REVIEW_DAYS__'
FORWARD_HOURS='__FORWARD_HOURS__'
SHORT_FORWARD_HOURS='__SHORT_FORWARD_HOURS__'
NEAR_THRESHOLD_MAX_GAP='__NEAR_THRESHOLD_MAX_GAP__'
TAKE_PROFIT_PCT='__TAKE_PROFIT_PCT__'
STOP_LOSS_PCT='__STOP_LOSS_PCT__'
ROUND_TRIP_FEE_PCT='__ROUND_TRIP_FEE_PCT__'
LIMIT='__LIMIT__'

fail() {
  echo "[strategy574-near-threshold-shadow-observation] FAIL: $*" >&2
  exit 1
}

read_env_key() {
  local key="$1"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
  printf '%s\n' "${line#*=}" | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

command -v mysql >/dev/null 2>&1 || fail "mysql is not available on server"

SPRING_DATASOURCE_URL="$(read_env_key SPRING_DATASOURCE_URL)"
SPRING_DATASOURCE_USERNAME="$(read_env_key SPRING_DATASOURCE_USERNAME)"
SPRING_DATASOURCE_PASSWORD="$(read_env_key SPRING_DATASOURCE_PASSWORD)"

case "$SPRING_DATASOURCE_URL" in
  jdbc:mysql://*) ;;
  *) fail "SPRING_DATASOURCE_URL must be a jdbc:mysql URL" ;;
esac

jdbc_without_prefix="${SPRING_DATASOURCE_URL#jdbc:mysql://}"
jdbc_without_query="${jdbc_without_prefix%%\?*}"
host_port="${jdbc_without_query%%/*}"
database="${jdbc_without_query#*/}"

[ -n "$database" ] && [ "$database" != "$jdbc_without_query" ] || fail "database name missing in SPRING_DATASOURCE_URL"
if [ "$database" != "agora_market" ]; then
  fail "refusing to query unexpected database: $database"
fi

if printf '%s\n' "$host_port" | grep -q ':'; then
  host="${host_port%%:*}"
  port="${host_port##*:}"
else
  host="$host_port"
  port="3306"
fi

case "$port" in
  ''|*[!0-9]*) fail "database port is invalid in SPRING_DATASOURCE_URL: $port" ;;
esac

export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
export SYMBOL STRATEGY_ID INTERVAL_CODE REVIEW_DAYS FORWARD_HOURS SHORT_FORWARD_HOURS NEAR_THRESHOLD_MAX_GAP TAKE_PROFIT_PCT STOP_LOSS_PCT ROUND_TRIP_FEE_PCT LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import json
import os
import statistics
import subprocess
import sys

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
review_days = int(os.environ["REVIEW_DAYS"])
forward_hours = int(os.environ["FORWARD_HOURS"])
short_forward_hours = int(os.environ["SHORT_FORWARD_HOURS"])
near_threshold_max_gap = float(os.environ["NEAR_THRESHOLD_MAX_GAP"])
tp_pct = float(os.environ["TAKE_PROFIT_PCT"])
sl_pct = float(os.environ["STOP_LOSS_PCT"])
fee_pct = float(os.environ["ROUND_TRIP_FEE_PCT"])
limit = int(os.environ["LIMIT"])

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def run_query(sql):
    cmd = [
        "mysql",
        "--batch",
        "--raw",
        "--skip-column-names",
        "-h", os.environ["MYSQL_HOST"],
        "-P", os.environ["MYSQL_PORT"],
        "-u", os.environ["MYSQL_USER"],
        os.environ["MYSQL_DATABASE"],
        "-e", sql,
    ]
    try:
        proc = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exc:
        print(exc.stderr, file=sys.stderr)
        sys.exit(exc.returncode or 1)
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def as_float(value):
    try:
        return float(str(value or "0"))
    except Exception:
        return 0.0

def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"

def row_dict(fields, row):
    return dict(zip(fields, row))

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

candidate_sql = f"""
SELECT
  a.id AS audit_id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  DATE_FORMAT(COALESCE(a.bar_open_time, a.event_time), '%Y-%m-%dT%H:%i:%s') AS anchor_time,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_indicator')), 'N/A') AS mih_indicator,
  CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6)) AS mih_value,
  CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6)) AS buy_threshold,
  (
    CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6))
    - CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6))
  ) AS buy_gap,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.hold_reason')), COALESCE(a.reason, 'UNKNOWN')) AS hold_reason,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = a.symbol
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time <= COALESCE(a.bar_open_time, a.event_time)
    ORDER BY k.open_time DESC
    LIMIT 1
  ) AS entry_price
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.strategy_id = {strategy_id}
  AND COALESCE(a.interval_code, 'N/A') = '{interval_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value') IS NOT NULL
  AND JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold') IS NOT NULL
  AND (
    CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6))
    - CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6))
  ) BETWEEN 0 AND {near_threshold_max_gap}
ORDER BY a.event_time ASC
LIMIT {limit}
"""

fields = [
    "audit_id", "event_time", "anchor_time", "mih_indicator", "mih_value",
    "buy_threshold", "buy_gap", "hold_reason", "entry_price",
]
candidates = [row_dict(fields, row) for row in run_query(candidate_sql)]

def bars_for(anchor_time):
    sql = f"""
SELECT DATE_FORMAT(open_time, '%Y-%m-%dT%H:%i:%s') AS open_time, high_price, low_price, close_price
FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE symbol = '{symbol_sql}'
  AND interval_code = '1h'
  AND source = 'okx'
  AND open_time > '{esc(anchor_time)}'
  AND open_time <= DATE_ADD('{esc(anchor_time)}', INTERVAL {forward_hours} HOUR)
ORDER BY open_time ASC
"""
    return [row_dict(["open_time", "high_price", "low_price", "close_price"], row) for row in run_query(sql)]

def forward_close(anchor_time, hours):
    sql = f"""
SELECT close_price
FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE symbol = '{symbol_sql}'
  AND interval_code = '1h'
  AND source = 'okx'
  AND open_time >= DATE_ADD('{esc(anchor_time)}', INTERVAL {hours} HOUR)
ORDER BY open_time ASC
LIMIT 1
"""
    rows = run_query(sql)
    return as_float(rows[0][0]) if rows else 0.0

def simulate(candidate):
    entry = as_float(candidate.get("entry_price"))
    if entry <= 0:
        return {**candidate, "status": "MISSING_ENTRY", "netReturnPct": None}
    short_close = forward_close(candidate["anchor_time"], short_forward_hours)
    long_close = forward_close(candidate["anchor_time"], forward_hours)
    bars = bars_for(candidate["anchor_time"])
    if short_close <= 0 or long_close <= 0 or not bars:
        return {**candidate, "entry": entry, "status": "MISSING_KLINE", "netReturnPct": None}
    tp_price = entry * (1.0 + tp_pct / 100.0)
    sl_price = entry * (1.0 - sl_pct / 100.0)
    max_high = max(as_float(b["high_price"]) for b in bars)
    min_low = min(as_float(b["low_price"]) for b in bars)
    base = {
        **candidate,
        "entry": entry,
        "shortReturnPct": (short_close - entry) * 100.0 / entry,
        "forwardReturnPct": (long_close - entry) * 100.0 / entry,
        "mfePct": (max_high - entry) * 100.0 / entry,
        "maePct": (min_low - entry) * 100.0 / entry,
        "tpPrice": tp_price,
        "slPrice": sl_price,
    }
    for bar in bars:
        high = as_float(bar["high_price"])
        low = as_float(bar["low_price"])
        tp_hit = high >= tp_price
        sl_hit = low <= sl_price
        if tp_hit and sl_hit:
            return {**base, "status": "AMBIGUOUS_SAME_BAR", "exitTime": bar["open_time"], "netReturnPct": None}
        if sl_hit:
            return {**base, "status": "SL_HIT", "exitTime": bar["open_time"], "netReturnPct": -sl_pct - fee_pct}
        if tp_hit:
            return {**base, "status": "TP_HIT", "exitTime": bar["open_time"], "netReturnPct": tp_pct - fee_pct}
    return {**base, "status": "TIMEOUT", "exitTime": bars[-1]["open_time"], "netReturnPct": base["forwardReturnPct"] - fee_pct}

results = [simulate(c) for c in candidates]
reviewed = [r for r in results if r.get("netReturnPct") is not None]
forward_reviewed = [r for r in results if r.get("forwardReturnPct") is not None]
missing_kline = sum(1 for r in results if r.get("status") in ("MISSING_ENTRY", "MISSING_KLINE"))
tp_hits = sum(1 for r in results if r.get("status") == "TP_HIT")
sl_hits = sum(1 for r in results if r.get("status") == "SL_HIT")
timeouts = sum(1 for r in results if r.get("status") == "TIMEOUT")
ambiguous = sum(1 for r in results if r.get("status") == "AMBIGUOUS_SAME_BAR")
net_returns = [r["netReturnPct"] for r in reviewed]
forward_returns = [r["forwardReturnPct"] for r in forward_reviewed]
short_returns = [r["shortReturnPct"] for r in forward_reviewed]
mfe_values = [r["mfePct"] for r in forward_reviewed]
mae_values = [r["maePct"] for r in forward_reviewed]
positive_forward = sum(1 for v in forward_returns if v > 0)
negative_forward = sum(1 for v in forward_returns if v < 0)
false_positive_rows = sum(1 for v in forward_returns if v <= 0)
net_positive = sum(1 for v in net_returns if v > 0)
avg_forward = statistics.mean(forward_returns) if forward_returns else None
avg_short = statistics.mean(short_returns) if short_returns else None
avg_mfe = statistics.mean(mfe_values) if mfe_values else None
avg_mae = statistics.mean(mae_values) if mae_values else None
avg_net = statistics.mean(net_returns) if net_returns else None
net_win_rate = (net_positive * 100.0 / len(reviewed)) if reviewed else None
false_positive_rate = (false_positive_rows * 100.0 / len(forward_reviewed)) if forward_reviewed else None

oco_preflight_status = "REQUIRED_NOT_PROVEN"
if len(reviewed) > 0 and ambiguous == 0 and tp_hits > 0:
    oco_preflight_status = "REVIEW_REQUIRED_TP_SL_PROXY_AVAILABLE"

if not candidates:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_NO_RECENT_ROWS"
elif len(forward_reviewed) < 3:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_LOW_FORWARD_SAMPLE"
elif false_positive_rate is not None and false_positive_rate > 50.0:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH"
elif avg_net is not None and avg_net > 0 and net_win_rate is not None and net_win_rate >= 50.0 and ambiguous == 0:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_SHADOW_OBSERVATION_CANDIDATE_NOT_LIVE"
elif avg_forward is not None and avg_forward > 0:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_FORWARD_ALPHA_REVIEW_OCO_REQUIRED"
else:
    recommendation = "STRATEGY574_NEAR_THRESHOLD_ALPHA_NOT_PROVEN"

plan = [
    {
        "step": "shadowObservation",
        "candidate": "record strategy574 near-threshold rows without changing threshold or activation",
        "requiredEvidence": [
            "forward returns remain positive after fees",
            "false-positive rate acceptable",
            "OCO preflight separately reviewed",
            "TinyLive/live/order/scheduler flags remain disabled",
        ],
    },
    {
        "step": "futureMutationGate",
        "requiredEvidence": [
            "separate operator authorization",
            "EV gate pass",
            "OCO/min-notional/pre-position checks pass",
            "rollback and post-change read-only verification plan",
        ],
    },
]

print("[strategy574-near-threshold-shadow-observation] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} reviewDays={review_days} shortForwardHours={short_forward_hours} forwardHours={forward_hours} nearThresholdMaxGap={near_threshold_max_gap:.2f} takeProfitPct={tp_pct:.2f} stopLossPct={sl_pct:.2f} roundTripFeePct={fee_pct:.2f} limit={limit}")
print("")
print("Near-Threshold Forward Evidence:")
print(f"  near_threshold_rows={len(candidates)}")
print(f"  reviewable_forward_rows={len(forward_reviewed)}")
print(f"  missing_kline_rows={missing_kline}")
print(f"  positive_forward_rows={positive_forward}")
print(f"  negative_forward_rows={negative_forward}")
print(f"  false_positive_rows={false_positive_rows}")
print(f"  false_positive_rate_pct={fmt(false_positive_rate, 2)}")
print(f"  avg_short_forward_return_pct={fmt(avg_short)}")
print(f"  avg_forward_return_pct={fmt(avg_forward)}")
print(f"  avg_forward_mfe_pct={fmt(avg_mfe)}")
print(f"  avg_forward_mae_pct={fmt(avg_mae)}")
print(f"  avg_{short_forward_hours}h_return_pct={fmt(avg_short)}")
print(f"  avg_{forward_hours}h_return_pct={fmt(avg_forward)}")
print(f"  avg_mfe_{forward_hours}h_pct={fmt(avg_mfe)}")
print(f"  avg_mae_{forward_hours}h_pct={fmt(avg_mae)}")
print("")
print("TP/SL/Fee Proxy:")
print(f"  replay_reviewed_rows={len(reviewed)}")
print(f"  tp_hit_rows={tp_hits}")
print(f"  sl_hit_rows={sl_hits}")
print(f"  timeout_rows={timeouts}")
print(f"  ambiguous_same_bar_rows={ambiguous}")
print(f"  net_positive_rows={net_positive}")
print(f"  net_win_rate_pct={fmt(net_win_rate, 2)}")
print(f"  avg_net_return_pct={fmt(avg_net)}")
print(f"  oco_preflight_status={oco_preflight_status}")
print("Examples:")
if not results:
    print("  - NONE")
else:
    for item in results[:limit]:
        print(
            "  - auditId={audit_id} event={event_time} anchor={anchor_time} gap={gap} indicator={indicator} "
            "entry={entry} ret4h={ret4h} ret24h={ret24h} mfe24h={mfe} mae24h={mae} status={status} net={net} holdReason={reason}".format(
                audit_id=item.get("audit_id"),
                event_time=item.get("event_time"),
                anchor_time=item.get("anchor_time"),
                gap=fmt(as_float(item.get("buy_gap"))),
                indicator=item.get("mih_indicator", "N/A"),
                entry=fmt(item.get("entry"), 2) if item.get("entry") is not None else "N/A",
                ret4h=fmt(item.get("shortReturnPct")),
                ret24h=fmt(item.get("forwardReturnPct")),
                mfe=fmt(item.get("mfePct")),
                mae=fmt(item.get("maePct")),
                status=item.get("status"),
                net=fmt(item.get("netReturnPct")),
                reason=(item.get("hold_reason") or "UNKNOWN")[:100],
            )
        )
print("")
print("Shadow Observation Plan:")
print("  strategy574_near_threshold_shadow_observation_plan=" + json.dumps(plan, separators=(",", ":")))
print("")
print("Conclusion:")
print(f"  strategy574_near_threshold_shadow_recommendation={recommendation}")
print("  strategy574_near_threshold_next_action=Use this as review-only forward/false-positive/TP-SL evidence for a shadow observation plan; do not change strategy threshold, activate strategy, execute TinyLive, or enable live policy from this smoke.")
print("  notAuthorization=read-only evidence only; does not authorize strategy threshold changes, strategy activation, live trading, TinyLive execution, scheduler enablement, orders, OCO modification, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import")
print("")
print("[strategy574-near-threshold-shadow-observation] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__STRATEGY_ID__", [string]$StrategyId).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__FORWARD_HOURS__", [string]$ForwardHours).
    Replace("__SHORT_FORWARD_HOURS__", [string]$ShortForwardHours).
    Replace("__NEAR_THRESHOLD_MAX_GAP__", [string]$NearThresholdMaxGap).
    Replace("__TAKE_PROFIT_PCT__", [string]$TakeProfitPct).
    Replace("__STOP_LOSS_PCT__", [string]$StopLossPct).
    Replace("__ROUND_TRIP_FEE_PCT__", [string]$RoundTripFeePct).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Strategy574 near-threshold shadow observation smoke failed with exit code $LASTEXITCODE"
}
