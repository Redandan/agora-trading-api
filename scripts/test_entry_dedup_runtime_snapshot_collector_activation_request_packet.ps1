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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_runtime_snapshot_collector_activation_request_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_PACKET",
        "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_INCOMPLETE_NOT_LIVE",
        "PREPARE_OPERATOR_REVIEW_FOR_SHADOW_COLLECTOR_ACTIVATION_NOT_DEPLOYMENT",
        "AUTHORIZE_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_ONLY",
        "collectorActivationAllowed = `$false",
        "runtimeEvidenceWriteAllowed = `$false",
        "deployOrEnvChangeAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_runtime_snapshot_collector_activation_request_packet",
        "collector_activation_allowed=false",
        "runtime_evidence_write_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup runtime snapshot collector activation request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-collector-activation-request-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $collectorPath = Join-Path $tempDir "collector.log"
    $coveragePath = Join-Path $tempDir "coverage.log"
    $handoffPath = Join-Path $tempDir "handoff.log"

    $collectorPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
        localImplementationStatus = "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
        reviewEnvelope = [pscustomobject]@{
            collectorActivationAllowed = $false
            runtimeEvidenceWriteAllowed = $false
            orderAllowed = $false
        }
    }
    $coveragePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
        exactOpportunityCoverage = [pscustomobject]@{
            exactOpportunityCount = 2
        }
        collectorContractCoverage = [pscustomobject]@{
            collectorContractReady = $true
        }
        runtimeSnapshotEvidence = [pscustomobject]@{
            runtimeSnapshotCoverageCleared = $false
            runtimeSnapshotBlockerReason = "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING"
            candidateRuntimeEntryPlanRows = 0
            candidateRuntimeOcoPlanRows = 1
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    $handoffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
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

    Set-Content -LiteralPath $collectorPath -Encoding UTF8 -Value @(
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet=$((ConvertTo-Json -Compress -Depth 8 $collectorPacket))"
    )
    Set-Content -LiteralPath $coveragePath -Encoding UTF8 -Value @(
        "entry_dedup_exact_ev_oco_snapshot_coverage_packet=$((ConvertTo-Json -Compress -Depth 8 $coveragePacket))"
    )
    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @(
        "entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -CollectorReviewLogPath $collectorPath `
        -ExactEvOcoCoverageLogPath $coveragePath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup runtime snapshot collector activation request packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_runtime_snapshot_collector_activation_request_status=READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE",
            "entry_dedup_runtime_snapshot_collector_activation_request_decision=PREPARE_OPERATOR_REVIEW_FOR_SHADOW_COLLECTOR_ACTIVATION_NOT_DEPLOYMENT",
            "entry_dedup_runtime_snapshot_collector_activation_request_exact_opportunity_count=2",
            "entry_dedup_runtime_snapshot_collector_activation_request_local_implementation_status=LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE",
            "entry_dedup_runtime_snapshot_collector_activation_request_contract_ready=true",
            "entry_dedup_runtime_snapshot_collector_activation_request_runtime_snapshot_cleared=false",
            "entry_dedup_runtime_snapshot_collector_activation_request_runtime_snapshot_blocker=CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING",
            "entry_dedup_runtime_snapshot_collector_activation_request_candidate_entry_plan_rows=0",
            "entry_dedup_runtime_snapshot_collector_activation_request_candidate_oco_plan_rows=1",
            "entry_dedup_runtime_snapshot_collector_activation_request_daily_cap_max_loss_runtime_status=MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED",
            "entry_dedup_runtime_snapshot_collector_activation_request_confirm_text=AUTHORIZE_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_ONLY",
            "entry_dedup_runtime_snapshot_collector_activation_request_missing_requirements=[]",
            "request_ready=true",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "deploy_or_env_change_allowed=false",
            "mutation_ready=false",
            "order_allowed=false",
            "read-only EntryDedup runtime snapshot collector activation request packet only"
        )) {
        Assert-Contains -Name "EntryDedup runtime snapshot collector activation request output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-runtime-snapshot-collector-activation-request-packet-test] OK"
