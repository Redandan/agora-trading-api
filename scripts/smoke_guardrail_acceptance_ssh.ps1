param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireNoReviewGaps
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' REQUIRE_NO_REVIEW_GAPS='$($RequireNoReviewGaps.IsPresent)'
python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()
require_no_review_gaps = os.environ["REQUIRE_NO_REVIEW_GAPS"].lower() == "true"

def call_tool(name, arguments, timeout=120):
    body = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments,
        },
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    data = json.loads(raw)
    if "error" in data:
        raise RuntimeError(f"{name} JSON-RPC error: {data['error']}")
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        text = content[0].get("text") or ""
    else:
        text = json.dumps(result, ensure_ascii=False)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def require(description, pattern, text):
    if not re.search(pattern, text, re.MULTILINE):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

print("[guardrail-acceptance] read-only production MCP check")
print(f"symbol={symbol} requireNoReviewGaps={str(require_no_review_gaps).lower()}")

anti_wick = call_tool("analyzeSpotAntiWickPolicyCoverage", {"symbol": symbol}, timeout=120)
event_risk = call_tool("getEventRiskControlStatus", {"symbol": symbol}, timeout=120)

print("")
print("## analyzeSpotAntiWickPolicyCoverage")
print(anti_wick)
print("")
print("## getEventRiskControlStatus")
print(event_risk)
print("")

anti_wick_patterns = {
    "anti-wick read-only boundary": r"boundary:\s*READ_ONLY",
    "anti-wick policy line": r"policy: live BTC spot LONG entries default to ULTRA_LOW_DISASTER SL",
    "anti-wick summary": r"Summary:",
    "anti-wick operator action": r"Operator action:\s*(HOLD|HOLD_WITH_SIZE_CAPS|REVIEW_POLICY_GAPS)",
}
for description, pattern in anti_wick_patterns.items():
    require(description, pattern, anti_wick)

event_risk_patterns = {
    "event-risk read-only boundary": r"boundary=READ_ONLY",
    "event-risk risk level": r"riskLevel=R[0-3]",
    "event-risk policy": r"policy=",
    "event-risk config-only controls": r"operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION",
}
for description, pattern in event_risk_patterns.items():
    require(description, pattern, event_risk)

if "Operator action: REVIEW_POLICY_GAPS" in anti_wick:
    print("[guardrail-acceptance] REVIEW: anti-wick policy coverage has review gaps before live promotion.")
    if require_no_review_gaps:
        print("[guardrail-acceptance] FAIL: review gaps are not acceptable for issue acceptance.", file=sys.stderr)
        sys.exit(1)
else:
    print("[guardrail-acceptance] PASS: anti-wick and event-risk read-only guardrail surfaces are deployed and callable.")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "guardrail acceptance smoke failed with exit code $LASTEXITCODE"
}
