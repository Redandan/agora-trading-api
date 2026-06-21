param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$SignalExecutionDays = 5,
    [int]$SignalBlockedDays = 7,
    [int]$SignalAccuracyDays = 14,
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
        throw "$Name contains unsupported characters for DataFreshness profit blocker brief arguments."
    }
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups[1].Value.Trim()
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
        throw "Unable to find powershell or pwsh for DataFreshness profit blocker brief."
    }

    Write-Host "[data-freshness-profit-blocker-brief] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[data-freshness-profit-blocker-brief] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
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
if ($SignalExecutionDays -lt 1 -or $SignalExecutionDays -gt 90 -or $SignalBlockedDays -lt 1 -or $SignalBlockedDays -gt 90 -or $SignalAccuracyDays -lt 1 -or $SignalAccuracyDays -gt 90) {
    throw "SignalExecutionDays, SignalBlockedDays, and SignalAccuracyDays must be between 1 and 90."
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

$signal = Invoke-ReadOnlyScript -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments ($commonArgs + @(
    "-ExecutionDays", "$SignalExecutionDays",
    "-BlockedDays", "$SignalBlockedDays",
    "-AccuracyDays", "$SignalAccuracyDays"
))

$observation = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_replay_observation_bundle_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", "$ReviewDays",
    "-ReplayIdDays", "$ReplayIdDays",
    "-Limit", "$Limit"
))

$dataFreshnessStatus = Get-RegexValue -Text $signal.Text -Pattern "dataFreshnessCurrentStatus=([A-Z_]+)"
$dataFreshnessAcceptance = Get-RegexValue -Text $signal.Text -Pattern "dataFreshnessCurrentStatus=[A-Z_]+ acceptance=([A-Z_]+)" -Default "N/A"
$signalPolicyClear = Get-RegexValue -Text $signal.Text -Pattern "signalPolicyClear=(true|false)" -Default "N/A"
$reviewPolicyGaps = Get-RegexValue -Text $signal.Text -Pattern "reviewPolicyGaps=(true|false)" -Default "N/A"
$signalPolicyPlan = Get-RegexValue -Text $signal.Text -Pattern "signal_policy_review_plan=(.+)" -Default "[]"

$originDeltaStatus = Get-RegexValue -Text $observation.Text -Pattern "  origin_delta_status=([A-Z_]+)" -Default "N/A"
$runtimeCurrent = Get-RegexValue -Text $observation.Text -Pattern "  deployment_runtime_current_for_replay_id=(true|false|N/A)" -Default "N/A"
$replayCandidateRecommendation = Get-RegexValue -Text $observation.Text -Pattern "  data_freshness_replay_candidate_id_recommendation=([A-Z_]+)" -Default "N/A"
$replayCandidateRows = Get-RegexValue -Text $observation.Text -Pattern "  replay_candidate_id_rows=([0-9]+)" -Default "0"
$counterfactualRecommendation = Get-RegexValue -Text $observation.Text -Pattern "  data_freshness_counterfactual_recommendation=([A-Z_]+)" -Default "N/A"
$completeReplayRows = Get-RegexValue -Text $observation.Text -Pattern "  complete_replayable_candidate_rows=([0-9]+)" -Default "0"
$missingCounterfactualFields = Get-RegexValue -Text $observation.Text -Pattern "  missing_counterfactual_fields=(.+)" -Default "[]"
$observationRecommendation = Get-RegexValue -Text $observation.Text -Pattern "  replay_observation_bundle_recommendation=([A-Z_]+)" -Default "N/A"

$blockers = [System.Collections.Generic.List[string]]::new()
if ($signal.ExitCode -ne 0) {
    $blockers.Add("SIGNAL_CORRECTNESS_SMOKE_FAILED")
}
if ($observation.ExitCode -ne 0) {
    $blockers.Add("REPLAY_OBSERVATION_BUNDLE_FAILED")
}
if ($dataFreshnessStatus -eq "NO_CURRENT_SAMPLE" -or [string]::IsNullOrWhiteSpace($dataFreshnessStatus)) {
    $blockers.Add("DATAFRESHNESS_CURRENT_SAMPLE_MISSING")
} elseif ($dataFreshnessStatus -ne "CLEAN") {
    $blockers.Add("DATAFRESHNESS_CURRENT_NOT_CLEAN")
}
if ($replayCandidateRecommendation -eq "PENDING_NO_NEW_DATAFRESHNESS_ROWS") {
    $blockers.Add("NO_NEW_DATAFRESHNESS_REPLAY_ROWS")
} elseif ($replayCandidateRecommendation -ne "REPLAY_CANDIDATE_ID_EVIDENCE_OK") {
    $blockers.Add("REPLAY_CANDIDATE_ID_NOT_READY")
}
if ($counterfactualRecommendation -eq "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING") {
    $blockers.Add("COUNTERFACTUAL_REPLAY_SNAPSHOT_MISSING")
} elseif ($counterfactualRecommendation -ne "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
    $blockers.Add("COUNTERFACTUAL_REPLAY_NOT_READY")
}
if ($signalPolicyClear -ne "true") {
    $blockers.Add("SIGNAL_POLICY_NOT_CLEAR")
}

