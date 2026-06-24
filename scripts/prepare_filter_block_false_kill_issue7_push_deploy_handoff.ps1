param(
    [string]$PostActivationLog = "target/profit-review/issue7-collector-post-activation-status-refresh.log",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
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

$repoRoot = Split-Path -Parent $PSScriptRoot
$postActivationPath = Resolve-RepoPath $PostActivationLog
$missingRequirements = [System.Collections.Generic.List[string]]::new()

$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCountText = (& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim()
$behindCountText = (& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim()
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$aheadCount = [int]$aheadCountText
$behindCount = [int]$behindCountText
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

if (-not $worktreeClean) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before push/deploy handoff"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$postActivationText = ""
$postActivationFreshness = "MISSING"
$postActivationAgeMinutes = $null
if (-not (Test-Path -LiteralPath $postActivationPath)) {
    Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 post-activation status log"
} else {
    $item = Get-Item -LiteralPath $postActivationPath
    $postActivationAgeMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    $postActivationFreshness = if ($postActivationAgeMinutes -le 180) { "FRESH" } else { "STALE" }
    if ($postActivationFreshness -ne "FRESH") {
        Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 post-activation status log"
    }
    $postActivationText = Get-Content -Raw -LiteralPath $postActivationPath
}

$postActivationStatus = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_collector_post_activation_status=" -Default "UNKNOWN"
$remainingBlocker = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_remaining_blocker=" -Default "UNKNOWN"
$closeAllowed = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_close_allowed=" -Default "false"
$liveRelaxationAllowed = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_live_relaxation_allowed=" -Default "false"
$deployOrEnvAllowed = Get-LastPrefixedValue -Text $postActivationText -Prefix "deploy_or_env_change_allowed=" -Default "false"

if ($postActivationStatus -ne "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    Add-MissingRequirement -List $missingRequirements -Value "post-activation status is deploy-currentness blocker"
}
if ($remainingBlocker -ne "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    Add-MissingRequirement -List $missingRequirements -Value "remaining blocker is deploy currentness"
}
if ($closeAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 remains not closeable"
}
if ($liveRelaxationAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "live relaxation remains blocked"
}
if ($deployOrEnvAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "packet itself does not authorize deploy/env change"
}

$status = "NOT_READY"
$decision = "REFRESH_ISSUE7_POST_ACTIVATION_STATUS"
$nextAction = "Refresh issue #7 post-activation status before preparing push/deploy handoff."
if ($missingRequirements.Count -eq 0 -and $aheadCount -gt 0) {
    $status = "READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED"
    $decision = "REQUEST_SEPARATE_PUSH_DEPLOY_AUTHORIZATION"
    $nextAction = "Request explicit push + deploy + read-only post-deploy verification authorization; do not relax live policy or close issue #7."
} elseif ($missingRequirements.Count -eq 0 -and $aheadCount -eq 0) {
    $status = "NO_LOCAL_PUSH_REQUIRED_DEPLOY_RECHECK_NEEDED"
    $decision = "RERUN_DEPLOY_CURRENTNESS_EVIDENCE"
    $nextAction = "Rerun production currentness and post-activation evidence before requesting deploy."
}

$packet = [ordered]@{
    packetType = "ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET"
    status = $status
    decision = $decision
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    postActivationLog = $PostActivationLog
    postActivationLogFreshness = $postActivationFreshness
    postActivationLogAgeMinutes = $postActivationAgeMinutes
    postActivationStatus = $postActivationStatus
    remainingBlocker = $remainingBlocker
    closeAllowed = $false
    liveRelaxationAllowed = $false
    deployOrEnvChangeAllowed = $false
    requiredAuthorization = @(
        "git push origin main",
        "deploy current origin/main to production",
        "read-only post-deploy verification only"
    )
    requiredPostDeployReadOnlyVerification = @(
        ".\scripts\smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1 -RequireBlocked",
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_filter_block_false_kill_issue7_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1 *> target\profit-review\issue7-df-replay-observation-latest.log",
        ".\scripts\prepare_data_freshness_replay_evidence_readiness_ssh.ps1 -ReviewDays 14 -ReplayIdDays 3 -Limit 200",
        ".\scripts\prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1 -RequireBlocked"
    )
    forbiddenActions = @(
        "close issue #7",
        "relax DataFreshnessGuard",
        "enable live trading/staged-add/TinyLive",
        "enable scheduler mutation",
        "place orders",
        "modify OCO",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only issue #7 push/deploy handoff packet only; does not push, deploy, restart, reload nginx, change production env, close issue #7, relax DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable scheduler, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[issue7-push-deploy-handoff] read-only packet"
Write-Host "scope=READ_ONLY; reads local git metadata and saved issue #7 post-activation log only; no push, deploy, restart, nginx reload, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host "issue7_push_deploy_handoff_packet=$($packet | ConvertTo-Json -Compress -Depth 8)"
Write-Host "issue7_push_deploy_handoff_status=$status"
Write-Host "issue7_push_deploy_handoff_decision=$decision"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host "issue7_remaining_blocker=$remainingBlocker"
Write-Host "issue7_close_allowed=false"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "issue7_push_deploy_handoff_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_push_deploy_handoff_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED") {
    throw "Issue #7 push/deploy handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
