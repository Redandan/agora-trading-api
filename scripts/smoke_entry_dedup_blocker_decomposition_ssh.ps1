param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$Hours = 720,
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
if ($Hours -lt 1 -or $Hours -gt 720) {
    throw "Hours must be between 1 and 720."
}
if ($Limit -lt 1 -or $Limit -gt 100) {
    throw "Limit must be between 1 and 100."
}

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
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-blocker-decomposition] FAIL: $*" >&2
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
export SYMBOL HOURS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import json
import os
import subprocess
import sys
from collections import defaultdict

symbol = os.environ["SYMBOL"].upper()
hours = int(os.environ["HOURS"])
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

def classify(reason, exposure):
    text = (reason or "").lower()
    auto = as_int(exposure.get("auto_traded_open_rows"))
    non_auto = as_int(exposure.get("non_auto_open_rows"))
    non_auto_zero = as_int(exposure.get("non_auto_zero_qty_rows"))
    non_auto_eventrisk = as_int(exposure.get("non_auto_eventrisk_rows"))
    open_rows = as_int(exposure.get("open_signal_rows"))
    if "same strategy/symbol/interval long exposure already exists" in text:
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
    if "duplicate" in text or "bar" in text:
        return "PROTECTIVE_DUPLICATE_OR_BAR"
    if "cap" in text or "budget" in text or "daily" in text:
        return "PROTECTIVE_CAP_OR_BUDGET"
    return "OTHER_ENTRY_DEDUP"

symbol_sql = esc(symbol)
entry_sql = f"""
SELECT
  COALESCE(a.strategy_id, 0) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(a.reason, '') AS reason,
  COUNT(*) AS row_count,
  COALESCE(MAX(DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s')), 'NONE') AS latest_time
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
GROUP BY COALESCE(a.strategy_id, 0), COALESCE(a.interval_code, 'N/A'), COALESCE(a.reason, '')
ORDER BY row_count DESC, latest_time DESC
"""

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

example_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, 0) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
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

groups = []
ranking = defaultdict(int)
reason_ranking = defaultdict(int)
total_rows = 0
for row in run_query(entry_sql):
    strategy, interval, reason, rows_raw, latest_time = row
    rows = as_int(rows_raw)
    exposure = exposures.get((strategy, interval), {})
    classification = classify(reason, exposure)
    total_rows += rows
    ranking[classification] += rows
    reason_ranking[reason or "N/A"] += rows
    groups.append({
        "strategyId": as_int(strategy),
        "intervalCode": interval,
        "reason": reason or "N/A",
        "rows": rows,
        "latestTime": latest_time,
        "classification": classification,
        "openSignalRows": as_int(exposure.get("open_signal_rows")),
        "autoTradedOpenRows": as_int(exposure.get("auto_traded_open_rows")),
        "nonAutoOpenRows": as_int(exposure.get("non_auto_open_rows")),
        "nonAutoZeroQtyRows": as_int(exposure.get("non_auto_zero_qty_rows")),
        "nonAutoEventRiskRows": as_int(exposure.get("non_auto_eventrisk_rows")),
        "missingOcoRows": as_int(exposure.get("missing_oco_rows")),
        "openNotional": as_float(exposure.get("open_notional")),
    })

classification_rows = [
    {"classification": key, "rows": value, "pct": round(value * 100.0 / total_rows, 2) if total_rows else 0.0}
    for key, value in sorted(ranking.items(), key=lambda item: (-item[1], item[0]))
]
reason_rows = [
    {"reason": key, "rows": value, "pct": round(value * 100.0 / total_rows, 2) if total_rows else 0.0}
    for key, value in sorted(reason_ranking.items(), key=lambda item: (-item[1], item[0]))[:limit]
]

