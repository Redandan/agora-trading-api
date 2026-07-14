param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$PositionIds = "260,261,262",
    [decimal]$ExpectedTotalQty = 0.00047090,
    [switch]$Execute,
    [string]$ConfirmText = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or
        $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "SshHost contains unsupported characters."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters."
    }
}

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

Assert-SshHostSafe -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

$idTokens = @($PositionIds -split "," | ForEach-Object { $_.Trim() })
if ($idTokens.Count -lt 1 -or $idTokens.Count -gt 20 -or
    @($idTokens | Where-Object { $_ -notmatch "^[1-9][0-9]{0,18}$" }).Count -gt 0) {
    throw "PositionIds must contain 1-20 comma-separated positive integer IDs."
}
if (@($idTokens | Select-Object -Unique).Count -ne $idTokens.Count) {
    throw "PositionIds must not contain duplicates."
}
$normalizedPositionIds = $idTokens -join ","
if ($ExpectedTotalQty -le 0) {
    throw "ExpectedTotalQty must be positive."
}
$qtyText = $ExpectedTotalQty.ToString(
    "0.############################",
    [System.Globalization.CultureInfo]::InvariantCulture)
$requiredLocalConfirmText =
    "EXECUTE_BTC_BASE_ADOPTION_KEEP_BTC_CANCEL_OCO|POSITIONS=$normalizedPositionIds|QTY=$qtyText|RESTART_AND_AUTO_ROLLBACK_GATES"

if ($Execute.IsPresent -and $ConfirmText -cne $requiredLocalConfirmText) {
    throw "ConfirmText must exactly equal: $requiredLocalConfirmText"
}

$executeValue = if ($Execute.IsPresent) { "1" } else { "0" }
$remoteScript = @'
set -euo pipefail

APP_DIR='__APP_DIR__'
ENV_FILE='__ENV_FILE__'
POSITION_IDS='__POSITION_IDS__'
EXPECTED_TOTAL_QTY='__EXPECTED_TOTAL_QTY__'
EXECUTE='__EXECUTE__'
JAR_NAME='target/agora-trading-api-1.0-SNAPSHOT.jar'
GATE_FEATURE='TRADING_BTC_BASE_ADOPTION_ENABLED'
GATE_LIVE='TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED'

cd "$APP_DIR"

for required in awk bash cat chmod cp curl date grep head java kill lsof mkdir mktemp mv nohup ps python3 rm sed seq sha256sum sleep stat tail tr; do
  command -v "$required" >/dev/null 2>&1 || {
    echo "FAIL: missing command: $required" >&2
    exit 1
  }
done
test -r "$ENV_FILE" || { echo "FAIL: env file is not readable" >&2; exit 1; }
test -w "$ENV_FILE" || { echo "FAIL: env file is not writable" >&2; exit 1; }
test -f "$JAR_NAME" || { echo "FAIL: application jar missing" >&2; exit 1; }
test -f app.port || { echo "FAIL: app.port missing" >&2; exit 1; }
test -f app.pid || { echo "FAIL: app.pid missing" >&2; exit 1; }

ACTIVE_PORT="$(cat app.port)"
ACTIVE_PID="$(cat app.pid)"
case "$ACTIVE_PORT" in
  8084) INACTIVE_PORT=8085 ;;
  8085) INACTIVE_PORT=8084 ;;
  *) echo "FAIL: unsupported active port: $ACTIVE_PORT" >&2; exit 1 ;;
esac
case "$ACTIVE_PID" in
  ''|*[!0-9]*) echo "FAIL: invalid app.pid" >&2; exit 1 ;;
esac

MCP_KEY="$(grep -E '^TRADING_MCP_KEY=' "$ENV_FILE" | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')"
test -n "$MCP_KEY" || { echo "FAIL: TRADING_MCP_KEY missing" >&2; exit 1; }
export ACTIVE_PORT MCP_KEY POSITION_IDS EXPECTED_TOTAL_QTY

PY_HELPER="$(mktemp)"
PRECHECK_STATE="$(mktemp)"
EVIDENCE_DIR="$APP_DIR/target/btc-base-adoption"
EVIDENCE_FILE="$EVIDENCE_DIR/adoption-$(date -u +%Y%m%dT%H%M%SZ).json"
export PRECHECK_STATE EVIDENCE_FILE
ENV_BACKUP=''
ORIGINAL_ENV_SHA=''
MUTATION_STARTED=0
SAFE_FINALIZED=0
LAST_RUN_LOG=''

