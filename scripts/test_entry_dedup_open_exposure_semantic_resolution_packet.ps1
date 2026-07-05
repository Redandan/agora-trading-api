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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_open_exposure_semantic_resolution_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_PACKET",
        "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_INCOMPLETE_NOT_LIVE",
        "KEEP_OPEN_EXPOSURE_BLOCKED_PENDING_ZERO_QTY_NON_AUTO_SEMANTICS_REVIEW",
        "zeroQtyMissingOcoSemanticsReviewRequired",
        "operatorSemanticsChoiceRequired",
        "openExposureClearanceAllowed = `$false",
        "autoClearAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_open_exposure_semantic_resolution_packet",
        "semantic_resolution_review_ready=",
        "open_exposure_clearance_allowed=false",
        "auto_clear_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup open exposure semantic resolution script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-open-exposure-semantic-resolution-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $openExposurePath = Join-Path $tempDir "open-exposure.log"
    $traceabilityPath = Join-Path $tempDir "traceability.log"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    $openExposurePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        openExposureEvidence = [pscustomobject]@{
            classification = "OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION"
            exactOpportunityCount = 2
            openSignalRows = 1
            autoTradedOpenRows = 0
            nonAutoOpenRows = 1
            nonAutoZeroQtyRows = 1
            nonAutoEventRiskRows = 1
            missingOcoRows = 1
            openNotional = 0
            autoTradedExposureClear = $true
            nonAutoExposureClear = $false
            openExposureReviewRequiredBeforeMutation = $true
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    $traceabilityPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE"
        requirementTraceability = [pscustomobject]@{
            nonLiveGuardrails = [pscustomobject]@{
                allMutationFlagsFalse = $true
                orderReadiness = "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE"
            }
        }
        reviewEnvelope = [pscustomobject]@{
            liveExecutionReady = $false
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $openExposurePath -Encoding UTF8 -Value @(
        "entry_dedup_open_exposure_review_packet=$((ConvertTo-Json -Compress -Depth 8 $openExposurePacket))"
    )
    Set-Content -LiteralPath $traceabilityPath -Encoding UTF8 -Value @(
        "entry_dedup_review_only_objective_traceability_packet=$((ConvertTo-Json -Compress -Depth 8 $traceabilityPacket))"
    )
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Open Exposure Semantic Resolution`nZERO_QTY_NON_AUTO_SEMANTICS_REVIEW"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_open_exposure_semantic_resolution_packet.ps1`nzero-qty non-auto row is a semantic blocker, not order readiness"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -OpenExposureReviewLogPath $openExposurePath `
        -ObjectiveTraceabilityLogPath $traceabilityPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup open exposure semantic resolution packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_open_exposure_semantic_resolution_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE",
            "entry_dedup_open_exposure_semantic_resolution_decision=KEEP_OPEN_EXPOSURE_BLOCKED_PENDING_ZERO_QTY_NON_AUTO_SEMANTICS_REVIEW",
            "entry_dedup_open_exposure_semantic_resolution_classification=OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION",
            "entry_dedup_open_exposure_semantic_resolution_exact_opportunity_count=2",
            "entry_dedup_open_exposure_semantic_resolution_open_signal_rows=1",
            "entry_dedup_open_exposure_semantic_resolution_auto_traded_open_rows=0",
            "entry_dedup_open_exposure_semantic_resolution_non_auto_open_rows=1",
            "entry_dedup_open_exposure_semantic_resolution_non_auto_zero_qty_rows=1",
            "entry_dedup_open_exposure_semantic_resolution_non_auto_eventrisk_rows=1",
            "entry_dedup_open_exposure_semantic_resolution_missing_oco_rows=1",
            "entry_dedup_open_exposure_semantic_resolution_open_notional=0",
            "entry_dedup_open_exposure_semantic_resolution_actual_auto_exposure_clear=true",
            "entry_dedup_open_exposure_semantic_resolution_semantic_blocker_present=true",
            "entry_dedup_open_exposure_semantic_resolution_zero_qty_missing_oco_semantics_review_required=true",
            "entry_dedup_open_exposure_semantic_resolution_operator_semantics_choice_required=true",
            "entry_dedup_open_exposure_semantic_resolution_traceability_order_readiness=BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE",
            "entry_dedup_open_exposure_semantic_resolution_report_updated=true",
            "entry_dedup_open_exposure_semantic_resolution_runbook_updated=true",
            "entry_dedup_open_exposure_semantic_resolution_missing_requirements=[]",
            "semantic_resolution_review_ready=true",
            "open_exposure_clearance_allowed=false",
            "auto_clear_allowed=false",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup open exposure semantic resolution packet only"
        )) {
        Assert-Contains -Name "EntryDedup open exposure semantic resolution output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-open-exposure-semantic-resolution-packet-test] OK"
