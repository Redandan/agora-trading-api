[CmdletBinding()]
param(
    [string]$NnCsv = (Join-Path $PSScriptRoot "..\docs\tradingview\strategy485-nn-chart-export-365.csv"),
    [string]$GoldenCsv = (Join-Path $PSScriptRoot "..\docs\tradingview\strategy485-golden-365.csv"),
    [datetime]$ReplayStartUtc = [datetime]::SpecifyKind([datetime]"2017-08-17T00:00:00", [DateTimeKind]::Utc),
    [datetime]$WindowEndExclusiveUtc = [datetime]::SpecifyKind([datetime]"2026-07-11T00:00:00", [DateTimeKind]::Utc),
    [string]$RestBaseUrl = "https://data-api.binance.vision/api/v3/klines",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\target\tradingview"),
    [switch]$RequireFullDailyNnParity
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RequiredFile {
    param([string]$PathValue, [string]$Description)

    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        throw "$Description not found: $PathValue"
    }
    return (Resolve-Path -LiteralPath $PathValue).Path
}

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$nnFullPath = Resolve-RequiredFile -PathValue $NnCsv -Description "TradingView NN CSV"
$goldenFullPath = Resolve-RequiredFile -PathValue $GoldenCsv -Description "TradingView golden CSV"
$restUri = [uri]$RestBaseUrl
if ($restUri.Scheme -ne "https" -or
    $restUri.Host -ne "data-api.binance.vision" -or
    $restUri.AbsolutePath -ne "/api/v3/klines") {
    throw "RestBaseUrl must be the official Binance Vision HTTPS kline endpoint"
}
$outputCandidate = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $projectRoot $OutputDirectory
}
$outputFullPath = [System.IO.Path]::GetFullPath($outputCandidate)
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "target"))
$targetPrefix = $targetRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $outputFullPath.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDirectory must stay under the project target directory"
}
New-Item -ItemType Directory -Force -Path $outputFullPath | Out-Null

$cursor = [DateTimeOffset]$ReplayStartUtc
$endExclusive = [DateTimeOffset]$WindowEndExclusiveUtc
if ($cursor -ge $endExclusive) {
    throw "ReplayStartUtc must be before WindowEndExclusiveUtc"
}

$bars = [System.Collections.Generic.List[object]]::new()
while ($cursor -lt $endExclusive) {
    $startMs = $cursor.ToUnixTimeMilliseconds()
    $endMs = $endExclusive.ToUnixTimeMilliseconds() - 1
    $uri = "$RestBaseUrl`?symbol=BTCUSDT&interval=1d&startTime=$startMs&endTime=$endMs&limit=1000"
    $page = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 30
    if ($page.Count -eq 0) {
        break
    }
    foreach ($row in $page) {
        $bars.Add($row)
    }
    $lastRow = $page[$page.Count - 1]
    $nextMs = [int64]$lastRow[0] + 86400000
    if ($nextMs -le $startMs) {
        throw "Binance Vision cursor did not advance"
    }
    $cursor = [DateTimeOffset]::FromUnixTimeMilliseconds($nextMs)
}

$expectedBars = [int](($endExclusive - [DateTimeOffset]$ReplayStartUtc).TotalDays)
if ($bars.Count -ne $expectedBars) {
    throw "Binance replay bar count mismatch: expected=$expectedBars actual=$($bars.Count)"
}
for ($i = 0; $i -lt $bars.Count; $i++) {
    $expectedOpenMs = ([DateTimeOffset]$ReplayStartUtc).ToUnixTimeMilliseconds() + ([int64]$i * 86400000)
    $actualOpenMs = [int64]$bars[$i][0]
    if ($actualOpenMs -ne $expectedOpenMs) {
        throw "Binance daily replay gap at index $i expected=$expectedOpenMs actual=$actualOpenMs"
    }
}

$replayCsvPath = Join-Path $outputFullPath "strategy485-binance-replay.csv"
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("open_time,open,high,low,close,volume,close_time")
foreach ($row in $bars) {
    $openTime = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$row[0]).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss")
    $closeTime = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$row[6]).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss.fff")
    $lines.Add("$openTime,$($row[1]),$($row[2]),$($row[3]),$($row[4]),$($row[5]),$closeTime")
}
[System.IO.File]::WriteAllLines($replayCsvPath, $lines, [System.Text.UTF8Encoding]::new($false))

$resultPath = Join-Path $outputFullPath "strategy485-exact-parity-result.json"
$mavenArguments = @(
    "-Dtest=TradingViewScoreBuyGoldenDatasetIT",
    "-Dtradingview.replay.csv=$replayCsvPath",
    "-Dtradingview.nn.csv=$nnFullPath",
    "-Dtradingview.golden.csv=$goldenFullPath",
    "-Dtradingview.result.json=$resultPath",
    "-Dtradingview.require.full.daily.nn=$($RequireFullDailyNnParity.IsPresent.ToString().ToLowerInvariant())",
    "test"
)

Push-Location $projectRoot
try {
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "TradingView exact parity Maven test failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "Exact parity result was not written: $resultPath"
}
$result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
if ($result.intentParityStatus -ne "PASS_EXACT_PARITY") {
    throw "Strategy 485 exact parity failed: status=$($result.status) nnMismatch=$($result.rawNnMismatchCount) missing=$($result.missingIntents) extra=$($result.extraIntents)"
}
if ($RequireFullDailyNnParity -and -not $result.fullDailyNnParity) {
    throw "Strategy 485 full daily NN parity failed: mismatch=$($result.rawNnMismatchCount) maxError=$($result.maxRawNnError)"
}
if (-not $result.fullDailyNnParity) {
    Write-Warning "Buy-point parity passed, but full daily NN series has $($result.rawNnMismatchCount) non-intent rows over tolerance; maxError=$($result.maxRawNnError)"
}

$result | ConvertTo-Json -Depth 8
Write-Host "[strategy485-tradingview-exact-parity] PASS bars=$($result.replayBars) nnRows=$($result.nnRowsCompared) expected=$($result.expectedIntents) actual=$($result.actualIntents) missing=0 extra=0"
Write-Host "[strategy485-tradingview-exact-parity] boundary=LOCAL_READ_ONLY no production import/env/live/order mutation"
