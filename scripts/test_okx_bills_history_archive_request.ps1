Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "request_okx_bills_history_archive_ssh.ps1"
$text = Get-Content -LiteralPath $scriptPath -Raw
foreach ($marker in @("DRY_RUN_BY_DEFAULT_EXACT_CONFIRMATION_SINGLE_POST_NO_RETRY", "AUTHORIZE_OKX_BILLS_HISTORY_ARCHIVE_REQUEST", "EXACT_CONFIRMATION_MISMATCH", "do not retry without a fresh GET reconciliation", '"providerOrderAttempted": False', '"databaseMutationAttempted": False')) {
    if ($text -notmatch [regex]::Escape($marker)) { throw "Archive request operator missing marker: $marker" }
}
if ([regex]::Matches($text, 'method="POST"').Count -ne 1) { throw "Archive request operator must contain exactly one POST call site." }
if ($text -match "placeOrder|createGrid|stopOkxNative|INSERT INTO|UPDATE bt_|DELETE FROM|systemctl|deploy\.sh") {
    throw "Archive request operator contains an unrelated mutation path."
}

$commit = "3401f96d723fb2e49c55a810b137dc77e1bfad5f"
$confirm = "AUTHORIZE_OKX_BILLS_HISTORY_ARCHIVE_REQUEST|year=2026|quarter=Q2|commit=$commit|stateSha256=$('a' * 64)"
$fixture = [ordered]@{
    packetType = "OKX_BILLS_HISTORY_ARCHIVE_REQUEST_V1"
    boundary = "DRY_RUN_BY_DEFAULT_EXACT_CONFIRMATION_SINGLE_POST_NO_RETRY"
    year = "2026"; quarter = "Q2"; serverCommit = $commit
    providerAvailabilityCode = "51604"; fileAvailableBefore = $false
    stateSha256 = "a" * 64; requiredConfirmText = $confirm
    executeRequested = $false; providerGetAttempted = $true; providerPostAttempted = $false
    providerArchiveRequestAccepted = $false; providerOrderAttempted = $false
    databaseMutationAttempted = $false; productionMutationAttempted = $false
    blockers = @(); status = "READY_FOR_SEPARATE_EXACT_PROVIDER_ARCHIVE_REQUEST_AUTHORIZATION"
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ("okx-bills-request-" + [guid]::NewGuid().ToString("N") + ".log")
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
try {
    Set-Content -LiteralPath $temp -Encoding UTF8 -Value ("okx_bills_history_archive_request=" + (ConvertTo-Json $fixture -Compress -Depth 10))
    $output = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExpectedCommit $commit -SourceLogPath $temp 2>&1
    if ($LASTEXITCODE -ne 0 -or ($output | Out-String) -notmatch [regex]::Escape($confirm)) { throw "Archive request dry-run fixture failed." }
} finally { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
Write-Host "[okx-bills-history-archive-request-test] OK"

