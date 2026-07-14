param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$CanonicalCsvPath = (Join-Path $PSScriptRoot "..\target\research\okx-btc-usdt-1h-final-ledger-v2\okx-btc-usdt-1h-20260713T090000Z\btc-usdt-okx-1h.csv"),
    [string]$ExpectedBaseCommit = "6e369d07c88c5fc641f495fdad7a5ee499cb49b3",
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$expectedCsvSha256 = "74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8"

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

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) {
    throw "A valid SshKey is required."
}
if (-not (Test-Path -LiteralPath $CanonicalCsvPath)) {
    throw "Canonical CSV not found: $CanonicalCsvPath"
}
if ($ExpectedBaseCommit -notmatch "^[0-9a-fA-F]{40}$") {
    throw "ExpectedBaseCommit must be a full 40-character git SHA."
}
foreach ($command in @("ssh", "scp")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command is not available on PATH."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

$resolvedCsv = (Resolve-Path -LiteralPath $CanonicalCsvPath).Path
$actualCsvSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedCsv).Hash.ToLowerInvariant()
if ($actualCsvSha256 -ne $expectedCsvSha256) {
    throw "Canonical CSV SHA-256 mismatch: expected $expectedCsvSha256, actual $actualCsvSha256"
}

$token = [Guid]::NewGuid().ToString("N")
$remoteCsv = "/tmp/agora-btc-donchian-golden-repair-$token.csv"
Assert-RemotePathSafe -Name "RemoteCsv" -Value $remoteCsv

$scpTarget = "${SshHost}:$remoteCsv"
& scp -q -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 -- $resolvedCsv $scpTarget
if ($LASTEXITCODE -ne 0) {
    throw "Failed to copy the canonical CSV to the production host."
}

$applyValue = if ($Apply) { "true" } else { "false" }
$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

export REPAIR_ENV_FILE='__ENVFILE__'
export REPAIR_CSV='__CSV__'
export REPAIR_APPLY='__APPLY__'
export REPAIR_EXPECTED_BASE='__EXPECTED_BASE__'

cleanup() {
  rm -f -- "$REPAIR_CSV"
}
trap cleanup EXIT

timeout 1800s python3 - <<'PY'
from __future__ import annotations

import csv
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import tempfile
import urllib.request
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path

EXPECTED_CSV_SHA256 = "74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8"
EXPECTED_CANONICAL_HASH = "361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d"
EXPECTED_PRE_HASH = "78f1e59dc1a2a80072134bbd501c8f974d6915060b192927bcb1561502f0072f"
EXPECTED_PRE_NORMALIZED_HASH = "96b08185fa83574705e5ddbf1149407dc8a169ad7fb4098b4cf1c009f45558fa"
EXPECTED_HEADERS = [
    "open_time_utc", "open", "high", "low", "close", "volume",
    "confirm", "source", "inst_id", "bar",
]
EXPECTED_FIRST = dt.datetime(2019, 1, 1, 0, 0, 0)
EXPECTED_PREFIX_LAST = dt.datetime(2025, 4, 27, 12, 0, 0)
EXPECTED_PRE_FIRST = dt.datetime(2025, 4, 27, 13, 0, 0)
EXPECTED_LAST = dt.datetime(2026, 7, 13, 8, 0, 0)
EXPECTED_ROWS = 66009
EXPECTED_MISSING_ROWS = 55405
EXPECTED_PRE_ROWS = 10604
TARGET_OPEN = dt.datetime(2026, 5, 1, 17, 0, 0)
TARGET_OLD_CLOSE = dt.datetime(2026, 5, 2, 2, 0, 0)
TARGET_NEW_CLOSE = dt.datetime(2026, 5, 1, 18, 0, 0)
TARGET_OHLC = (Decimal("78212.2"), Decimal("78588"), Decimal("78134.3"), Decimal("78556.4"))
VOLUME_SCALE = Decimal("0.0000000001")

