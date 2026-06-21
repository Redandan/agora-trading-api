param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$MaxAttempts = 3,
    [int]$SleepSeconds = 300,
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RequireEvidenceReady
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
        throw "$Name contains unsupported characters for profit evidence watch arguments."
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
        throw "Unable to find powershell or pwsh for profit evidence watch."
    }

    Write-Host "[profit-evidence-watch] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Text = ($childOutput | Out-String -Width 4096)
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
                Write-Host "[profit-evidence-watch] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[profit-evidence-watch] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

    return [pscustomobject]@{
        Text = $output
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
if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 48) {
    throw "MaxAttempts must be between 1 and 48."
}
if ($SleepSeconds -lt 0 -or $SleepSeconds -gt 3600) {
    throw "SleepSeconds must be between 0 and 3600."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) {
    throw "ChildTimeoutSeconds must be between 60 and 3600."
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

$briefArgs = $commonArgs + @(
    "-ExecutionDays", "$ExecutionDays",
    "-BlockedDays", "$BlockedDays",
    "-AccuracyDays", "$AccuracyDays",
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds"
)

$observationArgs = $commonArgs + @(
    "-ReviewDays", "$AccuracyDays",
    "-ReplayIdDays", "3",
    "-Limit", "$([Math]::Min($ReplayLimit, 500))"
)

Write-Host "[profit-evidence-watch] read-only bounded watcher"
Write-Host "scope=READ_ONLY; invokes prepare_profit_readiness_brief_ssh.ps1 and smoke_data_freshness_replay_observation_bundle_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol maxAttempts=$MaxAttempts sleepSeconds=$SleepSeconds requireEvidenceReady=$($RequireEvidenceReady.IsPresent.ToString().ToLowerInvariant())"

$finalStatus = "PENDING_EVIDENCE"
$finalReason = "NO_ATTEMPTS"
$finalBriefStatus = ""
$finalDataFreshnessStatus = ""
$finalReplayRecommendation = ""

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    Write-Host ""
    Write-Host "[profit-evidence-watch] attempt=$attempt/$MaxAttempts"

    $brief = Invoke-ReadOnlyScript -ScriptName "prepare_profit_readiness_brief_ssh.ps1" -Arguments $briefArgs
    $observation = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_replay_observation_bundle_ssh.ps1" -Arguments $observationArgs

    $briefStatus = Get-LastPrefixedValue -Text $brief.Text -Prefix "profit_readiness_brief_status="
    $signalPolicyClear = Get-LastPrefixedValue -Text $brief.Text -Prefix "signal_policy_clear="
    $dataFreshnessStatus = Get-LastPrefixedValue -Text $brief.Text -Prefix "data_freshness_current_status="
    $dataFreshnessClean = Get-LastPrefixedValue -Text $brief.Text -Prefix "data_freshness_current_clean="
    $trailingAcceptance = Get-LastPrefixedValue -Text $brief.Text -Prefix "trailing_stop_acceptance="
    $replayRecommendation = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_observation_bundle_recommendation="
    $replayCandidateRecommendation = Get-LastPrefixedValue -Text $observation.Text -Prefix "  data_freshness_replay_candidate_id_recommendation="
    $replayRows = Get-LastPrefixedValue -Text $observation.Text -Prefix "  replay_candidate_id_rows="
    $completeReplayRows = Get-LastPrefixedValue -Text $observation.Text -Prefix "  complete_replayable_candidate_rows="
    $missingCounterfactualFields = Get-LastPrefixedValue -Text $observation.Text -Prefix "  missing_counterfactual_fields="

    $finalBriefStatus = $briefStatus
    $finalDataFreshnessStatus = $dataFreshnessStatus
    $finalReplayRecommendation = $replayRecommendation

    $evidenceReady = (
        $brief.ExitCode -eq 0 -and
        $observation.ExitCode -eq 0 -and
        $dataFreshnessStatus -eq "CLEAN" -and
        $replayCandidateRecommendation -eq "REPLAY_CANDIDATE_ID_EVIDENCE_OK" -and
        $replayRecommendation -eq "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES"
    )

    if ($evidenceReady) {
        $finalStatus = "EVIDENCE_READY_FOR_REVIEW_NOT_LIVE"
        $finalReason = "DATAFRESHNESS_AND_REPLAY_EVIDENCE_READY"
    } elseif ($dataFreshnessStatus -eq "NO_CURRENT_SAMPLE" -or [string]::IsNullOrWhiteSpace($dataFreshnessStatus)) {
        $finalStatus = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
        $finalReason = "NO_CURRENT_SAMPLE"
    } elseif ($replayCandidateRecommendation -ne "REPLAY_CANDIDATE_ID_EVIDENCE_OK") {
        $finalStatus = "PENDING_REPLAY_CANDIDATE_ID_EVIDENCE"
        $finalReason = $replayCandidateRecommendation
    } else {
        $finalStatus = "PENDING_COUNTERFACTUAL_REPLAY_EVIDENCE"
        $finalReason = $replayRecommendation
    }

    Write-Host "attempt_profit_readiness_brief_status=$briefStatus"
    Write-Host "attempt_signal_policy_clear=$signalPolicyClear"
    Write-Host "attempt_data_freshness_current_status=$dataFreshnessStatus"
    Write-Host "attempt_data_freshness_current_clean=$dataFreshnessClean"
    Write-Host "attempt_trailing_stop_acceptance=$trailingAcceptance"
    Write-Host "attempt_replay_candidate_id_recommendation=$replayCandidateRecommendation"
    Write-Host "attempt_replay_candidate_id_rows=$replayRows"
    Write-Host "attempt_complete_replayable_candidate_rows=$completeReplayRows"
    Write-Host "attempt_missing_counterfactual_fields=$missingCounterfactualFields"
    Write-Host "attempt_replay_observation_bundle_recommendation=$replayRecommendation"
    Write-Host "attempt_profit_evidence_ready=$($evidenceReady.ToString().ToLowerInvariant())"

    if ($evidenceReady) {
        break
    }
    if ($attempt -lt $MaxAttempts -and $SleepSeconds -gt 0) {
        Write-Host "[profit-evidence-watch] sleeping seconds=$SleepSeconds"
        Start-Sleep -Seconds $SleepSeconds
    }
}

Write-Host ""
Write-Host "profit_evidence_watch_status=$finalStatus"
Write-Host "profit_evidence_watch_reason=$finalReason"
Write-Host "profit_evidence_watch_brief_status=$finalBriefStatus"
Write-Host "profit_evidence_watch_data_freshness_status=$finalDataFreshnessStatus"
Write-Host "profit_evidence_watch_replay_recommendation=$finalReplayRecommendation"
Write-Host "profit_evidence_watch_next_action=If pending, rerun this bounded watcher after new DataFreshnessGuard rows are expected; do not relax DataFreshness, EntryDedup, or live policy from watcher output alone."
Write-Host "notAuthorization=read-only evidence watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-evidence-watch] read-only check complete"

if ($RequireEvidenceReady -and $finalStatus -ne "EVIDENCE_READY_FOR_REVIEW_NOT_LIVE") {
    throw "Profit evidence is not ready: status=$finalStatus reason=$finalReason"
}
