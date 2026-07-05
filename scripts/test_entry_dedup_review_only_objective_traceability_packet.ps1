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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_review_only_objective_traceability_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_PACKET",
        "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_INCOMPLETE_NOT_LIVE",
        "REVIEW_ONLY_EVIDENCE_PACKAGED_LIVE_CLEARANCE_BLOCKED",
        "KEEP_ENTRY_DEDUP_REVIEW_ONLY_SCOPE_READY_AND_LIVE_MUTATIONS_BLOCKED",
        "reviewOnlyToolingReady",
        "liveExecutionReady = `$false",
        "orderAllowed = `$false",
        "entry_dedup_review_only_objective_traceability_packet",
        "traceability_ready=",
        "review_only_tooling_ready=",
        "live_execution_ready=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup review-only objective traceability script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-objective-traceability-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $bundlePath = Join-Path $tempDir "bundle.log"
    $exactPath = Join-Path $tempDir "exact.log"
    $duplicatePath = Join-Path $tempDir "duplicate.log"
    $eventRiskPath = Join-Path $tempDir "event-risk.log"
    $historicalEventRiskPath = Join-Path $tempDir "historical-event-risk.log"
    $budgetPath = Join-Path $tempDir "budget.log"
    $ocoPath = Join-Path $tempDir "oco.log"
    $boardPath = Join-Path $tempDir "board.log"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    $bundlePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        reviewEvidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 2
            exactEvOcoCoverageReady = $true
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
        reviewEnvelope = [pscustomobject]@{
            exactEvOcoCoverageReady = $true
            orderAllowed = $false
        }
        runtimeSnapshotEvidence = [pscustomobject]@{
            runtimeSnapshotCoverageCleared = $false
        }
    }
    $duplicatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE"
        exactOpportunityEvidence = [pscustomobject]@{
            duplicateSuppressionCountConsistent = $true
        }
        writePathSourceEvidence = [pscustomobject]@{
            sourceMarkersPresent = $true
        }
        collectorContractEvidence = [pscustomobject]@{
            hasDuplicateCandidateHash = $true
            hasReplayCandidateId = $true
        }
    }
    $eventRiskPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE"
        reviewEnvelope = [pscustomobject]@{
            currentEventRiskR0Clear = $true
            orderAllowed = $false
        }
    }
    $historicalEventRiskPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
        historicalEventRiskReview = [pscustomobject]@{
            reviewRequiredBeforeMutation = $true
        }
    }
    $budgetPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
        budgetSnapshotEvidence = [pscustomobject]@{
            contractIncludesDailyCapSnapshot = $true
            contractIncludesMaxLossSnapshot = $true
            budgetSnapshotRuntimeCleared = $false
        }
    }
    $ocoPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
    }
    $boardPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_NOT_LIVE"
        clearanceBoard = [pscustomobject]@{
            exactOpportunityCount = 2
            orderReadiness = "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE"
            ocoRouteClearanceBlocked = $true
            anyClearanceBlockerRemaining = $true
        }
        reviewEnvelope = [pscustomobject]@{
            mutationReady = $false
            orderAllowed = $false
            collectorActivationAllowed = $false
            runtimeEvidenceWriteAllowed = $false
            entryDedupPolicyChangeAllowed = $false
            livePolicyChangeAllowed = $false
            positionOrOcoMutationAllowed = $false
            deployOrEnvChangeAllowed = $false
            dbMutationAllowed = $false
            exchangeMutationAllowed = $false
        }
    }

    Set-Content -LiteralPath $bundlePath -Encoding UTF8 -Value @("entry_dedup_review_only_shadow_bundle_packet=$((ConvertTo-Json -Compress -Depth 8 $bundlePacket))")
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @("entry_dedup_exact_ev_oco_snapshot_coverage_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))")
    Set-Content -LiteralPath $duplicatePath -Encoding UTF8 -Value @("entry_dedup_duplicate_hash_replay_protection_packet=$((ConvertTo-Json -Compress -Depth 8 $duplicatePacket))")
    Set-Content -LiteralPath $eventRiskPath -Encoding UTF8 -Value @("entry_dedup_event_risk_control_evidence_packet=$((ConvertTo-Json -Compress -Depth 8 $eventRiskPacket))")
    Set-Content -LiteralPath $historicalEventRiskPath -Encoding UTF8 -Value @("entry_dedup_historical_event_risk_row_review_packet=$((ConvertTo-Json -Compress -Depth 8 $historicalEventRiskPacket))")
    Set-Content -LiteralPath $budgetPath -Encoding UTF8 -Value @("entry_dedup_budget_snapshot_review_request_packet=$((ConvertTo-Json -Compress -Depth 8 $budgetPacket))")
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @("entry_dedup_oco_route_dry_run_request_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))")
    Set-Content -LiteralPath $boardPath -Encoding UTF8 -Value @("entry_dedup_mutation_blocker_clearance_board_packet=$((ConvertTo-Json -Compress -Depth 8 $boardPacket))")
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Mutation Blocker Clearance Board`nBLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE`nNon-Authorization"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_mutation_blocker_clearance_board_packet.ps1`nrequest-ready packets are not order readiness"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -ReviewOnlyShadowBundleLogPath $bundlePath `
        -ExactEvOcoCoverageLogPath $exactPath `
        -DuplicateHashReplayProtectionLogPath $duplicatePath `
        -EventRiskControlEvidenceLogPath $eventRiskPath `
        -HistoricalEventRiskRowReviewLogPath $historicalEventRiskPath `
        -BudgetSnapshotReviewRequestLogPath $budgetPath `
        -OcoRouteDryRunRequestLogPath $ocoPath `
        -MutationBlockerClearanceBoardLogPath $boardPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup review-only objective traceability packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_review_only_objective_traceability_status=READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE",
            "entry_dedup_review_only_objective_traceability_decision=KEEP_ENTRY_DEDUP_REVIEW_ONLY_SCOPE_READY_AND_LIVE_MUTATIONS_BLOCKED",
            "entry_dedup_review_only_objective_traceability_review_scope_status=REVIEW_ONLY_EVIDENCE_PACKAGED_LIVE_CLEARANCE_BLOCKED",
            "entry_dedup_review_only_objective_traceability_order_readiness=BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE",
            "entry_dedup_review_only_objective_traceability_exact_opportunity_count=2",
            "entry_dedup_review_only_objective_traceability_exact_ev_oco_preflight_ready=true",
            "entry_dedup_review_only_objective_traceability_runtime_snapshot_cleared=false",
            "entry_dedup_review_only_objective_traceability_duplicate_hash_ready=true",
            "entry_dedup_review_only_objective_traceability_event_risk_current_r0_clear=true",
            "entry_dedup_review_only_objective_traceability_historical_event_risk_blocked=true",
            "entry_dedup_review_only_objective_traceability_budget_contract_ready=true",
            "entry_dedup_review_only_objective_traceability_budget_runtime_cleared=false",
            "entry_dedup_review_only_objective_traceability_oco_route_blocked=true",
            "entry_dedup_review_only_objective_traceability_any_blocker_remaining=true",
            "entry_dedup_review_only_objective_traceability_report_updated=true",
            "entry_dedup_review_only_objective_traceability_runbook_updated=true",
            "entry_dedup_review_only_objective_traceability_all_mutation_flags_false=true",
            "entry_dedup_review_only_objective_traceability_missing_requirements=[]",
            "traceability_ready=true",
            "review_only_tooling_ready=true",
            "live_execution_ready=false",
            "mutation_ready=false",
            "order_allowed=false",
            "read-only EntryDedup review-only objective traceability packet only"
        )) {
        Assert-Contains -Name "EntryDedup review-only objective traceability output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-review-only-objective-traceability-packet-test] OK"
