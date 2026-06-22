param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$Limit = 200,
    [switch]$RequireActionable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for DataFreshness replay evidence readiness arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness replay evidence readiness."
    }

    Write-Host "[data-freshness-replay-evidence-readiness] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[data-freshness-replay-evidence-readiness] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        ScriptName = $ScriptName
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}
if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$observation = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_replay_observation_bundle_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", [string]$ReviewDays,
    "-ReplayIdDays", [string]$ReplayIdDays,
    "-Limit", [string]$Limit
))

$originDelta = Get-LastPrefixedValue -Text $observation.Text -Prefix "  origin_delta_status="
$runtimeCurrent = Get-LastPrefixedValue -Text $observation.Text -Prefix "  deployment_runtime_current_for_replay_id="
$replayCandidateRecommendation = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_replay_candidate_id_recommendation="
$replayCandidateRows = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_candidate_id_rows="
$latestDataFreshnessRowTime = Get-LastPrefixedValue -Text $observation.Text -Prefix "  latest_data_freshness_row_time="
$latestDataFreshnessRowAgeHours = Get-LastPrefixedValue -Text $observation.Text -Prefix "  latest_data_freshness_row_age_hours="
$dataFreshnessRows1d = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_rows_1d="
$dataFreshnessRows3d = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_rows_3d="
$dataFreshnessRows7d = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_rows_7d="
$dataFreshnessRows14d = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_rows_14d="
$dataFreshnessRows30d = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_rows_30d="
$dataFreshnessSampleGapStatus = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_sample_gap_status="
$counterfactualRecommendation = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_counterfactual_recommendation="
$replayInputStage = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_input_stage="
$replayInputNextAction = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_input_next_action="
$previewOnlyInputRows = Get-LastPrefixedValue -Text $observation.Text -Prefix "  preview_only_input_rows="
$collectorStatusCounts = Get-LastPrefixedValue -Text $observation.Text -Prefix "  collector_status_counts="
$hardGatePreviewStatusCounts = Get-LastPrefixedValue -Text $observation.Text -Prefix "  hard_gate_preview_status_counts="
$completeReplayRows = Get-LastPrefixedValue -Text $observation.Text -Prefix "  complete_replayable_candidate_rows="
$missingCounterfactualFields = Get-LastPrefixedValue -Text $observation.Text -Prefix "  missing_counterfactual_fields="
$observationReviewItems = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_observation_review_items=")
$observationRecommendation = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_observation_bundle_recommendation="

$sampleGapRecommendation = "NOT_RUN"
$sampleGapNextAction = "N/A"
$sampleGapExitCode = $null
$shouldRunSampleGapRca = (
    $dataFreshnessSampleGapStatus -eq "NO_ROWS_IN_REVIEW_WINDOW" -or
    $replayCandidateRecommendation -eq "PENDING_NO_NEW_DATAFRESHNESS_ROWS" -or
    @($observationReviewItems) -contains "WAIT_FOR_NEW_DATAFRESHNESS_SAMPLE"
)
if ($shouldRunSampleGapRca) {
    $sampleGap = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_sample_gap_rca_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string][Math]::Min($ReplayIdDays, 30),
        "-LongDays", [string][Math]::Max($ReviewDays, $ReplayIdDays),
        "-Limit", "10"
    ))
    $sampleGapExitCode = $sampleGap.ExitCode
    $sampleGapRecommendation = Get-LastPrefixedValue -Text $sampleGap.Text -Prefix "  data_freshness_sample_gap_rca_recommendation="
    $sampleGapNextAction = Get-LastPrefixedValue -Text $sampleGap.Text -Prefix "  data_freshness_sample_gap_next_action="
}

$blockers = [System.Collections.Generic.List[string]]::new()
$requiredEvidence = [System.Collections.Generic.List[string]]::new()

