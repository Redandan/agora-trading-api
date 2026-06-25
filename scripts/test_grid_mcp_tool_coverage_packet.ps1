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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_mcp_tool_coverage_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-mcp-tool-coverage] read-only packet",
        "GRID_MCP_TOOL_COVERAGE_PACKET",
        "tools/list",
        "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION",
        "BLOCKED_GRID_MCP_TOOL_COVERAGE_MISSING_TOOLS",
        "readOnlyReviewTools",
        "futureActionToolsPresentButNotInvoked",
        "boundaryContextTools",
        "missingRequiredTools",
        "getGridTrendAdjustmentReview",
        "listGrids",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "getEventRiskControlStatus",
        "gridStats",
        "getGridEfficiencyScore",
        "listGridDustSellRisks",
        "getGridRedesignPlan",
        "createGrid",
        "pauseGrid",
        "resumeGrid",
        "closeGrid",
        "enableGridAutoRebalance",
        "getOcoHealth",
        "checkOcoHealth",
        "getEarnBalance",
        "getBalance",
        "listSchedulerTasks",
        "mutation_tools_invoked=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid MCP tool coverage only",
        "RequireCoverageReady"
    )) {
    Assert-Contains -Name "grid MCP tool coverage script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "grid MCP tool coverage uses server-local MCP" -Text $scriptText -Pattern "127\.0\.0\.1.*?/api/mcp"
if ($scriptText -match 'tools/call|createGrid".*urllib|pauseGrid".*urllib|resumeGrid".*urllib|closeGrid".*urllib|enableGridAutoRebalance".*urllib') {
    throw "grid MCP tool coverage packet must not call tool mutation endpoints"
}

foreach ($marker in @(
        "prepare_grid_mcp_tool_coverage_packet_ssh.ps1",
        "GRID_MCP_TOOL_COVERAGE_PACKET",
        "grid_mcp_tool_coverage_status",
        "futureActionToolsPresentButNotInvoked",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid MCP tool coverage packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-mcp-coverage-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "unsafe app dir" -Pattern "AppDir contains unsupported characters" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api;bad" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-mcp-tool-coverage-packet-test] OK"
