param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
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
        throw "$Name contains unsupported characters for signal/missed blocker decision brief arguments."
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
        throw "Unable to find powershell or pwsh for signal/missed blocker decision brief."
    }

    Write-Host "[signal-missed-blocker-decision-brief] child_start script=$ScriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[signal-missed-blocker-decision-brief] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
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
        Write-Host "[signal-missed-blocker-decision-brief] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotalSeconds"
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
    "-Symbol", $Symbol,
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
)

$entryFilter = Invoke-ReadOnlyScript -ScriptName "prepare_entry_filter_operator_review_packet_ssh.ps1" -Arguments $commonArgs
$noBuy = Invoke-ReadOnlyScript -ScriptName "prepare_no_buy_row_review_packet_ssh.ps1" -Arguments $commonArgs
$missed = Invoke-ReadOnlyScript -ScriptName "prepare_missed_opportunity_shadow_design_packet_ssh.ps1" -Arguments $commonArgs
$governance = Invoke-ReadOnlyScript -ScriptName "prepare_governance_relaxation_review_packet_ssh.ps1" -Arguments $commonArgs

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($result in @($entryFilter, $noBuy, $missed, $governance)) {
    if ($result.ExitCode -ne 0) {
        Add-UniqueString -List $missingRequirements -Value "$($result.ScriptName) completed"
    }
}

$entryStatus = Get-LastPrefixedValue -Text $entryFilter.Text -Prefix "entry_filter_operator_packet_status="
$entryPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $entryFilter.Text -Prefix "entry_filter_operator_review_packet=")
$entryMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $entryFilter.Text -Prefix "entry_filter_operator_packet_missing_requirements=")
$noBuyStatus = Get-LastPrefixedValue -Text $noBuy.Text -Prefix "no_buy_row_review_packet_status="
$noBuyPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $noBuy.Text -Prefix "no_buy_row_review_packet=")
$noBuyMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $noBuy.Text -Prefix "no_buy_row_review_packet_missing_requirements=")
$noBuyFamilyCounts = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $noBuy.Text -Prefix "no_buy_row_action_family_counts=")
$missedStatus = Get-LastPrefixedValue -Text $missed.Text -Prefix "missed_opportunity_shadow_design_packet_status="
$missedPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $missed.Text -Prefix "missed_opportunity_shadow_design_packet=")
$missedMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $missed.Text -Prefix "missed_opportunity_shadow_design_missing_requirements=")
$governanceStatus = Get-LastPrefixedValue -Text $governance.Text -Prefix "governance_relaxation_review_packet_status="
$governancePacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $governance.Text -Prefix "governance_relaxation_review_packet=")
$governanceMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $governance.Text -Prefix "governance_relaxation_missing_requirements=")

foreach ($item in @($entryMissing + $noBuyMissing + $missedMissing + $governanceMissing)) {
    Add-UniqueString -List $missingRequirements -Value ([string]$item)
}

$signalPolicyClear = if ($null -ne $entryPacket) { [string]$entryPacket.signalPolicyClear } else { "N/A" }
$governanceMode = if ($null -ne $entryPacket) { [string]$entryPacket.governanceMode } else { "N/A" }
$missedOpportunityStatus = if ($null -ne $entryPacket) { [string]$entryPacket.missedOpportunityStatus } else { "N/A" }
$suspiciousNoBuyCount = if ($null -ne $entryPacket) { [string]$entryPacket.suspiciousNoBuyCount } else { "N/A" }
$falseBlockRiskCount = if ($null -ne $entryPacket) { [string]$entryPacket.falseBlockRiskCount } else { "N/A" }
$highForwardReturnNoBuyCount = if ($null -ne $entryPacket) { [string]$entryPacket.highForwardReturnNoBuyCount } else { "N/A" }
$dataFreshnessCurrentStatus = if ($null -ne $entryPacket) { [string]$entryPacket.dataFreshnessCurrentStatus } else { "N/A" }
$candidateMissedRows = if ($null -ne $missedPacket -and $null -ne $missedPacket.candidateMissedOpportunityRows) { @($missedPacket.candidateMissedOpportunityRows).Count } else { 0 }
$waitRows = if ($null -ne $missedPacket -and $null -ne $missedPacket.waitForSignalConfirmationRows) { @($missedPacket.waitForSignalConfirmationRows).Count } else { 0 }
$hardSafetyRows = if ($null -ne $missedPacket -and $null -ne $missedPacket.hardSafetyRows) { @($missedPacket.hardSafetyRows).Count } else { 0 }
$relaxationCandidateCount = if ($null -ne $governancePacket -and $null -ne $governancePacket.relaxationCandidateCount) { [string]$governancePacket.relaxationCandidateCount } else { "0" }

