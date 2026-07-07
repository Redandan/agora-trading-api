param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [int]$MaxAttempts = 3,
    [int]$SleepSeconds = 300,
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$Limit = 200,
    [int]$ChildTimeoutSeconds = 900,
    [string]$ReviewOutputDir = "target/profit-review",
    [switch]$RequireSampleReady
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
        throw "$Name contains unsupported characters for profit next execution watch arguments."
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
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit next execution watch." }

    Write-Host "[profit-next-execution-watch] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[profit-next-execution-watch] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[profit-next-execution-watch] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

    return [pscustomobject]@{
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
    Write-Host "[profit-next-execution-watch] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

function Save-ChildLog {
    param([string]$Name, [string]$Text)
    $path = Join-Path $ReviewOutputDir $Name
    Set-Content -LiteralPath $path -Value $Text -Encoding UTF8
    return $path
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 48) { throw "MaxAttempts must be between 1 and 48." }
if ($SleepSeconds -lt 0 -or $SleepSeconds -gt 3600) { throw "SleepSeconds must be between 0 and 3600." }
if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) { throw "ReviewDays must be between 1 and 90." }
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) { throw "ReplayIdDays must be between 1 and 30." }
if ($Limit -lt 1 -or $Limit -gt 1000) { throw "Limit must be between 1 and 1000." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

if (-not (Test-Path -LiteralPath $ReviewOutputDir)) {
    New-Item -ItemType Directory -Path $ReviewOutputDir -Force | Out-Null
}

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

Write-Host "[profit-next-execution-watch] read-only bounded watcher"
Write-Host "scope=READ_ONLY; invokes prepare_data_freshness_replay_evidence_readiness_ssh.ps1 and prepare_profit_next_execution_blocker_packet.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol strategyId=$StrategyId maxAttempts=$MaxAttempts sleepSeconds=$SleepSeconds requireSampleReady=$($RequireSampleReady.IsPresent.ToString().ToLowerInvariant())"

$finalStatus = "PENDING_NEXT_EXECUTION_EVIDENCE"
$finalReason = "NO_ATTEMPTS"
$finalRoute = ""
$finalBlockerStatus = ""
$finalUniqueBlocker = ""
$finalObservationStatus = ""
$finalObservationSampleReady = ""
$finalSampleCollectionBlockedBy = ""
$finalOpenOcoPositions = ""
$finalReplayCandidateRows = ""
$finalCompleteReplayableRows = ""
$finalNextAction = ""

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    Write-Host ""
    Write-Host "[profit-next-execution-watch] attempt=$attempt/$MaxAttempts"

    $dataFreshness = Invoke-ReadOnlyScript -ScriptName "prepare_data_freshness_replay_evidence_readiness_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string]$ReviewDays,
        "-ReplayIdDays", [string]$ReplayIdDays,
        "-Limit", [string]$Limit
    ))
    Write-ChildFailureContext -ScriptName "prepare_data_freshness_replay_evidence_readiness_ssh.ps1" -Result $dataFreshness
    $dataFreshnessLogPath = Save-ChildLog -Name "profit-next-execution-watch-data-freshness-attempt-$attempt.log" -Text $dataFreshness.Text

    $nextBlocker = Invoke-ReadOnlyScript -ScriptName "prepare_profit_next_execution_blocker_packet.ps1" -Arguments ($commonArgs + @(
        "-StrategyId", [string]$StrategyId,
        "-DataFreshnessReadinessLogPath", $dataFreshnessLogPath,
        "-RequireReady"
    ))
    Write-ChildFailureContext -ScriptName "prepare_profit_next_execution_blocker_packet.ps1" -Result $nextBlocker
    $nextBlockerLogPath = Save-ChildLog -Name "profit-next-execution-watch-next-blocker-attempt-$attempt.log" -Text $nextBlocker.Text
    $nextBlockerLatestPath = Save-ChildLog -Name "profit-next-execution-blocker-packet-latest.log" -Text $nextBlocker.Text

    $route = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_route="
    $blockerStatus = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_blocker_status="
    $uniqueBlocker = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_unique_blocker="
    $observationStatus = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_observation_status="
    $observationSampleReady = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_observation_sample_ready="
    $sampleCollectionBlockedBy = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_sample_collection_blocked_by="
    $openOcoPositions = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_open_oco_positions="
    $replayCandidateRows = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "data_freshness_replay_candidate_id_rows="
    $completeReplayRows = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "data_freshness_complete_replayable_candidate_rows="
    $exactUnlockCommand = Get-LastPrefixedValue -Text $nextBlocker.Text -Prefix "profit_next_execution_exact_unlock_command="

    $finalRoute = $route
    $finalBlockerStatus = $blockerStatus
    $finalUniqueBlocker = $uniqueBlocker
    $finalObservationStatus = $observationStatus
    $finalObservationSampleReady = $observationSampleReady
    $finalSampleCollectionBlockedBy = $sampleCollectionBlockedBy
    $finalOpenOcoPositions = $openOcoPositions
    $finalReplayCandidateRows = $replayCandidateRows
    $finalCompleteReplayableRows = $completeReplayRows
    $finalNextAction = if ([string]::IsNullOrWhiteSpace($exactUnlockCommand)) { "Rerun the watcher after new read-only evidence is expected." } else { $exactUnlockCommand }

    $replayRowsMissing = ([string]::IsNullOrWhiteSpace($replayCandidateRows) -or $replayCandidateRows -eq "0" -or [string]::IsNullOrWhiteSpace($completeReplayRows) -or $completeReplayRows -eq "0")
    if ($nextBlocker.ExitCode -ne 0 -and [string]::IsNullOrWhiteSpace($blockerStatus)) {
        $finalStatus = "PENDING_NEXT_EXECUTION_PACKET_REFRESH"
        $finalReason = "NEXT_EXECUTION_PACKET_FAILED"
    } elseif ($observationSampleReady -eq "true") {
        $finalStatus = "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
        $finalReason = "TRAILING_DRY_RUN_SAMPLE_READY"
    } elseif ($blockerStatus -eq "TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION" -and ($uniqueBlocker -eq "NO_OPEN_OCO_POSITIONS" -or $sampleCollectionBlockedBy -eq "NO_OPEN_OCO_POSITIONS")) {
        $finalStatus = "PENDING_OPEN_OCO_SAMPLE"
        $finalReason = "NO_OPEN_OCO_POSITIONS"
    } elseif ($uniqueBlocker -eq "FIX_TRAILING_OPT_IN_EVIDENCE") {
        $finalStatus = "PENDING_TRAILING_OPT_IN_EVIDENCE"
        $finalReason = "FIX_TRAILING_OPT_IN_EVIDENCE"
    } elseif ($replayRowsMissing) {
        $finalStatus = "PENDING_DATAFRESHNESS_REPLAY_EVIDENCE"
        $finalReason = "REPLAY_CANDIDATE_ROWS_MISSING"
    } elseif (-not [string]::IsNullOrWhiteSpace($uniqueBlocker)) {
        $finalStatus = "PENDING_NEXT_EXECUTION_BLOCKER"
        $finalReason = $uniqueBlocker
    } else {
        $finalStatus = "PENDING_NEXT_EXECUTION_EVIDENCE"
        $finalReason = $blockerStatus
    }

    Write-Host "attempt_data_freshness_log_path=$dataFreshnessLogPath"
    Write-Host "attempt_profit_next_execution_log_path=$nextBlockerLogPath"
    Write-Host "attempt_profit_next_execution_latest_log_path=$nextBlockerLatestPath"
    Write-Host "attempt_profit_next_execution_route=$route"
    Write-Host "attempt_profit_next_execution_blocker_status=$blockerStatus"
    Write-Host "attempt_profit_next_execution_unique_blocker=$uniqueBlocker"
    Write-Host "attempt_profit_next_execution_observation_status=$observationStatus"
    Write-Host "attempt_profit_next_execution_observation_sample_ready=$observationSampleReady"
    Write-Host "attempt_profit_next_execution_sample_collection_blocked_by=$sampleCollectionBlockedBy"
    Write-Host "attempt_profit_next_execution_open_oco_positions=$openOcoPositions"
    Write-Host "attempt_profit_next_execution_replay_candidate_id_rows=$replayCandidateRows"
    Write-Host "attempt_profit_next_execution_complete_replayable_candidate_rows=$completeReplayRows"
    Write-Host "attempt_profit_next_execution_ready=$($($finalStatus -eq "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE").ToString().ToLowerInvariant())"

    if ($finalStatus -eq "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE") { break }
    if ($attempt -lt $MaxAttempts -and $SleepSeconds -gt 0) {
        Write-Host "[profit-next-execution-watch] sleeping seconds=$SleepSeconds"
        Start-Sleep -Seconds $SleepSeconds
    }
}

