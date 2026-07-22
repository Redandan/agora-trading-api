param(
    [Parameter(Mandatory = $true)]
    [string]$ArchivePath,
    [ValidateRange(1, 500)]
    [int]$MaxUncompressedMegabytes = 100
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) { throw "ArchivePath not found: $ArchivePath" }
$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $ArchivePath).Path)
$sha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
$maxBytes = $MaxUncompressedMegabytes * 1MB
$entries = [Collections.Generic.List[object]]::new()

function Inspect-TabularText {
    param([string]$Name, [byte[]]$Content)
    $text = [Text.Encoding]::UTF8.GetString($Content)
    $rows = @($text | ConvertFrom-Csv)
    $columns = if ($rows.Count -gt 0) { @($rows[0].PSObject.Properties.Name) } else {
        $first = ($text -split "`r?`n", 2)[0]
        if ([string]::IsNullOrWhiteSpace($first)) { @() } else { @($first -split ',') }
    }
    $normalized = @($columns | ForEach-Object { $_.Trim().ToLowerInvariant().Replace(" ", "").Replace("_", "") })
    $orderColumns = @($columns | Where-Object { $_.Trim().ToLowerInvariant().Replace(" ", "").Replace("_", "") -in @("ordid", "orderid") })
    $feeColumns = @($columns | Where-Object { $_.Trim().ToLowerInvariant().Replace(" ", "").Replace("_", "") -in @("fee", "feeccy", "feecurrency", "feeunit") })
    return [ordered]@{ name = $Name; type = "CSV"; byteCount = $Content.Length; rowCount = $rows.Count; columns = $columns; orderIdColumns = $orderColumns; feeColumns = $feeColumns; hasOrderId = $orderColumns.Count -gt 0; hasFeeEvidence = $feeColumns.Count -ge 2 }
}

$isZip = $bytes.Length -ge 4 -and $bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B -and $bytes[2] -eq 0x03 -and $bytes[3] -eq 0x04
if ($isZip) {
    $memory = [IO.MemoryStream]::new($bytes, $false)
    $zip = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $total = [int64]0
        foreach ($entry in $zip.Entries) {
            if ([string]::IsNullOrWhiteSpace($entry.Name)) { continue }
            $parts = $entry.FullName.Replace('\', '/').Split('/')
            if ([IO.Path]::IsPathRooted($entry.FullName) -or $parts -contains '..') { throw "Unsafe ZIP entry path: $($entry.FullName)" }
            if ($entry.Length -gt $maxBytes -or ($total + $entry.Length) -gt $maxBytes) { throw "ZIP uncompressed size exceeds limit." }
            $extension = [IO.Path]::GetExtension($entry.Name).ToLowerInvariant()
            if ($extension -notin @(".csv", ".txt")) { throw "Unsupported ZIP entry type: $($entry.FullName)" }
            $stream = $entry.Open(); $buffer = [IO.MemoryStream]::new()
            try { $stream.CopyTo($buffer); $content = $buffer.ToArray() } finally { $buffer.Dispose(); $stream.Dispose() }
            $entries.Add((Inspect-TabularText -Name $entry.FullName -Content $content))
            $total += $entry.Length
        }
    } finally { $zip.Dispose(); $memory.Dispose() }
} else {
    if ($bytes.Length -gt $maxBytes) { throw "Archive size exceeds inspection limit." }
    $entries.Add((Inspect-TabularText -Name ([IO.Path]::GetFileName($ArchivePath)) -Content $bytes))
}
if ($entries.Count -eq 0) { throw "Archive contains no readable tabular entry." }
$totalRows = [int64]0
$allHaveOrderId = $true
$allHaveFeeEvidence = $true
foreach ($item in $entries) {
    $totalRows += [int64]$item.rowCount
    if (-not [bool]$item.hasOrderId) { $allHaveOrderId = $false }
    if (-not [bool]$item.hasFeeEvidence) { $allHaveFeeEvidence = $false }
}
$result = [ordered]@{
    packetType = "OKX_BILLS_HISTORY_ARCHIVE_STRUCTURAL_INSPECTION_V1"
    boundary = "LOCAL_READ_ONLY_IN_MEMORY_NO_EXTRACTION"
    archiveSha256 = $sha
    archiveByteCount = $bytes.Length
    container = if ($isZip) { "ZIP" } else { "CSV_OR_TEXT" }
    entryCount = $entries.Count
    entries = @($entries)
    totalRows = $totalRows
    allEntriesHaveOrderId = $allHaveOrderId
    allEntriesHaveFeeEvidence = $allHaveFeeEvidence
    status = "ARCHIVE_CONTAINER_STRUCTURALLY_READABLE_NOT_GRID_ATTRIBUTED"
    notProven = @("46 custom Grid pair attribution", "signed-fee exact-net reconciliation", "immutable legacy Grid archive PASS")
}
Write-Output ("okx_bills_history_archive_inspection=" + (ConvertTo-Json $result -Compress -Depth 20))
Write-Output "okx_bills_history_archive_inspection_status=$($result.status)"
Write-Output "notAuthorization=local in-memory inspection only; no extraction, provider request, Production write, DB, Grid/Bot, deploy, or runtime mutation"
