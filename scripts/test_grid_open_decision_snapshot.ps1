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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_decision_snapshot_ssh.ps1"
$watchPath = Join-Path $PSScriptRoot "prepare_grid_trend_clearance_watch_packet_ssh.ps1"
$coveragePath = Join-Path $PSScriptRoot "prepare_grid_mcp_tool_coverage_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$watchText = Get-Content -Raw -LiteralPath $watchPath
$coverageText = Get-Content -Raw -LiteralPath $coveragePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-open-decision-snapshot] read-only packet",
        "GRID_OPEN_DECISION_SNAPSHOT",
        "prepare_grid_trend_clearance_watch_packet_ssh.ps1",
        "prepare_grid_mcp_tool_coverage_packet_ssh.ps1",
        "grid_trend_clearance_watch_packet=",
        "grid_mcp_tool_coverage_packet=",
        "READY_FOR_SEPARATE_GRID_OPEN_AUTHORIZATION_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_DECISION_SNAPSHOT_NOT_MUTATION",
        "WAIT_FOR_TREND_CLEARANCE_OR_SEPARATE_TREND_OVERRIDE",
        "FIX_GRID_MCP_TOOL_COVERAGE_BEFORE_OPERATOR_REVIEW",
        "RESOLVE_REMAINING_GRID_OPEN_BLOCKERS_BEFORE_AUTHORIZATION",
        "PREPARE_SEPARATE_ENV_AND_CREATEGRID_AUTHORIZATION",
        "quantitativeReadiness",
        "trendDistanceToSidewaysPct",
        "effectiveReviewCapitalCapUsdt",
        "mcpToolCount",
        "gateStatuses",
        "okxGridEnvPreflight",
        "remainingBlockers",
        "missingEvidence",
        "requiredOperatorAuthorization",
        "requiredBeforeAnyGridOpen",
        "gridOpenReadyForAuthorization",
        "gridOpenAllowed = `$false",
        "grid_open_ready_for_authorization",
        "grid_open_allowed=false",
        "production_env_change_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid open decision snapshot only",
        "RequireAuthorizationReady"
    )) {
    Assert-Contains -Name "grid open decision snapshot script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GRID_TREND_CLEARANCE_WATCH_PACKET",
        "grid_trend_clearance_watch_packet",
        "okxGridEnvPreflightStatus"
    )) {
    Assert-Contains -Name "trend watch packet supports decision snapshot" -Text $watchText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GRID_MCP_TOOL_COVERAGE_PACKET",
        "grid_mcp_tool_coverage_packet",
        "missingRequiredTools"
    )) {
    Assert-Contains -Name "MCP coverage packet supports decision snapshot" -Text $coverageText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid open decision snapshot must not invoke grid or MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_open_decision_snapshot_ssh.ps1",
        "GRID_OPEN_DECISION_SNAPSHOT",
        "grid_open_decision_snapshot_status",
        "grid_open_ready_for_authorization",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid open decision snapshot" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-open-decision-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "unsafe symbol" -Pattern "Symbol contains unsupported characters" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -Symbol "BTCUSDT';echo bad"
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-open-decision-snapshot-test] OK"
