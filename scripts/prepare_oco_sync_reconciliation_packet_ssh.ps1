param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 485,
    [string]$SourceLogPath = "",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireReviewReady
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
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only packet arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonStringOrRaw {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    $current = $Value
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $parsed = $current | ConvertFrom-Json -ErrorAction Stop
            if ($parsed -is [string]) {
                $current = $parsed
                continue
            }
        } catch {
        }
        break
    }
    return $current
}

function Convert-ToJsonArray {
    param([object[]]$Items)
    return ConvertTo-Json -Compress -Depth 8 @($Items)
}

function Count-BeforeMarker {
    param([string]$Text, [string]$Marker)
    $index = $Text.IndexOf($Marker, [StringComparison]::OrdinalIgnoreCase)
    if ($index -lt 0) { return $null }
    $prefix = $Text.Substring(0, $index).TrimEnd()
    $match = [regex]::Match($prefix, "(\d+)\D*$")
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Get-PositionLine {
    param([string]$Text, [int]$PositionId)
    $line = @($Text -split "`r?`n" | Where-Object { $_ -match "Position\s*#?$PositionId\b|#${PositionId}\b" } | Select-Object -First 1)
    if ($line) { return $line.Trim() }
    return ""
}

function Parse-SyncErrorRows {
    param([string]$OcoText)

    $rows = [System.Collections.Generic.List[object]]::new()
    $pattern = [regex]'(?is)Position\s*#?(?<positionId>\d+).*?child\s+(?<childOrderId>\d+)\s+filled\s*@\s*(?<fillPrice>[0-9.]+).*?parent\s*[=:]\s*(?<parentState>[A-Za-z_]+).*?DB\s*(?:still\s*)?(?<dbState>OPEN|CLOSED|[A-Za-z_]+)'
    foreach ($match in $pattern.Matches($OcoText)) {
        $positionId = [int]$match.Groups["positionId"].Value
        $dbState = $match.Groups["dbState"].Value
        $parentState = $match.Groups["parentState"].Value
        $confidence = if ($dbState -match "OPEN" -and $parentState -match "effective") { "HIGH" } else { "REVIEW" }
        $rows.Add([pscustomobject]@{
                positionId = $positionId
                symbol = $Symbol
                strategyId = $StrategyId
                dbState = $dbState
                okxParentState = $parentState
                okxChildOrderId = $match.Groups["childOrderId"].Value
                childFillPrice = $match.Groups["fillPrice"].Value
                proposedCloseReason = "SL"
                proposedClosePrice = $match.Groups["fillPrice"].Value
                proposedWriteTool = "forceClosePosition"
                expectedPostReconciliationState = "DB_CLOSED_SL"
                confidence = $confidence
                sourceEvidence = Get-PositionLine -Text $OcoText -PositionId $positionId
            })
    }

    if ($rows.Count -eq 0 -and $OcoText -match "SYNC_ERROR") {
        foreach ($match in [regex]::Matches($OcoText, "Position\s*#?(?<positionId>\d+).*?SYNC_ERROR", "IgnoreCase")) {
            $positionId = [int]$match.Groups["positionId"].Value
            $rows.Add([pscustomobject]@{
                    positionId = $positionId
                    symbol = $Symbol
                    strategyId = $StrategyId
                    dbState = "UNKNOWN_FROM_OCO_TEXT"
                    okxParentState = "UNKNOWN_FROM_OCO_TEXT"
                    okxChildOrderId = "UNKNOWN_FROM_OCO_TEXT"
                    childFillPrice = "UNKNOWN_FROM_OCO_TEXT"
                    proposedCloseReason = "REVIEW_REQUIRED"
                    proposedClosePrice = "UNKNOWN_FROM_OCO_TEXT"
                    proposedWriteTool = "forceClosePosition"
                    expectedPostReconciliationState = "MANUAL_REVIEW_REQUIRED"
                    confidence = "LOW"
                    sourceEvidence = Get-PositionLine -Text $OcoText -PositionId $positionId
                })
        }
    }

    return @($rows)
}

function Invoke-ReadOnlyRemoteCollection {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST, or pass -SourceLogPath." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY, or pass -SourceLogPath." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }

    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

    $remoteScript = @"
set -euo pipefail
cd '$AppDir'
PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi
export PORT MCP_KEY SYMBOL='$Symbol'
python3 - <<'PY'
import json
import os
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": f"Bearer {os.environ['MCP_KEY']}"}
symbol = os.environ["SYMBOL"].upper()

def call_tool(name, arguments=None, timeout=160):
    body = {"jsonrpc": "2.0", "id": f"oco-sync-reconciliation-{name}", "method": "tools/call", "params": {"name": name, "arguments": arguments or {}}}
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if data.get("error"):
        raise RuntimeError(f"{name} JSON-RPC error: {data['error']}")
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        return content[0].get("text") or ""
    return json.dumps(result, ensure_ascii=False)

print("[oco-sync-reconciliation-source] read-only server-local MCP collection")
print("scope=READ_ONLY; calls getOcoHealth, listOpenPositions, and getExecutionRiskSnapshot only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print("activePort=" + os.environ["PORT"])
print("oco_health_raw_json=" + json.dumps(call_tool("getOcoHealth", {"symbol": symbol}), ensure_ascii=False, separators=(",", ":")))
print("open_positions_raw_json=" + json.dumps(call_tool("listOpenPositions", {"symbol": symbol}), ensure_ascii=False, separators=(",", ":")))
print("execution_risk_snapshot_raw_json=" + json.dumps(call_tool("getExecutionRiskSnapshot", {"symbol": symbol}), ensure_ascii=False, separators=(",", ":")))
print("[oco-sync-reconciliation-source] read-only collection complete")
PY
"@

    $remoteOutput = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "OCO sync reconciliation read-only collection failed with exit code $LASTEXITCODE"
    }
    return ($remoteOutput | Out-String)
}

