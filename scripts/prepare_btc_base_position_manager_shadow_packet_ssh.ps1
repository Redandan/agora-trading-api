param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$PositionIds = "260,261,262",
    [string]$SourceLogPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | ForEach-Object { $_.TrimStart() } |
            Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Parse-RequestedPositionIds {
    param([string]$Value)
    $ids = [System.Collections.Generic.List[long]]::new()
    foreach ($token in $Value.Split(',')) {
        $trimmed = $token.Trim()
        if ($trimmed -notmatch '^\d+$' -or [long]$trimmed -lt 1) {
            throw "PositionIds must be a comma-separated list of positive integers. Invalid token: $trimmed"
        }
        $id = [long]$trimmed
        if (-not $ids.Contains($id)) { $ids.Add($id) }
    }
    if ($ids.Count -lt 1 -or $ids.Count -gt 20) { throw "PositionIds must contain 1 to 20 unique IDs." }
    return @($ids)
}

function Parse-OpenPositionDetails {
    param([string]$Text)
    $rows = @{}
    $pattern = [regex]'(?is)ID:\s*(?<id>\d+)\s+Strategy\s+ID:\s*(?<strategyId>\d+)\s+Interval:\s*(?<interval>\S+).*?入場價:\s*(?<entry>[0-9.]+)\s+數量:\s*(?<qty>[0-9.]+)\s+TP:\s*(?<tp>[0-9.]+)\s*\|\s*SL:\s*(?<sl>[0-9.]+).*?algoId=(?<ocoAlgoId>\d+)'
    foreach ($match in $pattern.Matches($Text)) {
        $id = [long]$match.Groups['id'].Value
        $rows[$id] = [pscustomobject]@{
            positionId = $id
            strategyId = [long]$match.Groups['strategyId'].Value
            intervalCode = $match.Groups['interval'].Value
            entry = [decimal]$match.Groups['entry'].Value
            displayedOwnedQty = [decimal]$match.Groups['qty'].Value
            recordedTp = [decimal]$match.Groups['tp'].Value
            recordedSl = [decimal]$match.Groups['sl'].Value
            ocoAlgoId = [long]$match.Groups['ocoAlgoId'].Value
        }
    }
    return $rows
}

$requestedIds = @(Parse-RequestedPositionIds -Value $PositionIds)
$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceText = ""
$source = ""

if (-not [string]::IsNullOrWhiteSpace($SourceLogPath)) {
    $resolved = if ([System.IO.Path]::IsPathRooted($SourceLogPath)) {
        $SourceLogPath
    } else {
        Join-Path $repoRoot $SourceLogPath
    }
    if (-not (Test-Path -LiteralPath $resolved)) { throw "Source log not found: $resolved" }
    $sourceText = Get-Content -Raw -LiteralPath $resolved
    $source = $resolved
} else {
    $riskScript = Join-Path $PSScriptRoot "smoke_strategy485_position_risk_ssh.ps1"
    if (-not (Test-Path -LiteralPath $riskScript)) { throw "Missing read-only risk collector: $riskScript" }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find pwsh or powershell for read-only risk collection." }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $riskScript `
        -SshHost $SshHost -SshKey $SshKey -AppDir $AppDir -EnvFile $EnvFile `
        -Symbol BTCUSDT -StrategyId 508 -Days 30 -PositionAgeWarnDays 3 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Read-only risk collection failed with exit code $LASTEXITCODE.`n$($output | Out-String)"
    }
    $sourceText = $output | Out-String
    $source = "server-local:/api/mcp via smoke_strategy485_position_risk_ssh.ps1"
}

$decisionRaw = Get-LastPrefixedValue -Text $sourceText -Prefix "strategy485_position_review_decision="
if ([string]::IsNullOrWhiteSpace($decisionRaw)) {
    throw "Read-only source is missing strategy position review decision JSON."
}
$decision = $decisionRaw | ConvertFrom-Json -Depth 20
if ([long]$decision.strategyId -ne 508) {
    throw "Read-only source strategyId must be 508; actual=$($decision.strategyId)"
}

$openRows = Parse-OpenPositionDetails -Text $sourceText
$outcomesById = @{}
foreach ($row in @($decision.positions)) { $outcomesById[[long]$row.positionId] = $row }

$positions = [System.Collections.Generic.List[object]]::new()
$blockers = [System.Collections.Generic.List[string]]::new()
$totalQty = [decimal]0
$totalCost = [decimal]0
$combinedEv = [decimal]0
$allEvPresent = $true

