param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$SourceLogPath = "",
    [int]$ResidualLimit = 50,
    [int]$MaxAgeMinutes = 180,
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

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for read-only packet invocation."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonPrefixedValue {
    param([string]$Value, [object]$Default = $null)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $Default }

    $current = $Value
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $parsed = $current | ConvertFrom-Json -ErrorAction Stop
            if ($parsed -is [string]) {
                $current = $parsed
                continue
            }
            return $parsed
        } catch {
            return $Default
        }
    }
    return $Default
}

function Convert-ToJsonText {
    param([object]$Value, [int]$Depth = 12)
    return ConvertTo-Json -InputObject $Value -Compress -Depth $Depth
}

function Convert-ToDecimal {
    param([object]$Value)
    if ($null -eq $Value) { return [decimal]0 }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return [decimal]0 }
    return [decimal]::Parse($text, [Globalization.CultureInfo]::InvariantCulture)
}

function Get-ObjectPropertyValue {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    return $property.Value
}

function Invoke-ReadOnlyRemoteCollection {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST, or pass -SourceLogPath." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY, or pass -SourceLogPath." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }

    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

    $remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
RESIDUAL_LIMIT='__RESIDUAL_LIMIT__'

fail() {
  echo "[closed-grid-residual-source] FAIL: $*" >&2
  exit 1
}

read_env_key() {
  local key="$1"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
  printf '%s\n' "${line#*=}" | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

command -v mysql >/dev/null 2>&1 || fail "mysql is not available on server"
[ -s app.port ] || fail "app.port missing"
PORT="$(cat app.port | tr -d '[:space:]')"
case "$PORT" in ''|*[!0-9]*) fail "app.port is invalid: $PORT" ;; esac

MCP_KEY="$(read_env_key TRADING_MCP_KEY)"
SPRING_DATASOURCE_URL="$(read_env_key SPRING_DATASOURCE_URL)"
SPRING_DATASOURCE_USERNAME="$(read_env_key SPRING_DATASOURCE_USERNAME)"
SPRING_DATASOURCE_PASSWORD="$(read_env_key SPRING_DATASOURCE_PASSWORD)"

case "$SPRING_DATASOURCE_URL" in
  jdbc:mysql://*) ;;
  *) fail "SPRING_DATASOURCE_URL must be a jdbc:mysql URL" ;;
esac

jdbc_without_prefix="${SPRING_DATASOURCE_URL#jdbc:mysql://}"
jdbc_without_query="${jdbc_without_prefix%%\?*}"
host_port="${jdbc_without_query%%/*}"
database="${jdbc_without_query#*/}"
[ -n "$database" ] && [ "$database" != "$jdbc_without_query" ] || fail "database name missing in SPRING_DATASOURCE_URL"
if [ "$database" != "agora_market" ]; then
  fail "refusing to query unexpected database: $database"
fi

if printf '%s\n' "$host_port" | grep -q ':'; then
  host="${host_port%%:*}"
  port="${host_port##*:}"
else
  host="$host_port"
  port="3306"
fi
case "$port" in ''|*[!0-9]*) fail "database port is invalid in SPRING_DATASOURCE_URL: $port" ;; esac

ACTIVE_LOG=""
if [ -d logs/runs ]; then
  ACTIVE_LOG="$(ls -1t logs/runs/app-*port${PORT}.log 2>/dev/null | head -n 1 || true)"
fi
if [ -z "$ACTIVE_LOG" ] && [ -f app.log ]; then
  ACTIVE_LOG="app.log"
fi

export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
export PORT MCP_KEY SYMBOL RESIDUAL_LIMIT ACTIVE_LOG MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import json
import os
import re
import subprocess
import sys
import urllib.request

