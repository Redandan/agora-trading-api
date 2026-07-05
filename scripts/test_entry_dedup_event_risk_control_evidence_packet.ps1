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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_event_risk_control_evidence_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_PACKET",
        "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_INCOMPLETE_NOT_LIVE",
        "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
        "currentR0Clear",
        "historicalRowsNeedSeparateReview",
        "eventRiskOverrideAllowed = `$false",
        "entry_dedup_event_risk_control_evidence_packet",
        "event_risk_override_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup EventRiskControl evidence script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-event-risk-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $gatePath = Join-Path $tempDir "gate.log"
    $exactPath = Join-Path $tempDir "exact.log"
    $coveragePath = Join-Path $tempDir "coverage.log"

    $gatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET"
        status = "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE"
        gateStatuses = [pscustomobject]@{
            eventRiskControl = "CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
        }
        runtimeMcpEvidence = [pscustomobject]@{
            eventRiskOk = $true
            eventRiskLevel = "R0"
            eventRiskPolicy = "new entries allowed"
        }
        dbEvidence = [pscustomobject]@{
            nonAutoEventRiskRows = 1
            globalGateRows = [pscustomobject]@{
                eventrisk_block_rows = 1
            }
            candidateGateRows = [pscustomobject]@{
                eventRiskBlockRows = 0
            }
        }
        orderAllowed = $false
    }
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityCount = 2
        openExposure = [pscustomobject]@{
            non_auto_eventrisk_rows = 1
        }
        orderAllowed = $false
    }
    $coveragePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
        eventRiskEvidence = [pscustomobject]@{
            eventRiskEvidenceStatus = "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }
    Set-Content -LiteralPath $gatePath -Encoding UTF8 -Value @(
        "entry_dedup_semantics_gate_preflight_packet=$((ConvertTo-Json -Compress -Depth 8 $gatePacket))"
    )
    Set-Content -LiteralPath $exactPath -Encoding UTF8 -Value @(
        "entry_dedup_exact_opportunity_staged_add_review_packet=$((ConvertTo-Json -Compress -Depth 8 $exactPacket))"
    )
    Set-Content -LiteralPath $coveragePath -Encoding UTF8 -Value @(
        "entry_dedup_exact_ev_oco_snapshot_coverage_packet=$((ConvertTo-Json -Compress -Depth 8 $coveragePacket))"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -GatePreflightLogPath $gatePath `
        -ExactOpportunityLogPath $exactPath `
        -ExactEvOcoCoverageLogPath $coveragePath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup EventRiskControl evidence packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_event_risk_control_evidence_status=READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE",
            "entry_dedup_event_risk_control_evidence_decision=REVIEW_CURRENT_R0_EVENT_RISK_WITH_HISTORICAL_NON_AUTO_ROWS_NOT_LIVE",
            "entry_dedup_event_risk_control_evidence_event_risk_status=CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
            "entry_dedup_event_risk_control_evidence_gate_status=CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
            "entry_dedup_event_risk_control_evidence_current_r0_clear=true",
            "entry_dedup_event_risk_control_evidence_event_risk_level=R0",
            "entry_dedup_event_risk_control_evidence_event_risk_policy=new entries allowed",
            "entry_dedup_event_risk_control_evidence_candidate_rows_clear=true",
            "entry_dedup_event_risk_control_evidence_candidate_event_risk_block_rows=0",
            "entry_dedup_event_risk_control_evidence_global_event_risk_block_rows=1",
            "entry_dedup_event_risk_control_evidence_non_auto_event_risk_rows=1",
            "entry_dedup_event_risk_control_evidence_historical_rows_need_separate_review=true",
            "entry_dedup_event_risk_control_evidence_missing_requirements=[]",
            "event_risk_override_allowed=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup EventRiskControl evidence packet only"
        )) {
        Assert-Contains -Name "EntryDedup EventRiskControl evidence output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-event-risk-control-evidence-packet-test] OK"
