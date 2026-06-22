param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$ReplayLimit = 200,
    [int]$EntryDedupStrategyId = 508,
    [string]$EntryDedupIntervalCode = "1h",
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RequireBrief
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
        throw "$Name contains unsupported characters for entry-filter blocker decision brief arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) { return $Default }
    return $match.Groups[1].Value.Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return @() }
    try { return @($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return @() }
}

function Add-UniqueString {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
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
        throw "Unable to find powershell or pwsh for entry-filter blocker decision brief."
    }

    Write-Host "[entry-filter-blocker-decision-brief] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
    $startedAt = Get-Date
    $timedOut = $false
    $stdout = ""
    $stderr = ""
    $exitCode = 1
    $job = $null
    try {
        $job = Start-Job -ScriptBlock {
            param([string]$PowerShellSource, [string]$ChildScriptPath, [string]$WorkingDirectory, [object[]]$ChildArguments)
            $ErrorActionPreference = "Continue"
            Set-Location -LiteralPath $WorkingDirectory
            $output = & $PowerShellSource -NoProfile -ExecutionPolicy Bypass -File $ChildScriptPath @ChildArguments 2>&1
            $childSuccess = $?
            $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($childSuccess) { 0 } else { 1 }
            [pscustomobject]@{
                Text = ($output | Out-String -Width 4096)
                ExitCode = $code
            }
        } -ArgumentList @($powerShell.Source, $scriptPath, (Get-Location).Path, (, @($Arguments)))

        $lastHeartbeatSeconds = 0
        while ($job.State -eq "Running") {
            $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            if ($elapsedSeconds -ge $ChildTimeoutSeconds) {
                $timedOut = $true
                Stop-Job -Job $job -ErrorAction SilentlyContinue
                break
            }
            if (($elapsedSeconds - $lastHeartbeatSeconds) -ge 30) {
                Write-Host "[entry-filter-blocker-decision-brief] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
                $lastHeartbeatSeconds = $elapsedSeconds
            }
            Start-Sleep -Seconds 5
            $job = Get-Job -Id $job.Id
        }

        if (-not $timedOut) {
            $jobOutput = @(Receive-Job -Job $job)
            $result = @($jobOutput | Where-Object { $null -ne $_ -and $null -ne $_.PSObject.Properties["ExitCode"] } | Select-Object -Last 1)
            if ($result) {
                $stdout = [string]$result[0].Text
                $exitCode = [int]$result[0].ExitCode
            } else {
                $stderr = ($jobOutput | Out-String -Width 4096)
                $exitCode = 1
            }
        } else {
            $exitCode = -1
        }

        $elapsedTotalSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
        Write-Host "[entry-filter-blocker-decision-brief] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotalSeconds"
        if ($exitCode -ne 0 -or $timedOut) {
            $summarySource = (($stderr, $stdout) -join "`n").Trim()
            $summary = if ($summarySource.Length -gt 600) { $summarySource.Substring(0, 600) } else { $summarySource }
            $summary = ($summary -replace "`r?`n", " | ")
            Write-Host "child_error_summary script=$ScriptName summary=$summary"
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    return [pscustomobject]@{
        ScriptName = $ScriptName
        Text = (($stdout, $stderr) -join "`n")
        ExitCode = $exitCode
        TimedOut = $timedOut
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
if ($ExecutionDays -lt 1 -or $ExecutionDays -gt 90 -or $BlockedDays -lt 1 -or $BlockedDays -gt 90 -or $AccuracyDays -lt 1 -or $AccuracyDays -gt 90) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90."
}
if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 1000) {
    throw "ReplayLimit must be between 1 and 1000."
}
if ($EntryDedupStrategyId -lt 1 -or $EntryDedupStrategyId -gt 1000000) {
    throw "EntryDedupStrategyId must be between 1 and 1000000."
}
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) {
    throw "ChildTimeoutSeconds must be between 60 and 3600."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "EntryDedupIntervalCode" -Value $EntryDedupIntervalCode

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$signal = Invoke-ReadOnlyScript -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments ($commonArgs + @(
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
))
$dataFreshness = Invoke-ReadOnlyScript -ScriptName "prepare_data_freshness_replay_evidence_readiness_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", [string]$ReviewDays,
    "-ReplayIdDays", [string]$ReplayIdDays,
    "-Limit", [string]$ReplayLimit
))
$entryDedupDecision = Invoke-ReadOnlyScript -ScriptName "prepare_entry_dedup_operator_decision_brief_ssh.ps1" -Arguments ($commonArgs + @(
    "-StrategyId", [string]$EntryDedupStrategyId,
    "-IntervalCode", $EntryDedupIntervalCode,
    "-RequireDecisionReady"
))

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($result in @($signal, $dataFreshness, $entryDedupDecision)) {
    if ($result.ExitCode -ne 0) {
        Add-UniqueString -List $missingRequirements -Value "$($result.ScriptName) completed"
    }
}

