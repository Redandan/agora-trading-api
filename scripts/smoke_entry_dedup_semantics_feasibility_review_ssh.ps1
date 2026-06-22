param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 168,
    [int]$ForwardHours = 24,
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [int]$Limit = 30
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

if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

if ($Hours -lt 1 -or $Hours -gt 720) {
    throw "Hours must be between 1 and 720."
}

if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) {
    throw "ForwardHours must be between 1 and 168."
}

if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) {
    throw "TakeProfitPct must be greater than 0 and at most 20."
}

if ($StopLossPct -le 0 -or $StopLossPct -gt 20) {
    throw "StopLossPct must be greater than 0 and at most 20."
}

if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) {
    throw "RoundTripFeePct must be between 0 and 2."
}

if ($Limit -lt 1 -or $Limit -gt 100) {
    throw "Limit must be between 1 and 100."
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
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
STRATEGY_ID='__STRATEGY_ID__'
INTERVAL_CODE='__INTERVAL_CODE__'
HOURS='__HOURS__'
FORWARD_HOURS='__FORWARD_HOURS__'
TAKE_PROFIT_PCT='__TAKE_PROFIT_PCT__'
STOP_LOSS_PCT='__STOP_LOSS_PCT__'
ROUND_TRIP_FEE_PCT='__ROUND_TRIP_FEE_PCT__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-semantics-feasibility-review] FAIL: $*" >&2
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
export SYMBOL STRATEGY_ID INTERVAL_CODE HOURS FORWARD_HOURS TAKE_PROFIT_PCT STOP_LOSS_PCT ROUND_TRIP_FEE_PCT LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

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
hours = int(os.environ["HOURS"])
forward_hours = int(os.environ["FORWARD_HOURS"])
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

def row_dict(fields, row):
    return dict(zip(fields, row))

def as_float(value):
    try:
        return float(str(value or "0"))
    except Exception:
        return 0.0

def as_int(value):
    try:
        return int(float(str(value or "0")))
    except Exception:
        return 0

def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

candidate_sql = f"""
SELECT
  a.id AS audit_id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  DATE_FORMAT(COALESCE(a.bar_open_time, a.event_time), '%Y-%m-%dT%H:%i:%s') AS anchor_time,
  COALESCE(a.reason, '') AS reason,
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
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.reason = 'same strategy/symbol/interval LONG exposure already exists'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
ORDER BY a.event_time ASC
LIMIT {limit}
"""

exposure_sql = f"""
SELECT
  COUNT(*) AS open_signal_rows,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS auto_traded_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 THEN 1 ELSE 0 END), 0) AS non_auto_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS non_auto_zero_qty_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(filter_reason, '') LIKE 'EventRiskControl:%' THEN 1 ELSE 0 END), 0) AS non_auto_eventrisk_rows,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL OR oco_order_list_id = '' THEN 1 ELSE 0 END), 0) AS missing_oco_rows,
  COALESCE(SUM(COALESCE(actual_entry_price, entry_price, 0) * COALESCE(oco_qty, traded_qty, 0)), 0) AS open_notional
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
"""

candidate_fields = ["audit_id", "event_time", "anchor_time", "reason", "entry_price"]
candidates = [row_dict(candidate_fields, row) for row in run_query(candidate_sql)]
exposure_fields = [
    "open_signal_rows",
    "auto_traded_open_rows",
    "non_auto_open_rows",
    "non_auto_zero_qty_rows",
    "non_auto_eventrisk_rows",
    "missing_oco_rows",
    "open_notional",
]
exposure = row_dict(exposure_fields, run_query(exposure_sql)[0])

def bars_for(anchor_time):
    sql = f"""
SELECT
  DATE_FORMAT(k.open_time, '%Y-%m-%dT%H:%i:%s') AS open_time,
  k.high_price,
  k.low_price,
  k.close_price
FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE k.symbol = '{symbol_sql}'
  AND k.interval_code = '1h'
  AND k.source = 'okx'
  AND k.open_time > '{esc(anchor_time)}'
  AND k.open_time <= DATE_ADD('{esc(anchor_time)}', INTERVAL {forward_hours} HOUR)
ORDER BY k.open_time ASC
"""
    fields = ["open_time", "high_price", "low_price", "close_price"]
    return [row_dict(fields, row) for row in run_query(sql)]

def simulate(candidate):
    entry = as_float(candidate.get("entry_price"))
    if entry <= 0:
        return {**candidate, "status": "MISSING_ENTRY", "netReturnPct": None}
    tp_price = entry * (1.0 + tp_pct / 100.0)
    sl_price = entry * (1.0 - sl_pct / 100.0)
    bars = bars_for(candidate["anchor_time"])
    if not bars:
        return {**candidate, "entry": entry, "tpPrice": tp_price, "slPrice": sl_price, "status": "NO_BARS", "netReturnPct": None}
    max_high = max(as_float(b.get("high_price")) for b in bars)
    min_low = min(as_float(b.get("low_price")) for b in bars)
    last_close = as_float(bars[-1].get("close_price"))
    for bar in bars:
        high = as_float(bar.get("high_price"))
        low = as_float(bar.get("low_price"))
        tp_hit = high >= tp_price
        sl_hit = low <= sl_price
        if tp_hit and sl_hit:
            return {
                **candidate,
                "entry": entry,
                "tpPrice": tp_price,
                "slPrice": sl_price,
                "status": "AMBIGUOUS_SAME_BAR",
                "exitTime": bar.get("open_time"),
                "netReturnPct": None,
                "mfePct": (max_high - entry) * 100.0 / entry,
                "maePct": (min_low - entry) * 100.0 / entry,
            }
        if sl_hit:
            return {
                **candidate,
                "entry": entry,
                "tpPrice": tp_price,
                "slPrice": sl_price,
                "status": "SL_HIT",
                "exitTime": bar.get("open_time"),
                "netReturnPct": -sl_pct - fee_pct,
                "mfePct": (max_high - entry) * 100.0 / entry,
                "maePct": (min_low - entry) * 100.0 / entry,
            }
        if tp_hit:
            return {
                **candidate,
                "entry": entry,
                "tpPrice": tp_price,
                "slPrice": sl_price,
                "status": "TP_HIT",
                "exitTime": bar.get("open_time"),
                "netReturnPct": tp_pct - fee_pct,
                "mfePct": (max_high - entry) * 100.0 / entry,
                "maePct": (min_low - entry) * 100.0 / entry,
            }
    gross_timeout = (last_close - entry) * 100.0 / entry if last_close > 0 else None
    net_timeout = gross_timeout - fee_pct if gross_timeout is not None else None
    return {
        **candidate,
        "entry": entry,
        "tpPrice": tp_price,
        "slPrice": sl_price,
        "status": "TIMEOUT",
        "exitTime": bars[-1].get("open_time"),
        "netReturnPct": net_timeout,
        "mfePct": (max_high - entry) * 100.0 / entry,
        "maePct": (min_low - entry) * 100.0 / entry,
    }

results = [simulate(candidate) for candidate in candidates]
reviewed = [r for r in results if r.get("netReturnPct") is not None]
net_returns = [r["netReturnPct"] for r in reviewed]
tp_hits = sum(1 for r in results if r.get("status") == "TP_HIT")
sl_hits = sum(1 for r in results if r.get("status") == "SL_HIT")
timeouts = sum(1 for r in results if r.get("status") == "TIMEOUT")
ambiguous = sum(1 for r in results if r.get("status") == "AMBIGUOUS_SAME_BAR")
no_bars = sum(1 for r in results if r.get("status") in ("NO_BARS", "MISSING_ENTRY"))
net_positive = sum(1 for v in net_returns if v > 0)
avg_net = statistics.mean(net_returns) if net_returns else None
median_net = statistics.median(net_returns) if net_returns else None
win_rate = net_positive * 100.0 / len(reviewed) if reviewed else None

auto_rows = as_int(exposure.get("auto_traded_open_rows"))
non_auto_rows = as_int(exposure.get("non_auto_open_rows"))
eventrisk_rows = as_int(exposure.get("non_auto_eventrisk_rows"))
zero_qty_rows = as_int(exposure.get("non_auto_zero_qty_rows"))
mismatch = auto_rows == 0 and non_auto_rows > 0 and eventrisk_rows > 0 and zero_qty_rows > 0

if not candidates:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_NO_RECENT_SKIPS"
elif not mismatch:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_EXPOSURE_NOT_MISMATCHED"
elif len(reviewed) < 3:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_LOW_SAMPLE"
elif ambiguous > 0:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_AMBIGUOUS_REPLAY_REVIEW"
elif avg_net is not None and avg_net > 0 and win_rate is not None and win_rate >= 50.0 and tp_hits >= 3:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE"
elif avg_net is not None and avg_net > 0:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_POSITIVE_TIMEOUT_REVIEW"
else:
    recommendation = "ENTRY_DEDUP_FEASIBILITY_NOT_PROVEN"

plan = [
    {
        "step": "feeAdjustedReplay",
        "assumption": f"LONG TP {tp_pct:.2f}%, SL {sl_pct:.2f}%, roundTripFee {fee_pct:.2f}%, {forward_hours}h max hold",
        "requiredEvidence": [
            "net positive replay after fees",
            "same-bar TP/SL ambiguity excluded or resolved with lower timeframe bars",
            "OCO route feasible before any live policy change",
        ],
    },
    {
        "step": "shadowExperimentOnly",
        "requiredEvidence": [
            "ExpectedValueGate pass-like evidence",
            "EventRiskControl, exact duplicate hash, daily cap, max loss, and exposure caps remain hard blockers",
            "operator review packet explicitly keeps order_allowed=false",
        ],
    },
]

print("[entry-dedup-semantics-feasibility-review] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} forwardHours={forward_hours} takeProfitPct={tp_pct:.2f} stopLossPct={sl_pct:.2f} roundTripFeePct={fee_pct:.2f} limit={limit}")
print("")
print("Exposure Semantics:")
for key in exposure_fields:
    print(f"  {key}={exposure.get(key, '0')}")
print(f"  exposure_semantics_mismatch={str(mismatch).lower()}")
print("")
print("TP/SL/OCO Feasibility Replay:")
print(f"  entry_dedup_skip_rows={len(candidates)}")
print(f"  replay_reviewed_rows={len(reviewed)}")
print(f"  tp_hit_rows={tp_hits}")
print(f"  sl_hit_rows={sl_hits}")
print(f"  timeout_rows={timeouts}")
print(f"  ambiguous_same_bar_rows={ambiguous}")
print(f"  missing_kline_rows={no_bars}")
print(f"  net_positive_rows={net_positive}")
print(f"  net_win_rate_pct={fmt(win_rate, 2)}")
print(f"  avg_net_return_pct={fmt(avg_net)}")
print(f"  median_net_return_pct={fmt(median_net)}")
print("Feasibility examples:")
if not results:
    print("  - NONE")
else:
    for item in results[:limit]:
        print(
            "  - auditId={audit_id} event={event_time} anchor={anchor_time} status={status} "
            "exit={exit_time} entry={entry} tp={tp} sl={sl} netReturnPct={net} mfe24h={mfe} mae24h={mae} reason={reason}".format(
                audit_id=item.get("audit_id"),
                event_time=item.get("event_time"),
                anchor_time=item.get("anchor_time"),
                status=item.get("status"),
                exit_time=item.get("exitTime", "N/A"),
                entry=fmt(item.get("entry"), 2) if item.get("entry") is not None else "N/A",
                tp=fmt(item.get("tpPrice"), 2) if item.get("tpPrice") is not None else "N/A",
                sl=fmt(item.get("slPrice"), 2) if item.get("slPrice") is not None else "N/A",
                net=fmt(item.get("netReturnPct")),
                mfe=fmt(item.get("mfePct")),
                mae=fmt(item.get("maePct")),
                reason=(item.get("reason") or "NONE")[:120],
            )
        )
print("")
print("Shadow Feasibility Plan:")
print("  entry_dedup_semantics_feasibility_plan=" + json.dumps(plan, separators=(",", ":")))
print("")
print("Conclusion:")
print(f"  entry_dedup_semantics_feasibility_recommendation={recommendation}")
print("  entry_dedup_semantics_feasibility_next_action=Use this as review-only TP/SL/OCO feasibility input; do not relax EntryDedup or place/add orders from this smoke.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, EntryDedup/DataFreshness/live policy relaxation, staged-add execution, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[entry-dedup-semantics-feasibility-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__STRATEGY_ID__", [string]$StrategyId).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__HOURS__", [string]$Hours).
    Replace("__FORWARD_HOURS__", [string]$ForwardHours).
    Replace("__TAKE_PROFIT_PCT__", [string]$TakeProfitPct).
    Replace("__STOP_LOSS_PCT__", [string]$StopLossPct).
    Replace("__ROUND_TRIP_FEE_PCT__", [string]$RoundTripFeePct).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup semantics feasibility review smoke failed with exit code $LASTEXITCODE"
}
