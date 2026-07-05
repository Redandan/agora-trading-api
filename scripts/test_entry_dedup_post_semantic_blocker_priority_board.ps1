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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_post_semantic_blocker_priority_board.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_PACKET",
        "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_INCOMPLETE_NOT_LIVE",
        "KEEP_ENTRY_DEDUP_BLOCKED_AND_PRIORITIZE_REVIEW_ONLY_CLEARANCE_SEQUENCE",
        "OpenExposureOperatorChoiceLogPath",
        "BLOCKED_OPERATOR_CHOICE_READY_NOT_AUTHORIZED",
        "openExposureOperatorChoiceReady",
        "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_ready=",
        "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS",
        "CANDIDATE_RUNTIME_ENTRY_PLAN_SNAPSHOT",
        "DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT",
        "EXACT_OCO_ROUTE_PROOF",
        "HISTORICAL_EVENT_RISK_ROW",
        "priorityBoardReady",
        "liveExecutionReady = `$false",
        "orderAllowed = `$false",
        "entry_dedup_post_semantic_blocker_priority_board_packet",
        "priority_board_ready=",
        "live_execution_ready=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup post-semantic blocker priority board script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-post-semantic-board-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $semanticPath = Join-Path $tempDir "semantic.log"
    $operatorChoicePath = Join-Path $tempDir "operator-choice.log"
    $runtimePath = Join-Path $tempDir "runtime.log"
    $budgetPath = Join-Path $tempDir "budget.log"
    $ocoPath = Join-Path $tempDir "oco.log"
    $historicalPath = Join-Path $tempDir "historical.log"
    $traceabilityPath = Join-Path $tempDir "traceability.log"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    $semanticPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        semanticEvidence = [pscustomobject]@{
            exactOpportunityCount = 2
            actualAutoExposureClear = $true
            operatorSemanticsChoiceRequired = $true
        }
    }
    $runtimePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
        requestEvidence = [pscustomobject]@{
            runtimeSnapshotCoverageCleared = $false
            candidateRuntimeEntryPlanRows = 0
        }
    }
    $operatorChoicePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
        proposedChange = [pscustomobject]@{
            confirmText = "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"
            recommendedReviewChoice = "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION"
        }
    }
    $budgetPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
        budgetSnapshotEvidence = [pscustomobject]@{
            budgetSnapshotRuntimeCleared = $false
            contractIncludesDailyCapSnapshot = $true
            contractIncludesMaxLossSnapshot = $true
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
    $historicalPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
        historicalEventRiskReview = [pscustomobject]@{
            reviewRequiredBeforeMutation = $true
            currentR0Clear = $true
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
    }

    Set-Content -LiteralPath $semanticPath -Encoding UTF8 -Value @("entry_dedup_open_exposure_semantic_resolution_packet=$((ConvertTo-Json -Compress -Depth 8 $semanticPacket))")
    Set-Content -LiteralPath $operatorChoicePath -Encoding UTF8 -Value @("entry_dedup_open_exposure_operator_choice_packet=$((ConvertTo-Json -Compress -Depth 8 $operatorChoicePacket))")
    Set-Content -LiteralPath $runtimePath -Encoding UTF8 -Value @("entry_dedup_runtime_snapshot_collector_activation_request_packet=$((ConvertTo-Json -Compress -Depth 8 $runtimePacket))")
    Set-Content -LiteralPath $budgetPath -Encoding UTF8 -Value @("entry_dedup_budget_snapshot_review_request_packet=$((ConvertTo-Json -Compress -Depth 8 $budgetPacket))")
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @("entry_dedup_oco_route_dry_run_request_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))")
    Set-Content -LiteralPath $historicalPath -Encoding UTF8 -Value @("entry_dedup_historical_event_risk_row_review_packet=$((ConvertTo-Json -Compress -Depth 8 $historicalPacket))")
    Set-Content -LiteralPath $traceabilityPath -Encoding UTF8 -Value @("entry_dedup_review_only_objective_traceability_packet=$((ConvertTo-Json -Compress -Depth 8 $traceabilityPacket))")
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Post-Semantic Blocker Priority Board`nPOST_SEMANTIC_BLOCKER_PRIORITY_BOARD"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_post_semantic_blocker_priority_board.ps1`npriority board is not order readiness"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -OpenExposureSemanticResolutionLogPath $semanticPath `
        -OpenExposureOperatorChoiceLogPath $operatorChoicePath `
        -RuntimeSnapshotCollectorRequestLogPath $runtimePath `
        -BudgetSnapshotReviewRequestLogPath $budgetPath `
        -OcoRouteDryRunRequestLogPath $ocoPath `
        -HistoricalEventRiskRowReviewLogPath $historicalPath `
        -ObjectiveTraceabilityLogPath $traceabilityPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup post-semantic blocker priority board unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_post_semantic_blocker_priority_board_status=READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE",
            "entry_dedup_post_semantic_blocker_priority_board_decision=KEEP_ENTRY_DEDUP_BLOCKED_AND_PRIORITIZE_REVIEW_ONLY_CLEARANCE_SEQUENCE",
            "entry_dedup_post_semantic_blocker_priority_board_remaining_blocker_count=5",
            "entry_dedup_post_semantic_blocker_priority_board_next_blocker=OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS",
            "entry_dedup_post_semantic_blocker_priority_board_exact_opportunity_count=2",
            "entry_dedup_post_semantic_blocker_priority_board_actual_auto_exposure_clear=true",
            "entry_dedup_post_semantic_blocker_priority_board_open_exposure_semantic_blocked=true",
            "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_ready=true",
            "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_confirm_text=AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
            "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_recommended_review_choice=REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION",
            "entry_dedup_post_semantic_blocker_priority_board_runtime_snapshot_blocked=true",
            "entry_dedup_post_semantic_blocker_priority_board_budget_runtime_blocked=true",
            "entry_dedup_post_semantic_blocker_priority_board_oco_route_blocked=true",
            "entry_dedup_post_semantic_blocker_priority_board_historical_event_risk_blocked=true",
            "entry_dedup_post_semantic_blocker_priority_board_traceability_order_readiness=BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE",
            "entry_dedup_post_semantic_blocker_priority_board_report_updated=true",
            "entry_dedup_post_semantic_blocker_priority_board_runbook_updated=true",
            "entry_dedup_post_semantic_blocker_priority_board_missing_requirements=[]",
            "priority_board_ready=true",
            "live_execution_ready=false",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup post-semantic blocker priority board only"
        )) {
        Assert-Contains -Name "EntryDedup post-semantic blocker priority board output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-post-semantic-blocker-priority-board-test] OK"
