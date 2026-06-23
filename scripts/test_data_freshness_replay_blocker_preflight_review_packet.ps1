Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_blocker_preflight_review_packet.ps1"
$decisionPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_blocker_decision_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$decisionText = Get-Content -Raw -LiteralPath $decisionPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[data-freshness-replay-blocker-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_data_freshness_replay_blocker_decision_packet.ps1",
        "DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_NOT_LIVE",
        "PREPARE_REVIEW_ONLY_DATAFRESHNESS_REPLAY_BLOCKER_REVIEW",
        "data_freshness_replay_blocker_preflight_review_packet",
        "data_freshness_replay_blocker_preflight_status",
        "data_freshness_policy_relaxation_allowed=false",
        "data_freshness_shadow_review_allowed=false",
        "collector_activation_allowed=false",
        "staged_add_execution_allowed=false",
        "tiny_live_execution_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only DataFreshness replay blocker preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "DataFreshness replay blocker preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_PACKET",
        "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE",
        "data_freshness_replay_blocker_decision_packet",
        "WAIT_FOR_REPLAYABLE_CANDIDATE_EVIDENCE"
    )) {
    Assert-Contains -Name "DataFreshness blocker decision packet supports preflight" -Text $decisionText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_data_freshness_replay_blocker_preflight_review_packet.ps1",
        "DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_PACKET",
        "data_freshness_replay_blocker_preflight_review_packet",
        "DataFreshness replay blocker preflight review packet",
        "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention DataFreshness replay blocker preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-blocker-preflight-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-blocker-preflight-review-" + [guid]::NewGuid().ToString("N"))
try {
    $matrixPacket = [pscustomobject]@{
        packetType = "PROFIT_OPERATOR_REVIEW_MATRIX"
        status = "HAS_REVIEW_READY_ITEMS_NOT_LIVE"
        symbol = "BTCUSDT"
        readinessStatus = "BLOCKED_ENTRY_FILTER_REVIEW"
        evidenceWatchStatus = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
        exitSideStatus = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
        dataFreshnessShadowCandidateStatus = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                priority = "P1"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                status = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
                priority = "P2"
                readyForOperatorReview = $false
                evidenceMarkers = @(
                    "profit_evidence_watch_reason=NO_CURRENT_SAMPLE",
                    "profit_evidence_watch_replay_recommendation=COLLECT_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW",
                    "data_freshness_shadow_candidate_packet_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
                    "counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
                    "shadow_candidate_review_allowed=false",
                    "replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
                    "complete_replayable_candidate_rows=0",
                    "preview_only_input_rows=0",
                    "missing_counterfactual_fields=[""liveSignalId"",""replayCandidateId"",""explicit entry/TP/SL candidate plan"",""EV snapshot"",""OCO plan"",""complete replayable candidate rows""]"
                )
                missingRequirements = @("fresh replayCandidateId rows", "complete DataFreshness replayable candidate rows")
                nextAction = "Rerun bounded evidence watch after new DataFreshnessGuard rows are expected."
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

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for DataFreshness replay blocker preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "DataFreshness replay blocker preflight failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_decision_packet_status=READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE",
            "source_matrix_freshness_status=FRESH",
            "source_data_freshness_replay_lane_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "source_data_freshness_replay_ready_for_operator_review=false",
            "source_complete_replayable_candidate_rows=0",
            "source_shadow_candidate_review_allowed=false",
            "data_freshness_replay_blocker_preflight_decision=PREPARE_REVIEW_ONLY_DATAFRESHNESS_REPLAY_BLOCKER_REVIEW",
            "data_freshness_replay_blocker_preflight_status=READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_NOT_LIVE",
            '"packetType":"DATAFRESHNESS_REPLAY_BLOCKER_PREFLIGHT_REVIEW_PACKET"',
            '"preflightDecision":"PREPARE_REVIEW_ONLY_DATAFRESHNESS_REPLAY_BLOCKER_REVIEW"',
            '"dataFreshnessPolicyRelaxationAllowed":false',
            '"dataFreshnessShadowReviewAllowed":false',
            '"collectorActivationAllowed":false',
            '"tinyLiveExecutionAllowed":false',
            '"telegramSendAllowed":false',
            "data_freshness_policy_relaxation_allowed=false",
            "data_freshness_shadow_review_allowed=false",
            "collector_activation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only DataFreshness replay blocker preflight review packet only"
        )) {
        Assert-Contains -Name "DataFreshness replay blocker preflight latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness replay blocker preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) { Remove-Item -LiteralPath $tempMatrixPath -Force }
    if (Test-Path -LiteralPath $tempReviewDir) { Remove-Item -LiteralPath $tempReviewDir -Recurse -Force }
}

Write-Host "[data-freshness-replay-blocker-preflight-review-packet-test] OK"