foreach ($id in $requestedIds) {
    if (-not $openRows.ContainsKey($id)) {
        $blockers.Add("POSITION_${id}:OPEN_POSITION_DETAILS_MISSING")
        continue
    }
    if (-not $outcomesById.ContainsKey($id)) {
        $blockers.Add("POSITION_${id}:EV_OUTCOME_MISSING")
        continue
    }
    $open = $openRows[$id]
    $outcome = $outcomesById[$id]
    if ([long]$open.strategyId -ne 508) { $blockers.Add("POSITION_${id}:STRATEGY_NOT_508") }
    if ([decimal]$open.displayedOwnedQty -le 0) { $blockers.Add("POSITION_${id}:DISPLAYED_QTY_INVALID") }
    if ([long]$open.ocoAlgoId -le 0) { $blockers.Add("POSITION_${id}:OCO_ID_MISSING") }

    $ev = $null
    if ($outcome.evUsdt -ne $null -and "$($outcome.evUsdt)" -ne "N/A") {
        $ev = [decimal]$outcome.evUsdt
        $combinedEv += $ev
    } else {
        $allEvPresent = $false
        $blockers.Add("POSITION_${id}:HEURISTIC_EV_MISSING")
    }
    $totalQty += [decimal]$open.displayedOwnedQty
    $totalCost += [decimal]$open.entry * [decimal]$open.displayedOwnedQty
    $positions.Add([pscustomobject]@{
            positionId = $id
            strategyId = [long]$open.strategyId
            intervalCode = $open.intervalCode
            entry = [decimal]$open.entry
            displayedOwnedQty = [decimal]$open.displayedOwnedQty
            recordedTp = [decimal]$open.recordedTp
            recordedSl = [decimal]$open.recordedSl
            ocoAlgoId = [long]$open.ocoAlgoId
            ocoHealthConfirmed = [bool]$decision.ocoHealthOk
            ageDays = $outcome.entryAgeDays
            paperPct = $outcome.paperPct
            heuristicEvUsdt = $ev
            heuristicSuggestion = $outcome.suggestion
        })
}

if (-not [bool]$decision.ocoHealthOk) { $blockers.Add("COHORT_OCO_HEALTH_NOT_CONFIRMED") }
if ($positions.Count -ne $requestedIds.Count) { $blockers.Add("REQUESTED_POSITION_COHORT_INCOMPLETE") }
$blockers.Add("EXACT_TRADED_QTY_OCO_QTY_PARITY_REQUIRES_POST_DEPLOY_MANAGER_TOOL")

$recommendation = "KEEP_OCO"
$hasAgedNegativeEv = @($positions | Where-Object {
        $_.heuristicEvUsdt -ne $null -and [decimal]$_.heuristicEvUsdt -lt 0 -and
        $_.ageDays -ne "N/A" -and [decimal]$_.ageDays -ge 3
    }).Count -gt 0
$hasNegativeEv = $allEvPresent -and $combinedEv -lt 0
$hasClose = @($positions | Where-Object { $_.heuristicSuggestion -eq "CLOSE" }).Count -gt 0
if ($hasClose -or $hasAgedNegativeEv) {
    $recommendation = "RETIRE_CLOSE_REVIEW"
} elseif ($hasNegativeEv) {
    $recommendation = "RECOVERY_EXIT_REVIEW"
}

$weightedEntry = if ($totalQty -gt 0) { $totalCost / $totalQty } else { $null }
$packet = [ordered]@{
    packetType = "BTC_BASE_POSITION_MANAGER_PREDEPLOY_SHADOW_PACKET"
    policyMode = "BTC_BASE_POSITION_MANAGER_V1"
    stage = "PREDEPLOY_PRODUCTION_READ_ONLY_SIMULATION"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    source = $source
    symbol = "BTCUSDT"
    strategyId = 508
    requestedPositionIds = @($requestedIds)
    positions = @($positions)
    aggregate = [ordered]@{
        displayedOwnedQty = $totalQty
        costUsdt = $totalCost
        weightedEntry = $weightedEntry
        heuristicCombinedEvUsdt = if ($allEvPresent) { $combinedEv } else { $null }
        exactOwnershipConfirmed = $false
        feeAdjustedExitNowPnlAvailable = $false
    }
    simulationRecommendation = $recommendation
    adoptionEligible = $false
    adoptionPersisted = $false
    blockers = @($blockers | Select-Object -Unique)
    status = if ([bool]$decision.ocoHealthOk -and $positions.Count -eq $requestedIds.Count) {
        "READY_FOR_POST_DEPLOY_EXACT_ADOPTION_PREVIEW_NOT_LIVE_ACTION"
    } else {
        "BLOCKED_PREDEPLOY_PRODUCTION_EVIDENCE_INCOMPLETE"
    }
    nextAction = "After deployment, call previewBtcBasePositionAdoption and previewBtcBasePositionDisposition for the exact IDs; keep all OCO active."
    safety = [ordered]@{
        databaseMutated = $false
        runtimeEvidenceWritten = $false
        orderSent = $false
        positionClosed = $false
        ocoCancelled = $false
        ocoModified = $false
        telegramSent = $false
        fundsMoved = $false
    }
    notAuthorization = "Read-only predeploy simulation only; does not authorize adoption persistence, close, OCO/order change, deploy, production env change, scheduler, Telegram, DB, grid, fund, Earn, or exchange mutation."
}

Write-Host "[btc-base-position-manager-shadow-packet] production read-only predeploy simulation"
Write-Host "scope=READ_ONLY; existing server-local MCP evidence only; no production mutation."
Write-Host ("requested_position_ids=" + (ConvertTo-Json -Compress @($requestedIds)))
Write-Host "simulation_recommendation=$recommendation"
Write-Host "adoption_eligible=false"
Write-Host ("blockers=" + (ConvertTo-Json -Compress @($packet.blockers)))
Write-Host ("btc_base_position_manager_packet=" + (ConvertTo-Json -Compress -Depth 20 $packet))
Write-Host "btc_base_position_manager_status=$($packet.status)"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[btc-base-position-manager-shadow-packet] read-only check complete"
