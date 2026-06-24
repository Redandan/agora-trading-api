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
        "remaining_open_issues_status",
        "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS",
        "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE",
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

    $blocked = & $scriptPath -ProfitImprovementLog $profitLog -Issue7BundleLog $issue7Log -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -RequireBlocked *>&1
    $blockedText = $blocked -join "`n"

    Assert-Contains -Name "remaining open issues blocked status" -Text $blockedText -Pattern "remaining_open_issues_status=BLOCKED_NOT_CLOSEABLE"
    Assert-Contains -Name "remaining open issues blocker" -Text $blockedText -Pattern "remaining_open_issues_global_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues issue6" -Text $blockedText -Pattern "issue6_decision=BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE"
    Assert-Contains -Name "remaining open issues issue7" -Text $blockedText -Pattern "issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues not live" -Text $blockedText -Pattern "live_relaxation_allowed=false"

    $packetJson = ($blockedText -split "`r?`n" | Where-Object { $_.StartsWith("remaining_open_issues_status_packet=") } | Select-Object -Last 1).Substring("remaining_open_issues_status_packet=".Length)
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ($packet.status -ne "BLOCKED_NOT_CLOSEABLE") {
        throw "unexpected packet status: $($packet.status)"
    }
    if ($packet.globalBlocker -ne "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
        throw "unexpected packet blocker: $($packet.globalBlocker)"
    }

    $bundleWithoutSummaryLog = Join-Path $tempDir "issue7-bundle-without-summary.log"
    @"
[issue7-post-deploy-read-only-bundle] read-only bundle
===== BEGIN split-acceptance =====
[server-verify] FAIL: worktree commit 923d799 does not match origin/main aad8a6d
===== END split-acceptance exitCode=1 =====
"@ | Set-Content -LiteralPath $bundleWithoutSummaryLog -Encoding UTF8

    $fallback = & $scriptPath -ProfitImprovementLog $profitLog -Issue7BundleLog $bundleWithoutSummaryLog -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -RequireBlocked *>&1
    $fallbackText = $fallback -join "`n"
    Assert-Contains -Name "remaining open issues fallback status" -Text $fallbackText -Pattern "remaining_open_issues_status=BLOCKED_NOT_CLOSEABLE"
    Assert-Contains -Name "remaining open issues fallback issue7" -Text $fallbackText -Pattern "issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "remaining open issues fallback source freshness" -Text $fallbackText -Pattern "issue7PostActivation"

    $missing = & $scriptPath -ProfitImprovementLog (Join-Path $tempDir "missing-profit.log") -Issue7BundleLog $issue7Log -Issue7PostActivationLog $issue7PostActivationLog -ProfitEvidenceWatchLog $watchLog -RequireBlocked *>&1
    $missingText = $missing -join "`n"
    Assert-Contains -Name "remaining open issues missing status" -Text $missingText -Pattern "remaining_open_issues_status=BLOCKED_REFRESH_LOCAL_EVIDENCE_LOGS"
    Assert-Contains -Name "remaining open issues missing blocker" -Text $missingText -Pattern "remaining_open_issues_global_blocker=LOCAL_STATUS_EVIDENCE_MISSING_OR_STALE"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[remaining-open-issues-status-test] OK"
