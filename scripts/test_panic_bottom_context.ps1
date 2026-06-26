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

function Assert-NotContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -match $Pattern) {
        throw "$Name contains forbidden pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$servicePath = Join-Path $repoRoot "src/main/java/com/agora/service/trading/PanicBottomContextPreviewService.java"
$mcpPath = Join-Path $repoRoot "src/main/java/com/agora/mcp/ScoreBuyMcpTools.java"
$serviceTestPath = Join-Path $repoRoot "src/test/java/com/agora/service/trading/PanicBottomContextPreviewServiceTest.java"
$mcpTestPath = Join-Path $repoRoot "src/test/java/com/agora/mcp/ScoreBuyMcpToolsTest.java"

foreach ($path in @($servicePath, $mcpPath, $serviceTestPath, $mcpTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "missing panic-bottom context file: $path"
    }
}

$service = Get-Content -Raw -LiteralPath $servicePath
$mcp = Get-Content -Raw -LiteralPath $mcpPath
$serviceTest = Get-Content -Raw -LiteralPath $serviceTestPath
$mcpTest = Get-Content -Raw -LiteralPath $mcpTestPath
$combined = $service + "`n" + $mcp + "`n" + $serviceTest + "`n" + $mcpTest

foreach ($marker in @(
        "previewPanicBottomContext",
        "boundary",
        "READ_ONLY",
        "orderAllowed",
        "gridMutationAllowed",
        "downWaveCount",
        "largestDrawdownPct",
        "currentLegPct",
        "retestLowStatus",
        "fear_greed",
        "classification",
        "priceVs200wmaPct",
        "panicBottomScore",
        "phase",
        "suggestedAction",
        "SCOUT_PRE_POSITION",
        "WATCH",
        "CONFIRMED_DEPLOY_REVIEW",
        "TRENDING_BEARISH",
        "OCO_ABNORMAL_OR_1H_4H_TRENDING_BEARISH",
        "panicBottomContext=",
        "never places orders",
        "gridMutationAllowed"
    )) {
    Assert-Contains -Name "panic-bottom marker" -Text $combined -Pattern ([regex]::Escape($marker))
}

foreach ($pattern in @(
        'orderAllowed", true',
        'gridMutationAllowed", true',
        'ocoMutationAllowed", true',
        'telegramSendAllowed", true',
        'writesRuntimeEvidence", true',
        'executeScoreBuy',
        'createGrid',
        'resumeGrid',
        'pauseGrid',
        'modifyOco\(',
        'forceClosePosition',
        'sendEventScanNotification'
    )) {
    Assert-NotContains -Name "panic-bottom no mutation" -Text $service -Pattern $pattern
}

Assert-Contains -Name "MCP tool has auth" -Text $mcp -Pattern "@McpAuth\(McpAuthLevel\.OPS\)"
Assert-Contains -Name "MCP tool has category" -Text $mcp -Pattern "@McpCategory"
Assert-Contains -Name "conviction attaches panic context" -Text $mcp -Pattern "panicBottomContext="
Assert-Contains -Name "unit test enforces scout downgrade" -Text $serviceTest -Pattern "highPanicScoreWithOcoOrBearishTrendOnlyAllowsScout"
Assert-Contains -Name "unit test enforces readonly" -Text $serviceTest -Pattern "gridMutationAllowed"
Assert-Contains -Name "MCP test enforces integration" -Text $mcpTest -Pattern "previewScoreBuyConvictionDisplaysPanicBottomContextWithoutExecution"

Write-Host "[panic-bottom-context-test] OK"
