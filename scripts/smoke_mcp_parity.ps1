param(
    [string]$BaseUrl = "http://127.0.0.1:18084/api/trading",
    [string]$McpKey = "local-smoke-mcp"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-McpRequest {
    param(
        [string]$Url,
        [hashtable]$Body
    )

    $json = $Body | ConvertTo-Json -Depth 10 -Compress
    $response = Invoke-WebRequest `
        -Uri $Url `
        -Method Post `
        -UseBasicParsing `
        -TimeoutSec 30 `
        -ContentType "application/json" `
        -Headers @{ Authorization = "Bearer $McpKey" } `
        -Body $json

    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw "MCP parity request failed with HTTP $($response.StatusCode): $Url"
    }

    return $response.Content | ConvertFrom-Json
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
    "listStrategies",
    "runBacktest",
    "listGrids",
    "getOpenPositions",
    "getSystemHealth",
    "getMarketSentiment",
    "getCollectionFreshness",
    "getReport",
    "getTradingManagerDigest",
    "getMlLimits",
    "listRuntimeDecisionEvidence",
    "getScoreBuyFormingDayStatus",
    "listExecutionEvents",
    "getGuardianSnapshot",
    "listFundingArb",
    "getEarnBalance",
    "previewEnsembleScore",
    "listAiProviders",
    "listAiTasks"
)

$missing = @($requiredTools | Where-Object { $toolNames -notcontains $_ })
if ($missing.Count -gt 0) {
    throw "MCP parity smoke missing required standalone trading tool(s): $($missing -join ', ')"
}

$version = Invoke-McpRequest -Url $mcpUrl -Body @{
    jsonrpc = "2.0"
    id = "mcp-parity-registry-version"
    method = "tools/call"
    params = @{
        name = "getMcpRegistryVersion"
        arguments = @{}
    }
}

if (-not $version.result -or -not $version.result.content) {
    throw "MCP parity smoke: getMcpRegistryVersion returned no content"
}

Write-Host "[mcp-parity] OK $mcpUrl toolCount=$($toolNames.Count) required=$($requiredTools.Count)"
