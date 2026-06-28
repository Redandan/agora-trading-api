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
$scriptPath = Join-Path $PSScriptRoot "smoke_grid_post_open_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

if ($scriptText -match '[^\x00-\x7F]') {
    throw "grid post-open smoke script must keep remote Python source ASCII-only for Windows PowerShell SSH piping"
}

foreach ($marker in @(
        "[grid-post-open-smoke] read-only verification",
        "scope=READ_ONLY",
        "OPS MCP key",
        "gridStats",
        "listGrids",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "listSchedulerTasks",
        "active_state_marker",
        "active_icon",
        "\u72c0\u614b: ACTIVE",
        "\u2705",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
        "GRID_RECOVERY_ENABLED",
        "OKX_EARN_TOPUP_ENABLED",
        "GridManagerScheduler.checkAllGrids()",
        "grid_post_open_smoke_packet",
        "grid_post_open_grid_stats_excerpt",
        "grid_post_open_alignment_excerpt",
        "grid_post_open_exposure_excerpt",
        "check_server_runtime_log.sh",
        "ALLOW_HIGH_RISK_LOG=0",
        "sed '1s/^\xEF\xBB\xBF//'",
        "bash -s",
        '$sshExitCode',
        "grid post-open smoke failed with exit code",
        "notAuthorization=read-only grid post-open smoke only",
        "does not create, pause, resume, close, rebalance"
    )) {
    Assert-Contains -Name "grid post-open smoke script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        '"createGrid"',
        '"pauseGrid"',
        '"resumeGrid"',
        '"closeGrid"',
        '"enableGridAutoRebalance"',
        '"modifyOco"',
        '"subscribeEarn"',
        '"redeemEarn"',
        '"forceClosePosition"'
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "grid post-open smoke must not call mutation tool $forbidden"
    }
}

foreach ($marker in @(
        "smoke_grid_post_open_ssh.ps1",
        "grid_post_open_smoke_packet",
        "Grid #10",
        "read-only",
        "runtime log"
    )) {
    Assert-Contains -Name "docs mention grid post-open smoke" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-post-open-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad grid id" -Pattern "GridId must be positive" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridId 0
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-post-open-smoke-test] OK"
