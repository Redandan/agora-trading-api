Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsWith {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [string]$Pattern
    )

    $failed = $false
    try {
        & $Action
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Name failed with unexpected message: $($_.Exception.Message)"
        }
    }

    if (-not $failed) {
        throw "$Name did not fail"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_readiness_packet_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "[grid-open-readiness] read-only packet",
        "GRID_OPEN_READINESS_PACKET",
        "getGridTrendAdjustmentReview",
        "getEventRiskControlStatus",
        "listGrids",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "boundary=READ_ONLY",
        "mutationAllowed=false",
        "orderAllowed=false",
        "gridMutationAllowed=false",
        "schedulerChangeAllowed=false",
        "telegramSendAllowed=false",
        "grid_open_readiness_packet",
        "grid_open_readiness_status",
        "grid_open_readiness_blockers",
        "grid_open_readiness_required_evidence",
        "BLOCKED_GRID_OPEN_READINESS_NOT_MUTATION",
        "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION",
        "NO_REPLAYABLE_GRID_CANDIDATE_PLAN",
        "GRID_UNFAVORABLE_TREND_REGIME_",
        "EVENT_RISK_NOT_R0",
        "HISTORICAL_GRID_SELL_FAILED_RECONCILIATION_REQUIRED",
        "TRADING_GRID_ENABLED",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
        "GRID_RECOVERY_ENABLED",
        "TRADING_OKX_API_KEY",
        "TRADING_OKX_SECRET_KEY",
        "TRADING_OKX_PASSPHRASE",
        "TRADING_OKX_ENABLED_FALSE",
        "OKX_EARN_TOPUP_ENABLED_MUST_REMAIN_FALSE_FOR_GRID_OPEN_REVIEW",
        "notAuthorization=read-only grid open readiness only",
        "does not create/pause/resume/close/rebalance grid",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReady"
    )) {
    Assert-Contains -Name "grid open readiness script" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "grid open readiness uses server-local MCP" -Text $scriptText -Pattern "127\.0\.0\.1.*?/api/mcp"
if ($scriptText -match "https://agoratradingapi\.purrtechllc\.com/api/mcp|https://agoramarketapi\.purrtechllc\.com/api/trading/mcp|/api/trading/mcp") {
    throw "grid open readiness packet must not call public or legacy Trading MCP routes"
}

foreach ($pathText in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "grid open docs mention script" -Text $pathText -Pattern "prepare_grid_open_readiness_packet_ssh\.ps1"
    Assert-Contains -Name "grid open docs mention packet" -Text $pathText -Pattern "GRID_OPEN_READINESS_PACKET"
    Assert-Contains -Name "grid open docs mention status" -Text $pathText -Pattern "grid_open_readiness_status"
    Assert-Contains -Name "grid open docs mention read-only" -Text $pathText -Pattern "read-only"
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-open-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "unsafe symbol" -Pattern "Symbol contains unsupported characters" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -Symbol "BTCUSDT';echo bad"
    }
    Assert-FailsWith -Name "bad lookback" -Pattern "LookbackHours must be between 24 and 720" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -LookbackHours 1
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-open-readiness-packet-test] OK"
