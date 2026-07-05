Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_duplicate_hash_replay_protection_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_PACKET",
        "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_EVIDENCE_INCOMPLETE_NOT_LIVE",
        "duplicateCandidateHash",
        "replayCandidateId",
        "edsr1_",
        "duplicateSuppressionCountConsistent",
        "opportunityKeysUnique",
        "opportunityKeysValid",
        "sourceMarkersPresent",
        "runtimeEvidenceWriteAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_duplicate_hash_replay_protection_packet",
        "order_allowed=false",
        "runtime_evidence_write_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup duplicate-hash replay protection script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-duplicate-hash-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $exactPath = Join-Path $tempDir "exact.log"
    $collectorPath = Join-Path $tempDir "collector.log"
    $optimizerPath = Join-Path $tempDir "ExposureOptimizer.java"
    $optimizerTestPath = Join-Path $tempDir "ExposureOptimizerTest.java"

    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        rawAuditRows = 4
        exactOpportunityCount = 2
        exactDuplicateSuppressedRows = 2
        opportunities = @(
            [pscustomobject]@{
                opportunityKey = "0123456789abcdef"
                auditRows = 3
                exactDuplicateSuppressedRows = 2
                firstAuditId = 101
                lastAuditId = 103
            },
            [pscustomobject]@{
                opportunityKey = "fedcba9876543210"
                auditRows = 1
                exactDuplicateSuppressedRows = 0
                firstAuditId = 104
                lastAuditId = 104
            }
        )
        orderAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        livePolicyChangeAllowed = $false
    }
    $collectorPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
        proposedCollectorContextKeys = @(
            "entryPrice",
            "tpPrice",
            "slPrice",
            "duplicateCandidateHash",
            "replayCandidateId"
        )
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
            runtimeEvidenceWriteAllowed = $false
        }
    }
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @(
        "[entry-dedup-exact-opportunity-staged-add-review] read-only production evidence check",
        "entry_dedup_exact_opportunity_staged_add_review_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))"
    )
    Set-Content -LiteralPath $collectorPath -Encoding UTF8 -Value @(
        "[entry-dedup-candidate-runtime-snapshot-collector-review-packet] read-only packet",
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet=$((ConvertTo-Json -Compress -Depth 8 $collectorPacket))"
    )
    Set-Content -LiteralPath $optimizerPath -Encoding UTF8 -Value @(
        'ctx.put("duplicateCandidateHash", hash);',
        'ctx.put("replayCandidateId", "edsr1_" + hash);',
        'String hash = shortHash("edsr1", strategy.getId(), symbol, intervalCode, barOpenTime,',
        'plain(entry), plain(tp), plain(sl), round(expectedR), round(minExpectedR));'
    )
    Set-Content -LiteralPath $optimizerTestPath -Encoding UTF8 -Value @(
        'assertThat(result.context().get("duplicateCandidateHash")).asString().hasSize(24);',
        'assertThat(result.context().get("replayCandidateId")).asString().startsWith("edsr1_");'
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -ExactOpportunityLogPath $exactPath `
        -CollectorReviewLogPath $collectorPath `
        -ExposureOptimizerPath $optimizerPath `
        -ExposureOptimizerTestPath $optimizerTestPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup duplicate-hash replay protection packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_duplicate_hash_replay_protection_status=READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE",
            "entry_dedup_duplicate_hash_replay_protection_exact_opportunity_count=2",
            "entry_dedup_duplicate_hash_replay_protection_exact_duplicate_suppressed_rows=2",
            "entry_dedup_duplicate_hash_replay_protection_unique_opportunity_key_count=2",
            "entry_dedup_duplicate_hash_replay_protection_valid_opportunity_key_count=2",
            "entry_dedup_duplicate_hash_replay_protection_duplicate_suppression_count_consistent=true",
            "entry_dedup_duplicate_hash_replay_protection_opportunity_keys_unique=true",
            "entry_dedup_duplicate_hash_replay_protection_opportunity_keys_valid=true",
            "entry_dedup_duplicate_hash_replay_protection_source_markers_present=true",
            "entry_dedup_duplicate_hash_replay_protection_collector_has_duplicate_hash=true",
            "entry_dedup_duplicate_hash_replay_protection_collector_has_replay_candidate_id=true",
            "entry_dedup_duplicate_hash_replay_protection_missing_requirements=[]",
            "runtime_evidence_write_allowed=false",
            "entry_dedup_policy_change_allowed=false",
            "staged_add_execution_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup duplicate-hash replay protection packet only"
        )) {
        Assert-Contains -Name "EntryDedup duplicate-hash replay protection output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-duplicate-hash-replay-protection-packet-test] OK"
