Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_closed_grid_residual_disposition_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$runbookPath = Join-Path $repoRoot "docs\deploy-runbook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $runbookPath
) -join "`n"

foreach ($marker in @(
        "[closed-grid-residual-disposition-packet] read-only packet",
        "scope=READ_ONLY",
        "CLOSED_GRID_RESIDUAL_DISPOSITION_PACKET",
        "READY_FOR_CLOSED_GRID_RESIDUAL_OPERATOR_DECISION_NOT_MUTATION",
        "OPERATOR_CHOOSE_KEEP_WATCH_OR_REQUEST_SEPARATE_CLEANUP_PLAN",
        "KEEP_TRACKED_RESIDUAL_AND_WATCH",
        "REQUEST_EXCHANGE_SELL_RESIDUAL",
        "REQUEST_DB_RECONCILE_ONLY",
        "exactAuthorizationTextForNextPlan",
        "closed_grid_residual_cleanup_allowed=false",
        "exchange_sell_allowed=false",
        "db_write_allowed=false",
        "order_allowed=false",
        "grid_mutation_allowed=false",
        "telegram_send_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only closed-grid residual disposition packet only"
    )) {
    Assert-Contains -Name "closed-grid residual disposition script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_closed_grid_residual_disposition_packet_ssh.ps1",
        "CLOSED_GRID_RESIDUAL_DISPOSITION_PACKET",
        "closed_grid_residual_disposition_status",
        "READY_FOR_CLOSED_GRID_RESIDUAL_OPERATOR_DECISION_NOT_MUTATION",
        "closed_grid_residual_cleanup_allowed=false"
    )) {
    Assert-Contains -Name "docs mention closed-grid residual disposition packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("closed-grid-residual-disposition-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $closedRows = @(
        [pscustomobject]@{
            levelId = "61"
            gridId = "7"
            symbol = "BTCUSDT"
            levelIndex = "3"
            status = "HOLDING"
            filledQty = "0.00012479"
            filledPrice = "60200.00000000"
            pairedSellPrice = "60400.00000000"
            estimatedNotionalUsdt = "7.53731600"
            filledAt = "2026-07-03T00:00:00.000000Z"
            levelClosedAt = ""
            retryCount = "0"
            errorMessage = ""
            gridEnabled = "0"
            gridPausedAt = ""
            gridPausedReason = ""
            gridClosedAt = "2026-07-05T00:00:00.000000Z"
            gridPriceLower = "56000.00000000"
            gridPriceUpper = "64000.00000000"
            gridPerLevelUsdt = "10.00"
            gridTotalRealizedPnl = "1.23000000"
        }
    )
    $summaryRows = @(
        [pscustomobject]@{
            lifecycle = "ACTIVE_GRID"
            rowCount = "2"
            filledQty = "0.00030000"
            estimatedNotionalUsdt = "18.00000000"
        },
        [pscustomobject]@{
            lifecycle = "CLOSED_GRID"
            rowCount = "1"
            filledQty = "0.00012479"
            estimatedNotionalUsdt = "7.53731600"
        }
    )
    $mcpEvidence = [pscustomobject]@{
        listGrids = [pscustomobject]@{ ok = $true; text = "Grid #7 BTCUSDT CLOSED" }
        listGridDustSellRisks = [pscustomobject]@{ ok = $true; text = "threshold: 10.0 USDT" }
        getOcoHealth = [pscustomobject]@{ ok = $true; text = "OCO Health Check fixture" }
        listOpenPositions = [pscustomobject]@{ ok = $true; text = "no open positions fixture" }
        getExecutionRiskSnapshot = [pscustomobject]@{ ok = $true; text = '{"openPositionCount":0}' }
        reconcileOrphanTrades = [pscustomobject]@{ ok = $true; text = "matched fixture" }
        gridStats = @([pscustomobject]@{ gridId = 7; result = [pscustomobject]@{ ok = $true; text = "Grid #7 stats fixture" } })
    }

    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[closed-grid-residual-source] fixture",
        "scope=READ_ONLY",
        "activePort=8085",
        "activeLog=logs/runs/app-20260706T035515Z-port8085.log",
        ("closed_grid_residual_rows_json=" + (ConvertTo-Json -InputObject $closedRows -Compress -Depth 8)),
        ("grid_residual_summary_json=" + (ConvertTo-Json -InputObject $summaryRows -Compress -Depth 8)),
        ("mcp_read_evidence_json=" + (ConvertTo-Json -InputObject $mcpEvidence -Compress -Depth 8)),
        "untrackedWarnCount=0",
        "closedGridResidualInfoCount=1",
        "[closed-grid-residual-source] read-only collection complete"
    )

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for closed-grid residual disposition packet test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLogPath $tempLogPath -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "Closed-grid residual disposition packet failed fixture reuse:`n$text"
    }
    foreach ($marker in @(
            "closed_grid_residual_rows=1",
            "closed_grid_residual_qty=0.00012479",
            "closed_grid_residual_estimated_notional_usdt=7.53731600",
            "closed_grid_summary_qty=0.00012479",
            "active_grid_summary_qty=0.00030000",
            "untracked_warn_count=0",
            "closed_grid_residual_info_count=1",
            "closed_grid_residual_decision=OPERATOR_CHOOSE_KEEP_WATCH_OR_REQUEST_SEPARATE_CLEANUP_PLAN",
            "requiredAuthorization=separate explicit operator approval before any cleanup",
            "exactAuthorizationTextForNextPlan=I explicitly authorize preparation of a separate closed-grid residual cleanup execution plan",
            "closed_grid_residual_cleanup_allowed=false",
            "exchange_sell_allowed=false",
            "db_write_allowed=false",
            "order_allowed=false",
            "oco_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "fund_or_earn_mutation_allowed=false",
            "scheduler_enablement_allowed=false",
            "telegram_send_allowed=false",
            "deploy_or_env_change_allowed=false",
            "external_backfill_or_import_allowed=false",
            "closed_grid_residual_disposition_status=READY_FOR_CLOSED_GRID_RESIDUAL_OPERATOR_DECISION_NOT_MUTATION",
            "notAuthorization=read-only closed-grid residual disposition packet only"
        )) {
        Assert-Contains -Name "closed-grid residual fixture output" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Closed-grid residual disposition packet unexpectedly invoked SSH during fixture mode:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) {
        Remove-Item -LiteralPath $tempLogPath -Force
    }
}

Write-Host "[closed-grid-residual-disposition-packet-test] OK"
