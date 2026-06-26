Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_remaining_open_issues_status.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "REMAINING_OPEN_ISSUES_STATUS_PACKET",
        "activeOpenIssueNumbers = @(6, 7, 8)",
        "remainingIssueCount = 3",
        "completedIssueContext",
        "closed_issue_context_numbers=9,10,11,12",
        "remaining_open_issues_status",
        "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS",
        "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE",
        "issue8_status",
        "issue8_upstream_rca",
        "BUY_LIKE_FLOW_NOT_REACHING_DATAFRESHNESS_TERMINAL",
        "closed_issue9_context_status",
        "closed_issue10_context_status",
        "closed_issue11_context_status",
        "closed_issue12_context_status",
        "profit_evidence_watch_status",
        "read-only remaining open issues status packet only",
        "does not run SSH"
    )) {
    Assert-Contains -Name "remaining open issues script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "ssh ",
        "gh issue",
        "bash deploy.sh",
        "git push",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "remaining open issues status script must not contain mutation/external marker: $forbidden"
    }
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("remaining-open-issues-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $profitLog = Join-Path $tempDir "profit.log"
    $issue7Log = Join-Path $tempDir "issue7.log"
    $issue7PostActivationLog = Join-Path $tempDir "issue7-post-activation.log"
    $watchLog = Join-Path $tempDir "watch.log"
    $buyLikeLossLog = Join-Path $tempDir "buy-like-loss.log"
    $strategy485GateLog = Join-Path $tempDir "strategy485-gate.log"
    $trailingStopLog = Join-Path $tempDir "trailing-stop.log"

    @"
Profit Improvement Bundle Summary:
  origin_delta_status=CURRENT_ORIGIN_MAIN
  complete_replayable_candidate_rows=0
  profit_improvement_review_decision={"decision":"BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE","topCandidate":"DataFreshness false-kill counterfactual"}
  profit_improvement_missing_requirements=["fresh replayCandidateId rows"]
  top_profit_improvement_candidate=DataFreshness false-kill counterfactual
  profit_improvement_bundle_recommendation=COLLECT_DATAFRESHNESS_REPLAYABLE_CANDIDATE_SNAPSHOTS
  notAuthorization=read-only review evidence only
"@ | Set-Content -LiteralPath $profitLog -Encoding UTF8

    @"
Issue #7 Post-Deploy Read-Only Bundle Summary:
  issue7_post_deploy_read_only_bundle_status=BLOCKED_NOT_CLOSEABLE
  issue7_collector_post_activation_status=BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS
  issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS
  issue7_close_allowed=false
  issue7_live_relaxation_allowed=false
  deploy_or_env_change_allowed=false
"@ | Set-Content -LiteralPath $issue7Log -Encoding UTF8

    @"
[issue7-collector-post-activation-status] read-only packet
issue7_collector_post_activation_status=BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS
issue7_close_allowed=false
issue7_live_relaxation_allowed=false
fresh_post_collector_data_freshness_rows_observed=false
complete_replayable_candidate_rows=0
issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS
"@ | Set-Content -LiteralPath $issue7PostActivationLog -Encoding UTF8

    @"
profit_evidence_watch_status=PENDING_DATAFRESHNESS_CURRENT_SAMPLE
profit_evidence_watch_reason=NO_CURRENT_SAMPLE
profit_evidence_watch_data_freshness_status=NO_CURRENT_SAMPLE
profit_evidence_watch_replay_recommendation=COLLECT_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW
notAuthorization=read-only evidence watcher only
"@ | Set-Content -LiteralPath $watchLog -Encoding UTF8

    @"
[buy-like-candidate-loss-review-packet] read-only packet
buy_like_candidate_loss_review_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE
buy_like_candidate_loss_dominant_blocker=ENTRY_SKIP:EntryDedup
buy_like_candidate_loss_14d_rows=84
buy_like_candidate_loss_30d_rows=738
issue8_status=BLOCKED_NO_FRESH_DATAFRESHNESS_TERMINAL_ROWS
issue8_recent_could_produce_data_freshness_terminal=false
issue12_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE
issue12_close_readiness=OPERATOR_REVIEW_READY_NOT_LIVE
issue12_next_evidence_target=Use the EntryDedup/ShadowExecutionIntent operator packet; keep EntryDedup policy unchanged.
close_issue12_allowed=true
close_issue6_or_7_allowed=false
notAuthorization=read-only BUY-like candidate loss review packet only
"@ | Set-Content -LiteralPath $buyLikeLossLog -Encoding UTF8

    @"
[strategy485-position-review-gate] read-only gate
strategy485_position_risk_recommendation=WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED
position_or_oco_mutation_allowed=false
strategy485_review_missing_requirements=[]
strategy485_position_review_gate_status=WATCH_ONLY
notAuthorization=read-only gate only
"@ | Set-Content -LiteralPath $strategy485GateLog -Encoding UTF8

    @"
[trailing-stop-dry-run-operator-decision-packet] read-only packet
trailing_stop_dry_run_primary_focus=trailing-stop-dry-run-operator-review
trailing_stop_dry_run_operator_decision_status=NOT_READY
position_or_oco_mutation_allowed=false
order_allowed=false
notAuthorization=read-only trailing-stop dry-run operator decision packet only
"@ | Set-Content -LiteralPath $trailingStopLog -Encoding UTF8

    $blocked = & $scriptPath -ProfitImprovementLog $profitLog -Issue7BundleLog $issue7Log -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -BuyLikeCandidateLossReviewLog $buyLikeLossLog -Strategy485PositionGateLog $strategy485GateLog -TrailingStopDryRunDecisionLog $trailingStopLog -RequireBlocked *>&1
    $blockedText = $blocked -join "`n"

    Assert-Contains -Name "remaining open issues blocked status" -Text $blockedText -Pattern "remaining_open_issues_status=BLOCKED_NOT_CLOSEABLE"
    Assert-Contains -Name "remaining open issues blocker" -Text $blockedText -Pattern "remaining_open_issues_global_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues issue6" -Text $blockedText -Pattern "issue6_decision=BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE"
    Assert-Contains -Name "remaining open issues issue7" -Text $blockedText -Pattern "issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues issue8" -Text $blockedText -Pattern "issue8_status=BLOCKED_NO_FRESH_DATAFRESHNESS_TERMINAL_ROWS"
    Assert-Contains -Name "remaining open issues issue8 upstream rca" -Text $blockedText -Pattern "issue8_upstream_rca=BUY_LIKE_FLOW_NOT_REACHING_DATAFRESHNESS_TERMINAL"
    Assert-Contains -Name "remaining open issues next action uses upstream RCA" -Text $blockedText -Pattern "Do not only wait for DataFreshness rows"
    Assert-Contains -Name "remaining open issues active issue numbers" -Text $blockedText -Pattern "active_open_issue_numbers=6,7,8"
    Assert-Contains -Name "remaining open issues active count" -Text $blockedText -Pattern "active_remaining_issue_count=3"
    Assert-Contains -Name "remaining open issues closed context numbers" -Text $blockedText -Pattern "closed_issue_context_numbers=9,10,11,12"
    Assert-Contains -Name "remaining open issues issue9 context" -Text $blockedText -Pattern "closed_issue9_context_status=WATCH_ONLY"
    Assert-Contains -Name "remaining open issues issue10 context" -Text $blockedText -Pattern "closed_issue10_context_status=COMPLETED_STATUS_PACKET_ARCHIVED_CONTEXT_NOT_ACTIVE"
    Assert-Contains -Name "remaining open issues issue11 context" -Text $blockedText -Pattern "closed_issue11_context_status=NOT_READY"
    Assert-Contains -Name "remaining open issues issue12 context" -Text $blockedText -Pattern "closed_issue12_context_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE"
    Assert-Contains -Name "remaining open issues not live" -Text $blockedText -Pattern "live_relaxation_allowed=false"

    $packetJson = ($blockedText -split "`r?`n" | Where-Object { $_.StartsWith("remaining_open_issues_status_packet=") } | Select-Object -Last 1).Substring("remaining_open_issues_status_packet=".Length)
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ($packet.status -ne "BLOCKED_NOT_CLOSEABLE") {
        throw "unexpected packet status: $($packet.status)"
    }
    if ($packet.globalBlocker -ne "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
        throw "unexpected packet blocker: $($packet.globalBlocker)"
    }
    $issue8 = @($packet.issues | Where-Object { [int]$_.number -eq 8 } | Select-Object -First 1)
    if (-not $issue8 -or $issue8.upstreamRca -ne "BUY_LIKE_FLOW_NOT_REACHING_DATAFRESHNESS_TERMINAL") {
        throw "unexpected issue8 upstream RCA: $($issue8.upstreamRca)"
    }
    if ($packet.remainingIssueCount -ne 3) {
        throw "unexpected remaining issue count: $($packet.remainingIssueCount)"
    }
    if (@($packet.issues).Count -ne 3) {
        throw "unexpected issue row count: $(@($packet.issues).Count)"
    }
    $activeNumbers = @($packet.issues | ForEach-Object { [int]$_.number })
    if (($activeNumbers -join ",") -ne "6,7,8") {
        throw "unexpected active issue numbers: $($activeNumbers -join ',')"
    }
    $completedNumbers = @($packet.completedIssueContext | ForEach-Object { [int]$_.number })
    if (($completedNumbers -join ",") -ne "9,10,11,12") {
        throw "unexpected completed context issue numbers: $($completedNumbers -join ',')"
    }
    if (@($packet.completedIssueContext | Where-Object { $_.state -ne "CLOSED" }).Count -ne 0) {
        throw "completed context rows must be marked CLOSED"
    }

    $bundleWithoutSummaryLog = Join-Path $tempDir "issue7-bundle-without-summary.log"
    @"
[issue7-post-deploy-read-only-bundle] read-only bundle
===== BEGIN split-acceptance =====
[server-verify] FAIL: worktree commit 923d799 does not match origin/main aad8a6d
===== END split-acceptance exitCode=1 =====
"@ | Set-Content -LiteralPath $bundleWithoutSummaryLog -Encoding UTF8

    $fallback = & $scriptPath -ProfitImprovementLog $profitLog -Issue7BundleLog $bundleWithoutSummaryLog -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -BuyLikeCandidateLossReviewLog $buyLikeLossLog -Strategy485PositionGateLog $strategy485GateLog -TrailingStopDryRunDecisionLog $trailingStopLog -RequireBlocked *>&1
    $fallbackText = $fallback -join "`n"
    Assert-Contains -Name "remaining open issues fallback status" -Text $fallbackText -Pattern "remaining_open_issues_status=BLOCKED_NOT_CLOSEABLE"
    Assert-Contains -Name "remaining open issues fallback issue7" -Text $fallbackText -Pattern "issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues fallback source freshness" -Text $fallbackText -Pattern "issue7PostActivation"

    $missing = & $scriptPath -ProfitImprovementLog (Join-Path $tempDir "missing-profit.log") -Issue7BundleLog $issue7Log -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -BuyLikeCandidateLossReviewLog $buyLikeLossLog -Strategy485PositionGateLog $strategy485GateLog -TrailingStopDryRunDecisionLog $trailingStopLog -RequireBlocked *>&1
    $missingText = $missing -join "`n"
    Assert-Contains -Name "remaining open issues missing status" -Text $missingText -Pattern "remaining_open_issues_status=BLOCKED_REFRESH_LOCAL_EVIDENCE_LOGS"
    Assert-Contains -Name "remaining open issues missing blocker" -Text $missingText -Pattern "remaining_open_issues_global_blocker=LOCAL_STATUS_EVIDENCE_MISSING_OR_STALE"

    $missingClosedContext = & $scriptPath -ProfitImprovementLog $profitLog -Issue7BundleLog $issue7Log -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -BuyLikeCandidateLossReviewLog $buyLikeLossLog -Strategy485PositionGateLog (Join-Path $tempDir "missing-strategy485.log") -TrailingStopDryRunDecisionLog (Join-Path $tempDir "missing-trailing.log") -RequireBlocked *>&1
    $missingClosedContextText = $missingClosedContext -join "`n"
    Assert-Contains -Name "closed context missing still blocked by active issues" -Text $missingClosedContextText -Pattern "remaining_open_issues_status=BLOCKED_NOT_CLOSEABLE"
    Assert-Contains -Name "closed context missing does not refresh active evidence" -Text $missingClosedContextText -Pattern "remaining_open_issues_global_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "closed context unknown issue9" -Text $missingClosedContextText -Pattern "closed_issue9_context_status=UNKNOWN"
    Assert-Contains -Name "closed context unknown issue11" -Text $missingClosedContextText -Pattern "closed_issue11_context_status=UNKNOWN"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[remaining-open-issues-status-test] OK"
