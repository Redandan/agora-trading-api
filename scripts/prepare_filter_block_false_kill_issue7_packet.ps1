param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireFresh,
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

function Get-FieldValue {
    param(
        [string]$Text,
        [string]$Name,
        [string]$Default = "N/A"
    )

    $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($Name))=(?<value>.+?)\s*$")
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups["value"].Value.Trim()
}

function Convert-ToNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A") {
        return $null
    }
    $clean = $Value.Trim().TrimEnd("%")
    $number = 0.0
    if ([double]::TryParse($clean, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        return $number
    }
    return $null
}

function Convert-ToPacketInt {
    param([object]$Value)
    if ($Value -is [array]) {
        $Value = $Value[-1]
    }
    if ($null -eq $Value) {
        return 0
    }
    return [int][double]$Value
}

function Convert-ToPacketScalar {
    param([object]$Value)
    if ($Value -is [array]) {
        return $Value[-1]
    }
    return $Value
}

function Add-MissingRequirement {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    if (-not $List.Contains($Value)) {
        [void]$List.Add($Value)
    }
}

$sourcePath = Join-Path (Get-Location) $SourceLog
if ([System.IO.Path]::IsPathRooted($SourceLog)) {
    $sourcePath = $SourceLog
}

if (-not (Test-Path -LiteralPath $sourcePath)) {
    $packet = [ordered]@{
        status = "BLOCKED_SOURCE_LOG_MISSING"
        sourceLog = $SourceLog
        requiredEvidence = @("fresh issue #7 read-only SSH smoke log")
        notAuthorization = "local packet only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, orders, OCO modification, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
    Write-Host "issue7_filter_block_false_kill_packet=$($packet | ConvertTo-Json -Compress -Depth 5)"
    Write-Host "issue7_filter_block_false_kill_status=BLOCKED_SOURCE_LOG_MISSING"
    Write-Host "issue7_filter_block_false_kill_review_allowed=false"
    Write-Host "issue7_live_relaxation_allowed=false"
    Write-Host "notAuthorization=$($packet.notAuthorization)"
    if ($RequireBlocked) {
        exit 0
    }
    throw "Issue #7 source log missing: $sourcePath"
}

$sourceItem = Get-Item -LiteralPath $sourcePath
$ageMinutes = [math]::Round(((Get-Date) - $sourceItem.LastWriteTime).TotalMinutes, 2)
$freshnessStatus = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
if ($RequireFresh -and $freshnessStatus -ne "FRESH") {
    throw "Issue #7 source log is stale: ageMinutes=$ageMinutes maxAgeMinutes=$MaxAgeMinutes"
}

$text = Get-Content -Raw -LiteralPath $sourcePath
foreach ($marker in @(
        "scope=READ_ONLY",
        "Filter Block False-Kill Summary:",
        "False-Kill Source Ranking:",
        "DataFreshnessGuard RCA:",
        "Replayable Candidate Evidence:",
        "issue7_live_relaxation_allowed=false",
        "notAuthorization="
    )) {
    if ($text -notmatch [regex]::Escape($marker)) {
        throw "Issue #7 source log missing marker: $marker"
    }
}

$totalRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_total_rows" -Default "0")
$maturedRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_matured_rows" -Default "0")
$falseKillRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_false_kill_rows" -Default "0")
$correctBlockRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_correct_block_rows" -Default "0")
$falseKillPct = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_false_kill_pct")
$avgForward = Convert-ToNumber (Get-FieldValue -Text $text -Name "filter_block_avg_forward_24h_pct")
$dfRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_rows" -Default "0")
$dfFalseKillRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_false_kill_rows" -Default "0")
$dfCorrectRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_correct_block_rows" -Default "0")
$dfFalseKillPct = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_false_kill_pct")
$dfAvgForward = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_avg_forward_24h_pct")
$dfReplayableRows = Convert-ToNumber (Get-FieldValue -Text $text -Name "data_freshness_complete_replayable_candidate_rows" -Default "0")
$sourceRecommendation = Get-FieldValue -Text $text -Name "issue7_recommendation"
$missingEvidenceRaw = Get-FieldValue -Text $text -Name "live_relaxation_missing_evidence" -Default "[]"

$ranking = New-Object System.Collections.Generic.List[object]
foreach ($match in [regex]::Matches($text, "(?m)^\s*-\s+blocker=(?<blocker>\S+)\s+rows=(?<rows>\d+)\s+falseKillRows=(?<false>\d+)\s+correctBlockRows=(?<correct>\d+)\s+falseKillPct=(?<pct>[+-]?[0-9.]+)%\s+avgForward24h=(?<avg>[+-]?[0-9.]+)%.*?replayableCandidateRows=(?<replay>\d+)")) {
    [void]$ranking.Add([ordered]@{
            blocker = $match.Groups["blocker"].Value
            rows = [int]$match.Groups["rows"].Value
            falseKillRows = [int]$match.Groups["false"].Value
            correctBlockRows = [int]$match.Groups["correct"].Value
            falseKillPct = [double]$match.Groups["pct"].Value
            avgForward24hPct = [double]$match.Groups["avg"].Value
            replayableCandidateRows = [int]$match.Groups["replay"].Value
        })
}

$missingRequirements = New-Object System.Collections.Generic.List[string]
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 source log"
}
if ($totalRows -le 0 -or $maturedRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "matured BTCUSDT 1h FILTER_BLOCK sample"
}
if ($dfRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshnessGuard rows in review window"
}
if ($dfReplayableRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "complete replayable DataFreshness rows"
    Add-MissingRequirement -List $missingRequirements -Value "liveSignalId or replayCandidateId"
    Add-MissingRequirement -List $missingRequirements -Value "entry/TP/SL plan"
    Add-MissingRequirement -List $missingRequirements -Value "evaluated EV snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "OCO plan snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "duplicate/daily-cap/exposure/event-risk hard-gate snapshot"
    Add-MissingRequirement -List $missingRequirements -Value "counterfactual replay removing only DataFreshnessGuard"
}

