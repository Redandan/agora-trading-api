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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_resize_rebuild_operator_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
$verifyLocalText = Get-Content -Raw -LiteralPath $verifyLocalPath

foreach ($marker in @(
        "[grid-resize-rebuild-operator-packet] read-only packet",
        "GRID_RESIZE_REBUILD_OPERATOR_PACKET",
        "getGridTrendAdjustmentReview",
        "listGrids",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "getEventRiskControlStatus",
        "boundary=READ_ONLY",
        "mutationAllowed=false",
        "orderAllowed=false",
        "gridMutationAllowed=false",
        "schedulerChangeAllowed=false",
        "telegramSendAllowed=false",
        "decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW",
        "candidatePlan=",
        "READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_RESIZE_REBUILD_EVIDENCE_MISSING_NOT_MUTATION",
        "NO_GRID_RESIZE_REBUILD_RECOMMENDED_NOT_MUTATION",
        "BLOCKED_GRID_RESIZE_REBUILD_NO_ACTIVE_GRID_NOT_MUTATION",
        "PREPARE_SEPARATE_GRID_RESIZE_REBUILD_OPERATOR_DECISION",
        "resizeRebuildReviewAllowed",
        "remainingExecutionBlockersBeforeMutation",
        "TOTAL_CANDIDATE_CAPITAL_ABOVE_REVIEW_CAP",
        "EVENT_RISK_NOT_R0_BEFORE_GRID_RESIZE_REBUILD",
        "ACTIVE_GRID_HOLDING_EXIT_PLAN_REQUIRED_BEFORE_CLOSE_RECREATE",
        "GRID_LEVEL_FAILURE_RECONCILIATION_REQUIRED",
        "REPLAYABLE_PREVIEW_CANDIDATE_PLAN_MISSING",
        "grid_resize_rebuild_operator_status",
        "grid_resize_rebuild_operator_review_ready",
        "grid_resize_rebuild_candidate_grid_ids",
        "grid_resize_rebuild_candidate_capital_usdt",
        "grid_resize_rebuild_operator_packet",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "close_grid_allowed=false",
        "create_grid_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid resize/rebuild operator packet only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid resize rebuild operator packet script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "MCP_OPS_KEY",
        "TRADING_MCP_OPS_KEY",
        "TRADING_OPS_MCP_KEY",
        "json.loads(stripped)",
        'http://127.0.0.1:${PORT}/api/mcp'
    )) {
    Assert-Contains -Name "grid resize rebuild SSH safety marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match 'call_tool\("(createGrid|pauseGrid|resumeGrid|closeGrid|enableGridAutoRebalance|placeOrder|modifyOco|sendTelegram)') {
    throw "grid resize/rebuild operator packet must not call mutation MCP tools"
}

if ($scriptText -match "https://agoratradingapi\.purrtechllc\.com/api/mcp|https://agoramarketapi\.purrtechllc\.com/api/trading/mcp|/api/trading/mcp") {
    throw "grid resize/rebuild operator packet must not call public or legacy Trading MCP routes"
}

foreach ($marker in @(
        "prepare_grid_resize_rebuild_operator_packet_ssh.ps1",
        "GRID_RESIZE_REBUILD_OPERATOR_PACKET",
        "grid_resize_rebuild_operator_status",
        "READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION",
        "grid_resize_rebuild_candidate_grid_ids",
        "grid_mutation_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid resize rebuild operator packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local includes grid resize rebuild operator packet test" -Text $verifyLocalText -Pattern "test_grid_resize_rebuild_operator_packet\.ps1"

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-resize-rebuild-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad lookback" -Pattern "LookbackHours must be between 24 and 336" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -LookbackHours 1
    }
    Assert-FailsWith -Name "bad capital cap" -Pattern "TotalReviewCapitalCapUsdt must be between 5 and 10000" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -TotalReviewCapitalCapUsdt 1
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-resize-rebuild-operator-packet-test] OK"
