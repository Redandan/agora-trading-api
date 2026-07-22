param(
    [Parameter(Mandatory = $true)]
    [string]$ArchivePath,
    [switch]$RequirePass
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Add-UniqueBlocker {
    param([Collections.Generic.List[string]]$List, [string]$Value)
    if (-not $List.Contains($Value)) { $List.Add($Value) }
}

function Decimal-OrNull {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return $null }
    $parsed = [decimal]0
    if (-not [decimal]::TryParse([string]$Value, [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function Decimal-Text {
    param([decimal]$Value)
    return $Value.ToString("0.############################", [Globalization.CultureInfo]::InvariantCulture)
}

if (-not (Test-Path -LiteralPath $ArchivePath)) { throw "ArchivePath not found: $ArchivePath" }
$envelope = Get-Content -LiteralPath $ArchivePath -Raw | ConvertFrom-Json -Depth 100
$blockers = [Collections.Generic.List[string]]::new()

if ([string]$envelope.schemaVersion -ne "LEGACY_CUSTOM_GRID_ARCHIVE_V2") {
    Add-UniqueBlocker $blockers "ARCHIVE_SCHEMA_VERSION_MISMATCH"
}
if ([string]$envelope.payloadEncoding -ne "base64-utf8-json") {
    Add-UniqueBlocker $blockers "ARCHIVE_PAYLOAD_ENCODING_MISMATCH"
}

$payloadBytes = $null
try { $payloadBytes = [Convert]::FromBase64String([string]$envelope.payloadBase64) }
catch { Add-UniqueBlocker $blockers "ARCHIVE_PAYLOAD_BASE64_INVALID" }

$payload = $null
$actualSha = $null
if ($null -ne $payloadBytes) {
    $actualSha = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($payloadBytes)).ToLowerInvariant()
    if ([string]$envelope.payloadSha256 -cne $actualSha) {
        Add-UniqueBlocker $blockers "ARCHIVE_PAYLOAD_SHA256_MISMATCH"
    }
    try { $payload = [Text.Encoding]::UTF8.GetString($payloadBytes) | ConvertFrom-Json -Depth 100 }
    catch { Add-UniqueBlocker $blockers "ARCHIVE_PAYLOAD_JSON_INVALID" }
}

$computedPairCount = 0
$computedExactNetPnl = [decimal]0
$computedDust = [decimal]0
if ($null -ne $payload) {
    if ([string]$payload.deployedCommit -notmatch "^[a-f0-9]{40}$") {
        Add-UniqueBlocker $blockers "DEPLOYED_COMMIT_INVALID"
    }
    try { [DateTimeOffset]::Parse([string]$payload.collectedAtUtc, [Globalization.CultureInfo]::InvariantCulture) | Out-Null }
    catch { Add-UniqueBlocker $blockers "COLLECTED_AT_UTC_INVALID" }
    if ([string]$payload.collectionBoundary -ne "PRODUCTION_READ_ONLY_DB_SELECT_AND_OKX_GET") {
        Add-UniqueBlocker $blockers "COLLECTION_BOUNDARY_INVALID"
    }

    $grids = @($payload.grids)
    $levels = @($payload.levels)
    $orders = @($payload.providerOrders)
    $fills = @($payload.providerFills)
    $completedPairs = @(if ($payload.PSObject.Properties.Name -contains "completedPairs") { @($payload.completedPairs) })
    $attributionRecords = @(if ($payload.PSObject.Properties.Name -contains "attributionRecords") { @($payload.attributionRecords) })
    if ($grids.Count -eq 0) { Add-UniqueBlocker $blockers "GRID_ROWS_MISSING" }
    if ($levels.Count -eq 0) { Add-UniqueBlocker $blockers "GRID_LEVEL_ROWS_MISSING" }
    if (@($grids | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.closedAt) }).Count -gt 0) {
        Add-UniqueBlocker $blockers "OPEN_LEGACY_GRID_REMAINS"
    }
    $unsafe = @("HOLDING", "SELL_FAILED", "SELL_PARTIAL", "PENDING_OKX", "SELLING_OKX")
    if (@($levels | Where-Object { $unsafe -contains ([string]$_.status).ToUpperInvariant() }).Count -gt 0) {
        Add-UniqueBlocker $blockers "LEGACY_INVENTORY_OR_IN_FLIGHT_REMAINS"
    }
    $databaseClosedPairCount = 0
    $gridIds = @{}
    foreach ($grid in $grids) {
        $gridId = 0
        if (-not [int]::TryParse([string]$grid.id, [ref]$gridId) -or $gridId -le 0 -or $gridIds.ContainsKey($gridId)) {
            Add-UniqueBlocker $blockers "GRID_ID_INVALID_OR_DUPLICATE"
        } else {
            $gridIds[$gridId] = $true
        }
        $gridClosedPairCount = 0
        if (-not [int]::TryParse([string]$grid.closedPairCount, [ref]$gridClosedPairCount) -or $gridClosedPairCount -lt 0) {
            Add-UniqueBlocker $blockers "GRID_CLOSED_PAIR_COUNT_INVALID"
        } else {
            $databaseClosedPairCount += $gridClosedPairCount
        }
    }

    $levelsById = @{}
    foreach ($level in $levels) {
        $levelId = 0
        if ([int]::TryParse([string]$level.id, [ref]$levelId) -and $levelId -gt 0 -and -not $levelsById.ContainsKey($levelId)) {
            $levelsById[$levelId] = $level
        }
    }
    $attributionRecordsById = @{}
    foreach ($record in $attributionRecords) {
        $recordId = [string]$record.recordId
        if ([string]::IsNullOrWhiteSpace($recordId) -or $attributionRecordsById.ContainsKey($recordId)) {
            Add-UniqueBlocker $blockers "ATTRIBUTION_RECORD_ID_INVALID_OR_DUPLICATE"
        } else {
            $attributionRecordsById[$recordId] = $record
        }
    }

    $ordersById = @{}
    foreach ($order in $orders) {
        $orderId = [string]$order.ordId
        if ($orderId -notmatch "^[0-9]+$") { Add-UniqueBlocker $blockers "PROVIDER_ORDER_ID_INVALID"; continue }
        if ($ordersById.ContainsKey($orderId)) { Add-UniqueBlocker $blockers "DUPLICATE_PROVIDER_ORDER_DETAIL" }
        else { $ordersById[$orderId] = $order }
    }
    $fillsByOrder = @{}
    $providerFillKeys = @{}
    foreach ($fill in $fills) {
        $orderId = [string]$fill.ordId
        $tradeId = [string]$fill.tradeId
        $billId = [string]$fill.billId
        $side = ([string]$fill.side).ToUpperInvariant()
        $price = Decimal-OrNull $fill.fillPx
        $quantity = Decimal-OrNull $fill.fillSz
        $fee = Decimal-OrNull $fill.fee
        $feeCurrency = ([string]$fill.feeCcy).ToUpperInvariant()
        if ($orderId -notmatch "^[0-9]+$" -or $tradeId -notmatch "^[0-9]+$" -or $billId -notmatch "^[0-9]+$" -or
                $side -notin @("BUY", "SELL") -or
                $null -eq $price -or $price -le 0 -or $null -eq $quantity -or $quantity -le 0 -or
                $null -eq $fee -or $feeCurrency -notin @("BTC", "USDT")) {
            Add-UniqueBlocker $blockers "PROVIDER_FILL_FIELDS_OR_SIGNED_FEE_INVALID"
            continue
        }
        $fillKey = "$billId`:$tradeId"
        if ($providerFillKeys.ContainsKey($fillKey)) {
            Add-UniqueBlocker $blockers "DUPLICATE_PROVIDER_FILL_EVIDENCE"
            continue
        }
        $providerFillKeys[$fillKey] = $true
        if (-not $fillsByOrder.ContainsKey($orderId)) { $fillsByOrder[$orderId] = [Collections.Generic.List[object]]::new() }
        $fillsByOrder[$orderId].Add($fill)
    }

    foreach ($level in $levels) {
        foreach ($property in @("buyOrderId", "sellOrderId")) {
            $orderId = [string]$level.$property
            if ([string]::IsNullOrWhiteSpace($orderId)) { continue }
            if (-not $ordersById.ContainsKey($orderId)) { Add-UniqueBlocker $blockers "PROVIDER_ORDER_DETAIL_COVERAGE_INCOMPLETE" }
        }
    }

    $pairLedger = [Collections.Generic.List[object]]::new()
    if ($completedPairs.Count -gt 0) {
        foreach ($pair in $completedPairs) { $pairLedger.Add($pair) }
    } else {
        foreach ($level in $levels) {
            $buyOrderId = [string]$level.buyOrderId
            $sellOrderId = [string]$level.sellOrderId
            if (([string]$level.status).ToUpperInvariant() -ne "CLOSED" -or
                    [string]::IsNullOrWhiteSpace($buyOrderId) -or [string]::IsNullOrWhiteSpace($sellOrderId)) { continue }
            $pairLedger.Add([pscustomobject]@{
                pairKey = "legacy-level:$([string]$level.id)"
                gridId = $level.gridId
                buyOrderIds = @($buyOrderId)
                sellOrderIds = @($sellOrderId)
                attributionMethod = "DATABASE_PRESERVED_ORDER_IDS"
                databaseLevelId = $level.id
            })
        }
    }

    $allowedAttributionMethods = @(
        "DATABASE_PRESERVED_ORDER_IDS",
        "PROVIDER_TAG_PAIR_KEY",
        "AUDIT_EXACT_ORDER_IDS"
    )
    $pairKeys = @{}
    $usedOrderIds = @{}
    foreach ($pair in $pairLedger) {
        $pairKey = if ($pair.PSObject.Properties.Name -contains "pairKey") { [string]$pair.pairKey } else { "" }
        if ([string]::IsNullOrWhiteSpace($pairKey) -or $pairKeys.ContainsKey($pairKey)) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_KEY_INVALID_OR_DUPLICATE"
        } else {
            $pairKeys[$pairKey] = $true
        }
        $pairGridId = 0
        $pairGridIdText = if ($pair.PSObject.Properties.Name -contains "gridId") { [string]$pair.gridId } else { "" }
        if (-not [int]::TryParse($pairGridIdText, [ref]$pairGridId) -or -not $gridIds.ContainsKey($pairGridId)) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_GRID_ID_INVALID"
        }
        $attributionMethod = if ($pair.PSObject.Properties.Name -contains "attributionMethod") { ([string]$pair.attributionMethod).ToUpperInvariant() } else { "" }
        if ($attributionMethod -notin $allowedAttributionMethods) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_ATTRIBUTION_NOT_EXACT"
        }
        $buyOrderIds = @(if ($pair.PSObject.Properties.Name -contains "buyOrderIds") {
            @($pair.buyOrderIds | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        })
        $sellOrderIds = @(if ($pair.PSObject.Properties.Name -contains "sellOrderIds") {
            @($pair.sellOrderIds | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        })
        if ($buyOrderIds.Count -eq 0 -or $sellOrderIds.Count -eq 0) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_ORDER_IDS_MISSING"
            continue
        }
        $pairOrderIdsValid = $true
        foreach ($orderId in @($buyOrderIds) + @($sellOrderIds)) {
            if ($orderId -notmatch "^[0-9]+$") {
                Add-UniqueBlocker $blockers "COMPLETED_PAIR_ORDER_ID_INVALID"
                $pairOrderIdsValid = $false
                continue
            }
            if ($usedOrderIds.ContainsKey($orderId)) {
                Add-UniqueBlocker $blockers "COMPLETED_PAIR_ORDER_ID_REUSED"
                $pairOrderIdsValid = $false
            } else {
                $usedOrderIds[$orderId] = $pairKey
            }
            if (-not $fillsByOrder.ContainsKey($orderId)) {
                Add-UniqueBlocker $blockers "COMPLETED_PAIR_FILL_COVERAGE_INCOMPLETE"
                $pairOrderIdsValid = $false
            }
        }
        if (-not $pairOrderIdsValid) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_FILL_COVERAGE_INCOMPLETE"
            continue
        }

        $attributionValid = $true
        if ($attributionMethod -eq "DATABASE_PRESERVED_ORDER_IDS") {
            $databaseLevelId = 0
            if ($pair.PSObject.Properties.Name -notcontains "databaseLevelId" -or
                    -not [int]::TryParse([string]$pair.databaseLevelId, [ref]$databaseLevelId) -or
                    -not $levelsById.ContainsKey($databaseLevelId) -or
                    $buyOrderIds.Count -ne 1 -or $sellOrderIds.Count -ne 1) {
                $attributionValid = $false
            } else {
                $sourceLevel = $levelsById[$databaseLevelId]
                if ([int]$sourceLevel.gridId -ne $pairGridId -or
                        [string]$sourceLevel.buyOrderId -cne $buyOrderIds[0] -or
                        [string]$sourceLevel.sellOrderId -cne $sellOrderIds[0]) {
                    $attributionValid = $false
                }
            }
        } elseif ($attributionMethod -eq "PROVIDER_TAG_PAIR_KEY") {
            $attributionValue = if ($pair.PSObject.Properties.Name -contains "attributionValue") { [string]$pair.attributionValue } else { "" }
            if ([string]::IsNullOrWhiteSpace($attributionValue) -or $attributionValue -cne $pairKey) {
                $attributionValid = $false
            } else {
                foreach ($orderId in @($buyOrderIds) + @($sellOrderIds)) {
                    foreach ($fill in $fillsByOrder[$orderId]) {
                        if ($fill.PSObject.Properties.Name -notcontains "tag" -or [string]$fill.tag -cne $attributionValue) { $attributionValid = $false }
                    }
                }
            }
        } elseif ($attributionMethod -eq "AUDIT_EXACT_ORDER_IDS") {
            $auditRecordId = if ($pair.PSObject.Properties.Name -contains "auditRecordId") { [string]$pair.auditRecordId } else { "" }
            if ([string]::IsNullOrWhiteSpace($auditRecordId) -or -not $attributionRecordsById.ContainsKey($auditRecordId)) {
                $attributionValid = $false
            } else {
                $record = $attributionRecordsById[$auditRecordId]
                $recordBuyIds = @(if ($record.PSObject.Properties.Name -contains "buyOrderIds") { @($record.buyOrderIds | ForEach-Object { [string]$_ }) })
                $recordSellIds = @(if ($record.PSObject.Properties.Name -contains "sellOrderIds") { @($record.sellOrderIds | ForEach-Object { [string]$_ }) })
                if ($record.PSObject.Properties.Name -notcontains "pairKey" -or $record.PSObject.Properties.Name -notcontains "gridId" -or
                        [string]$record.pairKey -cne $pairKey -or [int]$record.gridId -ne $pairGridId -or
                        ($recordBuyIds -join ",") -cne ($buyOrderIds -join ",") -or
                        ($recordSellIds -join ",") -cne ($sellOrderIds -join ",")) {
                    $attributionValid = $false
                }
            }
        }
        if (-not $attributionValid) {
            Add-UniqueBlocker $blockers "COMPLETED_PAIR_ATTRIBUTION_EVIDENCE_INVALID"
        }

        $buyBase = [decimal]0; $buyQuote = [decimal]0; $sellBaseDisposed = [decimal]0; $sellQuote = [decimal]0
        foreach ($buyOrderId in $buyOrderIds) {
            foreach ($fill in $fillsByOrder[$buyOrderId]) {
                if (([string]$fill.side).ToUpperInvariant() -ne "BUY") { Add-UniqueBlocker $blockers "BUY_ORDER_CONTAINS_NON_BUY_FILL"; continue }
                $price = Decimal-OrNull $fill.fillPx; $quantity = Decimal-OrNull $fill.fillSz; $fee = Decimal-OrNull $fill.fee
                $buyBase += $quantity; $buyQuote += $price * $quantity
                if (([string]$fill.feeCcy).ToUpperInvariant() -eq "BTC") { $buyBase += $fee } else { $buyQuote -= $fee }
            }
        }
        foreach ($sellOrderId in $sellOrderIds) {
            foreach ($fill in $fillsByOrder[$sellOrderId]) {
                if (([string]$fill.side).ToUpperInvariant() -ne "SELL") { Add-UniqueBlocker $blockers "SELL_ORDER_CONTAINS_NON_SELL_FILL"; continue }
                $price = Decimal-OrNull $fill.fillPx; $quantity = Decimal-OrNull $fill.fillSz; $fee = Decimal-OrNull $fill.fee
                $sellBaseDisposed += $quantity; $sellQuote += $price * $quantity
                if (([string]$fill.feeCcy).ToUpperInvariant() -eq "BTC") { $sellBaseDisposed -= $fee } else { $sellQuote += $fee }
            }
        }
        if ($buyBase -le 0 -or $sellBaseDisposed -le 0 -or $sellBaseDisposed -gt $buyBase) {
            Add-UniqueBlocker $blockers "PAIR_BASE_QUANTITY_RECONCILIATION_INVALID"
            continue
        }
        $costBasis = ($buyQuote / $buyBase) * $sellBaseDisposed
        $computedExactNetPnl += $sellQuote - $costBasis
        $computedDust += $buyBase - $sellBaseDisposed
        $computedPairCount++
    }

    $reconciliation = $payload.reconciliation
    if ($null -eq $reconciliation) {
        Add-UniqueBlocker $blockers "RECONCILIATION_MISSING"
    } else {
        if (-not [bool]$reconciliation.allLegacyGridsClosed) { Add-UniqueBlocker $blockers "RECONCILIATION_GRIDS_NOT_CLOSED" }
        if ([int]$reconciliation.unsafeLevelCount -ne 0) { Add-UniqueBlocker $blockers "RECONCILIATION_UNSAFE_LEVEL_COUNT_NONZERO" }
        if (-not [bool]$reconciliation.signedFeeCoverageComplete) { Add-UniqueBlocker $blockers "RECONCILIATION_SIGNED_FEE_INCOMPLETE" }
        if ([int]$reconciliation.completedPairCount -ne $computedPairCount) { Add-UniqueBlocker $blockers "RECONCILIATION_PAIR_COUNT_MISMATCH" }
        if ($databaseClosedPairCount -ne $computedPairCount) { Add-UniqueBlocker $blockers "HISTORICAL_COMPLETED_PAIR_COVERAGE_INCOMPLETE" }
        $claimedPnl = Decimal-OrNull $reconciliation.exactNetRealizedPnlUsdt
        $claimedDust = Decimal-OrNull $reconciliation.attributedDustBtc
        if ($null -eq $claimedPnl -or $claimedPnl -ne $computedExactNetPnl) { Add-UniqueBlocker $blockers "RECONCILIATION_EXACT_NET_PNL_MISMATCH" }
        if ($null -eq $claimedDust -or $claimedDust -ne $computedDust) { Add-UniqueBlocker $blockers "RECONCILIATION_ATTRIBUTED_DUST_MISMATCH" }
    }
}