$signalPolicyClear = Get-RegexValue -Text $signal.Text -Pattern "signalPolicyClear=([^\r\n]+)" -Default "N/A"
$governanceMode = Get-RegexValue -Text $signal.Text -Pattern "7d Governance Drift:\s*[\r\n]+\s*governanceMode=([^\r\n]+)" -Default "N/A"
$missedStatus = Get-RegexValue -Text $signal.Text -Pattern "overallStatus=([A-Z_]+)" -Default "N/A"
$suspiciousNoBuyCount = Get-RegexValue -Text $signal.Text -Pattern "suspiciousNoBuyCount=([0-9]+)" -Default "0"
$falseBlockRiskCount = Get-RegexValue -Text $signal.Text -Pattern "falseBlockRiskCount=([0-9]+)" -Default "0"
$highForwardReturnNoBuyCount = Get-RegexValue -Text $signal.Text -Pattern "highForwardReturnNoBuyCount=([0-9]+)" -Default "0"
$accuracySummary = Get-RegexValue -Text $signal.Text -Pattern "passSummary=([^\r\n]+)" -Default "N/A"
$dataFreshnessCurrentStatus = Get-RegexValue -Text $signal.Text -Pattern "dataFreshnessCurrentStatus=([A-Z_]+)" -Default "N/A"
$dataFreshnessCurrentClean = ($dataFreshnessCurrentStatus -eq "CLEAN")

$dataFreshnessStatus = Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "data_freshness_replay_evidence_readiness_status="
$dataFreshnessBlockers = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "data_freshness_replay_evidence_blockers=")
$dataFreshnessRequired = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "data_freshness_replay_evidence_required=")
$replayCandidateRows = Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "replay_candidate_id_rows="
$completeReplayRows = Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "complete_replayable_candidate_rows="
$missingCounterfactualFields = Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "missing_counterfactual_fields="
$dataFreshnessNextAction = Get-LastPrefixedValue -Text $dataFreshness.Text -Prefix "data_freshness_replay_evidence_next_action="
foreach ($required in @($dataFreshnessRequired)) {
    Add-UniqueString -List $missingRequirements -Value ([string]$required)
}

$entryDedupDecisionStatus = Get-LastPrefixedValue -Text $entryDedupDecision.Text -Prefix "entry_dedup_operator_decision_brief_status="
$entryDedupPrimaryRecommendation = Get-LastPrefixedValue -Text $entryDedupDecision.Text -Prefix "entry_dedup_operator_primary_recommendation="
$entryDedupPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $entryDedupDecision.Text -Prefix "entry_dedup_operator_decision_brief_packet=")
$entryDedupSummary = if ($null -ne $entryDedupPacket -and $null -ne $entryDedupPacket.evidenceSummary) { $entryDedupPacket.evidenceSummary } else { $null }
$entryDedupSkipRows = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.entryDedupSkipRows } else { "N/A" }
$entryDedupPositive24hRows = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.positive24hRows } else { "N/A" }
$entryDedupTpHitRows = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.tpHitRows } else { "N/A" }
$entryDedupSlHitRows = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.slHitRows } else { "N/A" }
$entryDedupAmbiguousSameBarRows = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.ambiguousSameBarRows } else { "N/A" }
$entryDedupAvgNetReturnPct = if ($null -ne $entryDedupSummary) { [string]$entryDedupSummary.avgNetReturnPct } else { "N/A" }

if ($signalPolicyClear -ne "true") {
    Add-UniqueString -List $missingRequirements -Value "signal policy review clear"
}
if ($governanceMode -eq "TOO_STRICT" -or $missedStatus -eq "WARN") {
    Add-UniqueString -List $missingRequirements -Value "governance drift and missed-opportunity review"
}
if ($dataFreshnessStatus -ne "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE") {
    Add-UniqueString -List $missingRequirements -Value "DataFreshness replay evidence review ready"
}
if ($entryDedupDecisionStatus -ne "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE") {
    Add-UniqueString -List $missingRequirements -Value "EntryDedup operator decision brief ready"
}

