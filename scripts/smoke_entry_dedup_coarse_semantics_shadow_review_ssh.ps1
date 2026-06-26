param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$Hours = 720,
    [int]$ForwardHours = 24,
    [int]$ShortForwardHours = 4,
    [decimal]$MinPositive24hReturnPct = 0.20,
    [int]$Limit = 200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($Hours -lt 1 -or $Hours -gt 720) { throw "Hours must be between 1 and 720." }
if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) { throw "ForwardHours must be between 1 and 168." }
if ($ShortForwardHours -lt 1 -or $ShortForwardHours -gt 72) { throw "ShortForwardHours must be between 1 and 72." }
if ($ShortForwardHours -gt $ForwardHours) { throw "ShortForwardHours must be less than or equal to ForwardHours." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
HOURS='__HOURS__'
FORWARD_HOURS='__FORWARD_HOURS__'
SHORT_FORWARD_HOURS='__SHORT_FORWARD_HOURS__'
MIN_POSITIVE_24H_RETURN_PCT='__MIN_POSITIVE_24H_RETURN_PCT__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-coarse-semantics-shadow-review] FAIL: $*" >&2
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
export SYMBOL HOURS FORWARD_HOURS SHORT_FORWARD_HOURS MIN_POSITIVE_24H_RETURN_PCT LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import json
import os
import statistics
import subprocess
import sys
from collections import defaultdict

symbol = os.environ["SYMBOL"].upper()
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

def as_int(value):
    try:
        return int(float(str(value or "0")))
    except Exception:
        return 0

def as_float(value):
    try:
        return float(str(value or "0"))
    except Exception:
        return 0.0

def pct(entry, value):
    if entry <= 0 or value <= 0:
        return None
    return (value - entry) * 100.0 / entry

def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"

def classify_exposure(exposure):
    auto = as_int(exposure.get("auto_traded_open_rows"))
    non_auto = as_int(exposure.get("non_auto_open_rows"))
    non_auto_zero = as_int(exposure.get("non_auto_zero_qty_rows"))
    non_auto_eventrisk = as_int(exposure.get("non_auto_eventrisk_rows"))
    open_rows = as_int(exposure.get("open_signal_rows"))
    if open_rows == 0:
        return "STALE_OR_RACE_OPEN_EXPOSURE_REFERENCE"
    if auto > 0:
        return "PROTECTIVE_AUTO_TRADED_OPEN_EXPOSURE"
    if non_auto_zero > 0 and non_auto_eventrisk > 0:
        return "COARSE_NON_AUTO_EVENTRISK_ZERO_QTY_OPEN_SIGNAL"
    if non_auto_zero > 0:
        return "COARSE_NON_AUTO_ZERO_QTY_OPEN_SIGNAL"
    if non_auto > 0:
        return "COARSE_NON_AUTO_OPEN_SIGNAL"
    return "SAME_EXPOSURE_INCONCLUSIVE"

symbol_sql = esc(symbol)

exposure_sql = f"""
SELECT
  COALESCE(strategy_id, 0) AS strategy_id,
  COALESCE(interval_code, 'N/A') AS interval_code,
  COUNT(*) AS open_signal_rows,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS auto_traded_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 THEN 1 ELSE 0 END), 0) AS non_auto_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS non_auto_zero_qty_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(filter_reason, '') LIKE 'EventRiskControl:%' THEN 1 ELSE 0 END), 0) AS non_auto_eventrisk_rows,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL OR oco_order_list_id = '' THEN 1 ELSE 0 END), 0) AS missing_oco_rows,
  COALESCE(SUM(COALESCE(actual_entry_price, entry_price, 0) * COALESCE(oco_qty, traded_qty, 0)), 0) AS open_notional
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
GROUP BY COALESCE(strategy_id, 0), COALESCE(interval_code, 'N/A')
"""

candidate_sql = f"""
SELECT
  a.id AS audit_id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  DATE_FORMAT(COALESCE(a.bar_open_time, a.event_time), '%Y-%m-%dT%H:%i:%s') AS anchor_time,
  COALESCE(a.strategy_id, 0) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
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
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.reason = 'same strategy/symbol/interval LONG exposure already exists'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
ORDER BY a.event_time DESC
LIMIT {limit}
"""

exposures = {}
for row in run_query(exposure_sql):
    strategy, interval, open_rows, auto, non_auto, non_auto_zero, non_auto_eventrisk, missing_oco, notional = row
    exposures[(strategy, interval)] = {
        "open_signal_rows": as_int(open_rows),
        "auto_traded_open_rows": as_int(auto),
        "non_auto_open_rows": as_int(non_auto),
        "non_auto_zero_qty_rows": as_int(non_auto_zero),
        "non_auto_eventrisk_rows": as_int(non_auto_eventrisk),
        "missing_oco_rows": as_int(missing_oco),
        "open_notional": round(as_float(notional), 4),
    }

fields = [
    "audit_id", "event_time", "anchor_time", "strategy_id", "interval_code", "reason",
    "entry_price", "short_close", "forward_close", "max_high", "min_low",
]
candidates = []
missing_kline_rows = 0
for row in run_query(candidate_sql):
    item = dict(zip(fields, row))
    exposure = exposures.get((item["strategy_id"], item["interval_code"]), {})
    classification = classify_exposure(exposure)
    entry = as_float(item.get("entry_price"))
    short_close = as_float(item.get("short_close"))
    forward_close = as_float(item.get("forward_close"))
    max_high = as_float(item.get("max_high"))
    min_low = as_float(item.get("min_low"))
    if entry <= 0 or short_close <= 0 or forward_close <= 0 or max_high <= 0 or min_low <= 0:
        missing_kline_rows += 1
        continue
    candidates.append({
        "auditId": as_int(item["audit_id"]),
        "eventTime": item["event_time"],
        "anchorTime": item["anchor_time"],
        "strategyId": as_int(item["strategy_id"]),
        "intervalCode": item["interval_code"],
        "reason": item["reason"],
        "classification": classification,
        "entry": entry,
        "shortReturnPct": pct(entry, short_close),
        "forwardReturnPct": pct(entry, forward_close),
        "mfePct": pct(entry, max_high),
        "maePct": pct(entry, min_low),
        "openSignalRows": as_int(exposure.get("open_signal_rows")),
        "autoTradedOpenRows": as_int(exposure.get("auto_traded_open_rows")),
        "nonAutoOpenRows": as_int(exposure.get("non_auto_open_rows")),
        "nonAutoZeroQtyRows": as_int(exposure.get("non_auto_zero_qty_rows")),
        "nonAutoEventRiskRows": as_int(exposure.get("non_auto_eventrisk_rows")),
        "missingOcoRows": as_int(exposure.get("missing_oco_rows")),
        "openNotional": as_float(exposure.get("open_notional")),
    })

def summarize(items):
    forward = [v["forwardReturnPct"] for v in items if v["forwardReturnPct"] is not None]
    short = [v["shortReturnPct"] for v in items if v["shortReturnPct"] is not None]
    mfe = [v["mfePct"] for v in items if v["mfePct"] is not None]
    mae = [v["maePct"] for v in items if v["maePct"] is not None]
    n = len(forward)
    positive = sum(1 for v in forward if v >= min_positive_24h_return_pct)
    negative = sum(1 for v in forward if v < 0)
    return {
        "rows": len(items),
        "reviewableForwardRows": n,
        "positive24hRows": positive,
        "negative24hRows": negative,
        "positive24hRatePct": round(positive * 100.0 / n, 2) if n else 0.0,
        "avg4hReturnPct": round(statistics.mean(short), 4) if short else None,
        "avg24hReturnPct": round(statistics.mean(forward), 4) if forward else None,
        "median24hReturnPct": round(statistics.median(forward), 4) if forward else None,
        "avgMfe24hPct": round(statistics.mean(mfe), 4) if mfe else None,
        "avgMae24hPct": round(statistics.mean(mae), 4) if mae else None,
    }

classification_groups = defaultdict(list)
strategy_groups = defaultdict(list)
for item in candidates:
    classification_groups[item["classification"]].append(item)
    strategy_groups[f"{item['strategyId']}|{item['intervalCode']}"].append(item)

classification_summary = [
    {"classification": key, **summarize(items)}
    for key, items in sorted(classification_groups.items(), key=lambda kv: (-len(kv[1]), kv[0]))
]
strategy_summary = [
    {"strategyInterval": key, **summarize(items)}
    for key, items in sorted(strategy_groups.items(), key=lambda kv: (-len(kv[1]), kv[0]))
]
overall = summarize(candidates)

coarse_classes = {
    "COARSE_NON_AUTO_EVENTRISK_ZERO_QTY_OPEN_SIGNAL",
    "COARSE_NON_AUTO_ZERO_QTY_OPEN_SIGNAL",
    "COARSE_NON_AUTO_OPEN_SIGNAL",
    "STALE_OR_RACE_OPEN_EXPOSURE_REFERENCE",
}
coarse_candidates = [item for item in candidates if item["classification"] in coarse_classes]
coarse = summarize(coarse_candidates)

if not candidates:
    recommendation = "ENTRY_DEDUP_COARSE_SHADOW_NO_REVIEWABLE_ROWS"
elif coarse["reviewableForwardRows"] < 3:
    recommendation = "ENTRY_DEDUP_COARSE_SHADOW_LOW_SAMPLE"
elif (coarse["avg24hReturnPct"] or 0) > 0 and coarse["positive24hRatePct"] >= 50:
    recommendation = "ENTRY_DEDUP_COARSE_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE"
elif (coarse["avgMfe24hPct"] or 0) > 0:
    recommendation = "ENTRY_DEDUP_COARSE_TP_PATH_REPLAY_REVIEW"
else:
    recommendation = "ENTRY_DEDUP_COARSE_SHADOW_ALPHA_NOT_PROVEN"

packet = {
    "packetType": "ENTRY_DEDUP_COARSE_SEMANTICS_SHADOW_REVIEW_PACKET",
    "status": "READY_FOR_ENTRY_DEDUP_COARSE_SEMANTICS_SHADOW_REVIEW_NOT_LIVE",
    "symbol": symbol,
    "hours": hours,
    "shortForwardHours": short_forward_hours,
    "forwardHours": forward_hours,
    "minPositive24hReturnPct": min_positive_24h_return_pct,
    "candidateRows": len(candidates),
    "missingKlineRows": missing_kline_rows,
    "overall": overall,
    "coarseSemantics": coarse,
    "classificationSummary": classification_summary,
    "strategyIntervalSummary": strategy_summary,
    "examples": candidates[:min(limit, 50)],
    "recommendation": recommendation,
    "reviewEnvelope": {
        "reviewOnly": True,
        "entryDedupPolicyChangeAllowed": False,
        "livePolicyChangeAllowed": False,
        "schedulerEnablementAllowed": False,
        "orderAllowed": False,
        "positionOrOcoMutationAllowed": False,
        "telegramSendAllowed": False,
        "deployOrEnvChangeAllowed": False,
    },
    "nextAction": "If alpha remains positive, draft a review-only EntryDedup semantics shadow experiment for coarse non-auto/stale same-exposure rows; keep true auto-traded exposure, duplicate/bar, cap, DataFreshness, EV, and OCO gates unchanged.",
    "notAuthorization": "read-only EntryDedup coarse semantics shadow review only; does not authorize live trading, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import",
}

print("[entry-dedup-coarse-semantics-shadow-review] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} hours={hours} shortForwardHours={short_forward_hours} forwardHours={forward_hours} limit={limit}")
print(f"entry_dedup_coarse_semantics_shadow_review_status={packet['status']}")
print(f"candidate_rows={packet['candidateRows']}")
print(f"missing_kline_rows={missing_kline_rows}")
print(f"coarse_reviewable_forward_rows={coarse['reviewableForwardRows']}")
print(f"coarse_positive_24h_rows={coarse['positive24hRows']}")
print(f"coarse_negative_24h_rows={coarse['negative24hRows']}")
print(f"coarse_positive_24h_rate_pct={coarse['positive24hRatePct']}")
print(f"coarse_avg_4h_return_pct={fmt(coarse['avg4hReturnPct'])}")
print(f"coarse_avg_24h_return_pct={fmt(coarse['avg24hReturnPct'])}")
print(f"coarse_median_24h_return_pct={fmt(coarse['median24hReturnPct'])}")
print(f"coarse_avg_mfe_24h_pct={fmt(coarse['avgMfe24hPct'])}")
print(f"coarse_avg_mae_24h_pct={fmt(coarse['avgMae24hPct'])}")
print("classification_summary=" + json.dumps(classification_summary, separators=(",", ":")))
print("strategy_interval_summary=" + json.dumps(strategy_summary, separators=(",", ":")))
print("entry_dedup_coarse_examples=" + json.dumps(candidates[:min(limit, 50)], separators=(",", ":")))
print(f"entry_dedup_coarse_semantics_shadow_recommendation={recommendation}")
print("entry_dedup_policy_change_allowed=false")
print("live_policy_change_allowed=false")
print("scheduler_enablement_allowed=false")
print("order_allowed=false")
print("position_or_oco_mutation_allowed=false")
print("telegram_send_allowed=false")
print("deploy_or_env_change_allowed=false")
print("entry_dedup_coarse_semantics_shadow_review_packet=" + json.dumps(packet, separators=(",", ":")))
print("entry_dedup_coarse_semantics_next_action=" + packet["nextAction"])
print("notAuthorization=" + packet["notAuthorization"])
print("[entry-dedup-coarse-semantics-shadow-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__HOURS__", [string]$Hours).
    Replace("__FORWARD_HOURS__", [string]$ForwardHours).
    Replace("__SHORT_FORWARD_HOURS__", [string]$ShortForwardHours).
    Replace("__MIN_POSITIVE_24H_RETURN_PCT__", [string]$MinPositive24hReturnPct).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup coarse semantics shadow review smoke failed with exit code $LASTEXITCODE"
}
