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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_exact_ev_oco_snapshot_coverage_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET",
        "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_INCOMPLETE_NOT_LIVE",
        "allExactEvProxyPositive",
        "allExactPlanShapesValid",
        "collectorContractReady",
        "eventRiskEvidenceStatus",
        "runtimeSnapshotCoverageCleared = `$false",
        "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
        "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
        "exact_ev_oco_coverage_ready=",
        "runtime_snapshot_coverage_cleared=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup exact EV/OCO snapshot coverage script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-exact-ev-oco-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $exactPath = Join-Path $tempDir "exact.log"
    $syntheticPath = Join-Path $tempDir "synthetic.log"
    $collectorPath = Join-Path $tempDir "collector.log"
    $gatePath = Join-Path $tempDir "gate.log"

    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityCount = 2
        tpHitOpportunities = 2
        slHitOpportunities = 0
        ambiguousOpportunities = 0
        opportunities = @(
            [pscustomobject]@{
                opportunityKey = "0123456789abcdef"
                entry = 100
                tp = 101
                sl = 99
                outcome = "TP_HIT"
                netReturnPct = 0.8
                expectedRProxy = 0.8
            },
            [pscustomobject]@{
                opportunityKey = "fedcba9876543210"
                entry = 110
                tp = 111.1
                sl = 108.9
                outcome = "TP_HIT"
                netReturnPct = 0.8
                expectedRProxy = 0.8
            }
        )
    }
    $syntheticPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SYNTHETIC_EV_OCO_PREVIEW_PACKET"
        status = "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        candidateRows = 2
        syntheticEvProxyPassRows = 2
        validOcoPlanShapeRows = 2
        notRuntimeEvidence = $true
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
    }
    $collectorPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
        localImplementationStatus = "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
        sourceContract = [pscustomobject]@{
            missing = @()
        }
        proposedCollectorContextKeys = @(
            "entryPrice",
            "tpPrice",
            "slPrice",
            "expected_r",
            "min_expected_r",
            "ev_reason",
            "eventRiskLevel",
            "dailyLossGuard",
            "ocoCapable",
            "ocoPlanCreated",
            "orderSent=false",
            "suppressionReason=SHADOW_MODE",
            "duplicateCandidateHash",
            "dailyCapSnapshot",
            "maxLossSnapshot",
            "replayCandidateId"
        )
        orderAllowed = $false
        runtimeEvidenceWriteAllowed = $false
    }
    $gatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET"
        status = "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE"
        gateStatuses = [pscustomobject]@{
            eventRiskControl = "CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
        }
        runtimeMcpEvidence = [pscustomobject]@{
            eventRiskOk = $true
            eventRiskLevel = "R0"
            eventRiskPolicy = "new entries allowed"
        }
        dbEvidence = [pscustomobject]@{
            nonAutoEventRiskRows = 1
            globalGateRows = [pscustomobject]@{
                eventrisk_block_rows = 1
            }
            candidateGateRows = [pscustomobject]@{
                eventRiskBlockRows = 0
                runtimeEvidenceRows = 2
                runtimeEvEvaluatedRows = 2
                runtimeEntryPlanRows = 0
                runtimeOcoPlanRows = 2
                runtimeOrderSentRows = 0
            }
        }
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
    }
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @(
        "entry_dedup_exact_opportunity_staged_add_review_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))"
    )
    Set-Content -LiteralPath $syntheticPath -Encoding UTF8 -Value @(
        "entry_dedup_synthetic_ev_oco_preview_packet=$((ConvertTo-Json -Compress -Depth 8 $syntheticPacket))"
    )
    Set-Content -LiteralPath $collectorPath -Encoding UTF8 -Value @(
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet=$((ConvertTo-Json -Compress -Depth 8 $collectorPacket))"
    )
    Set-Content -LiteralPath $gatePath -Encoding UTF8 -Value @(
        "entry_dedup_semantics_gate_preflight_packet=$((ConvertTo-Json -Compress -Depth 8 $gatePacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -ExactOpportunityLogPath $exactPath `
        -SyntheticPreviewLogPath $syntheticPath `
        -CollectorReviewLogPath $collectorPath `
        -GatePreflightLogPath $gatePath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup exact EV/OCO snapshot coverage packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_exact_ev_oco_snapshot_coverage_status=READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE",
            "entry_dedup_exact_ev_oco_snapshot_coverage_exact_count=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_exact_positive_ev_proxy_rows=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_exact_plan_shape_rows=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_all_exact_ev_proxy_positive=true",
            "entry_dedup_exact_ev_oco_snapshot_coverage_all_exact_plan_shapes_valid=true",
            "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_candidate_rows=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_ev_proxy_pass_rows=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_valid_oco_plan_shape_rows=2",
            "entry_dedup_exact_ev_oco_snapshot_coverage_collector_contract_ready=true",
            "entry_dedup_exact_ev_oco_snapshot_coverage_event_risk_status=CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
            "entry_dedup_exact_ev_oco_snapshot_coverage_event_risk_level=R0",
            "entry_dedup_exact_ev_oco_snapshot_coverage_candidate_event_risk_block_rows=0",
            "entry_dedup_exact_ev_oco_snapshot_coverage_runtime_snapshot_cleared=false",
            "entry_dedup_exact_ev_oco_snapshot_coverage_runtime_snapshot_blocker_reason=CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
            "entry_dedup_exact_ev_oco_snapshot_coverage_missing_requirements=[]",
            "exact_ev_oco_coverage_ready=true",
            "runtime_snapshot_coverage_cleared=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup exact EV/OCO snapshot coverage packet only"
        )) {
        Assert-Contains -Name "EntryDedup exact EV/OCO snapshot coverage output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-exact-ev-oco-snapshot-coverage-packet-test] OK"
