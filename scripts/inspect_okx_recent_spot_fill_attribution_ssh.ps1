param(
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [ValidateRange(1, 50)]
    [int]$MaxPages = 20,
    [string]$OutputPath = "",
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$SourceLogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ($ExpectedCommit -notmatch "^[a-fA-F0-9]{40}$") { throw "ExpectedCommit must be a full 40-character commit." }

function Invoke-RemoteReadOnlyInspection {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") { throw "SshHost is missing or unsafe." }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "SshKey is missing or not found." }
    if ($AppDir -notmatch "^/[A-Za-z0-9._/-]+$") { throw "AppDir contains unsupported characters." }
    $remote = @'
set -euo pipefail
cd '__APP_DIR__'
export EXPECTED_COMMIT='__EXPECTED_COMMIT__'
export MAX_PAGES='__MAX_PAGES__'
export EXPORT_PAYLOAD='__EXPORT_PAYLOAD__'
python3 - <<'PY'
import base64
import datetime
import hashlib
import hmac
import json
import os
import subprocess
import time
import urllib.parse
import urllib.request

def fail(message):
    raise SystemExit(f"[okx-recent-spot-fill-attribution] FAIL: {message}")

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

head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip().lower()
dirty = bool(subprocess.run(["git", "status", "--porcelain"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip())
if head != os.environ["EXPECTED_COMMIT"].lower():
    fail("server commit mismatch")
if dirty:
    fail("server worktree dirty")

def signed_get(path):
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    signature = base64.b64encode(hmac.new(
        values["TRADING_OKX_SECRET_KEY"].encode(),
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
    if str(body.get("code") or "") != "0":
        fail(f"provider code={body.get('code')}")
    return body.get("data") or []

rows = []
cursor = None
page_count = 0
truncated = False
seen_cursors = set()
for _ in range(int(os.environ["MAX_PAGES"])):
    path = "/api/v5/trade/fills-history?instType=SPOT&instId=BTC-USDT&limit=100"
    if cursor:
        path += "&after=" + urllib.parse.quote(cursor, safe="")
    page = signed_get(path)
    page_count += 1
    rows.extend(page)
    if len(page) < 100:
        break
    next_cursor = str(page[-1].get("billId") or "")
    if not next_cursor or next_cursor in seen_cursors:
        fail("pagination cursor missing or repeated")
    seen_cursors.add(next_cursor)
    cursor = next_cursor
    time.sleep(0.05)
else:
    truncated = True

def nonempty(name):
    return [row for row in rows if str(row.get(name) or "").strip()]

def valid_signed_fee(row):
    try:
        float(str(row.get("fee")))
    except (TypeError, ValueError):
        return False
    return str(row.get("feeCcy") or "").upper() in {"BTC", "USDT"}

fill_keys = [(str(row.get("billId") or ""), str(row.get("tradeId") or "")) for row in rows]
valid_fill_keys = [key for key in fill_keys if key[0].isdigit() and key[1].isdigit()]
order_ids = {str(row.get("ordId")) for row in rows if str(row.get("ordId") or "").isdigit()}
times = []
for row in rows:
    raw = str(row.get("fillTime") or row.get("ts") or "")
    if raw.isdigit():
        times.append(int(raw))

tag_rows = nonempty("tag")
client_rows = nonempty("clOrdId")
client_groups = {}
for row in client_rows:
    key = str(row.get("clOrdId"))
    group = client_groups.setdefault(key, {"orders": set(), "sides": set()})
    if str(row.get("ordId") or "").isdigit():
        group["orders"].add(str(row.get("ordId")))
    group["sides"].add(str(row.get("side") or "").upper())
packet = {
    "packetType": "OKX_RECENT_SPOT_FILL_ATTRIBUTION_INSPECTION_V1",
    "boundary": "AUTHENTICATED_OKX_GET_ONLY_NO_ORDER_DB_OR_PRODUCTION_MUTATION",
    "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "serverCommit": head,
    "serverWorktreeDirty": dirty,
    "instrument": "BTC-USDT",
    "providerRetentionBoundary": "LAST_3_MONTHS_PROVIDER_DEFINED",
    "pageCount": page_count,
    "paginationTruncated": truncated,
    "fillRowCount": len(rows),
    "uniqueOrderIdCount": len(order_ids),
    "validUniqueBillTradeKeyCount": len(set(valid_fill_keys)),
    "duplicateBillTradeKeyCount": len(valid_fill_keys) - len(set(valid_fill_keys)),
    "buyFillRowCount": sum(1 for row in rows if str(row.get("side") or "").upper() == "BUY"),
    "sellFillRowCount": sum(1 for row in rows if str(row.get("side") or "").upper() == "SELL"),
    "signedFeeCompleteRowCount": sum(1 for row in rows if valid_signed_fee(row)),
    "nonemptyTagRowCount": len(tag_rows),
    "distinctNonemptyTagCount": len({str(row.get("tag")) for row in tag_rows}),
    "nonemptyClientOrderIdRowCount": len(client_rows),
    "distinctNonemptyClientOrderIdCount": len(client_groups),
    "clientOrderIdSpanningMultipleOrdersCount": sum(1 for group in client_groups.values() if len(group["orders"]) > 1),
    "clientOrderIdSpanningBuyAndSellCount": sum(1 for group in client_groups.values() if {"BUY", "SELL"}.issubset(group["sides"])),
    "nonemptyAlgoIdRowCount": len(nonempty("algoId")),
    "earliestFillTimeUtc": datetime.datetime.fromtimestamp(min(times) / 1000, datetime.timezone.utc).isoformat() if times else None,
    "latestFillTimeUtc": datetime.datetime.fromtimestamp(max(times) / 1000, datetime.timezone.utc).isoformat() if times else None,
    "providerGetAttempted": True,
    "providerPostAttempted": False,
    "providerOrderAttempted": False,
    "databaseMutationAttempted": False,
    "productionMutationAttempted": False,
}
if truncated:
    packet["status"] = "BLOCKED_RECENT_FILL_PAGINATION_TRUNCATED"
elif len(rows) != len(valid_fill_keys) or packet["duplicateBillTradeKeyCount"] != 0 or packet["signedFeeCompleteRowCount"] != len(rows):
    packet["status"] = "BLOCKED_RECENT_FILL_EVIDENCE_FIELDS_INCOMPLETE"
elif packet["nonemptyTagRowCount"] == 0:
    packet["status"] = "RECENT_PROVIDER_FILLS_HAVE_NO_PAIR_TAG_ATTRIBUTION"
else:
    packet["status"] = "RECENT_PROVIDER_FILL_TAGS_REQUIRE_EXACT_PAIR_KEY_REVIEW"

if os.environ["EXPORT_PAYLOAD"] == "true":
    snapshot = {
        "schemaVersion": "OKX_SPOT_FILL_HISTORY_SNAPSHOT_V1",
        "collectionBoundary": "AUTHENTICATED_OKX_GET_ONLY_NO_PRODUCTION_WRITE",
        "collectedAtUtc": packet["checkedAtUtc"],
        "serverCommit": head,
        "instrument": "BTC-USDT",
        "providerRetentionBoundary": packet["providerRetentionBoundary"],
        "pageCount": page_count,
        "paginationTruncated": truncated,
        "fills": rows,
    }
    payload = json.dumps(snapshot, separators=(",", ":"), sort_keys=True).encode("utf-8")
    packet["payloadSha256"] = hashlib.sha256(payload).hexdigest()
    packet["payloadByteCount"] = len(payload)
    packet["payloadBase64"] = base64.b64encode(payload).decode()

print("okx_recent_spot_fill_attribution=" + json.dumps(packet, separators=(",", ":")))
print("scope=authenticated paginated GET only; aggregate metadata output and optional caller-memory snapshot; no credentials, POST, order, DB, Production file, deploy, Grid/Bot, or runtime mutation")
PY
'@
    $exportPayload = if ([string]::IsNullOrWhiteSpace($OutputPath)) { "false" } else { "true" }
    $remote = $remote.Replace("__APP_DIR__", $AppDir).Replace("__EXPECTED_COMMIT__", $ExpectedCommit.ToLowerInvariant()).Replace("__MAX_PAGES__", [string]$MaxPages).Replace("__EXPORT_PAYLOAD__", $exportPayload)
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "OKX recent spot fill attribution SSH failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    if (Test-Path -LiteralPath $OutputPath) { throw "OutputPath already exists; refusing overwrite: $OutputPath" }
    $parent = Split-Path -Parent $OutputPath
    if ([string]::IsNullOrWhiteSpace($parent) -or -not (Test-Path -LiteralPath $parent -PathType Container)) { throw "OutputPath parent directory must already exist." }
}
$text = if ([string]::IsNullOrWhiteSpace($SourceLogPath)) { Invoke-RemoteReadOnlyInspection } else {
    if (-not (Test-Path -LiteralPath $SourceLogPath -PathType Leaf)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
}
$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("okx_recent_spot_fill_attribution=") } | Select-Object -Last 1)
if (-not $line) { throw "OKX recent spot fill attribution output missing" }
$packet = $line.Substring("okx_recent_spot_fill_attribution=".Length) | ConvertFrom-Json -Depth 20
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    if ($packet.PSObject.Properties.Name -notcontains "payloadBase64" -or $packet.PSObject.Properties.Name -notcontains "payloadSha256") {
        throw "OKX recent spot fill snapshot payload missing"
    }
    $bytes = [Convert]::FromBase64String([string]$packet.payloadBase64)
    $sha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if ($sha -cne [string]$packet.payloadSha256 -or $bytes.Length -ne [int64]$packet.payloadByteCount) {
        throw "OKX recent spot fill snapshot hash or byte count mismatch"
    }
    [IO.File]::WriteAllBytes($OutputPath, $bytes)
    $packet.PSObject.Properties.Remove("payloadBase64")
    $packet | Add-Member -NotePropertyName localSnapshotPath -NotePropertyValue (Resolve-Path -LiteralPath $OutputPath).Path.Replace('\', '/')
}
Write-Output ("okx_recent_spot_fill_attribution=" + (ConvertTo-Json $packet -Compress -Depth 20))
Write-Output "okx_recent_spot_fill_attribution_status=$($packet.status)"
Write-Output "notAuthorization=authenticated provider GET-only aggregate inspection; no raw fill output, provider POST/order, Production/DB/Grid/Bot/deploy/runtime mutation"
