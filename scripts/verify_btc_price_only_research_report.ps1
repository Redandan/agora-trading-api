[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [string]$ResearchPolicyPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Sha256Text {
    param([Parameter(Mandatory = $true)][string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Assert-Near {
    param(
        [Parameter(Mandatory = $true)][double]$Actual,
        [Parameter(Mandatory = $true)][double]$Expected,
        [Parameter(Mandatory = $true)][double]$Tolerance,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ([Math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "$Label mismatch: actual=$Actual expected=$Expected"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ResearchPolicyPath)) {
    $ResearchPolicyPath = Join-Path $PSScriptRoot "btc_price_only_research_policy.json"
}
$reportFull = [System.IO.Path]::GetFullPath($ReportPath)
if (-not (Test-Path -LiteralPath $reportFull -PathType Leaf)) { throw "Report not found: $reportFull" }
if (-not (Test-Path -LiteralPath $ResearchPolicyPath -PathType Leaf)) { throw "Policy not found" }
$report = Get-Content -Raw -LiteralPath $reportFull | ConvertFrom-Json
$policy = Get-Content -Raw -LiteralPath $ResearchPolicyPath | ConvertFrom-Json
if ([string]$report.schemaVersion -ne "BTC_PRICE_ONLY_RESEARCH_REPORT_V1") {
    throw "Unsupported report schema: $($report.schemaVersion)"
}
if ([string]$report.boundary -ne "LOCAL_READ_ONLY") { throw "Report boundary is not LOCAL_READ_ONLY" }
$policyHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResearchPolicyPath).Hash.ToLowerInvariant()
if ($policyHash -ne [string]$report.researchPolicySha256) { throw "Report policy hash mismatch" }
$analyzerPath = Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1"
$analyzerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $analyzerPath).Hash.ToLowerInvariant()
if ($analyzerHash -ne [string]$report.analyzerSha256) { throw "Report analyzer hash mismatch" }
if (-not [bool]$report.dataset.manifestContractValidated -or
        -not [bool]$report.dataset.rawCanonicalReconstructionMatched -or
        -not [bool]$report.dataset.analyzerVersionMatchesDatasetBuild) {
    throw "Report dataset provenance checks are incomplete"
}
foreach ($safetyProperty in $report.safety.PSObject.Properties) {
    if ([bool]$safetyProperty.Value) { throw "Unsafe report marker: $($safetyProperty.Name)" }
}
$results = @($report.results)
if ($results.Count -ne @($policy.candidates).Count) { throw "Frozen candidate count mismatch" }
$scenarioCount = 0
foreach ($candidate in $results) {
    foreach ($scenarioName in @("normal", "stress")) {
        $scenarioCount++
        $scenario = $candidate.$scenarioName
        if ([string]$scenario.status -ne "SIMULATED") { continue }
        $cost = $policy.execution.$scenarioName
        $feeRate = [double]$cost.feeRatePerSide
        $slippage = [double]$cost.adverseSlippageRatePerSide
        $signals = @($scenario.signalLedger)
        $orders = @($scenario.orderLedger)
        $trades = @($scenario.tradeLedger)
        $signalHash = Get-Sha256Text (ConvertTo-Json -InputObject $signals -Depth 10 -Compress)
        $orderHash = Get-Sha256Text (ConvertTo-Json -InputObject $orders -Depth 10 -Compress)
        $tradeHash = Get-Sha256Text (ConvertTo-Json -InputObject $trades -Depth 10 -Compress)
        if ($signalHash -ne [string]$scenario.signalLedgerSha256) { throw "Signal ledger hash mismatch" }
        if ($orderHash -ne [string]$scenario.orderLedgerSha256) { throw "Order ledger hash mismatch" }
        if ($tradeHash -ne [string]$scenario.tradeLedgerSha256) { throw "Trade ledger hash mismatch" }
        if ($orders.Count -ne [int]$scenario.orders) { throw "Order count mismatch" }
        if ($trades.Count -ne [int]$scenario.completedRoundTrips) { throw "Trade count mismatch" }
        $signalsById = @{}
        foreach ($signal in $signals) {
            $id = [string]$signal.signalId
            if ($signalsById.ContainsKey($id)) { throw "Duplicate signal id: $id" }
            $availableAt = [DateTimeOffset]::Parse([string]$signal.signalAvailableAtUtc)
            $scheduledAt = [DateTimeOffset]::Parse([string]$signal.scheduledExecutionTimeUtc)
            if ($scheduledAt -lt $availableAt) { throw "Signal scheduled before availability: $id" }
            $signalsById[$id] = $signal
        }
        $cash = 1.0
        $quantity = 0.0
        $feeSum = 0.0
        $turnoverSum = 0.0
        $sequence = 0
        foreach ($order in $orders) {
            $sequence++
            if ([int]$order.sequence -ne $sequence) { throw "Order sequence mismatch" }
            $signalId = [string]$order.signalId
            if (-not $signalsById.ContainsKey($signalId)) { throw "Order references unknown signal: $signalId" }
            $signal = $signalsById[$signalId]
            $executionAt = [DateTimeOffset]::Parse([string]$order.executionTimeUtc)
            if ($executionAt -lt [DateTimeOffset]::Parse([string]$signal.signalAvailableAtUtc)) {
                throw "Order executed before signal availability"
            }
            if ([string]$order.reason -notin @("ATR_STOP", "FINAL_LIQUIDATION") -and
                    [string]$order.executionTimeUtc -ne [string]$signal.scheduledExecutionTimeUtc) {
                throw "Scheduled order execution time mismatch"
            }
            $gross = [double]$order.grossNotionalEquityUnits
            $fee = [double]$order.feeEquityUnits
            $baseQuantity = [double]$order.baseQuantity
            $midPrice = [double]$order.midPrice
            $fillPrice = [double]$order.fillPrice
            Assert-Near -Actual $fee -Expected ($gross * $feeRate) -Tolerance 0.000001 -Label "Per-order fee"
            Assert-Near -Actual $gross -Expected ($baseQuantity * $fillPrice) -Tolerance 0.000001 -Label "Per-order notional"
            $expectedFill = if ([string]$order.side -eq "BUY") {
                $midPrice * (1.0 + $slippage)
            } else {
                $midPrice * (1.0 - $slippage)
            }
            $fillTolerance = [Math]::Max(0.000001, [Math]::Abs($expectedFill) * 0.00000001)
            Assert-Near -Actual $fillPrice -Expected $expectedFill -Tolerance $fillTolerance -Label "Adverse slippage"
            Assert-Near -Actual ([double]$order.cashBefore) -Expected $cash -Tolerance 0.000001 -Label "Cash before"
            Assert-Near -Actual ([double]$order.positionQuantityBefore) -Expected $quantity `
                -Tolerance 0.000001 -Label "Position before"
            if ([string]$order.side -eq "BUY") {
                $cash -= $gross + $fee
                $quantity += $baseQuantity
            } elseif ([string]$order.side -eq "SELL") {
                $cash += $gross - $fee
                $quantity -= $baseQuantity
            } else {
                throw "Unsupported order side: $($order.side)"
            }
            Assert-Near -Actual ([double]$order.cashAfter) -Expected $cash -Tolerance 0.000001 -Label "Cash after"
            Assert-Near -Actual ([double]$order.positionQuantityAfter) -Expected $quantity `
                -Tolerance 0.000001 -Label "Position after"
            $cash = [double]$order.cashAfter
            $quantity = [double]$order.positionQuantityAfter
            $feeSum += $fee
            $turnoverSum += $gross
        }
        Assert-Near -Actual ($feeSum * 100.0) -Expected ([double]$scenario.totalFeesPctOfInitialCapital) `
            -Tolerance 0.0001 -Label "Total fees"
        Assert-Near -Actual ($turnoverSum * 100.0) -Expected ([double]$scenario.turnoverPctOfInitialCapital) `
            -Tolerance 0.0001 -Label "Total turnover"
        foreach ($check in $scenario.ledgerIntegrityChecks.PSObject.Properties) {
            if (-not [bool]$check.Value) { throw "Analyzer ledger check is false: $($check.Name)" }
        }
    }
}
$deterministicPayload = [ordered]@{
    datasetSha256 = [string]$report.dataset.canonicalSha256
    researchPolicySha256 = [string]$report.researchPolicySha256
    analyzerSha256 = [string]$report.analyzerSha256
    results = @($report.results)
    recommendedShadowCandidates = @($report.recommendedShadowCandidates)
    verdict = [string]$report.verdict
}
$deterministicHash = Get-Sha256Text ($deterministicPayload | ConvertTo-Json -Depth 30 -Compress)
if ($deterministicHash -ne [string]$report.deterministicResultSha256) {
    throw "Deterministic result hash mismatch"
}
Write-Output ([ordered]@{
        status = "REPORT_VERIFIED_READ_ONLY"
        reportPath = $reportFull
        scenarioCount = $scenarioCount
        candidateCount = $results.Count
        recommendedShadowCandidates = @($report.recommendedShadowCandidates)
        deterministicResultSha256 = $deterministicHash
        productionMutationPerformed = $false
    } | ConvertTo-Json -Compress)
