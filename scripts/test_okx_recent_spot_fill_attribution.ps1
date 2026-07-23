Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script = Join-Path $PSScriptRoot "inspect_okx_recent_spot_fill_attribution_ssh.ps1"
$text = Get-Content -LiteralPath $script -Raw
foreach ($marker in @(
    "AUTHENTICATED_OKX_GET_ONLY_NO_ORDER_DB_OR_PRODUCTION_MUTATION",
    "RECENT_PROVIDER_FILLS_HAVE_NO_PAIR_TAG_ATTRIBUTION",
    '"providerPostAttempted": False',
    '"providerOrderAttempted": False',
    '"databaseMutationAttempted": False',
    "aggregate metadata output and optional caller-memory snapshot"
)) {
    if ($text -notmatch [regex]::Escape($marker)) { throw "Recent fill inspector missing marker: $marker" }
}
if ($text -match 'method="POST"|signed_post|INSERT INTO|UPDATE bt_|DELETE FROM|systemctl|deploy\.sh') {
    throw "Recent fill inspector contains a forbidden mutation path."
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("okx-recent-fill-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
$tempPath = Join-Path $tempRoot "fixture.log"
$outputPath = Join-Path $tempRoot "snapshot.json"
$snapshotBytes = [Text.Encoding]::UTF8.GetBytes('{"schemaVersion":"OKX_SPOT_FILL_HISTORY_SNAPSHOT_V1","fills":[]}')
$snapshotSha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($snapshotBytes)).ToLowerInvariant()
$fixture = [ordered]@{
    packetType = "OKX_RECENT_SPOT_FILL_ATTRIBUTION_INSPECTION_V1"
    boundary = "AUTHENTICATED_OKX_GET_ONLY_NO_ORDER_DB_OR_PRODUCTION_MUTATION"
    serverCommit = "a" * 40
    fillRowCount = 2
    uniqueOrderIdCount = 2
    validUniqueBillTradeKeyCount = 2
    duplicateBillTradeKeyCount = 0
    signedFeeCompleteRowCount = 2
    nonemptyTagRowCount = 0
    distinctNonemptyClientOrderIdCount = 0
    clientOrderIdSpanningMultipleOrdersCount = 0
    clientOrderIdSpanningBuyAndSellCount = 0
    payloadSha256 = $snapshotSha
    payloadByteCount = $snapshotBytes.Length
    payloadBase64 = [Convert]::ToBase64String($snapshotBytes)
    providerPostAttempted = $false
    providerOrderAttempted = $false
    databaseMutationAttempted = $false
    productionMutationAttempted = $false
    status = "RECENT_PROVIDER_FILLS_HAVE_NO_PAIR_TAG_ATTRIBUTION"
}
try {
    Set-Content -LiteralPath $tempPath -Encoding UTF8 -Value ("okx_recent_spot_fill_attribution=" + (ConvertTo-Json $fixture -Compress -Depth 10))
    $output = & $script -ExpectedCommit ("a" * 40) -SourceLogPath $tempPath -OutputPath $outputPath 2>&1
    $outputText = $output | Out-String
    if ($outputText -notmatch "RECENT_PROVIDER_FILLS_HAVE_NO_PAIR_TAG_ATTRIBUTION" -or
            $outputText -match [regex]::Escape([Convert]::ToBase64String($snapshotBytes)) -or
            -not (Test-Path -LiteralPath $outputPath) -or
            ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($outputPath))).ToLowerInvariant() -cne $snapshotSha)) {
        throw "Recent fill inspector fixture failed: $($output | Out-String)"
    }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[okx-recent-spot-fill-attribution-test] OK"