if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

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
    $sourcePath = "server-local:/api/mcp"
}

$ocoText = Convert-JsonStringOrRaw -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "oco_health_raw_json=")
if ([string]::IsNullOrWhiteSpace($ocoText)) {
    $ocoText = $sourceText
}
$openPositionsText = Convert-JsonStringOrRaw -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "open_positions_raw_json=")
$riskSnapshotText = Convert-JsonStringOrRaw -Value (Get-LastPrefixedValue -Text $sourceText -Prefix "execution_risk_snapshot_raw_json=")

$syncErrorCount = Count-BeforeMarker -Text $ocoText -Marker "SYNC_ERROR"
$reconciliationRows = @(Parse-SyncErrorRows -OcoText $ocoText)
if ($null -eq $syncErrorCount) {
    $summaryMatch = @([regex]::Matches($ocoText, "(?<count>\d+)\s+SYNC_ERROR", "IgnoreCase")) | Select-Object -Last 1
    if ($summaryMatch) {
        $syncErrorCount = [int]$summaryMatch.Groups["count"].Value
    } elseif ($reconciliationRows.Count -gt 0) {
        $syncErrorCount = $reconciliationRows.Count
    } else {
        $syncErrorCount = ([regex]::Matches($ocoText, "SYNC_ERROR", "IgnoreCase")).Count
    }
}
$positionsRequiringWrite = @($reconciliationRows | Where-Object { $_.confidence -in @("HIGH", "REVIEW", "LOW") } | ForEach-Object { $_.positionId } | Sort-Object -Unique)
$completeRows = @($reconciliationRows | Where-Object {
        $_.dbState -notmatch "UNKNOWN" -and
        $_.okxParentState -notmatch "UNKNOWN" -and
        $_.okxChildOrderId -notmatch "UNKNOWN" -and
        $_.childFillPrice -notmatch "UNKNOWN"
    })

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($sourceFreshness -eq "STALE") { $missingRequirements.Add("source log is FRESH") }
if ([string]::IsNullOrWhiteSpace($ocoText)) { $missingRequirements.Add("OCO health text present") }
if ($syncErrorCount -lt 1) { $missingRequirements.Add("current OCO SYNC_ERROR evidence present") }
if ($reconciliationRows.Count -eq 0 -and $syncErrorCount -gt 0) { $missingRequirements.Add("position-level SYNC_ERROR rows parseable") }
if ($completeRows.Count -lt $reconciliationRows.Count) { $missingRequirements.Add("complete DB state, OKX parent state, child fill, and close price for every SYNC_ERROR row") }

