[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [Parameter(Mandatory = $true)]
    [string]$WindowStartUtc,

    [Parameter(Mandatory = $true)]
    [string]$WindowEndUtc,

    [string]$TimeColumn = "",
    [string]$ReasonColumn = "",
    [string]$LabelColumn = "",
    [string]$QuantityColumn = "",
    [string]$NnOutputColumn = "",
    [string]$SourceTimeZoneId = "UTC",
    [string]$ReasonMapPath = "",
    [string]$LabelMapPath = "",
    [string]$FilterColumn = "",
    [string]$FilterRegex = "",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1d",
    [string]$Source = "binance",
    [int]$MinimumWindowDays = 365,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Resolve-FullPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    return [System.IO.Path]::GetFullPath($PathValue)
}

function Resolve-Header {
    param(
        [string[]]$Headers,
        [string]$Requested,
        [string[]]$Aliases,
        [string]$FieldName
    )

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $candidates += $Requested
    } else {
        $candidates += $Aliases
    }

    foreach ($candidate in $candidates) {
        $match = @($Headers | Where-Object { $_.Trim().Equals($candidate.Trim(), [System.StringComparison]::OrdinalIgnoreCase) })
        if ($match.Count -eq 1) { return $match[0] }
    }

    $mode = if ([string]::IsNullOrWhiteSpace($Requested)) { "aliases=" + ($Aliases -join ",") } else { "requested=$Requested" }
    throw "Missing $FieldName CSV column ($mode). Available headers: $($Headers -join ', ')"
}

function Read-StringMap {
    param([string]$PathValue, [string]$Name)
    $map = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return $map }

    $fullPath = Resolve-FullPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Name map file not found: $fullPath"
    }
    $parsed = Get-Content -Raw -LiteralPath $fullPath | ConvertFrom-Json -ErrorAction Stop
    foreach ($property in $parsed.PSObject.Properties) {
        $value = [string]$property.Value
        if ([string]::IsNullOrWhiteSpace($property.Name) -or [string]::IsNullOrWhiteSpace($value)) {
            throw "$Name map contains a blank key or value: $fullPath"
        }
        $map.Add($property.Name.Trim(), $value.Trim())
    }
    if ($map.Count -eq 0) { throw "$Name map is empty: $fullPath" }
    return $map
}

function Resolve-MappedValue {
    param(
        [string]$RawValue,
        [System.Collections.Generic.Dictionary[string, string]]$Map,
        [string]$FieldName,
        [int]$RowNumber
    )
    $trimmed = if ($null -eq $RawValue) { "" } else { $RawValue.Trim() }
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        throw "Row $RowNumber has blank $FieldName"
    }
    if ($Map.Count -eq 0) { return $trimmed }
    if (-not $Map.ContainsKey($trimmed)) {
        throw "Row $RowNumber $FieldName value is missing from its exact map: $trimmed"
    }
    return $Map[$trimmed]
}

function Convert-ToUtcInstant {
    param(
        [string]$RawValue,
        [System.TimeZoneInfo]$SourceTimeZone,
        [int]$RowNumber
    )
    $text = if ($null -eq $RawValue) { "" } else { $RawValue.Trim() }
    if ([string]::IsNullOrWhiteSpace($text)) { throw "Row $RowNumber has blank time" }

    if ($text -match '^\d{10}$') {
        return [System.DateTimeOffset]::FromUnixTimeSeconds([long]$text).ToUniversalTime()
    }
    if ($text -match '^\d{13}$') {
        return [System.DateTimeOffset]::FromUnixTimeMilliseconds([long]$text).ToUniversalTime()
    }

    $offsetValue = [System.DateTimeOffset]::MinValue
    if ($text -match '(Z|[+-]\d{2}:?\d{2})$') {
        if (-not [System.DateTimeOffset]::TryParse(
                $text,
                $invariant,
                [System.Globalization.DateTimeStyles]::AllowWhiteSpaces,
                [ref]$offsetValue)) {
            throw "Row $RowNumber has invalid offset-aware time: $text"
        }
        return $offsetValue.ToUniversalTime()
    }

    $localValue = [System.DateTime]::MinValue
    if (-not [System.DateTime]::TryParse(
            $text,
            $invariant,
            [System.Globalization.DateTimeStyles]::AllowWhiteSpaces,
            [ref]$localValue)) {
        throw "Row $RowNumber has invalid local time: $text"
    }
    $localValue = [System.DateTime]::SpecifyKind($localValue, [System.DateTimeKind]::Unspecified)
    if ($SourceTimeZone.IsInvalidTime($localValue)) {
        throw "Row $RowNumber time falls in an invalid daylight-saving gap: $text ($($SourceTimeZone.Id))"
    }
    if ($SourceTimeZone.IsAmbiguousTime($localValue)) {
        throw "Row $RowNumber time is daylight-saving ambiguous; export UTC or include an offset: $text ($($SourceTimeZone.Id))"
    }
    $utc = [System.TimeZoneInfo]::ConvertTimeToUtc($localValue, $SourceTimeZone)
    return [System.DateTimeOffset]::new($utc)
}

