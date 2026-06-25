param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [switch]$RequireCoverageReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
MCP_URL="http://127.0.0.1:${PORT}/api/mcp"
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export MCP_URL MCP_KEY REQUIRE_COVERAGE_READY='__REQUIRE_COVERAGE_READY__'
python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

mcp_url = os.environ["MCP_URL"]
mcp_key = os.environ["MCP_KEY"]
require_ready = os.environ.get("REQUIRE_COVERAGE_READY", "false").lower() == "true"

read_only_review_tools = [
    "getGridTrendAdjustmentReview",
    "listGrids",
    "getGridPriceAlignment",
    "getCurrentExposure",
    "getEventRiskControlStatus",
    "gridStats",
    "getGridEfficiencyScore",
    "listGridDustSellRisks",
    "getGridRedesignPlan",
]

future_action_tools = [
    "createGrid",
    "pauseGrid",
    "resumeGrid",
    "closeGrid",
    "enableGridAutoRebalance",
]

boundary_context_tools = [
    "getOcoHealth",
    "checkOcoHealth",
    "getEarnBalance",
    "getBalance",
    "listSchedulerTasks",
    "getSystemHealth",
    "getMcpAuthProbe",
]

payload = {
    "jsonrpc": "2.0",
    "id": "grid-mcp-tool-coverage-tools-list",
    "method": "tools/list",
    "params": {},
}
req = urllib.request.Request(
    mcp_url,
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + mcp_key,
    },
    method="POST",
)

try:
    with urllib.request.urlopen(req, timeout=60) as response:
        body = response.read().decode("utf-8", errors="replace")
except urllib.error.HTTPError as exc:
    error_body = exc.read().decode("utf-8", errors="replace")
    raise RuntimeError(f"tools/list HTTP {exc.code}: {error_body[:500]}")

message = json.loads(body)
if "error" in message:
    raise RuntimeError("tools/list JSON-RPC error: " + json.dumps(message["error"], ensure_ascii=False))

tools = message.get("result", {}).get("tools", [])
tool_names = sorted({item.get("name") for item in tools if isinstance(item, dict) and item.get("name")})

def coverage(names):
    present = [name for name in names if name in tool_names]
    missing = [name for name in names if name not in tool_names]
    return {"required": names, "present": present, "missing": missing}

read_only_coverage = coverage(read_only_review_tools)
future_action_coverage = coverage(future_action_tools)
boundary_coverage = coverage(boundary_context_tools)

missing_required = (
    read_only_coverage["missing"]
    + future_action_coverage["missing"]
    + boundary_coverage["missing"]
)
status = "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION" if not missing_required else "BLOCKED_GRID_MCP_TOOL_COVERAGE_MISSING_TOOLS"

packet = {
    "packetType": "GRID_MCP_TOOL_COVERAGE_PACKET",
    "scope": "READ_ONLY",
    "mcpUrl": mcp_url,
    "status": status,
    "toolCount": len(tool_names),
    "readOnlyReviewTools": read_only_coverage,
    "futureActionToolsPresentButNotInvoked": future_action_coverage,
    "boundaryContextTools": boundary_coverage,
    "missingRequiredTools": missing_required,
    "coverageNotes": [
        "tools/list only; no grid/OCO/order/Earn/fund/scheduler mutation tools are invoked",
        "future action tools are presence evidence only and still require separate operator authorization before use",
        "read-only review tools must be present before grid open readiness can be considered reviewable",
    ],
    "explicitNonInvokedTools": future_action_tools,
    "notAuthorization": "read-only grid MCP tool coverage only; does not invoke createGrid, pause/resume/close grid, enable grid auto-rebalance, modify OCO, place orders, send Telegram, deploy, change production env, or mutate DB/grid/fund/Earn/exchange state",
}

print("[grid-mcp-tool-coverage] read-only packet")
print("scope=READ_ONLY; invokes tools/list only through server-local /api/mcp; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print("grid_mcp_tool_coverage_tool_count=" + str(len(tool_names)))
print("grid_mcp_tool_coverage_read_only_tools=" + json.dumps(read_only_coverage, separators=(",", ":")))
print("grid_mcp_tool_coverage_future_action_tools_present_but_not_invoked=" + json.dumps(future_action_coverage, separators=(",", ":")))
print("grid_mcp_tool_coverage_boundary_context_tools=" + json.dumps(boundary_coverage, separators=(",", ":")))
print("grid_mcp_tool_coverage_missing_required_tools=" + json.dumps(missing_required, separators=(",", ":")))
print("grid_mcp_tool_coverage_packet=" + json.dumps(packet, sort_keys=True, separators=(",", ":")))
print("grid_mcp_tool_coverage_status=" + status)
print("mutation_tools_invoked=false")
print("grid_mutation_allowed=false")
print("scheduler_enablement_allowed=false")
print("order_allowed=false")
print("oco_mutation_allowed=false")
print("telegram_send_allowed=false")
print("notAuthorization=read-only grid MCP tool coverage only; does not invoke createGrid, pause/resume/close grid, enable grid auto-rebalance, modify OCO, place orders, send Telegram, deploy, change production env, or mutate DB/grid/fund/Earn/exchange state")
print("[grid-mcp-tool-coverage] read-only check complete")

if require_ready and status != "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION":
    print("FAIL: Grid MCP tool coverage is not ready: " + json.dumps(missing_required), file=sys.stderr)
    sys.exit(1)
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__REQUIRE_COVERAGE_READY__", $RequireCoverageReady.ToString().ToLowerInvariant())

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid MCP tool coverage packet failed with exit code $LASTEXITCODE"
}
