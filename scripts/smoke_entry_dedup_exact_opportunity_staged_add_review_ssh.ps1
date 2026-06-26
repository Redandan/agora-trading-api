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
    [decimal]$StagedAddBudgetUsdt = 20,
    [decimal]$CandidateAddUsdt = 10,
    [int]$Limit = 200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($Hours -lt 1 -or $Hours -gt 2160) { throw "Hours must be between 1 and 2160." }
if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) { throw "ForwardHours must be between 1 and 168." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($StagedAddBudgetUsdt -lt 0 -or $StagedAddBudgetUsdt -gt 10000) { throw "StagedAddBudgetUsdt must be between 0 and 10000." }
if ($CandidateAddUsdt -le 0 -or $CandidateAddUsdt -gt 10000) { throw "CandidateAddUsdt must be greater than 0 and at most 10000." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }

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
STAGED_ADD_BUDGET_USDT='__STAGED_ADD_BUDGET_USDT__'
CANDIDATE_ADD_USDT='__CANDIDATE_ADD_USDT__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-exact-opportunity-staged-add-review] FAIL: $*" >&2
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
export SYMBOL STRATEGY_ID INTERVAL_CODE HOURS FORWARD_HOURS TAKE_PROFIT_PCT STOP_LOSS_PCT ROUND_TRIP_FEE_PCT STAGED_ADD_BUDGET_USDT CANDIDATE_ADD_USDT LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import hashlib
import json
import os
import statistics
import subprocess
import sys
from collections import defaultdict

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
hours = int(os.environ["HOURS"])
forward_hours = int(os.environ["FORWARD_HOURS"])
tp_pct = float(os.environ["TAKE_PROFIT_PCT"])
sl_pct = float(os.environ["STOP_LOSS_PCT"])
fee_pct = float(os.environ["ROUND_TRIP_FEE_PCT"])
staged_add_budget_usdt = float(os.environ["STAGED_ADD_BUDGET_USDT"])
candidate_add_usdt = float(os.environ["CANDIDATE_ADD_USDT"])
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

def as_int(value):
    try:
        return int(float(str(value or "0")))
    except Exception:
        return 0

def fmt(value, digits=4):
    if value is None:
        return "N/A"
    return f"{value:.{digits}f}"

def pct(entry, value):
    if entry <= 0 or value <= 0:
        return None
    return (value - entry) * 100.0 / entry

def opportunity_hash(parts):
    raw = "|".join(str(part) for part in parts)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

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
    return [dict(zip(fields, row)) for row in run_query(sql)]

exposure_fields = [
    "open_signal_rows", "auto_traded_open_rows", "non_auto_open_rows",
    "non_auto_zero_qty_rows", "non_auto_eventrisk_rows", "missing_oco_rows", "open_notional",
]
exposure = dict(zip(exposure_fields, run_query(exposure_sql)[0]))
open_notional = as_float(exposure.get("open_notional"))
remaining_add_budget = max(0.0, staged_add_budget_usdt - open_notional)

candidate_fields = ["audit_id", "event_time", "anchor_time", "live_signal_id", "reason", "entry_price"]
raw_rows = [dict(zip(candidate_fields, row)) for row in run_query(candidate_sql)]
groups = defaultdict(list)
for row in raw_rows:
    entry = as_float(row.get("entry_price"))
    entry_bucket = round(entry, 1) if entry > 0 else "NA"
    key_parts = [strategy_id, symbol, interval_code, "LONG", row["anchor_time"], entry_bucket]
    row["opportunityKey"] = opportunity_hash(key_parts)
    row["entryBucket"] = entry_bucket
    groups[row["opportunityKey"]].append(row)

opportunities = []
missing_forward_rows = 0
for key, rows in groups.items():
    first = rows[0]
    entry = as_float(first.get("entry_price"))
    bars = bars_for(first["anchor_time"])
    if entry <= 0 or not bars:
        missing_forward_rows += 1
        opportunities.append({
            "opportunityKey": key,
            "auditRows": len(rows),
            "status": "MISSING_ENTRY_OR_FORWARD_BARS",
            "firstAuditId": as_int(first.get("audit_id")),
            "anchorTime": first["anchor_time"],
        })
        continue
    tp = entry * (1.0 + tp_pct / 100.0)
    sl = entry * (1.0 - sl_pct / 100.0)
    max_high = max(as_float(bar.get("high_price")) for bar in bars)
    min_low = min(as_float(bar.get("low_price")) for bar in bars)
    last_close = as_float(bars[-1].get("close_price"))
    forward_return = pct(entry, last_close)
    mfe = pct(entry, max_high)
    mae = pct(entry, min_low)
    outcome = "TIMEOUT"
    net_return = (forward_return or 0.0) - fee_pct
    for bar in bars:
        high = as_float(bar.get("high_price"))
        low = as_float(bar.get("low_price"))
        if high >= tp and low <= sl:
            outcome = "AMBIGUOUS_SAME_BAR"
            net_return = None
            break
        if low <= sl:
            outcome = "SL_HIT"
            net_return = -sl_pct - fee_pct
            break
        if high >= tp:
            outcome = "TP_HIT"
            net_return = tp_pct - fee_pct
            break
    expected_r_proxy = None if net_return is None or sl_pct <= 0 else net_return / sl_pct
    budget_proxy_allowed = remaining_add_budget >= candidate_add_usdt
    exact_duplicate_suppressed_rows = max(0, len(rows) - 1)
    has_auto_exposure = as_int(exposure.get("auto_traded_open_rows")) > 0
    has_non_auto_zero = as_int(exposure.get("non_auto_zero_qty_rows")) > 0
    has_missing_oco = as_int(exposure.get("missing_oco_rows")) > 0
    synthetic_ev_pass = expected_r_proxy is not None and expected_r_proxy > 0
    staged_add_review_candidate = (
        synthetic_ev_pass
        and budget_proxy_allowed
        and not has_auto_exposure
        and outcome != "AMBIGUOUS_SAME_BAR"
    )
    blockers = []
    if not synthetic_ev_pass:
        blockers.append("SYNTHETIC_EV_PROXY_NOT_PASS")
    if not budget_proxy_allowed:
        blockers.append("STAGED_ADD_BUDGET_PROXY_EXHAUSTED")
    if has_auto_exposure:
        blockers.append("AUTO_TRADED_OPEN_EXPOSURE_PRESENT")
    if has_non_auto_zero:
        blockers.append("NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT")
    if has_missing_oco:
        blockers.append("OCO_ROUTE_NOT_PROVEN_OR_MISSING")
    opportunities.append({
        "opportunityKey": key,
        "auditRows": len(rows),
        "exactDuplicateSuppressedRows": exact_duplicate_suppressed_rows,
        "firstAuditId": as_int(first.get("audit_id")),
        "lastAuditId": as_int(rows[-1].get("audit_id")),
        "anchorTime": first["anchor_time"],
        "entry": round(entry, 4),
        "tp": round(tp, 4),
        "sl": round(sl, 4),
        "outcome": outcome,
        "netReturnPct": None if net_return is None else round(net_return, 4),
        "expectedRProxy": None if expected_r_proxy is None else round(expected_r_proxy, 4),
        "forwardReturnPct": None if forward_return is None else round(forward_return, 4),
        "mfePct": None if mfe is None else round(mfe, 4),
        "maePct": None if mae is None else round(mae, 4),
        "budgetProxyAllowed": budget_proxy_allowed,
        "stagedAddReviewCandidate": staged_add_review_candidate,
        "reviewBlockers": blockers,
    })

reviewable = [item for item in opportunities if item.get("expectedRProxy") is not None]
review_candidates = [item for item in opportunities if item.get("stagedAddReviewCandidate")]
tp_hits = sum(1 for item in opportunities if item.get("outcome") == "TP_HIT")
sl_hits = sum(1 for item in opportunities if item.get("outcome") == "SL_HIT")
ambiguous = sum(1 for item in opportunities if item.get("outcome") == "AMBIGUOUS_SAME_BAR")
duplicate_suppressed = sum(item.get("exactDuplicateSuppressedRows", 0) for item in opportunities)
avg_expected_r = statistics.mean(item["expectedRProxy"] for item in reviewable) if reviewable else None
avg_net = statistics.mean(item["netReturnPct"] for item in reviewable if item.get("netReturnPct") is not None) if reviewable else None

if not opportunities:
    status = "NO_ENTRY_DEDUP_EXACT_OPPORTUNITIES_NOT_LIVE"
elif review_candidates:
    status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
else:
    status = "BLOCKED_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"

packet = {
    "packetType": "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET",
    "status": status,
    "scope": "READ_ONLY_SYNTHETIC_REPLAY_PROXY_NOT_RUNTIME_EV",
    "symbol": symbol,
    "strategyId": strategy_id,
    "intervalCode": interval_code,
    "hours": hours,
    "rawAuditRows": len(raw_rows),
    "exactOpportunityCount": len(opportunities),
    "exactDuplicateSuppressedRows": duplicate_suppressed,
    "stagedAddBudgetUsdt": staged_add_budget_usdt,
    "candidateAddUsdt": candidate_add_usdt,
    "openNotionalUsdt": round(open_notional, 4),
    "remainingAddBudgetUsdt": round(remaining_add_budget, 4),
    "stagedAddBudgetProxyAllowedOpportunities": len([item for item in opportunities if item.get("budgetProxyAllowed")]),
    "stagedAddReviewCandidateOpportunities": len(review_candidates),
    "tpHitOpportunities": tp_hits,
    "slHitOpportunities": sl_hits,
    "ambiguousOpportunities": ambiguous,
    "missingForwardRows": missing_forward_rows,
    "avgExpectedRProxy": None if avg_expected_r is None else round(avg_expected_r, 4),
    "avgNetReturnPct": None if avg_net is None else round(avg_net, 4),
    "openExposure": {key: as_float(value) for key, value in exposure.items()},
    "opportunities": opportunities[: min(limit, 80)],
    "requiredBeforeAnyMutation": [
        "runtime EV snapshot for each exact opportunity",
        "runtime OCO route proof for each exact opportunity",
        "exact duplicate replay protection in write path",
        "daily cap and max-loss snapshot in write path",
        "operator approval for staged-add policy and deployment",
    ],
    "orderAllowed": False,
    "entryDedupPolicyChangeAllowed": False,
    "livePolicyChangeAllowed": False,
    "stagedAddExecutionAllowed": False,
    "positionOrOcoMutationAllowed": False,
    "deployOrEnvChangeAllowed": False,
}

print("[entry-dedup-exact-opportunity-staged-add-review] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no RuntimeDecisionEvidence writes, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} forwardHours={forward_hours} limit={limit}")
print(f"entry_dedup_exact_opportunity_staged_add_review_status={status}")
print(f"raw_audit_rows={len(raw_rows)}")
print(f"exact_opportunity_count={len(opportunities)}")
print(f"exact_duplicate_suppressed_rows={duplicate_suppressed}")
print(f"staged_add_budget_usdt={staged_add_budget_usdt:.4f}")
print(f"candidate_add_usdt={candidate_add_usdt:.4f}")
print(f"open_notional_usdt={open_notional:.4f}")
print(f"remaining_add_budget_usdt={remaining_add_budget:.4f}")
print(f"staged_add_budget_proxy_allowed_opportunities={packet['stagedAddBudgetProxyAllowedOpportunities']}")
print(f"staged_add_review_candidate_opportunities={len(review_candidates)}")
print(f"tp_hit_opportunities={tp_hits}")
print(f"sl_hit_opportunities={sl_hits}")
print(f"ambiguous_opportunities={ambiguous}")
print(f"missing_forward_rows={missing_forward_rows}")
print(f"avg_expected_r_proxy={fmt(avg_expected_r)}")
print(f"avg_net_return_pct={fmt(avg_net)}")
for key, value in exposure.items():
    print(f"open_exposure_{key}={value}")
print("entry_dedup_exact_opportunity_examples=" + json.dumps(opportunities[: min(limit, 30)], separators=(",", ":")))
print("entry_dedup_exact_opportunity_staged_add_review_packet=" + json.dumps(packet, separators=(",", ":")))
print("order_allowed=false")
print("entry_dedup_policy_change_allowed=false")
print("live_policy_change_allowed=false")
print("staged_add_execution_allowed=false")
print("position_or_oco_mutation_allowed=false")
print("deploy_or_env_change_allowed=false")
print("notAuthorization=read-only EntryDedup exact-opportunity staged-add review only; does not authorize EntryDedup relaxation, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import")
print("[entry-dedup-exact-opportunity-staged-add-review] OK read-only check complete")
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
    Replace("__STAGED_ADD_BUDGET_USDT__", [string]$StagedAddBudgetUsdt).
    Replace("__CANDIDATE_ADD_USDT__", [string]$CandidateAddUsdt).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup exact-opportunity staged-add review smoke failed with exit code $LASTEXITCODE"
}