function Convert-ToRequiredUtcInstant {
    param([string]$RawValue, [string]$Name)
    if ($RawValue -notmatch '(Z|[+-]\d{2}:?\d{2})$') {
        throw "$Name must include Z or an explicit UTC offset: $RawValue"
    }
    $parsed = [System.DateTimeOffset]::MinValue
    if (-not [System.DateTimeOffset]::TryParse(
            $RawValue,
            $invariant,
            [System.Globalization.DateTimeStyles]::AllowWhiteSpaces,
            [ref]$parsed)) {
        throw "$Name is invalid: $RawValue"
    }
    return $parsed.ToUniversalTime()
}

function Convert-ToCanonicalDecimal {
    param([string]$RawValue, [string]$FieldName, [int]$RowNumber)
    $value = [decimal]::Zero
    if (-not [decimal]::TryParse(
            [string]$RawValue,
            [System.Globalization.NumberStyles]::Float,
            $invariant,
            [ref]$value)) {
        throw "Row $RowNumber has invalid $FieldName decimal: $RawValue"
    }
    if ($FieldName -eq "qty" -and $value -le 0) {
        throw "Row $RowNumber qty must be positive: $RawValue"
    }
    return $value.ToString("0.############################", $invariant)
}

if ($MinimumWindowDays -lt 365) { throw "MinimumWindowDays cannot be lower than 365" }
if ($Symbol -ne "BTCUSDT") { throw "Golden truth symbol must be BTCUSDT" }
if ($IntervalCode -ne "1d") { throw "Golden truth interval must be 1d" }
if ($Source -ne "binance") { throw "Golden truth source must be binance" }
if ([string]::IsNullOrWhiteSpace($FilterColumn) -xor [string]::IsNullOrWhiteSpace($FilterRegex)) {
    throw "FilterColumn and FilterRegex must be supplied together"
}

