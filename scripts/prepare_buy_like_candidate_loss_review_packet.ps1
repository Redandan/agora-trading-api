param(
    [string]$BuyLike14dLogPath = "target/profit-review/buy-like-candidate-progression-issue12-14d-latest.log",
    [string]$BuyLike30dLogPath = "target/profit-review/buy-like-candidate-progression-issue12-30d-latest.log",
    [string]$DataFreshnessReadinessLogPath = "target/profit-review/issue7-df-replay-candidate-id-maintenance-latest.log",
    [string]$SignalCorrectnessLogPath = "target/profit-review/signal-correctness-maintenance-latest.log",
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
        ResolvedPath = $resolved
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

function Get-FirstRegexGroup {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success -or $match.Groups.Count -lt 2) { return $Default }
    return $match.Groups[1].Value.Trim()
}

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-ClassificationRanking {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $sectionMatch = [regex]::Match(
        $Text,
        "buy_like_followup_classification:\s*(?<section>(?:\r?\n\s*-\s+[^\r\n]+)+)",
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $sectionMatch.Success) { return @() }
    foreach ($match in [regex]::Matches($sectionMatch.Groups["section"].Value, "^\s*-\s+([^=\r\n]+)=(\d+)\s*$", [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        $name = $match.Groups[1].Value.Trim()
        $family = if ($name.StartsWith("ENTRY_SKIP:")) {
            $name.Substring("ENTRY_SKIP:".Length)
        } elseif ($name.StartsWith("FILTER_BLOCK:")) {
            $name.Substring("FILTER_BLOCK:".Length)
        } else {
            $name
        }
        $category = if ($name.StartsWith("ENTRY_SKIP:")) {
            "ENTRY_SKIP"
        } elseif ($name.StartsWith("FILTER_BLOCK:")) {
            "FILTER_BLOCK"
        } else {
            $name
        }
        $items.Add([pscustomobject]@{
            classification = $name
            category = $category
            family = $family
            rows = [int]$match.Groups[2].Value
        })
    }
    return @($items | Sort-Object -Property @{ Expression = "rows"; Descending = $true }, classification)
}

function Get-TypeDistribution {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $sectionMatch = [regex]::Match(
        $Text,
        "buy_like_candidate_type_distribution:\s*(?<section>(?:\r?\n\s*-\s+[^\r\n]+)+)",
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $sectionMatch.Success) { return @() }
    foreach ($match in [regex]::Matches($sectionMatch.Groups["section"].Value, "^\s*-\s+event=([^ ]+)\s+strategy=([^ ]+)\s+interval=([^ ]+)\s+count=(\d+)\s*$", [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        $items.Add([pscustomobject]@{
            event = $match.Groups[1].Value
            strategy = $match.Groups[2].Value
            interval = $match.Groups[3].Value
            count = [int]$match.Groups[4].Value
        })
    }
    return @($items | Sort-Object -Property @{ Expression = "count"; Descending = $true }, strategy, interval)
}

function Get-NoTerminalExamples {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $sectionMatch = [regex]::Match(
        $Text,
        "NoTerminalFollowupExamples:\s*(?<section>(?:\r?\n\s*-\s+[^\r\n]+)+)",
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $sectionMatch.Success) { return @() }
    $pattern = "^\s*-\s+candidateAuditId=(\d+)\s+time=([^ ]+)\s+strategy=([^ ]+)\s+interval=([^ ]+)\s+event=([^ ]+)\s+classification=NO_TERMINAL_FOLLOWUP\s+reason=(.*)$"
    foreach ($match in [regex]::Matches($sectionMatch.Groups["section"].Value, $pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)) {
        $items.Add([pscustomobject]@{
            candidateAuditId = [int64]$match.Groups[1].Value
            time = $match.Groups[2].Value
            strategy = $match.Groups[3].Value
            interval = $match.Groups[4].Value
            event = $match.Groups[5].Value
            classification = "NO_TERMINAL_FOLLOWUP"
            reason = $match.Groups[6].Value.Trim()
        })
    }
    return @($items)
}

function Get-RankingRows {
    param([object[]]$Ranking, [string]$Classification)
    $item = @($Ranking | Where-Object { $_.classification -eq $Classification } | Select-Object -First 1)
    if (-not $item) { return 0 }
    return Get-IntValue ([string]$item[0].rows)
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

function Get-JsonRowsByProperty {
    param([object[]]$Items, [string]$PropertyName, [string]$PropertyValue)
    $item = @($Items | Where-Object {
        $property = $_.PSObject.Properties[$PropertyName]
        $null -ne $property -and [string]$property.Value -eq $PropertyValue
    } | Select-Object -First 1)
    if (-not $item) { return 0 }
    $rowsProperty = $item[0].PSObject.Properties["rows"]
    if ($null -eq $rowsProperty) { return 0 }
    return Get-IntValue ([string]$rowsProperty.Value)
}

function Get-ProgressionSummary {
    param([object]$Log, [int]$Days)
    $ranking = @(Get-ClassificationRanking -Text $Log.Text)
    [pscustomobject]@{
        reviewDays = $Days
        buyLikeCandidateRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  buy_like_candidate_rows=")
        sampledBuyLikeCandidateRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  sampled_buy_like_candidate_rows=")
        followupTerminalEventRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  followup_terminal_event_rows=")
        noTerminalFollowupRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  no_terminal_followup_rows=")
        filterBlockFollowupRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  filter_block_followup_rows=")
        entrySkipFollowupRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  entry_skip_followup_rows=")
        signalBuyRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  signal_buy_rows=")
        autotradeFollowupRows = Get-IntValue (Get-LastPrefixedValue -Text $Log.Text -Prefix "  autotrade_followup_rows=")
        recommendation = Get-LastPrefixedValue -Text $Log.Text -Prefix "  buy_like_candidate_progression_recommendation=" -Default (Get-LastPrefixedValue -Text $Log.Text -Prefix "buy_like_candidate_progression_recommendation=")
        classificationRanking = @($ranking)
        typeDistribution = @(Get-TypeDistribution -Text $Log.Text)
        noTerminalExamples = @(Get-NoTerminalExamples -Text $Log.Text)
        entryDedupRows = Get-RankingRows -Ranking $ranking -Classification "ENTRY_SKIP:EntryDedup"
        duplicateBarRows = Get-RankingRows -Ranking $ranking -Classification "ENTRY_SKIP:DuplicateBar"
        shadowExecutionIntentRows = Get-RankingRows -Ranking $ranking -Classification "ENTRY_SKIP:ShadowExecutionIntent"
        dataFreshnessGuardRows = Get-RankingRows -Ranking $ranking -Classification "FILTER_BLOCK:DataFreshnessGuard"
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) { throw "MaxAgeMinutes must be between 1 and 10080." }
foreach ($path in @($BuyLike14dLogPath, $BuyLike30dLogPath, $DataFreshnessReadinessLogPath, $SignalCorrectnessLogPath, $NoTerminalContinuityLogPath)) {
    Assert-RelativeOrRootedPathSafe -Name "LogPath" -Value $path
}

$buyLike14d = Read-FreshLog -Name "buy-like-14d" -Path $BuyLike14dLogPath -MaxAge $MaxAgeMinutes
$buyLike30d = Read-FreshLog -Name "buy-like-30d" -Path $BuyLike30dLogPath -MaxAge $MaxAgeMinutes
$dataFreshnessReadiness = Read-FreshLog -Name "data-freshness-readiness" -Path $DataFreshnessReadinessLogPath -MaxAge $MaxAgeMinutes
$signalCorrectness = Read-FreshLog -Name "signal-correctness" -Path $SignalCorrectnessLogPath -MaxAge $MaxAgeMinutes
$noTerminalContinuity = Read-FreshLog -Name "no-terminal-continuity" -Path $NoTerminalContinuityLogPath -MaxAge $MaxAgeMinutes

$summary14d = Get-ProgressionSummary -Log $buyLike14d -Days 14
$summary30d = Get-ProgressionSummary -Log $buyLike30d -Days 30
$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($buyLike14d, $buyLike30d, $dataFreshnessReadiness, $signalCorrectness, $noTerminalContinuity)) {
    if (-not $log.Fresh) {
        Add-Unique -List $missingRequirements -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}
if ($summary14d.buyLikeCandidateRows -lt 1) { Add-Unique -List $missingRequirements -Value "14d BUY-like candidate rows present" }
if ($summary30d.buyLikeCandidateRows -lt 1) { Add-Unique -List $missingRequirements -Value "30d BUY-like candidate rows present" }
if ($summary14d.classificationRanking.Count -lt 1) { Add-Unique -List $missingRequirements -Value "14d follow-up classification ranking present" }
if ($summary30d.classificationRanking.Count -lt 1) { Add-Unique -List $missingRequirements -Value "30d follow-up classification ranking present" }
if ($summary14d.entrySkipFollowupRows -lt 1 -and $summary30d.entrySkipFollowupRows -lt 1) {
    Add-Unique -List $missingRequirements -Value "EntrySkip follow-up rows present"
}
if ($summary14d.noTerminalFollowupRows -lt 1 -and $summary30d.noTerminalFollowupRows -lt 1) {
    Add-Unique -List $missingRequirements -Value "no-terminal follow-up rows present"
}

$replayCandidateIdRows = Get-IntValue (Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "replay_candidate_id_rows=" -Default (Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "  replay_candidate_id_rows="))
$dataFreshnessRows7d = Get-IntValue (Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "data_freshness_rows_7d=" -Default (Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "  data_freshness_rows_7d="))
$latestDataFreshnessTime = Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "latest_data_freshness_row_time=" -Default (Get-LastPrefixedValue -Text $dataFreshnessReadiness.Text -Prefix "  latest_data_freshness_row_time=")
$dataFreshnessCurrentStatus = Get-FirstRegexGroup -Text $signalCorrectness.Text -Pattern "dataFreshnessCurrentStatus=([^ ]+)" -Default "UNKNOWN"
$dataFreshnessAcceptance = Get-FirstRegexGroup -Text $signalCorrectness.Text -Pattern "acceptance=([^ ]+)" -Default "UNKNOWN"
$noTerminalContinuityStatus = Get-LastPrefixedValue -Text $noTerminalContinuity.Text -Prefix "no_terminal_continuity_review_status=" -Default "UNKNOWN"
$noTerminalContinuityRows = Get-IntValue (Get-LastPrefixedValue -Text $noTerminalContinuity.Text -Prefix "no_terminal_followup_rows=" -Default "0")
$noTerminalContinuityClassification = @(Convert-JsonArrayValue -Text $noTerminalContinuity.Text -Prefix "no_terminal_continuity_classification=")
$noTerminalTerminalAfterPrimary = @(Convert-JsonArrayValue -Text $noTerminalContinuity.Text -Prefix "terminal_after_primary_window_distribution=")
if ($summary30d.noTerminalFollowupRows -gt 0 -and $noTerminalContinuityStatus -eq "UNKNOWN") {
    Add-Unique -List $missingRequirements -Value "no-terminal continuity RCA status marker"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$dominantBlocker = if ($summary30d.classificationRanking.Count -gt 0) { $summary30d.classificationRanking[0].classification } else { "UNKNOWN" }
$recentCouldProduceDataFreshnessTerminal = ($summary14d.dataFreshnessGuardRows -gt 0 -or $dataFreshnessRows7d -gt 0)
$hasContinuityRca = ($noTerminalContinuityStatus -eq "READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE")
$terminalAfterPrimaryRows = Get-JsonRowsByProperty -Items $noTerminalContinuityClassification -PropertyName "classification" -PropertyValue "TERMINAL_AFTER_PRIMARY_WINDOW"
$differentIntervalRows = Get-JsonRowsByProperty -Items $noTerminalContinuityClassification -PropertyName "classification" -PropertyValue "SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL"
$nearbyTerminalRows = Get-JsonRowsByProperty -Items $noTerminalContinuityClassification -PropertyName "classification" -PropertyValue "OTHER_TERMINAL_NEARBY"
$nextEvidenceTarget = if ($hasContinuityRca -and ($terminalAfterPrimaryRows + $differentIntervalRows + $nearbyTerminalRows) -gt 0) {
    "Review longer-window and interval-aware BUY-like continuity matching before treating NO_TERMINAL_FOLLOWUP as a real pipeline gap; keep EntryDedup/DataFreshness/live policy unchanged."
} elseif ($summary30d.noTerminalFollowupRows -gt 0) {
    "Add or refresh read-only candidate-audit continuity evidence for NO_TERMINAL_FOLLOWUP rows, then review EntryDedup/ShadowExecutionIntent separately."
} elseif ($summary30d.entryDedupRows -gt 0) {
    "Use the EntryDedup/ShadowExecutionIntent operator packet; keep EntryDedup policy unchanged."
} else {
    "Refresh BUY-like progression after new candidate flow appears."
}

$packet = [pscustomobject]@{
    packetType = "BUY_LIKE_CANDIDATE_LOSS_REVIEW_PACKET"
    status = $status
    sourceLogs = [pscustomobject]@{
        buyLike14d = $BuyLike14dLogPath
        buyLike30d = $BuyLike30dLogPath
        dataFreshnessReadiness = $DataFreshnessReadinessLogPath
        signalCorrectness = $SignalCorrectnessLogPath
        noTerminalContinuity = $NoTerminalContinuityLogPath
    }
    sourceLogFreshness = @(@($buyLike14d, $buyLike30d, $dataFreshnessReadiness, $signalCorrectness, $noTerminalContinuity) | ForEach-Object {
        [pscustomobject]@{ name = $_.Name; ageMinutes = $_.AgeMinutes; fresh = $_.Fresh }
    })
    summary14d = $summary14d
    summary30d = $summary30d
    noTerminalContinuity = [pscustomobject]@{
        status = $noTerminalContinuityStatus
        noTerminalFollowupRows = $noTerminalContinuityRows
        classification = @($noTerminalContinuityClassification)
        terminalAfterPrimaryWindowDistribution = @($noTerminalTerminalAfterPrimary)
        interpretation = if ($hasContinuityRca) { "Most no-terminal rows should be reviewed as matching-window or interval-linking evidence before any live policy relaxation." } else { "No-terminal continuity RCA is missing or not ready." }
    }
    issue8 = [pscustomobject]@{
        status = "BLOCKED_NO_FRESH_DATAFRESHNESS_TERMINAL_ROWS"
        recentCouldProduceDataFreshnessTerminal = $recentCouldProduceDataFreshnessTerminal
        replayCandidateIdRows = $replayCandidateIdRows
        dataFreshnessRows7d = $dataFreshnessRows7d
        latestDataFreshnessRowTime = $latestDataFreshnessTime
        dataFreshnessCurrentStatus = $dataFreshnessCurrentStatus
        dataFreshnessAcceptance = $dataFreshnessAcceptance
    }
    issue12 = [pscustomobject]@{
        status = $status
        dominantBlocker = $dominantBlocker
        nextEvidenceTarget = $nextEvidenceTarget
        closeReadiness = if ($ready) { "OPERATOR_REVIEW_READY_NOT_LIVE" } else { "BLOCKED_REFRESH_EVIDENCE" }
    }
    interpretation = [pscustomobject]@{
        entryDedup = "protective duplicate/exposure or shadow daily cap lane; review-only evidence target, not relaxation approval"
        duplicateBar = "duplicate notification/bar protection; not a live relaxation target from this packet"
        shadowExecutionIntent = "shadow candidate suppression before real order; needs separate operator review"
        noTerminalFollowup = if ($hasContinuityRca) { "candidate-audit continuity is partially explained by terminal-after-window and interval-linking artifacts; review matcher before policy changes" } else { "candidate-audit continuity gap; needs replayable continuity evidence before closure" }
        filterBlockFamilies = "true filter blocks are secondary in 30d and absent in 14d; DataFreshnessGuard is not the dominant recent blocker"
    }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        closeIssue8Allowed = $false
        closeIssue12Allowed = $ready
        closeIssue6Or7Allowed = $false
        livePolicyChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
    }
    requiredBeforeIssue6Or7Closure = @(
        "fresh post-collector DataFreshnessGuard terminal rows",
        "replayCandidateId rows",
        "entry/TP/SL candidate plan",
        "EV and OCO snapshots",
        "complete replayable candidate rows"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextEvidenceTarget
    notAuthorization = "read-only BUY-like candidate loss review packet only; does not authorize live trading, EntryDedup/DataFreshness/live policy relaxation, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[buy-like-candidate-loss-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved local evidence logs only; no SSH, GitHub, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "buy_like_candidate_loss_review_status=$status"
Write-Host "buy_like_candidate_loss_dominant_blocker=$dominantBlocker"
Write-Host "buy_like_candidate_loss_14d_rows=$($summary14d.buyLikeCandidateRows)"
Write-Host "buy_like_candidate_loss_14d_entry_dedup_rows=$($summary14d.entryDedupRows)"
Write-Host "buy_like_candidate_loss_14d_no_terminal_rows=$($summary14d.noTerminalFollowupRows)"
Write-Host "buy_like_candidate_loss_14d_duplicate_bar_rows=$($summary14d.duplicateBarRows)"
Write-Host "buy_like_candidate_loss_14d_shadow_execution_intent_rows=$($summary14d.shadowExecutionIntentRows)"
Write-Host "buy_like_candidate_loss_14d_filter_block_rows=$($summary14d.filterBlockFollowupRows)"
Write-Host "buy_like_candidate_loss_30d_rows=$($summary30d.buyLikeCandidateRows)"
Write-Host "buy_like_candidate_loss_30d_entry_dedup_rows=$($summary30d.entryDedupRows)"
Write-Host "buy_like_candidate_loss_30d_no_terminal_rows=$($summary30d.noTerminalFollowupRows)"
Write-Host "buy_like_candidate_loss_30d_duplicate_bar_rows=$($summary30d.duplicateBarRows)"
Write-Host "buy_like_candidate_loss_30d_shadow_execution_intent_rows=$($summary30d.shadowExecutionIntentRows)"
Write-Host "buy_like_candidate_loss_30d_filter_block_rows=$($summary30d.filterBlockFollowupRows)"
Write-Host "issue8_status=$($packet.issue8.status)"
Write-Host "issue8_recent_could_produce_data_freshness_terminal=$recentCouldProduceDataFreshnessTerminal"
Write-Host "issue12_status=$($packet.issue12.status)"
Write-Host "issue12_close_readiness=$($packet.issue12.closeReadiness)"
Write-Host "issue12_next_evidence_target=$nextEvidenceTarget"
Write-Host "no_terminal_continuity_status=$noTerminalContinuityStatus"
Write-Host "no_terminal_continuity_rows=$noTerminalContinuityRows"
Write-Host "no_terminal_continuity_terminal_after_primary_rows=$terminalAfterPrimaryRows"
Write-Host "no_terminal_continuity_different_interval_rows=$differentIntervalRows"
Write-Host "no_terminal_continuity_other_nearby_terminal_rows=$nearbyTerminalRows"
Write-Host ("buy_like_candidate_loss_14d_ranking=" + (ConvertTo-Json -Compress -Depth 6 @($summary14d.classificationRanking)))
Write-Host ("buy_like_candidate_loss_30d_ranking=" + (ConvertTo-Json -Compress -Depth 6 @($summary30d.classificationRanking)))
Write-Host ("buy_like_candidate_loss_14d_no_terminal_examples=" + (ConvertTo-Json -Compress -Depth 6 @($summary14d.noTerminalExamples)))
Write-Host ("buy_like_candidate_loss_30d_no_terminal_examples=" + (ConvertTo-Json -Compress -Depth 6 @($summary30d.noTerminalExamples)))
Write-Host ("no_terminal_continuity_classification=" + (ConvertTo-Json -Compress -Depth 6 @($noTerminalContinuityClassification)))
Write-Host ("terminal_after_primary_window_distribution=" + (ConvertTo-Json -Compress -Depth 6 @($noTerminalTerminalAfterPrimary)))
Write-Host ("buy_like_candidate_loss_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("buy_like_candidate_loss_review_packet=" + (ConvertTo-Json -Compress -Depth 14 $packet))
Write-Host "close_issue8_allowed=false"
Write-Host "close_issue12_allowed=$($packet.reviewEnvelope.closeIssue12Allowed.ToString().ToLowerInvariant())"
Write-Host "close_issue6_or_7_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[buy-like-candidate-loss-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "BUY-like candidate loss review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
