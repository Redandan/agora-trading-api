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

    $response = Invoke-McpRequest -Url $Url -TimeoutSec $TimeoutSec -Body @{
        jsonrpc = "2.0"
        id = "mcp-parity-$Name"
        method = "tools/call"
        params = @{
            name = $Name
            arguments = $Arguments
        }
    }

    $errorProperty = $response.PSObject.Properties["error"]
    $resultProperty = $response.PSObject.Properties["result"]
    if ($errorProperty -and $errorProperty.Value) {
        $errorJson = $errorProperty.Value | ConvertTo-Json -Depth 10 -Compress
        throw "MCP parity smoke: $Name returned JSON-RPC error: $errorJson"
    }
    if (-not $resultProperty -or -not $resultProperty.Value) {
        $responseJson = $response | ConvertTo-Json -Depth 10 -Compress
        throw "MCP parity smoke: $Name returned no result: $responseJson"
    }
    $isErrorProperty = $resultProperty.Value.PSObject.Properties["isError"]
    if ($isErrorProperty -and $isErrorProperty.Value) {
        $resultJson = $resultProperty.Value | ConvertTo-Json -Depth 10 -Compress
        throw "MCP parity smoke: $Name returned isError=true: $resultJson"
    }

    return $response
}

function Get-McpResultText {
    param([object]$Response)

    $parts = @()
    foreach ($item in @($Response.result.content)) {
        $textProperty = $item.PSObject.Properties["text"]
        if (-not $textProperty) {
            continue
        }
        $text = [string]$textProperty.Value
        if ($text.Length -ge 2 -and $text.StartsWith('"') -and $text.EndsWith('"')) {
            try {
                $decoded = $text | ConvertFrom-Json -ErrorAction Stop
                if ($decoded -is [string]) {
                    $text = $decoded
                }
            } catch {
                # Keep the original text; the assertion below will print context.
            }
        }
        $parts += $text
    }

    return ($parts -join "`n")
}

