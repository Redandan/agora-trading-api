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
    "runtime_terminal_blocker", "has_explicit_entry", "has_tp", "has_sl",
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

def top(counter):
    return "none" if not counter else ",".join(f"{k}:{v}" for k, v in counter.most_common(8))

strategy_counts = Counter(str(r.get("strategy_id", "N/A")) for r in rows)
blocker_counts = Counter(str(r.get("blocker", "UNKNOWN")) for r in rows)
stale_class_counts = Counter(stale_class(r) for r in rows if str(r.get("blocker")) == "DataFreshnessGuard")
data_freshness_rows = [r for r in matured_rows if str(r.get("blocker")) == "DataFreshnessGuard"]
df_returns = [ret_pct(r) for r in data_freshness_rows]
df_false = [r for r in data_freshness_rows if ret_pct(r) is not None and ret_pct(r) > 0]
df_correct = [r for r in data_freshness_rows if ret_pct(r) is not None and ret_pct(r) <= 0]

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
        f"staleClass={stale_class(row)} staleMinutes={row.get('stale_minutes')} thresholdMinutes={row.get('threshold_minutes')} "
        f"latestBarOpen={row.get('latest_bar_open')} blockReason={compact(row.get('reason'))}"
    )
print("")
print("Conclusion:")
if total_rows == 0:
    recommendation = "NO_FILTER_BLOCK_SAMPLE"
elif data_freshness_rows and not any(is_replayable(r) for r in data_freshness_rows):
    recommendation = "DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_BUT_REPLAY_SNAPSHOTS_MISSING"
elif data_freshness_rows and df_false:
    recommendation = "REVIEW_DATAFRESHNESS_REPLAYABLE_CANDIDATES_BEFORE_POLICY_CHANGE"
else:
    recommendation = "COLLECT_MORE_FILTER_BLOCK_SAMPLE"
print(f"  issue7_recommendation={recommendation}")
print("  safe_guard_optimization_candidates=[\"review whether BTCUSDT 1h L0 freshness should require current source failure, not only historical latestOpen lag\",\"consider source/interval-specific freshness grace or fallback only after replay evidence proves EV/OCO/hard-gates would pass\",\"keep terminal block when staleNow/noData/queryFailed is current\"]")
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
