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
$scriptText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "scripts/smoke_tiny_live_post_trade_ssh.ps1")
$readmeText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "README.md")
$runbookText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "docs/deploy-runbook.md")

foreach ($pattern in @(
        'read-only server-local MCP smoke',
        'getTinyLiveAutoExecutionTriggerStatus',
        'listTinyLiveExecutions',
        'getAutonomousExecutionAttribution',
        'getOcoHealth',
        'listExecutionEvents',
        'getTgNotificationHistory',
        'getAutonomousReadinessDashboard',
        'hardScope=BTCUSDT/574/LONG/5USDT',
        'orderSent=false',
        'PENDING_NO_NEW_TINY_LIVE_EXECUTION',
        'latestWithinWindow',
        'createdAt=',
        'POST_TRADE_EVIDENCE_OK',
        'POST_TRADE_REVIEW_FAILED',
        'LATEST_TINY_LIVE_OCO_NOT_ATTACHED',
        'OCO_PROTECTION_EFFECTIVENESS_NOT_PASS',
        'TG_TINY_LIVE_EVIDENCE_MISSING',
        'ACTIVE_OCO_MISSING_EXECUTION_EVENT',
        'RequireExecution',
        'Assert-RemotePathSafe',
        'Assert-SshHostSafe',
        'Assert-McpSmokeTokenSafe'
    )) {
    Assert-Contains -Name "post-trade smoke" -Text $scriptText -Pattern ([regex]::Escape($pattern))
}

Assert-Contains -Name "README post-trade smoke" -Text $readmeText -Pattern 'smoke_tiny_live_post_trade_ssh\.ps1'
Assert-Contains -Name "runbook post-trade smoke" -Text $runbookText -Pattern 'smoke_tiny_live_post_trade_ssh\.ps1'

Write-Host "[tiny-live-post-trade-smoke-test] OK"