$reviewReady = $syncErrorCount -gt 0 -and $reconciliationRows.Count -gt 0 -and $sourceFreshness -ne "STALE"
$status = if ($reviewReady) { "READY_FOR_OPERATOR_RECONCILIATION_REVIEW_NOT_MUTATION" } elseif ($syncErrorCount -eq 0) { "NO_CURRENT_OCO_SYNC_ERROR" } else { "BLOCKED_RECONCILIATION_EVIDENCE_INCOMPLETE" }
$decision = if ($reviewReady) { "PREPARE_SEPARATE_OCO_SYNC_RECONCILIATION_AUTHORIZATION" } elseif ($syncErrorCount -eq 0) { "RERUN_SCOREBUY_GRID_READINESS_READ_ONLY" } else { "REFRESH_OCO_HEALTH_WITH_POSITION_LEVEL_CHILD_FILL_EVIDENCE" }
$nextAction = if ($reviewReady) {
    "Operator may review a separate reconciliation authorization for listed positions; this packet itself must not call forceClosePosition or modify OCO."
} elseif ($syncErrorCount -eq 0) {
    "OCO health currently has no SYNC_ERROR evidence; rerun ScoreBuy/Grid readiness read-only checks."
} else {
    "Refresh server-local getOcoHealth/listOpenPositions evidence until position-level child-fill details are complete."
}

$packet = [pscustomobject]@{
    packetType = "OCO_SYNC_RECONCILIATION_PACKET"
    status = $status
    decision = $decision
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePath = $sourcePath
    sourceFreshness = $sourceFreshness
    sourceAgeMinutes = $sourceAgeMinutes
    syncErrorCount = $syncErrorCount
    positionsRequiringWrite = @($positionsRequiringWrite)
    completeReconciliationRows = @($completeRows).Count
    reconciliationRows = @($reconciliationRows)
    requiredAuthorization = @(
        "separate explicit operator approval before forceClosePosition",
        "one approval decision per affected position",
        "post-reconciliation read-only getOcoHealth verification",
        "post-reconciliation ScoreBuy/Grid readiness refresh"
    )
    allowedVerificationAfterAuthorization = @(
        "checkOcoHealth or getOcoHealth read-only",
        "getExecutionRiskSnapshot read-only",
        "previewScoreBuyConviction read-only",
        "grid readiness read-only packet"
    )
    forbiddenActions = @(
        "place orders",
        "modify or cancel OCO",
        "forceClosePosition without separate authorization",
        "enable live trading",
        "enable scheduler mutation",
        "deploy or change production env",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange state from this packet"
    )
    openPositionsEvidencePresent = -not [string]::IsNullOrWhiteSpace($openPositionsText)
    executionRiskSnapshotEvidencePresent = -not [string]::IsNullOrWhiteSpace($riskSnapshotText)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only OCO sync reconciliation packet only; does not authorize forceClosePosition, close-position, OCO modification/cancelation, live trading, scheduler enablement, orders, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[oco-sync-reconciliation-packet] read-only packet"
Write-Host "scope=READ_ONLY; uses existing source log or server-local /api/mcp read tools only; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_path=$sourcePath"
Write-Host "source_freshness=$sourceFreshness"
Write-Host "symbol=$Symbol"
Write-Host "strategyId=$StrategyId"
Write-Host "sync_error_count=$syncErrorCount"
Write-Host ("positionsRequiringWrite=" + (Convert-ToJsonArray @($positionsRequiringWrite)))
Write-Host ("reconciliationRows=" + (Convert-ToJsonArray @($reconciliationRows)))
Write-Host "complete_reconciliation_rows=$(@($completeRows).Count)"
Write-Host "oco_sync_reconciliation_decision=$decision"
Write-Host "requiredAuthorization=separate explicit operator approval before forceClosePosition or any position/OCO mutation"
Write-Host "force_close_position_allowed=false"
Write-Host "close_position_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host ("oco_sync_reconciliation_missing_requirements=" + (Convert-ToJsonArray @($missingRequirements)))
Write-Host ("oco_sync_reconciliation_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "oco_sync_reconciliation_status=$status"
Write-Host "oco_sync_reconciliation_next_action=$nextAction"
Write-Host "notAuthorization=read-only OCO sync reconciliation packet only; does not authorize forceClosePosition, close-position, OCO modification/cancelation, live trading, scheduler enablement, orders, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[oco-sync-reconciliation-packet] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "OCO sync reconciliation packet is not review-ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
