[CmdletBinding()]
param(
    [string]$BaseUrl = "https://app.okx.com",
    [string]$Instrument = "BTC-USDT",
    [string]$Bar = "1H",
    [datetime]$StartUtc = [datetime]::Parse("2019-01-01T00:00:00Z").ToUniversalTime(),
    [datetime]$EndUtc = (Get-Date).ToUniversalTime(),
    [string]$OutputRoot,
    [string]$ResearchPolicyPath,
    [int]$PageLimit = 300,
    [int]$MaxPages = 400,
    [int]$InterPageDelayMs = 150,
    [string]$SourcePageDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Sha256Text {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Convert-ToUtc {
    param([Parameter(Mandatory = $true)][datetime]$Value)
    if ($Value.Kind -eq [DateTimeKind]::Unspecified) {
        return [datetime]::SpecifyKind($Value, [DateTimeKind]::Utc)
    }
    return $Value.ToUniversalTime()
}

function Get-DecimalInvariant {
    param([Parameter(Mandatory = $true)]$Value)
    $parsed = [decimal]::Zero
    $ok = [decimal]::TryParse(
        [string]$Value,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsed)
    if (-not $ok) { throw "Invalid decimal value: $Value" }
    return $parsed
}

function Assert-OfficialOkxBaseUrl {
    param([Parameter(Mandatory = $true)][string]$Value)
    $uri = [uri]$Value
    if ($uri.Scheme -ne "https") { throw "BaseUrl must use HTTPS: $Value" }
    $hostName = $uri.DnsSafeHost.ToLowerInvariant()
    if ($hostName -ne "okx.com" -and -not $hostName.EndsWith(".okx.com")) {
        throw "BaseUrl must be an official okx.com host: $Value"
    }
    return $uri.GetLeftPart([System.UriPartial]::Authority).TrimEnd("/")
}

function Read-Page {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$PageName
    )
    try {
        $response = $Text | ConvertFrom-Json
    }
    catch {
        throw "Invalid JSON in $PageName`: $($_.Exception.Message)"
    }
    if ([string]$response.code -ne "0") {
        throw "OKX response failure in $PageName`: code=$($response.code) msg=$($response.msg)"
    }
    if ($null -eq $response.data) { throw "OKX response missing data in $PageName" }
    return $response
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "target/research/okx-btc-usdt-1h"
}
if ([string]::IsNullOrWhiteSpace($ResearchPolicyPath)) {
    $ResearchPolicyPath = Join-Path $PSScriptRoot "btc_price_only_research_policy.json"
}

$start = Convert-ToUtc $StartUtc
$end = Convert-ToUtc $EndUtc
if ($start -ge $end) { throw "StartUtc must be earlier than EndUtc" }
if ($Instrument -ne "BTC-USDT") { throw "Only BTC-USDT is supported by this frozen research lane" }
if ($Bar -ne "1H") { throw "Only 1H is supported by this frozen research lane" }
if ($PageLimit -lt 1 -or $PageLimit -gt 300) { throw "PageLimit must be between 1 and 300" }
if ($MaxPages -lt 1) { throw "MaxPages must be positive" }
if (-not (Test-Path -LiteralPath $ResearchPolicyPath -PathType Leaf)) {
    throw "Research policy not found: $ResearchPolicyPath"
}

$sourceMode = if ([string]::IsNullOrWhiteSpace($SourcePageDirectory)) {
    "OKX_OFFICIAL_API"
} else {
    "LOCAL_RAW_PAGE_FIXTURE"
}
$officialBase = Assert-OfficialOkxBaseUrl $BaseUrl
$asOfUtc = $end.ToString("yyyy-MM-ddTHH:mm:ss.fffffffZ", [System.Globalization.CultureInfo]::InvariantCulture)
$datasetId = "okx-btc-usdt-1h-" + $end.ToString("yyyyMMddTHHmmssZ", [System.Globalization.CultureInfo]::InvariantCulture)
$outputRootFull = [System.IO.Path]::GetFullPath($OutputRoot)
$finalDirectory = Join-Path $outputRootFull $datasetId
if (Test-Path -LiteralPath $finalDirectory) { throw "Dataset directory already exists: $finalDirectory" }

$stagingRoot = Join-Path $outputRootFull (".staging-" + [guid]::NewGuid().ToString("N"))
$pagesDirectory = Join-Path $stagingRoot "raw-pages"
New-Item -ItemType Directory -Path $pagesDirectory -Force | Out-Null

$pageFiles = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
$paginationTermination = "UNKNOWN"
try {
    if ($sourceMode -eq "LOCAL_RAW_PAGE_FIXTURE") {
        if (-not (Test-Path -LiteralPath $SourcePageDirectory -PathType Container)) {
            throw "SourcePageDirectory not found: $SourcePageDirectory"
        }
        $fixturePages = @(Get-ChildItem -LiteralPath $SourcePageDirectory -Filter "*.json" -File | Sort-Object Name)
        if ($fixturePages.Count -eq 0) { throw "No JSON pages found in $SourcePageDirectory" }
        foreach ($fixture in $fixturePages) {
            $target = Join-Path $pagesDirectory $fixture.Name
            Copy-Item -LiteralPath $fixture.FullName -Destination $target
            $pageFiles.Add((Get-Item -LiteralPath $target))
        }
        $paginationTermination = "LOCAL_FIXTURE_COMPLETE"
    }
    else {
        $cursorMs = [DateTimeOffset]::new($end).ToUnixTimeMilliseconds()
        $startMs = [DateTimeOffset]::new($start).ToUnixTimeMilliseconds()
        for ($pageIndex = 1; $pageIndex -le $MaxPages; $pageIndex++) {
            $url = "$officialBase/api/v5/market/history-candles?instId=$([uri]::EscapeDataString($Instrument))&bar=$([uri]::EscapeDataString($Bar))&limit=$PageLimit&after=$cursorMs"
            $response = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 30 -Headers @{ "User-Agent" = "agora-trading-research/1.0" }
            $text = [string]$response.Content
            $pageName = "page-{0:D4}.json" -f $pageIndex
            $pagePath = Join-Path $pagesDirectory $pageName
            Write-Utf8NoBom -Path $pagePath -Text $text
            $pageFiles.Add((Get-Item -LiteralPath $pagePath))
            $parsed = Read-Page -Text $text -PageName $pageName
            $rows = @($parsed.data)
            if ($rows.Count -eq 0) {
                $paginationTermination = "NO_MORE_DATA"
                break
            }
            $timestamps = @($rows | ForEach-Object { [int64]$_[0] })
            $minimumTimestamp = ($timestamps | Measure-Object -Minimum).Minimum
            if ($minimumTimestamp -ge $cursorMs) {
                throw "Pagination cursor did not move backward at $pageName"
            }
            if ($minimumTimestamp -le $startMs) {
                $paginationTermination = "REQUESTED_START_REACHED"
                break
            }
            $cursorMs = [int64]$minimumTimestamp
            if ($pageIndex -eq $MaxPages) {
                throw "MaxPages reached before requested start; increase MaxPages"
            }
            if ($InterPageDelayMs -gt 0) { Start-Sleep -Milliseconds $InterPageDelayMs }
        }
    }

    $canonicalRows = [System.Collections.Generic.List[object]]::new()
    $pageManifest = [System.Collections.Generic.List[object]]::new()
    $unconfirmedRows = 0
    $outOfWindowRows = 0
    foreach ($pageFile in $pageFiles) {
        $rawText = Get-Content -Raw -LiteralPath $pageFile.FullName
        $parsed = Read-Page -Text $rawText -PageName $pageFile.Name
        $pageRows = @($parsed.data)
        $pageTimestamps = @()
        foreach ($row in $pageRows) {
            if (@($row).Count -lt 9) { throw "Malformed candle row in $($pageFile.Name)" }
            $timestampMs = [int64]$row[0]
            $pageTimestamps += $timestampMs
            $openTime = [DateTimeOffset]::FromUnixTimeMilliseconds($timestampMs).UtcDateTime
            if ([string]$row[8] -ne "1") {
                $unconfirmedRows++
                continue
            }
            if ($openTime -lt $start -or $openTime -ge $end) {
                $outOfWindowRows++
                continue
            }
            $open = Get-DecimalInvariant $row[1]
            $high = Get-DecimalInvariant $row[2]
            $low = Get-DecimalInvariant $row[3]
            $close = Get-DecimalInvariant $row[4]
            $volume = Get-DecimalInvariant $row[5]
            if ($open -le 0 -or $high -le 0 -or $low -le 0 -or $close -le 0 -or $volume -lt 0) {
                throw "Non-positive OHLC or negative volume at $($openTime.ToString('o'))"
            }
            if ($high -lt [Math]::Max($open, $close) -or $low -gt [Math]::Min($open, $close) -or $high -lt $low) {
                throw "OHLC invariant failure at $($openTime.ToString('o'))"
            }
            $canonicalRows.Add([pscustomobject]@{
                    open_time_utc = $openTime.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                    open = [string]$row[1]
                    high = [string]$row[2]
                    low = [string]$row[3]
                    close = [string]$row[4]
                    volume = [string]$row[5]
                    confirm = "1"
                    source = "okx_spot"
                    inst_id = $Instrument
                    bar = $Bar
                })
        }
        $pageManifest.Add([ordered]@{
                file = "raw-pages/$($pageFile.Name)"
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $pageFile.FullName).Hash.ToLowerInvariant()
                responseRows = $pageRows.Count
                minimumTimestampMs = if ($pageTimestamps.Count -eq 0) { $null } else { ($pageTimestamps | Measure-Object -Minimum).Minimum }
                maximumTimestampMs = if ($pageTimestamps.Count -eq 0) { $null } else { ($pageTimestamps | Measure-Object -Maximum).Maximum }
            })
    }

    if ($canonicalRows.Count -eq 0) { throw "No confirmed candles in requested window" }
    $sortedRows = @($canonicalRows | Sort-Object open_time_utc)
    $timestampValues = @($sortedRows | ForEach-Object {
            [DateTimeOffset]::ParseExact(
                $_.open_time_utc,
                "yyyy-MM-ddTHH:mm:ss'Z'",
                [System.Globalization.CultureInfo]::InvariantCulture,
                ([System.Globalization.DateTimeStyles]::AssumeUniversal -bor
                    [System.Globalization.DateTimeStyles]::AdjustToUniversal)).ToUnixTimeMilliseconds()
        })
    $duplicateCount = $timestampValues.Count - @($timestampValues | Sort-Object -Unique).Count
    if ($duplicateCount -ne 0) { throw "Duplicate canonical timestamps: $duplicateCount" }
    $hourMs = [int64]3600000
    $offGridCount = @($timestampValues | Where-Object { ($_ % $hourMs) -ne 0 }).Count
    if ($offGridCount -ne 0) { throw "Off-grid 1H timestamps: $offGridCount" }
    $firstTimestampMs = [int64]$timestampValues[0]
    $lastTimestampMs = [int64]$timestampValues[-1]
    $expectedRows = [int64](($lastTimestampMs - $firstTimestampMs) / $hourMs) + 1
    $missingTimestampCount = $expectedRows - $timestampValues.Count
    if ($missingTimestampCount -ne 0) { throw "Missing 1H timestamps inside effective range: $missingTimestampCount" }

    $canonicalPath = Join-Path $stagingRoot "btc-usdt-okx-1h.csv"
    $sortedRows | Export-Csv -LiteralPath $canonicalPath -NoTypeInformation -Encoding utf8
    $canonicalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalPath).Hash.ToLowerInvariant()
    $policyHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResearchPolicyPath).Hash.ToLowerInvariant()
    $datasetBuilderHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    $analyzerPath = Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1"
    $analyzerHash = if (Test-Path -LiteralPath $analyzerPath -PathType Leaf) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $analyzerPath).Hash.ToLowerInvariant()
    } else {
        $null
    }
    $commit = (& git -C $repoRoot rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) { $commit = "UNKNOWN" }
    $dirtyLines = @(& git -C $repoRoot status --short 2>$null)
    $dirtyText = ($dirtyLines -join "`n")

    $manifest = [ordered]@{
        schemaVersion = "OKX_BTC_RESEARCH_DATASET_V1"
        status = if ($sourceMode -eq "OKX_OFFICIAL_API") { "IMMUTABLE_DATASET_READY" } else { "FIXTURE_DATASET_READY_NOT_EXTERNAL_EVIDENCE" }
        datasetId = $datasetId
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        asOfUtc = $asOfUtc
        sourceMode = $sourceMode
        officialEndpoint = "$officialBase/api/v5/market/history-candles"
        tlsCertificateValidationSkipped = $false
        request = [ordered]@{
            instrument = $Instrument
            bar = $Bar
            requestedStartUtc = $start.ToString("o")
            requestedEndUtcExclusive = $end.ToString("o")
            pageLimit = $PageLimit
            maxPages = $MaxPages
            paginationTermination = $paginationTermination
        }
        provenance = [ordered]@{
            sourceCommit = ([string]$commit).Trim()
            worktreeDirty = $dirtyLines.Count -gt 0
            worktreeStatusSha256 = Get-Sha256Text $dirtyText
            researchPolicyFile = [System.IO.Path]::GetFileName($ResearchPolicyPath)
            researchPolicySha256 = $policyHash
            datasetBuilderFile = [System.IO.Path]::GetFileName($PSCommandPath)
            datasetBuilderSha256 = $datasetBuilderHash
            analyzerSha256AtBuild = $analyzerHash
        }
        rawPages = @($pageManifest)
        canonical = [ordered]@{
            file = "btc-usdt-okx-1h.csv"
            sha256 = $canonicalHash
            rowCount = $sortedRows.Count
            firstOpenTimeUtc = $sortedRows[0].open_time_utc
            lastOpenTimeUtc = $sortedRows[-1].open_time_utc
            expectedRowsOnEffectiveLattice = $expectedRows
            missingTimestampCount = $missingTimestampCount
            duplicateTimestampCount = $duplicateCount
            offGridTimestampCount = $offGridCount
            unconfirmedRowsExcluded = $unconfirmedRows
            outOfWindowRowsExcluded = $outOfWindowRows
            ohlcInvariantFailures = 0
            confirmedBarCoveragePct = 100.0
        }
        safety = [ordered]@{
            productionDatabaseWritten = $false
            externalBackfillFlagChanged = $false
            orderSent = $false
            ocoModified = $false
            telegramSent = $false
            livePromotionAllowed = $false
        }
    }
    $manifestPath = Join-Path $stagingRoot "manifest.json"
    Write-Utf8NoBom -Path $manifestPath -Text ($manifest | ConvertTo-Json -Depth 20)
    New-Item -ItemType Directory -Path $outputRootFull -Force | Out-Null
    Move-Item -LiteralPath $stagingRoot -Destination $finalDirectory
    $summary = [ordered]@{
        status = $manifest.status
        datasetDirectory = $finalDirectory
        manifestPath = (Join-Path $finalDirectory "manifest.json")
        canonicalCsvPath = (Join-Path $finalDirectory "btc-usdt-okx-1h.csv")
        rowCount = $sortedRows.Count
        firstOpenTimeUtc = $sortedRows[0].open_time_utc
        lastOpenTimeUtc = $sortedRows[-1].open_time_utc
        canonicalSha256 = $canonicalHash
        researchPolicySha256 = $policyHash
        datasetBuilderSha256 = $datasetBuilderHash
        analyzerSha256AtBuild = $analyzerHash
    }
    Write-Output ($summary | ConvertTo-Json -Compress)
}
catch {
    if (Test-Path -LiteralPath $stagingRoot) {
        $resolvedStaging = [System.IO.Path]::GetFullPath($stagingRoot)
        $expectedPrefix = $outputRootFull.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
            [System.IO.Path]::DirectorySeparatorChar
        if (-not $resolvedStaging.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove staging directory outside output root: $resolvedStaging"
        }
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
    throw
}
