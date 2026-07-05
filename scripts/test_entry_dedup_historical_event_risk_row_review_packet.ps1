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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_historical_event_risk_row_review_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_INCOMPLETE_NOT_LIVE",
        "REVIEW_HISTORICAL_NON_AUTO_EVENT_RISK_ROWS_NOT_EXECUTION",
        "HISTORICAL_EVENT_RISK_REVIEW_REQUIRED_BEFORE_MUTATION",
        "historicalEventRiskReviewRequiredBeforeMutation",
        "eventRiskOverrideAllowed = `$false",
        "historicalRowMutationAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_historical_event_risk_row_review_packet",
        "historical_event_risk_review_ready=",
        "historical_row_mutation_allowed=false",
        "event_risk_override_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup historical EventRisk row review script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-historical-event-risk-row-review-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $eventRiskPath = Join-Path $tempDir "event-risk.log"
    $openExposurePath = Join-Path $tempDir "open-exposure.log"
    $handoffPath = Join-Path $tempDir "handoff.log"

    $eventRiskPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE"
        decision = "REVIEW_CURRENT_R0_EVENT_RISK_WITH_HISTORICAL_NON_AUTO_ROWS_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        eventRiskEvidence = [pscustomobject]@{
            currentR0Clear = $true
            candidateRowsClear = $true
            historicalRowsNeedSeparateReview = $true
            globalEventRiskBlockRows = 1
            nonAutoEventRiskRows = 1
            exactOpenExposureNonAutoEventRiskRows = 1
            exactOpportunityCount = 2
        }
        reviewEnvelope = [pscustomobject]@{
            eventRiskOverrideAllowed = $false
            orderAllowed = $false
        }
    }
    $openExposurePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE"
        openExposureEvidence = [pscustomobject]@{
            exactOpportunityCount = 2
            nonAutoEventRiskRows = 1
            missingOcoRows = 1
            nonAutoZeroQtyRows = 1
        }
        reviewEnvelope = [pscustomobject]@{
            openExposureMutationAllowed = $false
            orderAllowed = $false
        }
    }
    $handoffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
        evidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 2
        }
        routeAndRuntimeEvidence = [pscustomobject]@{
            historicalEventRiskRowsNeedSeparateReview = $true
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $eventRiskPath -Encoding UTF8 -Value @(
        "entry_dedup_event_risk_control_evidence_packet=$((ConvertTo-Json -Compress -Depth 8 $eventRiskPacket))"
    )
    Set-Content -LiteralPath $openExposurePath -Encoding UTF8 -Value @(
        "entry_dedup_open_exposure_review_packet=$((ConvertTo-Json -Compress -Depth 8 $openExposurePacket))"
    )
    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @(
        "entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -EventRiskControlEvidenceLogPath $eventRiskPath `
        -OpenExposureReviewLogPath $openExposurePath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup historical EventRisk row review packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_historical_event_risk_row_review_status=READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE",
            "entry_dedup_historical_event_risk_row_review_decision=REVIEW_HISTORICAL_NON_AUTO_EVENT_RISK_ROWS_NOT_EXECUTION",
            "entry_dedup_historical_event_risk_row_review_classification=HISTORICAL_EVENT_RISK_REVIEW_REQUIRED_BEFORE_MUTATION",
            "entry_dedup_historical_event_risk_row_review_exact_opportunity_count=2",
            "entry_dedup_historical_event_risk_row_review_current_r0_clear=true",
            "entry_dedup_historical_event_risk_row_review_candidate_rows_clear=true",
            "entry_dedup_historical_event_risk_row_review_historical_rows_need_separate_review=true",
            "entry_dedup_historical_event_risk_row_review_global_event_risk_block_rows=1",
            "entry_dedup_historical_event_risk_row_review_non_auto_event_risk_rows=1",
            "entry_dedup_historical_event_risk_row_review_exact_open_exposure_non_auto_event_risk_rows=1",
            "entry_dedup_historical_event_risk_row_review_open_exposure_non_auto_eventrisk_rows=1",
            "entry_dedup_historical_event_risk_row_review_open_exposure_missing_oco_rows=1",
            "entry_dedup_historical_event_risk_row_review_open_exposure_non_auto_zero_qty_rows=1",
            "entry_dedup_historical_event_risk_row_review_required_before_mutation=true",
            "entry_dedup_historical_event_risk_row_review_missing_requirements=[]",
            "historical_event_risk_review_ready=true",
            "event_risk_override_allowed=false",
            "historical_row_mutation_allowed=false",
            "open_exposure_mutation_allowed=false",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup historical EventRisk row review packet only"
        )) {
        Assert-Contains -Name "EntryDedup historical EventRisk row review output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-historical-event-risk-row-review-packet-test] OK"