cat > "$PY_HELPER" <<'PY'
import json
import os
import re
import urllib.request
from decimal import Decimal
from pathlib import Path

url = f"http://127.0.0.1:{os.environ['ACTIVE_PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
expected_ids = [int(value) for value in os.environ["POSITION_IDS"].split(",")]
expected_qty = Decimal(os.environ["EXPECTED_TOTAL_QTY"])
mode = os.environ["MODE"]
precheck_path = Path(os.environ["PRECHECK_STATE"])
evidence_path = Path(os.environ["EVIDENCE_FILE"])

def call_tool(name, arguments=None):
    body = {
        "jsonrpc": "2.0",
        "id": f"btc-base-adoption-{mode}-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        payload = json.loads(response.read().decode("utf-8", "replace"))
    if payload.get("error"):
        raise AssertionError(f"{name} JSON-RPC error: {payload['error']}")
    result = payload.get("result") or {}
    if result.get("isError"):
        raise AssertionError(f"{name} returned isError=true")
    content = result.get("content") or []
    text = content[0].get("text", "") if content and isinstance(content[0], dict) else json.dumps(result)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        decoded = json.loads(text)
        if isinstance(decoded, str):
            text = decoded
    return text

def json_tool(name, arguments=None):
    value = json.loads(call_tool(name, arguments))
    if not isinstance(value, dict):
        raise AssertionError(f"{name} did not return an object")
    return value

def decimal(value):
    return Decimal(str(value))

def assert_false_safety(label, safety):
    unexpected = sorted(key for key, value in safety.items() if value is True)
    if unexpected:
        raise AssertionError(f"{label} safety flags unexpectedly true: {unexpected}")

def selected_snapshot(dry_run):
    positions = dry_run.get("positions") or []
    if [row.get("positionId") for row in positions] != expected_ids:
        raise AssertionError("position ID sequence drift")
    return [{
        "positionId": row["positionId"],
        "strategyId": row.get("strategyId"),
        "intervalCode": row.get("intervalCode"),
        "ocoAlgoId": row.get("ocoAlgoId"),
        "ownedQty": str(decimal(row.get("ownedQty"))),
        "ocoState": row.get("ocoState"),
        "alreadyAdopted": bool(row.get("alreadyAdopted")),
        "eligible": bool(row.get("eligible")),
    } for row in positions]

def assert_common_status(status, armed, adopted):
    if status.get("policyMode") != "BTC_BASE_POSITION_MANAGER_V1":
        raise AssertionError("manager policy mode mismatch")
    if status.get("blockers") != []:
        raise AssertionError(f"manager blockers: {status.get('blockers')}")
    if status.get("adoptionFeatureEnabled") is not armed:
        raise AssertionError("feature gate state mismatch")
    if status.get("adoptionLiveActionEnabled") is not armed:
        raise AssertionError("live-action gate state mismatch")
    if status.get("adoptionExecutionArmed") is not armed:
        raise AssertionError("execution-armed state mismatch")
    inventory = status.get("inventory") or {}
    if inventory.get("openAutoTradedPositionCount") != len(expected_ids):
        raise AssertionError("open position cohort drift")
    if adopted:
        if inventory.get("adoptedFromOcoIds") != expected_ids:
            raise AssertionError("adopted position IDs mismatch")
        if inventory.get("adoptionPendingCount") != 0:
            raise AssertionError("adoption pending rows remain")
        if inventory.get("recordedOcoCandidateCount") != 0:
            raise AssertionError("recorded OCO candidates remain")
        if status.get("persistedManagedPositionCount") != len(expected_ids):
            raise AssertionError("persisted managed count mismatch")
    else:
        if inventory.get("recordedOcoCandidateIds") != expected_ids:
            raise AssertionError("pre-adoption candidate IDs mismatch")
        if inventory.get("adoptionPendingCount") != 0 or inventory.get("adoptedFromOcoCount") != 0:
            raise AssertionError("unexpected pre-existing adoption state")
    assert_false_safety("manager status", status.get("safety") or {})

def assert_live_dry_run(dry_run, armed):
    if dry_run.get("status") != "READY_FOR_EXPLICIT_EXECUTION_NOT_AUTHORIZED":
        raise AssertionError(f"dry-run status mismatch: {dry_run.get('status')}")
    if dry_run.get("executeRequested") is not False:
        raise AssertionError("dry-run unexpectedly requested execution")
    if dry_run.get("featureEnabled") is not armed or dry_run.get("liveActionEnabled") is not armed:
        raise AssertionError("dry-run gate state mismatch")
    if dry_run.get("executionArmed") is not armed:
        raise AssertionError("dry-run execution-armed mismatch")
    if dry_run.get("blockers") != [] or dry_run.get("executionBlockers") != []:
        raise AssertionError("dry-run has blockers")
    if decimal(dry_run.get("actualTotalQty")) != expected_qty:
        raise AssertionError("aggregate quantity drift")
    rows = selected_snapshot(dry_run)
    if not all(row["eligible"] and not row["alreadyAdopted"] and row["ocoState"] == "live" for row in rows):
        raise AssertionError("one or more positions are not active eligible OCO rows")
    assert_false_safety("adoption dry-run", dry_run.get("safety") or {})
    return rows

def assert_post_state(status, dry_run, oco_health, open_positions, armed):
    assert_common_status(status, armed=armed, adopted=True)
    if dry_run.get("status") != "READY_FOR_EXPLICIT_EXECUTION_NOT_AUTHORIZED":
        raise AssertionError("idempotent post-adoption dry-run not ready")
    if decimal(dry_run.get("actualTotalQty")) != expected_qty:
        raise AssertionError("post-adoption quantity drift")
    rows = selected_snapshot(dry_run)
    if not all(row["eligible"] and row["alreadyAdopted"] and
               row["ocoState"] == "ADOPTED_FROM_OCO" for row in rows):
        raise AssertionError("post-adoption row state mismatch")
    for position_id in expected_ids:
        marker = f"[BTC_BASE_ADOPTED_FROM_OCO] Position #{position_id}"
        if marker not in oco_health:
            raise AssertionError(f"missing managed OCO-health marker for {position_id}")
        blocks = [block for block in open_positions.split("---") if re.search(
            rf"\bID:\s*{position_id}\b", block)]
        if len(blocks) != 1 or "BTC_BASE" not in blocks[0] or "Strategy ID: 508" not in blocks[0]:
            raise AssertionError(f"open-position BTC_BASE evidence mismatch for {position_id}")
    if "3 OK" not in oco_health or "0 SYNC_ERROR" not in oco_health or "0 \u7570\u5e38" not in oco_health:
        raise AssertionError("post-adoption OCO health is not clean")
    return rows

def dry_run():
    return json_tool("adoptBtcBasePositionsKeepBtc", {
        "positionIds": os.environ["POSITION_IDS"],
        "expectedTotalQty": float(expected_qty),
        "execute": False,
        "confirmText": "",
    })

if mode == "preflight":
    status = json_tool("getBtcBasePositionManagerStatus", {"symbol": "BTCUSDT"})
    preview = json_tool("previewBtcBasePositionAdoption", {
        "positionIds": os.environ["POSITION_IDS"], "horizonHours": 168})
    current = dry_run()
    oco_health = call_tool("getOcoHealth", {"symbol": "BTCUSDT"})
    assert_common_status(status, armed=False, adopted=False)
    if preview.get("blockers") != [] or preview.get("decision", {}).get("adoptionEligible") is not True:
        raise AssertionError("manager adoption preview is not eligible")
    if decimal(preview.get("aggregate", {}).get("ownedQty")) != expected_qty:
        raise AssertionError("manager preview aggregate quantity drift")
    assert_false_safety("manager preview", preview.get("safety") or {})
    rows = assert_live_dry_run(current, armed=False)
    if "3 OK" not in oco_health or "0 SYNC_ERROR" not in oco_health or "0 \u7570\u5e38" not in oco_health:
        raise AssertionError("preflight OCO health is not clean")
    precheck_path.write_text(json.dumps(rows, sort_keys=True), encoding="utf-8")
    print("btc_base_preflight=" + json.dumps({
        "status": "READY_READ_ONLY",
        "positionIds": expected_ids,
        "actualTotalQty": str(expected_qty),
        "ocoAlgoIds": [row["ocoAlgoId"] for row in rows],
        "gatesArmed": False,
        "ocoHealth": "3_OK_0_SYNC_ERROR_0_ABNORMAL",
    }, sort_keys=True))

elif mode == "armed":
    status = json_tool("getBtcBasePositionManagerStatus", {"symbol": "BTCUSDT"})
    current = dry_run()
    assert_common_status(status, armed=True, adopted=False)
    rows = assert_live_dry_run(current, armed=True)
    before = json.loads(precheck_path.read_text(encoding="utf-8"))
    if rows != before:
        raise AssertionError("position/OCO state drifted across guarded restart")
    print("btc_base_armed_preflight=" + json.dumps({
        "status": "READY_FOR_SCOPED_EXECUTION",
        "positionIds": expected_ids,
        "actualTotalQty": str(expected_qty),
        "dynamicConfirmTextGenerated": bool(current.get("requiredConfirmText")),
        "gatesArmed": True,
    }, sort_keys=True))

elif mode == "execute":
    current = dry_run()
    rows = assert_live_dry_run(current, armed=True)
    before = json.loads(precheck_path.read_text(encoding="utf-8"))
    if rows != before:
        raise AssertionError("position/OCO state drifted immediately before execution")
    required_confirm = current.get("requiredConfirmText")
    if not required_confirm:
        raise AssertionError("dynamic confirmation text missing")
    result = json_tool("adoptBtcBasePositionsKeepBtc", {
        "positionIds": os.environ["POSITION_IDS"],
        "expectedTotalQty": float(expected_qty),
        "execute": True,
        "confirmText": required_confirm,
    })
    if result.get("status") != "COMPLETED_KEEP_BTC":
        raise AssertionError(f"execution did not complete: {result.get('status')}")
    if result.get("completedCount") != len(expected_ids) or result.get("alreadyAdoptedCount") != 0:
        raise AssertionError("execution completion count mismatch")
    if result.get("pendingCount") != 0 or result.get("failedCount") != 0:
        raise AssertionError("execution left pending or failed positions")
    if result.get("ocoCancelConfirmedCount") != len(expected_ids):
        raise AssertionError("OCO cancellation confirmation count mismatch")
    safety = result.get("safety") or {}
    required_true = ["databaseMutated", "ocoCancelAttempted", "ocoCancelConfirmed", "btcRetainedConfirmed"]
    required_false = ["marketSellAttempted", "btcSold", "positionClosed", "orderPlaced", "telegramSent", "fundsMoved"]
    if not all(safety.get(key) is True for key in required_true):
        raise AssertionError("execution success safety evidence incomplete")
    if not all(safety.get(key) is False for key in required_false):
        raise AssertionError("forbidden execution safety marker detected")
    outcomes = result.get("executionOutcomes") or []
    if [row.get("positionId") for row in outcomes] != expected_ids:
        raise AssertionError("execution outcome IDs mismatch")
    if not all(row.get("status") == "ADOPTED" and row.get("ocoCancelConfirmed") is True and
               row.get("btcRetainedConfirmed") is True and row.get("marketSellAttempted") is False
               for row in outcomes):
        raise AssertionError("one or more execution outcomes are incomplete")
    status = json_tool("getBtcBasePositionManagerStatus", {"symbol": "BTCUSDT"})
    after = dry_run()
    oco_health = call_tool("getOcoHealth", {"symbol": "BTCUSDT"})
    open_positions = call_tool("listOpenPositions", {"symbol": "BTCUSDT"})
    post_rows = assert_post_state(status, after, oco_health, open_positions, armed=True)
    evidence = {
        "status": "COMPLETED_KEEP_BTC",
        "positionIds": expected_ids,
        "actualTotalQty": str(expected_qty),
        "outcomes": outcomes,
        "safety": safety,
        "postRows": post_rows,
        "postManagerInventory": status.get("inventory"),
        "dynamicConfirmTextStored": False,
    }
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True), encoding="utf-8")
    os.chmod(evidence_path, 0o600)
    print("btc_base_execution=" + json.dumps({
        "status": result["status"],
        "positionIds": expected_ids,
        "actualTotalQty": str(expected_qty),
        "completedCount": result["completedCount"],
        "ocoCancelConfirmedCount": result["ocoCancelConfirmedCount"],
        "btcRetainedConfirmed": safety["btcRetainedConfirmed"],
        "marketSellAttempted": safety["marketSellAttempted"],
        "positionClosed": safety["positionClosed"],
    }, sort_keys=True))

