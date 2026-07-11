Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "join_tradingview_nn_chart_export.ps1"
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("tv-nn-join-" + [guid]::NewGuid().ToString("N"))
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.Directory]::CreateDirectory($tempDir) | Out-Null

try {
    $chartPath = Join-Path $tempDir "chart.csv"
    $intentPath = Join-Path $tempDir "intents.csv"
    $outputPath = Join-Path $tempDir "golden.csv"
    [System.IO.File]::WriteAllText($chartPath, @'
time,open,AI: NN Output Export
2025-08-03T00:00:00Z,112000,0.3812345678
2025-08-04T00:00:00Z,114000,0.3823456789
'@.Trim() + [Environment]::NewLine, $utf8NoBom)
    [System.IO.File]::WriteAllText($intentPath, @'
signal_time_utc,reason,label,qty
2025-08-03T00:00:00,TRADINGVIEW_RELATIVE_LOW,relative-low,1000
2025-08-03T00:00:00,TRADINGVIEW_POTENTIAL_LOW,potential-low,2000
2025-08-04T00:00:00,TRADINGVIEW_RELATIVE_LOW,relative-low,1000
'@.Trim() + [Environment]::NewLine, $utf8NoBom)

    & $scriptPath `
        -ChartCsvPath $chartPath `
        -IntentCsvPath $intentPath `
        -OutputPath $outputPath
    $joined = @(Import-Csv -LiteralPath $outputPath)
    if ($joined.Count -ne 3) { throw "Expected 3 joined intents, got $($joined.Count)" }
    if ($joined[0].nn_output -ne "0.3812345678" -or $joined[1].nn_output -ne "0.3812345678") {
        throw "Duplicate same-bar intents did not receive the same exact NN value"
    }
    if ($joined[2].nn_output -ne "0.3823456789") {
        throw "Second bar NN value was not preserved"
    }
    $manifest = Get-Content -Raw -LiteralPath "$outputPath.manifest.json" | ConvertFrom-Json
    if ($manifest.intentCount -ne 3 -or -not $manifest.nnEvidenceComplete) {
        throw "Join manifest is incomplete"
    }
    if ($manifest.productionImportAllowed -or $manifest.productionEnvChangeAllowed -or $manifest.livePromotionAllowed) {
        throw "Join manifest must not authorize production or live changes"
    }

    $missingIntentPath = Join-Path $tempDir "missing-intent.csv"
    $missingOutputPath = Join-Path $tempDir "missing-golden.csv"
    [System.IO.File]::WriteAllText($missingIntentPath, @'
signal_time_utc,reason,label,qty
2025-08-05T00:00:00,TRADINGVIEW_RELATIVE_LOW,relative-low,1000
'@.Trim() + [Environment]::NewLine, $utf8NoBom)
    $missingFailed = $false
    try {
        & $scriptPath `
            -ChartCsvPath $chartPath `
            -IntentCsvPath $missingIntentPath `
            -OutputPath $missingOutputPath | Out-Null
    } catch {
        $missingFailed = $_.Exception.Message -match "missing NN rows"
    }
    if (-not $missingFailed) {
        throw "Join must fail closed when an intent timestamp has no chart NN value"
    }

    Write-Host "[tradingview-nn-chart-join-test] OK"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        $resolvedTempDir = [System.IO.Path]::GetFullPath($tempDir)
        $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if (-not $resolvedTempDir.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not ([System.IO.Path]::GetFileName($resolvedTempDir)).StartsWith("tv-nn-join-", [System.StringComparison]::Ordinal)) {
            throw "Refusing to remove unexpected temp path: $resolvedTempDir"
        }
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}
