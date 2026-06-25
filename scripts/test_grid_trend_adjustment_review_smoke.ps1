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

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_grid_trend_adjustment_review_ssh.ps1"
$javaPath = Join-Path $repoRoot "src/main/java/com/agora/trading/smoke/McpSmokeCli.java"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$javaText = Get-Content -Raw -LiteralPath $javaPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        'callTool("getGridTrendAdjustmentReview"',
        "Grid Trend Adjustment Review",
        "boundary=READ_ONLY",
        "mutationAllowed=false",
        "orderAllowed=false",
        "gridMutationAllowed=false",
        "schedulerChangeAllowed=false",
        "telegramSendAllowed=false",
        "operator review only",
        "recommendation=",
        "grid_trend_adjustment_review_packet",
        "grid_trend_adjustment_review_status",
        "grid_trend_adjustment_recommendation",
        "requiredEvidence",
        "notAuthorization=true",
        "separate explicit approval",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "grid trend Java smoke markers" -Text $javaText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        'url = f"http://127.0.0.1:{os.environ[''PORT'']}/api/mcp"',
        'MCP_URL="http://127.0.0.1:${PORT}/api/mcp"',
        "TRADING_MCP_KEY",
        "dependency:build-classpath",
        "SMOKE_CLASSPATH",
        'java -cp "$SMOKE_CLASSPATH" com.agora.trading.smoke.McpSmokeCli',
        "--mcp-key-env MCP_KEY",
        "Assert-RemotePathSafe",
        "Assert-SshHostSafe",
        "Assert-McpSmokeTokenSafe"
    )) {
    Assert-Contains -Name "grid trend smoke safety markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed",
        "--mcp-key or --mcp-key-env",
        "tools/call",
        "JSON-RPC error"
    )) {
    Assert-Contains -Name "grid trend Java safety markers" -Text $javaText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "grid trend smoke avoids public MCP" -Text $scriptText -Pattern "127\.0\.0\.1"
if ($scriptText -match "https://agoratradingapi\.purrtechllc\.com/api/mcp|https://agoramarketapi\.purrtechllc\.com/api/trading/mcp|/api/trading/mcp") {
    throw "grid trend smoke must not call public or legacy Trading MCP routes"
}

foreach ($pathText in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "grid trend docs mention smoke" -Text $pathText -Pattern "smoke_grid_trend_adjustment_review_ssh\.ps1"
    Assert-Contains -Name "grid trend docs mention read-only" -Text $pathText -Pattern "read-only"
}

Write-Host "[grid-trend-adjustment-review-smoke-test] OK"
