param(
    [ValidatePattern("^[0-9]{4}$")]
    [string]$Year = "2026",
    [ValidateSet("Q1", "Q2", "Q3", "Q4")]
    [string]$Quarter = "Q2",
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$SourceLogPath = "",
    [switch]$RequireAvailable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-RemoteAvailabilityGet {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") { throw "SshHost is missing or unsafe." }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "SshKey is missing or not found." }
    if ($AppDir -notmatch "^/[A-Za-z0-9._/-]+$") { throw "AppDir contains unsupported characters." }
    $remote = @'
set -euo pipefail
cd '__APP_DIR__'
export ARCHIVE_YEAR='__YEAR__'
export ARCHIVE_QUARTER='__QUARTER__'
python3 - <<'PY'
import base64
import datetime
import hashlib
import hmac
import json
import os
import sys
import urllib.request

def fail(message):
    print(f"[okx-bills-history-archive-availability] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

if not os.path.isfile("app.pid"):
    fail("app.pid missing")
pid = open("app.pid", encoding="utf-8").read().strip()
values = {}
with open(f"/proc/{pid}/environ", "rb") as handle:
    for item in handle.read().split(b"\0"):
        if b"=" not in item:
            continue
        key, value = item.split(b"=", 1)
        values[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
for key in ("TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"):
    if not values.get(key):
        fail(f"running process {key} missing")

path = "/api/v5/account/bills-history-archive?year=" + os.environ["ARCHIVE_YEAR"] + "&quarter=" + os.environ["ARCHIVE_QUARTER"]
timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
signature = base64.b64encode(hmac.new(values["TRADING_OKX_SECRET_KEY"].encode(),
    (timestamp + "GET" + path).encode(), hashlib.sha256).digest()).decode()
request = urllib.request.Request("https://www.okx.com" + path, headers={
    "OK-ACCESS-KEY": values["TRADING_OKX_API_KEY"],
    "OK-ACCESS-SIGN": signature,
    "OK-ACCESS-TIMESTAMP": timestamp,
    "OK-ACCESS-PASSPHRASE": values["TRADING_OKX_PASSPHRASE"],
    "User-Agent": "agora-trading-api/1.0",
    "Accept": "application/json",
}, method="GET")
with urllib.request.urlopen(request, timeout=30) as response:
    body = json.loads(response.read().decode("utf-8", "replace"))
provider_code = str(body.get("code") or "")
if provider_code not in {"0", "51604"}:
    fail(f"OKX GET failed code={provider_code} msg={body.get('msg')}")
rows = body.get("data") or [] if provider_code == "0" else []
row = rows[0] if rows else {}
state = str(row.get("state") or "").lower()
file_available = state == "finished" and bool(row.get("fileHref"))
packet = {
    "packetType": "OKX_BILLS_HISTORY_ARCHIVE_AVAILABILITY_V1",
    "boundary": "AUTHENTICATED_OKX_GET_ONLY_NO_ARCHIVE_APPLICATION",
    "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "year": os.environ["ARCHIVE_YEAR"],
    "quarter": os.environ["ARCHIVE_QUARTER"],
    "providerRowCount": len(rows),
    "providerCode": provider_code,
    "providerMessage": "ARCHIVE_REQUEST_REQUIRED" if provider_code == "51604" else None,
    "providerState": state or None,
    "fileAvailable": file_available,
    "fileHrefRedacted": bool(row.get("fileHref")),
    "providerGetAttempted": True,
    "providerPostAttempted": False,
    "providerMutationAttempted": False,
    "productionMutationAttempted": False,
    "status": "ARCHIVE_DOWNLOAD_READY_READ_ONLY" if file_available else "ARCHIVE_NOT_PREPARED_REQUIRES_SEPARATE_PROVIDER_EXPORT_REQUEST",
}
print("okx_bills_history_archive_availability=" + json.dumps(packet, separators=(",", ":")))
print("scope=OKX authenticated GET only; fileHref redacted; no archive POST/application/download or Production mutation")
PY
'@
    $remote = $remote.Replace("__APP_DIR__", $AppDir).Replace("__YEAR__", $Year).Replace("__QUARTER__", $Quarter)
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "OKX bills archive availability SSH failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

$text = if ([string]::IsNullOrWhiteSpace($SourceLogPath)) {
    Invoke-RemoteAvailabilityGet
} else {
    if (-not (Test-Path -LiteralPath $SourceLogPath)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
}
$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("okx_bills_history_archive_availability=") } | Select-Object -Last 1)
if (-not $line) { throw "OKX bills archive availability output missing" }
$packet = $line.Substring("okx_bills_history_archive_availability=".Length) | ConvertFrom-Json -Depth 20
Write-Output $line
Write-Output "okx_bills_history_archive_status=$($packet.status)"
Write-Output "notAuthorization=GET-only availability check; does not authorize archive POST/application/download, order, Grid/Bot, DB, deploy, or Production mutation"
if ($RequireAvailable -and -not [bool]$packet.fileAvailable) { throw "OKX $Year $Quarter bills archive is not available." }
