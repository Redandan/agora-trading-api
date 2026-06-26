param(
    [string]$BuyLikeLossReviewLogPath = "target/profit-review/buy-like-candidate-loss-review-latest.log",
    [string]$NoTerminalContinuityLogPath = "target/profit-review/no-terminal-followup-continuity-current.log",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-RelativeOrRootedPathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Read-FreshLog {
    param([string]$Name, [string]$Path, [int]$MaxAge)
    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $ageMinutes = [Math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $Path
        AgeMinutes = $ageMinutes
        Fresh = $ageMinutes -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Convert-JsonArrayValue {
    param([string]$Text, [string]$Prefix)
    $value = Get-LastPrefixedValue -Text $Text -Prefix $Prefix -Default "[]"
    if ([string]::IsNullOrWhiteSpace($value)) { return @() }
    try {
        return @($value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Get-RowsByClassification {
    param([object[]]$Items, [string]$Classification)
    $item = @($Items | Where-Object {
        $property = $_.PSObject.Properties["classification"]
        $null -ne $property -and [string]$property.Value -eq $Classification
    } | Select-Object -First 1)
    if (-not $item) { return 0 }
    $rows = $item[0].PSObject.Properties["rows"]
    if ($null -eq $rows) { return 0 }
    return Get-IntValue ([string]$rows.Value)
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if (-not $List.Contains($Value)) { [void]$List.Add($Value) }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) { throw "MaxAgeMinutes must be between 1 and 10080." }
foreach ($path in @($BuyLikeLossReviewLogPath, $NoTerminalContinuityLogPath)) {
    Assert-RelativeOrRootedPathSafe -Name "LogPath" -Value $path
}

$loss = Read-FreshLog -Name "buy-like-loss-review" -Path $BuyLikeLossReviewLogPath -MaxAge $MaxAgeMinutes
$continuity = Read-FreshLog -Name "no-terminal-continuity" -Path $NoTerminalContinuityLogPath -MaxAge $MaxAgeMinutes
$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($loss, $continuity)) {
    if (-not $log.Fresh) { Add-Unique -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes" }
}

$lossStatus = Get-LastPrefixedValue -Text $loss.Text -Prefix "buy_like_candidate_loss_review_status=" -Default "UNKNOWN"
$issue8Status = Get-LastPrefixedValue -Text $loss.Text -Prefix "issue8_status=" -Default "UNKNOWN"
$issue12Target = Get-LastPrefixedValue -Text $loss.Text -Prefix "issue12_next_evidence_target=" -Default "UNKNOWN"
$continuityStatus = Get-LastPrefixedValue -Text $continuity.Text -Prefix "no_terminal_continuity_review_status=" -Default "UNKNOWN"
$noTerminalRows = Get-IntValue (Get-LastPrefixedValue -Text $continuity.Text -Prefix "no_terminal_followup_rows=" -Default "0")
$classification = @(Convert-JsonArrayValue -Text $continuity.Text -Prefix "no_terminal_continuity_classification=")
$terminalAfterDistribution = @(Convert-JsonArrayValue -Text $continuity.Text -Prefix "terminal_after_primary_window_distribution=")

if ($lossStatus -eq "UNKNOWN") { Add-Unique -List $missing -Value "buy-like loss status marker" }
if ($continuityStatus -eq "UNKNOWN") { Add-Unique -List $missing -Value "no-terminal continuity status marker" }
if ($noTerminalRows -lt 1) { Add-Unique -List $missing -Value "no-terminal continuity rows present" }

$terminalAfterPrimaryRows = Get-RowsByClassification -Items $classification -Classification "TERMINAL_AFTER_PRIMARY_WINDOW"
$differentIntervalRows = Get-RowsByClassification -Items $classification -Classification "SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL"
$nearbyTerminalRows = Get-RowsByClassification -Items $classification -Classification "OTHER_TERMINAL_NEARBY"
$pendingPrimaryRows = Get-RowsByClassification -Items $classification -Classification "PENDING_PRIMARY_FOLLOWUP_WINDOW"
$nonTerminalSameKeyRows = Get-RowsByClassification -Items $classification -Classification "NON_TERMINAL_SAME_KEY_CONTINUED"
$noExtendedRows = Get-RowsByClassification -Items $classification -Classification "NO_FOLLOWUP_WITHIN_EXTENDED_WINDOW"

$explainedRows = $terminalAfterPrimaryRows + $differentIntervalRows + $nearbyTerminalRows
$residualRows = [Math]::Max(0, $noTerminalRows - $explainedRows)
$explainedPct = if ($noTerminalRows -gt 0) { [Math]::Round(($explainedRows * 100.0) / $noTerminalRows, 2) } else { 0 }
$residualPct = if ($noTerminalRows -gt 0) { [Math]::Round(($residualRows * 100.0) / $noTerminalRows, 2) } else { 0 }

$ready = $missing.Count -eq 0
$status = if ($ready) { "READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$recommendedMatcher = if ($terminalAfterPrimaryRows -gt ($differentIntervalRows + $nearbyTerminalRows)) {
    "EXTEND_PRIMARY_WINDOW_THEN_RECHECK_INTERVAL_LINKING"
} elseif ($differentIntervalRows -gt 0) {
    "ADD_INTERVAL_AWARE_STRATEGY_LINKING_REVIEW"
} elseif ($nearbyTerminalRows -gt 0) {
    "REVIEW_NEARBY_TERMINAL_LINKING"
} else {
    "KEEP_CURRENT_MATCHER_AND_COLLECT_MORE_ROWS"
}

$packet = [pscustomobject]@{
    packetType = "BUY_LIKE_CONTINUITY_MATCHER_REVIEW_PACKET"
    status = $status
    issue8Status = $issue8Status
    sourceLogs = [pscustomobject]@{
        buyLikeLossReview = $BuyLikeLossReviewLogPath
        noTerminalContinuity = $NoTerminalContinuityLogPath
    }
    sourceLogFreshness = @($loss, $continuity | ForEach-Object {
        [pscustomobject]@{ name = $_.Name; ageMinutes = $_.AgeMinutes; fresh = $_.Fresh }
    })
    noTerminalRows = $noTerminalRows
    explainedAsMatcherArtifactRows = $explainedRows
    explainedAsMatcherArtifactPct = $explainedPct
    residualPotentialTrueGapRows = $residualRows
    residualPotentialTrueGapPct = $residualPct
    classification = @($classification)
    terminalAfterPrimaryWindowDistribution = @($terminalAfterDistribution)
    matcherReviewRecommendation = $recommendedMatcher
    currentIssue12NextEvidenceTarget = $issue12Target
    proposedNextEvidenceTarget = "Review longer-window and interval-aware BUY-like continuity matching before treating NO_TERMINAL_FOLLOWUP as a real pipeline gap; keep EntryDedup/DataFreshness/live policy unchanged."
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        closeIssue8Allowed = $false
        livePolicyChangeAllowed = $false
        matcherPolicyChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this packet to review continuity matcher semantics only; do not relax DataFreshnessGuard, EntryDedup, strategy activation, scheduler, or live execution."
    notAuthorization = "read-only BUY-like continuity matcher review packet only; does not authorize live trading, matcher/runtime behavior changes, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[buy-like-continuity-matcher-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved local evidence logs only; no SSH, GitHub, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "buy_like_continuity_matcher_review_status=$status"
Write-Host "no_terminal_followup_rows=$noTerminalRows"
Write-Host "matcher_artifact_explained_rows=$explainedRows"
Write-Host "matcher_artifact_explained_pct=$explainedPct"
Write-Host "residual_potential_true_gap_rows=$residualRows"
Write-Host "residual_potential_true_gap_pct=$residualPct"
Write-Host "terminal_after_primary_window_rows=$terminalAfterPrimaryRows"
Write-Host "same_strategy_different_interval_rows=$differentIntervalRows"
Write-Host "other_terminal_nearby_rows=$nearbyTerminalRows"
Write-Host "pending_primary_window_rows=$pendingPrimaryRows"
Write-Host "non_terminal_same_key_continued_rows=$nonTerminalSameKeyRows"
Write-Host "no_followup_within_extended_window_rows=$noExtendedRows"
Write-Host "matcher_review_recommendation=$recommendedMatcher"
Write-Host ("buy_like_continuity_matcher_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "close_issue8_allowed=false"
Write-Host "matcher_policy_change_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[buy-like-continuity-matcher-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "BUY-like continuity matcher review packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