$status = "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE"
$nextAction = "Wait for a current DataFreshnessGuard sample and replay rows, then rerun this read-only brief."
if ($blockers.Count -eq 0) {
    $status = "READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE"
    $nextAction = "Attach this brief to a separate DataFreshness replay review; do not relax DataFreshnessGuard or live policy from this output."
} elseif ($blockers -contains "DATAFRESHNESS_CURRENT_SAMPLE_MISSING") {
    $status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
} elseif ($blockers -contains "NO_NEW_DATAFRESHNESS_REPLAY_ROWS") {
    $status = "PENDING_REPLAY_CANDIDATE_ID_EVIDENCE"
} elseif ($blockers -contains "COUNTERFACTUAL_REPLAY_SNAPSHOT_MISSING") {
    $status = "PENDING_COUNTERFACTUAL_REPLAY_EVIDENCE"
}

$brief = [pscustomobject]@{
    packetType = "DATAFRESHNESS_PROFIT_BLOCKER_BRIEF"
    status = $status
    symbol = $Symbol
    dataFreshnessCurrentStatus = $dataFreshnessStatus
    dataFreshnessAcceptance = $dataFreshnessAcceptance
    signalPolicyClear = $signalPolicyClear
    reviewPolicyGaps = $reviewPolicyGaps
    originDeltaStatus = $originDeltaStatus
    deploymentRuntimeCurrentForReplayId = $runtimeCurrent
    replayCandidateRecommendation = $replayCandidateRecommendation
    replayCandidateRows = $replayCandidateRows
    counterfactualRecommendation = $counterfactualRecommendation
    completeReplayableCandidateRows = $completeReplayRows
    missingCounterfactualFields = $missingCounterfactualFields
    replayObservationRecommendation = $observationRecommendation
    blockers = @($blockers)
    requiredEvidence = @(
        "dataFreshnessCurrentStatus=CLEAN",
        "data_freshness_replay_candidate_id_recommendation=REPLAY_CANDIDATE_ID_EVIDENCE_OK",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]",
        "signalPolicyClear=true"
    )
    signalPolicyReviewPlan = $signalPolicyPlan
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness profit blocker brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[data-freshness-profit-blocker-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes smoke_signal_correctness_ssh.ps1 and smoke_data_freshness_replay_observation_bundle_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_signal=smoke_signal_correctness_ssh.ps1 exitCode=$($signal.ExitCode)"
Write-Host "source_observation=smoke_data_freshness_replay_observation_bundle_ssh.ps1 exitCode=$($observation.ExitCode)"
Write-Host "data_freshness_current_status=$dataFreshnessStatus"
Write-Host "data_freshness_current_acceptance=$dataFreshnessAcceptance"
Write-Host "signal_policy_clear=$signalPolicyClear"
Write-Host "review_policy_gaps=$reviewPolicyGaps"
Write-Host "origin_delta_status=$originDeltaStatus"
Write-Host "deployment_runtime_current_for_replay_id=$runtimeCurrent"
Write-Host "data_freshness_replay_candidate_id_recommendation=$replayCandidateRecommendation"
Write-Host "replay_candidate_id_rows=$replayCandidateRows"
Write-Host "data_freshness_counterfactual_recommendation=$counterfactualRecommendation"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "missing_counterfactual_fields=$missingCounterfactualFields"
Write-Host "replay_observation_bundle_recommendation=$observationRecommendation"
Write-Host ("data_freshness_profit_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host ("data_freshness_profit_blocker_brief_packet=" + (ConvertTo-Json -Compress -Depth 8 $brief))
Write-Host "data_freshness_profit_blocker_status=$status"
Write-Host "data_freshness_profit_blocker_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness profit blocker brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[data-freshness-profit-blocker-brief] read-only check complete"

if ($RequireActionable -and $status -eq "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE") {
    throw "DataFreshness profit blocker brief has only generic blocked evidence."
}
