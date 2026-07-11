[CmdletBinding()]
param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1d",
    [string]$Source = "binance",
    [datetime]$ReplayStartUtc = [datetime]::SpecifyKind([datetime]"2017-08-17T00:00:00", [DateTimeKind]::Utc),
    [datetime]$EndExclusiveUtc = [datetime]::SpecifyKind([datetime]::UtcNow.Date, [DateTimeKind]::Utc),
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
        throw "$Name contains unsupported characters for read-only preflight."
    }
}

function Convert-ToUtc {
    param([datetime]$Value)
    if ($Value.Kind -eq [DateTimeKind]::Unspecified) {
        return [datetime]::SpecifyKind($Value, [DateTimeKind]::Utc)
    }
    return $Value.ToUniversalTime()
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "SSH key not found: $SshKey"
}
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16
Assert-SmokeTokenSafe -Name "Source" -Value $Source -MaxLength 16

if ($IntervalCode.ToLowerInvariant() -ne "1d") {
    throw "Strategy 485 replay preflight currently requires IntervalCode=1d."
}
if ($Source.ToLowerInvariant() -ne "binance") {
    throw "Strategy 485 replay preflight currently requires Source=binance."
}

$startUtc = Convert-ToUtc -Value $ReplayStartUtc
$endUtc = Convert-ToUtc -Value $EndExclusiveUtc
if ($startUtc.TimeOfDay -ne [TimeSpan]::Zero -or $endUtc.TimeOfDay -ne [TimeSpan]::Zero) {
    throw "ReplayStartUtc and EndExclusiveUtc must align to UTC midnight."
}
if ($startUtc -ge $endUtc) {
    throw "ReplayStartUtc must be earlier than EndExclusiveUtc."
}
$rangeDays = [int]($endUtc - $startUtc).TotalDays
if ($rangeDays -lt 365 -or $rangeDays -gt 5000) {
    throw "Replay range must be between 365 and 5000 days."
}

$startText = $startUtc.ToString("yyyy-MM-ddTHH:mm:ss", [Globalization.CultureInfo]::InvariantCulture)
$endText = $endUtc.ToString("yyyy-MM-ddTHH:mm:ss", [Globalization.CultureInfo]::InvariantCulture)
$requireReadyText = $RequireReady.IsPresent.ToString().ToLowerInvariant()

$remoteScriptTemplate = @'
set -euo pipefail
cd '__APP_DIR__'
command -v mysql >/dev/null 2>&1 || { echo "FAIL: mysql is not available on server" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "FAIL: python3 is not available on server" >&2; exit 1; }

export PREFLIGHT_ENV_FILE='__ENV_FILE__'
export PREFLIGHT_SYMBOL='__SYMBOL__'
export PREFLIGHT_INTERVAL='__INTERVAL__'
export PREFLIGHT_SOURCE='__SOURCE__'
export PREFLIGHT_START='__START__'
export PREFLIGHT_END='__END__'
export PREFLIGHT_REQUIRE_READY='__REQUIRE_READY__'

python3 - <<'PY'
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

DAY_MS = 86_400_000
MAX_RANGE_DAYS = 730
VISION_DEFAULT = "https://data-api.binance.vision/api/v3/klines"
BINANCE_US_EVIDENCE_ENDPOINT = "https://api.binance.us/api/v3/klines"

env_file = os.environ["PREFLIGHT_ENV_FILE"]
symbol = os.environ["PREFLIGHT_SYMBOL"].upper()
interval = os.environ["PREFLIGHT_INTERVAL"].lower()
source = os.environ["PREFLIGHT_SOURCE"].lower()
start = datetime.fromisoformat(os.environ["PREFLIGHT_START"]).replace(tzinfo=timezone.utc)
end = datetime.fromisoformat(os.environ["PREFLIGHT_END"]).replace(tzinfo=timezone.utc)
require_ready = os.environ.get("PREFLIGHT_REQUIRE_READY", "false").lower() == "true"

if not re.fullmatch(r"[A-Z0-9_-]{1,31}", symbol):
    raise RuntimeError("unsafe symbol")
if interval != "1d" or source != "binance":
    raise RuntimeError("strategy 485 preflight requires binance 1d")

def read_env(path):
    values = {}
    with open(path, "r", encoding="utf-8") as handle:
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

def bool_value(values, key, default=False):
    raw = values.get(key, "true" if default else "false").strip().lower()
    return raw in ("1", "true", "yes", "y", "on")