$result = [ordered]@{
    packetType = "LEGACY_GRID_IMMUTABLE_ARCHIVE_VERIFICATION_V2"
    boundary = "LOCAL_READ_ONLY_ARCHIVE_VERIFICATION"
    archivePath = (Resolve-Path -LiteralPath $ArchivePath).Path.Replace('\', '/')
    payloadSha256 = $actualSha
    computedCompletedPairCount = $computedPairCount
    computedExactNetRealizedPnlUsdt = Decimal-Text $computedExactNetPnl
    computedAttributedDustBtc = Decimal-Text $computedDust
    blockers = @($blockers)
    status = if ($blockers.Count -eq 0) { "PASS_LEGACY_GRID_IMMUTABLE_ARCHIVE_LOCAL_COMPONENT_ONLY" } else { "FAIL_LEGACY_GRID_IMMUTABLE_ARCHIVE" }
    productionMutationPerformed = $false
    notProven = @("Production post-export reconciliation", "PASS_CUSTOM_GRID_RUNTIME_REMOVED", "PASS_CUSTOM_GRID_FULLY_DELETED", "MIGRATION_ACCEPTED")
}
Write-Output ("legacy_grid_immutable_archive_verification=" + (ConvertTo-Json $result -Compress -Depth 20))
Write-Output "legacy_grid_immutable_archive_status=$($result.status)"
Write-Output "notAuthorization=local archive verification only; no Production DB/provider/Grid/Bot/deploy/runtime/table mutation"
if ($RequirePass -and $blockers.Count -gt 0) { throw "Legacy Grid archive has $($blockers.Count) blocker(s)." }