$status = "READY_FOR_REPLAYABLE_CANDIDATE_REVIEW_NOT_LIVE"
if ($freshnessStatus -ne "FRESH") {
    $status = "BLOCKED_SOURCE_LOG_STALE"
} elseif ($totalRows -le 0 -or $maturedRows -le 0) {
    $status = "BLOCKED_NO_FILTER_BLOCK_SAMPLE"
} elseif ($dfRows -le 0) {
    $status = "BLOCKED_NO_DATAFRESHNESS_SAMPLE"
} elseif ($dfReplayableRows -le 0) {
    $status = "BLOCKED_DATAFRESHNESS_REPLAY_SNAPSHOTS_MISSING"
}

if ($RequireBlocked -and $status -notlike "BLOCKED_*") {
    throw "Issue #7 packet was expected to stay blocked, got $status"
}

$reviewAllowed = $status -eq "READY_FOR_REPLAYABLE_CANDIDATE_REVIEW_NOT_LIVE"
$rankingArray = @()
foreach ($item in $ranking) {
    $rankingArray += $item
}
$missingRequirementsArray = @()
foreach ($item in $missingRequirements) {
    $missingRequirementsArray += $item
}
$packet = [ordered]@{
    status = $status
    sourceLog = $SourceLog
    sourceLogFreshness = $freshnessStatus
    sourceLogAgeMinutes = $ageMinutes
    reviewAllowed = $reviewAllowed
    liveRelaxationAllowed = $false
    totalRows = (Convert-ToPacketInt $totalRows)
    maturedRows = (Convert-ToPacketInt $maturedRows)
    falseKillRows = (Convert-ToPacketInt $falseKillRows)
    correctBlockRows = (Convert-ToPacketInt $correctBlockRows)
    falseKillPct = (Convert-ToPacketScalar $falseKillPct)
    avgForward24hPct = (Convert-ToPacketScalar $avgForward)
    dataFreshnessRows = (Convert-ToPacketInt $dfRows)
    dataFreshnessFalseKillRows = (Convert-ToPacketInt $dfFalseKillRows)
    dataFreshnessCorrectBlockRows = (Convert-ToPacketInt $dfCorrectRows)
    dataFreshnessFalseKillPct = (Convert-ToPacketScalar $dfFalseKillPct)
    dataFreshnessAvgForward24hPct = (Convert-ToPacketScalar $dfAvgForward)
    dataFreshnessReplayableRows = (Convert-ToPacketInt $dfReplayableRows)
    sourceRecommendation = $sourceRecommendation
    sourceMissingEvidence = $missingEvidenceRaw
    falseKillSourceRanking = $rankingArray
    missingRequirements = $missingRequirementsArray
    safeNextAction = "Collect complete replayable DataFreshness rows before any live relaxation; keep DataFreshnessGuard terminal and rerun the issue #7 SSH smoke plus this packet."
    notAuthorization = "local issue #7 packet only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, orders, OCO modification, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "issue7_filter_block_false_kill_packet=$($packet | ConvertTo-Json -Compress -Depth 8)"
Write-Host "issue7_filter_block_false_kill_status=$status"
Write-Host "issue7_filter_block_false_kill_review_allowed=$($reviewAllowed.ToString().ToLowerInvariant())"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "issue7_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_safe_next_action=$($packet.safeNextAction)"
Write-Host "notAuthorization=$($packet.notAuthorization)"
