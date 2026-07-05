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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_oco_route_proof_preflight_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_PACKET",
        "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_INCOMPLETE_NOT_LIVE",
        "allExactPlanShapesValid",
        "syntheticPlanShapeCoverageReady",
        "routeProofCleared",
        "exchangeDryRunRequired",
        "routeBlockerReason",
        "ocoRouteProofCleared = `$false",
        "orderAllowed = `$false",
        "entry_dedup_oco_route_proof_preflight_packet",
        "oco_route_proof_cleared=false",
        "exchange_dry_run_required=true",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup OCO route proof preflight script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-oco-route-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $exactPath = Join-Path $tempDir "exact.log"
    $syntheticPath = Join-Path $tempDir "synthetic.log"
    $gatePath = Join-Path $tempDir "gate.log"

    $openExposure = [pscustomobject]@{
        open_signal_rows = 1
        auto_traded_open_rows = 0
        non_auto_open_rows = 1
        non_auto_zero_qty_rows = 1
        missing_oco_rows = 1
    }
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityCount = 2
        openExposure = $openExposure
        opportunities = @(
            [pscustomobject]@{
                opportunityKey = "0123456789abcdef"
                entry = 100
                tp = 101
                sl = 99
            },
            [pscustomobject]@{
                opportunityKey = "fedcba9876543210"
                entry = 110
                tp = 111.1
                sl = 108.9
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
        validOcoPlanShapeRows = 2
        openExposure = $openExposure
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
    }
    $gatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET"
        status = "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE"
        gateStatuses = [pscustomobject]@{
            ocoFeasibility = "BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY"
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
        -GatePreflightLogPath $gatePath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup OCO route proof preflight packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_oco_route_proof_preflight_status=READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE",
            "entry_dedup_oco_route_proof_exact_opportunity_count=2",
            "entry_dedup_oco_route_proof_valid_exact_plan_shape_rows=2",
            "entry_dedup_oco_route_proof_all_exact_plan_shapes_valid=true",
            "entry_dedup_oco_route_proof_synthetic_candidate_rows=2",
            "entry_dedup_oco_route_proof_synthetic_valid_oco_plan_shape_rows=2",
            "entry_dedup_oco_route_proof_gate_oco_status=BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY",
            "entry_dedup_oco_route_proof_route_cleared=false",
            "entry_dedup_oco_route_proof_exchange_dry_run_required=true",
            "entry_dedup_oco_route_proof_blocker_reason=EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO",
            "entry_dedup_oco_route_proof_missing_oco_rows=1",
            "entry_dedup_oco_route_proof_non_auto_zero_qty_rows=1",
            "entry_dedup_oco_route_proof_preflight_missing_requirements=[]",
            "oco_route_proof_cleared=false",
            "exchange_dry_run_required=true",
            "runtime_evidence_write_allowed=false",
            "entry_dedup_policy_change_allowed=false",
            "staged_add_execution_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup OCO route proof preflight packet only"
        )) {
        Assert-Contains -Name "EntryDedup OCO route proof preflight output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-oco-route-proof-preflight-packet-test] OK"
