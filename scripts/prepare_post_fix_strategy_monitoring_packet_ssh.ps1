param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$LocalTradingViewStrategyId = 485,
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$LocalTradingViewDays = 90,
    [string]$LocalTradingViewSource = "binance",
    [int]$LocalTradingViewWatchMaxAttempts = 1,
    [int]$LocalTradingViewWatchSleepSeconds = 0,
    [long]$Strategy508Id = 508,
    [string]$Strategy508IntervalCode = "1h",
    [int]$Hours = 168,
    [int]$ExecutionDays = 7,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 30,
    [long]$RuntimeEvidenceTargetStrategyId = 574,
    [string]$RuntimeEvidenceSide = "LONG",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$Strategy508Limit = 10,
    [int]$ChildTimeoutSeconds = 1200,
    [switch]$RunFullReadinessEveryAttempt,
    [switch]$RequireNoMissedOrder
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

function Assert-MonitorTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for post-fix strategy monitoring arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) { return $Default }
    return $match.Groups[1].Value.Trim()
}

function Convert-ToIntOrZero {
    param([string]$Value)
    $number = 0
    $normalized = if ($null -eq $Value) { "" } else { $Value.Trim() }
    if ([int]::TryParse($normalized, [ref]$number)) { return $number }
    return 0
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for post-fix strategy monitoring packet." }

    Write-Host "[post-fix-strategy-monitoring] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
    $startedAt = Get-Date
    $timedOut = $false
    $output = ""
    $exitCode = 1
    $job = $null
    try {
        $job = Start-Job -ScriptBlock {
            param(
                [string]$PowerShellSource,
                [string]$ChildScriptPath,
                [string]$WorkingDirectory,
                [object[]]$ChildArguments
            )
            $ErrorActionPreference = "Continue"
            Set-Location -LiteralPath $WorkingDirectory
            $childOutput = & $PowerShellSource -NoProfile -ExecutionPolicy Bypass -File $ChildScriptPath @ChildArguments 2>&1
            $childSuccess = $?
            $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($childSuccess) { 0 } else { 1 }
            [pscustomobject]@{
                Text = ($childOutput | Out-String -Width 8192)
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
            if ($elapsedSeconds -ge ($lastHeartbeatSeconds + 30)) {
                $lastHeartbeatSeconds = $elapsedSeconds
                Write-Host "[post-fix-strategy-monitoring] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
            }
            Start-Sleep -Seconds 2
        }

        if ($timedOut) {
            $output = "timed out after $ChildTimeoutSeconds second(s)"
            $exitCode = 124
        } else {
            $result = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($null -ne $result) {
                $output = [string]$result.Text
                $exitCode = [int]$result.ExitCode
            }
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    $elapsedTotal = [int]((Get-Date) - $startedAt).TotalSeconds
    Write-Host "[post-fix-strategy-monitoring] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

    [pscustomobject]@{
        Text = $output
        ExitCode = $exitCode
    }
}

function Write-ChildFailureContext {
    param([string]$ScriptName, [pscustomobject]$Result)
    if ($Result.ExitCode -eq 0) { return }
    $text = [string]$Result.Text
    if ($text.Length -gt 4000) {
        $text = $text.Substring(0, 4000) + "`n...[truncated]"
    }
    Write-Host "[post-fix-strategy-monitoring] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) { throw "LocalTradingViewStrategyId must be between 1 and 999999999." }
if ($Strategy508Id -lt 1 -or $Strategy508Id -gt 999999999) { throw "Strategy508Id must be between 1 and 999999999." }
if ($RuntimeEvidenceTargetStrategyId -lt 1 -or $RuntimeEvidenceTargetStrategyId -gt 999999999) { throw "RuntimeEvidenceTargetStrategyId must be between 1 and 999999999." }
if ($LocalTradingViewDays -lt 7 -or $LocalTradingViewDays -gt 730) { throw "LocalTradingViewDays must be between 7 and 730." }
if ($LocalTradingViewWatchMaxAttempts -lt 1 -or $LocalTradingViewWatchMaxAttempts -gt 96) { throw "LocalTradingViewWatchMaxAttempts must be between 1 and 96." }
if ($LocalTradingViewWatchSleepSeconds -lt 0 -or $LocalTradingViewWatchSleepSeconds -gt 3600) { throw "LocalTradingViewWatchSleepSeconds must be between 0 and 3600." }
if ($Hours -lt 1 -or $Hours -gt 720) { throw "Hours must be between 1 and 720." }
if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) { throw "RuntimeEvidenceMinutes must be between 60 and 43200." }
if ($ExecutionDays -lt 1 -or $ExecutionDays -gt 90 -or $BlockedDays -lt 1 -or $BlockedDays -gt 90 -or $AccuracyDays -lt 1 -or $AccuracyDays -gt 90) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90."
}
if ($Strategy508Limit -lt 1 -or $Strategy508Limit -gt 50) { throw "Strategy508Limit must be between 1 and 50." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 7200) { throw "ChildTimeoutSeconds must be between 60 and 7200." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-MonitorTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-MonitorTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16
Assert-MonitorTokenSafe -Name "LocalTradingViewSource" -Value $LocalTradingViewSource -MaxLength 32
Assert-MonitorTokenSafe -Name "Strategy508IntervalCode" -Value $Strategy508IntervalCode -MaxLength 16
Assert-MonitorTokenSafe -Name "RuntimeEvidenceSide" -Value $RuntimeEvidenceSide -MaxLength 16

Write-Host "[post-fix-strategy-monitoring] read-only 485/508 post-fix monitoring packet"
Write-Host "scope=READ_ONLY; wraps watch_local_tradingview_buy_candidate_ssh.ps1, smoke_strategy508_entry_dedup_exposure_ssh.ps1, smoke_signal_correctness_ssh.ps1, and smoke_runtime_evidence_rca_ssh.ps1; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol localTradingViewStrategyId=$LocalTradingViewStrategyId localTradingViewInterval=$LocalTradingViewIntervalCode strategy508Id=$Strategy508Id strategy508Interval=$Strategy508IntervalCode runtimeEvidenceTargetStrategyId=$RuntimeEvidenceTargetStrategyId hours=$Hours executionDays=$ExecutionDays blockedDays=$BlockedDays accuracyDays=$AccuracyDays"

$ltvArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-LocalTradingViewStrategyId", [string]$LocalTradingViewStrategyId,
    "-Symbol", $Symbol,
    "-LocalTradingViewIntervalCode", $LocalTradingViewIntervalCode,
    "-LocalTradingViewDays", [string]$LocalTradingViewDays,
    "-LocalTradingViewSource", $LocalTradingViewSource,
    "-MaxAttempts", [string]$LocalTradingViewWatchMaxAttempts,
    "-SleepSeconds", [string]$LocalTradingViewWatchSleepSeconds,
    "-ChildTimeoutSeconds", [string]$ChildTimeoutSeconds
)
if ($RunFullReadinessEveryAttempt.IsPresent) {
    $ltvArgs += "-RunFullReadinessEveryAttempt"
}

$dedupArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$Strategy508Id,
    "-IntervalCode", $Strategy508IntervalCode,
    "-Hours", [string]$Hours,
    "-Limit", [string]$Strategy508Limit
)

$signalArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
)

$runtimeArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$RuntimeEvidenceTargetStrategyId,
    "-Side", $RuntimeEvidenceSide,
    "-Minutes", [string]$RuntimeEvidenceMinutes
)

$ltv = Invoke-ReadOnlyScript -ScriptName "watch_local_tradingview_buy_candidate_ssh.ps1" -Arguments $ltvArgs
Write-ChildFailureContext -ScriptName "watch_local_tradingview_buy_candidate_ssh.ps1" -Result $ltv

$dedup = Invoke-ReadOnlyScript -ScriptName "smoke_strategy508_entry_dedup_exposure_ssh.ps1" -Arguments $dedupArgs
Write-ChildFailureContext -ScriptName "smoke_strategy508_entry_dedup_exposure_ssh.ps1" -Result $dedup

$signal = Invoke-ReadOnlyScript -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments $signalArgs
Write-ChildFailureContext -ScriptName "smoke_signal_correctness_ssh.ps1" -Result $signal

$runtime = Invoke-ReadOnlyScript -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments $runtimeArgs
Write-ChildFailureContext -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Result $runtime

$ltvStatus = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_status="
$ltvReason = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_reason="
$ltvCandidateStatus = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_current_candidate_status="
$ltvPreExecutionBlockers = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_pre_execution_blockers="
$ltvOnlyStatus = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_only_status="
$ltvOnlyBlockers = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_only_blockers="
$ltvEffectiveNotional = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_effective_notional_usdt="
$ltvNextAction = Get-LastPrefixedValue -Text $ltv.Text -Prefix "local_tradingview_buy_candidate_watch_next_action="

