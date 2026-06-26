Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$servicePath = Join-Path $repoRoot "src/main/java/com/agora/service/trading/ScoreBuyMlGateDiagnosticService.java"
$mcpPath = Join-Path $repoRoot "src/main/java/com/agora/mcp/ScoreBuyMcpTools.java"
$testPath = Join-Path $repoRoot "src/test/java/com/agora/mcp/ScoreBuyMcpToolsTest.java"

$service = Get-Content -Raw -LiteralPath $servicePath
$mcp = Get-Content -Raw -LiteralPath $mcpPath
$test = Get-Content -Raw -LiteralPath $testPath

foreach ($marker in @(
        "diagnoseScoreBuyMlGate",
        "scorebuy_ml_gate_status",
        "promotedModelVersion",
        "expectedFeatures",
        "providedFeatures",
        "pWin",
        "buyThreshold",
        "decision",
        "missingRequirements",
        "notAuthorization",
        "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.",
        "orderSent",
        "ocoModified",
        "telegramSent",
        "writesRuntimeEvidence",
        "BLOCKED_SCHEMA_MISMATCH",
        "schemaMissingFromProvided",
        "failureOwner",
        "MODEL_RETRAIN_OR_FEATURE_BUILDER_ALIGNMENT",
        "FEATURE_BUILDER_ALIGNMENT"
    )) {
    Assert-Contains -Name "ScoreBuy ML diagnostic service marker" -Text $service -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "diagnoseScoreBuyMlGate",
        "ScoreBuyMlGateDiagnosticService",
        "Category.MODEL_OPS",
        "Read-only SCORE_BUY ML gate diagnostic"
    )) {
    Assert-Contains -Name "ScoreBuy ML diagnostic MCP marker" -Text $mcp -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "diagnoseScoreBuyMlGateKeepsOpsReadOnlyMetadata",
        "diagnoseScoreBuyMlGateDelegatesWithoutExecutionSideEffects",
        "Category.MODEL_OPS",
        '"orderSent":false',
        '"ocoModified":false',
        '"telegramSent":false',
        '"writesRuntimeEvidence":false'
    )) {
    Assert-Contains -Name "ScoreBuy ML diagnostic test marker" -Text $test -Pattern ([regex]::Escape($marker))
}

Write-Host "[scorebuy-ml-gate-diagnostic-test] OK"