Write-Host ""
Write-Host "profit_next_execution_watch_status=$finalStatus"
Write-Host "profit_next_execution_watch_reason=$finalReason"
Write-Host "profit_next_execution_watch_route=$finalRoute"
Write-Host "profit_next_execution_watch_blocker_status=$finalBlockerStatus"
Write-Host "profit_next_execution_watch_unique_blocker=$finalUniqueBlocker"
Write-Host "profit_next_execution_watch_observation_status=$finalObservationStatus"
Write-Host "profit_next_execution_watch_observation_sample_ready=$finalObservationSampleReady"
Write-Host "profit_next_execution_watch_sample_collection_blocked_by=$finalSampleCollectionBlockedBy"
Write-Host "profit_next_execution_watch_open_oco_positions=$finalOpenOcoPositions"
Write-Host "profit_next_execution_watch_data_freshness_replay_candidate_id_rows=$finalReplayCandidateRows"
Write-Host "profit_next_execution_watch_data_freshness_complete_replayable_candidate_rows=$finalCompleteReplayableRows"
Write-Host "profit_next_execution_watch_next_action=$finalNextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only next-execution watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-next-execution-watch] read-only check complete"

if ($RequireSampleReady -and $finalStatus -ne "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
    throw "Profit next execution evidence is not sample-ready: status=$finalStatus reason=$finalReason"
}
