Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$verifier = Join-Path $PSScriptRoot "verify_legacy_grid_immutable_archive.ps1"
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
$tempPath = Join-Path ([IO.Path]::GetTempPath()) ("legacy-grid-archive-" + [guid]::NewGuid().ToString("N") + ".json")

function Write-Archive {
    param($Payload, [switch]$TamperHash)
    $payloadJson = ConvertTo-Json $Payload -Compress -Depth 30
    $bytes = [Text.Encoding]::UTF8.GetBytes($payloadJson)
    $sha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if ($TamperHash) { $sha = "0" * 64 }
    [ordered]@{
        schemaVersion = "LEGACY_CUSTOM_GRID_ARCHIVE_V1"
        payloadEncoding = "base64-utf8-json"
        payloadSha256 = $sha
        payloadBase64 = [Convert]::ToBase64String($bytes)
    } | ConvertTo-Json -Compress -Depth 10 | Set-Content -LiteralPath $tempPath -Encoding UTF8
}

$payload = [ordered]@{
    deployedCommit = "3401f96d723fb2e49c55a810b137dc77e1bfad5f"
    collectedAtUtc = "2026-07-22T12:00:00Z"
    collectionBoundary = "PRODUCTION_READ_ONLY_DB_SELECT_AND_OKX_GET"
    grids = @([ordered]@{ id = 10; symbol = "BTCUSDT"; closedAt = "2026-07-22T11:00:00Z"; closedPairCount = 1 })
    levels = @([ordered]@{ id = 1; gridId = 10; status = "CLOSED"; buyOrderId = "1"; sellOrderId = "2" })
    providerOrders = @(
        [ordered]@{ ordId = "1"; state = "filled" },
        [ordered]@{ ordId = "2"; state = "filled" }
    )
    providerFills = @(
        [ordered]@{ ordId = "1"; side = "buy"; fillPx = "10"; fillSz = "1"; fee = "-0.01"; feeCcy = "BTC" },
        [ordered]@{ ordId = "2"; side = "sell"; fillPx = "12"; fillSz = "0.99"; fee = "-0.02"; feeCcy = "USDT" }
    )
    reconciliation = [ordered]@{
        allLegacyGridsClosed = $true
        unsafeLevelCount = 0
        signedFeeCoverageComplete = $true
        completedPairCount = 1
        exactNetRealizedPnlUsdt = "1.86"
        attributedDustBtc = "0"
    }
}

try {
    Write-Archive $payload
    $pass = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -ArchivePath $tempPath -RequirePass 2>&1
    if ($LASTEXITCODE -ne 0 -or ($pass | Out-String) -notmatch "PASS_LEGACY_GRID_IMMUTABLE_ARCHIVE_LOCAL_COMPONENT_ONLY") {
        throw "Valid archive fixture failed: $($pass | Out-String)"
    }

    Write-Archive $payload -TamperHash
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blocked = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -ArchivePath $tempPath -RequirePass 2>&1
        $blockedExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($blockedExit -eq 0 -or ($blocked | Out-String) -notmatch "ARCHIVE_PAYLOAD_SHA256_MISMATCH") {
        throw "Tampered archive did not fail closed."
    }

    $payload.levels[0].status = "HOLDING"
    Write-Archive $payload
    try {
        $ErrorActionPreference = "Continue"
        $unsafe = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -ArchivePath $tempPath -RequirePass 2>&1
        $unsafeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($unsafeExit -eq 0 -or ($unsafe | Out-String) -notmatch "LEGACY_INVENTORY_OR_IN_FLIGHT_REMAINS") {
        throw "Unsafe-level archive did not fail closed."
    }

    $payload.levels[0].status = "CLOSED"
    $payload.grids[0].closedPairCount = 2
    Write-Archive $payload
    try {
        $ErrorActionPreference = "Continue"
        $historyGap = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -ArchivePath $tempPath -RequirePass 2>&1
        $historyGapExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($historyGapExit -eq 0 -or ($historyGap | Out-String) -notmatch "HISTORICAL_COMPLETED_PAIR_COVERAGE_INCOMPLETE") {
        throw "Historical completed-pair coverage gap did not fail closed."
    }
} finally {
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
}

Write-Host "[legacy-grid-immutable-archive-test] OK"
