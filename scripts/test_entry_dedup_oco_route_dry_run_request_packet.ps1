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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_oco_route_dry_run_request_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_PACKET",
        "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_INCOMPLETE_NOT_LIVE",
        "PREPARE_OPERATOR_REVIEW_FOR_OCO_ROUTE_DRY_RUN_NOT_EXECUTION",
        "AUTHORIZE_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REVIEW_ONLY",
        "openExposureReviewRequiredBeforeExecution",
        "exchangeDryRunExecutionAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_oco_route_dry_run_request_packet",
        "request_ready=",
        "exchange_dry_run_execution_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup OCO route dry-run request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-oco-dry-run-request-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $handoffPath = Join-Path $tempDir "handoff.log"
    $ocoPath = Join-Path $tempDir "oco.log"

    $handoffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        evidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 2
        }
        openExposureEvidence = [pscustomobject]@{
            missingOcoRows = 1
            nonAutoZeroQtyRows = 1
        }
        nextReviewActions = @(
            [pscustomobject]@{
                action = "REQUEST_EXACT_OCO_ROUTE_DRY_RUN_REVIEW"
                blocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING:EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
            }
        )
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
            routeProofCleared = $false
            exchangeDryRunRequired = $true
            routeBlockerReason = "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
            missingOcoRows = 1
            nonAutoZeroQtyRows = 1
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @(
        "entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))"
    )
    Set-Content -LiteralPath $ocoPath -Encoding UTF8 -Value @(
        "entry_dedup_oco_route_proof_preflight_packet=$((ConvertTo-Json -Compress -Depth 8 $ocoPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -OcoRoutePreflightLogPath $ocoPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup OCO route dry-run request packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_oco_route_dry_run_request_status=READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE",
            "entry_dedup_oco_route_dry_run_request_decision=PREPARE_OPERATOR_REVIEW_FOR_OCO_ROUTE_DRY_RUN_NOT_EXECUTION",
            "entry_dedup_oco_route_dry_run_request_exact_opportunity_count=2",
            "entry_dedup_oco_route_dry_run_request_route_proof_cleared=false",
            "entry_dedup_oco_route_dry_run_request_exchange_dry_run_required=true",
            "entry_dedup_oco_route_dry_run_request_open_exposure_review_required=true",
            "entry_dedup_oco_route_dry_run_request_missing_oco_rows=1",
            "entry_dedup_oco_route_dry_run_request_non_auto_zero_qty_rows=1",
            "entry_dedup_oco_route_dry_run_request_confirm_text=AUTHORIZE_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REVIEW_ONLY",
            "entry_dedup_oco_route_dry_run_request_missing_requirements=[]",
            "request_ready=true",
            "mutation_ready=false",
            "exchange_dry_run_execution_allowed=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup OCO route dry-run request packet only"
        )) {
        Assert-Contains -Name "EntryDedup OCO route dry-run request output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-oco-route-dry-run-request-packet-test] OK"