$entryFilterPolicyLaneStatus = if ($signalPolicyClear -eq "true" -and $missedStatus -ne "WARN" -and $governanceMode -ne "TOO_STRICT") {
    "CLEAR"
} elseif ($governanceMode -eq "TOO_STRICT" -or $missedStatus -eq "WARN") {
    "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
} else {
    "REVIEW_SIGNAL_POLICY"
}
$dataFreshnessReplayLaneStatus = if ($dataFreshnessStatus -eq "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE") {
    "DATAFRESHNESS_REPLAY_EVIDENCE_READY_NOT_LIVE"
} else {
    "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE"
}
$entryDedupShadowLaneStatus = if ($entryDedupDecisionStatus -eq "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE") {
    "ENTRY_DEDUP_SHADOW_REVIEW_READY_NOT_LIVE"
} else {
    "ENTRY_DEDUP_SHADOW_REVIEW_NOT_READY"
}

$briefStatus = if ($entryFilterPolicyLaneStatus -ne "CLEAR") {
    "BLOCKED_SIGNAL_POLICY_OR_MISSED_OPPORTUNITY_REVIEW"
} elseif ($dataFreshnessReplayLaneStatus -ne "DATAFRESHNESS_REPLAY_EVIDENCE_READY_NOT_LIVE") {
    "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE"
} elseif ($entryDedupShadowLaneStatus -ne "ENTRY_DEDUP_SHADOW_REVIEW_READY_NOT_LIVE") {
    "BLOCKED_ENTRY_DEDUP_SHADOW_REVIEW"
} else {
    "READY_FOR_ENTRY_FILTER_OPERATOR_REVIEW_NOT_LIVE"
}

$nextAction = if ($briefStatus -eq "BLOCKED_SIGNAL_POLICY_OR_MISSED_OPPORTUNITY_REVIEW") {
    "Review signal policy, governance drift, and missed-opportunity no-buy rows before any entry-filter promotion review."
} elseif ($briefStatus -eq "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE") {
    if (-not [string]::IsNullOrWhiteSpace($dataFreshnessNextAction)) { $dataFreshnessNextAction } else { "Collect fresh DataFreshness replay candidate rows and counterfactual snapshots before any policy relaxation review." }
} elseif ($briefStatus -eq "BLOCKED_ENTRY_DEDUP_SHADOW_REVIEW") {
    "Refresh EntryDedup shadow evidence and decision brief before any separate review."
} else {
    "Prepare a separate operator review packet; this brief is not live approval and does not authorize EntryDedup/DataFreshness/live policy changes."
}

$decisionChecklist = @(
    "signal_policy_clear=true",
    "missed_opportunity_status not WARN",
    "governance_mode not TOO_STRICT",
    "DataFreshness replay evidence ready with fresh replayCandidateId rows",
    "entry/TP/SL, EV, OCO, and hard-gate snapshots complete",
    "EntryDedup operator decision brief ready",
    "ExpectedValueGate, event-risk control, duplicate hash, daily cap, max-loss, and OCO feasibility reviewed",
    "separate explicit operator authorization before any live, scheduler, order, OCO, grid, fund, Earn, Telegram, exchange, or production env mutation"
)

