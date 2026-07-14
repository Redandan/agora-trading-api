param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [ValidateSet("OFF", "SHADOW")]
    [string]$ExpectedMode = "OFF",
    [switch]$RequireGoldenParity,
    [switch]$RequireForwardReady
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

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Z0-9_]+$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "A valid SshKey is required." }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($RequireForwardReady -and $ExpectedMode -ne "SHADOW") {
    throw "RequireForwardReady requires ExpectedMode SHADOW."
}
Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "ExpectedMode" -Value $ExpectedMode

$requireGolden = if ($RequireGoldenParity) { "1" } else { "0" }
$requireForward = if ($RequireForwardReady) { "1" } else { "0" }

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
export PORT ENV_FILE='__ENVFILE__' EXPECTED_MODE='__EXPECTED_MODE__'
export REQUIRE_GOLDEN='__REQUIRE_GOLDEN__' REQUIRE_FORWARD='__REQUIRE_FORWARD__'
python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

def fail(message):
    print(f"FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def read_env(path):
    values = {}
    with open(path, "r", encoding="utf-8-sig") as handle:
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

env = read_env(os.environ["ENV_FILE"])
mcp_key = env.get("TRADING_MCP_KEY", "")
if not mcp_key:
    fail("TRADING_MCP_KEY missing in env file")

expected_mode = os.environ["EXPECTED_MODE"]
actual_mode = env.get("TRADING_BTC_DONCHIAN_SHADOW_MODE", "OFF").strip().upper()
if actual_mode != expected_mode:
    fail(f"TRADING_BTC_DONCHIAN_SHADOW_MODE expected={expected_mode!r} actual={actual_mode!r}")
if actual_mode == "SHADOW" and env.get("TRADING_RUNTIME_EVIDENCE_ENABLED", "").strip().lower() != "true":
    fail("SHADOW requires TRADING_RUNTIME_EVIDENCE_ENABLED=true")

unexpected_scope_keys = sorted(
    key for key in env
    if key.startswith("TRADING_BTC_DONCHIAN_SHADOW_")
    and key != "TRADING_BTC_DONCHIAN_SHADOW_MODE"
)
if unexpected_scope_keys:
    fail(f"unsupported Donchian runtime keys present: {unexpected_scope_keys}")

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": f"Bearer {mcp_key}"}

def call_tool(name, arguments, timeout=300):
    payload = {"jsonrpc": "2.0", "id": name, "method": "tools/call",
               "params": {"name": name, "arguments": arguments}}
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                     headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        fail(f"{name} HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}")
    if body.get("error"):
        fail(f"{name} MCP error: {body['error']}")
    result = body.get("result") or {}
    if result.get("isError"):
        fail(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    text = content[0].get("text", "") if content and isinstance(content[0], dict) else json.dumps(result)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                text = decoded
        except Exception:
            pass
    try:
        return json.loads(text)
    except Exception as exc:
        fail(f"{name} result is not JSON: {exc}; text={text[:1000]}")

def require_equal(path, actual, expected):
    if actual != expected:
        fail(f"{path} expected={expected!r} actual={actual!r}")

golden = call_tool("analyzeBtcDonchianShadowGoldenParity", {"symbol": "BTCUSDT"})
require_equal("golden.tool", golden.get("tool"), "analyzeBtcDonchianShadowGoldenParity")
require_equal("golden.boundary", golden.get("boundary"),
              "READ_ONLY_REPLAY_NO_ORDER_NO_OCO_NO_TELEGRAM_NO_BACKFILL")
require_equal("golden.policyMode", golden.get("policyMode"), "BTC_DONCHIAN_20D_10D_V1")
require_equal("golden.expectedRowCount", golden.get("expectedRowCount"), 66009)
require_equal("golden.expectedPriceBarLedgerSha256", golden.get("expectedPriceBarLedgerSha256"),
              "361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d")
require_equal("golden.liveImplementationPresent", golden.get("liveImplementationPresent"), False)
require_equal("golden.liveOrderAllowed", golden.get("liveOrderAllowed"), False)
require_equal("golden.orderSent", golden.get("orderSent"), False)
require_equal("golden.ocoModified", golden.get("ocoModified"), False)
require_equal("golden.telegramSent", golden.get("telegramSent"), False)
require_equal("golden.externalBackfillPerformed", golden.get("externalBackfillPerformed"), False)
if os.environ["REQUIRE_GOLDEN"] == "1" and golden.get("goldenParityPassed") is not True:
    fail(f"golden parity required but status={golden.get('status')} blockers={golden.get('blockers')}")

readiness = call_tool("getBtcDonchianShadowReadiness", {"symbol": "BTCUSDT"})
require_equal("readiness.tool", readiness.get("tool"), "getBtcDonchianShadowReadiness")
require_equal("readiness.boundary", readiness.get("boundary"),
              "READ_ONLY_SHADOW_EVIDENCE_NO_LIVE_IMPLEMENTATION")
require_equal("readiness.policyMode", readiness.get("policyMode"), "BTC_DONCHIAN_20D_10D_V1")
require_equal("readiness.configuredMode", readiness.get("configuredMode"), expected_mode)
require_equal("readiness.liveImplementationPresent", readiness.get("liveImplementationPresent"), False)
require_equal("readiness.liveOrderAllowed", readiness.get("liveOrderAllowed"), False)
require_equal("readiness.promotionAuthorizationGranted", readiness.get("promotionAuthorizationGranted"), False)
require_equal("readiness.orderSent", readiness.get("orderSent"), False)
require_equal("readiness.ocoModified", readiness.get("ocoModified"), False)
require_equal("readiness.telegramSent", readiness.get("telegramSent"), False)
if actual_mode == "OFF":
    require_equal("readiness.status", readiness.get("status"), "OFF_NOT_COLLECTING")
    require_equal("readiness.forwardGatePassed", readiness.get("forwardGatePassed"), False)
if os.environ["REQUIRE_FORWARD"] == "1":
    require_equal("readiness.status", readiness.get("status"),
                  "READY_FOR_SHADOW_EVIDENCE_REVIEW_NOT_LIVE")
    require_equal("readiness.forwardGatePassed", readiness.get("forwardGatePassed"), True)

print("[btc-donchian-shadow] production read-only smoke")
print(f"activePort={os.environ['PORT']} mode={actual_mode} runtimeEvidence={readiness.get('runtimeEvidenceEnabled')}")
print(f"goldenStatus={golden.get('status')} goldenParityPassed={golden.get('goldenParityPassed')}")
print(f"readinessStatus={readiness.get('status')} forwardGatePassed={readiness.get('forwardGatePassed')}")
print(f"blockers={json.dumps(readiness.get('blockers') or [])}")
print("orderSent=false; ocoModified=false; telegramSent=false; liveImplementationPresent=false")
print("notAuthorization=read-only SHADOW evidence only; no production mutation performed")
print("[btc-donchian-shadow] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__EXPECTED_MODE__", $ExpectedMode).
    Replace("__REQUIRE_GOLDEN__", $requireGolden).
    Replace("__REQUIRE_FORWARD__", $requireForward)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "BTC Donchian SHADOW read-only smoke failed with exit code $LASTEXITCODE"
}
