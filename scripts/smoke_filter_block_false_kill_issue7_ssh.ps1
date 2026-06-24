param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [int]$ReviewDays = 14,
    [int]$Limit = 500
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

if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}

if ($Limit -lt 1 -or $Limit -gt 2000) {
    throw "Limit must be between 1 and 2000."
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
INTERVAL_CODE='__INTERVAL_CODE__'
REVIEW_DAYS='__REVIEW_DAYS__'
LIMIT='__LIMIT__'

fail() {
  echo "[issue7-filter-block-false-kill] FAIL: $*" >&2
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

export SYMBOL INTERVAL_CODE REVIEW_DAYS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"

python3 - <<'PY'
import csv
import json
import math
import os
import subprocess
import sys
from collections import Counter, defaultdict

symbol = os.environ["SYMBOL"].upper()
interval_code = os.environ["INTERVAL_CODE"]
review_days = int(os.environ["REVIEW_DAYS"])
limit = int(os.environ["LIMIT"])

sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(a.blocker, 'UNKNOWN') AS blocker,
  COALESCE(a.reason, 'N/A') AS reason,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.kline_source')), 'unknown') AS kline_source,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.stale_minutes')), 'N/A') AS stale_minutes,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.threshold_minutes')),
    CAST((CAST(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.interval_minutes')) AS SIGNED) * 2 + 15) AS CHAR),
    'N/A'
  ) AS threshold_minutes,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.latest_bar_open')), 'N/A') AS latest_bar_open,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.latest_bar_close_estimate')), 'N/A') AS latest_bar_close_estimate,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.now_utc')), DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s')) AS now_utc,
  CASE WHEN e.decision_id IS NULL THEN 0 ELSE 1 END AS has_runtime_evidence,
  CASE WHEN COALESCE(a.live_signal_id, e.live_signal_id) IS NULL THEN 0 ELSE 1 END AS has_live_signal,
  CASE
    WHEN COALESCE(
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.replayCandidateId')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.replayCandidateId'))
    ) IS NULL THEN 0 ELSE 1
  END AS has_replay_candidate_id,
  COALESCE(e.freshness_state, 'N/A') AS runtime_freshness_state,
  COALESCE(e.blocker_reason, 'N/A') AS runtime_blocker_reason,
  COALESCE(e.terminal_blocker, 'N/A') AS runtime_terminal_blocker,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayCollectorStatus')), 'N/A') AS replay_collector_status,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayCandidateStatus')), 'N/A') AS replay_candidate_status,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayCandidatePlanStatus')), 'N/A') AS replay_candidate_plan_status,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayHardGatePreviewStatus')), 'N/A') AS hard_gate_preview_status,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayRequiredNextAction')), 'N/A') AS replay_required_next_action,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.expected_r')),
    JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.expected_r')),
    JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.expectedR')),
    'N/A'
  ) AS expected_r,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.min_expected_r')),
    JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.min_expected_r')),
    JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.minExpectedR')),
    'N/A'
  ) AS min_expected_r,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.ev_reason')),
    JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.ev_reason')),
    'N/A'
  ) AS ev_reason,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.entryPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.entry')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entryPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entry')),
    JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.entryPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.entryPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.currentPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateEntry')),
    'N/A'
  ) AS candidate_entry,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.tpPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.tp')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.tpPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.tp')),
    JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.tpPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateTp')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.tp')),
    'N/A'
  ) AS candidate_tp,
  COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.slPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.sl')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.slPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.sl')),
    JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.slPrice')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateSl')),
    JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.sl')),
    'N/A'
  ) AS candidate_sl,
  CASE
    WHEN COALESCE(
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.entryPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.entry')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entryPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entry')),
      JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.entryPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.entryPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.currentPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateEntry'))
    ) IS NULL THEN 0 ELSE 1
  END AS has_explicit_entry,
  CASE
    WHEN COALESCE(
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.tpPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.tp')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.tpPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.tp')),
      JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.tpPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateTp')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.tp'))
    ) IS NULL THEN 0 ELSE 1
  END AS has_tp,
  CASE
    WHEN COALESCE(
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.slPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.sl')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.slPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.sl')),
      JSON_UNQUOTE(JSON_EXTRACT(e.policy_inputs_json, '$.slPrice')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.candidateSl')),
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.sl'))
    ) IS NULL THEN 0 ELSE 1
  END AS has_sl,
  CASE
    WHEN e.ev_result_json IS NOT NULL
      AND e.ev_result_json NOT LIKE '%NOT_EVALUATED%'
      AND e.ev_result_json <> '{{}}'
    THEN 1 ELSE 0
  END AS has_ev_snapshot,
  CASE
    WHEN COALESCE(e.oco_plan_created, 0) = 1
      OR JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.ocoCapable')) = 'true'
      OR JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.ocoPlanCreated')) = 'true'
    THEN 1 ELSE 0
  END AS has_oco_plan,
  CASE
    WHEN e.execution_preview_json IS NOT NULL
      AND (
           e.execution_preview_json LIKE '%duplicate%'
        OR e.execution_preview_json LIKE '%daily%'
        OR e.execution_preview_json LIKE '%eventRisk%'
        OR e.execution_preview_json LIKE '%exposure%'
        OR e.risk_gate_result_json IS NOT NULL
      )
    THEN 1 ELSE 0
  END AS has_hard_gate_snapshot,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '{interval_code}'
      AND k.source = 'okx'
      AND k.open_time <= a.event_time
    ORDER BY k.open_time DESC
    LIMIT 1
  ) AS derived_entry,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '{interval_code}'
      AND k.source = 'okx'
      AND k.open_time >= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
    ORDER BY k.open_time ASC
    LIMIT 1
  ) AS close_after_24h,
  (
    SELECT MAX(k.high_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '{interval_code}'
      AND k.source = 'okx'
      AND k.open_time > a.event_time
      AND k.open_time <= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
  ) AS max_high_24h,
  (
    SELECT MIN(k.low_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '{interval_code}'
      AND k.source = 'okx'
      AND k.open_time > a.event_time
      AND k.open_time <= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
  ) AS min_low_24h
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
LEFT JOIN bt_runtime_decision_evidence e ON e.decision_id = a.id
WHERE a.symbol = '{symbol}'
  AND a.interval_code = '{interval_code}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time DESC
LIMIT {limit}
"""

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

fields = [
    "audit_id", "event_time", "strategy_id", "interval_code", "blocker", "reason",
    "kline_source", "stale_minutes", "threshold_minutes", "latest_bar_open",
    "latest_bar_close_estimate", "now_utc", "has_runtime_evidence", "has_live_signal",
    "has_replay_candidate_id", "runtime_freshness_state", "runtime_blocker_reason",
    "runtime_terminal_blocker", "replay_collector_status", "replay_candidate_status",
    "replay_candidate_plan_status", "hard_gate_preview_status", "replay_required_next_action",
    "expected_r", "min_expected_r", "ev_reason", "candidate_entry", "candidate_tp", "candidate_sl",
    "has_explicit_entry", "has_tp", "has_sl",
    "has_ev_snapshot", "has_oco_plan", "has_hard_gate_snapshot", "derived_entry",
    "close_after_24h", "max_high_24h", "min_low_24h",
]
rows = [dict(zip(fields, row)) for row in csv.reader(proc.stdout.splitlines(), delimiter="\t")]

def flag(row, name):
    return str(row.get(name, "0")) == "1"

def num(row, name):
    raw = row.get(name)
    if raw in (None, "", "NULL", "None", "N/A"):
        return None
    try:
        return float(raw)
    except Exception:
        return None

def pct(value):
    return "N/A" if value is None or math.isnan(value) else f"{value:+.2f}%"

def compact(value, limit=160):
    text = str(value or "N/A").replace("\r", " ").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit - 3] + "..."

def ret_pct(row):
    entry = num(row, "derived_entry")
    close = num(row, "close_after_24h")
    if not entry or close is None:
        return None
    return (close - entry) / entry * 100.0

def mfe_pct(row):
    entry = num(row, "derived_entry")
    high = num(row, "max_high_24h")
    if not entry or high is None:
        return None
    return (high - entry) / entry * 100.0

def mae_pct(row):
    entry = num(row, "derived_entry")
    low = num(row, "min_low_24h")
    if not entry or low is None:
        return None
    return (low - entry) / entry * 100.0

def tp_hit(row):
    tp = num(row, "candidate_tp")
    high = num(row, "max_high_24h")
    if tp is None or high is None:
        return None
    return high >= tp

def sl_hit(row):
    sl = num(row, "candidate_sl")
    low = num(row, "min_low_24h")
    if sl is None or low is None:
        return None
    return low <= sl

def tp_sl_evaluable(row):
    return num(row, "candidate_entry") is not None and num(row, "candidate_tp") is not None and num(row, "candidate_sl") is not None

def is_replayable(row):
    return (
        (flag(row, "has_live_signal") or flag(row, "has_replay_candidate_id"))
        and flag(row, "has_explicit_entry")
        and flag(row, "has_tp")
        and flag(row, "has_sl")
        and flag(row, "has_ev_snapshot")
        and flag(row, "has_oco_plan")
        and flag(row, "has_hard_gate_snapshot")
    )

def missing_replay_fields(row):
    missing = []
    if not (flag(row, "has_live_signal") or flag(row, "has_replay_candidate_id")):
        missing.append("liveSignalId/replayCandidateId")
    if not flag(row, "has_explicit_entry"):
        missing.append("entry")
    if not flag(row, "has_tp"):
        missing.append("tp")
    if not flag(row, "has_sl"):
        missing.append("sl")
    if not flag(row, "has_ev_snapshot"):
        missing.append("evSnapshot")
    if not flag(row, "has_oco_plan"):
        missing.append("ocoPlan")
    if not flag(row, "has_hard_gate_snapshot"):
        missing.append("hardGateSnapshot")
    return missing

def stale_class(row):
    stale = num(row, "stale_minutes")
    threshold = num(row, "threshold_minutes")
    if stale is None or threshold is None:
        return "CONTEXT_MISSING_REVIEW"
    return "TRUE_STALE_KLINE" if stale > threshold else "CONTEXT_MISMATCH_REVIEW"

total_rows = len(rows)
matured_rows = [r for r in rows if ret_pct(r) is not None]
all_returns = [ret_pct(r) for r in matured_rows]
false_kill_rows = [r for r in matured_rows if ret_pct(r) is not None and ret_pct(r) > 0]
correct_block_rows = [r for r in matured_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
family_rows = defaultdict(list)
for row in matured_rows:
    family_rows[str(row.get("blocker") or "UNKNOWN")].append(row)

def avg(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None

def fmt_num(value):
    return "N/A" if value is None or math.isnan(value) else f"{value:.2f}"

def top(counter):
    return "none" if not counter else ",".join(f"{k}:{v}" for k, v in counter.most_common(8))

strategy_counts = Counter(str(r.get("strategy_id", "N/A")) for r in rows)
blocker_counts = Counter(str(r.get("blocker", "UNKNOWN")) for r in rows)
stale_class_counts = Counter(stale_class(r) for r in rows if str(r.get("blocker")) == "DataFreshnessGuard")
data_freshness_rows = [r for r in matured_rows if str(r.get("blocker")) == "DataFreshnessGuard"]
df_returns = [ret_pct(r) for r in data_freshness_rows]
df_false = [r for r in data_freshness_rows if ret_pct(r) is not None and ret_pct(r) > 0]
df_correct = [r for r in data_freshness_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
collector_status_counts = Counter(str(r.get("replay_collector_status", "N/A") or "N/A") for r in data_freshness_rows)
candidate_status_counts = Counter(str(r.get("replay_candidate_status", "N/A") or "N/A") for r in data_freshness_rows)
candidate_plan_status_counts = Counter(str(r.get("replay_candidate_plan_status", "N/A") or "N/A") for r in data_freshness_rows)
hard_gate_preview_status_counts = Counter(str(r.get("hard_gate_preview_status", "N/A") or "N/A") for r in data_freshness_rows)
required_next_action_counts = Counter(str(r.get("replay_required_next_action", "N/A") or "N/A") for r in data_freshness_rows)
df_stale_minutes = [num(r, "stale_minutes") for r in data_freshness_rows if num(r, "stale_minutes") is not None]
df_threshold_minutes = [num(r, "threshold_minutes") for r in data_freshness_rows if num(r, "threshold_minutes") is not None]
near_miss_rows = [
    r for r in data_freshness_rows
    if num(r, "stale_minutes") is not None
    and num(r, "threshold_minutes") is not None
    and num(r, "threshold_minutes") < num(r, "stale_minutes") <= num(r, "threshold_minutes") + max(30.0, num(r, "threshold_minutes") / 2.0)
]
recoverable_grace_rows = [
    r for r in data_freshness_rows
    if num(r, "stale_minutes") is not None
    and num(r, "threshold_minutes") is not None
    and num(r, "threshold_minutes") < num(r, "stale_minutes") <= num(r, "threshold_minutes") * 2.0
]
severe_stale_rows = [
    r for r in data_freshness_rows
    if num(r, "stale_minutes") is not None
    and num(r, "threshold_minutes") is not None
    and num(r, "stale_minutes") > num(r, "threshold_minutes") * 4.0
]
proxy_actionable_rows = [r for r in data_freshness_rows if r not in severe_stale_rows]
severe_stale_ids = {r.get("audit_id") for r in severe_stale_rows}
actionable_rows = [r for r in matured_rows if r.get("audit_id") not in severe_stale_ids]
actionable_false_kill_rows = [r for r in actionable_rows if ret_pct(r) is not None and ret_pct(r) > 0]
actionable_correct_block_rows = [r for r in actionable_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
outage_incident_keys = {
    "|".join([
        str(r.get("blocker") or "UNKNOWN"),
        str(r.get("interval_code") or "UNKNOWN"),
        str(r.get("kline_source") or "UNKNOWN"),
        str(r.get("latest_bar_open") or "UNKNOWN"),
        str(r.get("threshold_minutes") or "UNKNOWN"),
    ])
    for r in severe_stale_rows
}
actionable_family_rows = defaultdict(list)
for row in actionable_rows:
    actionable_family_rows[str(row.get("blocker") or "UNKNOWN")].append(row)
expected_value_rows = [r for r in actionable_rows if str(r.get("blocker")) == "ExpectedValueGate"]
expected_value_false = [r for r in expected_value_rows if ret_pct(r) is not None and ret_pct(r) > 0]
expected_value_correct = [r for r in expected_value_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
expected_value_expected_rs = [num(r, "expected_r") for r in expected_value_rows if num(r, "expected_r") is not None]
expected_value_min_expected_rs = [num(r, "min_expected_r") for r in expected_value_rows if num(r, "min_expected_r") is not None]
tp_sl_rows = [r for r in actionable_rows if tp_sl_evaluable(r)]
tp_sl_tp_hit_rows = [r for r in tp_sl_rows if tp_hit(r) is True]
tp_sl_sl_hit_rows = [r for r in tp_sl_rows if sl_hit(r) is True]
tp_sl_clean_tp_rows = [r for r in tp_sl_rows if tp_hit(r) is True and sl_hit(r) is not True]
tp_sl_ambiguous_rows = [r for r in tp_sl_rows if tp_hit(r) is True and sl_hit(r) is True]
tp_sl_clean_sl_rows = [r for r in tp_sl_rows if sl_hit(r) is True and tp_hit(r) is not True]
preview_only_rows = [
    r for r in data_freshness_rows
    if str(r.get("hard_gate_preview_status", "")) == "PREVIEW_ONLY_NOT_REPLAYABLE"
    or str(r.get("replay_collector_status", "")) == "CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE"
]
trace_only_rows = [
    r for r in data_freshness_rows
    if str(r.get("replay_collector_status", "")) in ("DISABLED", "SNAPSHOT_ONLY_NOT_REPLAYABLE")
]
if not data_freshness_rows:
    replay_input_stage = "NO_DATAFRESHNESS_SAMPLE"
elif any(is_replayable(r) for r in data_freshness_rows):
    replay_input_stage = "REPLAYABLE_CANDIDATES_PRESENT"
elif preview_only_rows:
    replay_input_stage = "PREVIEW_ONLY_NOT_REPLAYABLE"
elif trace_only_rows:
    replay_input_stage = "COLLECTOR_TRACE_ONLY_NOT_REPLAYABLE"
elif collector_status_counts.get("N/A", 0) == len(data_freshness_rows) and sum(flag(r, "has_replay_candidate_id") for r in data_freshness_rows) == 0:
    replay_input_stage = "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
else:
    replay_input_stage = "SNAPSHOT_FIELDS_MISSING"

print("[issue7-filter-block-false-kill] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"issue=7 symbol={symbol} intervalCode={interval_code} reviewDays={review_days} limit={limit}")
print("")
print("Filter Block False-Kill Summary:")
print(f"  filter_block_total_rows={total_rows}")
print(f"  filter_block_matured_rows={len(matured_rows)}")
print(f"  filter_block_false_kill_rows={len(false_kill_rows)}")
print(f"  filter_block_correct_block_rows={len(correct_block_rows)}")
print(f"  filter_block_false_kill_pct={pct((len(false_kill_rows) / len(matured_rows) * 100.0) if matured_rows else None)}")
print(f"  filter_block_avg_forward_24h_pct={pct(avg(all_returns))}")
print(f"  filter_block_avg_mfe_24h_pct={pct(avg(mfe_pct(r) for r in matured_rows))}")
print(f"  filter_block_avg_mae_24h_pct={pct(avg(mae_pct(r) for r in matured_rows))}")
print(f"  blocker_counts={top(blocker_counts)}")
print(f"  strategy_counts={top(strategy_counts)}")
print("")
print("Actionable False-Kill Summary:")
print("  definition=excludes DataFreshnessGuard severe-stale/outage rows from policy-error denominator; those rows stay blocked and route to collector/source freshness.")
print(f"  raw_filter_block_false_kill_pct={pct((len(false_kill_rows) / len(matured_rows) * 100.0) if matured_rows else None)}")
print(f"  severe_stale_outage_rows_excluded={len(severe_stale_rows)}")
print(f"  severe_stale_outage_incidents={len(outage_incident_keys)}")
print(f"  actionable_filter_block_matured_rows={len(actionable_rows)}")
print(f"  actionable_filter_block_false_kill_rows={len(actionable_false_kill_rows)}")
print(f"  actionable_filter_block_correct_block_rows={len(actionable_correct_block_rows)}")
print(f"  actionable_filter_block_false_kill_pct={pct((len(actionable_false_kill_rows) / len(actionable_rows) * 100.0) if actionable_rows else None)}")
print(f"  actionable_filter_block_avg_forward_24h_pct={pct(avg(ret_pct(r) for r in actionable_rows))}")
print("")
print("TP/SL Proxy Actionable Summary:")
print("  definition=counts actionable rows with candidate entry/TP/SL; clean TP rows hit TP within 24h without also touching SL in the same 24h OHLC window. Ambiguous rows require finer intrabar replay.")
print(f"  tp_sl_proxy_evaluable_rows={len(tp_sl_rows)}")
print(f"  tp_sl_proxy_tp_hit_rows={len(tp_sl_tp_hit_rows)}")
print(f"  tp_sl_proxy_sl_hit_rows={len(tp_sl_sl_hit_rows)}")
print(f"  tp_sl_proxy_clean_tp_rows={len(tp_sl_clean_tp_rows)}")
print(f"  tp_sl_proxy_clean_sl_rows={len(tp_sl_clean_sl_rows)}")
print(f"  tp_sl_proxy_ambiguous_rows={len(tp_sl_ambiguous_rows)}")
print(f"  tp_sl_proxy_clean_tp_false_kill_pct={pct((len(tp_sl_clean_tp_rows) / len(tp_sl_rows) * 100.0) if tp_sl_rows else None)}")
if tp_sl_rows and not tp_sl_clean_tp_rows:
    tp_sl_verdict = "NO_CLEAN_TP_FALSE_KILL_IN_ACTIONABLE_SAMPLE"
elif tp_sl_ambiguous_rows:
    tp_sl_verdict = "TP_SL_INTRABAR_REPLAY_REQUIRED"
else:
    tp_sl_verdict = "REVIEW_CLEAN_TP_FALSE_KILLS"
print(f"  tp_sl_proxy_verdict={tp_sl_verdict}")
print("")
print("False-Kill Source Ranking:")
for blocker, blocker_rows in sorted(family_rows.items(), key=lambda item: (-sum(1 for r in item[1] if ret_pct(r) is not None and ret_pct(r) > 0), item[0])):
    returns = [ret_pct(r) for r in blocker_rows]
    false_rows = [r for r in blocker_rows if ret_pct(r) is not None and ret_pct(r) > 0]
    correct_rows = [r for r in blocker_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
    replayable = sum(1 for r in blocker_rows if is_replayable(r))
    print(
        "  - "
        f"blocker={blocker} rows={len(blocker_rows)} falseKillRows={len(false_rows)} "
        f"correctBlockRows={len(correct_rows)} falseKillPct={pct((len(false_rows) / len(blocker_rows) * 100.0) if blocker_rows else None)} "
        f"avgForward24h={pct(avg(returns))} avgMfe24h={pct(avg(mfe_pct(r) for r in blocker_rows))} "
        f"avgMae24h={pct(avg(mae_pct(r) for r in blocker_rows))} replayableCandidateRows={replayable}"
    )
print("")
print("Actionable False-Kill Source Ranking:")
if not actionable_family_rows:
    print("  none")
for blocker, blocker_rows in sorted(actionable_family_rows.items(), key=lambda item: (-sum(1 for r in item[1] if ret_pct(r) is not None and ret_pct(r) > 0), item[0])):
    returns = [ret_pct(r) for r in blocker_rows]
    false_rows = [r for r in blocker_rows if ret_pct(r) is not None and ret_pct(r) > 0]
    correct_rows = [r for r in blocker_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
    replayable = sum(1 for r in blocker_rows if is_replayable(r))
    print(
        "  - "
        f"blocker={blocker} rows={len(blocker_rows)} falseKillRows={len(false_rows)} "
        f"correctBlockRows={len(correct_rows)} falseKillPct={pct((len(false_rows) / len(blocker_rows) * 100.0) if blocker_rows else None)} "
        f"avgForward24h={pct(avg(returns))} avgMfe24h={pct(avg(mfe_pct(r) for r in blocker_rows))} "
        f"avgMae24h={pct(avg(mae_pct(r) for r in blocker_rows))} replayableCandidateRows={replayable}"
    )
print("")
print("DataFreshnessGuard RCA:")
print(f"  data_freshness_rows={len(data_freshness_rows)}")
print(f"  data_freshness_false_kill_rows={len(df_false)}")
print(f"  data_freshness_correct_block_rows={len(df_correct)}")
print(f"  data_freshness_false_kill_pct={pct((len(df_false) / len(data_freshness_rows) * 100.0) if data_freshness_rows else None)}")
print(f"  data_freshness_avg_forward_24h_pct={pct(avg(df_returns))}")
print(f"  data_freshness_stale_class_counts={top(stale_class_counts)}")
print(f"  data_freshness_runtime_evidence_rows={sum(flag(r, 'has_runtime_evidence') for r in data_freshness_rows)}")
print(f"  data_freshness_live_signal_rows={sum(flag(r, 'has_live_signal') for r in data_freshness_rows)}")
print(f"  data_freshness_replay_candidate_id_rows={sum(flag(r, 'has_replay_candidate_id') for r in data_freshness_rows)}")
print(f"  data_freshness_explicit_entry_rows={sum(flag(r, 'has_explicit_entry') for r in data_freshness_rows)}")
print(f"  data_freshness_tp_rows={sum(flag(r, 'has_tp') for r in data_freshness_rows)}")
print(f"  data_freshness_sl_rows={sum(flag(r, 'has_sl') for r in data_freshness_rows)}")
print(f"  data_freshness_ev_snapshot_rows={sum(flag(r, 'has_ev_snapshot') for r in data_freshness_rows)}")
print(f"  data_freshness_oco_plan_rows={sum(flag(r, 'has_oco_plan') for r in data_freshness_rows)}")
print(f"  data_freshness_hard_gate_snapshot_rows={sum(flag(r, 'has_hard_gate_snapshot') for r in data_freshness_rows)}")
print(f"  data_freshness_complete_replayable_candidate_rows={sum(1 for r in data_freshness_rows if is_replayable(r))}")
print(f"  data_freshness_preview_only_input_rows={len(preview_only_rows)}")
print(f"  data_freshness_trace_only_rows={len(trace_only_rows)}")
print(f"  replay_input_stage={replay_input_stage}")
print(f"  data_freshness_stale_minutes_min={fmt_num(min(df_stale_minutes) if df_stale_minutes else None)}")
print(f"  data_freshness_stale_minutes_avg={fmt_num(avg(df_stale_minutes))}")
print(f"  data_freshness_stale_minutes_max={fmt_num(max(df_stale_minutes) if df_stale_minutes else None)}")
print(f"  data_freshness_threshold_minutes_avg={fmt_num(avg(df_threshold_minutes))}")
print(f"  data_freshness_near_miss_rows={len(near_miss_rows)}")
print(f"  data_freshness_recoverable_grace_rows={len(recoverable_grace_rows)}")
print(f"  data_freshness_severe_stale_rows={len(severe_stale_rows)}")
print(f"  data_freshness_proxy_actionable_rows={len(proxy_actionable_rows)}")
print(f"  collector_status_counts={top(collector_status_counts)}")
print(f"  candidate_status_counts={top(candidate_status_counts)}")
print(f"  candidate_plan_status_counts={top(candidate_plan_status_counts)}")
print(f"  hard_gate_preview_status_counts={top(hard_gate_preview_status_counts)}")
print(f"  replay_required_next_action_counts={top(required_next_action_counts)}")
print("")
print("DataFreshness Guard Optimization Counterfactual:")
print("  definition=releaseRows means DataFreshnessGuard would not terminal-block under the candidate stale threshold; hard gates and replay snapshots are still required before live policy review.")
candidate_thresholds = [
    ("current_2x_plus_15", lambda base, interval: base),
    ("grace_3x_plus_15", lambda base, interval: 3.0 * interval + 15.0),
    ("grace_4x_plus_15", lambda base, interval: 4.0 * interval + 15.0),
    ("grace_6x_plus_15", lambda base, interval: 6.0 * interval + 15.0),
    ("grace_12x_plus_15", lambda base, interval: 12.0 * interval + 15.0),
]
for label, threshold_fn in candidate_thresholds:
    released = []
    for row in data_freshness_rows:
        stale = num(row, "stale_minutes")
        threshold = num(row, "threshold_minutes")
        interval_min = num(row, "threshold_minutes")
        if threshold is not None:
            interval_min = max((threshold - 15.0) / 2.0, 1.0)
        if stale is None or threshold is None or interval_min is None:
            continue
        candidate_threshold = threshold_fn(threshold, interval_min)
        if stale <= candidate_threshold:
            released.append(row)
    released_false = [r for r in released if ret_pct(r) is not None and ret_pct(r) > 0]
    released_correct = [r for r in released if ret_pct(r) is not None and ret_pct(r) <= 0]
    print(
        "  - "
        f"candidate={label} releaseRows={len(released)} falseKillReleased={len(released_false)} "
        f"correctBlockReleased={len(released_correct)} avgReleasedForward24h={pct(avg(ret_pct(r) for r in released))}"
    )
if data_freshness_rows and len(severe_stale_rows) == len(data_freshness_rows):
    optimization_verdict = "DO_NOT_RELAX_GRACE_FIX_COLLECTOR_OR_SOURCE_OUTAGE"
elif recoverable_grace_rows and not df_correct:
    optimization_verdict = "REVIEW_SMALL_GRACE_ONLY_AFTER_REPLAYABLE_SNAPSHOTS"
elif near_miss_rows:
    optimization_verdict = "REVIEW_NEAR_MISS_GRACE_WITH_CORRECT_BLOCK_LEAKAGE"
else:
    optimization_verdict = "COLLECT_FRESH_REPLAYABLE_ROWS_BEFORE_OPTIMIZATION"
print(f"  data_freshness_guard_optimization_verdict={optimization_verdict}")
print("  false_kill_reduction_path=reduce future false kills by restoring/monitoring current kline freshness and collecting replay snapshots; do not convert severe stale outage rows into live passes.")
print("")
print("ExpectedValueGate Optimization Counterfactual:")
print("  definition=releaseRows means ExpectedValueGate would not terminal-block at the candidate minExpectedR; this is report-only and still requires operator review before policy changes.")
print(f"  expected_value_rows={len(expected_value_rows)}")
print(f"  expected_value_false_kill_rows={len(expected_value_false)}")
print(f"  expected_value_correct_block_rows={len(expected_value_correct)}")
print(f"  expected_value_false_kill_pct={pct((len(expected_value_false) / len(expected_value_rows) * 100.0) if expected_value_rows else None)}")
print(f"  expected_value_avg_forward_24h_pct={pct(avg(ret_pct(r) for r in expected_value_rows))}")
print(f"  expected_value_expected_r_min={fmt_num(min(expected_value_expected_rs) if expected_value_expected_rs else None)}")
print(f"  expected_value_expected_r_avg={fmt_num(avg(expected_value_expected_rs))}")
print(f"  expected_value_expected_r_max={fmt_num(max(expected_value_expected_rs) if expected_value_expected_rs else None)}")
print(f"  expected_value_min_expected_r_avg={fmt_num(avg(expected_value_min_expected_rs))}")
ev_candidates = [0.0, 0.05, 0.10, 0.15, 0.20]
best_ev_candidate = None
best_ev_release_rows = []
for candidate_min in ev_candidates:
    released = []
    for row in expected_value_rows:
        expected_r = num(row, "expected_r")
        if expected_r is None:
            continue
        if expected_r > 0 and expected_r >= candidate_min:
            released.append(row)
    released_false = [r for r in released if ret_pct(r) is not None and ret_pct(r) > 0]
    released_correct = [r for r in released if ret_pct(r) is not None and ret_pct(r) <= 0]
    released_clean_tp = [r for r in released if tp_hit(r) is True and sl_hit(r) is not True]
    released_non_clean_tp = [r for r in released if not (tp_hit(r) is True and sl_hit(r) is not True)]
    if released_clean_tp and not released_non_clean_tp and best_ev_candidate is None:
        best_ev_candidate = candidate_min
        best_ev_release_rows = released
    print(
        "  - "
        f"candidate=minExpectedR_{candidate_min:.2f} releaseRows={len(released)} "
        f"falseKillReleased={len(released_false)} correctBlockReleased={len(released_correct)} "
        f"tpSlCleanTpReleased={len(released_clean_tp)} tpSlNonCleanReleased={len(released_non_clean_tp)} "
        f"avgReleasedForward24h={pct(avg(ret_pct(r) for r in released))}"
    )
if not expected_value_rows:
    ev_verdict = "NO_EXPECTED_VALUE_SAMPLE"
elif best_ev_candidate is not None:
    ev_verdict = f"REVIEW_MIN_EXPECTED_R_{best_ev_candidate:.2f}_TP_SL_SHADOW_ONLY"
elif expected_value_false:
    ev_verdict = "EXPECTED_VALUE_FALSE_KILL_HIGH_BUT_CORRECT_BLOCK_LEAKAGE_REVIEW"
else:
    ev_verdict = "EXPECTED_VALUE_GATE_OK"
print(f"  expected_value_gate_optimization_verdict={ev_verdict}")
if best_ev_release_rows:
    best_release_ids = {r.get("audit_id") for r in best_ev_release_rows}
    projected_rows = [r for r in actionable_rows if r.get("audit_id") not in best_release_ids]
    projected_false = [r for r in projected_rows if ret_pct(r) is not None and ret_pct(r) > 0]
    projected_correct = [r for r in projected_rows if ret_pct(r) is not None and ret_pct(r) <= 0]
    projected_family_rows = defaultdict(list)
    for row in projected_rows:
        projected_family_rows[str(row.get("blocker") or "UNKNOWN")].append(row)
    projected_next_blocker = "NONE"
    if projected_family_rows:
        projected_next_blocker = max(projected_family_rows.items(), key=lambda item: sum(1 for r in item[1] if ret_pct(r) is not None and ret_pct(r) > 0))[0]
    print(f"  expected_value_projected_actionable_rows_after_review={len(projected_rows)}")
    print(f"  expected_value_projected_actionable_false_kill_rows_after_review={len(projected_false)}")
    print(f"  expected_value_projected_actionable_correct_block_rows_after_review={len(projected_correct)}")
    print(f"  expected_value_projected_actionable_false_kill_pct_after_review={pct((len(projected_false) / len(projected_rows) * 100.0) if projected_rows else 0.0)}")
    print(f"  expected_value_projected_next_blocker_after_review={projected_next_blocker}")
else:
    print("  expected_value_projected_actionable_false_kill_pct_after_review=N/A")
    print("  expected_value_projected_next_blocker_after_review=UNKNOWN")
print("")
print("Replayable Candidate Evidence:")
print("  candidate_definition=entry and forward return are replayable proxy fields; live relaxation still requires liveSignal/replayCandidateId plus entry/TP/SL, evaluated EV, OCO, and hard-gate snapshots.")
print("  should_have_passed_proxy=forward_24h_pct > 0; this is not a live-policy pass verdict.")
for row in matured_rows[:12]:
    ret = ret_pct(row)
    missing = missing_replay_fields(row)
    print(
        "  - "
        f"auditId={row.get('audit_id')} time={row.get('event_time')} strategy={row.get('strategy_id')} "
        f"blocker={row.get('blocker')} entry={row.get('derived_entry')} closeAfter24h={row.get('close_after_24h')} "
        f"forward24h={pct(ret)} shouldHavePassedProxy={str(ret is not None and ret > 0).lower()} "
        f"replayableCandidate={str(is_replayable(row)).lower()} missingReplayFields={json.dumps(missing, separators=(',', ':'))} "
        f"collector={row.get('replay_collector_status')} candidateStatus={row.get('replay_candidate_status')} "
        f"planStatus={row.get('replay_candidate_plan_status')} hardGatePreview={row.get('hard_gate_preview_status')} "
        f"expectedR={row.get('expected_r')} minExpectedR={row.get('min_expected_r')} evReason={row.get('ev_reason')} "
        f"candidateEntry={row.get('candidate_entry')} candidateTp={row.get('candidate_tp')} candidateSl={row.get('candidate_sl')} "
        f"tpHit={str(tp_hit(row)).lower()} slHit={str(sl_hit(row)).lower()} "
        f"staleClass={stale_class(row)} staleMinutes={row.get('stale_minutes')} thresholdMinutes={row.get('threshold_minutes')} "
        f"latestBarOpen={row.get('latest_bar_open')} blockReason={compact(row.get('reason'))}"
    )
print("")
print("Actionable Candidate Evidence:")
for row in actionable_rows[:12]:
    ret = ret_pct(row)
    missing = missing_replay_fields(row)
    print(
        "  - "
        f"auditId={row.get('audit_id')} time={row.get('event_time')} strategy={row.get('strategy_id')} "
        f"blocker={row.get('blocker')} entry={row.get('derived_entry')} closeAfter24h={row.get('close_after_24h')} "
        f"forward24h={pct(ret)} shouldHavePassedProxy={str(ret is not None and ret > 0).lower()} "
        f"replayableCandidate={str(is_replayable(row)).lower()} missingReplayFields={json.dumps(missing, separators=(',', ':'))} "
        f"expectedR={row.get('expected_r')} minExpectedR={row.get('min_expected_r')} evReason={row.get('ev_reason')} "
        f"candidateEntry={row.get('candidate_entry')} candidateTp={row.get('candidate_tp')} candidateSl={row.get('candidate_sl')} "
        f"tpHit={str(tp_hit(row)).lower()} slHit={str(sl_hit(row)).lower()} "
        f"staleClass={stale_class(row)} blockReason={compact(row.get('reason'))}"
    )
print("")
print("Conclusion:")
if total_rows == 0:
    recommendation = "NO_FILTER_BLOCK_SAMPLE"
elif replay_input_stage == "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE":
    recommendation = "DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_PRE_REPLAY_COLLECTOR"
elif replay_input_stage == "COLLECTOR_TRACE_ONLY_NOT_REPLAYABLE":
    recommendation = "DATAFRESHNESS_COLLECTOR_TRACE_ONLY_REPLAY_SNAPSHOTS_MISSING"
elif replay_input_stage == "PREVIEW_ONLY_NOT_REPLAYABLE":
    recommendation = "DATAFRESHNESS_PREVIEW_ONLY_REPLAY_SNAPSHOTS_NOT_EVALUATED"
elif data_freshness_rows and not any(is_replayable(r) for r in data_freshness_rows):
    recommendation = "DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_BUT_REPLAY_SNAPSHOTS_MISSING"
elif data_freshness_rows and df_false:
    recommendation = "REVIEW_DATAFRESHNESS_REPLAYABLE_CANDIDATES_BEFORE_POLICY_CHANGE"
else:
    recommendation = "COLLECT_MORE_FILTER_BLOCK_SAMPLE"
print(f"  issue7_recommendation={recommendation}")
if actionable_rows:
    top_actionable = max(actionable_family_rows.items(), key=lambda item: sum(1 for r in item[1] if ret_pct(r) is not None and ret_pct(r) > 0))[0]
    print(f"  issue7_actionable_next_blocker={top_actionable}")
else:
    print("  issue7_actionable_next_blocker=NONE")
print(f"  issue7_expected_value_gate_verdict={ev_verdict}")
print(f"  issue7_tp_sl_proxy_verdict={tp_sl_verdict}")
print("  safe_guard_optimization_candidates=[\"do not relax severe stale/outage rows; fix collector/source freshness first\",\"review small grace only for near-miss stale rows after replay snapshots prove EV/OCO/hard-gates would pass\",\"keep terminal block when staleNow/noData/queryFailed is current\"]")
print("  live_relaxation_missing_evidence=[\"complete replayable DataFreshness rows\",\"liveSignalId or replayCandidateId\",\"entry/TP/SL plan\",\"evaluated EV snapshot\",\"OCO plan snapshot\",\"duplicate/daily-cap/exposure/event-risk hard-gate snapshot\",\"counterfactual replay removing only DataFreshnessGuard\"]")
print("  issue7_live_relaxation_allowed=false")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[issue7-filter-block-false-kill] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Issue #7 filter-block false-kill smoke failed with exit code $LASTEXITCODE"
}
