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
    [int]$ShortForwardHours = 4,
    [decimal]$MinPositive24hReturnPct = 0.20,
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

if ($ShortForwardHours -lt 1 -or $ShortForwardHours -gt 72) {
    throw "ShortForwardHours must be between 1 and 72."
}

if ($ShortForwardHours -gt $ForwardHours) {
    throw "ShortForwardHours must be less than or equal to ForwardHours."
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
SHORT_FORWARD_HOURS='__SHORT_FORWARD_HOURS__'
MIN_POSITIVE_24H_RETURN_PCT='__MIN_POSITIVE_24H_RETURN_PCT__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-semantics-shadow-review] FAIL: $*" >&2
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
export SYMBOL STRATEGY_ID INTERVAL_CODE HOURS FORWARD_HOURS SHORT_FORWARD_HOURS MIN_POSITIVE_24H_RETURN_PCT LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

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
short_forward_hours = int(os.environ["SHORT_FORWARD_HOURS"])
min_positive_24h_return_pct = float(os.environ["MIN_POSITIVE_24H_RETURN_PCT"])
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

def pct(entry, value):
    if not entry:
        return None
    return (value - entry) * 100.0 / entry

def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

skip_sql = f"""
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
  ) AS entry_price,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = a.symbol
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time >= DATE_ADD(COALESCE(a.bar_open_time, a.event_time), INTERVAL {short_forward_hours} HOUR)
    ORDER BY k.open_time ASC
    LIMIT 1
  ) AS short_close,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = a.symbol
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time >= DATE_ADD(COALESCE(a.bar_open_time, a.event_time), INTERVAL {forward_hours} HOUR)
    ORDER BY k.open_time ASC
    LIMIT 1
  ) AS forward_close,
  (
    SELECT MAX(k.high_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = a.symbol
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time > COALESCE(a.bar_open_time, a.event_time)
      AND k.open_time <= DATE_ADD(COALESCE(a.bar_open_time, a.event_time), INTERVAL {forward_hours} HOUR)
  ) AS max_high,
  (
    SELECT MIN(k.low_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = a.symbol
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time > COALESCE(a.bar_open_time, a.event_time)
      AND k.open_time <= DATE_ADD(COALESCE(a.bar_open_time, a.event_time), INTERVAL {forward_hours} HOUR)
  ) AS min_low
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

fields = [
    "audit_id", "event_time", "anchor_time", "reason", "entry_price",
    "short_close", "forward_close", "max_high", "min_low",
]
rows = [row_dict(fields, row) for row in run_query(skip_sql)]
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

reviewed = []
missing_kline_rows = 0
for row in rows:
    entry = as_float(row.get("entry_price"))
    short_close = as_float(row.get("short_close"))
    forward_close = as_float(row.get("forward_close"))
    max_high = as_float(row.get("max_high"))
    min_low = as_float(row.get("min_low"))
    if entry <= 0 or short_close <= 0 or forward_close <= 0 or max_high <= 0 or min_low <= 0:
        missing_kline_rows += 1
        continue
    short_return = pct(entry, short_close)
    forward_return = pct(entry, forward_close)
    mfe = pct(entry, max_high)
    mae = pct(entry, min_low)
    reviewed.append({
        **row,
        "entry": entry,
        "shortReturnPct": short_return,
        "forwardReturnPct": forward_return,
        "mfePct": mfe,
        "maePct": mae,
    })

forward_returns = [r["forwardReturnPct"] for r in reviewed]
short_returns = [r["shortReturnPct"] for r in reviewed]
mfe_values = [r["mfePct"] for r in reviewed]
mae_values = [r["maePct"] for r in reviewed]
positive_24h = sum(1 for v in forward_returns if v >= min_positive_24h_return_pct)
negative_24h = sum(1 for v in forward_returns if v < 0)
positive_mfe = sum(1 for v in mfe_values if v >= min_positive_24h_return_pct)

entry_dedup_rows = len(rows)
reviewable_rows = len(reviewed)
auto_rows = as_int(exposure.get("auto_traded_open_rows"))
non_auto_rows = as_int(exposure.get("non_auto_open_rows"))
eventrisk_rows = as_int(exposure.get("non_auto_eventrisk_rows"))
zero_qty_rows = as_int(exposure.get("non_auto_zero_qty_rows"))

avg_24h = statistics.mean(forward_returns) if forward_returns else None
median_24h = statistics.median(forward_returns) if forward_returns else None
avg_4h = statistics.mean(short_returns) if short_returns else None
avg_mfe = statistics.mean(mfe_values) if mfe_values else None
avg_mae = statistics.mean(mae_values) if mae_values else None
positive_rate = (positive_24h * 100.0 / reviewable_rows) if reviewable_rows else None

if entry_dedup_rows == 0:
    recommendation = "ENTRY_DEDUP_NO_RECENT_SKIPS"
elif reviewable_rows == 0:
    recommendation = "ENTRY_DEDUP_SHADOW_REVIEW_KLINE_GAP"
elif not (auto_rows == 0 and non_auto_rows > 0 and eventrisk_rows > 0 and zero_qty_rows > 0):
    recommendation = "ENTRY_DEDUP_SHADOW_REVIEW_EXPOSURE_NOT_MISMATCHED"
elif reviewable_rows < 3:
    recommendation = "ENTRY_DEDUP_SHADOW_REVIEW_LOW_SAMPLE"
elif positive_24h >= 3 and avg_24h is not None and avg_24h > 0 and positive_rate is not None and positive_rate >= 50.0:
    recommendation = "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE"
elif positive_mfe >= 3 and avg_mfe is not None and avg_mfe > 0:
    recommendation = "ENTRY_DEDUP_SEMANTICS_TP_PATH_REPLAY_REVIEW"
else:
    recommendation = "ENTRY_DEDUP_SEMANTICS_NO_POSITIVE_ALPHA_EVIDENCE"

shadow_plan = [
    {
        "step": "semantics",
        "candidate": "compare coarse any-open-signal EntryDedup with autoTraded-open-position exposure",
        "requiredEvidence": [
            "recent EntryDedup skips caused by same strategy/symbol/interval LONG exposure",
            "open exposure is non-auto-traded zero-quantity EventRisk-blocked row",
            "forward-return/MFE review remains positive after fees and TP/SL feasibility checks",
        ],
    },
    {
        "step": "shadowOnly",
        "candidate": "record would-have-passed entries without changing live EntryDedup",
        "requiredEvidence": [
            "ExpectedValueGate remains pass or reviewable",
            "EventRiskControl, exact duplicate hash, OCO preflight, daily cap, and max-loss caps remain hard blockers",
            "no order/OCO/grid/fund/Earn/Telegram/exchange mutation",
        ],
    },
]

print("[entry-dedup-semantics-shadow-review] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} shortForwardHours={short_forward_hours} forwardHours={forward_hours} limit={limit}")
print("")
print("Exposure Semantics:")
for key in exposure_fields:
    print(f"  {key}={exposure.get(key, '0')}")
print("  coarse_entry_dedup_definition=any open bt_live_signal row with same strategy/symbol/side/interval")
print("  staged_add_position_definition=autoTraded=true open bt_live_signal row with same strategy/symbol/side/interval")
print("")
print("Skipped Candidate Outcomes:")
print(f"  entry_dedup_skip_rows={entry_dedup_rows}")
print(f"  reviewable_forward_rows={reviewable_rows}")
print(f"  missing_kline_rows={missing_kline_rows}")
print(f"  positive_24h_rows={positive_24h}")
print(f"  negative_24h_rows={negative_24h}")
print(f"  positive_24h_rate_pct={fmt(positive_rate, 2)}")
print(f"  avg_4h_return_pct={fmt(avg_4h)}")
print(f"  avg_24h_return_pct={fmt(avg_24h)}")
print(f"  median_24h_return_pct={fmt(median_24h)}")
print(f"  avg_mfe_24h_pct={fmt(avg_mfe)}")
print(f"  avg_mae_24h_pct={fmt(avg_mae)}")
print(f"  min_positive_24h_return_pct={fmt(min_positive_24h_return_pct, 2)}")
print("Skipped candidate examples:")
if not reviewed:
    print("  - NONE")
else:
    for item in reviewed[:limit]:
        print(
            "  - auditId={audit_id} event={event_time} anchor={anchor_time} "
            "entry={entry:.2f} ret4h={ret4h} ret24h={ret24h} mfe24h={mfe} mae24h={mae} reason={reason}".format(
                audit_id=item.get("audit_id"),
                event_time=item.get("event_time"),
                anchor_time=item.get("anchor_time"),
                entry=item["entry"],
                ret4h=fmt(item["shortReturnPct"]),
                ret24h=fmt(item["forwardReturnPct"]),
                mfe=fmt(item["mfePct"]),
                mae=fmt(item["maePct"]),
                reason=(item.get("reason") or "NONE")[:120],
            )
        )
print("")
print("Shadow Review Plan:")
print("  entry_dedup_semantics_shadow_review_plan=" + json.dumps(shadow_plan, separators=(",", ":")))
print("")
print("Conclusion:")
print(f"  entry_dedup_semantics_shadow_recommendation={recommendation}")
print("  entry_dedup_semantics_next_action=Use this as review-only input for an EntryDedup semantics shadow experiment; do not relax EntryDedup or place/add orders from this smoke.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, EntryDedup/DataFreshness/live policy relaxation, staged-add execution, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[entry-dedup-semantics-shadow-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__STRATEGY_ID__", [string]$StrategyId).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__HOURS__", [string]$Hours).
    Replace("__FORWARD_HOURS__", [string]$ForwardHours).
    Replace("__SHORT_FORWARD_HOURS__", [string]$ShortForwardHours).
    Replace("__MIN_POSITIVE_24H_RETURN_PCT__", [string]$MinPositive24hReturnPct).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup semantics shadow review smoke failed with exit code $LASTEXITCODE"
}
