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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_budget_snapshot_review_request_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_PACKET",
        "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_INCOMPLETE_NOT_LIVE",
        "PREPARE_OPERATOR_REVIEW_FOR_DAILY_CAP_MAX_LOSS_SHADOW_SNAPSHOT_COLLECTION_NOT_DEPLOYMENT",
        "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_REQUIRED",
        "AUTHORIZE_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_ONLY",
        "budgetSnapshotReviewRequestReady",
        "collectorActivationAllowed = `$false",
        "runtimeEvidenceWriteAllowed = `$false",
        "deployOrEnvChangeAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_budget_snapshot_review_request_packet",
        "budget_snapshot_review_request_ready=",
        "collector_activation_allowed=false",
        "runtime_evidence_write_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup budget snapshot review request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-budget-snapshot-review-request-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $activationPath = Join-Path $tempDir "activation.log"
    $collectorPath = Join-Path $tempDir "collector.log"
    $handoffPath = Join-Path $tempDir "handoff.log"

    $activationPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        requestEvidence = [pscustomobject]@{
            exactOpportunityCount = 2
            collectorLocalImplementationStatus = "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
            runtimeSnapshotCoverageCleared = $false
            runtimeSnapshotBlockerReason = "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING"
            dailyCapMaxLossRuntimeStatus = "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
            candidateRuntimeEntryPlanRows = 0
            candidateRuntimeOcoPlanRows = 1
        }
        reviewEnvelope = [pscustomobject]@{
            requestReady = $true
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }
    $collectorPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
        localImplementationStatus = "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
        sourceContract = [pscustomobject]@{
            serviceRequiredMarkers = @(
                "copyIfPresent(context, exposure, `"dailyCapSnapshot`")",
                "copyIfPresent(context, exposure, `"maxLossSnapshot`")"
            )
            optimizerRequiredMarkers = @("dailyCapSnapshot", "maxLossSnapshot")
        }
        proposedCollectorContextKeys = @("dailyCapSnapshot", "maxLossSnapshot")
        reviewEnvelope = [pscustomobject]@{
            collectorActivationAllowed = $false
            runtimeEvidenceWriteAllowed = $false
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
            dailyCapMaxLossRuntimeStatus = "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $activationPath -Encoding UTF8 -Value @(
        "entry_dedup_runtime_snapshot_collector_activation_request_packet=$((ConvertTo-Json -Compress -Depth 8 $activationPacket))"
    )
    Set-Content -LiteralPath $collectorPath -Encoding UTF8 -Value @(
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet=$((ConvertTo-Json -Compress -Depth 8 $collectorPacket))"
    )
    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @(
        "entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -RuntimeSnapshotCollectorActivationRequestLogPath $activationPath `
        -CandidateRuntimeSnapshotCollectorReviewLogPath $collectorPath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup budget snapshot review request packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_budget_snapshot_review_request_status=READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE",
            "entry_dedup_budget_snapshot_review_request_decision=PREPARE_OPERATOR_REVIEW_FOR_DAILY_CAP_MAX_LOSS_SHADOW_SNAPSHOT_COLLECTION_NOT_DEPLOYMENT",
            "entry_dedup_budget_snapshot_review_request_classification=PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_REQUIRED",
            "entry_dedup_budget_snapshot_review_request_exact_opportunity_count=2",
            "entry_dedup_budget_snapshot_review_request_local_implementation_status=LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE",
            "entry_dedup_budget_snapshot_review_request_contract_includes_daily_cap_snapshot=true",
            "entry_dedup_budget_snapshot_review_request_contract_includes_max_loss_snapshot=true",
            "entry_dedup_budget_snapshot_review_request_runtime_snapshot_cleared=false",
            "entry_dedup_budget_snapshot_review_request_runtime_snapshot_blocker=CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
            "entry_dedup_budget_snapshot_review_request_daily_cap_max_loss_runtime_status=MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED",
            "entry_dedup_budget_snapshot_review_request_budget_snapshot_runtime_cleared=false",
            "entry_dedup_budget_snapshot_review_request_candidate_entry_plan_rows=0",
            "entry_dedup_budget_snapshot_review_request_candidate_oco_plan_rows=1",
            "entry_dedup_budget_snapshot_review_request_confirm_text=AUTHORIZE_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_ONLY",
            "entry_dedup_budget_snapshot_review_request_missing_requirements=[]",
            "budget_snapshot_review_request_ready=true",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "deploy_or_env_change_allowed=false",
            "mutation_ready=false",
            "order_allowed=false",
            "read-only EntryDedup daily-cap/max-loss snapshot review request packet only"
        )) {
        Assert-Contains -Name "EntryDedup budget snapshot review request output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-budget-snapshot-review-request-packet-test] OK"
