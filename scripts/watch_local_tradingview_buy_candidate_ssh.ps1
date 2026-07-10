param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$LocalTradingViewStrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$LocalTradingViewDays = 90,
    [string]$LocalTradingViewSource = "binance",
    [int]$MaxAttempts = 3,
    [int]$SleepSeconds = 300,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RunFullReadinessEveryAttempt,
    [switch]$RequireCurrentCandidate,
    [switch]$RequireReady
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
        throw "$Name contains unsupported characters for LOCAL_TRADINGVIEW BUY candidate watch arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW BUY candidate watch." }

    Write-Host "[local-tradingview-buy-candidate-watch] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[local-tradingview-buy-candidate-watch] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[local-tradingview-buy-candidate-watch] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

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
    Write-Host "[local-tradingview-buy-candidate-watch] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) { throw "LocalTradingViewStrategyId must be between 1 and 999999999." }
if ($LocalTradingViewDays -lt 7 -or $LocalTradingViewDays -gt 730) { throw "LocalTradingViewDays must be between 7 and 730." }
if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 96) { throw "MaxAttempts must be between 1 and 96." }
if ($SleepSeconds -lt 0 -or $SleepSeconds -gt 3600) { throw "SleepSeconds must be between 0 and 3600." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16
Assert-SmokeTokenSafe -Name "LocalTradingViewSource" -Value $LocalTradingViewSource -MaxLength 32

$candidateArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-StrategyId", [string]$LocalTradingViewStrategyId,
    "-Symbol", $Symbol,
    "-IntervalCode", $LocalTradingViewIntervalCode,
    "-Days", [string]$LocalTradingViewDays,
    "-Source", $LocalTradingViewSource
)

$readinessArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-LocalTradingViewStrategyId", [string]$LocalTradingViewStrategyId,
    "-Symbol", $Symbol,
    "-LocalTradingViewIntervalCode", $LocalTradingViewIntervalCode,
    "-LocalTradingViewDays", [string]$LocalTradingViewDays,
    "-LocalTradingViewSource", $LocalTradingViewSource
)

Write-Host "[local-tradingview-buy-candidate-watch] read-only bounded watcher"
Write-Host "scope=READ_ONLY; invokes smoke_local_tradingview_candidate_ssh.ps1 each attempt and smoke_local_tradingview_only_readiness_ssh.ps1 only after a current BUY candidate appears unless -RunFullReadinessEveryAttempt is set; no production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol localTradingViewStrategyId=$LocalTradingViewStrategyId interval=$LocalTradingViewIntervalCode days=$LocalTradingViewDays source=$LocalTradingViewSource maxAttempts=$MaxAttempts sleepSeconds=$SleepSeconds requireCurrentCandidate=$($RequireCurrentCandidate.IsPresent.ToString().ToLowerInvariant()) requireReady=$($RequireReady.IsPresent.ToString().ToLowerInvariant()) runFullReadinessEveryAttempt=$($RunFullReadinessEveryAttempt.IsPresent.ToString().ToLowerInvariant())"

