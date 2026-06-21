param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
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
        throw "$Name contains unsupported characters for readiness brief arguments."
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

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups[1].Value.Trim()
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

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Add-UniqueString {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
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
        throw "Unable to find powershell or pwsh for profit readiness brief."
    }

    Write-Host "[profit-readiness-brief] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
    $startedAt = Get-Date
    $timedOut = $false
    $stdout = ""
    $stderr = ""
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
                Write-Host "[profit-readiness-brief] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
                $exitCode = 1
                $stderr = ($jobOutput | Out-String -Width 4096)
            }
        } else {
            $exitCode = -1
        }

        $elapsedTotalSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
        Write-Host "[profit-readiness-brief] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotalSeconds"
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
if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
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

$signal = Invoke-ReadOnlyScript -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments ($commonArgs + @(
        "-ExecutionDays", [string]$ExecutionDays,
        "-BlockedDays", [string]$BlockedDays,
        "-AccuracyDays", [string]$AccuracyDays
    ))
$trailing = Invoke-ReadOnlyScript -ScriptName "smoke_trailing_stop_pnl_replay_ssh.ps1" -Arguments ($commonArgs + @(
        "-Days", [string]$ReplayDays,
        "-Limit", [string]$ReplayLimit
    ))
$ledger = Invoke-ReadOnlyScript -ScriptName "prepare_profit_blocker_ledger_ssh.ps1" -Arguments ($commonArgs + @(
        "-RequireActionable"
    ))

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($result in @($signal, $trailing, $ledger)) {
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
$dataFreshnessCurrentClean = (($signal.Text -match "staleNowKeys=0") -and ($signal.Text -match "noDataNowKeys=0") -and ($signal.Text -match "queryFailedNowKeys=0"))

$trailingAcceptance = Get-RegexValue -Text $trailing.Text -Pattern "acceptance=([A-Z_]+)" -Default "N/A"
$trailingImprovement = Get-RegexValue -Text $trailing.Text -Pattern "improvementPct=([-+0-9.]+%)" -Default "N/A"
$trailingDeltaPnl = Get-RegexValue -Text $trailing.Text -Pattern "acceptanceDeltaPnl=([-+0-9.]+)" -Default "N/A"
$trailingAcceptanceBlocker = Get-RegexValue -Text $trailing.Text -Pattern "acceptanceBlocker=([A-Z_]+)" -Default "N/A"

$ledgerStatus = Get-LastPrefixedValue -Text $ledger.Text -Prefix "profit_blocker_ledger_status="
$ledgerPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $ledger.Text -Prefix "profit_blocker_ledger_packet=")
$ledgerItems = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $ledger.Text -Prefix "profit_blocker_ledger_items=")
$ledgerMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $ledger.Text -Prefix "profit_blocker_ledger_missing_requirements=")
foreach ($item in @($ledgerMissing)) {
    Add-UniqueString -List $missingRequirements -Value ([string]$item)
}

if ($signalPolicyClear -ne "true") {
    Add-UniqueString -List $missingRequirements -Value "signal policy review clear"
}
if ($governanceMode -eq "TOO_STRICT" -or $missedStatus -eq "WARN") {
    Add-UniqueString -List $missingRequirements -Value "governance drift and missed-opportunity review"
}
if ($trailingAcceptance -ne "PASS") {
    Add-UniqueString -List $missingRequirements -Value "trailing-stop PnL acceptance PASS"
}

$entryLaneStatus = if ($signalPolicyClear -eq "true") {
    "CLEAR"
} elseif ($governanceMode -eq "TOO_STRICT" -or $missedStatus -eq "WARN") {
    "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
} else {
    "REVIEW_SIGNAL_POLICY"
}
$exitLaneStatus = if ($trailingAcceptance -eq "PASS") {
    "EXIT_SIDE_EVIDENCE_READY_NOT_LIVE"
} else {
    "EXIT_SIDE_EVIDENCE_NOT_PROVEN"
}

$briefStatus = "READY_FOR_READ_ONLY_REVIEW"
if ($ledgerStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME") {
    $briefStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
} elseif ($entryLaneStatus -ne "CLEAR") {
    $briefStatus = "BLOCKED_ENTRY_FILTER_REVIEW"
} elseif ($exitLaneStatus -ne "EXIT_SIDE_EVIDENCE_READY_NOT_LIVE") {
    $briefStatus = "BLOCKED_EXIT_REPLAY_EVIDENCE"
}

