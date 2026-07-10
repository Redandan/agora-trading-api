param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$LocalTradingViewStrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$MaxAttempts = 3,
    [int]$SleepSeconds = 300,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RunReadinessEveryAttempt,
    [switch]$RequireEvidence
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
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for LOCAL_TRADINGVIEW runtime evidence watch arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-ToIntOrDefault {
    param([string]$Value, [int]$Default = 0)
    $parsed = 0
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) {
        return $parsed
    }
    return $Default
}

function Invoke-RemoteLogProbe {
    $remoteScript = @"
set -euo pipefail
cd '$AppDir'
SYMBOL='$Symbol'
INTERVAL='$LocalTradingViewIntervalCode'
PORT=`$(cat app.port 2>/dev/null || true)
LOG=`$(ls -t logs/runs/app-*-port`$PORT.log 2>/dev/null | head -n 1 || true)
echo "active_port=`${PORT:-MISSING}"
echo "active_log=`${LOG:-MISSING}"
if [ -z "`$LOG" ]; then
  echo "target_interval_subscribe_count=0"
  echo "target_interval_connected_count=0"
  echo "target_interval_persisted_count=0"
  echo "target_interval_latest_persisted=N/A"
  echo "one_minute_persisted_count=0"
  echo "local_tradingview_log_count=0"
  exit 0
fi
persist_pattern="Persisted `$SYMBOL `$INTERVAL@"
subscribe_pattern="Auto subscribed via okx.*`$SYMBOL@`$INTERVAL"
connected_pattern="Connected: SPOT `$SYMBOL@`$INTERVAL"
latest_persisted=`$(grep "`$persist_pattern" "`$LOG" | tail -n 1 | sed -E "s/^.*Persisted `$SYMBOL `$INTERVAL@([^ ]+).*`$/\1/" || true)
echo "target_interval_subscribe_count=`$(grep -c "`$subscribe_pattern" "`$LOG" || true)"
echo "target_interval_connected_count=`$(grep -c "`$connected_pattern" "`$LOG" || true)"
echo "target_interval_persisted_count=`$(grep -c "`$persist_pattern" "`$LOG" || true)"
echo "target_interval_latest_persisted=`${latest_persisted:-N/A}"
echo "one_minute_persisted_count=`$(grep -c "Persisted `$SYMBOL 1m@" "`$LOG" || true)"
echo "local_tradingview_log_count=`$(grep -c "LocalTradingView\|KlineClosedEventListener" "`$LOG" || true)"
"@

    $output = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1
    $exitCode = $LASTEXITCODE
    [pscustomobject]@{
        Text = ($output | Out-String -Width 8192)
        ExitCode = $exitCode
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW runtime evidence watch." }

    Write-Host "[local-tradingview-runtime-evidence-watch] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[local-tradingview-runtime-evidence-watch] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[local-tradingview-runtime-evidence-watch] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

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
    Write-Host "[local-tradingview-runtime-evidence-watch] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) { throw "LocalTradingViewStrategyId must be between 1 and 999999999." }
if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) { throw "RuntimeEvidenceMinutes must be between 60 and 43200." }
if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 96) { throw "MaxAttempts must be between 1 and 96." }
if ($SleepSeconds -lt 0 -or $SleepSeconds -gt 3600) { throw "SleepSeconds must be between 0 and 3600." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16

$runtimeArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-StrategyId", [string]$LocalTradingViewStrategyId,
    "-Symbol", $Symbol,
    "-Side", "LONG",
    "-Minutes", [string]$RuntimeEvidenceMinutes
)

$readinessArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-LocalTradingViewStrategyId", [string]$LocalTradingViewStrategyId,
    "-Symbol", $Symbol,
    "-LocalTradingViewIntervalCode", $LocalTradingViewIntervalCode
)

Write-Host "[local-tradingview-runtime-evidence-watch] read-only bounded watcher"
Write-Host "scope=READ_ONLY; polls active runtime log for the configured LOCAL_TRADINGVIEW closed-K event, then invokes smoke_runtime_evidence_rca_ssh.ps1 and optionally smoke_local_tradingview_only_readiness_ssh.ps1; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol localTradingViewStrategyId=$LocalTradingViewStrategyId interval=$LocalTradingViewIntervalCode runtimeEvidenceMinutes=$RuntimeEvidenceMinutes maxAttempts=$MaxAttempts sleepSeconds=$SleepSeconds requireEvidence=$($RequireEvidence.IsPresent.ToString().ToLowerInvariant()) runReadinessEveryAttempt=$($RunReadinessEveryAttempt.IsPresent.ToString().ToLowerInvariant())"

$finalStatus = "WAIT_1D_CLOSED_K_EVENT"
$finalReason = "NO_ATTEMPTS"
$finalAttempt = 0
$finalTargetIntervalPersistedCount = 0
$finalTargetIntervalLatestPersisted = "N/A"
$finalTargetIntervalSubscribeCount = 0
$finalTargetIntervalConnectedCount = 0
$finalOneMinutePersistedCount = 0
$finalLocalTradingViewLogCount = 0
$finalRuntimeDiagnosis = ""
$finalTargetStrategyEvidenceRows = 0
$finalTargetStrategyShadowLikeRows = 0
$finalRuntimeOrderSentBlockerCount = 0
$finalRuntimeCurrentSignalDecision = ""
$finalRuntimeNoCurrentBuyReason = ""
$finalOnlyStatus = ""
$finalOnlyBlockers = "[]"
$finalOnlyNextAction = ""
$finalNextAction = "Wait for the next configured LOCAL_TRADINGVIEW closed-K event, then rerun this watcher."

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $finalAttempt = $attempt
    Write-Host ""
    Write-Host "[local-tradingview-runtime-evidence-watch] attempt=$attempt/$MaxAttempts"

    $probe = Invoke-RemoteLogProbe
    if ($probe.ExitCode -ne 0) {
        Write-ChildFailureContext -ScriptName "active-runtime-log-probe" -Result $probe
        $finalStatus = "EVIDENCE_UNAVAILABLE"
        $finalReason = "ACTIVE_RUNTIME_LOG_PROBE_FAILED"
        break
    }

    $targetPersistedCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $probe.Text -Prefix "target_interval_persisted_count=")
    $targetLatestPersisted = Get-LastPrefixedValue -Text $probe.Text -Prefix "target_interval_latest_persisted="
    $targetSubscribeCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $probe.Text -Prefix "target_interval_subscribe_count=")
    $targetConnectedCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $probe.Text -Prefix "target_interval_connected_count=")
    $oneMinutePersistedCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $probe.Text -Prefix "one_minute_persisted_count=")
    $localTradingViewLogCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $probe.Text -Prefix "local_tradingview_log_count=")

    $finalTargetIntervalPersistedCount = $targetPersistedCount
    $finalTargetIntervalLatestPersisted = if ([string]::IsNullOrWhiteSpace($targetLatestPersisted)) { "N/A" } else { $targetLatestPersisted }
    $finalTargetIntervalSubscribeCount = $targetSubscribeCount
    $finalTargetIntervalConnectedCount = $targetConnectedCount
    $finalOneMinutePersistedCount = $oneMinutePersistedCount
    $finalLocalTradingViewLogCount = $localTradingViewLogCount

    Write-Host "attempt_target_interval_subscribe_count=$targetSubscribeCount"
    Write-Host "attempt_target_interval_connected_count=$targetConnectedCount"
    Write-Host "attempt_target_interval_persisted_count=$targetPersistedCount"
    Write-Host "attempt_target_interval_latest_persisted=$finalTargetIntervalLatestPersisted"
    Write-Host "attempt_one_minute_persisted_count=$oneMinutePersistedCount"
    Write-Host "attempt_local_tradingview_log_count=$localTradingViewLogCount"

    $runtime = Invoke-ReadOnlyScript -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments $runtimeArgs
    Write-ChildFailureContext -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Result $runtime
    if ($runtime.ExitCode -ne 0) {
        $finalStatus = "EVIDENCE_UNAVAILABLE"
        $finalReason = "RUNTIME_EVIDENCE_RCA_FAILED"
        break
    }

    $runtimeDiagnosis = Get-LastPrefixedValue -Text $runtime.Text -Prefix "  diagnosis="
    $targetRows = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $runtime.Text -Prefix "  targetStrategyEvidenceRows=")
    $targetShadowRows = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $runtime.Text -Prefix "  targetStrategyShadowLikeRows=")
    $orderSentBlockerCount = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $runtime.Text -Prefix "  orderSentEvidenceBlockerCount=")
    $currentSignalDecision = Get-LastPrefixedValue -Text $runtime.Text -Prefix "  currentSignalDecision="
    $noCurrentBuyReason = Get-LastPrefixedValue -Text $runtime.Text -Prefix "  noCurrentBuyCandidateReason="

    $finalRuntimeDiagnosis = $runtimeDiagnosis
    $finalTargetStrategyEvidenceRows = $targetRows
    $finalTargetStrategyShadowLikeRows = $targetShadowRows
    $finalRuntimeOrderSentBlockerCount = $orderSentBlockerCount
    $finalRuntimeCurrentSignalDecision = $currentSignalDecision
    $finalRuntimeNoCurrentBuyReason = $noCurrentBuyReason

    Write-Host "attempt_runtime_evidence_diagnosis=$runtimeDiagnosis"
    Write-Host "attempt_target_strategy_evidence_rows=$targetRows"
    Write-Host "attempt_target_strategy_shadow_like_rows=$targetShadowRows"
    Write-Host "attempt_runtime_order_sent_blocker_count=$orderSentBlockerCount"
    Write-Host "attempt_runtime_current_signal_decision=$currentSignalDecision"
    Write-Host "attempt_runtime_no_current_buy_candidate_reason=$noCurrentBuyReason"

    $shouldRunReadiness = $RunReadinessEveryAttempt.IsPresent -or $targetPersistedCount -gt 0 -or $targetRows -gt 0
    if ($shouldRunReadiness) {
        $readiness = Invoke-ReadOnlyScript -ScriptName "smoke_local_tradingview_only_readiness_ssh.ps1" -Arguments $readinessArgs
        Write-ChildFailureContext -ScriptName "smoke_local_tradingview_only_readiness_ssh.ps1" -Result $readiness
        if ($readiness.ExitCode -ne 0) {
            $finalStatus = "EVIDENCE_UNAVAILABLE"
            $finalReason = "LOCAL_TRADINGVIEW_ONLY_READINESS_FAILED"
            break
        }
        $onlyStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "local_tradingview_only_status="
        $onlyBlockers = Get-LastPrefixedValue -Text $readiness.Text -Prefix "local_tradingview_only_blockers="
        $onlyNextAction = Get-LastPrefixedValue -Text $readiness.Text -Prefix "next_action="
        if ([string]::IsNullOrWhiteSpace($onlyBlockers)) { $onlyBlockers = "[]" }
        $finalOnlyStatus = $onlyStatus
        $finalOnlyBlockers = $onlyBlockers
        $finalOnlyNextAction = $onlyNextAction
        Write-Host "attempt_local_tradingview_only_status=$onlyStatus"
        Write-Host "attempt_local_tradingview_only_blockers=$onlyBlockers"
        Write-Host "attempt_local_tradingview_only_next_action=$onlyNextAction"
    }

    if ($targetRows -gt 0) {
        if ($targetShadowRows -gt 0) {
            $finalStatus = "BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED"
            $finalReason = "TARGET_STRATEGY_SHADOW_LIKE_RUNTIME_EVIDENCE_PRESENT"
        } else {
            $finalStatus = "WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED"
            $finalReason = "TARGET_STRATEGY_CANONICAL_WAIT_OR_NO_BUY_EVIDENCE_PRESENT"
        }
        $finalNextAction = "Rerun smoke_live_readiness_bundle_ssh.ps1 if operator review needs full cross-gate evidence; this watcher itself remains read-only."
        break
    }

    if ($targetPersistedCount -lt 1) {
        $finalStatus = "WAIT_1D_CLOSED_K_EVENT"
        $finalReason = "TARGET_INTERVAL_CLOSED_K_NOT_PERSISTED_SINCE_DEPLOY"
        $finalNextAction = "Wait for the next $Symbol@$LocalTradingViewIntervalCode closed-K persist in the active runtime log, then rerun this watcher."
    } else {
        $finalStatus = "WAIT_RUNTIME_EVIDENCE_AFTER_CLOSED_K"
        $finalReason = "TARGET_INTERVAL_CLOSED_K_PERSISTED_BUT_NO_TARGET_STRATEGY_EVIDENCE_YET"
        $finalNextAction = "Inspect LocalTradingView evaluator routing if this persists after the closed-K event; do not place manual orders from this watcher."
    }

    if ($attempt -lt $MaxAttempts -and $SleepSeconds -gt 0) {
        Write-Host "[local-tradingview-runtime-evidence-watch] sleeping seconds=$SleepSeconds"
        Start-Sleep -Seconds $SleepSeconds
    }
}

Write-Host ""
Write-Host "local_tradingview_runtime_evidence_watch_status=$finalStatus"
Write-Host "local_tradingview_runtime_evidence_watch_reason=$finalReason"
Write-Host "local_tradingview_runtime_evidence_watch_attempts=$finalAttempt"
Write-Host "local_tradingview_runtime_evidence_watch_target_interval_subscribe_count=$finalTargetIntervalSubscribeCount"
Write-Host "local_tradingview_runtime_evidence_watch_target_interval_connected_count=$finalTargetIntervalConnectedCount"
Write-Host "local_tradingview_runtime_evidence_watch_target_interval_persisted_count=$finalTargetIntervalPersistedCount"
Write-Host "local_tradingview_runtime_evidence_watch_target_interval_latest_persisted=$finalTargetIntervalLatestPersisted"
Write-Host "local_tradingview_runtime_evidence_watch_one_minute_persisted_count=$finalOneMinutePersistedCount"
Write-Host "local_tradingview_runtime_evidence_watch_local_tradingview_log_count=$finalLocalTradingViewLogCount"
Write-Host "local_tradingview_runtime_evidence_watch_runtime_diagnosis=$finalRuntimeDiagnosis"
Write-Host "local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows=$finalTargetStrategyEvidenceRows"
Write-Host "local_tradingview_runtime_evidence_watch_target_strategy_shadow_like_rows=$finalTargetStrategyShadowLikeRows"
Write-Host "local_tradingview_runtime_evidence_watch_order_sent_blocker_count=$finalRuntimeOrderSentBlockerCount"
Write-Host "local_tradingview_runtime_evidence_watch_current_signal_decision=$finalRuntimeCurrentSignalDecision"
Write-Host "local_tradingview_runtime_evidence_watch_no_current_buy_candidate_reason=$finalRuntimeNoCurrentBuyReason"
Write-Host "local_tradingview_runtime_evidence_watch_only_status=$finalOnlyStatus"
Write-Host "local_tradingview_runtime_evidence_watch_only_blockers=$finalOnlyBlockers"
Write-Host "local_tradingview_runtime_evidence_watch_only_next_action=$finalOnlyNextAction"
Write-Host "local_tradingview_runtime_evidence_watch_next_action=$finalNextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only LOCAL_TRADINGVIEW runtime evidence watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, change schedulers, or authorize manual order actions"
Write-Host "[local-tradingview-runtime-evidence-watch] read-only check complete"

if ($RequireEvidence -and $finalTargetStrategyEvidenceRows -lt 1) {
    throw "LOCAL_TRADINGVIEW runtime evidence is not present yet: status=$finalStatus reason=$finalReason"
}
