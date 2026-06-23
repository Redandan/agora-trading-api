param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 7,
    [int]$Limit = 10
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

if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) {
    throw "ReviewDays must be between 1 and 30."
}

if ($Limit -lt 1 -or $Limit -gt 50) {
    throw "Limit must be between 1 and 50."
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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for signal-eval no-buy generation smoke."
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
REVIEW_DAYS='__REVIEW_DAYS__'
LIMIT='__LIMIT__'

fail() {
  echo "[signal-eval-no-buy-generation] FAIL: $*" >&2
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
export SYMBOL REVIEW_DAYS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import os
import subprocess
import sys

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
limit = int(os.environ["LIMIT"])

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

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

symbol_sql = esc(symbol)
buy_like = """
(
  UPPER(COALESCE(a.outcome,'')) LIKE '%BUY%'
  OR UPPER(COALESCE(a.reason,'')) LIKE '%BUY%'
  OR UPPER(COALESCE(a.reason,'')) LIKE '%LONG%'
  OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.execution.decision')), '')) IN ('LONG','BUY')
  OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side')), '')) IN ('LONG','BUY')
  OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.signal')), '')) IN ('LONG','BUY')
)
"""

hold_reason_expr = """
COALESCE(
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.hold_reason')), ''),
  NULLIF(a.reason, ''),
  'UNKNOWN'
)
"""

summary_sql = f"""
SELECT
  COUNT(*) AS signal_eval_rows,
  COALESCE(SUM(CASE WHEN {buy_like} THEN 1 ELSE 0 END), 0) AS buy_like_signal_eval_rows,
  COALESCE(SUM(CASE WHEN NOT ({buy_like}) THEN 1 ELSE 0 END), 0) AS no_buy_signal_eval_rows,
  COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.outcome,'')) = 'PASS' THEN 1 ELSE 0 END), 0) AS pass_rows,
  COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.outcome,'')) = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked_rows,
  COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.outcome,'')) = 'ERROR' THEN 1 ELSE 0 END), 0) AS error_rows,
  COALESCE(SUM(CASE WHEN UPPER(COALESCE(a.reason,'')) LIKE '%HOLD%' THEN 1 ELSE 0 END), 0) AS hold_reason_rows,
  COALESCE(SUM(CASE WHEN CAST(COALESCE(JSON_EXTRACT(a.context_json, '$.version'), 1) AS UNSIGNED) = 2 THEN 1 ELSE 0 END), 0) AS v2_context_rows,
  COALESCE(SUM(CASE WHEN JSON_EXTRACT(a.context_json, '$.extras.strategy_decision') IS NOT NULL THEN 1 ELSE 0 END), 0) AS strategy_decision_context_rows,
  COALESCE(SUM(CASE WHEN UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.execution.decision')), '')) = 'HOLD' THEN 1 ELSE 0 END), 0) AS execution_hold_rows,
  COALESCE(SUM(CASE WHEN a.strategy_id IS NULL OR a.strategy_id < 0 THEN 1 ELSE 0 END), 0) AS macro_or_unknown_strategy_rows,
  COALESCE(SUM(CASE WHEN COALESCE(a.interval_code,'') = '' OR COALESCE(a.interval_code,'N/A') = 'N/A' THEN 1 ELSE 0 END), 0) AS no_interval_rows
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
"""

reason_family_sql = f"""
SELECT reason_family, COUNT(*) AS cnt
FROM (
  SELECT
    CASE
      WHEN {buy_like} THEN 'BUY_LIKE'
      WHEN UPPER({hold_reason_expr}) LIKE '%HOLD%' THEN 'HOLD_OR_WAIT'
      WHEN UPPER({hold_reason_expr}) LIKE '%WAIT%' THEN 'HOLD_OR_WAIT'
      WHEN UPPER({hold_reason_expr}) LIKE '%THRESHOLD%' THEN 'THRESHOLD_NOT_HIT'
      WHEN UPPER({hold_reason_expr}) LIKE '%INDICATOR%' THEN 'INDICATOR_OR_DATA_MISSING'
      WHEN UPPER({hold_reason_expr}) LIKE '%MISSING%' THEN 'INDICATOR_OR_DATA_MISSING'
      WHEN UPPER({hold_reason_expr}) LIKE '%BEAR%' THEN 'BEARISH_OR_SHORT_BIAS'
      WHEN UPPER({hold_reason_expr}) LIKE '%SHORT%' THEN 'BEARISH_OR_SHORT_BIAS'
      WHEN UPPER({hold_reason_expr}) LIKE '%NO%' THEN 'NO_SIGNAL_OR_CONDITION'
      WHEN TRIM(COALESCE({hold_reason_expr},'')) = '' THEN 'EMPTY_REASON'
      ELSE 'OTHER_REASON'
    END AS reason_family
  FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
  WHERE a.symbol = '{symbol_sql}'
    AND a.event_type = 'SIGNAL_EVAL'
    AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
) x
GROUP BY reason_family
ORDER BY cnt DESC, reason_family ASC
LIMIT {limit}
"""

hold_reason_sql = f"""
SELECT {hold_reason_expr} AS hold_reason, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND NOT ({buy_like})
GROUP BY hold_reason
ORDER BY cnt DESC, hold_reason ASC
LIMIT {limit}
"""

strategy_sql = f"""
SELECT COALESCE(a.strategy_id, -1) AS strategy_id, COALESCE(a.interval_code, 'N/A') AS interval_code, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
GROUP BY strategy_id, interval_code
ORDER BY cnt DESC, strategy_id ASC, interval_code ASC
LIMIT {limit}
"""

threshold_gap_sql = f"""
SELECT
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_indicator')), 'N/A') AS mih_indicator,
  ROUND(AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6))), 4) AS avg_mih_value,
  ROUND(AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6))), 4) AS avg_buy_threshold,
  ROUND(AVG(
    CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6))
    - CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6))
  ), 4) AS avg_buy_gap,
  ROUND(MIN(
    CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')) AS DECIMAL(18,6))
    - CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')) AS DECIMAL(18,6))
  ), 4) AS min_buy_gap,
  COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value') IS NOT NULL
  AND JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold') IS NOT NULL
GROUP BY strategy_id, interval_code, mih_indicator
ORDER BY cnt DESC, avg_buy_gap ASC, strategy_id ASC, interval_code ASC
LIMIT {limit}
"""

context_side_sql = f"""
SELECT context_side, COUNT(*) AS cnt
FROM (
  SELECT COALESCE(
    NULLIF(UPPER(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.execution.decision'))), ''),
    NULLIF(UPPER(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side'))), ''),
    NULLIF(UPPER(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.signal'))), ''),
    'NONE'
  ) AS context_side
  FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
  WHERE a.symbol = '{symbol_sql}'
    AND a.event_type = 'SIGNAL_EVAL'
    AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
) x
GROUP BY context_side
ORDER BY cnt DESC, context_side ASC
LIMIT {limit}
"""

examples_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(a.outcome, '') AS outcome,
  COALESCE(a.reason, '') AS reason,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.execution.decision')), '') AS execution_decision,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.hold_reason')), '') AS hold_reason,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_indicator')), '') AS mih_indicator,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.mih_value')), '') AS mih_value,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.extras.strategy_decision.buy_threshold')), '') AS buy_threshold
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'SIGNAL_EVAL'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND NOT ({buy_like})
ORDER BY a.event_time DESC
LIMIT {limit}
"""

summary_rows = run_query(summary_sql)
reason_rows = run_query(reason_family_sql)
hold_reason_rows = run_query(hold_reason_sql)
strategy_rows = run_query(strategy_sql)
threshold_gap_rows = run_query(threshold_gap_sql)
context_side_rows = run_query(context_side_sql)
example_rows = run_query(examples_sql)

fields = [
    "signal_eval_rows", "buy_like_signal_eval_rows", "no_buy_signal_eval_rows",
    "pass_rows", "blocked_rows", "error_rows", "hold_reason_rows",
    "v2_context_rows", "strategy_decision_context_rows", "execution_hold_rows",
    "macro_or_unknown_strategy_rows", "no_interval_rows",
]
summary = dict(zip(fields, summary_rows[0])) if summary_rows else {k: "0" for k in fields}

def intval(key):
    try:
        return int(summary.get(key, "0") or "0")
    except ValueError:
        return 0

signal_eval_rows = intval("signal_eval_rows")
buy_like_rows = intval("buy_like_signal_eval_rows")
no_buy_rows = intval("no_buy_signal_eval_rows")
hold_rows = intval("hold_reason_rows")
strategy_decision_rows = intval("strategy_decision_context_rows")
macro_rows = intval("macro_or_unknown_strategy_rows")

if signal_eval_rows == 0:
    recommendation = "NO_SIGNAL_EVAL_IN_REVIEW_WINDOW"
elif buy_like_rows == 0 and strategy_decision_rows * 100 >= max(1, signal_eval_rows) * 50:
    recommendation = "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT"
elif buy_like_rows == 0 and hold_rows * 100 >= max(1, signal_eval_rows) * 50:
    recommendation = "NO_BUY_LIKE_SIGNAL_EVAL_HOLD_OR_WAIT_DOMINATES"
elif buy_like_rows == 0 and macro_rows * 100 >= max(1, signal_eval_rows) * 50:
    recommendation = "NO_BUY_LIKE_SIGNAL_EVAL_MACRO_OR_UNKNOWN_DOMINATES"
elif buy_like_rows == 0:
    recommendation = "NO_BUY_LIKE_SIGNAL_EVAL_MIXED_REVIEW"
else:
    recommendation = "BUY_LIKE_SIGNAL_EVAL_PRESENT_REVIEW_PROGRESS_PATH"

print("[signal-eval-no-buy-generation] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} limit={limit}")
print("")
print("Signal Eval No-Buy Generation Summary:")
for key in fields:
    print(f"  {key}={summary.get(key, '0')}")
print("")
print("signal_eval_reason_family_distribution:")
if not reason_rows:
    print("  - NONE=0")
else:
    for row in reason_rows:
        name = row[0] if len(row) > 0 and row[0] else "UNKNOWN"
        cnt = row[1] if len(row) > 1 and row[1] else "0"
        print(f"  - {name}={cnt}")
print("signal_eval_hold_reason_distribution:")
if not hold_reason_rows:
    print("  - NONE=0")
else:
    for row in hold_reason_rows:
        name = row[0] if len(row) > 0 and row[0] else "UNKNOWN"
        cnt = row[1] if len(row) > 1 and row[1] else "0"
        print(f"  - {name}={cnt}")
print("signal_eval_strategy_distribution:")
if not strategy_rows:
    print("  - NONE=0")
else:
    for row in strategy_rows:
        strategy = row[0] if len(row) > 0 else "UNKNOWN"
        interval = row[1] if len(row) > 1 else "N/A"
        cnt = row[2] if len(row) > 2 else "0"
        print(f"  - strategy={strategy} interval={interval} count={cnt}")
print("signal_eval_threshold_gap_distribution:")
if not threshold_gap_rows:
    print("  - NONE=0")
else:
    for row in threshold_gap_rows:
        strategy = row[0] if len(row) > 0 else "UNKNOWN"
        interval = row[1] if len(row) > 1 else "N/A"
        indicator = row[2] if len(row) > 2 else "N/A"
        avg_value = row[3] if len(row) > 3 else "NULL"
        avg_threshold = row[4] if len(row) > 4 else "NULL"
        avg_gap = row[5] if len(row) > 5 else "NULL"
        min_gap = row[6] if len(row) > 6 else "NULL"
        cnt = row[7] if len(row) > 7 else "0"
        print(f"  - strategy={strategy} interval={interval} indicator={indicator} count={cnt} avg_mih_value={avg_value} avg_buy_threshold={avg_threshold} avg_buy_gap={avg_gap} min_buy_gap={min_gap}")
print("signal_eval_context_side_distribution:")
if not context_side_rows:
    print("  - NONE=0")
else:
    for row in context_side_rows:
        side = row[0] if len(row) > 0 and row[0] else "NONE"
        cnt = row[1] if len(row) > 1 and row[1] else "0"
        print(f"  - {side}={cnt}")
print("Examples:")
if not example_rows:
    print("  - NONE")
else:
    for row in example_rows:
        audit_id = row[0] if len(row) > 0 else ""
        event_time = row[1] if len(row) > 1 else ""
        strategy = row[2] if len(row) > 2 else ""
        interval = row[3] if len(row) > 3 else ""
        outcome = row[4] if len(row) > 4 else ""
        reason = (row[5] if len(row) > 5 else "")[:120]
        execution_decision = row[6] if len(row) > 6 else ""
        hold_reason = row[7] if len(row) > 7 else ""
        indicator = row[8] if len(row) > 8 else ""
        mih_value = row[9] if len(row) > 9 else ""
        buy_threshold = row[10] if len(row) > 10 else ""
        print(f"  - auditId={audit_id} time={event_time} strategy={strategy} interval={interval} outcome={outcome} execution={execution_decision or 'NONE'} hold_reason={hold_reason or reason or 'UNKNOWN'} indicator={indicator or 'N/A'} mih_value={mih_value or 'N/A'} buy_threshold={buy_threshold or 'N/A'}")
print("")
print("Conclusion:")
print(f"  signal_eval_no_buy_generation_recommendation={recommendation}")
print("  signal_eval_no_buy_generation_next_action=Use this classification to decide whether recent SIGNAL_EVAL rows are mostly strategy-threshold misses, generic hold/wait/no-condition rows, macro/unknown rows, or true BUY-like candidates that need progression RCA; do not relax entry filters, EntryDedup, DataFreshnessGuard, strategy activation, or live execution from this smoke alone.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[signal-eval-no-buy-generation] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Signal-eval no-buy generation smoke failed with exit code $LASTEXITCODE"
}
