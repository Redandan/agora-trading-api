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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_mutation_blocker_handoff_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET",
        "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_INCOMPLETE_NOT_LIVE",
        "REVIEW_ENTRY_DEDUP_MUTATION_BLOCKERS_KEEP_SHADOW_ONLY",
        "SEPARATE_OPEN_EXPOSURE_REVIEW",
        "REQUEST_EXACT_OCO_ROUTE_DRY_RUN_REVIEW",
        "COLLECT_CANDIDATE_RUNTIME_SNAPSHOTS",
        "COLLECT_PRODUCTION_DAILY_CAP_MAX_LOSS_SNAPSHOTS",
        "SEPARATE_HISTORICAL_EVENT_RISK_ROW_REVIEW",
        "KEEP_MUTATIONS_BLOCKED",
        "collectorActivationAllowed = `$false",
        "runtimeEvidenceWriteAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_mutation_blocker_handoff_packet",
        "mutation_ready=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup mutation blocker handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-mutation-blocker-handoff-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $bundlePath = Join-Path $tempDir "bundle.log"
    $ocoPath = Join-Path $tempDir "oco.log"
    $exactPath = Join-Path $tempDir "exact.log"

    $blockers = @(
        "OCO_ROUTE_NOT_PROVEN_OR_MISSING:EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO",
        "CANDIDATE_RUNTIME_SNAPSHOT_NOT_CLEARED:CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
        "HISTORICAL_EVENT_RISK_ROWS_NEED_SEPARATE_REVIEW",
        "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_NOT_CLEARED:MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
    )
    $bundlePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        reviewEvidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 2
        }
        mutationReadiness = [pscustomobject]@{
            mutationReady = $false
            topMutationBlocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
            ocoRouteProofCleared = $false
            ocoRouteBlockerReason = "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
            runtimeSnapshotCoverageCleared = $false
            runtimeSnapshotBlockerReason = "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING"
            dailyCapMaxLossRuntimeStatus = "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
            historicalEventRiskRowsNeedSeparateReview = $true
            remainingMutationBlockers = $blockers
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }
    $ocoPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE"
        routeEvidence = [pscustomobject]@{
            gateOcoStatus = "BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY"
            routeProofCleared = $false
            exchangeDryRunRequired = $true
            routeBlockerReason = "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
            missingOcoRows = 1
            nonAutoZeroQtyRows = 1
            autoTradedOpenRows = 0
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityCount = 2
        openExposure = [pscustomobject]@{
            open_signal_rows = 1
            auto_traded_open_rows = 0
            non_auto_open_rows = 1
            non_auto_zero_qty_rows = 1
            non_auto_eventrisk_rows = 1
            missing_oco_rows = 1
        }
        orderAllowed = $false
    }

    Set-Content -LiteralPath $bundlePath -Encoding UTF8 -Value @(
        "entry_dedup_review_only_shadow_bundle_packet=$((ConvertTo-Json -Compress -Depth 8 $bundlePacket))"
    )
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @(
        "entry_dedup_oco_route_proof_preflight_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))"
    )
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @(
        "entry_dedup_exact_opportunity_staged_add_review_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -ShadowBundleLogPath $bundlePath `
        -OcoRoutePreflightLogPath $ocoPath `
        -ExactOpportunityLogPath $exactPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup mutation blocker handoff packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_mutation_blocker_handoff_status=READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE",
            "entry_dedup_mutation_blocker_handoff_decision=REVIEW_ENTRY_DEDUP_MUTATION_BLOCKERS_KEEP_SHADOW_ONLY",
            "entry_dedup_mutation_blocker_handoff_exact_opportunity_count=2",
            "entry_dedup_mutation_blocker_handoff_blocker_count=4",
            "entry_dedup_mutation_blocker_handoff_top_mutation_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
            "entry_dedup_mutation_blocker_handoff_open_exposure_missing_oco_rows=1",
            "entry_dedup_mutation_blocker_handoff_open_exposure_non_auto_zero_qty_rows=1",
            "entry_dedup_mutation_blocker_handoff_oco_route_proof_cleared=false",
            "entry_dedup_mutation_blocker_handoff_runtime_snapshot_cleared=false",
            "entry_dedup_mutation_blocker_handoff_daily_cap_max_loss_runtime_status=MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED",
            "entry_dedup_mutation_blocker_handoff_historical_event_risk_review_required=true",
            "SEPARATE_OPEN_EXPOSURE_REVIEW",
            "REQUEST_EXACT_OCO_ROUTE_DRY_RUN_REVIEW",
            "COLLECT_CANDIDATE_RUNTIME_SNAPSHOTS",
            "COLLECT_PRODUCTION_DAILY_CAP_MAX_LOSS_SNAPSHOTS",
            "SEPARATE_HISTORICAL_EVENT_RISK_ROW_REVIEW",
            "KEEP_MUTATIONS_BLOCKED",
            "entry_dedup_mutation_blocker_handoff_missing_requirements=[]",
            "shadow_review_ready=true",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup mutation blocker handoff only"
        )) {
        Assert-Contains -Name "EntryDedup mutation blocker handoff output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-mutation-blocker-handoff-packet-test] OK"
