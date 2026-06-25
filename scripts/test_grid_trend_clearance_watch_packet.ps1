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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_trend_clearance_watch_packet_ssh.ps1"
$operatorPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$operatorText = Get-Content -Raw -LiteralPath $operatorPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-trend-clearance-watch] read-only packet",
        "GRID_TREND_CLEARANCE_WATCH_PACKET",
        "prepare_grid_open_operator_packet_ssh.ps1",
        "grid_open_operator_packet=",
        "grid_trend_clearance_watch_packet",
        "grid_trend_clearance_watch_status",
        "grid_trend_clearance_watch_decision",
        "WATCH_TREND_CLEARANCE_PENDING_NOT_MUTATION",
        "READY_TREND_GATE_CLEAR_NOT_OPEN_APPROVAL",
        "WAIT_FOR_SIDEWAYS_OR_SEPARATE_TREND_OVERRIDE",
        "TREND_GATE_CLEAR_REFRESH_OPERATOR_PACKET_BEFORE_ENV_OR_CREATEGRID",
        "SidewaysTrendPctThreshold",
        "trendDistanceToSidewaysPct",
        "[math]::Max(0.0",
        "directionToClear",
        "recommendedOverrideCapitalCapUsdt",
        "effectiveReviewCapitalCapUsdt",
        "clearanceCriteria",
        "abortCriteria",
        "nextVerification",
        "trend_gate_clear_allowed",
        "production_env_change_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only trend clearance watch only",
        "RequireTrendClear"
    )) {
    Assert-Contains -Name "grid trend clearance watch script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "trendOverrideRiskEnvelope",
        "combinedOverrideRiskEnvelope",
        "okxGridEnvPreflightEnvelope"
    )) {
    Assert-Contains -Name "operator packet supports trend clearance watch" -Text $operatorText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_grid_trend_clearance_watch_packet_ssh.ps1",
        "GRID_TREND_CLEARANCE_WATCH_PACKET",
        "grid_trend_clearance_watch_status",
        "trendDistanceToSidewaysPct",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid trend clearance watch packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-trend-watch-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe symbol" -Pattern "Symbol contains unsupported characters" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -Symbol "BTCUSDT';echo bad"
    }
    Assert-FailsWith -Name "bad threshold low" -Pattern "SidewaysTrendPctThreshold must be between 0.1 and 3.0" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -SidewaysTrendPctThreshold 0.01
    }
    Assert-FailsWith -Name "bad threshold high" -Pattern "SidewaysTrendPctThreshold must be between 0.1 and 3.0" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -SidewaysTrendPctThreshold 5
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-trend-clearance-watch-packet-test] OK"
