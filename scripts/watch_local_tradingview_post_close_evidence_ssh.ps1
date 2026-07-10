param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$LocalTradingViewStrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$MaxWaitMinutes = 1800,
    [int]$PollSeconds = 300,
    [int]$EvidenceAttempts = 3,
    [int]$EvidenceSleepSeconds = 300,
    [int]$ChildTimeoutSeconds = 1200,
    [switch]$AcceptExistingClosedK,
    [switch]$RunReadinessEveryAttempt,
    [switch]$AllowMissingEvidenceAfterClosedK,
    [switch]$AllowWaitTimeout
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
        throw "$Name contains unsupported characters for LOCAL_TRADINGVIEW post-close evidence watch arguments."
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

function Invoke-EvidenceWatcher {
    param([string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot "watch_local_tradingview_runtime_evidence_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing runtime evidence watcher: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW post-close evidence watch." }

    Write-Host "[local-tradingview-post-close-evidence-watch] child_start script=watch_local_tradingview_runtime_evidence_ssh.ps1 timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[local-tradingview-post-close-evidence-watch] child_heartbeat script=watch_local_tradingview_runtime_evidence_ssh.ps1 elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[local-tradingview-post-close-evidence-watch] child_complete script=watch_local_tradingview_runtime_evidence_ssh.ps1 exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

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
    Write-Host "[local-tradingview-post-close-evidence-watch] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) { throw "LocalTradingViewStrategyId must be between 1 and 999999999." }
if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) { throw "RuntimeEvidenceMinutes must be between 60 and 43200." }
if ($MaxWaitMinutes -lt 1 -or $MaxWaitMinutes -gt 43200) { throw "MaxWaitMinutes must be between 1 and 43200." }
if ($PollSeconds -lt 30 -or $PollSeconds -gt 3600) { throw "PollSeconds must be between 30 and 3600." }
if ($EvidenceAttempts -lt 1 -or $EvidenceAttempts -gt 24) { throw "EvidenceAttempts must be between 1 and 24." }
if ($EvidenceSleepSeconds -lt 0 -or $EvidenceSleepSeconds -gt 3600) { throw "EvidenceSleepSeconds must be between 0 and 3600." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16

$requireEvidenceAfterClosedK = -not $AllowMissingEvidenceAfterClosedK.IsPresent
$deadline = (Get-Date).AddMinutes($MaxWaitMinutes)
$maxPolls = [Math]::Max(1, [int][Math]::Ceiling(($MaxWaitMinutes * 60.0) / [Math]::Max(1, $PollSeconds)))
$baselinePersistedCount = -1
$finalStatus = "WAIT_1D_CLOSED_K_EVENT_TIMEOUT"
$finalReason = "NO_TARGET_INTERVAL_CLOSED_K_BEFORE_DEADLINE"
$finalAttempt = 0
$finalBaselinePersistedCount = 0
$finalTargetIntervalPersistedCount = 0
$finalTargetIntervalLatestPersisted = "N/A"
$finalTargetIntervalSubscribeCount = 0
$finalTargetIntervalConnectedCount = 0
$finalOneMinutePersistedCount = 0
$finalLocalTradingViewLogCount = 0
$finalEvidenceWatcherStatus = ""
$finalEvidenceWatcherReason = ""
$finalTargetStrategyEvidenceRows = 0
$finalTargetStrategyShadowLikeRows = 0
$finalNextAction = "Start this watcher before the next $Symbol@$LocalTradingViewIntervalCode closed-K or rerun with -AcceptExistingClosedK after confirming the existing persisted event should be accepted."

Write-Host "[local-tradingview-post-close-evidence-watch] read-only post-close watcher"
Write-Host "scope=READ_ONLY; waits for a fresh LOCAL_TRADINGVIEW closed-K persist in the active runtime log, then invokes watch_local_tradingview_runtime_evidence_ssh.ps1; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol localTradingViewStrategyId=$LocalTradingViewStrategyId interval=$LocalTradingViewIntervalCode maxWaitMinutes=$MaxWaitMinutes pollSeconds=$PollSeconds evidenceAttempts=$EvidenceAttempts evidenceSleepSeconds=$EvidenceSleepSeconds acceptExistingClosedK=$($AcceptExistingClosedK.IsPresent.ToString().ToLowerInvariant()) requireEvidenceAfterClosedK=$($requireEvidenceAfterClosedK.ToString().ToLowerInvariant()) runReadinessEveryAttempt=$($RunReadinessEveryAttempt.IsPresent.ToString().ToLowerInvariant())"

for ($attempt = 1; $attempt -le $maxPolls; $attempt++) {
    $finalAttempt = $attempt
    Write-Host ""
    Write-Host "[local-tradingview-post-close-evidence-watch] poll=$attempt/$maxPolls"

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

    if ($baselinePersistedCount -lt 0) {
        $baselinePersistedCount = $targetPersistedCount
        $finalBaselinePersistedCount = $baselinePersistedCount
    }

    $finalTargetIntervalPersistedCount = $targetPersistedCount
    $finalTargetIntervalLatestPersisted = if ([string]::IsNullOrWhiteSpace($targetLatestPersisted)) { "N/A" } else { $targetLatestPersisted }
    $finalTargetIntervalSubscribeCount = $targetSubscribeCount
    $finalTargetIntervalConnectedCount = $targetConnectedCount
    $finalOneMinutePersistedCount = $oneMinutePersistedCount
    $finalLocalTradingViewLogCount = $localTradingViewLogCount

    Write-Host "poll_baseline_target_interval_persisted_count=$finalBaselinePersistedCount"
    Write-Host "poll_target_interval_subscribe_count=$targetSubscribeCount"
    Write-Host "poll_target_interval_connected_count=$targetConnectedCount"
    Write-Host "poll_target_interval_persisted_count=$targetPersistedCount"
    Write-Host "poll_target_interval_latest_persisted=$finalTargetIntervalLatestPersisted"
    Write-Host "poll_one_minute_persisted_count=$oneMinutePersistedCount"
    Write-Host "poll_local_tradingview_log_count=$localTradingViewLogCount"

    $closedKObserved = $targetPersistedCount -gt $baselinePersistedCount
    if ($AcceptExistingClosedK.IsPresent -and $targetPersistedCount -gt 0) {
        $closedKObserved = $true
        $finalReason = "ACCEPTING_EXISTING_TARGET_INTERVAL_CLOSED_K"
    }

    if ($closedKObserved) {
        $watchArgs = @(
            "-SshHost", $SshHost,
            "-SshKey", $SshKey,
            "-AppDir", $AppDir,
            "-EnvFile", $EnvFile,
            "-LocalTradingViewStrategyId", [string]$LocalTradingViewStrategyId,
            "-Symbol", $Symbol,
            "-LocalTradingViewIntervalCode", $LocalTradingViewIntervalCode,
            "-RuntimeEvidenceMinutes", [string]$RuntimeEvidenceMinutes,
            "-MaxAttempts", [string]$EvidenceAttempts,
            "-SleepSeconds", [string]$EvidenceSleepSeconds,
            "-ChildTimeoutSeconds", [string]$ChildTimeoutSeconds
        )
        if ($RunReadinessEveryAttempt.IsPresent) {
            $watchArgs += "-RunReadinessEveryAttempt"
        }
        if ($requireEvidenceAfterClosedK) {
            $watchArgs += "-RequireEvidence"
        }

        $watcher = Invoke-EvidenceWatcher -Arguments $watchArgs
        Write-ChildFailureContext -ScriptName "watch_local_tradingview_runtime_evidence_ssh.ps1" -Result $watcher

        $watchStatus = Get-LastPrefixedValue -Text $watcher.Text -Prefix "local_tradingview_runtime_evidence_watch_status="
        $watchReason = Get-LastPrefixedValue -Text $watcher.Text -Prefix "local_tradingview_runtime_evidence_watch_reason="
        $targetRows = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $watcher.Text -Prefix "local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows=")
        $targetShadowRows = Convert-ToIntOrDefault (Get-LastPrefixedValue -Text $watcher.Text -Prefix "local_tradingview_runtime_evidence_watch_target_strategy_shadow_like_rows=")

        $finalEvidenceWatcherStatus = $watchStatus
        $finalEvidenceWatcherReason = $watchReason
        $finalTargetStrategyEvidenceRows = $targetRows
        $finalTargetStrategyShadowLikeRows = $targetShadowRows

        if ($watcher.ExitCode -eq 0 -and $targetRows -gt 0) {
            $finalStatus = "CLOSED_K_OBSERVED_EVIDENCE_CONFIRMED"
            $finalReason = "TARGET_INTERVAL_CLOSED_K_AND_TARGET_STRATEGY_EVIDENCE_PRESENT"
            $finalNextAction = "Evidence is available for review; this watcher itself remains read-only."
        } else {
            $finalStatus = "CLOSED_K_OBSERVED_EVIDENCE_MISSING"
            $finalReason = if ([string]::IsNullOrWhiteSpace($watchReason)) { "TARGET_INTERVAL_CLOSED_K_BUT_TARGET_STRATEGY_EVIDENCE_MISSING" } else { $watchReason }
            $finalNextAction = "Inspect LocalTradingView evaluator routing and runtime evidence RCA; do not place manual orders from this watcher."
        }
        break
    }

    if ((Get-Date) -ge $deadline -or $attempt -ge $maxPolls) {
        $finalStatus = "WAIT_1D_CLOSED_K_EVENT_TIMEOUT"
        $finalReason = "NO_TARGET_INTERVAL_CLOSED_K_BEFORE_DEADLINE"
        $finalNextAction = "No fresh $Symbol@$LocalTradingViewIntervalCode closed-K persist appeared before the wait deadline; rerun this watcher before the next close."
        break
    }

    Write-Host "[local-tradingview-post-close-evidence-watch] sleeping seconds=$PollSeconds"
    Start-Sleep -Seconds $PollSeconds
}

Write-Host ""
Write-Host "local_tradingview_post_close_evidence_watch_status=$finalStatus"
Write-Host "local_tradingview_post_close_evidence_watch_reason=$finalReason"
Write-Host "local_tradingview_post_close_evidence_watch_polls=$finalAttempt"
Write-Host "local_tradingview_post_close_evidence_watch_baseline_target_interval_persisted_count=$finalBaselinePersistedCount"
Write-Host "local_tradingview_post_close_evidence_watch_target_interval_subscribe_count=$finalTargetIntervalSubscribeCount"
Write-Host "local_tradingview_post_close_evidence_watch_target_interval_connected_count=$finalTargetIntervalConnectedCount"
Write-Host "local_tradingview_post_close_evidence_watch_target_interval_persisted_count=$finalTargetIntervalPersistedCount"
Write-Host "local_tradingview_post_close_evidence_watch_target_interval_latest_persisted=$finalTargetIntervalLatestPersisted"
Write-Host "local_tradingview_post_close_evidence_watch_one_minute_persisted_count=$finalOneMinutePersistedCount"
Write-Host "local_tradingview_post_close_evidence_watch_local_tradingview_log_count=$finalLocalTradingViewLogCount"
Write-Host "local_tradingview_post_close_evidence_watch_runtime_evidence_watch_status=$finalEvidenceWatcherStatus"
Write-Host "local_tradingview_post_close_evidence_watch_runtime_evidence_watch_reason=$finalEvidenceWatcherReason"
Write-Host "local_tradingview_post_close_evidence_watch_target_strategy_evidence_rows=$finalTargetStrategyEvidenceRows"
Write-Host "local_tradingview_post_close_evidence_watch_target_strategy_shadow_like_rows=$finalTargetStrategyShadowLikeRows"
Write-Host "local_tradingview_post_close_evidence_watch_next_action=$finalNextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only LOCAL_TRADINGVIEW post-close evidence watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, change schedulers, or authorize manual order actions"
Write-Host "[local-tradingview-post-close-evidence-watch] read-only check complete"

if (-not $AllowWaitTimeout.IsPresent -and $finalStatus -eq "WAIT_1D_CLOSED_K_EVENT_TIMEOUT") {
    throw "LOCAL_TRADINGVIEW target interval closed-K was not observed before the wait deadline."
}
if ($requireEvidenceAfterClosedK -and $finalStatus -eq "CLOSED_K_OBSERVED_EVIDENCE_MISSING") {
    throw "LOCAL_TRADINGVIEW post-close evidence is missing after the target interval closed-K."
}
