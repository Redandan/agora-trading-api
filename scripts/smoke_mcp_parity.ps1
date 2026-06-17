param(
    [string]$BaseUrl = "http://127.0.0.1:18084/api",
    [string]$McpKey = "local-smoke-mcp"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-McpRequest {
    param(
        [string]$Url,
        [hashtable]$Body,
        [int]$TimeoutSec = 30
    )

    $json = $Body | ConvertTo-Json -Depth 10 -Compress
    $response = Invoke-WebRequest `
        -Uri $Url `
        -Method Post `
        -UseBasicParsing `
        -TimeoutSec $TimeoutSec `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer $McpKey" } `
        -Body $json

    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw "MCP parity request failed with HTTP $($response.StatusCode): $Url"
    }

    return $response.Content | ConvertFrom-Json
}

function Invoke-McpTool {
    param(
        [string]$Url,
        [string]$Name,
        [hashtable]$Arguments = @{},
        [int]$TimeoutSec = 30
    )

    return Invoke-McpRequest -Url $Url -TimeoutSec $TimeoutSec -Body @{
        jsonrpc = "2.0"
        id = "mcp-parity-$Name"
        method = "tools/call"
        params = @{
            name = $Name
            arguments = $Arguments
        }
    }
}

function Assert-McpResultTextContains {
    param(
        [object]$Response,
        [string]$Pattern,
        [string]$Description
    )

    $text = ($Response.result.content | ConvertTo-Json -Depth 10 -Compress)
    if ($text -notmatch $Pattern) {
        throw "MCP parity smoke response missing expected evidence: $Description. pattern=$Pattern content=$text"
    }
}

$mcpUrl = "$($BaseUrl.TrimEnd('/'))/mcp"

$list = Invoke-McpRequest -Url $mcpUrl -Body @{
    jsonrpc = "2.0"
    id = "mcp-parity-tools-list"
    method = "tools/list"
    params = @{}
}

if (-not $list.result -or -not $list.result.tools) {
    throw "MCP parity smoke: tools/list returned no tools from $mcpUrl"
}

$toolNames = @($list.result.tools | ForEach-Object { $_.name } | Sort-Object -Unique)
$requiredTools = @(
    "getMcpRegistryVersion",
    "getMcpAuthProbe",
    "listSchedulerTasks",
    "listStrategies",
    "runBacktest",
    "listGrids",
    "getOpenPositions",
    "getSystemHealth",
    "getMarketSentiment",
    "getCollectionFreshness",
    "diagnoseDataFreshnessGuardBlocks",
    "getReport",
    "getTradingManagerDigest",
    "getMlLimits",
    "listRuntimeDecisionEvidence",
    "getScoreBuyFormingDayStatus",
    "getEventRiskControlStatus",
    "analyzeSpotAntiWickPolicyCoverage",
    "verifyStrategyExecution",
    "analyzeBlockedSignalOutcomes",
    "getSignalCorrectnessDashboard",
    "getSignalAccuracyReport",
    "getEntryDedupGovernanceDashboard",
    "getMissedOpportunityRegressionReport",
    "getGovernanceDriftDashboard",
    "findGovernanceRelaxationCandidates",
    "findGovernanceTighteningCandidates",
    "listExecutionEvents",
    "getGuardianSnapshot",
    "listFundingArb",
    "getEarnBalance",
    "previewEnsembleScore",
    "analyzeTrailingStopPnlReplay",
    "listAiProviders",
    "listAiTasks"
)

$missing = @($requiredTools | Where-Object { $toolNames -notcontains $_ })
if ($missing.Count -gt 0) {
    throw "MCP parity smoke missing required standalone trading tool(s): $($missing -join ', ')"
}

$version = Invoke-McpTool -Url $mcpUrl -Name "getMcpRegistryVersion"

if (-not $version.result -or -not $version.result.content) {
    throw "MCP parity smoke: getMcpRegistryVersion returned no content"
}

$dataFreshnessRca = Invoke-McpTool -Url $mcpUrl -Name "diagnoseDataFreshnessGuardBlocks" -Arguments @{
    days = 1
    symbol = "BTCUSDT"
    limit = 5
}

Assert-McpResultTextContains -Response $dataFreshnessRca -Pattern "boundary: READ_ONLY" -Description "DataFreshnessGuard RCA stays read-only"
Assert-McpResultTextContains -Response $dataFreshnessRca -Pattern "acceptance: PASS_NO_CURRENT_SAMPLE|acceptance: PASS_RCA_CLASSIFIED" -Description "DataFreshnessGuard RCA returns an explicit acceptance marker"

$eventRiskStatus = Invoke-McpTool -Url $mcpUrl -Name "getEventRiskControlStatus" -Arguments @{
    symbol = "BTCUSDT"
}
Assert-McpResultTextContains -Response $eventRiskStatus -Pattern "boundary=READ_ONLY" -Description "Event-risk control status stays read-only"
Assert-McpResultTextContains -Response $eventRiskStatus -Pattern "operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION" -Description "Event-risk control status keeps config-only operator controls"

$antiWickCoverage = Invoke-McpTool -Url $mcpUrl -Name "analyzeSpotAntiWickPolicyCoverage" -Arguments @{
    symbol = "BTCUSDT"
}
Assert-McpResultTextContains -Response $antiWickCoverage -Pattern "boundary: READ_ONLY" -Description "Anti-wick policy coverage stays read-only"
Assert-McpResultTextContains -Response $antiWickCoverage -Pattern "policy: live BTC spot LONG entries default to ULTRA_LOW_DISASTER SL" -Description "Anti-wick policy coverage keeps disaster-SL policy marker"
Assert-McpResultTextContains -Response $antiWickCoverage -Pattern "Summary:" -Description "Anti-wick policy coverage returns an operator summary"

$trailingPnlReplay = Invoke-McpTool -Url $mcpUrl -Name "analyzeTrailingStopPnlReplay" -Arguments @{
    symbol = "BTCUSDT"
    intervalCode = "1h"
    days = 30
    limit = 10
} -TimeoutSec 120
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "boundary: READ_ONLY" -Description "Trailing-stop PnL replay stays read-only"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "sampleStatus=NO_REPLAYABLE_TRADES|sampleStatus=REPLAYED|sampleStatus=NO_REPLAYED_ROWS" -Description "Trailing-stop PnL replay returns an explicit sample status"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals" -Description "Trailing-stop PnL replay keeps ambiguous same-bar exclusion"

Write-Host "[mcp-parity] OK $mcpUrl toolCount=$($toolNames.Count) required=$($requiredTools.Count)"