symbol = os.environ["SYMBOL"].upper()
residual_limit = int(os.environ["RESIDUAL_LIMIT"])
currency = symbol[:-4] if symbol.endswith("USDT") else symbol
url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": f"Bearer {os.environ['MCP_KEY']}"}

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def run_query(sql):
    cmd = [
        "mysql",
        "--batch",
        "--raw",
        "--skip-column-names",
        "-h", os.environ["MYSQL_HOST"],
        "-P", os.environ["MYSQL_PORT"],
        "-u", os.environ["MYSQL_USER"],
        os.environ["MYSQL_DATABASE"],
        "-e", sql,
    ]
    try:
        proc = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exc:
        print(exc.stderr or exc.stdout, file=sys.stderr)
        sys.exit(exc.returncode or 1)
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def call_tool(name, arguments=None, timeout=160):
    body = {
        "jsonrpc": "2.0",
        "id": f"closed-grid-residual-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if data.get("error"):
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

def safe_call(name, arguments=None, timeout=160):
    try:
        text = call_tool(name, arguments or {}, timeout=timeout)
        return {"ok": True, "text": text[:4000]}
    except Exception as exc:
        return {"ok": False, "error": str(exc)[:1000]}

def count_log(pattern):
    path = os.environ.get("ACTIVE_LOG", "")
    if not path:
        return 0
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except Exception:
        return 0
    return len(re.findall(pattern, text))

closed_sql = f"""
SELECT
  l.id,
  l.grid_id,
  g.symbol,
  l.level_index,
  l.status,
  CAST(l.filled_qty AS CHAR),
  CAST(l.filled_price AS CHAR),
  CAST(l.paired_sell_price AS CHAR),
  CAST(COALESCE(l.filled_qty, 0) * COALESCE(l.paired_sell_price, l.filled_price, 0) AS CHAR),
  COALESCE(DATE_FORMAT(l.filled_at, '%Y-%m-%dT%H:%i:%s.%fZ'), ''),
  COALESCE(DATE_FORMAT(l.closed_at, '%Y-%m-%dT%H:%i:%s.%fZ'), ''),
  l.retry_count,
  COALESCE(l.error_message, ''),
  CAST(g.enabled AS CHAR),
  COALESCE(DATE_FORMAT(g.paused_at, '%Y-%m-%dT%H:%i:%s.%fZ'), ''),
  COALESCE(g.paused_reason, ''),
  COALESCE(DATE_FORMAT(g.closed_at, '%Y-%m-%dT%H:%i:%s.%fZ'), ''),
  CAST(g.price_lower AS CHAR),
  CAST(g.price_upper AS CHAR),
  CAST(g.per_level_usdt AS CHAR),
  CAST(g.total_realized_pnl AS CHAR)
FROM bt_grid_level l
JOIN bt_grid g ON g.id = l.grid_id
WHERE g.symbol = '{esc(symbol)}'
  AND g.closed_at IS NOT NULL
  AND l.status IN ('HOLDING', 'SELL_FAILED', 'SELL_PARTIAL')
  AND l.filled_qty IS NOT NULL
  AND l.filled_qty > 0
ORDER BY g.closed_at DESC, l.id ASC
LIMIT {residual_limit}
"""

rows = []
for raw in run_query(closed_sql):
    if len(raw) < 21:
        continue
    rows.append({
        "levelId": raw[0],
        "gridId": raw[1],
        "symbol": raw[2],
        "levelIndex": raw[3],
        "status": raw[4],
        "filledQty": raw[5],
        "filledPrice": raw[6],
        "pairedSellPrice": raw[7],
        "estimatedNotionalUsdt": raw[8],
        "filledAt": raw[9],
        "levelClosedAt": raw[10],
        "retryCount": raw[11],
        "errorMessage": raw[12],
        "gridEnabled": raw[13],
        "gridPausedAt": raw[14],
        "gridPausedReason": raw[15],
        "gridClosedAt": raw[16],
        "gridPriceLower": raw[17],
        "gridPriceUpper": raw[18],
        "gridPerLevelUsdt": raw[19],
        "gridTotalRealizedPnl": raw[20],
    })

summary_sql = f"""
SELECT
  IF(g.closed_at IS NULL, 'ACTIVE_GRID', 'CLOSED_GRID') lifecycle,
  COUNT(*),
  CAST(COALESCE(SUM(l.filled_qty), 0) AS CHAR),
  CAST(COALESCE(SUM(l.filled_qty * COALESCE(l.paired_sell_price, l.filled_price, 0)), 0) AS CHAR)
FROM bt_grid_level l
JOIN bt_grid g ON g.id = l.grid_id
WHERE g.symbol = '{esc(symbol)}'
  AND l.status IN ('HOLDING', 'SELL_FAILED', 'SELL_PARTIAL')
  AND l.filled_qty IS NOT NULL
  AND l.filled_qty > 0
GROUP BY IF(g.closed_at IS NULL, 'ACTIVE_GRID', 'CLOSED_GRID')
ORDER BY lifecycle
"""
summary_rows = []
for raw in run_query(summary_sql):
    if len(raw) < 4:
        continue
    summary_rows.append({
        "lifecycle": raw[0],
        "rowCount": raw[1],
        "filledQty": raw[2],
        "estimatedNotionalUsdt": raw[3],
    })

grid_ids = []
for row in rows:
    if row["gridId"] not in grid_ids:
        grid_ids.append(row["gridId"])

mcp = {
    "listGrids": safe_call("listGrids", {}),
    "listGridDustSellRisks": safe_call("listGridDustSellRisks", {"minNotionalUsdt": 10.0}),
    "getOcoHealth": safe_call("getOcoHealth", {"symbol": symbol}),
    "listOpenPositions": safe_call("listOpenPositions", {"symbol": symbol}),
    "getExecutionRiskSnapshot": safe_call("getExecutionRiskSnapshot", {"symbol": symbol}),
    "reconcileOrphanTrades": safe_call("reconcileOrphanTrades", {
        "currency": currency,
        "hoursBack": 168,
        "priceTolerance": 20.0,
        "qtyTolerancePct": 1.0,
        "timeToleranceMinutes": 10,
        "includeFixSuggestion": False,
    }),
    "gridStats": [],
}
for grid_id in grid_ids[:5]:
    try:
        gid = int(grid_id)
    except Exception:
        continue
    mcp["gridStats"].append({"gridId": gid, "result": safe_call("gridStats", {"gridId": gid})})

print("[closed-grid-residual-source] read-only production collection")
print("scope=READ_ONLY; SELECTs bt_grid/bt_grid_level only and calls server-local MCP read tools only; no DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange mutation, external backfill/import, deploy, restart, nginx, or production env state changed.")
print("activePort=" + os.environ["PORT"])
print("activeLog=" + os.environ.get("ACTIVE_LOG", ""))
print("closed_grid_residual_rows_json=" + json.dumps(rows, ensure_ascii=False, separators=(",", ":")))
print("grid_residual_summary_json=" + json.dumps(summary_rows, ensure_ascii=False, separators=(",", ":")))
print("mcp_read_evidence_json=" + json.dumps(mcp, ensure_ascii=False, separators=(",", ":")))
print("untrackedWarnCount=" + str(count_log("發現未追蹤持倉|untracked position|untracked spot inventory")))
print("closedGridResidualInfoCount=" + str(count_log("include known closed-grid residual")))
print("[closed-grid-residual-source] read-only collection complete")
PY
'@

    $remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
        Replace("__ENVFILE__", $EnvFile).
        Replace("__SYMBOL__", $Symbol.ToUpperInvariant()).
        Replace("__RESIDUAL_LIMIT__", [string]$ResidualLimit)

    $remoteOutput = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "Closed grid residual read-only collection failed with exit code $LASTEXITCODE"
    }
    return ($remoteOutput | Out-String)
}

