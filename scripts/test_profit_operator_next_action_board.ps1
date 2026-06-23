Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_next_action_board.ps1"
$priorityPath = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$strategy574Path = Join-Path $PSScriptRoot "prepare_strategy574_tiny_live_governance_operator_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$priorityText = Get-Content -Raw -LiteralPath $priorityPath
$strategy574Text = Get-Content -Raw -LiteralPath $strategy574Path
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-operator-next-action-board] read-only board",
        "scope=READ_ONLY",
        "prepare_profit_operator_priority_decision_brief.ps1",
        "prepare_strategy574_tiny_live_governance_operator_packet.ps1",
        "PROFIT_OPERATOR_NEXT_ACTION_BOARD",
        "READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE",
        "profit_operator_next_action_board_packet",
        "profit_operator_next_action_board_status",
        "strategy574-tiny-live-governance-review",
        "P2_GOVERNANCE_BLOCKER_REVIEW_NOT_LIVE",
        "strategy574/TinyLive governance blocker review",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only profit operator next-action board only",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator next action board marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF", "trailing-stop-dry-run-operator-review")) {
    Assert-Contains -Name "priority brief supports next action board" -Text $priorityText -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @("STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET", "strategy574_tiny_live_governance_status")) {
    Assert-Contains -Name "strategy574 packet supports next action board" -Text $strategy574Text -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        "prepare_profit_operator_next_action_board.ps1",
        "PROFIT_OPERATOR_NEXT_ACTION_BOARD",
        "profit_operator_next_action_board_packet",
        "strategy574/TinyLive governance blocker review",
        "READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention profit operator next action board" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-action-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-action-review-" + [guid]::NewGuid().ToString("N"))
$tempStrategyLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-action-strategy574-" + [guid]::NewGuid().ToString("N") + ".log")
$tempTinyLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-action-tiny-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $matrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS", "strategy485_oco_health_ok=true")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false")
                missingRequirements = @("governance drift and missed-opportunity review")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE")
                missingRequirements = @("fresh replayCandidateId rows")
                nextAction = "Collect replay snapshots before policy review."
            }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $matrixPacket)),
        "profit_operator_review_matrix_next_action=Review ready read-only items separately."
    )
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    Set-Content -LiteralPath (Join-Path $tempReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $tempMatrixPath

    Set-Content -LiteralPath $tempStrategyLog -Encoding UTF8 -Value @(
        "[strategy574-signal-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "origin_delta_status=DOCS_TOOLING_ONLY_DRIFT",
        "strategy574_near_buy=true",
        "governance_too_strict_7d_or_14d=false",
        "short_window_insufficient_data=true",
        "data_freshness_current_clean=false",
        "strategy574_terminal_reason=FIX_CURRENT_DATA_FRESHNESS_FIRST",
        "strategy574_policy_change_recommendation=DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
        "deploy_required_before_strategy574_review=false",
        "shadow_observation_review_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        'strategy574_review_missing_requirements=["current DataFreshness clean","wait for threshold-cross evidence","OCO preflight pass"]',
        "strategy574_signal_review_gate_status=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
        "strategy574_signal_review_next_action=Fix current DataFreshness before reviewing strategy 574 exploration.",
        "[strategy574-signal-review-gate] read-only check complete"
    )
    Set-Content -LiteralPath $tempTinyLog -Encoding UTF8 -Value @(
        "[tiny-live-loss-rca] read-only server-local MCP smoke",
        "  hardStopDetected=false",
        "  autoApprovalEligible=false",
        "  autoApprovalMode=BLOCKED",
        "  autoApprovalBlockers=[PREVIEW_NOT_READY:[NO_CURRENT_BUY_CANDIDATE, OCO_PREFLIGHT_FAILED], NO_CURRENT_BUY_CANDIDATE]",
        "  triggerEnabled=true triggerDryRun=false",
        "  executionEligible=false wouldExecute=false",
        "  terminalBlockers=[NO_CURRENT_BUY_CANDIDATE]",
        "  executedAutonomousTrades=1",
        "  successfulOcoAttachRate=+100.00%",
        "  OCOProtectionEffectiveness=PASS_ALL_EXECUTIONS_PROTECTED",
        "  completedTinyLiveSamples=2",
        "  falsePositiveCount=2",
        "  dailyLossBudgetBreached=false",
        "  canEnableProduction=false",
        "  canIncreaseDailyCap=false",
        "  rolloutBlockers=[LOOP_NOT_READY:WAIT_SIGNAL_BUY, READY_TICKS_LT_3, NO_CURRENT_BUY_CANDIDATE, COMPLETED_TINY_LIVE_SAMPLES_LT_3, FALSE_POSITIVE_COUNT_GT_1]",
        "  missing_tiny_live_fields=[]",
        "  missedOverallStatus=WARN",
        "  suspiciousNoBuyCount=20",
        "  falseBlockRiskCount=20",
        "  recommendedFix=Review near-threshold/no-buy candidates with high 1h forward return.",
        "[tiny-live-loss-rca] OK read-only check complete"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator next action board test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -Strategy574GateLogPath $tempStrategyLog -TinyLiveLossRcaLogPath $tempTinyLog -ReviewNotionalCapUsdt 15 -ObservationHours 48 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "profit operator next action board failed temp evidence reuse:`n$text"
    }
    foreach ($marker in @(
            "profit_operator_next_action_primary_focus=trailing-stop-dry-run-operator-review",
            "strategy574_tiny_live_risk_posture=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
            "profit_operator_next_action_board_status=READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE",
            '"packetType":"PROFIT_OPERATOR_NEXT_ACTION_BOARD"',
            '"proposalId":"strategy574-tiny-live-governance-review"',
            '"priorityClass":"P2_GOVERNANCE_BLOCKER_REVIEW_NOT_LIVE"',
            '"operatorDecisionOrder":["1. trailing-stop dry-run review","2. strategy485 risk-reduction shadow review","3. EntryDedup semantics shadow review","4. strategy574/TinyLive governance blocker review"]',
            '"sourceStrategy574TinyLivePacketStatus":"READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE"',
            "tiny_live_order_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "deploy_or_env_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only profit operator next-action board only"
        )) {
        Assert-Contains -Name "profit operator next action temp evidence reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator next action board unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    foreach ($path in @($tempMatrixPath, $tempStrategyLog, $tempTinyLog)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[profit-operator-next-action-board-test] OK"
