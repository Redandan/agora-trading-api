Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$downloadScript = Join-Path $PSScriptRoot "download_okx_bills_history_archive_ssh.ps1"
$inspectScript = Join-Path $PSScriptRoot "inspect_okx_bills_history_archive.ps1"
$downloadText = Get-Content -LiteralPath $downloadScript -Raw
foreach ($marker in @("AUTHENTICATED_STATUS_GET_AND_PROVIDER_FILE_GET_NO_SERVER_WRITE", '"providerPostAttempted": False', '"productionFilesystemWriteAttempted": False', "fileHrefRedacted", "OutputPath already exists; refusing overwrite")) {
    if ($downloadText -notmatch [regex]::Escape($marker)) { throw "Download script missing marker: $marker" }
}
if ($downloadText -match 'method="POST"|signed_post|INSERT INTO|UPDATE bt_|DELETE FROM|systemctl|deploy\.sh') { throw "Download script contains a forbidden mutation path." }

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("okx-bills-download-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$fixtureLog = Join-Path $tempRoot "fixture.log"
$outputPath = Join-Path $tempRoot "q2.zip"
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
try {
    $memory = [IO.MemoryStream]::new()
    $zip = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Create, $true)
    $entry = $zip.CreateEntry("bills.csv")
    $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
    $writer.Write("ordId,fillPx,fillSz,fee,feeCcy`n1,10,1,-0.01,BTC`n")
    $writer.Dispose(); $zip.Dispose()
    $payload = $memory.ToArray(); $memory.Dispose()
    $sha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payload)).ToLowerInvariant()
    $packet = [ordered]@{
        packetType = "OKX_BILLS_HISTORY_ARCHIVE_DOWNLOAD_V1"; boundary = "AUTHENTICATED_STATUS_GET_AND_PROVIDER_FILE_GET_NO_SERVER_WRITE"
        year = "2026"; quarter = "Q2"; byteCount = $payload.Length; sha256 = $sha; payloadBase64 = [Convert]::ToBase64String($payload)
        providerPostAttempted = $false; providerOrderAttempted = $false; databaseMutationAttempted = $false; productionFilesystemWriteAttempted = $false
        status = "DOWNLOADED_TO_CALLER_MEMORY_READY_FOR_LOCAL_HASHED_WRITE"
    }
    Set-Content -LiteralPath $fixtureLog -Encoding UTF8 -Value ("okx_bills_history_archive_download=" + (ConvertTo-Json $packet -Compress -Depth 10))
    $download = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $downloadScript -ExpectedCommit ("a" * 40) -OutputPath $outputPath -SourceLogPath $fixtureLog 2>&1
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $outputPath) -or ($download | Out-String) -notmatch $sha) { throw "Download fixture failed." }
    $inspection = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $inspectScript -ArchivePath $outputPath 2>&1
    $inspectionText = $inspection | Out-String
    foreach ($marker in @("ARCHIVE_CONTAINER_STRUCTURALLY_READABLE_NOT_GRID_ATTRIBUTED", '"totalRows":1', '"allEntriesHaveOrderId":true', '"allEntriesHaveFeeEvidence":true')) {
        if ($inspectionText -notmatch [regex]::Escape($marker)) { throw "Inspection fixture missing marker: $marker output=$inspectionText" }
    }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $overwrite = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $downloadScript -ExpectedCommit ("a" * 40) -OutputPath $outputPath -SourceLogPath $fixtureLog 2>&1
        $overwriteExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($overwriteExit -eq 0 -or ($overwrite | Out-String) -notmatch "refusing overwrite") { throw "Existing output was not protected." }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "[okx-bills-history-archive-download-and-inspection-test] OK"