if ($ResidualLimit -lt 1 -or $ResidualLimit -gt 500) { throw "ResidualLimit must be between 1 and 500." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceText = ""
$sourcePath = ""
$sourceFreshness = "LIVE_READ_ONLY_COLLECTION"
$sourceAgeMinutes = 0

if (-not [string]::IsNullOrWhiteSpace($SourceLogPath)) {
    $resolvedSourcePath = if ([System.IO.Path]::IsPathRooted($SourceLogPath)) { $SourceLogPath } else { Join-Path $repoRoot $SourceLogPath }
    if (-not (Test-Path -LiteralPath $resolvedSourcePath)) { throw "Source log not found: $resolvedSourcePath" }
    $item = Get-Item -LiteralPath $resolvedSourcePath
    $sourceAgeMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    $sourceFreshness = if ($sourceAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    $sourceText = Get-Content -Raw -LiteralPath $resolvedSourcePath
    $sourcePath = $resolvedSourcePath
} else {
    $sourceText = Invoke-ReadOnlyRemoteCollection
    $sourcePath = "server-local:ssh:$SshHost"
}

$activePort = Get-LastPrefixedValue -Text $sourceText -Prefix "activePort="
$activeLog = Get-LastPrefixedValue -Text $sourceText -Prefix "activeLog="
$closedRows = @(Convert-JsonPrefixedValue -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "closed_grid_residual_rows_json=") -Default @())
$summaryRows = @(Convert-JsonPrefixedValue -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "grid_residual_summary_json=") -Default @())
$mcpEvidence = Convert-JsonPrefixedValue -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "mcp_read_evidence_json=") -Default ([pscustomobject]@{})
$untrackedWarnCount = [int](Get-LastPrefixedValue -Text $sourceText -Prefix "untrackedWarnCount=" -Default "0")
$closedGridResidualInfoCount = [int](Get-LastPrefixedValue -Text $sourceText -Prefix "closedGridResidualInfoCount=" -Default "0")

