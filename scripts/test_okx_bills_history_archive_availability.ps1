Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "check_okx_bills_history_archive_availability_ssh.ps1"
$text = Get-Content -LiteralPath $scriptPath -Raw
foreach ($marker in @('AUTHENTICATED_OKX_GET_ONLY_NO_ARCHIVE_APPLICATION', 'method="GET"', '"providerPostAttempted": False', 'fileHrefRedacted', 'ARCHIVE_NOT_PREPARED_REQUIRES_SEPARATE_PROVIDER_EXPORT_REQUEST')) {
    if ($text -notmatch [regex]::Escape($marker)) { throw "Availability script missing marker: $marker" }
}
if ($text -match 'method="POST"|signed_post|bills-history-archive"\s*,\s*data=|WriteAllBytes|DownloadFile') {
    throw "Availability script contains archive application or download behavior."
}

$fixture = [ordered]@{
    packetType = "OKX_BILLS_HISTORY_ARCHIVE_AVAILABILITY_V1"
    boundary = "AUTHENTICATED_OKX_GET_ONLY_NO_ARCHIVE_APPLICATION"
    year = "2026"
    quarter = "Q2"
    providerState = "finished"
    providerCode = "0"
    fileAvailable = $true
    fileHrefRedacted = $true
    providerGetAttempted = $true
    providerPostAttempted = $false
    providerMutationAttempted = $false
    productionMutationAttempted = $false
    status = "ARCHIVE_DOWNLOAD_READY_READ_ONLY"
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ("okx-bills-archive-" + [guid]::NewGuid().ToString("N") + ".log")
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
try {
    Set-Content -LiteralPath $temp -Encoding UTF8 -Value ("okx_bills_history_archive_availability=" + (ConvertTo-Json $fixture -Compress -Depth 10))
    $output = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLogPath $temp -RequireAvailable 2>&1
    if ($LASTEXITCODE -ne 0 -or ($output | Out-String) -notmatch "ARCHIVE_DOWNLOAD_READY_READ_ONLY") { throw "Available fixture failed." }

    $fixture.fileAvailable = $false
    $fixture.providerState = $null
    $fixture.providerCode = "51604"
    $fixture.status = "ARCHIVE_NOT_PREPARED_REQUIRES_SEPARATE_PROVIDER_EXPORT_REQUEST"
    Set-Content -LiteralPath $temp -Encoding UTF8 -Value ("okx_bills_history_archive_availability=" + (ConvertTo-Json $fixture -Compress -Depth 10))
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blocked = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLogPath $temp -RequireAvailable 2>&1
        $blockedExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($blockedExit -eq 0 -or ($blocked | Out-String) -notmatch "not available") { throw "Unavailable fixture did not fail closed." }
} finally {
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
}
Write-Host "[okx-bills-history-archive-availability-test] OK"