elif mode == "post":
    status = json_tool("getBtcBasePositionManagerStatus", {"symbol": "BTCUSDT"})
    after = dry_run()
    oco_health = call_tool("getOcoHealth", {"symbol": "BTCUSDT"})
    open_positions = call_tool("listOpenPositions", {"symbol": "BTCUSDT"})
    rows = assert_post_state(status, after, oco_health, open_positions, armed=False)
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    evidence["postRestart"] = {
        "gatesArmed": False,
        "positionsRemainOpen": True,
        "managedRows": rows,
        "ocoHealth": "3_OK_0_SYNC_ERROR_0_ABNORMAL",
    }
    evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True), encoding="utf-8")
    os.chmod(evidence_path, 0o600)
    print("btc_base_post_acceptance=" + json.dumps({
        "status": "PASS_GATES_OFF_MANAGED_BTC_RETAINED",
        "positionIds": expected_ids,
        "actualTotalQty": str(expected_qty),
        "gatesArmed": False,
        "positionsRemainOpen": True,
        "ocoHealth": "3_OK_0_SYNC_ERROR_0_ABNORMAL",
        "evidenceFile": str(evidence_path),
    }, sort_keys=True))
else:
    raise AssertionError(f"unsupported mode: {mode}")
PY

load_env_file() {
  local file="$1"
  local line key value
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|\#*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      [A-Za-z_][A-Za-z0-9_]*) export "$key=$value" ;;
      *) echo "WARN: ignoring invalid env key: $key" >&2 ;;
    esac
  done < "$file"
}

