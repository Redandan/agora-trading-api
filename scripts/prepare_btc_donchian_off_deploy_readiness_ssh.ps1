param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [ValidateSet("PREDEPLOY", "POSTDEPLOY_OFF")]
    [string]$Phase = "PREDEPLOY",
    [string]$ExpectedBaseCommit = "",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or
        $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-CommitSafe {
    param([string]$Name, [string]$Value)
    if (-not [string]::IsNullOrWhiteSpace($Value) -and $Value -notmatch "^[0-9a-fA-F]{7,40}$") {
        throw "$Name must be an abbreviated or full git commit SHA."
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) {
    throw "A valid SshKey is required."
}
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-CommitSafe -Name "ExpectedBaseCommit" -Value $ExpectedBaseCommit

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

export ENV_FILE='__ENVFILE__'
export READINESS_PHASE='__PHASE__'
export EXPECTED_BASE_COMMIT='__EXPECTED_COMMIT__'

python3 - <<'PY'
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation
from pathlib import Path

EXPECTED_FIRST = dt.datetime(2019, 1, 1, 0, 0, 0)
EXPECTED_LAST = dt.datetime(2026, 7, 13, 8, 0, 0)
EXPECTED_ROWS = 66009
EXPECTED_HASH = "361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d"
POLICY_MODE = "BTC_DONCHIAN_20D_10D_V1"
DONCHIAN_TOOLS = {
    "analyzeBtcDonchianShadowGoldenParity",
    "getBtcDonchianShadowReadiness",
}
PHASE = os.environ["READINESS_PHASE"]
ENV_FILE = Path(os.environ["ENV_FILE"])
EXPECTED_BASE_COMMIT = os.environ.get("EXPECTED_BASE_COMMIT", "").strip().lower()

blockers: list[str] = []
warnings: list[str] = []


def add_blocker(value: str) -> None:
    if value not in blockers:
        blockers.append(value)


def run(command: list[str], *, env: dict[str, str] | None = None,
        timeout: int = 60, check: bool = False) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout or "command failed").strip().splitlines()[-1]
        raise RuntimeError(detail[:300])
    return result


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    with path.open("r", encoding="utf-8-sig") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            values[key.strip()] = value
    return values


def parse_jdbc(value: str) -> tuple[str, str, str]:
    match = re.match(r"^jdbc:mysql://([^/:?]+)(?::([0-9]+))?/([^?]+)(?:\?.*)?$", value)
    if not match:
        raise ValueError("SPRING_DATASOURCE_URL is not a supported jdbc:mysql URL")
    return match.group(1), match.group(2) or "3306", match.group(3)