$inputFullPath = Resolve-FullPath -PathValue $InputPath
$outputFullPath = Resolve-FullPath -PathValue $OutputPath
$manifestFullPath = "$outputFullPath.manifest.json"
if (-not (Test-Path -LiteralPath $inputFullPath -PathType Leaf)) { throw "Input CSV not found: $inputFullPath" }
if ($inputFullPath.Equals($outputFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "InputPath and OutputPath must be different"
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

$windowStart = Convert-ToRequiredUtcInstant -RawValue $WindowStartUtc -Name "WindowStartUtc"
$windowEnd = Convert-ToRequiredUtcInstant -RawValue $WindowEndUtc -Name "WindowEndUtc"
if ($windowEnd -le $windowStart) { throw "WindowEndUtc must be after WindowStartUtc" }
$windowDays = ($windowEnd - $windowStart).TotalDays
if ($windowDays -lt $MinimumWindowDays) {
    throw "TradingView export window is $([math]::Round($windowDays, 3)) days; at least $MinimumWindowDays days are required"
}

try {
    $sourceTimeZone = [System.TimeZoneInfo]::FindSystemTimeZoneById($SourceTimeZoneId)
} catch {
    throw "Unknown SourceTimeZoneId: $SourceTimeZoneId"
}

$sourceRows = @(Import-Csv -LiteralPath $inputFullPath)
if ($sourceRows.Count -eq 0) { throw "Input CSV has no data rows" }
$headers = @($sourceRows[0].PSObject.Properties.Name)
$timeHeader = Resolve-Header -Headers $headers -Requested $TimeColumn `
    -Aliases @("time", "bar_time", "baropen_time", "date", "Entry time", "Entry date/time") -FieldName "time"
$reasonHeader = Resolve-Header -Headers $headers -Requested $ReasonColumn `
    -Aliases @("reason", "order_reason", "Entry ID", "Entry name", "Entry order") -FieldName "reason"
$labelHeader = Resolve-Header -Headers $headers -Requested $LabelColumn `
    -Aliases @("label", "order_label", "Entry comment", "Order comment") -FieldName "label"
$quantityHeader = Resolve-Header -Headers $headers -Requested $QuantityColumn `
    -Aliases @("qty", "quantity", "order_qty", "contracts", "Entry qty", "Size") -FieldName "qty"
$nnHeader = Resolve-Header -Headers $headers -Requested $NnOutputColumn `
    -Aliases @("nn_output", "nn", "neural_output", "Neural network output", "AI output") -FieldName "nn_output"

$filterHeader = ""
if (-not [string]::IsNullOrWhiteSpace($FilterColumn)) {
    $filterHeader = Resolve-Header -Headers $headers -Requested $FilterColumn -Aliases @() -FieldName "filter"
    try {
        $filterPattern = [regex]::new($FilterRegex, [System.Text.RegularExpressions.RegexOptions]::CultureInvariant)
    } catch {
        throw "FilterRegex is invalid: $FilterRegex"
    }
}

$reasonMap = Read-StringMap -PathValue $ReasonMapPath -Name "reason"
$labelMap = Read-StringMap -PathValue $LabelMapPath -Name "label"
$normalizedRows = [System.Collections.Generic.List[object]]::new()
$rowNumber = 1
foreach ($sourceRow in $sourceRows) {
    $rowNumber++
    if ($filterHeader -and -not $filterPattern.IsMatch([string]$sourceRow.$filterHeader)) { continue }

    $time = Convert-ToUtcInstant -RawValue ([string]$sourceRow.$timeHeader) `
        -SourceTimeZone $sourceTimeZone -RowNumber $rowNumber
    if ($time -lt $windowStart -or $time -gt $windowEnd) {
        throw "Row $rowNumber time is outside the declared TradingView window: $($time.ToString('o'))"
    }
    $reason = Resolve-MappedValue -RawValue ([string]$sourceRow.$reasonHeader) `
        -Map $reasonMap -FieldName "reason" -RowNumber $rowNumber
    $label = Resolve-MappedValue -RawValue ([string]$sourceRow.$labelHeader) `
        -Map $labelMap -FieldName "label" -RowNumber $rowNumber
    $qty = Convert-ToCanonicalDecimal -RawValue ([string]$sourceRow.$quantityHeader) `
        -FieldName "qty" -RowNumber $rowNumber
    $nn = Convert-ToCanonicalDecimal -RawValue ([string]$sourceRow.$nnHeader) `
        -FieldName "nn_output" -RowNumber $rowNumber

    $normalizedRows.Add([pscustomobject][ordered]@{
        time = $time.ToString("yyyy-MM-dd'T'HH:mm:ss", $invariant)
        reason = $reason
        label = $label
        qty = $qty
        nn_output = $nn
        ordinal = $rowNumber
    })
}
if ($normalizedRows.Count -eq 0) { throw "No rows remain after applying the entry filter" }

$orderedRows = @($normalizedRows | Sort-Object time, ordinal)
$csvRows = @($orderedRows | Select-Object time, reason, label, qty, nn_output | ConvertTo-Csv -NoTypeInformation)
[System.IO.File]::WriteAllLines($outputFullPath, $csvRows, $utf8NoBom)

$sourceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $inputFullPath).Hash.ToLowerInvariant()
$goldenSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputFullPath).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    formatVersion = 1
    status = "READY_FOR_LOCAL_PARITY_VERIFICATION_NOT_PRODUCTION_IMPORT"
    sourceCsvPath = $inputFullPath
    sourceCsvSha256 = $sourceSha256
    goldenCsvPath = $outputFullPath
    goldenCsvSha256 = $goldenSha256
    symbol = $Symbol
    intervalCode = $IntervalCode
    source = $Source
    sourceTimeZoneId = $SourceTimeZoneId
    windowStartUtc = $windowStart.ToString("o")
    windowEndUtc = $windowEnd.ToString("o")
    windowDays = [math]::Round($windowDays, 6)
    minimumWindowDays = $MinimumWindowDays
    intentCount = $orderedRows.Count
    firstIntentUtc = $orderedRows[0].time + "Z"
    lastIntentUtc = $orderedRows[-1].time + "Z"
    nnEvidenceComplete = $true
    selectedColumns = [ordered]@{
        time = $timeHeader
        reason = $reasonHeader
        label = $labelHeader
        qty = $quantityHeader
        nn_output = $nnHeader
        filter = $filterHeader
    }
    reasonMapPath = if ($reasonMap.Count -gt 0) { Resolve-FullPath -PathValue $ReasonMapPath } else { "N/A" }
    labelMapPath = if ($labelMap.Count -gt 0) { Resolve-FullPath -PathValue $LabelMapPath } else { "N/A" }
    productionImportAllowed = $false
    productionEnvChangeAllowed = $false
    livePromotionAllowed = $false
}
$manifestJson = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($manifestFullPath, $manifestJson + [Environment]::NewLine, $utf8NoBom)

Write-Host "TRADINGVIEW_GOLDEN_TRUTH_NORMALIZATION"
Write-Host "status=$($manifest.status)"
Write-Host "goldenCsvPath=$outputFullPath"
Write-Host "manifestPath=$manifestFullPath"
Write-Host "intentCount=$($orderedRows.Count)"
Write-Host "windowDays=$($manifest.windowDays)"
Write-Host "goldenSha256=$goldenSha256"
Write-Host "nnEvidenceComplete=true"
Write-Host "productionImportAllowed=false"
Write-Host "productionEnvChangeAllowed=false"
Write-Host "livePromotionAllowed=false"
Write-Host "nextAction=Run local exact-parity verification; request separate authorization before any production file or env change."
