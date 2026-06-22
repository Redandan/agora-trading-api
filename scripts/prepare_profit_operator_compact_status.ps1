param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-MatrixFreshness {
    param([string]$Path, [int]$MaxAgeMinutes)
    $item = Get-Item -LiteralPath $Path
    $ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        AgeMinutes = $ageMinutes
        MaxAgeMinutes = $MaxAgeMinutes
        Status = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        LastWriteTime = $item.LastWriteTime.ToString("o")
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator compact status arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$pointerPath = Join-Path $ReviewOutputDir "latest-profit-operator-matrix.path"
if (-not (Test-Path -LiteralPath $pointerPath)) {
    throw "Latest matrix pointer not found: $pointerPath. Run prepare_profit_operator_action_brief_ssh.ps1 first."
}

$matrixOutputPath = (Get-Content -LiteralPath $pointerPath -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($matrixOutputPath)) {
    throw "Latest matrix pointer is empty: $pointerPath"
}
if (-not (Test-Path -LiteralPath $matrixOutputPath)) {
    throw "Latest matrix output not found: $matrixOutputPath"
}

$freshness = Get-MatrixFreshness -Path $matrixOutputPath -MaxAgeMinutes $MatrixMaxAgeMinutes
$text = Get-Content -LiteralPath $matrixOutputPath -Raw
$matrixStatus = Get-LastPrefixedValue -Text $text -Prefix "profit_operator_review_matrix_status="
$matrixPacketJson = Get-LastPrefixedValue -Text $text -Prefix "profit_operator_review_matrix_packet="
$matrixNextAction = Get-LastPrefixedValue -Text $text -Prefix "profit_operator_review_matrix_next_action="

if ([string]::IsNullOrWhiteSpace($matrixPacketJson)) {
    throw "profit_operator_review_matrix_packet missing from latest matrix output: $matrixOutputPath"
}

$matrixPacket = $matrixPacketJson | ConvertFrom-Json -ErrorAction Stop
$reviewItems = @($matrixPacket.reviewItems)
if ($reviewItems.Count -eq 0) {
    throw "profit_operator_review_matrix_packet.reviewItems is empty in latest matrix output: $matrixOutputPath"
}

$readyLanes = @($reviewItems | Where-Object { $_.readyForOperatorReview -eq $true } | ForEach-Object {
        [pscustomobject]@{
            lane = [string]$_.lane
            priority = [string]$_.priority
            status = [string]$_.status
            evidenceMarkers = @($_.evidenceMarkers)
            nextAction = [string]$_.nextAction
        }
    })
$blockedLanes = @($reviewItems | Where-Object { $_.readyForOperatorReview -ne $true } | ForEach-Object {
        [pscustomobject]@{
            lane = [string]$_.lane
            priority = [string]$_.priority
            status = [string]$_.status
            evidenceMarkers = @($_.evidenceMarkers)
            missingRequirements = @($_.missingRequirements)
            nextAction = [string]$_.nextAction
        }
    })

$exitSideReady = @($readyLanes | Where-Object { $_.lane -eq "exit-side" }).Count -gt 0
$proposals = @()
if ($exitSideReady) {
    $proposals = @(
        [pscustomobject]@{
            proposalId = "trailing-stop-rollout-review"
            lane = "trailing-stop-rollout"
            status = "READY_TO_DRAFT_REVIEW_NOT_LIVE"
            notAuthorization = "does not authorize live trailing, scheduler enablement, OCO modification, deploy, or production env changes"
        },
        [pscustomobject]@{
            proposalId = "strategy485-risk-reduction-review"
            lane = "strategy485-risk-reduction"
            status = "READY_TO_DRAFT_REVIEW_NOT_MUTATION"
            notAuthorization = "does not authorize close-position, OCO modification, orders, deploy, or production env changes"
        }
    )
}

$compactStatus = if ($freshness.Status -ne "FRESH") {
    "STALE_MATRIX"
} elseif ($exitSideReady) {
    "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"
} elseif ($readyLanes.Count -gt 0 -or $matrixStatus -eq "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    "HAS_REVIEW_READY_ITEMS_NOT_LIVE"
} else {
    "NO_REVIEW_READY_ITEMS"
}

$nextAction = if ($compactStatus -eq "STALE_MATRIX") {
    "Refresh the read-only profit operator matrix before using this status."
} elseif ($exitSideReady) {
    "Prepare a separate exit-side operator review; keep entry-filter and DataFreshness lanes blocked."
} elseif (-not [string]::IsNullOrWhiteSpace($matrixNextAction)) {
    $matrixNextAction
} else {
    "Continue read-only evidence collection."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_COMPACT_STATUS"
    status = $compactStatus
    symbol = $Symbol
    strategyId = $StrategyId
    matrixStatus = $matrixStatus
    sourceMatrixOutputPath = $matrixOutputPath
    sourceMatrixFreshness = $freshness
    readyLaneCount = $readyLanes.Count
    blockedLaneCount = $blockedLanes.Count
    readyLanes = @($readyLanes)
    blockedLanes = @($blockedLanes)
    exitSideActionProposals = @($proposals)
    doNotActions = @(
        "do not enable live trading from this compact status",
        "do not enable trailing scheduler from this compact status",
        "do not close positions or modify OCO from this compact status",
        "do not relax EntryDedup/DataFreshness/live policy from this compact status",
        "do not deploy or change production env from this compact status"
    )
    nextAction = $nextAction
    notAuthorization = "read-only compact profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-compact-status] read-only compact status"
Write-Host "scope=READ_ONLY; reads latest-profit-operator-matrix.path and latest saved matrix only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "profit_operator_compact_source_matrix_pointer=$pointerPath"
Write-Host "profit_operator_compact_source_matrix_output_path=$matrixOutputPath"
Write-Host "profit_operator_compact_freshness_status=$($freshness.Status)"
Write-Host "profit_operator_compact_matrix_age_minutes=$($freshness.AgeMinutes)"
Write-Host "profit_operator_compact_matrix_max_age_minutes=$($freshness.MaxAgeMinutes)"
Write-Host "profit_operator_compact_matrix_status=$matrixStatus"
Write-Host ("profit_operator_compact_ready_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($readyLanes)))
Write-Host ("profit_operator_compact_blocked_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($blockedLanes)))
Write-Host ("profit_operator_compact_exit_side_proposals=" + (ConvertTo-Json -Compress -Depth 8 @($proposals)))
Write-Host ("profit_operator_compact_status_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "profit_operator_compact_status=$compactStatus"
Write-Host "profit_operator_compact_next_action=$nextAction"
Write-Host "notAuthorization=read-only compact profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-compact-status] read-only check complete"

if ($RequireReady -and $compactStatus -ne "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE" -and $compactStatus -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator compact status is not ready: $compactStatus"
}
