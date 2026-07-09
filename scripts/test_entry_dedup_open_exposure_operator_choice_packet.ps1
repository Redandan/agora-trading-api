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

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_open_exposure_operator_choice_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_PACKET",
        "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_INCOMPLETE_NOT_LIVE",
        "PRESENT_OPERATOR_CHOICES_FOR_ZERO_QTY_NON_AUTO_OPEN_EXPOSURE_NOT_IMPLEMENTATION",
        "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION",
        "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
        "choiceReviewReady = `$ready",
        "openExposureClearanceAllowed = `$false",
        "implementationAllowed = `$false",
        "strategyConfigMutationAllowed = `$false",
        "behaviorChangeAllowed = `$false",
        "liveGateChangeAllowed = `$false",
        "activationPreflight",
        "activationOperatorNote",
        "activationCommandPreview",
        "setStrategyFlags",
        "orderAllowed = `$false",
        "entry_dedup_open_exposure_operator_choice_packet",
        "entry_dedup_open_exposure_operator_choice_activation_preflight_ready=",
        "entry_dedup_open_exposure_operator_choice_activation_mcp_tool=",
        "entry_dedup_open_exposure_operator_choice_activation_command_preview=",
        "entry_dedup_open_exposure_operator_choice_activation_requested_scope=",
        "entry_dedup_open_exposure_operator_choice_activation_rollback_scope=",
        "choice_review_ready=",
        "open_exposure_clearance_allowed=false",
        "implementation_allowed=false",
        "strategy_config_mutation_allowed=false",
        "behavior_change_allowed=false",
        "live_gate_change_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup open exposure operator choice script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-open-exposure-operator-choice-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $semanticPath = Join-Path $tempDir "semantic.log"
    $defaultOffPath = Join-Path $tempDir "default-off.log"
    $priorityPath = Join-Path $tempDir "priority.log"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    $semanticPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        semanticEvidence = [pscustomobject]@{
            actualAutoExposureClear = $true
            semanticBlockerPresent = $true
            zeroQtyMissingOcoSemanticsReviewRequired = $true
            operatorSemanticsChoiceRequired = $true
            openSignalRows = 1
            autoTradedOpenRows = 0
            nonAutoZeroQtyRows = 1
            missingOcoRows = 1
        }
    }
    $defaultOffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_NOT_LIVE"
        proposedChange = [pscustomobject]@{
            configKey = "entryDedupOpenExposureScope"
            defaultScope = "ALL_OPEN_ROWS"
            requestedOptionalScope = "AUTO_TRADED_OPEN_ROWS"
            confirmText = "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"
        }
        evidence = [pscustomobject]@{
            scopeMismatchPresent = $true
            explainsCurrentNoBuy = $true
        }
        reviewEnvelope = [pscustomobject]@{
            requestReady = $true
            implementationAllowed = $false
            behaviorChangeAllowed = $false
            liveGateChangeAllowed = $false
            orderAllowed = $false
        }
    }
    $priorityPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE"
        nextBlocker = "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS"
        remainingBlockerCount = 5
        boardEvidence = [pscustomobject]@{
            openExposureSemanticBlocked = $true
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $semanticPath -Encoding UTF8 -Value "entry_dedup_open_exposure_semantic_resolution_packet=$((ConvertTo-Json -Compress -Depth 8 $semanticPacket))"
    Set-Content -LiteralPath $defaultOffPath -Encoding UTF8 -Value "entry_dedup_live_gate_default_off_change_request_packet=$((ConvertTo-Json -Compress -Depth 8 $defaultOffPacket))"
    Set-Content -LiteralPath $priorityPath -Encoding UTF8 -Value "entry_dedup_post_semantic_blocker_priority_board_packet=$((ConvertTo-Json -Compress -Depth 8 $priorityPacket))"
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Open Exposure Operator Choice Review`nAUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_open_exposure_operator_choice_packet.ps1`noperator choice review is not authorization to clear exposure"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -SemanticResolutionLogPath $semanticPath `
        -DefaultOffChangeRequestLogPath $defaultOffPath `
        -PriorityBoardLogPath $priorityPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup open exposure operator choice packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_open_exposure_operator_choice_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE",
            "entry_dedup_open_exposure_operator_choice_decision=PRESENT_OPERATOR_CHOICES_FOR_ZERO_QTY_NON_AUTO_OPEN_EXPOSURE_NOT_IMPLEMENTATION",
            "entry_dedup_open_exposure_operator_choice_next_blocker=OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS",
            "entry_dedup_open_exposure_operator_choice_remaining_blocker_count=5",
            "entry_dedup_open_exposure_operator_choice_actual_auto_exposure_clear=true",
            "entry_dedup_open_exposure_operator_choice_zero_qty_missing_oco_review_required=true",
            "entry_dedup_open_exposure_operator_choice_operator_semantics_choice_required=true",
            "entry_dedup_open_exposure_operator_choice_scope_mismatch_present=true",
            "entry_dedup_open_exposure_operator_choice_explains_current_no_buy=true",
            "entry_dedup_open_exposure_operator_choice_config_key=entryDedupOpenExposureScope",
            "entry_dedup_open_exposure_operator_choice_default_scope=ALL_OPEN_ROWS",
            "entry_dedup_open_exposure_operator_choice_requested_optional_scope=AUTO_TRADED_OPEN_ROWS",
            "entry_dedup_open_exposure_operator_choice_confirm_text=AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
            "entry_dedup_open_exposure_operator_choice_recommended_review_choice=REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION",
            "entry_dedup_open_exposure_operator_choice_activation_preflight_ready=true",
            "entry_dedup_open_exposure_operator_choice_activation_mcp_tool=setStrategyFlags",
            "entry_dedup_open_exposure_operator_choice_activation_command_preview=setStrategyFlags(strategyId=508, entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS, note=<operator-note>)",
            "entry_dedup_open_exposure_operator_choice_activation_requested_scope=AUTO_TRADED_OPEN_ROWS",
            "entry_dedup_open_exposure_operator_choice_activation_rollback_scope=ALL_OPEN_ROWS",
            "entry_dedup_open_exposure_operator_choice_report_updated=true",
            "entry_dedup_open_exposure_operator_choice_runbook_updated=true",
            "entry_dedup_open_exposure_operator_choice_missing_requirements=[]",
            "choice_review_ready=true",
            "open_exposure_clearance_allowed=false",
            "implementation_allowed=false",
            "strategy_config_mutation_allowed=false",
            "behavior_change_allowed=false",
            "live_gate_change_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup open exposure operator choice review only"
        )) {
        Assert-Contains -Name "EntryDedup open exposure operator choice output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-open-exposure-operator-choice-packet-test] OK"