if ($observation.ExitCode -ne 0) {
    $blockers.Add("REPLAY_OBSERVATION_BUNDLE_FAILED")
    $requiredEvidence.Add("smoke_data_freshness_replay_observation_bundle_ssh.ps1 exitCode=0")
}
if ($originDelta -eq "RUNTIME_DRIFT" -or $runtimeCurrent -eq "false" -or $replayCandidateRecommendation -eq "DEPLOYED_RUNTIME_NOT_CURRENT") {
    $blockers.Add("DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE")
    $requiredEvidence.Add("deployment_runtime_current_for_replay_id=true")
}
if ($replayCandidateRecommendation -eq "PENDING_NO_NEW_DATAFRESHNESS_ROWS") {
    $blockers.Add("FRESH_DATAFRESHNESS_REPLAY_ROWS_MISSING")
    $requiredEvidence.Add("fresh DataFreshnessGuard terminal rows after replay-id runtime")
}
if ($replayCandidateRecommendation -eq "REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE") {
    $blockers.Add("REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE")
    $requiredEvidence.Add("data_freshness_replay_candidate_id_recommendation=REPLAY_CANDIDATE_ID_EVIDENCE_OK")
}
if ($replayInputStage -eq "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE") {
    $blockers.Add("PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE")
    $requiredEvidence.Add("new replay-id rows that postdate replay-id/collector runtime")
}
if ($counterfactualRecommendation -eq "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING") {
    $blockers.Add("COUNTERFACTUAL_REPLAY_SNAPSHOTS_MISSING")
    $requiredEvidence.Add("complete_replayable_candidate_rows > 0")
    $requiredEvidence.Add("missing_counterfactual_fields=[]")
}
if ($shouldRunSampleGapRca -and $sampleGapExitCode -ne 0) {
    $blockers.Add("SAMPLE_GAP_RCA_FAILED")
    $requiredEvidence.Add("smoke_data_freshness_sample_gap_rca_ssh.ps1 exitCode=0")
}

$status = if (@($blockers).Count -eq 0) {
    "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE"
} elseif (@($blockers) -contains "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
} elseif (@($blockers) -contains "FRESH_DATAFRESHNESS_REPLAY_ROWS_MISSING") {
    "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS"
} elseif (@($blockers) -contains "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE") {
    "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
} elseif (@($blockers) -contains "COUNTERFACTUAL_REPLAY_SNAPSHOTS_MISSING") {
    "PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS"
} else {
    "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE"
}

$nextAction = if ($status -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE") {
    "Request separate deploy authorization, deploy current runtime, then rerun replay observation before waiting for replay-id evidence."
} elseif ($status -eq "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS") {
    if (-not [string]::IsNullOrWhiteSpace($sampleGapNextAction) -and $sampleGapNextAction -ne "N/A") { $sampleGapNextAction } else { "Wait for a fresh DataFreshnessGuard terminal row, then rerun replay observation with -RequireObserved only when a new row is expected." }
} elseif ($status -eq "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE") {
    "Do not draft shadow review from historical proxy rows; wait for fresh replay-id rows that postdate replay-id/collector runtime."
} elseif ($status -eq "PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS") {
    "Collect entry/TP/SL, EV, OCO, and hard-gate replay snapshots before any DataFreshness policy or shadow review."
} elseif ($status -eq "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE") {
    "Attach this packet to a separate DataFreshness replay evidence review; it is not approval to relax DataFreshnessGuard or live policy."
} else {
    "Inspect replay observation and sample-gap RCA outputs, then refresh the missing read-only evidence."
}

