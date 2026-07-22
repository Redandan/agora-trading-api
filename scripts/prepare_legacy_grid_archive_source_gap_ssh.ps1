param(
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$SourceLogPath = "",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($ExpectedCommit -notmatch "^[a-fA-F0-9]{40}$") { throw "ExpectedCommit must be a full 40-character commit." }
if ($AppDir -notmatch "^/[A-Za-z0-9._/-]+$") { throw "AppDir contains unsupported characters." }

function Invoke-RemoteReadOnlyGapCheck {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "SshHost is missing or unsafe."
    }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) {
        throw "SshKey is missing or not found."
    }
    $remote = @'
set -euo pipefail
cd '__APP_DIR__'
export EXPECTED_COMMIT='__EXPECTED_COMMIT__'
python3 - <<'PY'
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.request

def fail(message):
    print(f"[legacy-grid-archive-source-gap] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def proc_env(pid):
    values = {}
    with open(f"/proc/{pid}/environ", "rb") as handle:
        for item in handle.read().split(b"\0"):
            if b"=" not in item:
                continue
            key, value = item.split(b"=", 1)
            values[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    return values

def parse_jdbc(value):
    match = re.fullmatch(r"jdbc:mysql://([^/:?]+)(?::([0-9]+))?/([^?]+)(?:\?.*)?", value or "")
    if not match:
        fail("SPRING_DATASOURCE_URL is not a supported jdbc:mysql URL")
    return match.group(1), match.group(2) or "3306", match.group(3)

def mysql_scalar(env, query):
    child = os.environ.copy()
    child["MYSQL_PWD"] = env["SPRING_DATASOURCE_PASSWORD"]
    result = subprocess.run([
        "mysql", "--batch", "--raw", "--skip-column-names",
        "-h", env["MYSQL_HOST"], "-P", env["MYSQL_PORT"],
        "-u", env["SPRING_DATASOURCE_USERNAME"], env["MYSQL_DATABASE"],
        "-e", query,
    ], env=child, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        fail("read-only mysql SELECT failed")
    return result.stdout.strip()

if not os.path.isfile("app.pid") or not os.path.isfile("app.port"):
    fail("app.pid or app.port missing")
pid = open("app.pid", encoding="utf-8").read().strip()
port = open("app.port", encoding="utf-8").read().strip()
if not pid.isdigit() or not port.isdigit():
    fail("invalid app pid or port")
env = proc_env(pid)
for key in ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
    if not env.get(key):
        fail(f"running process {key} missing")
host, db_port, database = parse_jdbc(env["SPRING_DATASOURCE_URL"])
env.update({"MYSQL_HOST": host, "MYSQL_PORT": db_port, "MYSQL_DATABASE": database})

head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
dirty = bool(subprocess.run(["git", "status", "--porcelain"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip())
with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/actuator/health", timeout=10) as response:
    health = json.loads(response.read().decode("utf-8", "replace")).get("status")

grid_rows_text = mysql_scalar(env, """
SELECT CONCAT_WS('\t', id, symbol, IFNULL(DATE_FORMAT(closed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
                 closed_pair_count, CAST(total_realized_pnl AS CHAR))
FROM bt_grid ORDER BY id
""")
grids = []
for line in grid_rows_text.splitlines() if grid_rows_text else []:
    parts = line.split("\t")
    if len(parts) != 5:
        fail("unexpected bt_grid SELECT shape")
    grids.append({"id": int(parts[0]), "symbol": parts[1], "closedAt": parts[2] or None,
                  "closedPairCount": int(parts[3]), "databaseTotalRealizedPnl": parts[4]})

level_summary = mysql_scalar(env, """
SELECT CONCAT_WS('\t',
  COUNT(*),
  SUM(CASE WHEN status IN ('HOLDING','SELL_FAILED','SELL_PARTIAL','PENDING_OKX','SELLING_OKX') THEN 1 ELSE 0 END),
  SUM(CASE WHEN buy_order_id IS NOT NULL AND buy_order_id <> '' AND sell_order_id IS NOT NULL AND sell_order_id <> '' THEN 1 ELSE 0 END),
  SUM(CASE WHEN buy_order_id IS NOT NULL AND buy_order_id <> '' THEN 1 ELSE 0 END),
  SUM(CASE WHEN sell_order_id IS NOT NULL AND sell_order_id <> '' THEN 1 ELSE 0 END))
FROM bt_grid_level
""")
level_parts = level_summary.split("\t")
if len(level_parts) != 5:
    fail("unexpected bt_grid_level summary shape")
level_count, unsafe_count, surviving_pairs, surviving_buys, surviving_sells = [int(value or "0") for value in level_parts]

audit_summary = mysql_scalar(env, """
SELECT CONCAT_WS('\t',
  SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.source'))='GRID_BUY' THEN 1 ELSE 0 END),
  SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.source'))='GRID_SELL' THEN 1 ELSE 0 END),
  IFNULL(DATE_FORMAT(MIN(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.source')) IN ('GRID_BUY','GRID_SELL') THEN event_time END),'%Y-%m-%dT%H:%i:%s.%fZ'),''),
  IFNULL(DATE_FORMAT(MAX(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.source')) IN ('GRID_BUY','GRID_SELL') THEN event_time END),'%Y-%m-%dT%H:%i:%s.%fZ'),''))
FROM bt_decision_audit
WHERE event_type='AUTOTRADE_OK' AND symbol='BTCUSDT'
""")
audit_parts = audit_summary.split("\t")
if len(audit_parts) != 4:
    fail("unexpected bt_decision_audit summary shape")
audit_buys, audit_sells = int(audit_parts[0] or "0"), int(audit_parts[1] or "0")

closed_pair_count = sum(item["closedPairCount"] for item in grids)
open_grid_count = sum(1 for item in grids if not item["closedAt"])
blockers = []
if head.lower() != os.environ["EXPECTED_COMMIT"].lower(): blockers.append("SERVER_HEAD_COMMIT_MISMATCH")
if dirty: blockers.append("SERVER_WORKTREE_DIRTY")
if health != "UP": blockers.append("HEALTH_NOT_UP")
if open_grid_count: blockers.append("OPEN_LEGACY_GRIDS_REMAIN")
if unsafe_count: blockers.append("LEGACY_INVENTORY_OR_IN_FLIGHT_REMAINS")
if surviving_pairs != closed_pair_count: blockers.append("RECYCLED_LEVEL_ORDER_IDS_CLEARED")
if audit_buys < closed_pair_count or audit_sells < closed_pair_count: blockers.append("GRID_AUDIT_PAIR_EVENT_COVERAGE_INCOMPLETE")
if closed_pair_count and surviving_pairs < closed_pair_count: blockers.append("HISTORICAL_PROVIDER_ORDER_ID_COVERAGE_INCOMPLETE")

packet = {
    "packetType": "LEGACY_GRID_ARCHIVE_SOURCE_GAP_PREFLIGHT_V1",
    "boundary": "PRODUCTION_READ_ONLY_DB_SELECT_HEALTH_AND_GIT_ONLY",
    "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "expectedCommit": os.environ["EXPECTED_COMMIT"].lower(),
    "serverHeadCommit": head,
    "serverWorktreeDirty": dirty,
    "health": health,
    "grids": grids,
    "gridCount": len(grids),
    "openGridCount": open_grid_count,
    "databaseClosedPairCount": closed_pair_count,
    "levelCount": level_count,
    "unsafeLevelCount": unsafe_count,
    "survivingBuyOrderIdCount": surviving_buys,
    "survivingSellOrderIdCount": surviving_sells,
    "survivingCompletedPairOrderIdCount": surviving_pairs,
    "gridBuyAuditCount": audit_buys,
    "gridSellAuditCount": audit_sells,
    "gridAuditFirstAtUtc": audit_parts[2] or None,
    "gridAuditLastAtUtc": audit_parts[3] or None,
    "auditContainsProviderOrderIds": False,
    "historicalProviderOrderIdCoverageProven": surviving_pairs == closed_pair_count,
    "providerRequestAttempted": False,
    "databaseMutationAttempted": False,
    "productionMutationAttempted": False,
    "blockers": blockers,
    "status": "READY_FOR_READ_ONLY_PROVIDER_ARCHIVE_EXPORT" if not blockers else "BLOCKED_HISTORICAL_ARCHIVE_SOURCE_GAP",
}
print("legacy_grid_archive_source_gap=" + json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
print("scope=READ_ONLY; DB SELECT, local health, git metadata only; no OKX request or Production mutation")
PY
'@
    $remote = $remote.Replace("__APP_DIR__", $AppDir).Replace("__EXPECTED_COMMIT__", $ExpectedCommit.ToLowerInvariant())
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Legacy Grid archive source-gap SSH failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

$text = if ([string]::IsNullOrWhiteSpace($SourceLogPath)) {
    Invoke-RemoteReadOnlyGapCheck
} else {
    if (-not (Test-Path -LiteralPath $SourceLogPath)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
}
$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("legacy_grid_archive_source_gap=") } | Select-Object -Last 1)
if (-not $line) { throw "legacy_grid_archive_source_gap output missing" }
$packet = $line.Substring("legacy_grid_archive_source_gap=".Length) | ConvertFrom-Json -Depth 30
Write-Output $line
Write-Output "legacy_grid_archive_source_gap_status=$($packet.status)"
Write-Output "notAuthorization=Production read-only source-gap preflight only; no provider request, Grid/Bot/order/DB/deploy/archive/runtime mutation"
if ($RequireReady -and $packet.status -ne "READY_FOR_READ_ONLY_PROVIDER_ARCHIVE_EXPORT") {
    throw "Legacy Grid archive source evidence is not ready: $($packet.blockers -join ', ')"
}