listener_pid() {
  lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

assert_single_runtime() {
  local active_listener inactive_listener
  active_listener="$(listener_pid "$ACTIVE_PORT")"
  inactive_listener="$(listener_pid "$INACTIVE_PORT")"
  test -n "$active_listener" || { echo "FAIL: active port has no listener" >&2; return 1; }
  test -z "$inactive_listener" || { echo "FAIL: inactive port has a listener" >&2; return 1; }
  test "$active_listener" = "$(cat app.pid)" || { echo "FAIL: active listener/app.pid mismatch" >&2; return 1; }
  ps -p "$active_listener" -o args= | grep -Fq "$JAR_NAME" || {
    echo "FAIL: active listener is not the Trading runtime" >&2
    return 1
  }
}

stop_runtime() {
  local pid=''
  if [ -f app.pid ]; then
    pid="$(cat app.pid)"
  fi
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    for _ in $(seq 1 60); do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "FAIL: runtime PID $pid did not stop cleanly" >&2
      return 1
    fi
  fi
  rm -f app.pid "app.pid.$ACTIVE_PORT"
  test -z "$(listener_pid "$ACTIVE_PORT")" || {
    echo "FAIL: active port still has a listener after stop" >&2
    return 1
  }
}

start_runtime() {
  unset TRADING_BTC_BASE_ADOPTION_ENABLED TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED
  load_env_file "$ENV_FILE"
  local java_opts="${JAVA_OPTS:--Xms512m -Xmx2g -Duser.timezone=UTC}"
  mkdir -p logs/runs
  LAST_RUN_LOG="logs/runs/app-$(date -u +%Y%m%dT%H%M%SZ)-btc-base-adoption-port${ACTIVE_PORT}.log"
  PORT="$ACTIVE_PORT" nohup java $java_opts -jar "$JAR_NAME" > "$LAST_RUN_LOG" 2>&1 &
  local pid="$!"
  echo "$pid" > "app.pid.$ACTIVE_PORT"
  echo "$pid" > app.pid
  for _ in $(seq 1 180); do
    if curl -fsS "http://127.0.0.1:${ACTIVE_PORT}/api/actuator/health" >/dev/null 2>&1; then
      assert_single_runtime
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      tail -80 "$LAST_RUN_LOG" >&2 || true
      echo "FAIL: restarted runtime exited before health was ready" >&2
      return 1
    fi
    sleep 1
  done
  tail -80 "$LAST_RUN_LOG" >&2 || true
  echo "FAIL: restarted runtime health timed out" >&2
  return 1
}

set_gates_true() {
  python3 - "$ENV_FILE" <<'PY'
import os
import re
import stat
import sys
import tempfile

path = sys.argv[1]
keys = {
    "TRADING_BTC_BASE_ADOPTION_ENABLED": "true",
    "TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED": "true",
}
metadata = os.stat(path)
with open(path, "r", encoding="utf-8") as source:
    lines = source.readlines()
kept = [line for line in lines if not any(re.match(rf"^\s*{re.escape(key)}=", line) for key in keys)]
if kept and not kept[-1].endswith("\n"):
    kept[-1] += "\n"
for key, value in keys.items():
    kept.append(f"{key}={value}\n")
directory = os.path.dirname(path) or "."
fd, temp_path = tempfile.mkstemp(prefix=".btc-base-env-", dir=directory, text=True)
try:
    os.fchmod(fd, stat.S_IMODE(metadata.st_mode))
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        target.writelines(kept)
        target.flush()
        os.fsync(target.fileno())
    os.replace(temp_path, path)
finally:
    if os.path.exists(temp_path):
        os.unlink(temp_path)
PY
  test "$(grep -Ec '^TRADING_BTC_BASE_ADOPTION_ENABLED=true$' "$ENV_FILE")" = "1"
  test "$(grep -Ec '^TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED=true$' "$ENV_FILE")" = "1"
}

restore_env() {
  test -n "$ENV_BACKUP" || return 1
  local restore_path="${ENV_FILE}.restore.$$"
  cp -p "$ENV_BACKUP" "$restore_path"
  mv -f "$restore_path" "$ENV_FILE"
  test "$(sha256sum "$ENV_FILE" | awk '{print $1}')" = "$ORIGINAL_ENV_SHA"
}

cleanup() {
  local exit_code="$?"
  trap - EXIT INT TERM
  set +e
  if [ "$MUTATION_STARTED" = "1" ] && [ "$SAFE_FINALIZED" != "1" ]; then
    echo "[btc-base-adoption] failure recovery: restoring original env and gates-off runtime" >&2
    restore_env
    stop_runtime
    start_runtime
    if [ -n "$ENV_BACKUP" ]; then rm -f "$ENV_BACKUP"; fi
  fi
  rm -f "$PY_HELPER" "$PRECHECK_STATE"
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

assert_single_runtime
MODE=preflight python3 "$PY_HELPER"

if [ "$EXECUTE" != "1" ]; then
  echo "btc_base_operator_status=READY_FOR_EXPLICIT_EXECUTION_NOT_MUTATED"
  exit 0
fi

ENV_BACKUP="$(mktemp "${ENV_FILE}.btc-base-adoption.XXXXXX")"
cp -p "$ENV_FILE" "$ENV_BACKUP"
chmod 600 "$ENV_BACKUP"
ORIGINAL_ENV_SHA="$(sha256sum "$ENV_FILE" | awk '{print $1}')"

set_gates_true
MUTATION_STARTED=1
stop_runtime
start_runtime
MODE=armed python3 "$PY_HELPER"
MODE=execute python3 "$PY_HELPER"

restore_env
stop_runtime
start_runtime
MODE=post python3 "$PY_HELPER"

test "$(sha256sum "$ENV_FILE" | awk '{print $1}')" = "$ORIGINAL_ENV_SHA"
rm -f "$ENV_BACKUP"
ENV_BACKUP=''
SAFE_FINALIZED=1
MUTATION_STARTED=0
echo "btc_base_operator_status=COMPLETED_KEEP_BTC_GATES_RESTORED_FALSE"
echo "btc_base_operator_evidence_file=$EVIDENCE_FILE"
'@

$remoteScript = $remoteScript.Replace("__APP_DIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENV_FILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__POSITION_IDS__", $normalizedPositionIds)
$remoteScript = $remoteScript.Replace("__EXPECTED_TOTAL_QTY__", $qtyText)
$remoteScript = $remoteScript.Replace("__EXECUTE__", $executeValue)

$output = $remoteScript | & ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 `
    $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1
$exitCode = $LASTEXITCODE
$text = ($output | Out-String -Width 4096).Trim()
if (-not [string]::IsNullOrWhiteSpace($text)) {
    Write-Host $text
}
if ($exitCode -ne 0) {
    throw "BTC Base adoption operator failed with exit code $exitCode"
}

if (-not $Execute.IsPresent) {
    Write-Host "required_local_confirm_text=$requiredLocalConfirmText"
    Write-Host "scope=READ_ONLY; no env, runtime, DB, order, OCO, Telegram, fund, Grid, Earn, or exchange mutation performed."
}