$dedupRecommendation = Get-RegexValue -Text $dedup.Text -Pattern "^\s*strategy508_entry_dedup_exposure_recommendation=([^\r\n]+)" -Default ""
$dedupBlockerCount = Get-RegexValue -Text $dedup.Text -Pattern "^\s*strategy508_entry_dedup_blocker_count=([0-9]+)" -Default "0"
$dedupNextAction = Get-RegexValue -Text $dedup.Text -Pattern "^\s*strategy508_entry_dedup_next_action=([^\r\n]+)" -Default ""

$executionMachineStatus = Get-RegexValue -Text $signal.Text -Pattern "^\s*executionMachineStatus=([^\r\n]+)" -Default ""
$executionMarkerFound = Get-RegexValue -Text $signal.Text -Pattern "^\s*executionMachineStatusMarkerFound=([^\r\n]+)" -Default "false"
$missingEvalOrOrderBug = Get-RegexValue -Text $signal.Text -Pattern "^\s*missingEvalOrOrderBug=([^\r\n]+)" -Default ""
$policyPrimary = Get-RegexValue -Text $signal.Text -Pattern "^\s*executionSignalSourcePolicyPrimary=([^\r\n]+)" -Default ""
$correctnessDashboardLine = Get-RegexValue -Text $signal.Text -Pattern "^\s*governanceMode=([^\r\n]+actionableCandidates=[^\r\n]+)" -Default ""
$missedStatus = Get-RegexValue -Text $signal.Text -Pattern "^\s*overallStatus=([A-Z_]+)" -Default ""
$suspiciousNoBuyCount = Get-RegexValue -Text $signal.Text -Pattern "suspiciousNoBuyCount=([0-9]+)" -Default "0"
$falseBlockRiskCount = Get-RegexValue -Text $signal.Text -Pattern "falseBlockRiskCount=([0-9]+)" -Default "0"
$entryDedupSkipCount = Get-RegexValue -Text $signal.Text -Pattern "entryDedupSkipCount=([0-9]+)" -Default "0"

$runtimeDiagnosis = Get-RegexValue -Text $runtime.Text -Pattern "^\s*diagnosis=([^\r\n]+)" -Default ""
$runtimeOrderSentEvidence = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidence=([0-9]+)" -Default "UNKNOWN"
$runtimeTargetOrderSentEvidence = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidenceTargetStrategy=([0-9]+)" -Default "UNKNOWN"
$runtimeOtherOrderSentEvidence = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidenceOtherStrategy=([0-9]+)" -Default "UNKNOWN"
$runtimeGridOrderSentEvidence = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidenceNonAutonomousGrid=([0-9]+)" -Default "UNKNOWN"
$runtimeUnknownOrderSentEvidence = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidenceUnknown=([0-9]+)" -Default "UNKNOWN"
$runtimeOrderSentBlockerCount = Get-RegexValue -Text $runtime.Text -Pattern "^\s*orderSentEvidenceBlockerCount=([0-9]+)" -Default "UNKNOWN"
$runtimeOrderSentRows = Get-RegexValue -Text $runtime.Text -Pattern "^\s*order_sent_evidence_rows=([^\r\n]+)" -Default "[]"

$executionOk = (
    $executionMarkerFound -eq "true" -and
    $missingEvalOrOrderBug -eq "no" -and
    $executionMachineStatus -match "^no missing evaluation;\s*no missed order$"
)

