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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_review_only_shadow_bundle_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_PACKET",
        "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_INCOMPLETE_NOT_LIVE",
        "REVIEW_ENTRY_DEDUP_SHADOW_EXPERIMENT_EVIDENCE_KEEP_MUTATIONS_BLOCKED",
        "shadowReviewReady",
        "mutationReady",
        "remainingMutationBlockers",
        "collectorActivationAllowed = `$false",
        "runtimeEvidenceWriteAllowed = `$false",
        "entry_dedup_review_only_shadow_bundle_packet",
        "shadow_review_ready=",
        "mutation_ready=",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup review-only shadow bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-shadow-bundle-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $runtimePath = Join-Path $tempDir "runtime.log"
    $collectorPath = Join-Path $tempDir "collector.log"
    $duplicatePath = Join-Path $tempDir "duplicate.log"
    $ocoPath = Join-Path $tempDir "oco.log"
    $coveragePath = Join-Path $tempDir "coverage.log"
    $eventRiskPath = Join-Path $tempDir "event-risk.log"

    $runtimePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityEvidence = [pscustomobject]@{
            exactOpportunityCount = 2
            tpHitOpportunities = 2
            slHitOpportunities = 0
            ambiguousOpportunities = 0
        }
        gateStatuses = [pscustomobject]@{
            dailyCapMaxLossBudget = "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
        }
        topReviewEvidenceGap = "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"
        topMutationBlocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
        reviewProgressAllowed = $true
        shadowEvidenceCollectorAllowed = $true
    }
    $collectorPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
        localImplementationStatus = "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    $duplicatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE"
        exactOpportunityEvidence = [pscustomobject]@{
            opportunityKeysUnique = $true
        }
        writePathSourceEvidence = [pscustomobject]@{
            sourceMarkersPresent = $true
        }
        reviewEnvelope = [pscustomobject]@{
            reviewOnly = $true
            orderAllowed = $false
        }
    }
    $ocoPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE"
        routeEvidence = [pscustomobject]@{
            routeProofCleared = $false
            routeBlockerReason = "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    $coveragePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
        collectorContractCoverage = [pscustomobject]@{
            collectorContractReady = $true
        }
        runtimeSnapshotEvidence = [pscustomobject]@{
            runtimeSnapshotCoverageCleared = $false
            runtimeSnapshotBlockerReason = "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING"
        }
        reviewEnvelope = [pscustomobject]@{
            exactEvOcoCoverageReady = $true
            orderAllowed = $false
        }
    }
    $eventRiskPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE"
        eventRiskEvidence = [pscustomobject]@{
            eventRiskEvidenceStatus = "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
        }
        reviewEnvelope = [pscustomobject]@{
            currentEventRiskR0Clear = $true
            historicalEventRiskRowsNeedSeparateReview = $true
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $runtimePath -Encoding UTF8 -Value @(
        "entry_dedup_runtime_proof_gap_packet=$((ConvertTo-Json -Compress -Depth 8 $runtimePacket))"
    )
    Set-Content -LiteralPath $collectorPath -Encoding UTF8 -Value @(
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet=$((ConvertTo-Json -Compress -Depth 8 $collectorPacket))"
    )
    Set-Content -LiteralPath $duplicatePath -Encoding UTF8 -Value @(
        "entry_dedup_duplicate_hash_replay_protection_packet=$((ConvertTo-Json -Compress -Depth 8 $duplicatePacket))"
    )
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @(
        "entry_dedup_oco_route_proof_preflight_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))"
    )
    Set-Content -LiteralPath $coveragePath -Encoding UTF8 -Value @(
        "entry_dedup_exact_ev_oco_snapshot_coverage_packet=$((ConvertTo-Json -Compress -Depth 8 $coveragePacket))"
    )
    Set-Content -LiteralPath $eventRiskPath -Encoding UTF8 -Value @(
        "entry_dedup_event_risk_control_evidence_packet=$((ConvertTo-Json -Compress -Depth 8 $eventRiskPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -RuntimeProofGapLogPath $runtimePath `
        -CollectorReviewLogPath $collectorPath `
        -DuplicateHashLogPath $duplicatePath `
        -OcoRoutePreflightLogPath $ocoPath `
        -ExactEvOcoCoverageLogPath $coveragePath `
        -EventRiskControlLogPath $eventRiskPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup review-only shadow bundle packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_review_only_shadow_bundle_status=READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE",
            "entry_dedup_review_only_shadow_bundle_decision=REVIEW_ENTRY_DEDUP_SHADOW_EXPERIMENT_EVIDENCE_KEEP_MUTATIONS_BLOCKED",
            "entry_dedup_review_only_shadow_bundle_exact_opportunity_count=2",
            "entry_dedup_review_only_shadow_bundle_tp_hit_opportunities=2",
            "entry_dedup_review_only_shadow_bundle_sl_hit_opportunities=0",
            "entry_dedup_review_only_shadow_bundle_exact_ev_oco_ready=true",
            "entry_dedup_review_only_shadow_bundle_collector_contract_ready=true",
            "entry_dedup_review_only_shadow_bundle_duplicate_hash_ready=true",
            "entry_dedup_review_only_shadow_bundle_event_risk_r0_clear=true",
            "entry_dedup_review_only_shadow_bundle_shadow_review_ready=true",
            "entry_dedup_review_only_shadow_bundle_mutation_ready=false",
            "entry_dedup_review_only_shadow_bundle_top_review_gap=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
            "entry_dedup_review_only_shadow_bundle_top_mutation_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
            "OCO_ROUTE_NOT_PROVEN_OR_MISSING:EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO",
            "CANDIDATE_RUNTIME_SNAPSHOT_NOT_CLEARED:CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
            "HISTORICAL_EVENT_RISK_ROWS_NEED_SEPARATE_REVIEW",
            "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_NOT_CLEARED:MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED",
            "entry_dedup_review_only_shadow_bundle_missing_requirements=[]",
            "shadow_review_ready=true",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup review-only shadow bundle only"
        )) {
        Assert-Contains -Name "EntryDedup review-only shadow bundle output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-review-only-shadow-bundle-packet-test] OK"