coarse_classes = {
    "COARSE_NON_AUTO_EVENTRISK_ZERO_QTY_OPEN_SIGNAL",
    "COARSE_NON_AUTO_ZERO_QTY_OPEN_SIGNAL",
    "COARSE_NON_AUTO_OPEN_SIGNAL",
    "STALE_OR_RACE_OPEN_EXPOSURE_REFERENCE",
}
protective_classes = {
    "PROTECTIVE_AUTO_TRADED_OPEN_EXPOSURE",
    "PROTECTIVE_DUPLICATE_OR_BAR",
    "PROTECTIVE_CAP_OR_BUDGET",
}
possible_coarse_rows = sum(item["rows"] for item in groups if item["classification"] in coarse_classes)
protective_rows = sum(item["rows"] for item in groups if item["classification"] in protective_classes)
inconclusive_rows = max(0, total_rows - possible_coarse_rows - protective_rows)

examples = []
for row in run_query(example_sql):
    audit_id, event_time, strategy, interval, reason = row
    exposure = exposures.get((strategy, interval), {})
    examples.append({
        "auditId": as_int(audit_id),
        "time": event_time,
        "strategyId": as_int(strategy),
        "intervalCode": interval,
        "reason": reason or "N/A",
        "classification": classify(reason, exposure),
    })

packet = {
    "packetType": "ENTRY_DEDUP_BLOCKER_DECOMPOSITION_PACKET",
    "status": "READY_FOR_ENTRY_DEDUP_BLOCKER_DECOMPOSITION_REVIEW_NOT_LIVE",
    "symbol": symbol,
    "hours": hours,
    "entryDedupRows": total_rows,
    "classificationRanking": classification_rows,
    "reasonRanking": reason_rows,
    "possibleCoarseSemanticsRows": possible_coarse_rows,
    "possibleCoarseSemanticsPct": round(possible_coarse_rows * 100.0 / total_rows, 2) if total_rows else 0.0,
    "protectiveRows": protective_rows,
    "protectivePct": round(protective_rows * 100.0 / total_rows, 2) if total_rows else 0.0,
    "inconclusiveRows": inconclusive_rows,
    "topGroups": groups[:limit],
    "examples": examples,
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
    "nextAction": "Use coarse non-auto/open-signal rows to scope a review-only EntryDedup semantics shadow experiment; keep true auto-traded exposure, duplicate/bar, cap, DataFreshness, EV, and OCO gates unchanged.",
    "notAuthorization": "read-only EntryDedup blocker decomposition only; does not authorize live trading, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import",
}

print("[entry-dedup-blocker-decomposition] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} hours={hours} limit={limit}")
print(f"entry_dedup_blocker_decomposition_status={packet['status']}")
print(f"entry_dedup_rows={total_rows}")
print(f"possible_coarse_semantics_rows={possible_coarse_rows}")
print(f"possible_coarse_semantics_pct={packet['possibleCoarseSemanticsPct']}")
print(f"protective_rows={protective_rows}")
print(f"protective_pct={packet['protectivePct']}")
print(f"inconclusive_rows={inconclusive_rows}")
print("classification_ranking=" + json.dumps(classification_rows, separators=(",", ":")))
print("reason_ranking=" + json.dumps(reason_rows, separators=(",", ":")))
print("entry_dedup_top_groups=" + json.dumps(groups[:limit], separators=(",", ":")))
print("entry_dedup_examples=" + json.dumps(examples, separators=(",", ":")))
print("entry_dedup_policy_change_allowed=false")
print("live_policy_change_allowed=false")
print("scheduler_enablement_allowed=false")
print("order_allowed=false")
print("position_or_oco_mutation_allowed=false")
print("telegram_send_allowed=false")
print("deploy_or_env_change_allowed=false")
print("entry_dedup_blocker_decomposition_packet=" + json.dumps(packet, separators=(",", ":")))
print("entry_dedup_blocker_decomposition_next_action=" + packet["nextAction"])
print("notAuthorization=" + packet["notAuthorization"])
print("[entry-dedup-blocker-decomposition] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__HOURS__", [string]$Hours).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup blocker decomposition smoke failed with exit code $LASTEXITCODE"
}