$evidenceProblems = [System.Collections.Generic.List[string]]::new()
if ($ltv.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($ltvStatus)) {
    $evidenceProblems.Add("LOCAL_TRADINGVIEW_WATCH_EVIDENCE_UNAVAILABLE")
}
if ($dedup.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($dedupRecommendation)) {
    $evidenceProblems.Add("STRATEGY508_ENTRY_DEDUP_EVIDENCE_UNAVAILABLE")
}
if ([string]::IsNullOrWhiteSpace($executionMachineStatus) -or $executionMarkerFound -ne "true") {
    $evidenceProblems.Add("SIGNAL_CORRECTNESS_MACHINE_STATUS_EVIDENCE_UNAVAILABLE")
}
if ($runtime.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($runtimeDiagnosis) -or $runtimeOrderSentBlockerCount -eq "UNKNOWN") {
    $evidenceProblems.Add("RUNTIME_EVIDENCE_RCA_UNAVAILABLE")
}

$status = "WATCHING_NO_CURRENT_ACTION"
$nextAction = "Rerun this read-only packet after the next closed TradingView bar or strategy 508 evaluation cycle; no manual order action is authorized by this packet."
if ($evidenceProblems.Count -gt 0) {
    $status = "EVIDENCE_UNAVAILABLE"
    $nextAction = "Fix the listed evidence collection failure before deciding whether a BUY was missed."
} elseif ((Convert-ToIntOrZero -Value $runtimeOrderSentBlockerCount) -gt 0) {
    $status = "RUNTIME_TARGET_ORDER_SENT_REVIEW_REQUIRED"
    $nextAction = "Investigate target-strategy or unclassified runtime order-sent evidence before treating live-readiness blockers as clear."
} elseif (-not $executionOk) {
    $status = "MISSED_ORDER_REVIEW_REQUIRED"
    $nextAction = "Inspect verifyStrategyExecution and recent audit/order rows before enabling or relaxing any buy path."
} elseif ($ltvStatus -eq "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED") {
    $status = "CURRENT_BUY_READY_MONITOR_ORDER_AND_OCO"
    $nextAction = "Monitor the next LOCAL_TRADINGVIEW evaluator tick for order and OCO evidence; do not place a manual order from this packet."
} elseif ($ltvStatus -eq "BLOCKED_CURRENT_BUY_CANDIDATE") {
    $status = "CURRENT_BUY_BLOCKED_REVIEW_REQUIRED"
    $nextAction = if ([string]::IsNullOrWhiteSpace($ltvNextAction)) { "Fix LOCAL_TRADINGVIEW-only blockers before treating the current BUY as executable." } else { $ltvNextAction }
} elseif ($dedupRecommendation -eq "STAGED_ADD_SHADOW_CANDIDATE_REVIEW_NOT_LIVE") {
    $status = "STRATEGY508_STAGED_ADD_SHADOW_REVIEW"
    $nextAction = "Review strategy 508 staged-add shadow evidence; this packet does not relax EntryDedup or authorize add orders."
} elseif ((Convert-ToIntOrZero -Value $suspiciousNoBuyCount) -gt 0 -or (Convert-ToIntOrZero -Value $falseBlockRiskCount) -gt 0) {
    $status = "WATCH_FALSE_BLOCK_RISK"
    $nextAction = "Review suspicious no-buy / false-block rows, but keep live gates unchanged until shadow evidence and operator authorization exist."
}

$summary = [ordered]@{
    status = $status
    localTradingViewWatchStatus = $ltvStatus
    localTradingViewCurrentCandidateStatus = $ltvCandidateStatus
    strategy508EntryDedupRecommendation = $dedupRecommendation
    executionMachineStatus = $executionMachineStatus
    executionSignalSourcePolicyPrimary = $policyPrimary
    runtimeEvidenceDiagnosis = $runtimeDiagnosis
    runtimeOrderSentEvidence = $runtimeOrderSentEvidence
    runtimeOrderSentBlockerCount = $runtimeOrderSentBlockerCount
    suspiciousNoBuyCount = $suspiciousNoBuyCount
    falseBlockRiskCount = $falseBlockRiskCount
    evidenceProblems = @($evidenceProblems)
}
$summaryJson = $summary | ConvertTo-Json -Compress -Depth 5