$closedResidualQty = [decimal]0
$closedResidualNotional = [decimal]0
foreach ($row in $closedRows) {
    $closedResidualQty += Convert-ToDecimal (Get-ObjectPropertyValue -Object $row -Name "filledQty" -Default "0")
    $closedResidualNotional += Convert-ToDecimal (Get-ObjectPropertyValue -Object $row -Name "estimatedNotionalUsdt" -Default "0")
}

$closedSummary = $summaryRows | Where-Object { (Get-ObjectPropertyValue -Object $_ -Name "lifecycle" -Default "") -eq "CLOSED_GRID" } | Select-Object -First 1
$activeSummary = $summaryRows | Where-Object { (Get-ObjectPropertyValue -Object $_ -Name "lifecycle" -Default "") -eq "ACTIVE_GRID" } | Select-Object -First 1
$closedSummaryQty = Convert-ToDecimal (Get-ObjectPropertyValue -Object $closedSummary -Name "filledQty" -Default "0")
$activeSummaryQty = Convert-ToDecimal (Get-ObjectPropertyValue -Object $activeSummary -Name "filledQty" -Default "0")

$gridIds = @($closedRows | ForEach-Object { Get-ObjectPropertyValue -Object $_ -Name "gridId" -Default "" } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
$levelIds = @($closedRows | ForEach-Object { Get-ObjectPropertyValue -Object $_ -Name "levelId" -Default "" } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)

$mcpChecks = @("listGrids", "listGridDustSellRisks", "getOcoHealth", "listOpenPositions", "getExecutionRiskSnapshot", "reconcileOrphanTrades")
$mcpMissing = [System.Collections.Generic.List[string]]::new()
foreach ($check in $mcpChecks) {
    $node = Get-ObjectPropertyValue -Object $mcpEvidence -Name $check -Default $null
    if ($null -eq $node -or -not [bool](Get-ObjectPropertyValue -Object $node -Name "ok" -Default $false)) {
        $mcpMissing.Add($check)
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($sourceFreshness -eq "STALE") { $missingRequirements.Add("fresh source log within MaxAgeMinutes") }
if ($closedRows.Count -lt 1) { $missingRequirements.Add("at least one closed-grid residual row in bt_grid_level") }
if ($untrackedWarnCount -gt 0) { $missingRequirements.Add("active runtime log has no untracked-position WARN after residual classification") }
if ($closedGridResidualInfoCount -lt 1) { $missingRequirements.Add("active runtime log shows include known closed-grid residual classification") }
if ($summaryRows.Count -lt 1) { $missingRequirements.Add("grid residual summary rows present") }
foreach ($missing in $mcpMissing) { $missingRequirements.Add("server-local MCP read evidence present: $missing") }

$ready = $sourceFreshness -ne "STALE" -and $closedRows.Count -gt 0 -and $untrackedWarnCount -eq 0 -and $closedGridResidualInfoCount -gt 0 -and $mcpMissing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_CLOSED_GRID_RESIDUAL_OPERATOR_DECISION_NOT_MUTATION"
} elseif ($closedRows.Count -eq 0 -and $sourceFreshness -ne "STALE") {
    "NO_CLOSED_GRID_RESIDUAL"
} else {
    "BLOCKED_CLOSED_GRID_RESIDUAL_EVIDENCE_INCOMPLETE"
}

$decision = if ($ready) {
    "OPERATOR_CHOOSE_KEEP_WATCH_OR_REQUEST_SEPARATE_CLEANUP_PLAN"
} elseif ($closedRows.Count -eq 0 -and $sourceFreshness -ne "STALE") {
    "NO_CLEANUP_NEEDED_KEEP_WATCH"
} else {
    "REFRESH_CLOSED_GRID_RESIDUAL_PACKET_BEFORE_ANY_CLEANUP"
}

$exactAuthorizationTextForNextPlan = "I explicitly authorize preparation of a separate closed-grid residual cleanup execution plan for $($Symbol.ToUpperInvariant()) after reviewing this CLOSED_GRID_RESIDUAL_DISPOSITION_PACKET. The next plan must list exact bt_grid_level ids, qty, estimated notional, mutation type, rollback/read-only verification, and must not execute DB writes, exchange sells, grid/OCO mutation, Telegram send, deploy, or env changes until I approve that separate execution plan."

$packet = [pscustomobject]@{
    packetType = "CLOSED_GRID_RESIDUAL_DISPOSITION_PACKET"
    status = $status
    decision = $decision
    symbol = $Symbol.ToUpperInvariant()
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    sourcePath = $sourcePath
    sourceFreshness = $sourceFreshness
    sourceAgeMinutes = $sourceAgeMinutes
    activePort = $activePort
    activeLog = $activeLog
    affectedGridIds = @($gridIds)
    affectedLevelIds = @($levelIds)
    closedResidualRows = $closedRows.Count
    closedResidualQty = $closedResidualQty.ToString([Globalization.CultureInfo]::InvariantCulture)
    closedResidualEstimatedNotionalUsdt = $closedResidualNotional.ToString([Globalization.CultureInfo]::InvariantCulture)
    closedSummaryQty = $closedSummaryQty.ToString([Globalization.CultureInfo]::InvariantCulture)
    activeGridQty = $activeSummaryQty.ToString([Globalization.CultureInfo]::InvariantCulture)
    untrackedWarnCount = $untrackedWarnCount
    closedGridResidualInfoCount = $closedGridResidualInfoCount
    residualRows = @($closedRows)
    residualSummary = @($summaryRows)
    mcpReadEvidenceMissing = @($mcpMissing)
    missingRequirements = @($missingRequirements)
    decisionOptions = @(
        [pscustomobject]@{
            option = "KEEP_TRACKED_RESIDUAL_AND_WATCH"
            mutationRequired = $false
            description = "Keep the residual counted as known DB inventory and monitor OCO/grid reconciliation logs."
        },
        [pscustomobject]@{
            option = "REQUEST_EXCHANGE_SELL_RESIDUAL"
            mutationRequired = $true
            separateAuthorizationRequired = $true
            description = "Requires a new execution plan that states exact qty/max notional and confirms the sell will not conflict with active strategy exposure."
        },
        [pscustomobject]@{
            option = "REQUEST_DB_RECONCILE_ONLY"
            mutationRequired = $true
            separateAuthorizationRequired = $true
            description = "Requires proof the residual is no longer present on OKX before any bt_grid_level update."
        },
        [pscustomobject]@{
            option = "REQUEST_GRID_RESIDUAL_REPAIR_DESIGN"
            mutationRequired = $false
            separateAuthorizationRequired = $true
            description = "Design a follow-up repair path if closed-grid residuals should become first-class lifecycle state."
        }
    )
    exactAuthorizationTextForNextPlan = $exactAuthorizationTextForNextPlan
    forbiddenActions = @(
        "DB writes or cleanup",
        "exchange market sell or buy",
        "OCO modification or cancelation",
        "grid close/resume/rebuild/auto-rebalance mutation",
        "fund/Earn transfer or redemption",
        "Telegram send",
        "scheduler enablement",
        "deploy, restart, nginx, or production env changes",
        "external backfill/import"
    )
    notAuthorization = "read-only closed-grid residual disposition packet only; does not authorize cleanup, DB writes, exchange buy/sell, OCO/grid/fund/Earn/Telegram/scheduler mutation, deploy, restart, production env changes, or external backfill/import"
}

$nextAction = if ($ready) {
    "Choose KEEP_TRACKED_RESIDUAL_AND_WATCH or request a separate cleanup execution plan using exactAuthorizationTextForNextPlan; this packet itself must not mutate anything."
} elseif ($closedRows.Count -eq 0 -and $sourceFreshness -ne "STALE") {
    "No closed-grid residual row is currently present; keep the OCO/grid reconciliation watch."
} else {
    "Refresh the read-only packet until DB, MCP, and runtime-log evidence are complete before discussing cleanup."
}

Write-Host "[closed-grid-residual-disposition-packet] read-only packet"
Write-Host "scope=READ_ONLY; uses existing source log or server-local SSH collection; SELECTs trading-owned bt_grid/bt_grid_level and calls server-local MCP read tools only; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange mutation, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_path=$sourcePath"
Write-Host "source_freshness=$sourceFreshness"
Write-Host "symbol=$($Symbol.ToUpperInvariant())"
Write-Host "active_port=$activePort"
Write-Host "active_log=$activeLog"
Write-Host ("affected_grid_ids=" + (Convert-ToJsonText @($gridIds)))
Write-Host ("affected_level_ids=" + (Convert-ToJsonText @($levelIds)))
Write-Host "closed_grid_residual_rows=$($closedRows.Count)"
Write-Host "closed_grid_residual_qty=$($closedResidualQty.ToString([Globalization.CultureInfo]::InvariantCulture))"
Write-Host "closed_grid_residual_estimated_notional_usdt=$($closedResidualNotional.ToString([Globalization.CultureInfo]::InvariantCulture))"
Write-Host "closed_grid_summary_qty=$($closedSummaryQty.ToString([Globalization.CultureInfo]::InvariantCulture))"
Write-Host "active_grid_summary_qty=$($activeSummaryQty.ToString([Globalization.CultureInfo]::InvariantCulture))"
Write-Host "untracked_warn_count=$untrackedWarnCount"
Write-Host "closed_grid_residual_info_count=$closedGridResidualInfoCount"
Write-Host ("closed_grid_residual_missing_requirements=" + (Convert-ToJsonText @($missingRequirements)))
Write-Host "closed_grid_residual_decision=$decision"
Write-Host "requiredAuthorization=separate explicit operator approval before any cleanup, DB write, exchange sell, OCO/grid mutation, Telegram send, deploy, restart, or production env change"
Write-Host "exactAuthorizationTextForNextPlan=$exactAuthorizationTextForNextPlan"
Write-Host "closed_grid_residual_cleanup_allowed=false"
Write-Host "exchange_sell_allowed=false"
Write-Host "db_write_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "fund_or_earn_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "external_backfill_or_import_allowed=false"
Write-Host ("closed_grid_residual_disposition_packet=" + (Convert-ToJsonText $packet -Depth 14))
Write-Host "closed_grid_residual_disposition_status=$status"
Write-Host "closed_grid_residual_next_action=$nextAction"
Write-Host "notAuthorization=read-only closed-grid residual disposition packet only; does not authorize cleanup, DB writes, exchange buy/sell, OCO/grid/fund/Earn/Telegram/scheduler mutation, deploy, restart, production env changes, or external backfill/import"
Write-Host "[closed-grid-residual-disposition-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Closed grid residual disposition packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