$brief = [pscustomobject]@{
    packetType = "ENTRY_FILTER_BLOCKER_DECISION_BRIEF"
    status = $briefStatus
    symbol = $Symbol
    entryFilterPolicyLane = [pscustomobject]@{
        status = $entryFilterPolicyLaneStatus
        signalPolicyClear = $signalPolicyClear
        governanceMode = $governanceMode
        missedOpportunityStatus = $missedStatus
        suspiciousNoBuyCount = $suspiciousNoBuyCount
        falseBlockRiskCount = $falseBlockRiskCount
        highForwardReturnNoBuyCount = $highForwardReturnNoBuyCount
        accuracySummary = $accuracySummary
        dataFreshnessCurrentStatus = $dataFreshnessCurrentStatus
        dataFreshnessCurrentClean = $dataFreshnessCurrentClean
    }
    dataFreshnessReplayLane = [pscustomobject]@{
        status = $dataFreshnessReplayLaneStatus
        readinessStatus = $dataFreshnessStatus
        replayCandidateRows = $replayCandidateRows
        completeReplayableCandidateRows = $completeReplayRows
        missingCounterfactualFields = $missingCounterfactualFields
        blockers = @($dataFreshnessBlockers)
        requiredEvidence = @($dataFreshnessRequired)
    }
    entryDedupShadowLane = [pscustomobject]@{
        status = $entryDedupShadowLaneStatus
        decisionBriefStatus = $entryDedupDecisionStatus
        primaryRecommendation = $entryDedupPrimaryRecommendation
        strategyId = $EntryDedupStrategyId
        intervalCode = $EntryDedupIntervalCode
        entryDedupSkipRows = $entryDedupSkipRows
        positive24hRows = $entryDedupPositive24hRows
        tpHitRows = $entryDedupTpHitRows
        slHitRows = $entryDedupSlHitRows
        ambiguousSameBarRows = $entryDedupAmbiguousSameBarRows
        avgNetReturnPct = $entryDedupAvgNetReturnPct
    }
    decisionChecklist = @($decisionChecklist)
    missingRequirementCount = @($missingRequirements).Count
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only entry-filter blocker decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[entry-filter-blocker-decision-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes smoke_signal_correctness_ssh.ps1, prepare_data_freshness_replay_evidence_readiness_ssh.ps1, and prepare_entry_dedup_operator_decision_brief_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_signal_correctness_ssh.ps1 exitCode=$($signal.ExitCode)"
Write-Host "source_packet=prepare_data_freshness_replay_evidence_readiness_ssh.ps1 exitCode=$($dataFreshness.ExitCode)"
Write-Host "source_packet=prepare_entry_dedup_operator_decision_brief_ssh.ps1 exitCode=$($entryDedupDecision.ExitCode)"
Write-Host "signal_policy_clear=$signalPolicyClear"
Write-Host "governance_mode=$governanceMode"
Write-Host "missed_opportunity_status=$missedStatus"
Write-Host "suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "false_block_risk_count=$falseBlockRiskCount"
Write-Host "high_forward_return_no_buy_count=$highForwardReturnNoBuyCount"
Write-Host "data_freshness_current_status=$dataFreshnessCurrentStatus"
Write-Host "data_freshness_current_clean=$($dataFreshnessCurrentClean.ToString().ToLowerInvariant())"
Write-Host "data_freshness_replay_status=$dataFreshnessStatus"
Write-Host "replay_candidate_id_rows=$replayCandidateRows"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "entry_dedup_operator_decision_brief_status=$entryDedupDecisionStatus"
Write-Host "entry_dedup_operator_primary_recommendation=$entryDedupPrimaryRecommendation"
Write-Host "entry_dedup_shadow_lane_status=$entryDedupShadowLaneStatus"
Write-Host "entry_dedup_skip_rows=$entryDedupSkipRows"
Write-Host "entry_dedup_positive_24h_rows=$entryDedupPositive24hRows"
Write-Host "entry_dedup_tp_hit_rows=$entryDedupTpHitRows"
Write-Host "entry_dedup_sl_hit_rows=$entryDedupSlHitRows"
Write-Host "entry_dedup_ambiguous_same_bar_rows=$entryDedupAmbiguousSameBarRows"
Write-Host "entry_dedup_avg_net_return_pct=$entryDedupAvgNetReturnPct"
Write-Host "entry_filter_policy_lane_status=$entryFilterPolicyLaneStatus"
Write-Host "data_freshness_replay_lane_status=$dataFreshnessReplayLaneStatus"
Write-Host ("entry_filter_blocker_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_filter_blocker_decision_checklist=" + (ConvertTo-Json -Compress @($decisionChecklist)))
Write-Host ("entry_filter_blocker_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "entry_filter_blocker_decision_brief_status=$briefStatus"
Write-Host "entry_filter_blocker_decision_next_action=$nextAction"
Write-Host "notAuthorization=read-only entry-filter blocker decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[entry-filter-blocker-decision-brief] read-only check complete"

if ($RequireBrief -and $briefStatus -eq "READY_FOR_ENTRY_FILTER_OPERATOR_REVIEW_NOT_LIVE" -and @($missingRequirements).Count -gt 0) {
    throw "Entry-filter blocker decision brief is inconsistent: status ready but missing requirements exist."
}
