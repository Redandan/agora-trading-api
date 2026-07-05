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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_live_gate_semantics_diff_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_PACKET",
        "READY_FOR_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_INCOMPLETE_NOT_LIVE",
        "REVIEW_GATE_SCOPE_MISMATCH_BEFORE_ANY_ENTRY_DEDUP_POLICY_CHANGE",
        "ALL_EXIT_TIME_NULL_ROWS",
        "AUTO_TRADED_EXIT_TIME_NULL_ROWS",
        "scopeMismatchPresent",
        "behaviorChangeAllowed = `$false",
        "liveGateChangeAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_live_gate_semantics_diff_packet",
        "source_review_ready=",
        "behavior_change_allowed=false",
        "live_gate_change_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup live gate semantics diff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-live-gate-semantics-diff-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $liveSignalPath = Join-Path $tempDir "LiveSignalEvaluator.java"
    $stagedAddPath = Join-Path $tempDir "StagedAddPolicyService.java"
    $semanticPath = Join-Path $tempDir "semantic.log"
    $boardPath = Join-Path $tempDir "board.log"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    Set-Content -LiteralPath $liveSignalPath -Encoding UTF8 -Value @"
class LiveSignalEvaluator {
    boolean hasOpenLongExposure = liveSignalRepository.existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
        strategy.getId(), symbol, "LONG", intervalCode);
}
"@
    Set-Content -LiteralPath $stagedAddPath -Encoding UTF8 -Value @"
class StagedAddPolicyService {
    void evaluate() {
        liveSignalRepository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(508L);
        liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
    }
}
"@

    $semanticPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE"
        semanticEvidence = [pscustomobject]@{
            actualAutoExposureClear = $true
            operatorSemanticsChoiceRequired = $true
        }
        reviewEnvelope = [pscustomobject]@{
            openExposureClearanceAllowed = $false
            orderAllowed = $false
        }
    }
    $boardPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE"
        remainingBlockerCount = 5
        nextBlocker = "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS"
        reviewEnvelope = [pscustomobject]@{
            liveExecutionReady = $false
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $semanticPath -Encoding UTF8 -Value @("entry_dedup_open_exposure_semantic_resolution_packet=$((ConvertTo-Json -Compress -Depth 8 $semanticPacket))")
    Set-Content -LiteralPath $boardPath -Encoding UTF8 -Value @("entry_dedup_post_semantic_blocker_priority_board_packet=$((ConvertTo-Json -Compress -Depth 8 $boardPacket))")
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Live Gate Semantics Diff`nLIVE_GATE_SEMANTICS_DIFF"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_live_gate_semantics_diff_packet.ps1`ngate semantics diff is not a behavior change"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -LiveSignalEvaluatorPath $liveSignalPath `
        -StagedAddPolicyServicePath $stagedAddPath `
        -OpenExposureSemanticResolutionLogPath $semanticPath `
        -PostSemanticBlockerPriorityBoardLogPath $boardPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup live gate semantics diff packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_live_gate_semantics_diff_status=READY_FOR_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_REVIEW_NOT_LIVE",
            "entry_dedup_live_gate_semantics_diff_decision=REVIEW_GATE_SCOPE_MISMATCH_BEFORE_ANY_ENTRY_DEDUP_POLICY_CHANGE",
            "entry_dedup_live_gate_semantics_diff_live_signal_gate_scope=ALL_EXIT_TIME_NULL_ROWS",
            "entry_dedup_live_gate_semantics_diff_staged_add_gate_scope=AUTO_TRADED_EXIT_TIME_NULL_ROWS",
            "entry_dedup_live_gate_semantics_diff_scope_mismatch_present=true",
            "entry_dedup_live_gate_semantics_diff_actual_auto_exposure_clear=true",
            "entry_dedup_live_gate_semantics_diff_operator_semantics_choice_required=true",
            "entry_dedup_live_gate_semantics_diff_remaining_blocker_count=5",
            "entry_dedup_live_gate_semantics_diff_next_blocker=OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS",
            "entry_dedup_live_gate_semantics_diff_explains_current_no_buy=true",
            "entry_dedup_live_gate_semantics_diff_report_updated=true",
            "entry_dedup_live_gate_semantics_diff_runbook_updated=true",
            "entry_dedup_live_gate_semantics_diff_missing_requirements=[]",
            "source_review_ready=true",
            "behavior_change_allowed=false",
            "live_gate_change_allowed=false",
            "open_exposure_clearance_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup live gate semantics diff packet only"
        )) {
        Assert-Contains -Name "EntryDedup live gate semantics diff output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-live-gate-semantics-diff-packet-test] OK"