Write-Host ""
Write-Host "post_fix_strategy_monitoring_status=$status"
Write-Host "post_fix_strategy_monitoring_local_tradingview_watch_status=$ltvStatus"
Write-Host "post_fix_strategy_monitoring_local_tradingview_reason=$ltvReason"
Write-Host "post_fix_strategy_monitoring_local_tradingview_candidate_status=$ltvCandidateStatus"
Write-Host "post_fix_strategy_monitoring_local_tradingview_pre_execution_blockers=$ltvPreExecutionBlockers"
Write-Host "post_fix_strategy_monitoring_local_tradingview_only_status=$ltvOnlyStatus"
Write-Host "post_fix_strategy_monitoring_local_tradingview_only_blockers=$ltvOnlyBlockers"
Write-Host "post_fix_strategy_monitoring_local_tradingview_effective_notional_usdt=$ltvEffectiveNotional"
Write-Host "post_fix_strategy_monitoring_strategy508_recommendation=$dedupRecommendation"
Write-Host "post_fix_strategy_monitoring_strategy508_blocker_count=$dedupBlockerCount"
Write-Host "post_fix_strategy_monitoring_strategy508_next_action=$dedupNextAction"
Write-Host "post_fix_strategy_monitoring_verify_machine_status=$executionMachineStatus"
Write-Host "post_fix_strategy_monitoring_verify_machine_status_marker_found=$executionMarkerFound"
Write-Host "post_fix_strategy_monitoring_missing_eval_or_order_bug=$missingEvalOrOrderBug"
Write-Host "post_fix_strategy_monitoring_signal_source_policy_primary=$policyPrimary"
Write-Host "post_fix_strategy_monitoring_correctness_dashboard=$correctnessDashboardLine"
Write-Host "post_fix_strategy_monitoring_missed_status=$missedStatus"
Write-Host "post_fix_strategy_monitoring_suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "post_fix_strategy_monitoring_false_block_count=$falseBlockRiskCount"
Write-Host "post_fix_strategy_monitoring_false_block_risk_count=$falseBlockRiskCount"
Write-Host "post_fix_strategy_monitoring_entry_dedup_skip_count=$entryDedupSkipCount"
Write-Host "post_fix_strategy_monitoring_runtime_evidence_diagnosis=$runtimeDiagnosis"
Write-Host "post_fix_strategy_monitoring_runtime_order_sent_evidence=$runtimeOrderSentEvidence"
Write-Host "post_fix_strategy_monitoring_runtime_target_order_sent_evidence=$runtimeTargetOrderSentEvidence"
Write-Host "post_fix_strategy_monitoring_runtime_other_strategy_order_sent_evidence=$runtimeOtherOrderSentEvidence"
Write-Host "post_fix_strategy_monitoring_runtime_grid_order_sent_evidence=$runtimeGridOrderSentEvidence"
Write-Host "post_fix_strategy_monitoring_runtime_unknown_order_sent_evidence=$runtimeUnknownOrderSentEvidence"
Write-Host "post_fix_strategy_monitoring_runtime_order_sent_blocker_count=$runtimeOrderSentBlockerCount"
Write-Host "post_fix_strategy_monitoring_runtime_order_sent_rows=$runtimeOrderSentRows"
Write-Host "post_fix_strategy_monitoring_child_exit_codes=localTradingView=$($ltv.ExitCode);strategy508=$($dedup.ExitCode);signalCorrectness=$($signal.ExitCode);runtimeEvidence=$($runtime.ExitCode)"
Write-Host "post_fix_strategy_monitoring_evidence_problems=$(@($evidenceProblems) -join ',')"
Write-Host "post_fix_strategy_monitoring_summary_json=$summaryJson"
Write-Host "post_fix_strategy_monitoring_next_action=$nextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only post-fix strategy monitoring packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, change schedulers, or authorize manual order actions"
Write-Host "[post-fix-strategy-monitoring] read-only check complete"

if ($evidenceProblems.Count -gt 0) {
    throw "Post-fix strategy monitoring evidence unavailable: $(@($evidenceProblems) -join ',')"
}
if ($RequireNoMissedOrder.IsPresent -and -not $executionOk) {
    throw "Post-fix strategy monitoring found missing evaluation/order review required: $executionMachineStatus"
}
