Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_open_exposure_scope_activation_authorization_bundle.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_BUNDLE",
        "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_SCOPE_CONFIG_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
        "REFRESH_OPEN_EXPOSURE_SCOPE_ACTIVATION_EVIDENCE_BEFORE_AUTHORIZATION",
        "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
        "setStrategyFlags",
        "AUTO_TRADED_OPEN_ROWS",
        "ALL_OPEN_ROWS",
        "activationAuthorizationReviewReady",
        "activationExecutionAllowed = `$false",
        "strategyConfigMutationAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_open_exposure_scope_activation_authorization_bundle=",
        "entry_dedup_open_exposure_scope_activation_authorization_review_ready=",
        "entry_dedup_open_exposure_scope_activation_authorization_text=",
        "entry_dedup_open_exposure_scope_activation_command_preview=",
        "scope_activation_execution_allowed=false",
        "strategy_config_mutation_allowed=false",
        "order_allowed=false",
        "read-only EntryDedup open exposure scope activation authorization bundle only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup open exposure scope activation authorization script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "EntryDedup open exposure scope activation authorization bundle must not write files or invoke raw SSH/MCP/trading mutation calls"
}

Assert-Contains -Name "verify local runs EntryDedup scope activation authorization test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_entry_dedup_open_exposure_scope_activation_authorization_bundle.ps1"
Assert-Contains -Name "deploy runbook documents EntryDedup scope activation authorization bundle" -Text (Get-Content -Raw -LiteralPath $runbookPath) -Pattern "prepare_entry_dedup_open_exposure_scope_activation_authorization_bundle.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-open-exposure-scope-activation-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $operatorChoicePath = Join-Path $tempDir "operator-choice.log"
    $priorityPath = Join-Path $tempDir "priority.log"

    $operatorChoicePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        blockerEvidence = [pscustomobject]@{
            actualAutoExposureClear = $true
            autoTradedOpenRows = 0
            nonAutoZeroQtyRows = 1
        }
        proposedChange = [pscustomobject]@{
            confirmText = "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"
            recommendedReviewChoice = "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION"
        }
        activationPreflight = [pscustomobject]@{
            preflightReady = $true
            exactMcpTool = "setStrategyFlags"
            exactMcpArguments = [pscustomobject]@{
                strategyId = 508
                entryDedupOpenExposureScope = "AUTO_TRADED_OPEN_ROWS"
                note = "operator note"
            }
            commandPreview = "setStrategyFlags(strategyId=508, entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS, note=<operator-note>)"
            rollbackConfigValue = "ALL_OPEN_ROWS"
        }
        reviewEnvelope = [pscustomobject]@{
            strategyConfigMutationAllowed = $false
            orderAllowed = $false
        }
    }
    $priorityPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE"
        nextBlocker = "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS"
        remainingBlockerCount = 5
        boardEvidence = [pscustomobject]@{
            openExposureOperatorChoiceReady = $true
        }
        reviewEnvelope = [pscustomobject]@{
            orderAllowed = $false
        }
    }

    @(
        ("entry_dedup_open_exposure_operator_choice_packet=" + (ConvertTo-Json -Compress -Depth 10 $operatorChoicePacket)),
        "entry_dedup_open_exposure_operator_choice_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $operatorChoicePath -Encoding UTF8
    @(
        ("entry_dedup_post_semantic_blocker_priority_board_packet=" + (ConvertTo-Json -Compress -Depth 10 $priorityPacket)),
        "entry_dedup_post_semantic_blocker_priority_board_status=READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE"
    ) | Set-Content -LiteralPath $priorityPath -Encoding UTF8

    $readyOutput = & $scriptPath `
        -OperatorChoiceLogPath $operatorChoicePath `
        -PriorityBoardLogPath $priorityPath `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "entry_dedup_open_exposure_scope_activation_authorization_bundle=",
            '"packetType":"ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_BUNDLE"',
            "entry_dedup_open_exposure_scope_activation_authorization_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "entry_dedup_open_exposure_scope_activation_authorization_decision=PRESENT_EXACT_SCOPE_CONFIG_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
            "source_open_exposure_operator_choice_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE",
            "source_post_semantic_priority_board_status=READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE",
            "entry_dedup_open_exposure_scope_activation_authorization_review_ready=true",
            "entry_dedup_open_exposure_scope_activation_authorization_text=I explicitly authorize AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW for Strategy 508 BTCUSDT 1h",
            "entry_dedup_open_exposure_scope_activation_mcp_tool=setStrategyFlags",
            "entry_dedup_open_exposure_scope_activation_command_preview=setStrategyFlags(strategyId=508, entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS, note=<operator-note>)",
            "entry_dedup_open_exposure_scope_activation_requested_scope=AUTO_TRADED_OPEN_ROWS",
            "entry_dedup_open_exposure_scope_activation_rollback_scope=ALL_OPEN_ROWS",
            "entry_dedup_open_exposure_scope_activation_missing_requirements=[]",
            "scope_activation_execution_allowed=false",
            "strategy_config_mutation_allowed=false",
            "live_execution_ready=false",
            "mutation_ready=false",
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only EntryDedup open exposure scope activation authorization bundle only"
        )) {
        Assert-Contains -Name "ready EntryDedup scope activation authorization replay" -Text $readyText -Pattern ([regex]::Escape($marker))
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready scope activation authorization bundle unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "entry_dedup_open_exposure_scope_activation_authorization_bundle="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.activationAuthorizationReviewReady) {
        throw "scope activation authorization bundle should be review-ready for ready replay"
    }
    if ([bool]$packet.activationExecutionAllowed -or [bool]$packet.reviewEnvelope.strategyConfigMutationAllowed -or [bool]$packet.reviewEnvelope.orderAllowed) {
        throw "scope activation authorization bundle must keep mutation flags false"
    }

    $blockedOperatorChoicePacket = $operatorChoicePacket.PSObject.Copy()
    $blockedOperatorChoicePacket.activationPreflight.preflightReady = $false
    @(
        ("entry_dedup_open_exposure_operator_choice_packet=" + (ConvertTo-Json -Compress -Depth 10 $blockedOperatorChoicePacket)),
        "entry_dedup_open_exposure_operator_choice_status=READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $operatorChoicePath -Encoding UTF8

    $blockedOutput = & $scriptPath `
        -OperatorChoiceLogPath $operatorChoicePath `
        -PriorityBoardLogPath $priorityPath *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "entry_dedup_open_exposure_scope_activation_authorization_status=BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
            "REFRESH_OPEN_EXPOSURE_SCOPE_ACTIVATION_EVIDENCE_BEFORE_AUTHORIZATION",
            "activation preflight ready",
            "entry_dedup_open_exposure_scope_activation_authorization_review_ready=false",
            "scope_activation_execution_allowed=false",
            "strategy_config_mutation_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked EntryDedup scope activation authorization replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-open-exposure-scope-activation-authorization-bundle-test] OK"