$finalStatus = "WAIT_BUY"
$finalReason = "NO_ATTEMPTS"
$finalAttempt = 0
$finalCandidateStatus = ""
$finalDataEnd = ""
$finalDataClose = ""
$finalLastOrderAt = ""
$finalCandidateReadiness = ""
$finalCandidateBlockers = "[]"
$finalPreExecutionReadiness = ""
$finalPreExecutionBlockers = "[]"
$finalOnlyStatus = ""
$finalOnlyBlockers = "[]"
$finalOnlyHealthWarnings = "[]"
$finalNotionalAccepted = ""
$finalEffectiveNotionalUsdt = ""
$finalDailyCapAvailable = ""
$finalOpenPositionCapAvailable = ""
$finalOpenExactPositionExists = ""
$finalDuplicateBarExists = ""
$finalSignalStale = ""
$finalNextAction = "Rerun this bounded watcher after the next closed bar if no current BUY candidate appears."

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $finalAttempt = $attempt
    Write-Host ""
    Write-Host "[local-tradingview-buy-candidate-watch] attempt=$attempt/$MaxAttempts"

    $candidate = Invoke-ReadOnlyScript -ScriptName "smoke_local_tradingview_candidate_ssh.ps1" -Arguments $candidateArgs
    Write-ChildFailureContext -ScriptName "smoke_local_tradingview_candidate_ssh.ps1" -Result $candidate

    $candidateStatus = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  currentCandidateStatus="
    $dataEnd = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  dataEnd="
    $dataClose = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  dataClose="
    $lastOrderAt = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  lastOrderAt="
    $candidateReadiness = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewReadiness="
    $candidateBlockers = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  local_tradingview_blockers="
    $preExecutionReadiness = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewPreExecutionReadiness="
    $preExecutionBlockers = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  local_tradingview_pre_execution_blockers="
    $notionalAccepted = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewNotionalAccepted="
    $effectiveNotionalUsdt = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewEffectiveNotionalUsdt="
    $dailyCapAvailable = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewDailyCapAvailable="
    $openPositionCapAvailable = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewOpenPositionCapAvailable="
    $openExactPositionExists = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewOpenExactPositionExists="
    $duplicateBarExists = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewDuplicateBarExists="
    $signalStale = Get-LastPrefixedValue -Text $candidate.Text -Prefix "  localTradingViewSignalStale="

    if ([string]::IsNullOrWhiteSpace($candidateBlockers)) { $candidateBlockers = "[]" }
    if ([string]::IsNullOrWhiteSpace($preExecutionBlockers)) { $preExecutionBlockers = "[]" }

    $finalCandidateStatus = $candidateStatus
    $finalDataEnd = $dataEnd
    $finalDataClose = $dataClose
    $finalLastOrderAt = $lastOrderAt
    $finalCandidateReadiness = $candidateReadiness
    $finalCandidateBlockers = $candidateBlockers
    $finalPreExecutionReadiness = $preExecutionReadiness
    $finalPreExecutionBlockers = $preExecutionBlockers
    $finalNotionalAccepted = $notionalAccepted
    $finalEffectiveNotionalUsdt = $effectiveNotionalUsdt
    $finalDailyCapAvailable = $dailyCapAvailable
    $finalOpenPositionCapAvailable = $openPositionCapAvailable
    $finalOpenExactPositionExists = $openExactPositionExists
    $finalDuplicateBarExists = $duplicateBarExists
    $finalSignalStale = $signalStale

    Write-Host "attempt_local_tradingview_current_candidate_status=$candidateStatus"
    Write-Host "attempt_local_tradingview_data_end=$dataEnd"
    Write-Host "attempt_local_tradingview_data_close=$dataClose"
    Write-Host "attempt_local_tradingview_last_order_at=$lastOrderAt"
    Write-Host "attempt_local_tradingview_candidate_readiness=$candidateReadiness"
    Write-Host "attempt_local_tradingview_candidate_blockers=$candidateBlockers"
    Write-Host "attempt_local_tradingview_pre_execution_readiness=$preExecutionReadiness"
    Write-Host "attempt_local_tradingview_pre_execution_blockers=$preExecutionBlockers"

    if ($candidate.ExitCode -ne 0) {
        $finalStatus = "EVIDENCE_UNAVAILABLE"
        $finalReason = "LOCAL_TRADINGVIEW_CANDIDATE_SMOKE_FAILED"
        break
    }

    $hasCurrentCandidate = $candidateStatus -eq "HAS_CURRENT_BUY_CANDIDATE"
    if ($hasCurrentCandidate -or $RunFullReadinessEveryAttempt.IsPresent) {
        $readiness = Invoke-ReadOnlyScript -ScriptName "smoke_local_tradingview_only_readiness_ssh.ps1" -Arguments $readinessArgs
        Write-ChildFailureContext -ScriptName "smoke_local_tradingview_only_readiness_ssh.ps1" -Result $readiness

        $onlyStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "local_tradingview_only_status="
        $onlyBlockers = Get-LastPrefixedValue -Text $readiness.Text -Prefix "local_tradingview_only_blockers="
        $onlyHealthWarnings = Get-LastPrefixedValue -Text $readiness.Text -Prefix "local_tradingview_only_health_warnings="
        if ([string]::IsNullOrWhiteSpace($onlyBlockers)) { $onlyBlockers = "[]" }
        if ([string]::IsNullOrWhiteSpace($onlyHealthWarnings)) { $onlyHealthWarnings = "[]" }

        $finalOnlyStatus = $onlyStatus
        $finalOnlyBlockers = $onlyBlockers
        $finalOnlyHealthWarnings = $onlyHealthWarnings

        Write-Host "attempt_local_tradingview_only_status=$onlyStatus"
        Write-Host "attempt_local_tradingview_only_blockers=$onlyBlockers"
        Write-Host "attempt_local_tradingview_only_health_warnings=$onlyHealthWarnings"

        if ($readiness.ExitCode -ne 0) {
            $finalStatus = "EVIDENCE_UNAVAILABLE"
            $finalReason = "LOCAL_TRADINGVIEW_ONLY_READINESS_SMOKE_FAILED"
            break
        }
        if ($onlyStatus -in @("READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED", "READY_CURRENT_BUY_CANDIDATE_BTC_BASE_LIVE_MICRO_ARMED")) {
            $finalStatus = $onlyStatus
            $finalReason = "CURRENT_BUY_CANDIDATE_AND_PRE_EXECUTION_GATES_CLEAR"
            $finalNextAction = "Inspect live evaluator execution/audit rows immediately after the next LOCAL_TRADINGVIEW evaluator tick; this watcher itself remains read-only and does not authorize manual order/OCO actions."
            break
        }
        if ($hasCurrentCandidate) {
            $finalStatus = "BLOCKED_CURRENT_BUY_CANDIDATE"
            $finalReason = if ([string]::IsNullOrWhiteSpace($onlyStatus)) { "LOCAL_TRADINGVIEW_ONLY_READINESS_NOT_READY" } else { $onlyStatus }
            $finalNextAction = "Fix LOCAL_TRADINGVIEW-only blockers before treating this current TradingView parity BUY as executable."
            break
        }
    }

    if ($hasCurrentCandidate) {
        $finalStatus = "CURRENT_BUY_CANDIDATE_DETECTED_NEEDS_READINESS"
        $finalReason = "CURRENT_BUY_CANDIDATE_DETECTED"
        $finalNextAction = "Run smoke_local_tradingview_only_readiness_ssh.ps1 immediately; watcher did not collect full readiness evidence."
        break
    }

    $finalStatus = "WAIT_BUY"
    $finalReason = "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE"
    $finalNextAction = "Wait for the latest closed bar to emit a LOCAL_TRADINGVIEW parity BUY; the watcher will rerun until MaxAttempts is exhausted."

    if ($attempt -lt $MaxAttempts -and $SleepSeconds -gt 0) {
        Write-Host "[local-tradingview-buy-candidate-watch] sleeping seconds=$SleepSeconds"
        Start-Sleep -Seconds $SleepSeconds
    }
}

