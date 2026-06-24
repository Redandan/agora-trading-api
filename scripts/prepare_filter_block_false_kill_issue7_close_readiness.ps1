param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [string]$ObservationLog = "target/profit-review/issue7-df-replay-observation-latest.log",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireBlocked
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return $Default
    }
    return $line.Substring($Prefix.Length).Trim()
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

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if (-not $List.Contains($Value)) {
        [void]$List.Add($Value)
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

$sourcePath = Resolve-RepoPath $SourceLog
$observationPath = Resolve-RepoPath $ObservationLog
$missingRequirements = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $sourcePath)) {
    Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 filter-block source log"
}
if (-not (Test-Path -LiteralPath $observationPath)) {
    Add-MissingRequirement -List $missingRequirements -Value "fresh DataFreshness replay observation log"
}

$sourceFreshness = "MISSING"
$sourceAgeMinutes = $null
$issue7Packet = $null
if (Test-Path -LiteralPath $sourcePath) {
    $sourceItem = Get-Item -LiteralPath $sourcePath
    $sourceAgeMinutes = [math]::Round(((Get-Date) - $sourceItem.LastWriteTime).TotalMinutes, 2)
    $sourceFreshness = if ($sourceAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    if ($sourceFreshness -ne "FRESH") {
        Add-MissingRequirement -List $missingRequirements -Value "fresh issue #7 filter-block source log"
    }

    $packetOutput = & (Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_packet.ps1") -SourceLog $sourcePath -MaxAgeMinutes $MaxAgeMinutes *>&1
    $packetLine = @($packetOutput | ForEach-Object { [string]$_ } | Where-Object { $_.StartsWith("issue7_filter_block_false_kill_packet=") } | Select-Object -First 1)
    if (-not $packetLine) {
        Add-MissingRequirement -List $missingRequirements -Value "issue #7 filter-block packet JSON"
    } else {
        $issue7Packet = $packetLine.Substring("issue7_filter_block_false_kill_packet=".Length) | ConvertFrom-Json -ErrorAction Stop
    }
}

$observationFreshness = "MISSING"
$observationAgeMinutes = $null
$replayCandidateRecommendation = ""
$replayCandidateRows = $null
$completeReplayableRows = $null
$missingCounterfactualFieldsRaw = "[]"
if (Test-Path -LiteralPath $observationPath) {
    $observationItem = Get-Item -LiteralPath $observationPath
    $observationAgeMinutes = [math]::Round(((Get-Date) - $observationItem.LastWriteTime).TotalMinutes, 2)
    $observationFreshness = if ($observationAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    if ($observationFreshness -ne "FRESH") {
        Add-MissingRequirement -List $missingRequirements -Value "fresh DataFreshness replay observation log"
    }
    $observationText = Get-Content -Raw -LiteralPath $observationPath
    foreach ($marker in @("scope=READ_ONLY", "data_freshness_replay_candidate_id_recommendation=", "complete_replayable_candidate_rows=", "missing_counterfactual_fields=", "notAuthorization=")) {
        if ($observationText -notmatch [regex]::Escape($marker)) {
            Add-MissingRequirement -List $missingRequirements -Value "DataFreshness observation marker: $marker"
        }
    }
    $replayCandidateRecommendation = Get-LastPrefixedValue -Text $observationText -Prefix "  data_freshness_replay_candidate_id_recommendation="
    $replayCandidateRows = Convert-ToNumber (Get-LastPrefixedValue -Text $observationText -Prefix "  replay_candidate_id_rows=" -Default "0")
    $completeReplayableRows = Convert-ToNumber (Get-LastPrefixedValue -Text $observationText -Prefix "  complete_replayable_candidate_rows=" -Default "0")
    $missingCounterfactualFieldsRaw = Get-LastPrefixedValue -Text $observationText -Prefix "  missing_counterfactual_fields=" -Default "[]"
}

if ($null -eq $issue7Packet) {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 filter-block packet JSON"
}

if ($null -ne $issue7Packet) {
    if (-not [bool]$issue7Packet.reviewAllowed) {
        Add-MissingRequirement -List $missingRequirements -Value "issue7 filter-block packet reviewAllowed=true"
    }
    if ([bool]$issue7Packet.liveRelaxationAllowed) {
        Add-MissingRequirement -List $missingRequirements -Value "issue7 packet must remain review-only, not live relaxation"
    }
    if ([int]$issue7Packet.dataFreshnessReplayableRows -le 0) {
        Add-MissingRequirement -List $missingRequirements -Value "DataFreshness rows have complete replayable candidate snapshots"
    }
    if ([int]$issue7Packet.tpSlProxyEvaluableRows -le 0) {
        Add-MissingRequirement -List $missingRequirements -Value "entry/TP/SL proxy rows are present"
    }
}

if (($replayCandidateRows -as [double]) -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "stable replayCandidateId rows"
}
if (($completeReplayableRows -as [double]) -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "complete_replayable_candidate_rows > 0"
}
if ($missingCounterfactualFieldsRaw -ne "[]" -and -not [string]::IsNullOrWhiteSpace($missingCounterfactualFieldsRaw)) {
    Add-MissingRequirement -List $missingRequirements -Value "missing_counterfactual_fields=[]"
}

$closeAllowed = $missingRequirements.Count -eq 0
$status = if ($closeAllowed) { "READY_TO_CLOSE_NOT_LIVE_RELAXATION" } else { "BLOCKED_NOT_CLOSABLE_REPLAY_EVIDENCE_MISSING" }
$nextAction = if ($closeAllowed) {
    "Close issue #7 as evidence-ready/review-only; live relaxation still requires a separate authorization gate."
} else {
    "Do not close issue #7. Collect replayCandidateId rows and complete DataFreshness replay snapshots, or separately authorize the reviewed collector/env path."
}

$packet = [ordered]@{
    packetType = "ISSUE7_CLOSE_READINESS_PACKET"
    status = $status
    closeAllowed = $closeAllowed
    liveRelaxationAllowed = $false
    sourceLog = $SourceLog
    sourceLogFreshness = $sourceFreshness
    sourceLogAgeMinutes = $sourceAgeMinutes
    observationLog = $ObservationLog
    observationLogFreshness = $observationFreshness
    observationLogAgeMinutes = $observationAgeMinutes
    falseKillPct = if ($null -ne $issue7Packet) { $issue7Packet.falseKillPct } else { $null }
    actionableFalseKillPct = if ($null -ne $issue7Packet) { $issue7Packet.actionableFalseKillPct } else { $null }
    expectedValueProjectedActionableFalseKillPctAfterReview = if ($null -ne $issue7Packet) { $issue7Packet.expectedValueProjectedActionableFalseKillPctAfterReview } else { $null }
    actionableNextBlocker = if ($null -ne $issue7Packet) { $issue7Packet.actionableNextBlocker } else { "UNKNOWN" }
    projectedNextBlocker = if ($null -ne $issue7Packet) { $issue7Packet.expectedValueProjectedNextBlockerAfterReview } else { "UNKNOWN" }
    replayInputStage = if ($null -ne $issue7Packet) { $issue7Packet.replayInputStage } else { "UNKNOWN" }
    dataFreshnessRows = if ($null -ne $issue7Packet) { $issue7Packet.dataFreshnessRows } else { 0 }
    dataFreshnessReplayableRows = if ($null -ne $issue7Packet) { $issue7Packet.dataFreshnessReplayableRows } else { 0 }
    replayCandidateRecommendation = $replayCandidateRecommendation
    replayCandidateRows = $replayCandidateRows
    completeReplayableCandidateRows = $completeReplayableRows
    missingCounterfactualFields = $missingCounterfactualFieldsRaw
    acceptanceCriteria = [ordered]@{
        blockerFamilyRanking = ($null -ne $issue7Packet -and $issue7Packet.falseKillSourceRanking.Count -gt 0)
        dataFreshnessReplayableSnapshots = (($completeReplayableRows -as [double]) -gt 0)
        replayRemovesOnlyDataFreshnessGuard = ($missingCounterfactualFieldsRaw -eq "[]")
        executableExpectancyNotOnlyForwardProxy = ($null -ne $issue7Packet -and [int]$issue7Packet.tpSlProxyEvaluableRows -gt 0 -and (($completeReplayableRows -as [double]) -gt 0))
        reviewOnlyNoLiveRelaxation = $true
    }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only issue #7 close-readiness packet only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, orders, OCO modification, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or issue closure when closeAllowed=false"
}

Write-Host "[issue7-close-readiness] read-only packet"
Write-Host "scope=READ_ONLY; reuses saved evidence logs only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, nginx, GitHub, or policy state changed."
Write-Host "issue7_close_readiness_packet=$($packet | ConvertTo-Json -Compress -Depth 8)"
Write-Host "issue7_close_readiness_status=$status"
Write-Host "issue7_close_allowed=$($closeAllowed.ToString().ToLowerInvariant())"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "issue7_close_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_close_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireBlocked -and $closeAllowed) {
    throw "Issue #7 close-readiness was expected to stay blocked, got closeAllowed=true"
}