function Assert-McpResultTextContains {
    param(
        [object]$Response,
        [string]$Pattern,
        [string]$Description
    )

    $text = Get-McpResultText -Response $Response
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
    "runScoreBuyTradingViewBtcBaseBacktest",
    "runScoreBuyTradingViewProfitOptimizationReport",
    "runTimeframeAwareStrategyValidation",
    "verifyScoreBuyTradingViewGoldenTruth",
    "backfillBinanceKlines",
    "backfillBinanceKlinesRange",
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
    "analyzeStrategy508HoldCounterfactual",
    "analyzeStrategy508TimeExitCandidate",
    "getStrategy508TimeExitReadiness",
    "getStrategyNetPnlAttribution",
    "getBtcBasePositionManagerStatus",
    "previewBtcBasePositionAdoption",
    "previewBtcBasePositionDisposition",
    "analyzeBtcDonchianShadowGoldenParity",
    "getBtcDonchianShadowReadiness",
    "getOkxNativeSpotGridStatus",
    "previewOkxNativeSpotGridMigration",
    "getOkxNativeSpotGridAcceptanceEvidence",
    "getOkxNativeSpotGridFunctionalSafetyEvidence",
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
Write-Host ("required_tools=" + (ConvertTo-Json -InputObject @($requiredTools) -Compress))
Write-Host ("missing_required_tools=" + (ConvertTo-Json -InputObject @($missing) -Compress))
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

$entryDedupGovernance = Invoke-McpTool -Url $mcpUrl -Name "getEntryDedupGovernanceDashboard" -Arguments @{
    symbol = "BTCUSDT"
    hours = 24
} -TimeoutSec 120
Assert-McpResultTextContains -Response $entryDedupGovernance -Pattern "getEntryDedupGovernanceDashboard" -Description "EntryDedup governance dashboard returns its tool marker"
Assert-McpResultTextContains -Response $entryDedupGovernance -Pattern "READ_ONLY" -Description "EntryDedup governance dashboard stays read-only"
Assert-McpResultTextContains -Response $entryDedupGovernance -Pattern "orderSent" -Description "EntryDedup governance dashboard reports no order send"
Assert-McpResultTextContains -Response $entryDedupGovernance -Pattern "ocoModified" -Description "EntryDedup governance dashboard reports no OCO modification"
Assert-McpResultTextContains -Response $entryDedupGovernance -Pattern "writesRuntimeEvidence" -Description "EntryDedup governance dashboard reports no runtime evidence writes"

$missedOpportunityRegression = Invoke-McpTool -Url $mcpUrl -Name "getMissedOpportunityRegressionReport" -Arguments @{
    symbol = "BTCUSDT"
    hours = 24
} -TimeoutSec 120
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "getMissedOpportunityRegressionReport" -Description "Missed-opportunity regression report returns its tool marker"
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "READ_ONLY" -Description "Missed-opportunity regression report stays read-only"
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "overallStatus" -Description "Missed-opportunity regression report returns an overall status"
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "orderSent" -Description "Missed-opportunity regression report reports no order send"
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "ocoModified" -Description "Missed-opportunity regression report reports no OCO modification"
Assert-McpResultTextContains -Response $missedOpportunityRegression -Pattern "writesRuntimeEvidence" -Description "Missed-opportunity regression report reports no runtime evidence writes"

$strategy508Counterfactual = Invoke-McpTool -Url $mcpUrl -Name "analyzeStrategy508HoldCounterfactual" -Arguments @{
    symbol = "BTCUSDT"
    hours = 24
    detailLimit = 5
} -TimeoutSec 120
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern "analyzeStrategy508HoldCounterfactual" -Description "Strategy 508 counterfactual returns its tool marker"
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern '"boundary"\s*:\s*"READ_ONLY"' -Description "Strategy 508 counterfactual stays read-only"
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern '"sampleStatus"\s*:\s*"(INSUFFICIENT_DATA|SHADOW_SAMPLE_READY_FOR_REVIEW_NOT_LIVE)"' -Description "Strategy 508 counterfactual returns an explicit sample status"
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern '"sampleGateMinFinalizedEvents"\s*:\s*30' -Description "Strategy 508 counterfactual preserves the 30-event gate"
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern '"liveRelaxationAllowed"\s*:\s*false' -Description "Strategy 508 counterfactual never authorizes live relaxation"
Assert-McpResultTextContains -Response $strategy508Counterfactual -Pattern '"hardSafetyEventsEligible"\s*:\s*0' -Description "Strategy 508 counterfactual excludes all hard-safety events"

$trailingPnlReplay = Invoke-McpTool -Url $mcpUrl -Name "analyzeTrailingStopPnlReplay" -Arguments @{
    symbol = "BTCUSDT"
    intervalCode = "1h"
    replayIntervalCode = "1m"
    days = 30
    limit = 10
} -TimeoutSec 120
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "boundary: READ_ONLY" -Description "Trailing-stop PnL replay stays read-only"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "backtestInterval: 1h" -Description "Trailing-stop PnL replay reports backtest interval"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "replayInterval: 1m" -Description "Trailing-stop PnL replay reports replay interval"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "replayIntervalNote=backtest interval selects normalized trades" -Description "Trailing-stop PnL replay explains split interval semantics"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "sampleStatus=NO_REPLAYABLE_TRADES|sampleStatus=REPLAYED|sampleStatus=NO_REPLAYED_ROWS" -Description "Trailing-stop PnL replay returns an explicit sample status"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "acceptanceTarget: total trailing PnL improvement >= 5%" -Description "Trailing-stop PnL replay keeps the issue #3 acceptance target"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals" -Description "Trailing-stop PnL replay keeps ambiguous same-bar exclusion"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "acceptanceBlocker=(NO_REPLAYABLE_TRADES|NO_REPLAYED_ROWS|ALL_REPLAYED_ROWS_AMBIGUOUS|NO_NON_AMBIGUOUS_ACCEPTANCE_ROWS|ZERO_OR_MISSING_ORIGINAL_PNL|CURRENT_PARAMETERS_NO_PNL_IMPROVEMENT|BELOW_ACCEPTANCE_TARGET|NONE)" -Description "Trailing-stop PnL replay explains acceptance blocker"
Assert-McpResultTextContains -Response $trailingPnlReplay -Pattern "acceptanceBlockerDetail=" -Description "Trailing-stop PnL replay explains acceptance blocker detail"

Write-Host "[mcp-parity] OK $mcpUrl toolCount=$($toolNames.Count) required=$($requiredTools.Count)"
