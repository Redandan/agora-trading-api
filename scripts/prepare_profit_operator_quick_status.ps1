param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [switch]$RequireFreshMatrix,
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

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator quick status arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$pointerPath = Join-Path $ReviewOutputDir "latest-profit-operator-matrix.path"
$status = "REFRESH_REQUIRED_NO_MATRIX"
$refreshRequired = $true
$compactStatus = ""
$compactPacket = $null
$compactExitCode = $null
$matrixOutputPath = ""
$nextAction = "Run prepare_profit_operator_action_brief_ssh.ps1 to collect a fresh read-only matrix before using profit operator status."

if (Test-Path -LiteralPath $pointerPath) {
    $matrixOutputPath = (Get-Content -Raw -LiteralPath $pointerPath).Trim()
    if ([string]::IsNullOrWhiteSpace($matrixOutputPath)) {
        $status = "REFRESH_REQUIRED_EMPTY_MATRIX_POINTER"
        $nextAction = "Refresh the read-only profit operator matrix because latest-profit-operator-matrix.path is empty."
    } elseif (-not (Test-Path -LiteralPath $matrixOutputPath)) {
        $status = "REFRESH_REQUIRED_MATRIX_OUTPUT_MISSING"
        $nextAction = "Refresh the read-only profit operator matrix because the latest matrix output file is missing."
    } else {
        $compactScript = Join-Path $PSScriptRoot "prepare_profit_operator_compact_status.ps1"
        if (-not (Test-Path -LiteralPath $compactScript)) {
            throw "Missing compact status script: $compactScript"
        }
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
        if ($null -eq $powerShell) {
            $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
        }
        if ($null -eq $powerShell) {
            throw "Unable to find powershell or pwsh for profit operator quick status."
        }

        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $compactScript `
            -ReviewOutputDir $ReviewOutputDir `
            -MatrixMaxAgeMinutes $MatrixMaxAgeMinutes `
            -Symbol $Symbol `
            -StrategyId $StrategyId 2>&1
        $compactExitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
        $compactText = ($output | Out-String -Width 4096)
        $compactStatus = Get-LastPrefixedValue -Text $compactText -Prefix "profit_operator_compact_status="
        $compactPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $compactText -Prefix "profit_operator_compact_status_packet=")

        if ($compactExitCode -ne 0) {
            $status = "REFRESH_REQUIRED_COMPACT_STATUS_FAILED"
            $nextAction = "Refresh the read-only profit operator matrix or inspect compact status failure before using profit operator status."
        } elseif ($compactStatus -eq "STALE_MATRIX") {
            $status = "REFRESH_REQUIRED_STALE_MATRIX"
            $nextAction = "Refresh the read-only profit operator matrix before using this quick status."
        } elseif (-not [string]::IsNullOrWhiteSpace($compactStatus)) {
            $status = $compactStatus
            $refreshRequired = $false
            if ($null -ne $compactPacket -and -not [string]::IsNullOrWhiteSpace([string]$compactPacket.nextAction)) {
                $nextAction = [string]$compactPacket.nextAction
            } else {
                $nextAction = "Use the latest saved read-only matrix status; rerun fresh matrix only when evidence is stale or a new sample is expected."
            }
        } else {
            $status = "REFRESH_REQUIRED_COMPACT_STATUS_MISSING"
            $nextAction = "Refresh the read-only profit operator matrix because compact status did not emit profit_operator_compact_status."
        }
    }
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_QUICK_STATUS"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    reviewOutputDir = $ReviewOutputDir
    latestMatrixPointer = $pointerPath
    latestMatrixOutputPath = $matrixOutputPath
    matrixMaxAgeMinutes = $MatrixMaxAgeMinutes
    refreshRequired = $refreshRequired
    compactStatus = $compactStatus
    compactExitCode = $compactExitCode
    compactStatusPacket = $compactPacket
    doNotActions = @(
        "do not enable live trading from this quick status",
        "do not enable trailing scheduler from this quick status",
        "do not close positions or modify OCO from this quick status",
        "do not relax EntryDedup/DataFreshness/live policy from this quick status",
        "do not deploy or change production env from this quick status"
    )
    nextAction = $nextAction
    notAuthorization = "read-only quick profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-quick-status] read-only quick status"
Write-Host "scope=READ_ONLY; reads latest-profit-operator-matrix.path and may invoke prepare_profit_operator_compact_status.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "profit_operator_quick_source_matrix_pointer=$pointerPath"
Write-Host "profit_operator_quick_source_matrix_output_path=$matrixOutputPath"
Write-Host "profit_operator_quick_compact_status=$compactStatus"
Write-Host "profit_operator_quick_compact_exit_code=$compactExitCode"
Write-Host "profit_operator_quick_refresh_required=$($refreshRequired.ToString().ToLowerInvariant())"
Write-Host ("profit_operator_quick_status_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_operator_quick_status=$status"
Write-Host "profit_operator_quick_next_action=$nextAction"
Write-Host "notAuthorization=read-only quick profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-quick-status] read-only check complete"

if ($RequireFreshMatrix -and $refreshRequired) {
    throw "Profit operator quick status requires a fresh matrix: $status"
}
if ($RequireReady -and $status -ne "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE" -and $status -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator quick status is not ready: $status"
}