def canonical_decimal(value: str) -> str:
    number = Decimal(value)
    rendered = format(number, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return "0" if rendered in ("", "-0") else rendered


def parse_utc(value: str) -> dt.datetime:
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")


def mysql_query(values: dict[str, str], sql: str, timeout: int = 180) -> str:
    host, port, database = parse_jdbc(values.get("SPRING_DATASOURCE_URL", ""))
    username = values.get("SPRING_DATASOURCE_USERNAME", "")
    password = values.get("SPRING_DATASOURCE_PASSWORD", "")
    if not username or not password:
        raise ValueError("database credentials are missing")
    child_env = os.environ.copy()
    child_env["MYSQL_PWD"] = password
    command = [
        "mysql", "--batch", "--raw", "--skip-column-names",
        "--default-character-set=utf8mb4", "--connect-timeout=10",
        "-h", host, "-P", port, "-u", username, database, "-e", sql,
    ]
    result = run(command, env=child_env, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or "mysql query failed").strip().splitlines()[-1]
        raise RuntimeError(detail[:300])
    return result.stdout


def git_value(*args: str) -> str:
    result = run(["git", *args], timeout=30)
    return result.stdout.strip() if result.returncode == 0 else ""


env_values: dict[str, str] = {}
try:
    env_values = read_env(ENV_FILE)
except Exception as exc:
    add_blocker("PRODUCTION_ENV_UNREADABLE")
    warnings.append(f"env read failed: {str(exc)[:200]}")

effective_mode = env_values.get("TRADING_BTC_DONCHIAN_SHADOW_MODE", "OFF").strip().upper()
mode_key_present = "TRADING_BTC_DONCHIAN_SHADOW_MODE" in env_values
unsupported_keys = sorted(
    key for key in env_values
    if key.startswith("TRADING_BTC_DONCHIAN_SHADOW_")
    and key != "TRADING_BTC_DONCHIAN_SHADOW_MODE"
)
if effective_mode != "OFF":
    add_blocker("DONCHIAN_MODE_NOT_OFF")
if unsupported_keys:
    add_blocker("UNSUPPORTED_DONCHIAN_ENV_KEYS_PRESENT")

schema_env_expected = {
    "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
    "SPRING_FLYWAY_ENABLED": "true",
    "SPRING_FLYWAY_TABLE": "trading_flyway_schema_history",
}
schema_env = {key: env_values.get(key, "") for key in schema_env_expected}
for key, expected in schema_env_expected.items():
    if schema_env.get(key, "").strip().lower() != expected.lower():
        add_blocker(f"{key}_NOT_HARDENED")

head_commit = git_value("rev-parse", "HEAD").lower()
origin_result = run(["git", "ls-remote", "origin", "refs/heads/main"], timeout=30)
origin_commit = origin_result.stdout.split()[0].lower() if origin_result.returncode == 0 and origin_result.stdout.split() else ""
deployed_commit = Path("app.commit").read_text(encoding="utf-8").strip().lower() if Path("app.commit").exists() else ""
tracked_status = git_value("status", "--porcelain", "--untracked-files=no")
all_status = git_value("status", "--porcelain", "--untracked-files=normal")
server_worktree_dirty = bool(tracked_status)
untracked_paths = sorted(
    line[3:].strip() for line in all_status.splitlines()
    if line.startswith("?? ") and line[3:].strip()
)
if not head_commit or not origin_commit or not deployed_commit:
    add_blocker("DEPLOYMENT_METADATA_INCOMPLETE")
if head_commit and origin_commit and head_commit != origin_commit:
    add_blocker("SERVER_WORKTREE_NOT_ORIGIN_MAIN")
if head_commit and deployed_commit and head_commit != deployed_commit:
    add_blocker("DEPLOYED_COMMIT_NOT_SERVER_HEAD")
if server_worktree_dirty:
    add_blocker("SERVER_WORKTREE_DIRTY")
if EXPECTED_BASE_COMMIT and not head_commit.startswith(EXPECTED_BASE_COMMIT):
    add_blocker("EXPECTED_BASE_COMMIT_MISMATCH")

active_port = ""
active_pid = ""
health_status = "UNAVAILABLE"
mcp_tool_count = 0
deployed_donchian_tools: list[str] = []
try:
    active_port = Path("app.port").read_text(encoding="utf-8").strip()
    active_pid = Path("app.pid").read_text(encoding="utf-8").strip()
    if active_port not in {"8084", "8085"}:
        raise ValueError("active port is outside the blue-green set")
    if not active_pid.isdigit() or run(["kill", "-0", active_pid], timeout=10).returncode != 0:
        raise ValueError("active pid is not running")
    health_url = f"http://127.0.0.1:{active_port}/api/actuator/health"
    with urllib.request.urlopen(health_url, timeout=15) as response:
        health = json.loads(response.read().decode("utf-8", "replace"))
    health_status = str(health.get("status", "UNKNOWN"))
    if health_status != "UP":
        add_blocker("ACTIVE_HEALTH_NOT_UP")

    mcp_key = env_values.get("TRADING_MCP_KEY", "")
    if not mcp_key:
        raise ValueError("TRADING_MCP_KEY is missing")
    payload = json.dumps({"jsonrpc": "2.0", "id": "off-readiness", "method": "tools/list", "params": {}}).encode("utf-8")
    request = urllib.request.Request(
        f"http://127.0.0.1:{active_port}/api/mcp",
        data=payload,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {mcp_key}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        registry = json.loads(response.read().decode("utf-8", "replace"))
    tools = (registry.get("result") or {}).get("tools") or []
    names = {str(tool.get("name", "")) for tool in tools if isinstance(tool, dict)}
    mcp_tool_count = len(names)
    deployed_donchian_tools = sorted(DONCHIAN_TOOLS.intersection(names))
    if PHASE == "PREDEPLOY" and deployed_donchian_tools:
        add_blocker("DONCHIAN_TOOLS_ALREADY_DEPLOYED_PHASE_MISMATCH")
    if PHASE == "POSTDEPLOY_OFF" and set(deployed_donchian_tools) != DONCHIAN_TOOLS:
        add_blocker("DONCHIAN_TOOLS_MISSING_AFTER_DEPLOY")
except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exc:
    add_blocker("ACTIVE_RUNTIME_OR_MCP_UNAVAILABLE")
    warnings.append(f"runtime probe failed: {str(exc)[:200]}")

required_columns = {
    "md_kline": {
        "symbol", "interval_code", "open_time", "close_time", "open_price",
        "high_price", "low_price", "close_price", "volume", "source",
    },
    "bt_decision_audit": {
        "id", "event_time", "strategy_id", "symbol", "interval_code",
        "bar_open_time", "event_type", "outcome", "blocker", "reason", "context_json",
    },
    "bt_runtime_decision_evidence": {
        "id", "decision_id", "evidence_time", "symbol", "side", "strategy_id",
        "interval_code", "signal_source", "features_snapshot_json", "freshness_state",
        "blocker_reason", "policy_mode", "policy_reason", "policy_inputs_json",
        "selected_action", "reason", "final_outcome", "decision", "terminal_blocker",
        "execution_preview_json", "execution_mode", "order_sent", "suppression_reason",
        "intent_created", "oco_plan_created",
    },
}
schema_columns: dict[str, set[str]] = {name: set() for name in required_columns}
schema_indexes: dict[str, set[tuple[str, str]]] = {name: set() for name in required_columns}
try:
    column_sql = """
SET SESSION time_zone = '+00:00';
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('md_kline','bt_decision_audit','bt_runtime_decision_evidence')
ORDER BY table_name, ordinal_position;
"""
    for raw in mysql_query(env_values, column_sql).splitlines():
        parts = raw.split("\t")
        if len(parts) == 2 and parts[0] in schema_columns:
            schema_columns[parts[0]].add(parts[1])
    for table, required in required_columns.items():
        if not required.issubset(schema_columns[table]):
            add_blocker(f"{table.upper()}_SCHEMA_INCOMPLETE")

    index_sql = """
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('md_kline','bt_decision_audit','bt_runtime_decision_evidence')
GROUP BY table_name, index_name
ORDER BY table_name, index_name;
"""
    for raw in mysql_query(env_values, index_sql).splitlines():
        parts = raw.split("\t")
        if len(parts) == 3 and parts[0] in schema_indexes:
            schema_indexes[parts[0]].add((parts[1], parts[2]))
    if not any(columns == "symbol,interval_code,source,open_time" for _, columns in schema_indexes["md_kline"]):
        add_blocker("MD_KLINE_SCOPE_INDEX_MISSING")
    if not any(columns == "decision_id" for _, columns in schema_indexes["bt_runtime_decision_evidence"]):
        add_blocker("RUNTIME_EVIDENCE_DECISION_INDEX_MISSING")
except (OSError, ValueError, RuntimeError) as exc:
    add_blocker("PRODUCTION_SCHEMA_QUERY_FAILED")
    warnings.append(f"schema query failed: {str(exc)[:200]}")

row_count = 0
first_open: dt.datetime | None = None
last_open: dt.datetime | None = None
duplicate_rows = 0
lattice_gap_rows = 0
close_time_mismatch_rows = 0
ohlc_invariant_rows = 0
scope_mismatch_rows = 0
close_time_mismatch_samples: list[dict[str, str]] = []
canonical_hash = ""
normalized_close_hash = ""
try:
    bars_sql = """
SET SESSION time_zone = '+00:00';
SELECT DATE_FORMAT(open_time, '%Y-%m-%dT%H:%i:%sZ'),
       DATE_FORMAT(close_time, '%Y-%m-%dT%H:%i:%sZ'),
       symbol, interval_code, source,
       CAST(open_price AS CHAR), CAST(high_price AS CHAR), CAST(low_price AS CHAR),
       CAST(close_price AS CHAR), CAST(volume AS CHAR)
FROM md_kline
WHERE symbol = 'BTCUSDT'
  AND interval_code = '1h'
  AND source = 'okx'
  AND open_time BETWEEN '2019-01-01 00:00:00' AND '2026-07-13 08:00:00'
ORDER BY open_time ASC;
"""
    digest = hashlib.sha256()
    normalized_close_digest = hashlib.sha256()
    previous_open: dt.datetime | None = None
    for raw in mysql_query(env_values, bars_sql, timeout=300).splitlines():
        parts = raw.split("\t")
        if len(parts) != 10:
            raise RuntimeError("unexpected md_kline field count")
        open_text, close_text, symbol, interval_code, source, open_value, high_value, low_value, close_value, volume_value = parts
        open_time = parse_utc(open_text)
        close_time = parse_utc(close_text)
        row_count += 1
        first_open = first_open or open_time
        last_open = open_time
        if previous_open is not None:
            if open_time == previous_open:
                duplicate_rows += 1
            elif open_time != previous_open + dt.timedelta(hours=1):
                lattice_gap_rows += 1
        previous_open = open_time
        if close_time != open_time + dt.timedelta(hours=1):
            close_time_mismatch_rows += 1
            if len(close_time_mismatch_samples) < 5:
                close_time_mismatch_samples.append({
                    "openTimeUtc": open_text,
                    "actualCloseTimeUtc": close_text,
                    "expectedCloseTimeUtc": (open_time + dt.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                })
        if symbol != "BTCUSDT" or interval_code.lower() != "1h" or source.lower() != "okx":
            scope_mismatch_rows += 1
        try:
            open_number = Decimal(open_value)
            high_number = Decimal(high_value)
            low_number = Decimal(low_value)
            close_number = Decimal(close_value)
            volume_number = Decimal(volume_value)
            if (open_number <= 0 or high_number <= 0 or low_number <= 0 or close_number <= 0
                    or volume_number < 0 or high_number < max(open_number, close_number)
                    or low_number > min(open_number, close_number) or high_number < low_number):
                ohlc_invariant_rows += 1
        except InvalidOperation:
            ohlc_invariant_rows += 1
        row = {
            "sequence": row_count,
            "symbol": symbol.upper().replace("-", "").replace("/", "").replace("_", ""),
            "intervalCode": interval_code.lower(),
            "source": source.lower(),
            "openTimeUtc": open_text,
            "closeTimeUtc": close_text,
            "open": canonical_decimal(open_value),
            "high": canonical_decimal(high_value),
            "low": canonical_decimal(low_value),
            "close": canonical_decimal(close_value),
        }
        digest.update(json.dumps(row, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
        digest.update(b"\n")
        normalized_row = dict(row)
        normalized_row["closeTimeUtc"] = (open_time + dt.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        normalized_close_digest.update(json.dumps(
            normalized_row, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
        normalized_close_digest.update(b"\n")
    canonical_hash = digest.hexdigest()
    normalized_close_hash = normalized_close_digest.hexdigest()
    if row_count != EXPECTED_ROWS:
        add_blocker("GOLDEN_ROW_COUNT_MISMATCH")
    if first_open != EXPECTED_FIRST:
        add_blocker("GOLDEN_FIRST_OPEN_TIME_MISMATCH")
    if last_open != EXPECTED_LAST:
        add_blocker("GOLDEN_LAST_OPEN_TIME_MISMATCH")
    if duplicate_rows:
        add_blocker("GOLDEN_DUPLICATE_ROWS_PRESENT")
    if lattice_gap_rows:
        add_blocker("GOLDEN_UTC_LATTICE_GAPS_PRESENT")
    if close_time_mismatch_rows:
        add_blocker("GOLDEN_CLOSE_TIME_MISMATCH_PRESENT")
    if ohlc_invariant_rows:
        add_blocker("GOLDEN_OHLC_INVARIANT_FAILURE_PRESENT")
    if scope_mismatch_rows:
        add_blocker("GOLDEN_SCOPE_MISMATCH_PRESENT")
    if canonical_hash != EXPECTED_HASH:
        add_blocker("GOLDEN_CANONICAL_PRICE_BAR_HASH_MISMATCH")
except (OSError, ValueError, RuntimeError, InvalidOperation) as exc:
    add_blocker("PRODUCTION_GOLDEN_QUERY_FAILED")
    warnings.append(f"golden query failed: {str(exc)[:200]}")

status = "READY_FOR_OFF_DEPLOY_AUTHORIZATION" if not blockers else "BLOCKED"
report = {
    "packet": "BTC_DONCHIAN_OFF_DEPLOY_READINESS",
    "boundary": "READ_ONLY_NO_COMMIT_NO_DEPLOY_NO_RESTART_NO_ENV_OR_DB_WRITE_NO_ORDER_NO_OCO_NO_TELEGRAM_NO_BACKFILL",
    "status": status,
    "phase": PHASE,
    "policyMode": POLICY_MODE,
    "productionMetadata": {
        "serverHeadCommit": head_commit or None,
        "originMainCommit": origin_commit or None,
        "deployedCommit": deployed_commit or None,
        "expectedBaseCommit": EXPECTED_BASE_COMMIT or None,
        "serverWorktreeDirty": server_worktree_dirty,
        "untrackedOperationalPaths": untracked_paths,
        "activePort": active_port or None,
        "activePidPresent": bool(active_pid),
        "healthStatus": health_status,
        "mcpToolCount": mcp_tool_count,
        "deployedDonchianTools": deployed_donchian_tools,
    },
    "productionEnv": {
        "donchianModeKeyPresent": mode_key_present,
        "effectiveDonchianMode": effective_mode,
        "unsupportedDonchianKeys": unsupported_keys,
        "runtimeEvidenceEnabled": env_values.get("TRADING_RUNTIME_EVIDENCE_ENABLED", "false").strip().lower() == "true",
        "schemaHardening": schema_env,
    },
    "schema": {
        "requiredTables": sorted(required_columns),
        "requiredColumnsPresent": {
            table: required_columns[table].issubset(schema_columns[table])
            for table in sorted(required_columns)
        },
        "mdKlineScopeIndexPresent": any(
            columns == "symbol,interval_code,source,open_time"
            for _, columns in schema_indexes["md_kline"]
        ),
        "runtimeEvidenceDecisionIndexPresent": any(
            columns == "decision_id"
            for _, columns in schema_indexes["bt_runtime_decision_evidence"]
        ),
        "migrationRequired": False,
    },
    "goldenWindow": {
        "symbol": "BTCUSDT",
        "intervalCode": "1h",
        "source": "okx",
        "expectedRows": EXPECTED_ROWS,
        "actualRows": row_count,
        "expectedFirstOpenTimeUtc": EXPECTED_FIRST.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "actualFirstOpenTimeUtc": first_open.strftime("%Y-%m-%dT%H:%M:%SZ") if first_open else None,
        "expectedLastOpenTimeUtc": EXPECTED_LAST.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "actualLastOpenTimeUtc": last_open.strftime("%Y-%m-%dT%H:%M:%SZ") if last_open else None,
        "duplicateRows": duplicate_rows,
        "latticeGapRows": lattice_gap_rows,
        "closeTimeMismatchRows": close_time_mismatch_rows,
        "closeTimeMismatchSamples": close_time_mismatch_samples,
        "ohlcInvariantFailureRows": ohlc_invariant_rows,
        "scopeMismatchRows": scope_mismatch_rows,
        "expectedCanonicalPriceBarSha256": EXPECTED_HASH,
        "actualCanonicalPriceBarSha256": canonical_hash or None,
        "normalizedCloseTimePriceBarSha256": normalized_close_hash or None,
        "canonicalParityPassed": canonical_hash == EXPECTED_HASH,
    },
    "authorization": {
        "offDeployAuthorized": False,
        "shadowActivationAuthorized": False,
        "liveImplementationPresent": False,
        "orderAllowed": False,
        "ocoMutationAllowed": False,
        "telegramSendAllowed": False,
        "externalBackfillAllowed": False,
        "nextAuthorization": "OFF_CODE_DEPLOY_ONLY" if status == "READY_FOR_OFF_DEPLOY_AUTHORIZATION" else "RESOLVE_BLOCKERS_AND_RERUN",
    },
    "blockers": blockers,
    "warnings": warnings,
}
print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=False))
print(f"btc_donchian_off_deploy_readiness_status={status}")
print(f"golden_canonical_parity_passed={str(canonical_hash == EXPECTED_HASH).lower()}")
print(f"production_effective_donchian_mode={effective_mode}")
print("off_deploy_authorized=false")
print("shadow_activation_authorized=false")
print("order_allowed=false")
print("not_authorization=read-only OFF deployment readiness evidence only; a new explicit authorization is required to commit/deploy, and another one is required for SHADOW")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__PHASE__", $Phase).
    Replace("__EXPECTED_COMMIT__", $ExpectedBaseCommit)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $output = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$output | ForEach-Object { Write-Host $_ }
if ($exitCode -ne 0) {
    throw "BTC Donchian OFF deploy readiness failed before a complete read-only packet was produced (exit $exitCode)."
}

$text = $output -join "`n"
if ($RequireReady -and $text -notmatch "btc_donchian_off_deploy_readiness_status=READY_FOR_OFF_DEPLOY_AUTHORIZATION") {
    throw "BTC Donchian OFF deployment is not ready; inspect the BLOCKED packet and resolve its blockers."
}
