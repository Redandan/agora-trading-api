Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "prepare_legacy_grid_archive_source_gap_ssh.ps1"
$scriptText = Get-Content -LiteralPath $scriptPath -Raw
foreach ($required in @('PRODUCTION_READ_ONLY_DB_SELECT_HEALTH_AND_GIT_ONLY', 'RECYCLED_LEVEL_ORDER_IDS_CLEARED', 'HISTORICAL_PROVIDER_ORDER_ID_COVERAGE_INCOMPLETE', '"providerRequestAttempted": False', '"databaseMutationAttempted": False')) {
    if ($scriptText -notmatch [regex]::Escape($required)) { throw "Source-gap script missing marker: $required" }
}
if ($scriptText -cmatch "\b(?:INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE)\b|signed_(?:post|delete)|tools/call") {
    throw "Source-gap script contains a forbidden mutation path."
}

$commit = "3401f96d723fb2e49c55a810b137dc77e1bfad5f"
$ready = [ordered]@{
    packetType = "LEGACY_GRID_ARCHIVE_SOURCE_GAP_PREFLIGHT_V1"
    boundary = "PRODUCTION_READ_ONLY_DB_SELECT_HEALTH_AND_GIT_ONLY"
    expectedCommit = $commit
    serverHeadCommit = $commit
    databaseClosedPairCount = 1
    survivingCompletedPairOrderIdCount = 1
    gridBuyAuditCount = 1
    gridSellAuditCount = 1
    providerRequestAttempted = $false
    databaseMutationAttempted = $false
    productionMutationAttempted = $false
    blockers = @()
    status = "READY_FOR_READ_ONLY_PROVIDER_ARCHIVE_EXPORT"
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ("legacy-grid-archive-gap-" + [guid]::NewGuid().ToString("N") + ".log")
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
try {
    Set-Content -LiteralPath $temp -Encoding UTF8 -Value ("legacy_grid_archive_source_gap=" + (ConvertTo-Json $ready -Compress -Depth 10))
    $readyOutput = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExpectedCommit $commit -SourceLogPath $temp -RequireReady 2>&1
    if ($LASTEXITCODE -ne 0 -or ($readyOutput | Out-String) -notmatch "READY_FOR_READ_ONLY_PROVIDER_ARCHIVE_EXPORT") {
        throw "Ready source-gap fixture failed."
    }

    $blocked = $ready | ConvertTo-Json -Depth 10 | ConvertFrom-Json -Depth 10
    $blocked.blockers = @("HISTORICAL_PROVIDER_ORDER_ID_COVERAGE_INCOMPLETE")
    $blocked.status = "BLOCKED_HISTORICAL_ARCHIVE_SOURCE_GAP"
    Set-Content -LiteralPath $temp -Encoding UTF8 -Value ("legacy_grid_archive_source_gap=" + (ConvertTo-Json $blocked -Compress -Depth 10))
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExpectedCommit $commit -SourceLogPath $temp -RequireReady 2>&1
        $blockedExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($blockedExit -eq 0 -or ($blockedOutput | Out-String) -notmatch "HISTORICAL_PROVIDER_ORDER_ID_COVERAGE_INCOMPLETE") {
        throw "Blocked source-gap fixture did not fail closed."
    }
} finally {
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
}
Write-Host "[legacy-grid-archive-source-gap-test] OK"
