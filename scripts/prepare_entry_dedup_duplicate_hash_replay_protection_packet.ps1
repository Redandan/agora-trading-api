param(
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
    [string]$CollectorReviewLogPath = "target/profit-review/entry-dedup-candidate-runtime-snapshot-collector-review-latest.log",
    [string]$ExposureOptimizerPath = "src/main/java/com/agora/service/trading/ExposureOptimizer.java",
    [string]$ExposureOptimizerTestPath = "src/test/java/com/agora/service/trading/ExposureOptimizerTest.java",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path (Split-Path -Parent $PSScriptRoot) $PathValue
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Read-FreshLog {
    param([string]$Name, [string]$PathValue, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
        AgeMinutes = $age
        Fresh = $age -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Read-SourceFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name source file not found: $resolved"
    }
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

function Has-Marker {
    param([string]$Text, [string]$Marker)
    return $Text.Contains($Marker)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($ExactOpportunityLogPath, $CollectorReviewLogPath, $ExposureOptimizerPath, $ExposureOptimizerTestPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes
$collectorLog = Read-FreshLog -Name "entry-dedup-candidate-runtime-snapshot-collector" -PathValue $CollectorReviewLogPath -MaxAge $MaxAgeMinutes
$optimizer = Read-SourceFile -Name "exposure-optimizer" -PathValue $ExposureOptimizerPath
$optimizerTest = Read-SourceFile -Name "exposure-optimizer-test" -PathValue $ExposureOptimizerTestPath

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($exactLog, $collectorLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$exactJson = Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet="
$collectorJson = Get-LastPrefixedValue -Text $collectorLog.Text -Prefix "entry_dedup_candidate_runtime_snapshot_collector_review_packet="
$exactPacket = Convert-JsonObjectOrNull $exactJson
$collectorPacket = Convert-JsonObjectOrNull $collectorJson

if ($null -eq $exactPacket) {
    Add-Missing -List $missing -Value "exact opportunity staged-add packet JSON present"
}
if ($null -eq $collectorPacket) {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector packet JSON present"
}

$exactStatus = if ($null -ne $exactPacket) { [string]$exactPacket.status } else { "UNKNOWN" }
$collectorStatus = if ($null -ne $collectorPacket) { [string]$collectorPacket.status } else { "UNKNOWN" }
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EntryDedup staged-add review packet ready"
}
if ($collectorStatus -ne "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector review packet ready"
}

$opportunities = if ($null -ne $exactPacket -and $null -ne $exactPacket.opportunities) { @($exactPacket.opportunities) } else { @() }
$rawAuditRows = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.rawAuditRows } else { 0 }
$exactOpportunityCount = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.exactOpportunityCount } else { 0 }
$exactDuplicateSuppressedRows = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.exactDuplicateSuppressedRows } else { 0 }
$opportunityKeys = @($opportunities | ForEach-Object { [string]$_.opportunityKey })
$uniqueOpportunityKeyCount = @($opportunityKeys | Sort-Object -Unique).Count
$validOpportunityKeyCount = @($opportunityKeys | Where-Object { $_ -match "^[0-9a-f]{16}$" }).Count
$opportunityAuditRowsSum = 0
$opportunityDuplicateSuppressedRowsSum = 0
$multiAuditDuplicateGroups = 0
foreach ($opportunity in $opportunities) {
    $auditRows = Get-IntValue $opportunity.auditRows
    $suppressed = Get-IntValue $opportunity.exactDuplicateSuppressedRows
    $opportunityAuditRowsSum += $auditRows
    $opportunityDuplicateSuppressedRowsSum += $suppressed
    if ($auditRows -gt 1 -and $suppressed -gt 0) {
        $multiAuditDuplicateGroups += 1
    }
}

$duplicateSuppressionCountConsistent = (
    $rawAuditRows -gt 0 -and
    $exactOpportunityCount -gt 0 -and
    $rawAuditRows - $exactOpportunityCount -eq $exactDuplicateSuppressedRows -and
    $opportunityAuditRowsSum -eq $rawAuditRows -and
    $opportunityDuplicateSuppressedRowsSum -eq $exactDuplicateSuppressedRows
)
$opportunityKeysUnique = ($opportunityKeys.Count -gt 0 -and $uniqueOpportunityKeyCount -eq $opportunityKeys.Count)
$opportunityKeysValid = ($opportunityKeys.Count -gt 0 -and $validOpportunityKeyCount -eq $opportunityKeys.Count)

if ($exactOpportunityCount -lt 1) {
    Add-Missing -List $missing -Value "exact EntryDedup opportunities present"
}
if (-not $duplicateSuppressionCountConsistent) {
    Add-Missing -List $missing -Value "duplicate suppressed count matches raw minus exact opportunities and opportunity sums"
}
if (-not $opportunityKeysUnique) {
    Add-Missing -List $missing -Value "opportunity keys unique"
}
if (-not $opportunityKeysValid) {
    Add-Missing -List $missing -Value "opportunity keys are 16-char lowercase hex"
}
if ($exactDuplicateSuppressedRows -gt 0 -and $multiAuditDuplicateGroups -lt 1) {
    Add-Missing -List $missing -Value "suppressed duplicate rows have at least one multi-audit opportunity group"
}

$optimizerRequiredMarkers = @(
    "duplicateCandidateHash",
    "replayCandidateId",
    '"edsr1_"',
    "shortHash(",
    "strategy.getId(), symbol, intervalCode, barOpenTime",
    "plain(entry), plain(tp), plain(sl)",
    "round(expectedR), round(minExpectedR)"
)
$optimizerMissingMarkers = @($optimizerRequiredMarkers | Where-Object { -not (Has-Marker -Text $optimizer.Text -Marker $_) })
foreach ($marker in $optimizerMissingMarkers) {
    Add-Missing -List $missing -Value "ExposureOptimizer marker present: $marker"
}

$testRequiredMarkers = @(
    "duplicateCandidateHash",
    "hasSize(24)",
    "replayCandidateId",
    'startsWith("edsr1_")'
)
$testMissingMarkers = @($testRequiredMarkers | Where-Object { -not (Has-Marker -Text $optimizerTest.Text -Marker $_) })
foreach ($marker in $testMissingMarkers) {
    Add-Missing -List $missing -Value "ExposureOptimizerTest marker present: $marker"
}

$collectorContextKeys = if ($null -ne $collectorPacket -and $null -ne $collectorPacket.proposedCollectorContextKeys) {
    @($collectorPacket.proposedCollectorContextKeys | ForEach-Object { [string]$_ })
} else {
    @()
}
$collectorHasDuplicateHash = $collectorContextKeys -contains "duplicateCandidateHash"
$collectorHasReplayCandidateId = $collectorContextKeys -contains "replayCandidateId"
if (-not $collectorHasDuplicateHash) {
    Add-Missing -List $missing -Value "collector contract includes duplicateCandidateHash"
}
if (-not $collectorHasReplayCandidateId) {
    Add-Missing -List $missing -Value "collector contract includes replayCandidateId"
}

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_EVIDENCE_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "REVIEW_DUPLICATE_HASH_REPLAY_PROTECTION_NOT_LIVE"
} else {
    "COLLECT_DUPLICATE_HASH_REPLAY_PROTECTION_EVIDENCE_NOT_LIVE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_PACKET"
    status = $status
    decision = $decision
    symbol = if ($null -ne $exactPacket) { [string]$exactPacket.symbol } else { "BTCUSDT" }
    strategyId = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.strategyId } else { 508 }
    intervalCode = if ($null -ne $exactPacket) { [string]$exactPacket.intervalCode } else { "1h" }
    sourceLogs = [ordered]@{
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
        candidateRuntimeSnapshotCollectorReview = $CollectorReviewLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh },
        [ordered]@{ name = $collectorLog.Name; ageMinutes = $collectorLog.AgeMinutes; fresh = $collectorLog.Fresh }
    )
    exactOpportunityEvidence = [ordered]@{
        status = $exactStatus
        rawAuditRows = $rawAuditRows
        exactOpportunityCount = $exactOpportunityCount
        exactDuplicateSuppressedRows = $exactDuplicateSuppressedRows
        opportunityRows = $opportunityKeys.Count
        uniqueOpportunityKeyCount = $uniqueOpportunityKeyCount
        validOpportunityKeyCount = $validOpportunityKeyCount
        opportunityAuditRowsSum = $opportunityAuditRowsSum
        opportunityDuplicateSuppressedRowsSum = $opportunityDuplicateSuppressedRowsSum
        multiAuditDuplicateGroups = $multiAuditDuplicateGroups
        duplicateSuppressionCountConsistent = $duplicateSuppressionCountConsistent
        opportunityKeysUnique = $opportunityKeysUnique
        opportunityKeysValid = $opportunityKeysValid
    }
    writePathSourceEvidence = [ordered]@{
        exposureOptimizer = $ExposureOptimizerPath
        exposureOptimizerTest = $ExposureOptimizerTestPath
        requiredOptimizerMarkers = $optimizerRequiredMarkers
        missingOptimizerMarkers = $optimizerMissingMarkers
        requiredTestMarkers = $testRequiredMarkers
        missingTestMarkers = $testMissingMarkers
        sourceMarkersPresent = ($optimizerMissingMarkers.Count -eq 0 -and $testMissingMarkers.Count -eq 0)
    }
    collectorContractEvidence = [ordered]@{
        status = $collectorStatus
        contextKeys = $collectorContextKeys
        hasDuplicateCandidateHash = $collectorHasDuplicateHash
        hasReplayCandidateId = $collectorHasReplayCandidateId
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        entryDedupPolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this packet to review duplicate-hash/replay protection only; it is not an authorization to relax EntryDedup or execute staged-add/live orders."
    notAuthorization = "read-only EntryDedup duplicate-hash replay protection packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-duplicate-hash-replay-protection-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved exact-opportunity and collector review logs plus local source files only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_duplicate_hash_replay_protection_status=$status"
Write-Host "entry_dedup_duplicate_hash_replay_protection_decision=$decision"
Write-Host "entry_dedup_duplicate_hash_replay_protection_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_duplicate_hash_replay_protection_exact_duplicate_suppressed_rows=$exactDuplicateSuppressedRows"
Write-Host "entry_dedup_duplicate_hash_replay_protection_unique_opportunity_key_count=$uniqueOpportunityKeyCount"
Write-Host "entry_dedup_duplicate_hash_replay_protection_valid_opportunity_key_count=$validOpportunityKeyCount"
Write-Host "entry_dedup_duplicate_hash_replay_protection_duplicate_suppression_count_consistent=$($duplicateSuppressionCountConsistent.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_duplicate_hash_replay_protection_opportunity_keys_unique=$($opportunityKeysUnique.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_duplicate_hash_replay_protection_opportunity_keys_valid=$($opportunityKeysValid.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_duplicate_hash_replay_protection_source_markers_present=$((($optimizerMissingMarkers.Count -eq 0 -and $testMissingMarkers.Count -eq 0)).ToString().ToLowerInvariant())"
Write-Host "entry_dedup_duplicate_hash_replay_protection_collector_has_duplicate_hash=$($collectorHasDuplicateHash.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_duplicate_hash_replay_protection_collector_has_replay_candidate_id=$($collectorHasReplayCandidateId.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_duplicate_hash_replay_protection_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_duplicate_hash_replay_protection_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup duplicate-hash replay protection packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-duplicate-hash-replay-protection-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup duplicate-hash replay protection packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
