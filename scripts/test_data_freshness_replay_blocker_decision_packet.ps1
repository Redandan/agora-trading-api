Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_blocker_decision_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[data-freshness-replay-blocker-decision-packet] read-only packet",
        "scope=READ_ONLY",
        "latest-profit-operator-matrix.path",
        "DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_PACKET",
        "READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE",
        "WAIT_FOR_REPLAYABLE_CANDIDATE_EVIDENCE",
        "data_freshness_replay_blocker_decision_packet",
        "data_freshness_replay_blocker_decision_status",
        "complete_replayable_candidate_rows",
        "missing_counterfactual_fields",
        "shadow_candidate_review_allowed",
        "data_freshness_policy_relaxation_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "notAuthorization=read-only DataFreshness replay blocker operator decision packet only",
        "RequireBlocked"
    )) {
    Assert-Contains -Name "DataFreshness replay blocker decision marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_data_freshness_replay_blocker_decision_packet.ps1",
        "DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_PACKET",
        "data_freshness_replay_blocker_decision_packet",
        "DataFreshness replay blocker decision packet",
        "complete_replayable_candidate_rows=0"
    )) {
    Assert-Contains -Name "docs mention DataFreshness replay blocker packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-blocker-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-blocker-review-" + [guid]::NewGuid().ToString("N"))
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
                candidate = "Trailing stop + strategy 485 aged negative-EV review"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                priority = "P1"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                candidate = "DataFreshness false-kill shadow/counterfactual replay"
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
                    "collector_status_counts=N/A:110",
                    "hard_gate_preview_status_counts=N/A:110",
                    "replay_input_next_action=wait_for_new_replay_id_rows_before_shadow_review",
                    "complete_replayable_candidate_rows=0",
                    "preview_only_input_rows=0",
                    "missing_counterfactual_fields=[""liveSignalId"",""replayCandidateId"",""explicit entry/TP/SL candidate plan"",""EV snapshot"",""OCO plan"",""complete replayable candidate rows""]"
                )
                missingRequirements = @(
                    "fresh replayCandidateId rows",
                    "entry/TP/SL candidate snapshot",
                    "EV and OCO preflight snapshots",
                    "complete DataFreshness replayable candidate rows"
                )
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
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness replay blocker decision packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -RequireBlocked 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "DataFreshness replay blocker decision packet failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "data_freshness_replay_lane_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "data_freshness_replay_ready_for_operator_review=false",
            "counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "complete_replayable_candidate_rows=0",
            "shadow_candidate_review_allowed=false",
            "data_freshness_policy_relaxation_allowed=false",
            "data_freshness_replay_blocker_decision_status=READY_FOR_DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_NOT_LIVE",
            '"packetType":"DATAFRESHNESS_REPLAY_BLOCKER_OPERATOR_DECISION_PACKET"',
            '"blockerDecision":"WAIT_FOR_REPLAYABLE_CANDIDATE_EVIDENCE"',
            '"completeReplayableCandidateRows":0',
            '"shadowCandidateReviewAllowed":"false"',
            "order_allowed=false",
            "notAuthorization=read-only DataFreshness replay blocker operator decision packet only"
        )) {
        Assert-Contains -Name "DataFreshness replay blocker latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness replay blocker decision unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[data-freshness-replay-blocker-decision-packet-test] OK"
