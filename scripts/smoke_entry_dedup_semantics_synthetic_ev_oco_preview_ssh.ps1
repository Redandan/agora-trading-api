param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 720,
    [int]$ForwardHours = 24,
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [int]$Limit = 50
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
if ($Hours -lt 1 -or $Hours -gt 2160) {
    throw "Hours must be between 1 and 2160."
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
if ($Limit -lt 1 -or $Limit -gt 200) {
    throw "Limit must be between 1 and 200."
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
  echo "[entry-dedup-synthetic-ev-oco-preview] FAIL: $*" >&2
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
command -v python3 >/dev/null 2>&1 || fail "python3 is not available on server"

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
  COALESCE(a.live_signal_id, '') AS live_signal_id,
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

open_exposure_sql = f"""
SELECT
  COUNT(*) AS open_signal_rows,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS auto_traded_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS non_auto_zero_qty_rows,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL OR oco_order_list_id = '' THEN 1 ELSE 0 END), 0) AS missing_oco_rows
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
"""

candidate_fields = ["audit_id", "event_time", "anchor_time", "live_signal_id", "reason", "entry_price"]
candidates = [row_dict(candidate_fields, row) for row in run_query(candidate_sql)]
exposure = row_dict(["open_signal_rows", "auto_traded_open_rows", "non_auto_zero_qty_rows", "missing_oco_rows"], run_query(open_exposure_sql)[0])

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

def preview(candidate):
    entry = as_float(candidate.get("entry_price"))
    if entry <= 0:
        return {**candidate, "status": "MISSING_ENTRY", "entry": None}
    tp = entry * (1.0 + tp_pct / 100.0)
    sl = entry * (1.0 - sl_pct / 100.0)
    bars = bars_for(candidate["anchor_time"])
    if not bars:
        return {**candidate, "status": "NO_FORWARD_BARS", "entry": entry, "tp": tp, "sl": sl}
    max_high = max(as_float(b.get("high_price")) for b in bars)
    min_low = min(as_float(b.get("low_price")) for b in bars)
    last_close = as_float(bars[-1].get("close_price"))
    forward_ret = (last_close - entry) * 100.0 / entry
    mfe = (max_high - entry) * 100.0 / entry
    mae = (min_low - entry) * 100.0 / entry
    outcome = "TIMEOUT"
    net_return = forward_ret - fee_pct
    exit_time = bars[-1].get("open_time")
    for bar in bars:
        high = as_float(bar.get("high_price"))
        low = as_float(bar.get("low_price"))
        if high >= tp and low <= sl:
            outcome = "AMBIGUOUS_SAME_BAR"
            net_return = None
            exit_time = bar.get("open_time")
            break
        if low <= sl:
            outcome = "SL_HIT"
            net_return = -sl_pct - fee_pct
            exit_time = bar.get("open_time")
            break
        if high >= tp:
            outcome = "TP_HIT"
            net_return = tp_pct - fee_pct
            exit_time = bar.get("open_time")
            break
    expected_r_proxy = None if net_return is None or sl_pct <= 0 else net_return / sl_pct
    ev_status = "SYNTHETIC_EV_PROXY_PASS" if expected_r_proxy is not None and expected_r_proxy > 0 else "SYNTHETIC_EV_PROXY_NOT_PASS"
    if outcome == "AMBIGUOUS_SAME_BAR":
        ev_status = "SYNTHETIC_EV_PROXY_AMBIGUOUS"
    oco_shape = "PLAN_SHAPE_VALID" if sl < entry < tp else "PLAN_SHAPE_INVALID"
    if int(float(exposure.get("missing_oco_rows") or "0")) > 0 or int(float(exposure.get("non_auto_zero_qty_rows") or "0")) > 0:
        oco_route = "OCO_ROUTE_NOT_PROVEN_EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
    else:
        oco_route = "OCO_ROUTE_NOT_PROVEN_EXCHANGE_DRY_RUN_REQUIRED"
    return {
        **candidate,
        "status": outcome,
        "exitTime": exit_time,
        "entry": entry,
        "tp": tp,
        "sl": sl,
        "forwardReturnPct": forward_ret,
        "mfePct": mfe,
        "maePct": mae,
        "netReturnPct": net_return,
        "expectedRProxy": expected_r_proxy,
        "evStatus": ev_status,
        "ocoPlanShapeStatus": oco_shape,
        "ocoRouteStatus": oco_route,
    }

rows = [preview(candidate) for candidate in candidates]
reviewable = [r for r in rows if r.get("expectedRProxy") is not None]
tp_hits = sum(1 for r in rows if r.get("status") == "TP_HIT")
sl_hits = sum(1 for r in rows if r.get("status") == "SL_HIT")
ambiguous = sum(1 for r in rows if r.get("status") == "AMBIGUOUS_SAME_BAR")
ev_proxy_pass = sum(1 for r in rows if r.get("evStatus") == "SYNTHETIC_EV_PROXY_PASS")
valid_oco_shape = sum(1 for r in rows if r.get("ocoPlanShapeStatus") == "PLAN_SHAPE_VALID")
avg_expected_r_proxy = statistics.mean([r["expectedRProxy"] for r in reviewable]) if reviewable else None
avg_net = statistics.mean([r["netReturnPct"] for r in reviewable]) if reviewable else None

if not rows:
    status = "NO_RECENT_ENTRY_DEDUP_CANDIDATES_NOT_LIVE"
elif ambiguous > 0:
    status = "SYNTHETIC_EV_OCO_PREVIEW_AMBIGUOUS_NOT_LIVE"
elif ev_proxy_pass == len(rows) and valid_oco_shape == len(rows):
    status = "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE"
else:
    status = "SYNTHETIC_EV_OCO_PREVIEW_INCOMPLETE_NOT_LIVE"

packet = {
    "packetType": "ENTRY_DEDUP_SYNTHETIC_EV_OCO_PREVIEW_PACKET",
    "status": status,
    "scope": "READ_ONLY_SYNTHETIC_REPLAY_PROXY_NOT_RUNTIME_EV",
    "symbol": symbol,
    "strategyId": strategy_id,
    "intervalCode": interval_code,
    "candidateRows": len(rows),
    "tpHitRows": tp_hits,
    "slHitRows": sl_hits,
    "ambiguousSameBarRows": ambiguous,
    "syntheticEvProxyPassRows": ev_proxy_pass,
    "validOcoPlanShapeRows": valid_oco_shape,
    "avgExpectedRProxy": avg_expected_r_proxy,
    "avgNetReturnPct": avg_net,
    "openExposure": {k: int(float(v or "0")) for k, v in exposure.items()},
    "notRuntimeEvidence": True,
    "orderAllowed": False,
    "livePolicyChangeAllowed": False,
    "positionOrOcoMutationAllowed": False,
}

print("[entry-dedup-synthetic-ev-oco-preview] read-only production synthetic preview")
print("scope=READ_ONLY; direct MySQL SELECTs only; no RuntimeDecisionEvidence writes, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} forwardHours={forward_hours} takeProfitPct={tp_pct:.2f} stopLossPct={sl_pct:.2f} roundTripFeePct={fee_pct:.2f} limit={limit}")
print("")
print("Synthetic EV/OCO Summary:")
print(f"  entry_dedup_synthetic_ev_oco_preview_status={status}")
print(f"  candidate_rows={len(rows)}")
print(f"  tp_hit_rows={tp_hits}")
print(f"  sl_hit_rows={sl_hits}")
print(f"  ambiguous_same_bar_rows={ambiguous}")
print(f"  synthetic_ev_proxy_pass_rows={ev_proxy_pass}")
print(f"  valid_oco_plan_shape_rows={valid_oco_shape}")
print(f"  avg_expected_r_proxy={fmt(avg_expected_r_proxy)}")
print(f"  avg_net_return_pct={fmt(avg_net)}")
for key, value in exposure.items():
    print(f"  {key}={value}")
print("")
print("Synthetic candidate previews:")
if not rows:
    print("  - NONE")
else:
    for item in rows:
        print(
            "  - auditId={audit_id} event={event_time} anchor={anchor_time} status={status} "
            "entry={entry} tp={tp} sl={sl} netReturnPct={net} expectedRProxy={evr} "
            "evStatus={ev_status} ocoShape={oco_shape} ocoRoute={oco_route} "
            "forwardReturnPct={fwd} mfePct={mfe} maePct={mae} reason={reason}".format(
                audit_id=item.get("audit_id"),
                event_time=item.get("event_time"),
                anchor_time=item.get("anchor_time"),
                status=item.get("status"),
                entry=fmt(item.get("entry"), 2) if item.get("entry") is not None else "N/A",
                tp=fmt(item.get("tp"), 2) if item.get("tp") is not None else "N/A",
                sl=fmt(item.get("sl"), 2) if item.get("sl") is not None else "N/A",
                net=fmt(item.get("netReturnPct")),
                evr=fmt(item.get("expectedRProxy")),
                ev_status=item.get("evStatus", "N/A"),
                oco_shape=item.get("ocoPlanShapeStatus", "N/A"),
                oco_route=item.get("ocoRouteStatus", "N/A"),
                fwd=fmt(item.get("forwardReturnPct")),
                mfe=fmt(item.get("mfePct")),
                mae=fmt(item.get("maePct")),
                reason=(item.get("reason") or "NONE")[:120],
            )
        )
print("")
print("Conclusion:")
print("  entry_dedup_synthetic_ev_oco_preview_packet=" + json.dumps(packet, separators=(",", ":")))
print(f"  entry_dedup_synthetic_ev_oco_preview_status={status}")
print("  order_allowed=false")
print("  runtime_evidence_write_allowed=false")
print("  live_policy_change_allowed=false")
print("  position_or_oco_mutation_allowed=false")
print("  deploy_or_env_change_allowed=false")
print("  notAuthorization=read-only synthetic EV/OCO preview only; does not authorize EntryDedup relaxation, runtime evidence writes, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import")
print("")
print("[entry-dedup-synthetic-ev-oco-preview] OK read-only check complete")
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
    throw "EntryDedup synthetic EV/OCO preview smoke failed with exit code $LASTEXITCODE"
}
