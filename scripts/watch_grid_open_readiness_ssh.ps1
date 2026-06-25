param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$Symbol = "BTCUSDT",
    [int]$MaxAttempts = 3,
    [int]$SleepSeconds = 300,
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [int]$ChildTimeoutSeconds = 1200,
    [switch]$RequireOpenable
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
        throw "$Name contains unsupported characters for grid open readiness watch arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
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
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open readiness watch." }

    Write-Host "[grid-open-readiness-watch] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[grid-open-readiness-watch] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[grid-open-readiness-watch] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

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
    Write-Host "[grid-open-readiness-watch] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($MaxAttempts -lt 1 -or $MaxAttempts -gt 48) { throw "MaxAttempts must be between 1 and 48." }
if ($SleepSeconds -lt 0 -or $SleepSeconds -gt 3600) { throw "SleepSeconds must be between 0 and 3600." }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$boardArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-AgoraMarketApiToolsDir", $AgoraMarketApiToolsDir,
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct"
)

Write-Host "[grid-open-readiness-watch] read-only bounded watcher"
Write-Host "scope=READ_ONLY; invokes prepare_grid_open_blocker_priority_board_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol maxAttempts=$MaxAttempts sleepSeconds=$SleepSeconds requireOpenable=$($RequireOpenable.IsPresent.ToString().ToLowerInvariant())"

$finalStatus = "PENDING_GRID_OPEN_READINESS"
$finalReason = "NO_ATTEMPTS"
$finalScore = ""
$finalTopBlocker = ""
$finalBoardStatus = ""
$finalOpenable = "false"
$finalPassedGates = ""
$firstScore = $null
$lastScore = $null

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    Write-Host ""
    Write-Host "[grid-open-readiness-watch] attempt=$attempt/$MaxAttempts"
    $boardResult = Invoke-ReadOnlyScript -ScriptName "prepare_grid_open_blocker_priority_board_ssh.ps1" -Arguments $boardArgs
    Write-ChildFailureContext -ScriptName "prepare_grid_open_blocker_priority_board_ssh.ps1" -Result $boardResult

    $boardStatus = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_blocker_priority_board_status="
    $boardDecision = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_blocker_priority_board_decision="
    $openable = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_openable_now="
    $score = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_readiness_score_pct="
    $passedGates = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_readiness_passed_gates="
    $topBlockerJson = Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_blocker_priority_top_blocker="
    $boardPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $boardResult.Text -Prefix "grid_open_blocker_priority_board_packet=")
    $topBlocker = Convert-JsonObjectOrNull $topBlockerJson
    $topBlockerName = if ($null -ne $topBlocker) { [string]$topBlocker.blocker } else { "" }

    if ($null -eq $firstScore -and -not [string]::IsNullOrWhiteSpace($score)) {
        $firstScore = [decimal]$score
    }
    if (-not [string]::IsNullOrWhiteSpace($score)) {
        $lastScore = [decimal]$score
    }

    $finalBoardStatus = $boardStatus
    $finalScore = $score
    $finalTopBlocker = $topBlockerName
    $finalOpenable = if ([string]::IsNullOrWhiteSpace($openable)) { "false" } else { $openable }
    $finalPassedGates = $passedGates

    $openReady = (
        $boardResult.ExitCode -eq 0 -and
        $boardStatus -eq "READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION" -and
        $openable -eq "true" -and
        $null -ne $boardPacket -and
        [bool]$boardPacket.gridOpenableNow
    )

    if ($openReady) {
        $finalStatus = "GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION"
        $finalReason = "ALL_GRID_OPEN_READINESS_GATES_PASS"
    } elseif ($boardResult.ExitCode -ne 0) {
        $finalStatus = "PENDING_GRID_OPEN_BOARD_REFRESH"
        $finalReason = "GRID_OPEN_BLOCKER_PRIORITY_BOARD_FAILED"
    } elseif ($topBlockerName -eq "SPLIT_ACCEPTANCE_NOT_PASSING") {
        $finalStatus = "PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE"
        $finalReason = $topBlockerName
    } elseif ($topBlockerName -eq "EVENT_RISK_NOT_R0") {
        $finalStatus = "PENDING_GRID_EVENT_RISK_R0"
        $finalReason = $topBlockerName
    } elseif ($topBlockerName -eq "GRID_ENV_DIFF_NOT_APPLIED") {
        $finalStatus = "PENDING_GRID_ENV_DIFF"
        $finalReason = $topBlockerName
    } elseif ([string]::IsNullOrWhiteSpace($topBlockerName)) {
        $finalStatus = "PENDING_GRID_OPEN_READINESS"
        $finalReason = $boardDecision
    } else {
        $finalStatus = "PENDING_GRID_OPEN_BLOCKERS"
        $finalReason = $topBlockerName
    }

    Write-Host "attempt_grid_open_board_status=$boardStatus"
    Write-Host "attempt_grid_open_board_decision=$boardDecision"
    Write-Host "attempt_grid_openable_now=$openable"
    Write-Host "attempt_grid_open_readiness_score_pct=$score"
    Write-Host "attempt_grid_open_readiness_passed_gates=$passedGates"
    Write-Host "attempt_grid_open_top_blocker=$topBlockerName"
    Write-Host "attempt_grid_open_ready=$($openReady.ToString().ToLowerInvariant())"

    if ($openReady) { break }
    if ($attempt -lt $MaxAttempts -and $SleepSeconds -gt 0) {
        Write-Host "[grid-open-readiness-watch] sleeping seconds=$SleepSeconds"
        Start-Sleep -Seconds $SleepSeconds
    }
}

$scoreDelta = ""
if ($null -ne $firstScore -and $null -ne $lastScore) {
    $scoreDelta = [string]([math]::Round($lastScore - $firstScore, 2))
}

Write-Host ""
Write-Host "grid_open_readiness_watch_status=$finalStatus"
Write-Host "grid_open_readiness_watch_reason=$finalReason"
Write-Host "grid_open_readiness_watch_board_status=$finalBoardStatus"
Write-Host "grid_open_readiness_watch_openable=$finalOpenable"
Write-Host "grid_open_readiness_watch_score_pct=$finalScore"
Write-Host "grid_open_readiness_watch_score_delta_pct=$scoreDelta"
Write-Host "grid_open_readiness_watch_passed_gates=$finalPassedGates"
Write-Host "grid_open_readiness_watch_top_blocker=$finalTopBlocker"
Write-Host "grid_open_readiness_watch_next_action=If pending, resolve the top blocker named above, then rerun this bounded watcher; do not deploy, change env, call createGrid, or enable grid/scheduler/recovery from watcher output alone."
Write-Host "notAuthorization=read-only grid open readiness watcher only; does not deploy, restart, reload nginx, change production env, enable live trading, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[grid-open-readiness-watch] read-only check complete"

if ($RequireOpenable -and $finalStatus -ne "GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION") {
    throw "Grid open readiness is not openable: status=$finalStatus reason=$finalReason score=$finalScore topBlocker=$finalTopBlocker"
}
