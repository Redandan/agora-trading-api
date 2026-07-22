param(
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [ValidatePattern("^[0-9]{4}$")]
    [string]$Year = "2026",
    [ValidateSet("Q1", "Q2", "Q3", "Q4")]
    [string]$Quarter = "Q2",
    [switch]$Execute,
    [string]$ConfirmText = "",
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$SourceLogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ($ExpectedCommit -notmatch "^[a-fA-F0-9]{40}$") { throw "ExpectedCommit must be a full 40-character commit." }

function Invoke-RemoteRequestOperator {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") { throw "SshHost is missing or unsafe." }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "SshKey is missing or not found." }
    if ($AppDir -notmatch "^/[A-Za-z0-9._/-]+$") { throw "AppDir contains unsupported characters." }
    $executeText = if ($Execute) { "true" } else { "false" }
    $confirmB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($ConfirmText))
    $remote = @'
set -euo pipefail
cd '__APP_DIR__'
export EXPECTED_COMMIT='__EXPECTED_COMMIT__'
export ARCHIVE_YEAR='__YEAR__'
export ARCHIVE_QUARTER='__QUARTER__'
export EXECUTE_REQUEST='__EXECUTE__'
export CONFIRM_B64='__CONFIRM_B64__'
python3 - <<'PY'
import base64
import datetime
import hashlib
import hmac
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

def fail(message):
    print(f"[okx-bills-history-archive-request] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def sign_headers(values, method, path, body):
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    signature = base64.b64encode(hmac.new(values["TRADING_OKX_SECRET_KEY"].encode(),
        (timestamp + method + path + body).encode(), hashlib.sha256).digest()).decode()
    return {
        "OK-ACCESS-KEY": values["TRADING_OKX_API_KEY"],
        "OK-ACCESS-SIGN": signature,
        "OK-ACCESS-TIMESTAMP": timestamp,
        "OK-ACCESS-PASSPHRASE": values["TRADING_OKX_PASSPHRASE"],
        "User-Agent": "agora-trading-api/1.0",
        "Accept": "application/json",
        "Content-Type": "application/json",
    }

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
get_request = urllib.request.Request("https://www.okx.com" + path,
    headers=sign_headers(values, "GET", path, ""), method="GET")
try:
    with urllib.request.urlopen(get_request, timeout=30) as response:
        availability = json.loads(response.read().decode("utf-8", "replace"))
except urllib.error.HTTPError as error:
    availability = json.loads(error.read().decode("utf-8", "replace"))
provider_code = str(availability.get("code") or "")
rows = availability.get("data") or []
file_available = provider_code == "0" and bool(rows) and str(rows[0].get("state") or "").lower() == "finished" and bool(rows[0].get("fileHref"))
if provider_code not in {"0", "51604"}: fail(f"unexpected availability code={provider_code}")

state = {"commit": head, "year": year, "quarter": quarter, "providerCode": provider_code, "fileAvailable": file_available}
state_sha = hashlib.sha256(json.dumps(state, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
required_confirm = f"AUTHORIZE_OKX_BILLS_HISTORY_ARCHIVE_REQUEST|year={year}|quarter={quarter}|commit={head}|stateSha256={state_sha}"
execute = os.environ["EXECUTE_REQUEST"].lower() == "true"
confirm = base64.b64decode(os.environ["CONFIRM_B64"]).decode("utf-8")
blockers = []
if file_available: blockers.append("ARCHIVE_ALREADY_AVAILABLE_NO_POST_NEEDED")
if provider_code != "51604": blockers.append("PROVIDER_DID_NOT_REPORT_ARCHIVE_REQUEST_REQUIRED")
if execute and confirm != required_confirm: blockers.append("EXACT_CONFIRMATION_MISMATCH")

post_attempted = False
post_result = None
status = "READY_FOR_SEPARATE_EXACT_PROVIDER_ARCHIVE_REQUEST_AUTHORIZATION" if not blockers else "BLOCKED_NOT_READY"
if execute and not blockers:
    body = json.dumps({"year": year, "quarter": quarter}, separators=(",", ":"))
    post_request = urllib.request.Request("https://www.okx.com/api/v5/account/bills-history-archive",
        data=body.encode(), headers=sign_headers(values, "POST", "/api/v5/account/bills-history-archive", body), method="POST")
    post_attempted = True
    try:
        with urllib.request.urlopen(post_request, timeout=30) as response:
            post_result = json.loads(response.read().decode("utf-8", "replace"))
    except Exception as ambiguous:
        fail("archive request response ambiguous; do not retry without a fresh GET reconciliation")
    if str(post_result.get("code") or "") != "0" or not (post_result.get("data") or []):
        fail("archive request returned a non-success response; do not retry without GET reconciliation")
    status = "PROVIDER_ARCHIVE_REQUEST_ACCEPTED_RECONCILE_BY_GET_NO_RETRY"

packet = {
    "packetType": "OKX_BILLS_HISTORY_ARCHIVE_REQUEST_V1",
    "boundary": "DRY_RUN_BY_DEFAULT_EXACT_CONFIRMATION_SINGLE_POST_NO_RETRY",
    "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "year": year,
    "quarter": quarter,
    "serverCommit": head,
    "providerAvailabilityCode": provider_code,
    "fileAvailableBefore": file_available,
    "stateSha256": state_sha,
    "requiredConfirmText": required_confirm,
    "executeRequested": execute,
    "providerGetAttempted": True,
    "providerPostAttempted": post_attempted,
    "providerArchiveRequestAccepted": bool(post_result),
    "providerOrderAttempted": False,
    "databaseMutationAttempted": False,
    "productionMutationAttempted": False,
    "blockers": blockers,
    "status": status,
}
print("okx_bills_history_archive_request=" + json.dumps(packet, separators=(",", ":")))
print("scope=archive request only; never an order, Grid/Bot action, transfer, DB mutation, deploy, or runtime change")
PY
'@
    $remote = $remote.Replace("__APP_DIR__", $AppDir).Replace("__EXPECTED_COMMIT__", $ExpectedCommit.ToLowerInvariant()).Replace("__YEAR__", $Year).Replace("__QUARTER__", $Quarter).Replace("__EXECUTE__", $executeText).Replace("__CONFIRM_B64__", $confirmB64)
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "OKX bills archive request SSH failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

$text = if ([string]::IsNullOrWhiteSpace($SourceLogPath)) { Invoke-RemoteRequestOperator } else {
    if (-not (Test-Path -LiteralPath $SourceLogPath)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
}
$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("okx_bills_history_archive_request=") } | Select-Object -Last 1)
if (-not $line) { throw "OKX bills archive request output missing" }
$packet = $line.Substring("okx_bills_history_archive_request=".Length) | ConvertFrom-Json -Depth 20
Write-Output $line
Write-Output "okx_bills_history_archive_request_status=$($packet.status)"
Write-Output "okx_bills_history_archive_required_confirmation=$($packet.requiredConfirmText)"
Write-Output "notAuthorization=dry-run is not authorization; execute requires separate exact authorization for one provider archive POST"

