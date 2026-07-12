Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$smokePath = Join-Path $PSScriptRoot "smoke_strategy508_hold_counterfactual_ssh.ps1"
$servicePath = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508HoldCounterfactualService.java"
$mcpPath = Join-Path $repoRoot "src/main/java/com/agora/mcp/RuntimeEvidenceMcpTools.java"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"

$smoke = Get-Content -Raw -LiteralPath $smokePath
$service = Get-Content -Raw -LiteralPath $servicePath
$mcp = Get-Content -Raw -LiteralPath $mcpPath
$runbook = Get-Content -Raw -LiteralPath $runbookPath

foreach ($marker in @(
        "analyzeStrategy508HoldCounterfactual",
        "scope=READ_ONLY",
        "sampleGateMinFinalizedEvents",
        "liveRelaxationAllowed",
        "hardSafetyEventsEligible",
        "eventChainRowsCollapsed",
        "counterfactual_report=",
        "notAuthorization=",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "strategy 508 smoke" -Text $smoke -Pattern ([regex]::Escape($marker))
}
if ($smoke -match "s/\^'''//|s/'''\$//") {
    throw "strategy 508 smoke contains shell-breaking single-quote sed expressions"
}

foreach ($marker in @(
        'MIN_FINALIZED_EVENTS = 30',
        'NOTIONAL_USDT = new BigDecimal("10.00")',
        'FEE_RATE = new BigDecimal("0.001")',
        'TAKE_PROFIT_PCT = new BigDecimal("0.06")',
        'STOP_LOSS_PCT = new BigDecimal("0.12")',
        'FAIL_CLOSED_ANY_HARD_BLOCK_EXCLUDES_WHOLE_EVENT',
        'AMBIGUOUS_SAME_MINUTE',
        'INSUFFICIENT_DATA',
        'liveRelaxationAllowed'
    )) {
    Assert-Contains -Name "strategy 508 service" -Text $service -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "strategy 508 MCP" -Text $mcp -Pattern "public String analyzeStrategy508HoldCounterfactual"
Assert-Contains -Name "strategy 508 MCP read-only" -Text $mcp -Pattern "No order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence behavior is changed"
Assert-Contains -Name "strategy 508 runbook" -Text $runbook -Pattern "smoke_strategy508_hold_counterfactual_ssh\.ps1"
Assert-Contains -Name "strategy 508 runbook sample gate" -Text $runbook -Pattern "30\s+unique finalized events"

Write-Host "[strategy508-hold-counterfactual-smoke-test] OK"