Write-Host ""
Write-Host "local_tradingview_buy_candidate_watch_status=$finalStatus"
Write-Host "local_tradingview_buy_candidate_watch_reason=$finalReason"
Write-Host "local_tradingview_buy_candidate_watch_attempts=$finalAttempt"
Write-Host "local_tradingview_buy_candidate_watch_current_candidate_status=$finalCandidateStatus"
Write-Host "local_tradingview_buy_candidate_watch_data_end=$finalDataEnd"
Write-Host "local_tradingview_buy_candidate_watch_data_close=$finalDataClose"
Write-Host "local_tradingview_buy_candidate_watch_last_order_at=$finalLastOrderAt"
Write-Host "local_tradingview_buy_candidate_watch_candidate_readiness=$finalCandidateReadiness"
Write-Host "local_tradingview_buy_candidate_watch_candidate_blockers=$finalCandidateBlockers"
Write-Host "local_tradingview_buy_candidate_watch_pre_execution_readiness=$finalPreExecutionReadiness"
Write-Host "local_tradingview_buy_candidate_watch_pre_execution_blockers=$finalPreExecutionBlockers"
Write-Host "local_tradingview_buy_candidate_watch_only_status=$finalOnlyStatus"
Write-Host "local_tradingview_buy_candidate_watch_only_blockers=$finalOnlyBlockers"
Write-Host "local_tradingview_buy_candidate_watch_only_health_warnings=$finalOnlyHealthWarnings"
Write-Host "local_tradingview_buy_candidate_watch_notional_accepted=$finalNotionalAccepted"
Write-Host "local_tradingview_buy_candidate_watch_effective_notional_usdt=$finalEffectiveNotionalUsdt"
Write-Host "local_tradingview_buy_candidate_watch_daily_cap_available=$finalDailyCapAvailable"
Write-Host "local_tradingview_buy_candidate_watch_open_position_cap_available=$finalOpenPositionCapAvailable"
Write-Host "local_tradingview_buy_candidate_watch_open_exact_position_exists=$finalOpenExactPositionExists"
Write-Host "local_tradingview_buy_candidate_watch_duplicate_bar_exists=$finalDuplicateBarExists"
Write-Host "local_tradingview_buy_candidate_watch_signal_stale=$finalSignalStale"
Write-Host "local_tradingview_buy_candidate_watch_next_action=$finalNextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only LOCAL_TRADINGVIEW BUY candidate watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, change schedulers, or authorize manual order actions"
Write-Host "[local-tradingview-buy-candidate-watch] read-only check complete"

if ($RequireCurrentCandidate -and $finalCandidateStatus -ne "HAS_CURRENT_BUY_CANDIDATE") {
    throw "LOCAL_TRADINGVIEW current BUY candidate is not present: status=$finalStatus reason=$finalReason"
}
if ($RequireReady -and $finalStatus -notin @("READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED", "READY_CURRENT_BUY_CANDIDATE_BTC_BASE_LIVE_MICRO_ARMED")) {
    throw "LOCAL_TRADINGVIEW BUY candidate is not ready: status=$finalStatus reason=$finalReason blockers=$finalOnlyBlockers preExecutionBlockers=$finalPreExecutionBlockers"
}