$missedLaneStatus = if ($missedStatus -eq "READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE") {
    "MISSED_OPPORTUNITY_SHADOW_DESIGN_READY_NOT_LIVE"
} elseif ($candidateMissedRows -gt 0) {
    "BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED"
} else {
    "NO_MISSED_OPPORTUNITY_CANDIDATE_ROWS"
}
$governanceLaneStatus = if ($governanceStatus -eq "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE") {
    "GOVERNANCE_SHADOW_REVIEW_READY_NOT_LIVE"
} elseif ($relaxationCandidateCount -ne "0") {
    "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
} else {
    "NO_GOVERNANCE_RELAXATION_CANDIDATES"
}
$noBuyLaneStatus = if ($noBuyStatus -eq "READY_FOR_SHADOW_DESIGN_NOT_LIVE") {
    "NO_BUY_ROW_SHADOW_DESIGN_READY_NOT_LIVE"
} elseif ($noBuyStatus -eq "REVIEW_REQUIRED_NOT_EXPERIMENT") {
    "NO_BUY_ROW_REVIEW_REQUIRED_NOT_EXPERIMENT"
} else {
    "NO_BUY_ROW_EVIDENCE_NOT_READY"
}
$entryFilterLaneStatus = if ($entryStatus -eq "READY_FOR_OPERATOR_PACKET_NOT_LIVE") {
    "ENTRY_FILTER_OPERATOR_PACKET_READY_NOT_LIVE"
} elseif ($entryStatus -eq "REVIEW_REQUIRED_NOT_POLICY_CHANGE") {
    "ENTRY_FILTER_REVIEW_REQUIRED_NOT_POLICY_CHANGE"
} else {
    "ENTRY_FILTER_EVIDENCE_NOT_READY"
}

$briefStatus = if ($entryFilter.ExitCode -ne 0 -or $noBuy.ExitCode -ne 0 -or $missed.ExitCode -ne 0 -or $governance.ExitCode -ne 0 -or $entryStatus -eq "NO_EVIDENCE" -or $noBuyStatus -eq "NO_EVIDENCE") {
    "NO_EVIDENCE"
} elseif ($signalPolicyClear -ne "true" -or $missedOpportunityStatus -ne "PASS" -or $governanceMode -eq "INSUFFICIENT_DATA" -or $governanceMode -eq "TOO_STRICT" -or $governanceMode -eq "TOO_LOOSE" -or $dataFreshnessCurrentStatus -ne "CLEAN") {
    "BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW"
} elseif ($missedLaneStatus -eq "MISSED_OPPORTUNITY_SHADOW_DESIGN_READY_NOT_LIVE" -or $governanceLaneStatus -eq "GOVERNANCE_SHADOW_REVIEW_READY_NOT_LIVE") {
    "READY_FOR_SIGNAL_MISSED_OPERATOR_REVIEW_NOT_LIVE"
} else {
    "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
}

$nextAction = if ($briefStatus -eq "NO_EVIDENCE") {
    "Fix the child read-only packet failure before using this brief."
} elseif ($briefStatus -eq "BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW") {
    "Review signal policy, governance drift, missed-opportunity rows, no-buy families, and DataFreshness current sample before any shadow or tiny-live experiment design."
} elseif ($briefStatus -eq "READY_FOR_SIGNAL_MISSED_OPERATOR_REVIEW_NOT_LIVE") {
    "Attach this brief to a separate operator review packet; this is not live approval or policy relaxation."
} else {
    "Use the child packets for operator review only; keep all live and policy mutation disabled."
}

$decisionChecklist = @(
    "signalPolicyClear=true",
    "missedOpportunityStatus=PASS",
    "governanceMode is not TOO_STRICT, TOO_LOOSE, INSUFFICIENT_DATA, or N/A",
    "dataFreshnessCurrentStatus=CLEAN",
    "no-buy row action families reviewed",
    "missed-opportunity candidate rows do not include hard-safety rows",
    "governance relaxation candidates are shadow-only and not hard-gate bypasses",
    "separate explicit authorization before any live, tiny-live, scheduler, order, OCO, grid, fund, Earn, Telegram, exchange, production env, or policy mutation"
)

