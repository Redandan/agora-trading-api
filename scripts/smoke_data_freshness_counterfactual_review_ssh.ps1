param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 14,
    [int]$Limit = 200
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

if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
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
LIMIT='__LIMIT__'

fail() {
  echo "[data-freshness-counterfactual-review] FAIL: $*" >&2
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

export SYMBOL REVIEW_DAYS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"

python3 - <<'PY'
import csv
import json
import math
import os
import subprocess
import sys
from collections import Counter

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
limit = int(os.environ["LIMIT"])

sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  CASE WHEN e.id IS NULL THEN 0 ELSE 1 END AS has_runtime_evidence,
  CASE WHEN e.live_signal_id IS NULL THEN 0 ELSE 1 END AS has_live_signal,
  CASE
    WHEN COALESCE(
      JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.replayCandidateId')),
      JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.replayCandidateId'))
    ) IS NULL THEN 0 ELSE 1
  END AS has_replay_candidate_id,
  CASE WHEN COALESCE(e.intent_created, 0) = 1 THEN 1 ELSE 0 END AS intent_created,
  CASE WHEN COALESCE(e.oco_plan_created, 0) = 1 THEN 1 ELSE 0 END AS oco_plan_created,
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
    WHEN JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.ev_result.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayHardGatePreviewStatus')) = 'PREVIEW_ONLY_NOT_REPLAYABLE'
    THEN 1 ELSE 0
  END AS has_ev_preview_only,
  CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.status')) IN ('PASS','EV_PASS','READY')
      OR CAST(JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.expected_r')) AS DECIMAL(18,8)) > 0
      OR CAST(JSON_UNQUOTE(JSON_EXTRACT(e.ev_result_json, '$.expectedR')) AS DECIMAL(18,8)) > 0
    THEN 1 ELSE 0
  END AS ev_pass_like,
  CASE
    WHEN COALESCE(e.oco_plan_created, 0) = 1
      OR JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.ocoPlan.status')) NOT IN ('', 'NOT_CREATED')
      OR JSON_UNQUOTE(JSON_EXTRACT(e.execution_preview_json, '$.ocoCapable')) = 'true'
      OR JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.ocoPlanCreated')) = 'true'
    THEN 1 ELSE 0
  END AS has_oco_plan,
  CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.oco_preflight.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
    THEN 1 ELSE 0
  END AS has_oco_preview_only,
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
  CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.duplicate_gate.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.daily_cap.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.exposure_gate.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.event_risk.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.open_position.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
      OR JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.loss_budget.status')) = 'NOT_EVALUATED_REPLAY_INPUT_ONLY'
    THEN 1 ELSE 0
  END AS has_hard_gate_preview_only,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time <= a.event_time
    ORDER BY k.open_time DESC
    LIMIT 1
  ) AS derived_entry,
  (
    SELECT k.close_price
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time >= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
    ORDER BY k.open_time ASC
    LIMIT 1
  ) AS close_after_24h,
  (
    SELECT MAX(k.high_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time > a.event_time
      AND k.open_time <= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
  ) AS max_high_24h,
  (
    SELECT MIN(k.low_price)
    FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
    WHERE k.symbol = '{symbol}'
      AND k.interval_code = '1h'
      AND k.source = 'okx'
      AND k.open_time > a.event_time
      AND k.open_time <= DATE_ADD(a.event_time, INTERVAL 24 HOUR)
  ) AS min_low_24h,
  LEFT(COALESCE(e.ev_result_json, 'N/A'), 80) AS ev_excerpt,
  LEFT(COALESCE(e.execution_preview_json, 'N/A'), 120) AS execution_excerpt,
  LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayCollectorStatus')), 'N/A'), 64) AS replay_collector_status,
  LEFT(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.shadowReplayHardGatePreviewStatus')), 'N/A'), 64) AS hard_gate_preview_status
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
LEFT JOIN bt_runtime_decision_evidence e ON e.decision_id = a.id
WHERE a.symbol = '{symbol}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.blocker = 'DataFreshnessGuard'
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
    "audit_id", "event_time", "strategy_id", "interval_code",
    "has_runtime_evidence", "has_live_signal", "has_replay_candidate_id", "intent_created", "oco_plan_created",
    "has_explicit_entry", "has_tp", "has_sl", "has_ev_snapshot", "has_ev_preview_only", "ev_pass_like",
    "has_oco_plan", "has_oco_preview_only", "has_hard_gate_snapshot", "has_hard_gate_preview_only",
    "derived_entry", "close_after_24h", "max_high_24h", "min_low_24h", "ev_excerpt", "execution_excerpt",
    "replay_collector_status", "hard_gate_preview_status",
]
rows = [dict(zip(fields, row)) for row in csv.reader(proc.stdout.splitlines(), delimiter="\t")]

def flag(row, name):
    return str(row.get(name, "0")) == "1"

def num(row, name):
    raw = row.get(name)
    if raw in (None, "", "NULL", "None"):
        return None
    try:
        return float(raw)
    except Exception:
        return None

def pct(value):
    return "N/A" if value is None or math.isnan(value) else f"{value:+.2f}%"

total = len(rows)
matured = [r for r in rows if num(r, "derived_entry") and num(r, "close_after_24h")]
returns = []
mfe_values = []
mae_values = []
for r in matured:
    entry = num(r, "derived_entry")
    close = num(r, "close_after_24h")
    high = num(r, "max_high_24h")
    low = num(r, "min_low_24h")
    if entry and close:
        returns.append((close - entry) / entry * 100.0)
    if entry and high:
        mfe_values.append((high - entry) / entry * 100.0)
    if entry and low:
        mae_values.append((low - entry) / entry * 100.0)

strategy_counts = Counter(str(r.get("strategy_id", "N/A")) for r in rows)
interval_counts = Counter(str(r.get("interval_code", "N/A")) for r in rows)
runtime_rows = sum(flag(r, "has_runtime_evidence") for r in rows)
live_signal_rows = sum(flag(r, "has_live_signal") for r in rows)
replay_candidate_id_rows = sum(flag(r, "has_replay_candidate_id") for r in rows)
intent_rows = sum(flag(r, "intent_created") for r in rows)
oco_created_rows = sum(flag(r, "oco_plan_created") for r in rows)
explicit_entry_rows = sum(flag(r, "has_explicit_entry") for r in rows)
tp_rows = sum(flag(r, "has_tp") for r in rows)
sl_rows = sum(flag(r, "has_sl") for r in rows)
ev_snapshot_rows = sum(flag(r, "has_ev_snapshot") for r in rows)
ev_preview_only_rows = sum(flag(r, "has_ev_preview_only") for r in rows)
ev_pass_rows = sum(flag(r, "ev_pass_like") for r in rows)
oco_plan_rows = sum(flag(r, "has_oco_plan") for r in rows)
oco_preview_only_rows = sum(flag(r, "has_oco_preview_only") for r in rows)
hard_gate_rows = sum(flag(r, "has_hard_gate_snapshot") for r in rows)
hard_gate_preview_only_rows = sum(flag(r, "has_hard_gate_preview_only") for r in rows)
derived_entry_rows = sum(num(r, "derived_entry") is not None for r in rows)
forward_rows = len(matured)
positive_forward_rows = sum(1 for v in returns if v >= 1.0)
positive_mfe_rows = sum(1 for v in mfe_values if v >= 1.0)
replayable_candidate_rows = sum(
    (flag(r, "has_live_signal") or flag(r, "has_replay_candidate_id"))
    and flag(r, "has_explicit_entry")
    and flag(r, "has_tp")
    and flag(r, "has_sl")
    and flag(r, "has_ev_snapshot")
    and flag(r, "has_oco_plan")
    and flag(r, "has_hard_gate_snapshot")
    for r in rows
)
preview_input_rows = sum(
    (flag(r, "has_live_signal") or flag(r, "has_replay_candidate_id"))
    and flag(r, "has_explicit_entry")
    and flag(r, "has_tp")
    and flag(r, "has_sl")
    and flag(r, "has_ev_preview_only")
    and flag(r, "has_oco_preview_only")
    and flag(r, "has_hard_gate_preview_only")
    for r in rows
)

avg_return = sum(returns) / len(returns) if returns else None
avg_mfe = sum(mfe_values) / len(mfe_values) if mfe_values else None
avg_mae = sum(mae_values) / len(mae_values) if mae_values else None

missing = []
if live_signal_rows == 0:
    missing.append("liveSignalId")
if replay_candidate_id_rows == 0:
    missing.append("replayCandidateId")
if explicit_entry_rows == 0 or tp_rows == 0 or sl_rows == 0:
    missing.append("explicit entry/TP/SL candidate plan")
if ev_snapshot_rows == 0:
    missing.append("EV snapshot")
if oco_plan_rows == 0:
    missing.append("OCO plan")
if hard_gate_rows == 0:
    missing.append("hard-gate snapshot")
if replayable_candidate_rows == 0:
    missing.append("complete replayable candidate rows")

preview_missing = []
if replay_candidate_id_rows == 0:
    preview_missing.append("replayCandidateId")
if explicit_entry_rows == 0 or tp_rows == 0 or sl_rows == 0:
    preview_missing.append("entry/TP/SL preview")
if ev_preview_only_rows == 0:
    preview_missing.append("EV preview placeholder")
if oco_preview_only_rows == 0:
    preview_missing.append("OCO preview placeholder")
if hard_gate_preview_only_rows == 0:
    preview_missing.append("hard-gate preview placeholders")
if preview_input_rows == 0:
    preview_missing.append("complete preview-only input rows")

if total == 0:
    recommendation = "NO_DATAFRESHNESS_COUNTERFACTUAL_SAMPLE"
elif replayable_candidate_rows == 0 and (positive_forward_rows > 0 or positive_mfe_rows > 0):
    recommendation = "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING"
elif replayable_candidate_rows > 0 and positive_forward_rows > 0:
    recommendation = "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES"
else:
    recommendation = "COLLECT_MORE_COUNTERFACTUAL_SAMPLE"

def top(counter):
    return "none" if not counter else ",".join(f"{k}:{v}" for k, v in counter.most_common(8))

print("[data-freshness-counterfactual-review] read-only production DB/MCP evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} limit={limit}")
print("")
print("Counterfactual Sample Coverage:")
print(f"  data_freshness_counterfactual_rows={total}")
print(f"  runtime_evidence_linked_rows={runtime_rows}")
print(f"  live_signal_linked_rows={live_signal_rows}")
print(f"  replay_candidate_id_rows={replay_candidate_id_rows}")
print(f"  intent_created_rows={intent_rows}")
print(f"  oco_plan_created_rows={oco_created_rows}")
print(f"  explicit_candidate_entry_rows={explicit_entry_rows}")
print(f"  explicit_candidate_tp_rows={tp_rows}")
print(f"  explicit_candidate_sl_rows={sl_rows}")
print(f"  ev_snapshot_rows={ev_snapshot_rows}")
print(f"  ev_preview_only_rows={ev_preview_only_rows}")
print(f"  ev_pass_like_rows={ev_pass_rows}")
print(f"  oco_plan_snapshot_rows={oco_plan_rows}")
print(f"  oco_preview_only_rows={oco_preview_only_rows}")
print(f"  hard_gate_snapshot_rows={hard_gate_rows}")
print(f"  hard_gate_preview_only_rows={hard_gate_preview_only_rows}")
print(f"  complete_replayable_candidate_rows={replayable_candidate_rows}")
print(f"  preview_only_input_rows={preview_input_rows}")
print(f"  derived_entry_rows={derived_entry_rows}")
print(f"  forward_24h_window_rows={forward_rows}")
print("")
print("Forward Return Proxy:")
print(f"  positive_forward_24h_rows={positive_forward_rows}")
print(f"  positive_mfe_24h_rows={positive_mfe_rows}")
print(f"  avg_forward_24h_pct={pct(avg_return)}")
print(f"  avg_mfe_24h_pct={pct(avg_mfe)}")
print(f"  avg_mae_24h_pct={pct(avg_mae)}")
print("")
print("Breakdown:")
print(f"  strategy_counts={top(strategy_counts)}")
print(f"  interval_counts={top(interval_counts)}")
print("  missing_counterfactual_fields=" + json.dumps(missing, separators=(",", ":")))
print("  preview_only_missing_counterfactual_fields=" + json.dumps(preview_missing, separators=(",", ":")))
print("")
print("Examples:")
for r in rows[:5]:
    entry = num(r, "derived_entry")
    close = num(r, "close_after_24h")
    ret = ((close - entry) / entry * 100.0) if entry and close else None
    print(
        "  - "
        f"auditId={r.get('audit_id')} time={r.get('event_time')} strategy={r.get('strategy_id')} interval={r.get('interval_code')} "
        f"runtimeEvidence={r.get('has_runtime_evidence')} liveSignal={r.get('has_live_signal')} replayCandidateId={r.get('has_replay_candidate_id')} "
        f"entryPlan={r.get('has_explicit_entry')}/{r.get('has_tp')}/{r.get('has_sl')} "
        f"evSnapshot={r.get('has_ev_snapshot')} evPreview={r.get('has_ev_preview_only')} "
        f"ocoPlan={r.get('has_oco_plan')} ocoPreview={r.get('has_oco_preview_only')} "
        f"hardGateSnapshot={r.get('has_hard_gate_snapshot')} hardGatePreview={r.get('has_hard_gate_preview_only')} "
        f"collector={r.get('replay_collector_status')} hardGatePreviewStatus={r.get('hard_gate_preview_status')} "
        f"forward24h={pct(ret)} ev={r.get('ev_excerpt')} execution={r.get('execution_excerpt')}"
    )
print("")
print("Conclusion:")
print(f"  data_freshness_counterfactual_recommendation={recommendation}")
print("  preview_only_note=preview-only rows prove field presence and terminal-block traceability only; they are not evaluated EV/OCO/risk pass evidence and do not count as complete replayable candidates")
print("  counterfactual_required_evidence=[\"canonical candidate snapshot with liveSignalId or equivalent replay id\",\"replayCandidateId for DataFreshness L0 rows\",\"entryPrice,tpPrice,slPrice,expectedR and EV pass/fail\",\"OCO-capable preflight and hard-gate states for duplicate/daily-cap/exposure/event-risk\",\"shadow intent or replay result that removes only DataFreshness while preserving all other hard gates\"]")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[data-freshness-counterfactual-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "DataFreshness counterfactual review smoke failed with exit code $LASTEXITCODE"
}