$packet = [pscustomobject]@{
    packetType = "DATAFRESHNESS_REPLAY_EVIDENCE_READINESS_PACKET"
    status = $status
    symbol = $Symbol
    originDeltaStatus = $originDelta
    deploymentRuntimeCurrentForReplayId = $runtimeCurrent
    replayCandidateRecommendation = $replayCandidateRecommendation
    replayCandidateRows = $replayCandidateRows
    latestDataFreshnessRowTime = $latestDataFreshnessRowTime
    latestDataFreshnessRowAgeHours = $latestDataFreshnessRowAgeHours
    dataFreshnessRows1d = $dataFreshnessRows1d
    dataFreshnessRows3d = $dataFreshnessRows3d
    dataFreshnessRows7d = $dataFreshnessRows7d
    dataFreshnessRows14d = $dataFreshnessRows14d
    dataFreshnessRows30d = $dataFreshnessRows30d
    dataFreshnessSampleGapStatus = $dataFreshnessSampleGapStatus
    sampleGapRcaRecommendation = $sampleGapRecommendation
    counterfactualRecommendation = $counterfactualRecommendation
    replayInputStage = $replayInputStage
    replayInputNextAction = $replayInputNextAction
    previewOnlyInputRows = $previewOnlyInputRows
    collectorStatusCounts = $collectorStatusCounts
    hardGatePreviewStatusCounts = $hardGatePreviewStatusCounts
    completeReplayableCandidateRows = $completeReplayRows
    missingCounterfactualFields = $missingCounterfactualFields
    replayObservationRecommendation = $observationRecommendation
    blockers = @($blockers)
    requiredEvidence = @($requiredEvidence)
    sourceScripts = @(
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "smoke_data_freshness_sample_gap_rca_ssh.ps1"
    )
    childExitCodes = @{
        replayObservationBundle = $observation.ExitCode
        sampleGapRca = $sampleGapExitCode
    }
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness replay evidence readiness only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[data-freshness-replay-evidence-readiness] read-only packet"
Write-Host "scope=READ_ONLY; invokes smoke_data_freshness_replay_observation_bundle_ssh.ps1 and conditionally smoke_data_freshness_sample_gap_rca_ssh.ps1 only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_replay_observation=smoke_data_freshness_replay_observation_bundle_ssh.ps1 exitCode=$($observation.ExitCode)"
Write-Host "source_sample_gap_rca=smoke_data_freshness_sample_gap_rca_ssh.ps1 exitCode=$sampleGapExitCode recommendation=$sampleGapRecommendation"
Write-Host "origin_delta_status=$originDelta"
Write-Host "deployment_runtime_current_for_replay_id=$runtimeCurrent"
Write-Host "data_freshness_replay_candidate_id_recommendation=$replayCandidateRecommendation"
Write-Host "replay_candidate_id_rows=$replayCandidateRows"
Write-Host "latest_data_freshness_row_time=$latestDataFreshnessRowTime"
Write-Host "latest_data_freshness_row_age_hours=$latestDataFreshnessRowAgeHours"
Write-Host "data_freshness_rows_1d=$dataFreshnessRows1d"
Write-Host "data_freshness_rows_3d=$dataFreshnessRows3d"
Write-Host "data_freshness_rows_7d=$dataFreshnessRows7d"
Write-Host "data_freshness_rows_14d=$dataFreshnessRows14d"
Write-Host "data_freshness_rows_30d=$dataFreshnessRows30d"
Write-Host "data_freshness_sample_gap_status=$dataFreshnessSampleGapStatus"
Write-Host "data_freshness_sample_gap_rca_recommendation=$sampleGapRecommendation"
Write-Host "data_freshness_counterfactual_recommendation=$counterfactualRecommendation"
Write-Host "replay_input_stage=$replayInputStage"
Write-Host "replay_input_next_action=$replayInputNextAction"
Write-Host "collector_status_counts=$collectorStatusCounts"
Write-Host "hard_gate_preview_status_counts=$hardGatePreviewStatusCounts"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "missing_counterfactual_fields=$missingCounterfactualFields"
Write-Host ("data_freshness_replay_evidence_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host ("data_freshness_replay_evidence_required=" + (ConvertTo-Json -Compress @($requiredEvidence)))
Write-Host ("data_freshness_replay_evidence_readiness_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "data_freshness_replay_evidence_readiness_status=$status"
Write-Host "data_freshness_replay_evidence_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness replay evidence readiness only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[data-freshness-replay-evidence-readiness] read-only check complete"

if ($RequireActionable -and $status -eq "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE") {
    throw "DataFreshness replay evidence readiness is already review-ready; no blocker action is available."
}
