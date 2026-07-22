param(
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [ValidatePattern("^[0-9]{4}$")]
    [string]$Year = "2026",
    [ValidateSet("Q1", "Q2", "Q3", "Q4")]
    [string]$Quarter = "Q2",
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [ValidateRange(1, 100)]
    [int]$MaxMegabytes = 50,
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$SourceLogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ($ExpectedCommit -notmatch "^[a-fA-F0-9]{40}$") { throw "ExpectedCommit must be a full 40-character commit." }

function Invoke-RemoteReadOnlyDownload {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") { throw "SshHost is missing or unsafe." }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "SshKey is missing or not found." }
    if ($AppDir -notmatch "^/[A-Za-z0-9._/-]+$") { throw "AppDir contains unsupported characters." }
    $remote = @'
set -euo pipefail
cd '__APP_DIR__'
export EXPECTED_COMMIT='__EXPECTED_COMMIT__'
export ARCHIVE_YEAR='__YEAR__'
export ARCHIVE_QUARTER='__QUARTER__'
export MAX_BYTES='__MAX_BYTES__'
python3 - <<'PY'
import base64
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

def fail(message):
    print(f"[okx-bills-history-archive-download] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

pid = open("app.pid", encoding="utf-8").read().strip()
values = {}
with open(f"/proc/{pid}/environ", "rb") as handle:
    for item in handle.read().split(b"\0"):
        if b"=" not in item: continue
        key, value = item.split(b"=", 1)
        values[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
for key in ("TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"):
    if not values.get(key): fail(f"running process {key} missing")
head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip().lower()
dirty = bool(subprocess.run(["git", "status", "--porcelain"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip())
if head != os.environ["EXPECTED_COMMIT"].lower(): fail("server commit mismatch")
if dirty: fail("server worktree dirty")

year, quarter = os.environ["ARCHIVE_YEAR"], os.environ["ARCHIVE_QUARTER"]
path = f"/api/v5/account/bills-history-archive?year={year}&quarter={quarter}"
timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
signature = base64.b64encode(hmac.new(values["TRADING_OKX_SECRET_KEY"].encode(),
    (timestamp + "GET" + path).encode(), hashlib.sha256).digest()).decode()
request = urllib.request.Request("https://www.okx.com" + path, headers={
    "OK-ACCESS-KEY": values["TRADING_OKX_API_KEY"], "OK-ACCESS-SIGN": signature,
    "OK-ACCESS-TIMESTAMP": timestamp, "OK-ACCESS-PASSPHRASE": values["TRADING_OKX_PASSPHRASE"],
    "User-Agent": "agora-trading-api/1.0", "Accept": "application/json",
}, method="GET")
with urllib.request.urlopen(request, timeout=30) as response:
    body = json.loads(response.read().decode("utf-8", "replace"))
if str(body.get("code") or "") != "0": fail(f"archive is not available code={body.get('code')}")
rows = body.get("data") or []
if len(rows) != 1 or str(rows[0].get("state") or "").lower() != "finished" or not rows[0].get("fileHref"):
    fail("archive is not in one finished downloadable row")
file_url = str(rows[0]["fileHref"])
parsed = urllib.parse.urlparse(file_url)
if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password:
    fail("provider fileHref must be credential-free HTTPS")
if parsed.hostname.lower() == "localhost": fail("provider fileHref host is unsafe")
try:
    address = ipaddress.ip_address(parsed.hostname)
    if address.is_private or address.is_loopback or address.is_link_local: fail("provider fileHref IP is unsafe")
except ValueError:
    pass

max_bytes = int(os.environ["MAX_BYTES"])
download_request = urllib.request.Request(file_url, headers={"User-Agent": "agora-trading-api/1.0"}, method="GET")
with urllib.request.urlopen(download_request, timeout=120) as response:
    content_length = response.headers.get("Content-Length")
    if content_length and int(content_length) > max_bytes: fail("archive exceeds maximum byte limit")
    payload = response.read(max_bytes + 1)
if len(payload) > max_bytes: fail("archive exceeds maximum byte limit")
sha = hashlib.sha256(payload).hexdigest()
packet = {
    "packetType": "OKX_BILLS_HISTORY_ARCHIVE_DOWNLOAD_V1",
    "boundary": "AUTHENTICATED_STATUS_GET_AND_PROVIDER_FILE_GET_NO_SERVER_WRITE",
    "downloadedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "year": year, "quarter": quarter, "serverCommit": head,
    "fileHrefRedacted": True, "byteCount": len(payload), "sha256": sha,
    "payloadBase64": base64.b64encode(payload).decode(),
    "providerStatusGetAttempted": True, "providerFileGetAttempted": True,
    "providerPostAttempted": False, "providerOrderAttempted": False,
    "databaseMutationAttempted": False, "productionFilesystemWriteAttempted": False,
    "status": "DOWNLOADED_TO_CALLER_MEMORY_READY_FOR_LOCAL_HASHED_WRITE",
}
print("okx_bills_history_archive_download=" + json.dumps(packet, separators=(",", ":")))
print("scope=two GETs only; fileHref redacted; payload returned in memory; no server file, POST, order, DB, Grid/Bot, deploy, or runtime mutation")
PY
'@
    $maxBytes = $MaxMegabytes * 1MB
    $remote = $remote.Replace("__APP_DIR__", $AppDir).Replace("__EXPECTED_COMMIT__", $ExpectedCommit.ToLowerInvariant()).Replace("__YEAR__", $Year).Replace("__QUARTER__", $Quarter).Replace("__MAX_BYTES__", [string]$maxBytes)
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "OKX bills archive download SSH failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

if (Test-Path -LiteralPath $OutputPath) { throw "OutputPath already exists; refusing overwrite: $OutputPath" }
$parent = Split-Path -Parent $OutputPath
if ([string]::IsNullOrWhiteSpace($parent) -or -not (Test-Path -LiteralPath $parent -PathType Container)) { throw "OutputPath parent directory must already exist." }
$text = if ([string]::IsNullOrWhiteSpace($SourceLogPath)) { Invoke-RemoteReadOnlyDownload } else {
    if (-not (Test-Path -LiteralPath $SourceLogPath)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
}
$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("okx_bills_history_archive_download=") } | Select-Object -Last 1)
if (-not $line) { throw "OKX bills archive download output missing" }
$packet = $line.Substring("okx_bills_history_archive_download=".Length) | ConvertFrom-Json -Depth 20
if ($packet.status -ne "DOWNLOADED_TO_CALLER_MEMORY_READY_FOR_LOCAL_HASHED_WRITE") { throw "Unexpected download status: $($packet.status)" }
$bytes = [Convert]::FromBase64String([string]$packet.payloadBase64)
$sha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
if ($sha -cne [string]$packet.sha256 -or $bytes.Length -ne [int64]$packet.byteCount) { throw "Downloaded archive hash or byte count mismatch." }
[IO.File]::WriteAllBytes($OutputPath, $bytes)
Write-Output "okx_bills_history_archive_download_status=LOCAL_HASHED_FILE_WRITTEN"
Write-Output "outputPath=$((Resolve-Path -LiteralPath $OutputPath).Path)"
Write-Output "sha256=$sha"
Write-Output "byteCount=$($bytes.Length)"
Write-Output "notAuthorization=GET-only download to a new local file; no provider POST/order, Production file, DB, Grid/Bot, deploy, or runtime mutation"