CSV_PATH = Path(os.environ["REPAIR_CSV"])
ENV_FILE = Path(os.environ["REPAIR_ENV_FILE"])
APP_DIR = Path.cwd()
APPLY = os.environ.get("REPAIR_APPLY", "false").lower() == "true"
EXPECTED_BASE = os.environ["REPAIR_EXPECTED_BASE"].lower()


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
        raise RuntimeError("SPRING_DATASOURCE_URL is not a supported jdbc:mysql URL")
    return match.group(1), match.group(2) or "3306", match.group(3)


def parse_utc(value: str) -> dt.datetime:
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")


def sql_time(value: dt.datetime) -> str:
    return value.strftime("%Y-%m-%d %H:%M:%S")


def canonical_decimal(value: str | Decimal) -> str:
    number = value if isinstance(value, Decimal) else Decimal(value)
    rendered = format(number, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return "0" if rendered in ("", "-0") else rendered


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_value(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=APP_DIR, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError((result.stderr or "git command failed").strip()[-300:])
    return result.stdout.strip()


def canonical_row(sequence: int, open_time: dt.datetime, close_time: dt.datetime,
                  open_value: str | Decimal, high_value: str | Decimal,
                  low_value: str | Decimal, close_value: str | Decimal) -> dict[str, object]:
    return {
        "sequence": sequence,
        "symbol": "BTCUSDT",
        "intervalCode": "1h",
        "source": "okx",
        "openTimeUtc": open_time.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "closeTimeUtc": close_time.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "open": canonical_decimal(open_value),
        "high": canonical_decimal(high_value),
        "low": canonical_decimal(low_value),
        "close": canonical_decimal(close_value),
    }


def add_digest_row(digest: "hashlib._Hash", row: dict[str, object]) -> None:
    digest.update(json.dumps(row, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    digest.update(b"\n")


def load_csv() -> list[dict[str, object]]:
    if sha256_file(CSV_PATH) != EXPECTED_CSV_SHA256:
        raise RuntimeError("REMOTE_CANONICAL_CSV_SHA256_MISMATCH")
    rows: list[dict[str, object]] = []
    digest = hashlib.sha256()
    previous: dt.datetime | None = None
    with CSV_PATH.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != EXPECTED_HEADERS:
            raise RuntimeError("CANONICAL_CSV_HEADER_MISMATCH")
        for sequence, raw in enumerate(reader, start=1):
            if (raw["confirm"] != "1" or raw["source"] != "okx_spot"
                    or raw["inst_id"] != "BTC-USDT" or raw["bar"] != "1H"):
                raise RuntimeError(f"CANONICAL_CSV_SCOPE_MISMATCH_AT_{sequence}")
            open_time = parse_utc(raw["open_time_utc"])
            if previous is not None and open_time != previous + dt.timedelta(hours=1):
                raise RuntimeError(f"CANONICAL_CSV_UTC_LATTICE_MISMATCH_AT_{sequence}")
            previous = open_time
            try:
                open_value = Decimal(raw["open"])
                high_value = Decimal(raw["high"])
                low_value = Decimal(raw["low"])
                close_value = Decimal(raw["close"])
                volume_value = Decimal(raw["volume"])
            except InvalidOperation as exc:
                raise RuntimeError(f"CANONICAL_CSV_DECIMAL_INVALID_AT_{sequence}") from exc
            values = (open_value, high_value, low_value, close_value, volume_value)
            if any(not value.is_finite() for value in values):
                raise RuntimeError(f"CANONICAL_CSV_NONFINITE_VALUE_AT_{sequence}")
            if (min(open_value, high_value, low_value, close_value) <= 0
                    or volume_value < 0 or high_value < max(open_value, close_value)
                    or low_value > min(open_value, close_value) or high_value < low_value):
                raise RuntimeError(f"CANONICAL_CSV_OHLC_INVARIANT_AT_{sequence}")
            close_time = open_time + dt.timedelta(hours=1)
            add_digest_row(digest, canonical_row(
                sequence, open_time, close_time,
                open_value, high_value, low_value, close_value,
            ))
            rows.append({
                "open_time": open_time,
                "close_time": close_time,
                "open": open_value,
                "high": high_value,
                "low": low_value,
                "close": close_value,
                "volume": volume_value.quantize(VOLUME_SCALE, rounding=ROUND_HALF_UP),
            })
    if len(rows) != EXPECTED_ROWS:
        raise RuntimeError(f"CANONICAL_CSV_ROW_COUNT_MISMATCH_{len(rows)}")
    if rows[0]["open_time"] != EXPECTED_FIRST or rows[-1]["open_time"] != EXPECTED_LAST:
        raise RuntimeError("CANONICAL_CSV_BOUNDARY_MISMATCH")
    if rows[EXPECTED_MISSING_ROWS - 1]["open_time"] != EXPECTED_PREFIX_LAST:
        raise RuntimeError("CANONICAL_CSV_PREFIX_LAST_MISMATCH")
    if rows[EXPECTED_MISSING_ROWS]["open_time"] != EXPECTED_PRE_FIRST:
        raise RuntimeError("CANONICAL_CSV_OVERLAP_FIRST_MISMATCH")
    if digest.hexdigest() != EXPECTED_CANONICAL_HASH:
        raise RuntimeError("CANONICAL_CSV_PRICE_BAR_HASH_MISMATCH")
    return rows


def inspect_db_rows(lines: list[str], *, expect_complete: bool,
                    csv_rows: list[dict[str, object]]) -> dict[str, object]:
    digest = hashlib.sha256()
    normalized_digest = hashlib.sha256()
    previous: dt.datetime | None = None
    mismatches: list[tuple[dt.datetime, dt.datetime]] = []
    inserted_volume_mismatches = 0
    target_ohlc: tuple[Decimal, Decimal, Decimal, Decimal] | None = None
    for sequence, raw in enumerate(lines, start=1):
        parts = raw.split("\t")
        if len(parts) != 10:
            raise RuntimeError("DB_FIELD_COUNT_MISMATCH")
        (open_text, close_text, symbol, interval_code, source,
         open_text_value, high_text_value, low_text_value, close_text_value, volume_text_value) = parts
        open_time = parse_utc(open_text)
        close_time = parse_utc(close_text)
        try:
            open_value = Decimal(open_text_value)
            high_value = Decimal(high_text_value)
            low_value = Decimal(low_text_value)
            close_value = Decimal(close_text_value)
            volume_value = Decimal(volume_text_value)
        except InvalidOperation as exc:
            raise RuntimeError("DB_DECIMAL_INVALID") from exc
        if symbol != "BTCUSDT" or interval_code.lower() != "1h" or source.lower() != "okx":
            raise RuntimeError("DB_SCOPE_MISMATCH")
        if previous is not None and open_time != previous + dt.timedelta(hours=1):
            raise RuntimeError("DB_UTC_LATTICE_MISMATCH")
        previous = open_time
        if (min(open_value, high_value, low_value, close_value) <= 0
                or volume_value < 0 or high_value < max(open_value, close_value)
                or low_value > min(open_value, close_value) or high_value < low_value):
            raise RuntimeError("DB_OHLC_INVARIANT_FAILURE")
        if close_time != open_time + dt.timedelta(hours=1):
            mismatches.append((open_time, close_time))
        row = canonical_row(
            sequence, open_time, close_time,
            open_value, high_value, low_value, close_value,
        )
        add_digest_row(digest, row)
        normalized = dict(row)
        normalized["closeTimeUtc"] = (open_time + dt.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        add_digest_row(normalized_digest, normalized)
        if expect_complete and sequence <= EXPECTED_MISSING_ROWS:
            expected = csv_rows[sequence - 1]
            if (open_time != expected["open_time"]
                    or open_value != expected["open"] or high_value != expected["high"]
                    or low_value != expected["low"] or close_value != expected["close"]
                    or volume_value != expected["volume"]):
                inserted_volume_mismatches += 1
        if open_time == TARGET_OPEN:
            target_ohlc = (open_value, high_value, low_value, close_value)
    return {
        "count": len(lines),
        "first": parse_utc(lines[0].split("\t", 1)[0]) if lines else None,
        "last": parse_utc(lines[-1].split("\t", 1)[0]) if lines else None,
        "hash": digest.hexdigest(),
        "normalized_hash": normalized_digest.hexdigest(),
        "close_mismatches": mismatches,
        "inserted_value_mismatches": inserted_volume_mismatches,
        "target_ohlc": target_ohlc,
    }


def mysql_select_sql(*, for_update: bool) -> str:
    suffix = " FOR UPDATE" if for_update else ""
    return f"""
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
ORDER BY open_time ASC{suffix};
"""


env_values = read_env(ENV_FILE)
effective_mode = env_values.get("TRADING_BTC_DONCHIAN_SHADOW_MODE", "OFF").strip().upper()
if effective_mode != "OFF":
    raise RuntimeError(f"DONCHIAN_MODE_NOT_OFF_{effective_mode}")
unsupported_keys = sorted(
    key for key in env_values
    if key.startswith("TRADING_BTC_DONCHIAN_SHADOW_")
    and key != "TRADING_BTC_DONCHIAN_SHADOW_MODE"
)
if unsupported_keys:
    raise RuntimeError("UNSUPPORTED_DONCHIAN_ENV_KEYS_PRESENT")

head = git_value("rev-parse", "HEAD").lower()
origin_main = git_value("rev-parse", "origin/main").lower()
deployed = (APP_DIR / "app.commit").read_text(encoding="utf-8").strip().lower()
tracked_status = git_value("status", "--porcelain", "--untracked-files=no")
if head != EXPECTED_BASE or origin_main != EXPECTED_BASE or deployed != EXPECTED_BASE:
    raise RuntimeError("PRODUCTION_BASE_COMMIT_MISMATCH")
if tracked_status:
    raise RuntimeError("PRODUCTION_TRACKED_WORKTREE_DIRTY")

active_port = (APP_DIR / "app.port").read_text(encoding="utf-8").strip()
active_pid = (APP_DIR / "app.pid").read_text(encoding="utf-8").strip()
if active_port not in {"8084", "8085"} or not active_pid.isdigit():
    raise RuntimeError("ACTIVE_RUNTIME_METADATA_INVALID")
if subprocess.run(["kill", "-0", active_pid], check=False).returncode != 0:
    raise RuntimeError("ACTIVE_RUNTIME_PID_NOT_RUNNING")
with urllib.request.urlopen(
        f"http://127.0.0.1:{active_port}/api/actuator/health", timeout=15) as response:
    health = json.loads(response.read().decode("utf-8", "replace"))
if health.get("status") != "UP":
    raise RuntimeError("ACTIVE_RUNTIME_HEALTH_NOT_UP")

csv_rows = load_csv()
host, port, database = parse_jdbc(env_values.get("SPRING_DATASOURCE_URL", ""))
username = env_values.get("SPRING_DATASOURCE_USERNAME", "")
password = env_values.get("SPRING_DATASOURCE_PASSWORD", "")
if not username or not password:
    raise RuntimeError("DATABASE_CREDENTIALS_MISSING")

child_env = os.environ.copy()
child_env["MYSQL_PWD"] = password
command = [
    "mysql", "--batch", "--raw", "--skip-column-names", "--quick", "--unbuffered",
    "--default-character-set=utf8mb4", "--connect-timeout=10",
    "-h", host, "-P", port, "-u", username, database,
]

stderr_file = tempfile.TemporaryFile(mode="w+t", encoding="utf-8")
process = subprocess.Popen(
    command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr_file,
    text=True, bufsize=1, env=child_env,
)
if process.stdin is None or process.stdout is None:
    raise RuntimeError("MYSQL_CLI_PIPE_UNAVAILABLE")


def mysql_error() -> str:
    stderr_file.flush()
    stderr_file.seek(0)
    return stderr_file.read().strip()[-800:]


def send(sql: str) -> None:
    if process.poll() is not None:
        raise RuntimeError(f"MYSQL_CLI_EXITED_{process.returncode}: {mysql_error()}")
    process.stdin.write(sql)
    if not sql.endswith("\n"):
        process.stdin.write("\n")
    process.stdin.flush()


def read_until(marker: str) -> list[str]:
    lines: list[str] = []
    while True:
        raw = process.stdout.readline()
        if raw == "":
            process.poll()
            raise RuntimeError(f"MYSQL_MARKER_NOT_REACHED_{marker}: {mysql_error()}")
        value = raw.rstrip("\r\n")
        if value == marker:
            return lines
        lines.append(value)


transaction_open = False
committed = False
try:
    send("SET SESSION time_zone = '+00:00'; SELECT CONCAT('SQL_MODE=', @@SESSION.sql_mode); SELECT 'SESSION_READY';")
    session_lines = read_until("SESSION_READY")
    if len(session_lines) != 1 or "STRICT_" not in session_lines[0]:
        raise RuntimeError("MYSQL_SESSION_NOT_STRICT")

    send("SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE; START TRANSACTION; SELECT 'TX_STARTED';")
    if read_until("TX_STARTED"):
        raise RuntimeError("UNEXPECTED_TX_START_OUTPUT")
    transaction_open = True

    send(mysql_select_sql(for_update=True) + "SELECT 'PRESTATE_END';")
    pre_lines = read_until("PRESTATE_END")
    pre = inspect_db_rows(pre_lines, expect_complete=False, csv_rows=csv_rows)
    expected_pre_mismatch = [(TARGET_OPEN, TARGET_OLD_CLOSE)]
    if (pre["count"] != EXPECTED_PRE_ROWS or pre["first"] != EXPECTED_PRE_FIRST
            or pre["last"] != EXPECTED_LAST or pre["hash"] != EXPECTED_PRE_HASH
            or pre["normalized_hash"] != EXPECTED_PRE_NORMALIZED_HASH
            or pre["close_mismatches"] != expected_pre_mismatch
            or pre["target_ohlc"] != TARGET_OHLC):
        raise RuntimeError("PRODUCTION_PRESTATE_CONTRACT_MISMATCH")

    if not APPLY:
        send("ROLLBACK; SELECT 'ROLLBACK_DONE';")
        if read_until("ROLLBACK_DONE"):
            raise RuntimeError("UNEXPECTED_ROLLBACK_OUTPUT")
        transaction_open = False
        result = {
            "status": "READY_TO_APPLY",
            "mutationApplied": False,
            "donchianEffectiveMode": effective_mode,
            "productionBaseCommit": head,
            "canonicalCsvSha256": EXPECTED_CSV_SHA256,
            "canonicalCsvRows": len(csv_rows),
            "preRepairRows": pre["count"],
            "missingRows": EXPECTED_MISSING_ROWS,
            "closeTimeRowsToFix": len(pre["close_mismatches"]),
            "expectedCanonicalPriceBarSha256": EXPECTED_CANONICAL_HASH,
        }
    else:
        prefix = csv_rows[:EXPECTED_MISSING_ROWS]
        for offset in range(0, len(prefix), 250):
            values: list[str] = []
            for row in prefix[offset:offset + 250]:
                values.append(
                    "('BTCUSDT','1h','{}','{}',{},{},{},{},{},'okx')".format(
                        sql_time(row["open_time"]), sql_time(row["close_time"]),
                        format(row["open"], "f"), format(row["high"], "f"),
                        format(row["low"], "f"), format(row["close"], "f"),
                        format(row["volume"], "f"),
                    )
                )
            send(
                "INSERT INTO md_kline "
                "(symbol, interval_code, open_time, close_time, open_price, high_price, low_price, close_price, volume, source) VALUES "
                + ",".join(values) + ";"
            )

        send("""
UPDATE md_kline
SET close_time = '2026-05-01 18:00:00'
WHERE symbol = 'BTCUSDT'
  AND interval_code = '1h'
  AND source = 'okx'
  AND open_time = '2026-05-01 17:00:00'
  AND close_time = '2026-05-02 02:00:00'
  AND open_price = 78212.2
  AND high_price = 78588
  AND low_price = 78134.3
  AND close_price = 78556.4;
SELECT ROW_COUNT();
SELECT 'UPDATE_END';
""")
        update_lines = read_until("UPDATE_END")
        if update_lines != ["1"]:
            raise RuntimeError(f"TARGET_CLOSE_TIME_UPDATE_COUNT_MISMATCH_{update_lines}")

        send(mysql_select_sql(for_update=False) + "SELECT 'POSTSTATE_END';")
        post_lines = read_until("POSTSTATE_END")
        post = inspect_db_rows(post_lines, expect_complete=True, csv_rows=csv_rows)
        if (post["count"] != EXPECTED_ROWS or post["first"] != EXPECTED_FIRST
                or post["last"] != EXPECTED_LAST or post["hash"] != EXPECTED_CANONICAL_HASH
                or post["normalized_hash"] != EXPECTED_CANONICAL_HASH
                or post["close_mismatches"] or post["inserted_value_mismatches"]
                or post["target_ohlc"] != TARGET_OHLC):
            raise RuntimeError("TRANSACTIONAL_POSTSTATE_CONTRACT_MISMATCH")

        send("COMMIT; SELECT 'COMMIT_DONE';")
        if read_until("COMMIT_DONE"):
            raise RuntimeError("UNEXPECTED_COMMIT_OUTPUT")
        transaction_open = False
        committed = True
        result = {
            "status": "COMMITTED",
            "mutationApplied": True,
            "donchianEffectiveMode": effective_mode,
            "productionBaseCommit": head,
            "canonicalCsvSha256": EXPECTED_CSV_SHA256,
            "insertedRows": EXPECTED_MISSING_ROWS,
            "fixedCloseTimeRows": 1,
            "finalRows": post["count"],
            "firstOpenTimeUtc": EXPECTED_FIRST.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "lastOpenTimeUtc": EXPECTED_LAST.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "canonicalPriceBarSha256": post["hash"],
            "transactionalCanonicalParityPassed": True,
        }
except Exception:
    if transaction_open and process.poll() is None:
        try:
            send("ROLLBACK; SELECT 'ROLLBACK_DONE';")
            read_until("ROLLBACK_DONE")
            transaction_open = False
        except Exception:
            pass
    raise
finally:
    if process.poll() is None:
        try:
            process.stdin.close()
        except OSError:
            pass
        process.wait(timeout=60)
    stderr_text = mysql_error()
    stderr_file.close()
    if process.returncode not in (0, None):
        raise RuntimeError(f"MYSQL_CLI_FAILED_{process.returncode}: {stderr_text}")

print(json.dumps(result, ensure_ascii=False, sort_keys=True))
if APPLY and not committed:
    raise RuntimeError("REPAIR_NOT_COMMITTED")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__CSV__", $remoteCsv)
$remoteScript = $remoteScript.Replace("__APPLY__", $applyValue)
$remoteScript = $remoteScript.Replace("__EXPECTED_BASE__", $ExpectedBaseCommit.ToLowerInvariant())

try {
    $output = @($remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Output $_ }
    if ($exitCode -ne 0) {
        throw "BTC Donchian golden-data repair failed with exit code $exitCode."
    }
}
finally {
    & ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "rm -f -- '$remoteCsv'" 2>$null | Out-Null
}