$brief = [pscustomobject]@{
    packetType = "SIGNAL_MISSED_BLOCKER_DECISION_BRIEF"
    status = $briefStatus
    symbol = $Symbol
    signalPolicyClear = $signalPolicyClear
    governanceMode = $governanceMode
    missedOpportunityStatus = $missedOpportunityStatus
    suspiciousNoBuyCount = $suspiciousNoBuyCount
    falseBlockRiskCount = $falseBlockRiskCount
    highForwardReturnNoBuyCount = $highForwardReturnNoBuyCount
    dataFreshnessCurrentStatus = $dataFreshnessCurrentStatus
    lanes = [pscustomobject]@{
        entryFilterOperator = [pscustomobject]@{ status = $entryFilterLaneStatus; sourceStatus = $entryStatus }
        noBuyRowReview = [pscustomobject]@{ status = $noBuyLaneStatus; sourceStatus = $noBuyStatus; rowActionFamilyCounts = @($noBuyFamilyCounts) }
        missedOpportunityShadowDesign = [pscustomobject]@{ status = $missedLaneStatus; sourceStatus = $missedStatus; candidateMissedOpportunityRowCount = $candidateMissedRows; waitForSignalConfirmationRowCount = $waitRows; hardSafetyRowCount = $hardSafetyRows }
        governanceRelaxationReview = [pscustomobject]@{ status = $governanceLaneStatus; sourceStatus = $governanceStatus; relaxationCandidateCount = $relaxationCandidateCount }
    }
    decisionChecklist = @($decisionChecklist)
    missingRequirementCount = @($missingRequirements).Count
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only signal/missed blocker decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, execute tiny-live orders, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[signal-missed-blocker-decision-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes entry-filter, no-buy row, missed-opportunity shadow design, and governance relaxation review packets only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_entry_filter_operator_review_packet_ssh.ps1 exitCode=$($entryFilter.ExitCode) status=$entryStatus"
Write-Host "source_packet=prepare_no_buy_row_review_packet_ssh.ps1 exitCode=$($noBuy.ExitCode) status=$noBuyStatus"
Write-Host "source_packet=prepare_missed_opportunity_shadow_design_packet_ssh.ps1 exitCode=$($missed.ExitCode) status=$missedStatus"
Write-Host "source_packet=prepare_governance_relaxation_review_packet_ssh.ps1 exitCode=$($governance.ExitCode) status=$governanceStatus"
Write-Host "signal_policy_clear=$signalPolicyClear"
Write-Host "governance_mode=$governanceMode"
Write-Host "missed_opportunity_status=$missedOpportunityStatus"
Write-Host "suspicious_no_buy_count=$suspiciousNoBuyCount"
Write-Host "false_block_risk_count=$falseBlockRiskCount"
Write-Host "high_forward_return_no_buy_count=$highForwardReturnNoBuyCount"
Write-Host "data_freshness_current_status=$dataFreshnessCurrentStatus"
Write-Host "entry_filter_operator_lane_status=$entryFilterLaneStatus"
Write-Host "no_buy_row_review_lane_status=$noBuyLaneStatus"
Write-Host "missed_opportunity_shadow_lane_status=$missedLaneStatus"
Write-Host "governance_relaxation_lane_status=$governanceLaneStatus"
Write-Host "candidate_missed_opportunity_row_count=$candidateMissedRows"
Write-Host "wait_for_signal_confirmation_row_count=$waitRows"
Write-Host "hard_safety_row_count=$hardSafetyRows"
Write-Host "governance_relaxation_candidate_count=$relaxationCandidateCount"
Write-Host ("signal_missed_blocker_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("signal_missed_blocker_decision_checklist=" + (ConvertTo-Json -Compress @($decisionChecklist)))
Write-Host ("signal_missed_blocker_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "signal_missed_blocker_decision_brief_status=$briefStatus"
Write-Host "signal_missed_blocker_decision_next_action=$nextAction"
Write-Host "notAuthorization=read-only signal/missed blocker decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, execute tiny-live orders, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[signal-missed-blocker-decision-brief] read-only check complete"

if ($RequireBrief -and $briefStatus -eq "READY_FOR_SIGNAL_MISSED_OPERATOR_REVIEW_NOT_LIVE" -and @($missingRequirements).Count -gt 0) {
    throw "Signal/missed blocker decision brief is inconsistent: status ready but missing requirements exist."
}
