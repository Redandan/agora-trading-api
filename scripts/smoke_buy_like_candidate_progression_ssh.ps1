param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 7,
    [int]$FollowupHours = 6,
    [int]$Limit = 10,
    [int]$MaxCandidateRows = 500
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

if ($FollowupHours -lt 1 -or $FollowupHours -gt 48) {
    throw "FollowupHours must be between 1 and 48."
}

if ($Limit -lt 1 -or $Limit -gt 50) {
    throw "Limit must be between 1 and 50."
}

if ($MaxCandidateRows -lt 1 -or $MaxCandidateRows -gt 2000) {
    throw "MaxCandidateRows must be between 1 and 2000."
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

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
REVIEW_DAYS='__REVIEW_DAYS__'
FOLLOWUP_HOURS='__FOLLOWUP_HOURS__'
LIMIT='__LIMIT__'
MAX_CANDIDATE_ROWS='__MAX_CANDIDATE_ROWS__'

fail() {
  echo "[buy-like-candidate-progression] FAIL: $*" >&2
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
export SYMBOL REVIEW_DAYS FOLLOWUP_HOURS LIMIT MAX_CANDIDATE_ROWS MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import datetime as dt
import os
import subprocess
import sys
from collections import Counter

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
followup_hours = int(os.environ["FOLLOWUP_HOURS"])
limit = int(os.environ["LIMIT"])
max_candidate_rows = int(os.environ["MAX_CANDIDATE_ROWS"])

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

def parse_time(value):
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%S")

def same_key(candidate, row):
    return (
        candidate.get("strategy_id") == row.get("strategy_id")
        and candidate.get("interval_code") == row.get("interval_code")
    )

def bucket_followup(row):
    event_type = row.get("event_type", "")
    blocker = row.get("blocker", "") or "UNKNOWN"
    if event_type == "FILTER_BLOCK":
        return f"FILTER_BLOCK:{blocker}"
    if event_type == "ENTRY_SKIP":
        return f"ENTRY_SKIP:{blocker}"
    if event_type in ("AUTOTRADE_OK", "AUTOTRADE_FAIL", "SIGNAL_BUY"):
        return event_type
    return event_type or "UNKNOWN"

symbol_sql = esc(symbol)
candidate_condition = """
(
  a.event_type = 'SIGNAL_BUY'
  OR (
    a.event_type = 'SIGNAL_EVAL'
    AND (
      UPPER(COALESCE(a.outcome,'')) LIKE '%BUY%'
      OR UPPER(COALESCE(a.reason,'')) LIKE '%BUY%'
      OR UPPER(COALESCE(a.reason,'')) LIKE '%LONG%'
      OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side')), '')) IN ('LONG','BUY')
      OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.signal')), '')) IN ('LONG','BUY')
    )
  )
)
"""
terminal_events = "'SIGNAL_BUY','FILTER_BLOCK','ENTRY_SKIP','AUTOTRADE_OK','AUTOTRADE_FAIL'"

candidate_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  a.event_type,
  COALESCE(a.outcome, '') AS outcome,
  COALESCE(a.blocker, '') AS blocker,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND {candidate_condition}
ORDER BY a.event_time DESC
LIMIT {max_candidate_rows}
"""

candidate_count_sql = f"""
SELECT COUNT(*)
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND {candidate_condition}
"""

followup_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  a.event_type,
  COALESCE(a.outcome, '') AS outcome,
  COALESCE(a.blocker, '') AS blocker,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type IN ({terminal_events})
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time ASC
"""

candidate_type_sql = f"""
SELECT a.event_type, COALESCE(a.strategy_id, -1) AS strategy_id, COALESCE(a.interval_code, 'N/A') AS interval_code, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND {candidate_condition}
GROUP BY a.event_type, strategy_id, interval_code
ORDER BY cnt DESC, a.event_type ASC, strategy_id ASC, interval_code ASC
LIMIT {limit}
"""

candidate_rows = run_query(candidate_sql)
candidate_total_rows = run_query(candidate_count_sql)
followup_rows = run_query(followup_sql)
candidate_type_rows = run_query(candidate_type_sql)

candidate_fields = ["id", "event_time", "strategy_id", "interval_code", "event_type", "outcome", "blocker", "reason"]
followup_fields = ["id", "event_time", "strategy_id", "interval_code", "event_type", "outcome", "blocker", "reason"]
candidates = [dict(zip(candidate_fields, row)) for row in candidate_rows]
followups = [dict(zip(followup_fields, row)) for row in followup_rows]

for row in candidates:
    row["dt"] = parse_time(row["event_time"])
for row in followups:
    row["dt"] = parse_time(row["event_time"])

window = dt.timedelta(hours=followup_hours)
classification = Counter()
event_classification = Counter()
classification_by_strategy_interval = Counter()
examples = []
no_terminal_examples = []

for candidate in candidates:
    if candidate.get("event_type") == "SIGNAL_BUY":
        classification["SIGNAL_BUY_CANDIDATE_ROW"] += 1
        event_classification["SIGNAL_BUY"] += 1
        classification_by_strategy_interval[("SIGNAL_BUY_CANDIDATE_ROW", candidate.get("strategy_id", "-1"), candidate.get("interval_code", "N/A"))] += 1
        if len(examples) < limit:
            examples.append((candidate, None, "SIGNAL_BUY_CANDIDATE_ROW"))
        continue
    matched = None
    for row in followups:
        if row["dt"] <= candidate["dt"]:
            continue
        if row["dt"] > candidate["dt"] + window:
            break
        if same_key(candidate, row):
            matched = row
            break
    if matched is None:
        classification["NO_TERMINAL_FOLLOWUP"] += 1
        event_classification["NO_TERMINAL_FOLLOWUP"] += 1
        classification_by_strategy_interval[("NO_TERMINAL_FOLLOWUP", candidate.get("strategy_id", "-1"), candidate.get("interval_code", "N/A"))] += 1
        if len(examples) < limit:
            examples.append((candidate, None, "NO_TERMINAL_FOLLOWUP"))
        if len(no_terminal_examples) < limit:
            no_terminal_examples.append(candidate)
    else:
        bucket = bucket_followup(matched)
        classification[bucket] += 1
        event_classification[matched.get("event_type", "UNKNOWN") or "UNKNOWN"] += 1
        classification_by_strategy_interval[(bucket, candidate.get("strategy_id", "-1"), candidate.get("interval_code", "N/A"))] += 1
        if len(examples) < limit:
            examples.append((candidate, matched, bucket))

candidate_total = int(candidate_total_rows[0][0]) if candidate_total_rows and candidate_total_rows[0] else 0
sampled_candidates = len(candidates)
no_followup = classification["NO_TERMINAL_FOLLOWUP"]
filter_followup = sum(v for k, v in classification.items() if k.startswith("FILTER_BLOCK:"))
entry_skip_followup = sum(v for k, v in classification.items() if k.startswith("ENTRY_SKIP:"))
signal_buy_rows = classification["SIGNAL_BUY_CANDIDATE_ROW"] + classification["SIGNAL_BUY"]
autotrade_followup = classification["AUTOTRADE_OK"] + classification["AUTOTRADE_FAIL"]

if candidate_total == 0:
    recommendation = "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW"
elif no_followup * 100 >= max(1, sampled_candidates) * 80:
    recommendation = "BUY_LIKE_NO_TERMINAL_FOLLOWUP_DOMINATES"
elif entry_skip_followup >= max(filter_followup, autotrade_followup, signal_buy_rows, no_followup):
    recommendation = "BUY_LIKE_TO_ENTRY_SKIP_REVIEW"
elif filter_followup >= max(entry_skip_followup, autotrade_followup, signal_buy_rows, no_followup):
    recommendation = "BUY_LIKE_TO_FILTER_BLOCK_REVIEW"
elif signal_buy_rows + autotrade_followup > 0:
    recommendation = "BUY_LIKE_PIPELINE_HAS_TERMINAL_FOLLOWUP"
else:
    recommendation = "BUY_LIKE_PIPELINE_MIXED_REVIEW"

print("[buy-like-candidate-progression] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} followupHours={followup_hours} limit={limit} maxCandidateRows={max_candidate_rows}")
print("")
print("Buy-like Candidate Progression Summary:")
print("  excluded_event_type=ATTENTION_HIT")
print("  excluded_terminal_candidate_event=ENTRY_SKIP")
print(f"  buy_like_candidate_rows={candidate_total}")
print(f"  sampled_buy_like_candidate_rows={sampled_candidates}")
print(f"  followup_terminal_event_rows={len(followups)}")
print(f"  no_terminal_followup_rows={no_followup}")
print(f"  filter_block_followup_rows={filter_followup}")
print(f"  entry_skip_followup_rows={entry_skip_followup}")
print(f"  signal_buy_rows={signal_buy_rows}")
print(f"  autotrade_followup_rows={autotrade_followup}")
print("")
print("buy_like_followup_classification:")
if not classification:
    print("  - NONE=0")
else:
    for name, count in classification.most_common(limit):
        print(f"  - {name}={count}")
print("buy_like_followup_event_types:")
if not event_classification:
    print("  - NONE=0")
else:
    for name, count in event_classification.most_common(limit):
        print(f"  - {name}={count}")
print("buy_like_candidate_type_distribution:")
if not candidate_type_rows:
    print("  - NONE=0")
else:
    for row in candidate_type_rows:
        event_type = row[0] if len(row) > 0 else "UNKNOWN"
        strategy = row[1] if len(row) > 1 else "UNKNOWN"
        interval = row[2] if len(row) > 2 else "N/A"
        count = row[3] if len(row) > 3 else "0"
        print(f"  - event={event_type} strategy={strategy} interval={interval} count={count}")
print("buy_like_followup_classification_by_strategy_interval:")
if not classification_by_strategy_interval:
    print("  - NONE=0")
else:
    ranked = sorted(
        classification_by_strategy_interval.items(),
        key=lambda item: (-item[1], item[0][0], item[0][1], item[0][2])
    )
    for (classification_name, strategy, interval), count in ranked[:limit]:
        print(f"  - classification={classification_name} strategy={strategy} interval={interval} count={count}")
print("Examples:")
for candidate, follow, bucket in examples:
    if follow is None:
        print(f"  - candidateAuditId={candidate['id']} time={candidate['event_time']} strategy={candidate['strategy_id']} interval={candidate['interval_code']} event={candidate['event_type']} classification={bucket} reason={candidate['reason'][:120]}")
    else:
        delta_minutes = int((follow["dt"] - candidate["dt"]).total_seconds() // 60)
        print(f"  - candidateAuditId={candidate['id']} time={candidate['event_time']} strategy={candidate['strategy_id']} interval={candidate['interval_code']} event={candidate['event_type']} classification={bucket} followupAuditId={follow['id']} followupEvent={follow['event_type']} followupMinutes={delta_minutes} blocker={follow['blocker'] or 'NONE'} reason={follow['reason'][:120]}")
print("NoTerminalFollowupExamples:")
if not no_terminal_examples:
    print("  - NONE=0")
else:
    for candidate in no_terminal_examples:
        print(f"  - candidateAuditId={candidate['id']} time={candidate['event_time']} strategy={candidate['strategy_id']} interval={candidate['interval_code']} event={candidate['event_type']} classification=NO_TERMINAL_FOLLOWUP reason={candidate['reason'][:120]}")
print("")
print("Conclusion:")
print(f"  buy_like_candidate_progression_recommendation={recommendation}")
print("  buy_like_candidate_progression_next_action=Use this classification to inspect candidate-to-terminal-event loss before changing entry filters, DataFreshnessGuard, EntryDedup, strategy activation, or live execution.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[buy-like-candidate-progression] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__FOLLOWUP_HOURS__", [string]$FollowupHours).
    Replace("__LIMIT__", [string]$Limit).
    Replace("__MAX_CANDIDATE_ROWS__", [string]$MaxCandidateRows)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Buy-like candidate progression smoke failed with exit code $LASTEXITCODE"
}
