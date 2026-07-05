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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_open_exposure_review_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_INCOMPLETE_NOT_LIVE",
        "REVIEW_NON_AUTO_MISSING_OCO_OPEN_SIGNAL_ROWS_NOT_EXECUTION",
        "OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION",
        "openExposureReviewRequiredBeforeMutation",
        "openExposureMutationAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_open_exposure_review_packet",
        "open_exposure_review_ready=",
        "open_exposure_mutation_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup open exposure review script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-open-exposure-review-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $handoffPath = Join-Path $tempDir "handoff.log"
    $exactPath = Join-Path $tempDir "exact.log"

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
            openSignalRows = 1
            autoTradedOpenRows = 0
            nonAutoOpenRows = 1
            nonAutoZeroQtyRows = 1
            nonAutoEventRiskRows = 1
            missingOcoRows = 1
        }
        reviewEnvelope = [pscustomobject]@{
            shadowReviewReady = $true
            mutationReady = $false
            orderAllowed = $false
        }
    }
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityCount = 2
        openExposure = [pscustomobject]@{
            open_signal_rows = 1
            auto_traded_open_rows = 0
            non_auto_open_rows = 1
            non_auto_zero_qty_rows = 1
            non_auto_eventrisk_rows = 1
            missing_oco_rows = 1
            open_notional = 0
        }
        orderAllowed = $false
    }

    Set-Content -LiteralPath $handoffPath -Encoding UTF8 -Value @(
        "entry_dedup_mutation_blocker_handoff_packet=$((ConvertTo-Json -Compress -Depth 8 $handoffPacket))"
    )
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @(
        "entry_dedup_exact_opportunity_staged_add_review_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -MutationBlockerHandoffLogPath $handoffPath `
        -ExactOpportunityLogPath $exactPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup open exposure review packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_open_exposure_review_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE",
            "entry_dedup_open_exposure_review_decision=REVIEW_NON_AUTO_MISSING_OCO_OPEN_SIGNAL_ROWS_NOT_EXECUTION",
            "entry_dedup_open_exposure_review_classification=OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION",
            "entry_dedup_open_exposure_review_exact_opportunity_count=2",
            "entry_dedup_open_exposure_review_open_signal_rows=1",
            "entry_dedup_open_exposure_review_auto_traded_open_rows=0",
            "entry_dedup_open_exposure_review_non_auto_open_rows=1",
            "entry_dedup_open_exposure_review_non_auto_zero_qty_rows=1",
            "entry_dedup_open_exposure_review_non_auto_eventrisk_rows=1",
            "entry_dedup_open_exposure_review_missing_oco_rows=1",
            "entry_dedup_open_exposure_review_auto_traded_exposure_clear=true",
            "entry_dedup_open_exposure_review_non_auto_exposure_clear=false",
            "entry_dedup_open_exposure_review_required_before_mutation=true",
            "entry_dedup_open_exposure_review_missing_requirements=[]",
            "open_exposure_review_ready=true",
            "open_exposure_mutation_allowed=false",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup open exposure review packet only"
        )) {
        Assert-Contains -Name "EntryDedup open exposure review output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-open-exposure-review-packet-test] OK"