def parse_jdbc(url):
    match = re.match(r"^jdbc:mysql://([^:/?]+)(?::([0-9]+))?/([^?;]+)", (url or "").strip())
    if not match:
        raise RuntimeError("SPRING_DATASOURCE_URL is not a supported jdbc:mysql URL")
    return match.group(1), match.group(2) or "3306", match.group(3).split("/", 1)[0]

def sql_quote(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"

def decimal_value(value):
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise RuntimeError("invalid decimal value in kline data") from exc

def decimal_text(value):
    text = format(decimal_value(value), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"

def iso_from_ms(value):
    return datetime.fromtimestamp(value / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")

def canonical_bar(open_ms, bar):
    return "|".join([
        iso_from_ms(open_ms),
        decimal_text(bar["open"]),
        decimal_text(bar["high"]),
        decimal_text(bar["low"]),
        decimal_text(bar["close"]),
        decimal_text(bar["volume"]),
    ])

def digest_rows(rows):
    payload = "\n".join(canonical_bar(key, rows[key]) for key in sorted(rows))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()

def run_mysql(values, query):
    host, port, database = parse_jdbc(values.get("SPRING_DATASOURCE_URL", ""))
    user = values.get("SPRING_DATASOURCE_USERNAME", "")
    password = values.get("SPRING_DATASOURCE_PASSWORD", "")
    if not user:
        raise RuntimeError("SPRING_DATASOURCE_USERNAME is missing")
    command = [
        "mysql", "--batch", "--raw", "--skip-column-names", "--connect-timeout=10",
        "-h", host, "-P", port, "-u", user, database, "-e", query,
    ]
    child_env = os.environ.copy()
    child_env["MYSQL_PWD"] = password
    completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               env=child_env, timeout=45)
    if completed.returncode != 0:
        raise RuntimeError((completed.stderr or completed.stdout or "mysql failed").strip())
    return [line for line in completed.stdout.splitlines() if line.strip()]

def fetch_klines(endpoint, range_start, range_end, endpoint_label):
    parsed = urllib.parse.urlparse(endpoint)
    if parsed.scheme != "https" or not parsed.netloc:
        raise RuntimeError(f"{endpoint_label} REST endpoint must be HTTPS")
    cursor = int(range_start.timestamp() * 1000)
    end_ms = int(range_end.timestamp() * 1000)
    rows = {}
    duplicates = []
    while cursor < end_ms:
        query = urllib.parse.urlencode({
            "symbol": symbol,
            "interval": interval,
            "startTime": cursor,
            "endTime": end_ms - 1,
            "limit": 1000,
        })
        separator = "&" if parsed.query else "?"
        request = urllib.request.Request(endpoint + separator + query,
                                         headers={"User-Agent": "agora-trading-api-read-only-preflight/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            page = json.loads(response.read().decode("utf-8"))
        if not page:
            break
        for raw in page:
            open_ms = int(raw[0])
            if open_ms < cursor or open_ms >= end_ms:
                continue
            if open_ms in rows:
                duplicates.append(open_ms)
            rows[open_ms] = {
                "open": decimal_value(raw[1]),
                "high": decimal_value(raw[2]),
                "low": decimal_value(raw[3]),
                "close": decimal_value(raw[4]),
                "volume": decimal_value(raw[5]),
            }
        last_open = int(page[-1][0])
        next_cursor = last_open + DAY_MS
        if next_cursor <= cursor:
            raise RuntimeError(f"{endpoint_label} cursor did not advance")
        cursor = next_cursor
        if len(page) < 1000:
            break
    return rows, duplicates, parsed

def contiguous_ranges(keys):
    ordered = sorted(keys)
    if not ordered:
        return []
    result = []
    range_start = ordered[0]
    previous = ordered[0]
    for current in ordered[1:]:
        if current != previous + DAY_MS:
            result.append((range_start, previous + DAY_MS))
            range_start = current
        previous = current
    result.append((range_start, previous + DAY_MS))
    return result

def planned_chunks(keys):
    chunks = []
    for range_start, range_end in contiguous_ranges(keys):
        cursor = datetime.fromtimestamp(range_start / 1000, tz=timezone.utc)
        end_dt = datetime.fromtimestamp(range_end / 1000, tz=timezone.utc)
        while cursor < end_dt:
            chunk_end = min(cursor + timedelta(days=MAX_RANGE_DAYS), end_dt)
            bars = (chunk_end - cursor).days
            chunks.append({
                "tool": "backfillBinanceKlinesRange",
                "arguments": {
                    "symbol": symbol,
                    "intervalCode": interval,
                    "startUtc": cursor.strftime("%Y-%m-%dT%H:%M:%S"),
                    "endUtc": chunk_end.strftime("%Y-%m-%dT%H:%M:%S"),
                    "replaceExisting": False,
                },
                "bars": bars,
                "maxRangeDays": MAX_RANGE_DAYS,
                "plannedOnly": True,
            })
            cursor = chunk_end
    return chunks

values = read_env(env_file)
endpoint = values.get("MARKET_BINANCE_SPOT_REST_BASE_URL", VISION_DEFAULT).strip() or VISION_DEFAULT

query = f"""
SELECT DATE_FORMAT(open_time, '%Y-%m-%dT%H:%i:%s'),
       CAST(open_price AS CHAR), CAST(high_price AS CHAR), CAST(low_price AS CHAR),
       CAST(close_price AS CHAR), CAST(volume AS CHAR)
FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE symbol = {sql_quote(symbol)}
  AND interval_code = {sql_quote(interval)}
  AND source = {sql_quote(source)}
  AND open_time >= {sql_quote(start.strftime('%Y-%m-%d %H:%M:%S'))}
  AND open_time < {sql_quote(end.strftime('%Y-%m-%d %H:%M:%S'))}
ORDER BY open_time ASC
"""

db_rows = {}
db_duplicates = []
for line in run_mysql(values, query):
    columns = line.split("\t")
    if len(columns) != 6:
        raise RuntimeError("unexpected md_kline SELECT shape")
    open_dt = datetime.strptime(columns[0], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    open_ms = int(open_dt.timestamp() * 1000)
    if open_ms in db_rows:
        db_duplicates.append(open_ms)
    db_rows[open_ms] = {
        "open": decimal_value(columns[1]),
        "high": decimal_value(columns[2]),
        "low": decimal_value(columns[3]),
        "close": decimal_value(columns[4]),
        "volume": decimal_value(columns[5]),
    }

vision_rows, vision_duplicates, endpoint_parts = fetch_klines(endpoint, start, end, "Binance Vision")
expected_count = int((end - start).total_seconds() // 86400)
expected_keys = [int(start.timestamp() * 1000) + index * DAY_MS for index in range(expected_count)]
provider_missing = [key for key in expected_keys if key not in vision_rows]
provider_extra = [key for key in vision_rows if key not in set(expected_keys)]

db_keys = set(db_rows)
vision_keys = set(vision_rows)
missing = sorted(vision_keys - db_keys)
extra = sorted(db_keys - vision_keys)
overlap = sorted(db_keys & vision_keys)
mismatches = []
mismatch_field_counts = {field: 0 for field in ("open", "high", "low", "close", "volume")}
for key in overlap:
    fields = [field for field in ("open", "high", "low", "close", "volume")
              if db_rows[key][field] != vision_rows[key][field]]
    if fields:
        for field in fields:
            mismatch_field_counts[field] += 1
        mismatches.append({
            "openTimeUtc": iso_from_ms(key),
            "fields": fields,
            "db": canonical_bar(key, db_rows[key]),
            "vision": canonical_bar(key, vision_rows[key]),
        })

mismatch_keys = [int(datetime.fromisoformat(row["openTimeUtc"]).replace(tzinfo=timezone.utc).timestamp() * 1000)
                 for row in mismatches]
legacy_us_rows = {}
legacy_us_duplicates = []
legacy_us_parts = urllib.parse.urlparse(BINANCE_US_EVIDENCE_ENDPOINT)
legacy_us_match_keys = []
legacy_us_mismatch_keys = []
legacy_us_missing_keys = []
legacy_us_existing_target_rows = 0
source_column_max_length = None
if mismatch_keys:
    legacy_start = datetime.fromtimestamp(min(mismatch_keys) / 1000, tz=timezone.utc)
    legacy_end = datetime.fromtimestamp((max(mismatch_keys) + DAY_MS) / 1000, tz=timezone.utc)
    legacy_us_rows, legacy_us_duplicates, legacy_us_parts = fetch_klines(
        BINANCE_US_EVIDENCE_ENDPOINT, legacy_start, legacy_end, "Binance.US")
    for key in mismatch_keys:
        if key not in legacy_us_rows:
            legacy_us_missing_keys.append(key)
        elif db_rows[key] == legacy_us_rows[key]:
            legacy_us_match_keys.append(key)
        else:
            legacy_us_mismatch_keys.append(key)

    target_count_query = f"""
SELECT COUNT(*)
FROM md_kline FORCE INDEX (idx_md_kline_sym_int_src_open)
WHERE symbol = {sql_quote(symbol)}
  AND interval_code = {sql_quote(interval)}
  AND source = 'binance_us'
  AND open_time >= {sql_quote(legacy_start.strftime('%Y-%m-%d %H:%M:%S'))}
  AND open_time < {sql_quote(legacy_end.strftime('%Y-%m-%d %H:%M:%S'))}
"""
    target_count_rows = run_mysql(values, target_count_query)
    legacy_us_existing_target_rows = int(target_count_rows[0]) if target_count_rows else -1

source_length_rows = run_mysql(values, """
SELECT CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'md_kline' AND COLUMN_NAME = 'source'
""")
if source_length_rows:
    source_column_max_length = int(source_length_rows[0])

legacy_source_relabel_evidence_ok = (
    bool(mismatch_keys)
    and legacy_us_parts.hostname == "api.binance.us"
    and not legacy_us_duplicates
    and not legacy_us_missing_keys
    and not legacy_us_mismatch_keys
    and len(legacy_us_match_keys) == len(mismatch_keys)
    and legacy_us_existing_target_rows == 0
    and source_column_max_length is not None
    and source_column_max_length >= len("binance_us")
)
post_relabel_missing = sorted(set(missing) | set(mismatch_keys)) if legacy_source_relabel_evidence_ok else missing
post_relabel_chunks = planned_chunks(post_relabel_missing)

chunks = planned_chunks(missing)
external_backfills_enabled = bool_value(values, "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED", False)
primary = values.get("TRADING_SIGNAL_SOURCE_PRIMARY", "").strip().upper()
local_enabled = bool_value(values, "TRADINGVIEW_LOCAL_ENABLED", False)
execution_mode = values.get("TRADINGVIEW_LOCAL_EXECUTION_MODE", "LEGACY").strip().upper().replace("-", "_")
execution_dry_run = bool_value(values, "TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN", True)
live_order_enabled = bool_value(values, "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED", False)
safety_ok = (
    not external_backfills_enabled
    and primary == "LOCAL_TRADINGVIEW"
    and local_enabled
    and execution_mode == "BTC_BASE_DRY_RUN"
    and execution_dry_run
    and not live_order_enabled
)
provider_ok = (
    endpoint_parts.hostname == "data-api.binance.vision"
    and not vision_duplicates
    and not provider_missing
    and not provider_extra
    and len(vision_rows) == expected_count
)
existing_data_ok = not db_duplicates and not extra and not mismatches
chunk_plan_ok = all(0 < chunk["bars"] <= MAX_RANGE_DAYS for chunk in chunks)

if not provider_ok:
    status = "BLOCKED_BINANCE_VISION_COVERAGE"
    next_action = "Fix the read-only Binance Vision source before any import authorization."
elif not existing_data_ok:
    status = "BLOCKED_EXISTING_BINANCE_DATA_MISMATCH"
    next_action = (
        "Obtain separate exact authorization to relabel the proven Binance.US rows as binance_us, then backfill "
        "the post-relabel missing global Binance ranges with replaceExisting=false."
        if legacy_source_relabel_evidence_ok else
        "Review existing production Binance rows; INSERT_MISSING_ONLY is not sufficient while overlap differs."
    )
elif not safety_ok:
    status = "BLOCKED_RUNTIME_SAFETY_FLAGS"
    next_action = "Restore BTC_BASE_DRY_RUN, live-order=false, and external-backfills=false before requesting import authorization."
elif missing and chunk_plan_ok:
    status = "READY_FOR_SEPARATE_EXTERNAL_BACKFILL_AUTHORIZATION"
    next_action = "Obtain exact production env/import authorization, execute the planned chunks with replaceExisting=false, restore the guard to false, then rerun this preflight and parity readiness."
elif not missing:
    status = "REPLAY_HISTORY_ALREADY_COMPLETE"
    next_action = "Rerun strategy 485 parity readiness; no Binance history import is needed."
else:
    status = "BLOCKED_CHUNK_PLAN_INVALID"
    next_action = "Repair the bounded chunk plan before requesting import authorization."

overlap_db = {key: db_rows[key] for key in overlap}
overlap_vision = {key: vision_rows[key] for key in overlap}
packet = {
    "status": status,
    "boundary": "READ_ONLY_PRODUCTION_DB_AND_BINANCE_VISION",
    "strategyId": 485,
    "symbol": symbol,
    "intervalCode": interval,
    "source": source,
    "replayStartUtc": start.strftime("%Y-%m-%dT%H:%M:%S"),
    "endExclusiveUtc": end.strftime("%Y-%m-%dT%H:%M:%S"),
    "expectedBars": expected_count,
    "visionBars": len(vision_rows),
    "visionFirstBarUtc": iso_from_ms(min(vision_rows)) if vision_rows else None,
    "visionLastBarUtc": iso_from_ms(max(vision_rows)) if vision_rows else None,
    "visionSha256": digest_rows(vision_rows),
    "visionDuplicateCount": len(vision_duplicates),
    "visionMissingCount": len(provider_missing),
    "visionExtraCount": len(provider_extra),
    "productionDbBars": len(db_rows),
    "productionDbFirstBarUtc": iso_from_ms(min(db_rows)) if db_rows else None,
    "productionDbLastBarUtc": iso_from_ms(max(db_rows)) if db_rows else None,
    "productionDbSha256": digest_rows(db_rows),
    "productionDbDuplicateCount": len(db_duplicates),
    "overlapBars": len(overlap),
    "overlapDbSha256": digest_rows(overlap_db),
    "overlapVisionSha256": digest_rows(overlap_vision),
    "overlapMatchCount": len(overlap) - len(mismatches),
    "overlapMismatchCount": len(mismatches),
    "overlapMismatchFirstBarUtc": mismatches[0]["openTimeUtc"] if mismatches else None,
    "overlapMismatchLastBarUtc": mismatches[-1]["openTimeUtc"] if mismatches else None,
    "overlapMismatchFieldCounts": mismatch_field_counts,
    "overlapMismatchSample": mismatches[:10],
    "legacySourceDispositionStatus": (
        "READY_FOR_SEPARATE_SOURCE_RELABEL_AND_BACKFILL_AUTHORIZATION_NOT_MUTATION"
        if legacy_source_relabel_evidence_ok else "LEGACY_SOURCE_NOT_PROVEN"
    ),
    "legacyBinanceUsEndpoint": BINANCE_US_EVIDENCE_ENDPOINT,
    "legacyBinanceUsEndpointVerified": legacy_us_parts.hostname == "api.binance.us",
    "legacyBinanceUsFetchedBars": len(legacy_us_rows),
    "legacyBinanceUsDuplicateCount": len(legacy_us_duplicates),
    "legacyBinanceUsMatchCount": len(legacy_us_match_keys),
    "legacyBinanceUsMismatchCount": len(legacy_us_mismatch_keys),
    "legacyBinanceUsMissingCount": len(legacy_us_missing_keys),
    "legacyBinanceUsMatchSha256": digest_rows({key: db_rows[key] for key in legacy_us_match_keys}),
    "sourceColumnMaxLength": source_column_max_length,
    "sourceRelabelTarget": "binance_us",
    "sourceRelabelExistingTargetRows": legacy_us_existing_target_rows,
    "sourceRelabelEvidenceOk": legacy_source_relabel_evidence_ok,
    "sourceRelabelPlan": {
        "plannedOnly": True,
        "fromSource": "binance",
        "toSource": "binance_us",
        "rowCount": len(mismatch_keys),
        "firstBarUtc": mismatches[0]["openTimeUtc"] if mismatches else None,
        "lastBarUtc": mismatches[-1]["openTimeUtc"] if mismatches else None,
        "preserveRows": True,
        "deleteRows": False,
        "updateSqlEmitted": False,
        "mutationAllowed": False,
    },
    "missingBars": len(missing),
    "missingFirstBarUtc": iso_from_ms(missing[0]) if missing else None,
    "missingLastBarUtc": iso_from_ms(missing[-1]) if missing else None,
    "extraDbBars": len(extra),
    "extraDbSample": [iso_from_ms(key) for key in extra[:10]],
    "writeMode": "INSERT_MISSING_ONLY",
    "replaceExisting": False,
    "maxRangeDaysPerCall": MAX_RANGE_DAYS,
    "plannedCallCount": len(chunks),
    "estimatedProviderRequests": sum(math.ceil(chunk["bars"] / 1000) for chunk in chunks),
    "plannedCalls": chunks,
    "postRelabelMissingBars": len(post_relabel_missing),
    "postRelabelPlannedCallCount": len(post_relabel_chunks),
    "postRelabelPlannedCalls": post_relabel_chunks,
    "providerEndpoint": endpoint,
    "providerEndpointIsBinanceVision": endpoint_parts.hostname == "data-api.binance.vision",
    "currentSafetyFlags": {
        "externalBackfillsEnabled": external_backfills_enabled,
        "primarySignalSource": primary,
        "localTradingViewEnabled": local_enabled,
        "executionMode": execution_mode,
        "executionDryRun": execution_dry_run,
        "liveOrderEnabled": live_order_enabled,
    },
    "providerCoverageOk": provider_ok,
    "existingProductionDataOk": existing_data_ok,
    "runtimeSafetyOk": safety_ok,
    "chunkPlanOk": chunk_plan_ok,
    "productionMutationPerformed": False,
    "sourceRelabelMutationAllowed": False,
    "externalBackfillImportAllowed": False,
    "authorizationRequired": status == "READY_FOR_SEPARATE_EXTERNAL_BACKFILL_AUTHORIZATION",
    "separateSourceRelabelAuthorizationRequired": legacy_source_relabel_evidence_ok,
    "requiredAuthorizationText": (
        (
            f"I explicitly authorize relabeling exactly the {len(mismatch_keys)} proven BTCUSDT 1d "
            f"{mismatches[0]['openTimeUtc']}..{mismatches[-1]['openTimeUtc']} "
            "Binance.US md_kline rows from source=binance to source=binance_us without deleting them; temporarily "
            "setting TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true; executing only the listed post-relabel "
            "backfillBinanceKlinesRange chunks with replaceExisting=false; restoring the flag to false immediately "
            "afterward; restarting as required; and running read-only post-import verification while keeping "
            "BTC_BASE_DRY_RUN and live-order=false."
        ) if legacy_source_relabel_evidence_ok else (
            "I explicitly authorize temporarily setting TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true "
            "for agora-trading-api production, executing only the listed backfillBinanceKlinesRange chunks with "
            "replaceExisting=false, restoring the flag to false immediately afterward, restarting as required, "
            "and running read-only post-import verification while keeping BTC_BASE_DRY_RUN and live-order=false."
        )
    ),
    "nextAction": next_action,
    "notAuthorization": (
        "This read-only preflight does not authorize or perform production env changes, DB writes, external "
        "backfill/import, deploy, restart, live trading, order, OCO, grid, fund, Earn, Telegram, scheduler, "
        "or exchange mutation."
    ),
}

print("[strategy485-binance-replay-backfill-preflight] read-only evidence")
print("scope=READ_ONLY; production md_kline SELECT plus Binance Vision HTTPS GET only; no mutation performed")
print("strategy485_binance_replay_backfill_preflight=" + json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
print("strategy485_binance_replay_backfill_preflight_status=" + status)
print("production_mutation_performed=false")
print("source_relabel_mutation_allowed=false")
print("external_backfill_import_allowed=false")
print("notAuthorization=" + packet["notAuthorization"])
print("[strategy485-binance-replay-backfill-preflight] read-only check complete")

if require_ready and status != "READY_FOR_SEPARATE_EXTERNAL_BACKFILL_AUTHORIZATION":
    sys.exit(2)
PY
'@

$remoteScript = $remoteScriptTemplate
$remoteScript = $remoteScript.Replace("__APP_DIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENV_FILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol.ToUpperInvariant())
$remoteScript = $remoteScript.Replace("__INTERVAL__", $IntervalCode.ToLowerInvariant())
$remoteScript = $remoteScript.Replace("__SOURCE__", $Source.ToLowerInvariant())
$remoteScript = $remoteScript.Replace("__START__", $startText)
$remoteScript = $remoteScript.Replace("__END__", $endText)
$remoteScript = $remoteScript.Replace("__REQUIRE_READY__", $requireReadyText)

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
$remoteCommand = "printf '%s' '$encoded' | base64 -d | bash"

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $output = & ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=15 $SshHost $remoteCommand 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$output | ForEach-Object { Write-Host $_ }
if ($exitCode -ne 0) {
    $text = ($output -join "`n")
    if ($exitCode -eq 255 -and $text -match "Permission denied") {
        throw "Strategy 485 Binance replay preflight failed: SSH_AUTH_FAILED"
    }
    if ($exitCode -eq 255 -and $text -match "Connection timed out|Connection refused|Could not resolve hostname|No route to host") {
        throw "Strategy 485 Binance replay preflight failed: SSH_CONNECT_FAILED"
    }
    throw "Strategy 485 Binance replay preflight failed with exit code $exitCode"
}