$nextAction = if ($briefStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME") {
    "Use the profit blocker ledger and runtime deploy packet for separate deploy authorization, then rerun this brief."
} elseif ($briefStatus -eq "BLOCKED_ENTRY_FILTER_REVIEW") {
    "Review governance drift and missed-opportunity rows before proposing any bounded shadow/tiny-live entry experiment."
} elseif ($briefStatus -eq "BLOCKED_EXIT_REPLAY_EVIDENCE") {
    "Collect stronger trailing/TP-stop replay evidence before any exit-side promotion review."
} else {
    "Draft separate read-only operator review packets for entry/filter and exit-side candidates; this is not live approval."
}

$brief = [pscustomobject]@{
    packetType = "PROFIT_READINESS_BRIEF"
    status = $briefStatus
    symbol = $Symbol
    entryFilterLane = [pscustomobject]@{
        status = $entryLaneStatus
        signalPolicyClear = $signalPolicyClear
        governanceMode = $governanceMode
        missedOpportunityStatus = $missedStatus
        suspiciousNoBuyCount = $suspiciousNoBuyCount
        falseBlockRiskCount = $falseBlockRiskCount
        highForwardReturnNoBuyCount = $highForwardReturnNoBuyCount
        accuracySummary = $accuracySummary
        dataFreshnessCurrentClean = $dataFreshnessCurrentClean
    }
    exitLane = [pscustomobject]@{
        status = $exitLaneStatus
        trailingStopAcceptance = $trailingAcceptance
        trailingStopImprovementPct = $trailingImprovement
        trailingStopDeltaPnl = $trailingDeltaPnl
        trailingStopAcceptanceBlocker = $trailingAcceptanceBlocker
    }
    blockerLedger = [pscustomobject]@{
        status = $ledgerStatus
        monthlyPnlTotalUsdt = if ($null -ne $ledgerPacket -and $null -ne $ledgerPacket.PSObject.Properties["monthlyPnlTotalUsdt"]) { $ledgerPacket.monthlyPnlTotalUsdt } else { "" }
        topProfitImprovementCandidate = if ($null -ne $ledgerPacket -and $null -ne $ledgerPacket.PSObject.Properties["topProfitImprovementCandidate"]) { $ledgerPacket.topProfitImprovementCandidate } else { "" }
        ledgerItems = @($ledgerItems)
    }
    missingRequirementCount = @($missingRequirements).Count
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only profit readiness brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-readiness-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes signal correctness, trailing-stop PnL replay, and profit blocker ledger only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_signal_correctness_ssh.ps1 exitCode=$($signal.ExitCode)"
Write-Host "source_smoke=smoke_trailing_stop_pnl_replay_ssh.ps1 exitCode=$($trailing.ExitCode)"
Write-Host "source_packet=prepare_profit_blocker_ledger_ssh.ps1 exitCode=$($ledger.ExitCode)"
Write-Host "signal_policy_clear=$signalPolicyClear"
Write-Host "governance_mode=$governanceMode"
Write-Host "missed_opportunity_status=$missedStatus"
Write-Host "suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "false_block_risk_count=$falseBlockRiskCount"
Write-Host "high_forward_return_no_buy_count=$highForwardReturnNoBuyCount"
Write-Host "data_freshness_current_clean=$($dataFreshnessCurrentClean.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovement"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "profit_blocker_ledger_status=$ledgerStatus"
Write-Host "entry_filter_lane_status=$entryLaneStatus"
Write-Host "exit_lane_status=$exitLaneStatus"
Write-Host ("profit_readiness_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_readiness_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "profit_readiness_brief_status=$briefStatus"
Write-Host "profit_readiness_brief_next_action=$nextAction"
Write-Host "notAuthorization=read-only profit readiness brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-readiness-brief] read-only check complete"

if ($RequireBrief -and $briefStatus -eq "READY_FOR_READ_ONLY_REVIEW" -and @($missingRequirements).Count -gt 0) {
    throw "Profit readiness brief is inconsistent: status ready but missing requirements exist."
}
