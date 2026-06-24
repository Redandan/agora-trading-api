param(
    [string]$ProfitImprovementLog = "target/profit-review/profit-improvement-review-issue6-refresh.log",
    [string]$Issue7BundleLog = "target/profit-review/issue7-post-deploy-read-only-bundle-refresh.log",
    [string]$ProfitEvidenceWatchLog = "target/profit-review/profit-evidence-watch-remaining-issues.log",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return "" }
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Read-Log {
    param([string]$Path)
    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{
            Path = $Path
            ResolvedPath = $resolved
            Text = ""
            Freshness = "MISSING"
            AgeMinutes = $null
        }
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    return [pscustomobject]@{
        Path = $Path
        ResolvedPath = $resolved
        Text = Get-Content -Raw -LiteralPath $resolved
        Freshness = if ($age -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        AgeMinutes = $age
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if (-not $List.Contains($Value)) { [void]$List.Add($Value) }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

$missing = [System.Collections.Generic.List[string]]::new()
$profit = Read-Log -Path $ProfitImprovementLog
$issue7 = Read-Log -Path $Issue7BundleLog
$watch = Read-Log -Path $ProfitEvidenceWatchLog

foreach ($log in @($profit, $issue7, $watch)) {
    if ($log.Freshness -eq "MISSING") {
        Add-MissingRequirement -List $missing -Value "missing log: $($log.Path)"
    } elseif ($log.Freshness -eq "STALE") {
        Add-MissingRequirement -List $missing -Value "stale log: $($log.Path)"
    }
}

$issue6DecisionRaw = Get-LastPrefixedValue -Text $profit.Text -Prefix "  profit_improvement_review_decision=" -Default "UNKNOWN"
$issue6Decision = $issue6DecisionRaw
if ($issue6DecisionRaw.TrimStart().StartsWith("{")) {
    try {
        $issue6DecisionObject = $issue6DecisionRaw | ConvertFrom-Json -ErrorAction Stop
        if ($null -ne $issue6DecisionObject.decision -and -not [string]::IsNullOrWhiteSpace([string]$issue6DecisionObject.decision)) {
            $issue6Decision = [string]$issue6DecisionObject.decision
        }
    } catch {
        Add-MissingRequirement -List $missing -Value "issue #6 profit improvement decision JSON parseable"
    }
}
$issue6TopCandidate = Get-LastPrefixedValue -Text $profit.Text -Prefix "  top_profit_improvement_candidate=" -Default "UNKNOWN"
$issue6Recommendation = Get-LastPrefixedValue -Text $profit.Text -Prefix "  profit_improvement_bundle_recommendation=" -Default "UNKNOWN"
$issue6CompleteRows = Get-LastPrefixedValue -Text $profit.Text -Prefix "  complete_replayable_candidate_rows=" -Default "UNKNOWN"
$issue6MissingRequirements = Get-LastPrefixedValue -Text $profit.Text -Prefix "  profit_improvement_missing_requirements=" -Default "[]"

$issue7Status = Get-LastPrefixedValue -Text $issue7.Text -Prefix "  issue7_post_deploy_read_only_bundle_status=" -Default "UNKNOWN"
$issue7CollectorStatus = Get-LastPrefixedValue -Text $issue7.Text -Prefix "  issue7_collector_post_activation_status=" -Default "UNKNOWN"
$issue7RemainingBlocker = Get-LastPrefixedValue -Text $issue7.Text -Prefix "  issue7_remaining_blocker=" -Default "UNKNOWN"
$issue7CloseAllowed = Get-LastPrefixedValue -Text $issue7.Text -Prefix "  issue7_close_allowed=" -Default "false"
$issue7LiveRelaxationAllowed = Get-LastPrefixedValue -Text $issue7.Text -Prefix "  issue7_live_relaxation_allowed=" -Default "false"

$watchStatus = Get-LastPrefixedValue -Text $watch.Text -Prefix "profit_evidence_watch_status=" -Default "UNKNOWN"
$watchReason = Get-LastPrefixedValue -Text $watch.Text -Prefix "profit_evidence_watch_reason=" -Default "UNKNOWN"
$watchDataFreshnessStatus = Get-LastPrefixedValue -Text $watch.Text -Prefix "profit_evidence_watch_data_freshness_status=" -Default "UNKNOWN"
$watchReplayRecommendation = Get-LastPrefixedValue -Text $watch.Text -Prefix "profit_evidence_watch_replay_recommendation=" -Default "UNKNOWN"

if ($issue6Decision -eq "UNKNOWN") { Add-MissingRequirement -List $missing -Value "issue #6 profit improvement decision marker" }
if ($issue7Status -eq "UNKNOWN") { Add-MissingRequirement -List $missing -Value "issue #7 post-deploy bundle status marker" }
if ($watchStatus -eq "UNKNOWN") { Add-MissingRequirement -List $missing -Value "profit evidence watch status marker" }

$issue6CloseAllowed = $false
$issue6Status = if ($issue6Decision -eq "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE") {
    "BLOCKED_NOT_CLOSEABLE_REPLAY_EVIDENCE_MISSING"
} elseif ($issue6Decision -eq "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
    "READY_FOR_REVIEW_NOT_LIVE"
} else {
    "UNKNOWN_OR_BLOCKED"
}

$issue7CloseBool = ($issue7CloseAllowed -eq "true")
$issue7LiveBool = ($issue7LiveRelaxationAllowed -eq "true")

$globalBlocker = "UNKNOWN"
if ($missing.Count -gt 0) {
    $globalBlocker = "LOCAL_STATUS_EVIDENCE_MISSING_OR_STALE"
} elseif ($issue7RemainingBlocker -eq "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS" -or $watchStatus -eq "PENDING_DATAFRESHNESS_CURRENT_SAMPLE") {
    $globalBlocker = "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
} elseif ($issue6Decision -eq "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE") {
    $globalBlocker = "COUNTERFACTUAL_REPLAY_EVIDENCE_MISSING"
} elseif ($issue7CloseBool -and $issue6Status -eq "READY_FOR_REVIEW_NOT_LIVE") {
    $globalBlocker = "NONE"
} else {
    $globalBlocker = "REMAINING_ISSUES_STILL_BLOCKED"
}

$overallStatus = if ($globalBlocker -eq "NONE") {
    "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
} elseif ($globalBlocker -eq "LOCAL_STATUS_EVIDENCE_MISSING_OR_STALE") {
    "BLOCKED_REFRESH_LOCAL_EVIDENCE_LOGS"
} else {
    "BLOCKED_NOT_CLOSEABLE"
}

$nextAction = if ($globalBlocker -eq "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS") {
    "Wait for fresh post-collector DataFreshnessGuard terminal rows, then rerun the bounded read-only watcher and #7 post-deploy bundle."
} elseif ($globalBlocker -eq "LOCAL_STATUS_EVIDENCE_MISSING_OR_STALE") {
    "Refresh the read-only logs for #6/#7 before using this consolidated packet."
} elseif ($globalBlocker -eq "COUNTERFACTUAL_REPLAY_EVIDENCE_MISSING") {
    "Collect replayCandidateId, entry/TP/SL, EV, OCO, and hard-gate snapshots before any shadow/live review."
} else {
    "Keep remaining issues open until read-only close criteria are explicitly satisfied."
}

$packet = [ordered]@{
    packetType = "REMAINING_OPEN_ISSUES_STATUS_PACKET"
    status = $overallStatus
    closeIssuesAllowed = $false
    liveRelaxationAllowed = $false
    deployOrEnvChangeAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    remainingIssueCount = 2
    issues = @(
        [ordered]@{
            number = 6
            title = "Profit review: DataFreshness false-kill alpha needs replayable evidence before live changes"
            status = $issue6Status
            closeAllowed = $issue6CloseAllowed
            decision = $issue6Decision
            decisionRaw = $issue6DecisionRaw
            topCandidate = $issue6TopCandidate
            recommendation = $issue6Recommendation
            completeReplayableCandidateRows = $issue6CompleteRows
            missingRequirements = $issue6MissingRequirements
        },
        [ordered]@{
            number = 7
            title = "Optimize BTCUSDT 1h filter-block false-kill rate before any live policy relaxation"
            status = $issue7Status
            collectorPostActivationStatus = $issue7CollectorStatus
            closeAllowed = $issue7CloseBool
            liveRelaxationAllowed = $issue7LiveBool
            remainingBlocker = $issue7RemainingBlocker
        }
    )
    evidenceWatch = [ordered]@{
        status = $watchStatus
        reason = $watchReason
        dataFreshnessStatus = $watchDataFreshnessStatus
        replayRecommendation = $watchReplayRecommendation
    }
    logFreshness = [ordered]@{
        profitImprovement = $profit.Freshness
        issue7Bundle = $issue7.Freshness
        profitEvidenceWatch = $watch.Freshness
    }
    globalBlocker = $globalBlocker
    missingRequirements = @($missing)
    nextAction = $nextAction
    notAuthorization = "read-only remaining open issues status packet only; does not run SSH, deploy, restart, reload nginx, change production env, close GitHub issues, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[remaining-open-issues-status] read-only local packet"
Write-Host "scope=READ_ONLY; reads existing local evidence logs only; no SSH, GitHub, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, nginx, or policy state changed."
Write-Host ("remaining_open_issues_status_packet=" + ($packet | ConvertTo-Json -Compress -Depth 12))
Write-Host "remaining_open_issues_status=$overallStatus"
Write-Host "remaining_open_issues_global_blocker=$globalBlocker"
Write-Host "issue6_status=$issue6Status"
Write-Host "issue6_decision=$issue6Decision"
Write-Host "issue6_complete_replayable_candidate_rows=$issue6CompleteRows"
Write-Host "issue7_status=$issue7Status"
Write-Host "issue7_remaining_blocker=$issue7RemainingBlocker"
Write-Host "profit_evidence_watch_status=$watchStatus"
Write-Host "close_issues_allowed=false"
Write-Host "live_relaxation_allowed=false"
Write-Host "remaining_open_issues_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireBlocked -and $overallStatus -ne "BLOCKED_NOT_CLOSEABLE" -and $overallStatus -ne "BLOCKED_REFRESH_LOCAL_EVIDENCE_LOGS") {
    throw "Remaining open issues were expected to stay blocked, got status=$overallStatus"
}
