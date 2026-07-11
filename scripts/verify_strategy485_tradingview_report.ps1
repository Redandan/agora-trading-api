[CmdletBinding()]
param(
    [string]$ReportCsv = (Join-Path $PSScriptRoot "..\docs\tradingview\strategy485-report-365.csv"),
    [datetime]$ReplayStartUtc = [datetime]::SpecifyKind([datetime]"2017-08-17T00:00:00", [DateTimeKind]::Utc),
    [datetime]$WindowStartUtc = [datetime]::SpecifyKind([datetime]"2025-07-11T00:00:00", [DateTimeKind]::Utc),
    [datetime]$WindowEndExclusiveUtc = [datetime]::SpecifyKind([datetime]"2026-07-11T00:00:00", [DateTimeKind]::Utc),
    [string]$RestBaseUrl = "https://data-api.binance.vision/api/v3/klines"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ReportCsv -PathType Leaf)) {
    throw "TradingView report CSV not found: $ReportCsv"
}
if (($WindowEndExclusiveUtc - $WindowStartUtc).TotalDays -lt 365) {
    throw "TradingView parity window must be at least 365 days"
}

$supportedReasons = @(
    "TRADINGVIEW_RELATIVE_LOW",
    "TRADINGVIEW_POTENTIAL_LOW"
)
$report = @(Import-Csv -LiteralPath $ReportCsv)
$unsupported = @($report | Where-Object { $_.reason -notin $supportedReasons })
if ($unsupported.Count -gt 0) {
    throw "Report contains order paths that this low-pattern verifier cannot prove: $($unsupported.reason -join ',')"
}

$cursor = [DateTimeOffset]$ReplayStartUtc
$endExclusive = [DateTimeOffset]$WindowEndExclusiveUtc
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

if ($bars.Count -eq 0) {
    throw "Binance Vision returned no BTCUSDT 1d bars"
}
for ($i = 1; $i -lt $bars.Count; $i++) {
    $previousOpenMs = [int64]$bars[$i - 1][0]
    $currentOpenMs = [int64]$bars[$i][0]
    if ($currentOpenMs -ne $previousOpenMs + 86400000) {
        throw "Binance daily replay gap between $previousOpenMs and $currentOpenMs"
    }
}

$actual = [System.Collections.Generic.List[string]]::new()
for ($i = 1; $i -lt $bars.Count; $i++) {
    $openTime = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$bars[$i][0]).UtcDateTime
    if ($openTime -lt $WindowStartUtc -or $openTime -ge $WindowEndExclusiveUtc) {
        continue
    }

    $shortStart = [Math]::Max(0, $i - 20)
    $potentialStart = [Math]::Max(0, $i - 63)
    $previousShortLow = [double]::PositiveInfinity
    $previousPotentialLow = [double]::PositiveInfinity
    for ($j = $shortStart; $j -le $i - 1; $j++) {
        $previousShortLow = [Math]::Min($previousShortLow, [double]$bars[$j][3])
    }
    for ($j = $potentialStart; $j -le $i - 1; $j++) {
        $previousPotentialLow = [Math]::Min($previousPotentialLow, [double]$bars[$j][3])
    }

    $low = [double]$bars[$i][3]
    $close = [double]$bars[$i][4]
    $timeText = $openTime.ToString("yyyy-MM-ddTHH:mm:ss")
    if ($low -le $previousShortLow -and $close -gt $previousShortLow) {
        $actual.Add("$timeText|TRADINGVIEW_RELATIVE_LOW|1000")
    }
    if ($low -le $previousPotentialLow -and $close -gt $previousPotentialLow) {
        $actual.Add("$timeText|TRADINGVIEW_POTENTIAL_LOW|2000")
    }
}

$expected = @($report | ForEach-Object {
    "$($_.signal_time_utc)|$($_.reason)|$($_.qty)"
})
$missing = @($expected | Where-Object { $_ -notin $actual })
$extra = @($actual | Where-Object { $_ -notin $expected })
$reportHash = (Get-FileHash -LiteralPath $ReportCsv -Algorithm SHA256).Hash.ToLowerInvariant()

$result = [ordered]@{
    status = if ($missing.Count -eq 0 -and $extra.Count -eq 0) { "PASS" } else { "FAIL" }
    boundary = "READ_ONLY_EXTERNAL_BINANCE_VISION"
    source = $RestBaseUrl
    reportCsv = (Resolve-Path -LiteralPath $ReportCsv).Path
    reportSha256 = $reportHash
    replayFirstBarUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$bars[0][0]).UtcDateTime.ToString("s")
    replayLastBarUtc = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$bars[$bars.Count - 1][0]).UtcDateTime.ToString("s")
    replayBars = $bars.Count
    expectedIntents = $expected.Count
    actualIntents = $actual.Count
    missingCount = $missing.Count
    extraCount = $extra.Count
    missingSample = @($missing | Select-Object -First 10)
    extraSample = @($extra | Select-Object -First 10)
    nnParityProven = $false
    nnBlocker = "TRADINGVIEW_NN_SERIES_NOT_EXPORTED"
}

$result | ConvertTo-Json -Depth 5
if ($result.status -ne "PASS") {
    throw "Strategy 485 TradingView report parity failed: missing=$($missing.Count) extra=$($extra.Count)"
}
Write-Host "[strategy485-tradingview-report] PASS expected=$($expected.Count) actual=$($actual.Count) missing=0 extra=0"
