[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ChartCsvPath,

    [string]$IntentCsvPath = "",
    [string]$OutputPath = "",
    [string]$ChartTimeColumn = "",
    [string]$NnOutputColumn = "",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Resolve-FullPath {
    param([string]$PathValue)
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Normalize-Header {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return (($Value.Trim().ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_'))
}

function Resolve-Header {
    param(
        [string[]]$Headers,
        [string]$Requested,
        [string[]]$Aliases,
        [string]$FieldName,
        [switch]$AllowNormalizedSuffix
    )

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $exact = @($Headers | Where-Object {
            $_.Trim().Equals($Requested.Trim(), [System.StringComparison]::OrdinalIgnoreCase)
        })
        if ($exact.Count -eq 1) { return $exact[0] }
        throw "Missing $FieldName CSV column requested=$Requested. Available headers: $($Headers -join ', ')"
    }

    $normalizedAliases = @($Aliases | ForEach-Object { Normalize-Header -Value $_ })
    $matches = @($Headers | Where-Object {
        $normalized = Normalize-Header -Value $_
        if ($normalized -in $normalizedAliases) { return $true }
        if ($AllowNormalizedSuffix) {
            foreach ($alias in $normalizedAliases) {
                if ($normalized.EndsWith("_$alias", [System.StringComparison]::Ordinal) -or
                    $normalized.IndexOf($alias, [System.StringComparison]::Ordinal) -ge 0) {
                    return $true
                }
            }
        }
        return $false
    })
    if ($matches.Count -eq 1) { return $matches[0] }
    if ($matches.Count -gt 1) {
        throw "Ambiguous $FieldName CSV columns: $($matches -join ', ')"
    }
    throw "Missing $FieldName CSV column. Available headers: $($Headers -join ', ')"
}

function Convert-ToUtcKey {
    param([string]$RawValue, [string]$Context)
    $text = if ($null -eq $RawValue) { "" } else { $RawValue.Trim() }
    if ([string]::IsNullOrWhiteSpace($text)) { throw "$Context has blank time" }

    if ($text -match '^\d{10}$') {
        $time = [System.DateTimeOffset]::FromUnixTimeSeconds([long]$text).ToUniversalTime()
    } elseif ($text -match '^\d{13}$') {
        $time = [System.DateTimeOffset]::FromUnixTimeMilliseconds([long]$text).ToUniversalTime()
    } else {
        $parsed = [System.DateTimeOffset]::MinValue
        $styles = [System.Globalization.DateTimeStyles]::AllowWhiteSpaces -bor
            [System.Globalization.DateTimeStyles]::AssumeUniversal -bor
            [System.Globalization.DateTimeStyles]::AdjustToUniversal
        if (-not [System.DateTimeOffset]::TryParse($text, $invariant, $styles, [ref]$parsed)) {
            throw "$Context has invalid time: $text"
        }
        $time = $parsed.ToUniversalTime()
    }
    return $time.ToString("yyyy-MM-dd'T'HH:mm:ss", $invariant)
}

function Convert-ToNnText {
    param([string]$RawValue, [string]$Context)
    $value = [decimal]::Zero
    if (-not [decimal]::TryParse(
            [string]$RawValue,
            [System.Globalization.NumberStyles]::Float,
            $invariant,
            [ref]$value)) {
        throw "$Context has invalid NN output: $RawValue"
    }
    if ($value -lt 0 -or $value -gt 1) {
        throw "$Context NN output must be between 0 and 1: $RawValue"
    }
    return $value.ToString("0.################", $invariant)
}

if ([string]::IsNullOrWhiteSpace($IntentCsvPath)) {
    $IntentCsvPath = Join-Path $PSScriptRoot "..\docs\tradingview\strategy485-report-365.csv"
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot "..\docs\tradingview\strategy485-golden-365.csv"
}

$chartFullPath = Resolve-FullPath -PathValue $ChartCsvPath
$intentFullPath = Resolve-FullPath -PathValue $IntentCsvPath
$outputFullPath = Resolve-FullPath -PathValue $OutputPath
$manifestFullPath = "$outputFullPath.manifest.json"

foreach ($path in @($chartFullPath, $intentFullPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Input CSV not found: $path"
    }
}
if ($chartFullPath.Equals($outputFullPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    $intentFullPath.Equals($outputFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputPath must differ from both input paths"
}
$outputDirectory = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    throw "Output directory does not exist: $outputDirectory"
}
foreach ($target in @($outputFullPath, $manifestFullPath)) {
    if ((Test-Path -LiteralPath $target) -and -not $Force) {
        throw "Output already exists; use -Force to replace it: $target"
    }
}

$chartRows = @(Import-Csv -LiteralPath $chartFullPath)
$intentRows = @(Import-Csv -LiteralPath $intentFullPath)
if ($chartRows.Count -eq 0) { throw "Chart CSV has no rows" }
if ($intentRows.Count -eq 0) { throw "Intent CSV has no rows" }

$chartHeaders = @($chartRows[0].PSObject.Properties.Name)
$chartTimeHeader = Resolve-Header -Headers $chartHeaders -Requested $ChartTimeColumn `
    -Aliases @("time", "date", "datetime", "date_time") -FieldName "chart time"
$nnHeader = Resolve-Header -Headers $chartHeaders -Requested $NnOutputColumn `
    -Aliases @("nn_output_export", "nn_output", "neural_output") -FieldName "NN output" `
    -AllowNormalizedSuffix

$intentHeaders = @($intentRows[0].PSObject.Properties.Name)
$intentTimeHeader = Resolve-Header -Headers $intentHeaders -Requested "" `
    -Aliases @("signal_time_utc", "time", "bar_time", "baropen_time") -FieldName "intent time"
$reasonHeader = Resolve-Header -Headers $intentHeaders -Requested "" `
    -Aliases @("reason", "order_reason") -FieldName "reason"
$labelHeader = Resolve-Header -Headers $intentHeaders -Requested "" `
    -Aliases @("label", "order_label") -FieldName "label"
$qtyHeader = Resolve-Header -Headers $intentHeaders -Requested "" `
    -Aliases @("qty", "quantity", "order_qty") -FieldName "qty"

$nnByTime = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
$chartRowNumber = 1
foreach ($row in $chartRows) {
    $chartRowNumber++
    $key = Convert-ToUtcKey -RawValue ([string]$row.$chartTimeHeader) -Context "Chart row $chartRowNumber"
    $nnText = Convert-ToNnText -RawValue ([string]$row.$nnHeader) -Context "Chart row $chartRowNumber"
    if ($nnByTime.ContainsKey($key)) {
        throw "Chart CSV contains duplicate timestamp: $key"
    }
    $nnByTime.Add($key, $nnText)
}

$goldenRows = [System.Collections.Generic.List[object]]::new()
$missingTimes = [System.Collections.Generic.List[string]]::new()
$intentRowNumber = 1
foreach ($row in $intentRows) {
    $intentRowNumber++
    $key = Convert-ToUtcKey -RawValue ([string]$row.$intentTimeHeader) -Context "Intent row $intentRowNumber"
    if (-not $nnByTime.ContainsKey($key)) {
        $missingTimes.Add($key)
        continue
    }
    $reason = ([string]$row.$reasonHeader).Trim()
    $label = ([string]$row.$labelHeader).Trim()
    $qty = ([string]$row.$qtyHeader).Trim()
    if ([string]::IsNullOrWhiteSpace($reason) -or
        [string]::IsNullOrWhiteSpace($label) -or
        [string]::IsNullOrWhiteSpace($qty)) {
        throw "Intent row $intentRowNumber has blank reason, label, or qty"
    }
    $goldenRows.Add([pscustomobject][ordered]@{
        time = $key
        reason = $reason
        label = $label
        qty = $qty
        nn_output = $nnByTime[$key]
        ordinal = $intentRowNumber
    })
}
if ($missingTimes.Count -gt 0) {
    $sample = @($missingTimes | Select-Object -First 10) -join ","
    throw "Chart CSV is missing NN rows for $($missingTimes.Count) intent(s): $sample"
}
if ($goldenRows.Count -ne $intentRows.Count) {
    throw "Golden row count mismatch: intents=$($intentRows.Count) golden=$($goldenRows.Count)"
}

$orderedRows = @($goldenRows | Sort-Object time, ordinal)
$csvLines = @($orderedRows | Select-Object time, reason, label, qty, nn_output | ConvertTo-Csv -NoTypeInformation)
[System.IO.File]::WriteAllLines($outputFullPath, $csvLines, $utf8NoBom)

$manifest = [ordered]@{
    formatVersion = 1
    status = "READY_FOR_EXACT_LOCAL_PARITY_VERIFICATION_NOT_PRODUCTION_IMPORT"
    chartCsvPath = $chartFullPath
    chartCsvSha256 = (Get-FileHash -LiteralPath $chartFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    intentCsvPath = $intentFullPath
    intentCsvSha256 = (Get-FileHash -LiteralPath $intentFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    goldenCsvPath = $outputFullPath
    goldenCsvSha256 = (Get-FileHash -LiteralPath $outputFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    chartTimeColumn = $chartTimeHeader
    nnOutputColumn = $nnHeader
    intentCount = $orderedRows.Count
    firstIntentUtc = $orderedRows[0].time + "Z"
    lastIntentUtc = $orderedRows[-1].time + "Z"
    nnEvidenceComplete = $true
    pineInstrumentation = 'plot(nnOutput, title = "NN Output Export", display = display.data_window, format = format.price, precision = 10)'
    productionImportAllowed = $false
    productionEnvChangeAllowed = $false
    livePromotionAllowed = $false
}
[System.IO.File]::WriteAllText(
    $manifestFullPath,
    ($manifest | ConvertTo-Json -Depth 5) + [Environment]::NewLine,
    $utf8NoBom)

Write-Host "TRADINGVIEW_NN_CHART_EXPORT_JOIN"
Write-Host "status=$($manifest.status)"
Write-Host "intentCount=$($manifest.intentCount)"
Write-Host "goldenCsvPath=$outputFullPath"
Write-Host "manifestPath=$manifestFullPath"
Write-Host "nnEvidenceComplete=true"
Write-Host "productionImportAllowed=false"
Write-Host "productionEnvChangeAllowed=false"
Write-Host "livePromotionAllowed=false"
Write-Host "nextAction=Run exact parity verification locally; request separate authorization before any production import or env change."
