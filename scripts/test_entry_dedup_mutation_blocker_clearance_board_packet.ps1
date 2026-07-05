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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_mutation_blocker_clearance_board_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_PACKET",
        "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_INCOMPLETE_NOT_LIVE",
        "KEEP_ENTRY_DEDUP_MUTATIONS_BLOCKED_REVIEW_REQUESTS_PACKAGED",
        "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE",
        "clearanceBoardReady",
        "orderAllowed = `$false",
        "entry_dedup_mutation_blocker_clearance_board_packet",
        "clearance_board_ready=",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup mutation blocker clearance board script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-clearance-board-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $handoffPath = Join-Path $tempDir "handoff.log"
    $openPath = Join-Path $tempDir "open.log"
    $eventRiskPath = Join-Path $tempDir "event-risk.log"
    $runtimePath = Join-Path $tempDir "runtime.log"
    $budgetPath = Join-Path $tempDir "budget.log"
    $ocoPath = Join-Path $tempDir "oco.log"

    $handoffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        evidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 2
            remainingMutationBlockers = @("OCO_ROUTE_NOT_PROVEN_OR_MISSING", "CANDIDATE_RUNTIME_SNAPSHOT_NOT_CLEARED")
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }
    $openPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE"
        openExposureEvidence = [pscustomobject]@{
            openExposureReviewRequiredBeforeMutation = $true
        }
    }
    $eventRiskPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
        historicalEventRiskReview = [pscustomobject]@{
            reviewRequiredBeforeMutation = $true
        }
    }
    $runtimePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
        requestEvidence = [pscustomobject]@{
            runtimeSnapshotCoverageCleared = $false
        }
    }
    $budgetPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
        budgetSnapshotEvidence = [pscustomobject]@{
            budgetSnapshotRuntimeCleared = $false
        }
    }
    $ocoPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
        requestEvidence = [pscustomobject]@{
            routeProofCleared = $false
            exchangeDryRunRequired = $true
        }
    }

    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @("entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))")
    Set-Content -LiteralPath $openPath -Encoding UTF8 -Value @("entry_dedup_open_exposure_review_packet=$((ConvertTo-Json -Compress -Depth 8 $openPacket))")
    Set-Content -LiteralPath $eventRiskPath -Encoding UTF8 -Value @("entry_dedup_historical_event_risk_row_review_packet=$((ConvertTo-Json -Compress -Depth 8 $eventRiskPacket))")
    Set-Content -LiteralPath $runtimePath -Encoding UTF8 -Value @("entry_dedup_runtime_snapshot_collector_activation_request_packet=$((ConvertTo-Json -Compress -Depth 8 $runtimePacket))")
    Set-Content -LiteralPath $budgetPath -Encoding UTF8 -Value @("entry_dedup_budget_snapshot_review_request_packet=$((ConvertTo-Json -Compress -Depth 8 $budgetPacket))")
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @("entry_dedup_oco_route_dry_run_request_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))")

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -OpenExposureReviewLogPath $openPath `
        -HistoricalEventRiskRowReviewLogPath $eventRiskPath `
        -RuntimeSnapshotCollectorActivationRequestLogPath $runtimePath `
        -BudgetSnapshotReviewRequestLogPath $budgetPath `
        -OcoRouteDryRunRequestLogPath $ocoPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup mutation blocker clearance board packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_mutation_blocker_clearance_board_status=READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_NOT_LIVE",
            "entry_dedup_mutation_blocker_clearance_board_decision=KEEP_ENTRY_DEDUP_MUTATIONS_BLOCKED_REVIEW_REQUESTS_PACKAGED",
            "entry_dedup_mutation_blocker_clearance_board_order_readiness=BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE",
            "entry_dedup_mutation_blocker_clearance_board_exact_opportunity_count=2",
            "entry_dedup_mutation_blocker_clearance_board_remaining_mutation_blocker_count=2",
            "entry_dedup_mutation_blocker_clearance_board_open_exposure_blocked=true",
            "entry_dedup_mutation_blocker_clearance_board_historical_event_risk_blocked=true",
            "entry_dedup_mutation_blocker_clearance_board_runtime_snapshot_blocked=true",
            "entry_dedup_mutation_blocker_clearance_board_budget_snapshot_blocked=true",
            "entry_dedup_mutation_blocker_clearance_board_oco_route_blocked=true",
            "entry_dedup_mutation_blocker_clearance_board_any_blocker_remaining=true",
            "entry_dedup_mutation_blocker_clearance_board_missing_requirements=[]",
            "clearance_board_ready=true",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup mutation blocker clearance board only"
        )) {
        Assert-Contains -Name "EntryDedup mutation blocker clearance board output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-mutation-blocker-clearance-board-packet-test] OK"
