param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [decimal]$CandidateHalfWidthPct = 0,
    [int]$ChildTimeoutSeconds = 1200,
    [string]$SplitHandoffLog = "",
    [string]$OperatorAuthorizationRequestLog = "",
    [string]$PostEnvPlanLog = "",
    [switch]$RequireCompletePacketReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

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
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Add-UniqueValues {
    param([System.Collections.Generic.List[string]]$List, $Values)
    foreach ($value in @($Values)) {
        Add-Unique -List $List -Value ([string]$value)
    }
}

function Get-StringArray {
    param($Values)
    $list = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $Values) { return @() }
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        if ($value -is [pscustomobject] -and @($value.PSObject.Properties).Count -eq 0) { continue }
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) { $list.Add($text) }
    }
    return @($list)
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

function Get-PropertyOrNull {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Format-DecimalInvariant {
    param([decimal]$Value)
    return $Value.ToString([System.Globalization.CultureInfo]::InvariantCulture)
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptPath, [string[]]$Arguments)

    $scriptName = Split-Path -Leaf $ScriptPath
    Write-Host "[grid-open-complete-operator-packet] child_start script=$scriptName timeoutSeconds=$ChildTimeoutSeconds"
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
        } -ArgumentList @($script:PowerShell.Source, $ScriptPath, (Get-Location).Path, (, @($Arguments)))

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
                Write-Host "[grid-open-complete-operator-packet] child_heartbeat script=$scriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[grid-open-complete-operator-packet] child_complete script=$scriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

    [pscustomobject]@{
        ExitCode = $exitCode
        Text = $output
        Source = $scriptName
    }
}

function Read-EvidenceLog {
    param([string]$Path, [string]$SourceName)
    $resolved = Resolve-RepoPath $Path
    if (-not (Test-Path -LiteralPath $resolved)) { throw "$SourceName log not found: $Path" }
    [pscustomobject]@{
        ExitCode = 0
        Text = (Get-Content -Raw -LiteralPath $resolved)
        Source = $resolved
    }
}

$logInputs = @($SplitHandoffLog, $OperatorAuthorizationRequestLog, $PostEnvPlanLog)
$providedLogCount = @($logInputs | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
$usingReplayLogs = ($providedLogCount -eq 3)
if ($providedLogCount -ne 0 -and -not $usingReplayLogs) {
    throw "SplitHandoffLog, OperatorAuthorizationRequestLog, and PostEnvPlanLog must be provided together."
}

if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = $null
if (-not $usingReplayLogs) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open complete operator packet." }
    $script:PowerShell = $powerShell
}

$candidateArgs = @(
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", (Format-DecimalInvariant $PerLevelUsdt),
    "-StopOutPct", (Format-DecimalInvariant $StopOutPct),
    "-CandidateHalfWidthPct", (Format-DecimalInvariant $CandidateHalfWidthPct)
)

if ($usingReplayLogs) {
    $splitEvidence = Read-EvidenceLog -Path $SplitHandoffLog -SourceName "SplitHandoffLog"
    $requestEvidence = Read-EvidenceLog -Path $OperatorAuthorizationRequestLog -SourceName "OperatorAuthorizationRequestLog"
    $planEvidence = Read-EvidenceLog -Path $PostEnvPlanLog -SourceName "PostEnvPlanLog"
} else {
    $splitScript = Join-Path $PSScriptRoot "prepare_grid_split_acceptance_deploy_handoff_ssh.ps1"
    $requestScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"
    $planScript = Join-Path $PSScriptRoot "prepare_grid_post_env_verification_plan_ssh.ps1"
    foreach ($scriptPath in @($splitScript, $requestScript, $planScript)) {
        if (-not (Test-Path -LiteralPath $scriptPath)) { throw "Missing child script: $scriptPath" }
    }

    $sshArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile)
    $splitEvidence = Invoke-ReadOnlyScript -ScriptPath $splitScript -Arguments @(
        $sshArgs +
        @("-AgoraMarketApiToolsDir", $AgoraMarketApiToolsDir, "-ChildTimeoutSeconds", "$ChildTimeoutSeconds") +
        $candidateArgs
    )
    $requestEvidence = Invoke-ReadOnlyScript -ScriptPath $requestScript -Arguments @($sshArgs + $candidateArgs)
    $planEvidence = Invoke-ReadOnlyScript -ScriptPath $planScript -Arguments @($sshArgs + $candidateArgs)
}

$splitPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $splitEvidence.Text -Prefix "grid_split_acceptance_deploy_handoff_packet=")
$requestPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $requestEvidence.Text -Prefix "grid_open_operator_authorization_request_packet=")
$planPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $planEvidence.Text -Prefix "grid_post_env_verification_plan_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
if ($splitEvidence.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "split/currentness deploy handoff child completed" }
if ($requestEvidence.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "operator authorization request child completed" }
if ($planEvidence.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "post-env verification plan child completed" }
if ($null -eq $splitPacket) { Add-Unique -List $missingEvidence -Value "grid_split_acceptance_deploy_handoff_packet valid JSON" }
if ($null -eq $requestPacket) { Add-Unique -List $missingEvidence -Value "grid_open_operator_authorization_request_packet valid JSON" }
if ($null -eq $planPacket) { Add-Unique -List $missingEvidence -Value "grid_post_env_verification_plan_packet valid JSON" }

$splitReady = ($null -ne $splitPacket -and [string]$splitPacket.status -eq "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION")
$requestReady = ($null -ne $requestPacket -and [bool]$requestPacket.authorizationRequestReady)
$planReady = ($null -ne $planPacket -and [bool]$planPacket.postEnvVerificationPlanReady)

if (-not $splitReady) { Add-Unique -List $missingEvidence -Value "split/currentness handoff ready for separate deploy authorization" }
if (-not $requestReady) { Add-Unique -List $missingEvidence -Value "operator authorization request ready" }
if (-not $planReady) { Add-Unique -List $missingEvidence -Value "post-env verification plan ready" }

$splitMissing = if ($null -ne $splitPacket) { Get-StringArray $splitPacket.missingRequirements } else { @() }
$requestMissing = if ($null -ne $requestPacket) { Get-StringArray $requestPacket.missingEvidence } else { @() }
$planMissing = if ($null -ne $planPacket) { Get-StringArray $planPacket.missingEvidence } else { @() }
Add-UniqueValues -List $missingEvidence -Values $splitMissing
Add-UniqueValues -List $missingEvidence -Values $requestMissing
Add-UniqueValues -List $missingEvidence -Values $planMissing

$requestBlockers = if ($null -ne $requestPacket) { Get-StringArray $requestPacket.requestBlockers } else { @() }
$planBlockers = if ($null -ne $planPacket) { Get-StringArray $planPacket.planBlockers } else { @() }
$rankedBlockers = if ($null -ne $splitPacket) { @($splitPacket.gridOpenRankedBlockers) } else { @() }
$rankedBlockerNames = [System.Collections.Generic.List[string]]::new()
foreach ($blockerRow in @($rankedBlockers)) {
    $blocker = Get-PropertyOrNull $blockerRow "blocker"
    Add-Unique -List $rankedBlockerNames -Value ([string]$blocker)
}
$remainingExecutionBlockers = [System.Collections.Generic.List[string]]::new()
Add-UniqueValues -List $remainingExecutionBlockers -Values $rankedBlockerNames
if ($null -ne $requestPacket) { Add-UniqueValues -List $remainingExecutionBlockers -Values $requestPacket.remainingExecutionBlockers }
Add-UniqueValues -List $remainingExecutionBlockers -Values $requestBlockers
Add-UniqueValues -List $remainingExecutionBlockers -Values $planBlockers

$authorizationLines = if ($null -ne $requestPacket) { Get-StringArray $requestPacket.authorizationRequestLines } else { @() }
$postDeployCommands = if ($null -ne $splitPacket) { Get-StringArray $splitPacket.requiredPostDeployReadOnlyVerification } else { @() }
$postEnvCommands = if ($null -ne $planPacket) { Get-StringArray $planPacket.requiredPostEnvCommands } else { @() }
$coveredCreateReviewBlockers = [System.Collections.Generic.List[string]]::new()
$uncoveredCreateReviewBlockers = [System.Collections.Generic.List[string]]::new()
if ($null -ne $requestPacket) {
    Add-UniqueValues -List $coveredCreateReviewBlockers -Values (Get-PropertyOrNull $requestPacket "coveredCreateReviewBlockers")
    Add-UniqueValues -List $uncoveredCreateReviewBlockers -Values (Get-PropertyOrNull $requestPacket "uncoveredCreateReviewBlockers")
}
$createInputs = if ($null -ne $requestPacket) { Get-PropertyOrNull $requestPacket "reviewedCreateGridInputs" } else { $null }
$candidateParams = if ($null -ne $splitPacket) { Get-PropertyOrNull $splitPacket "reviewedGridCandidateParameters" } else { $null }
if ($authorizationLines.Count -eq 0) { Add-Unique -List $missingEvidence -Value "operator authorization request lines" }
if ($postDeployCommands.Count -eq 0) { Add-Unique -List $missingEvidence -Value "post-deploy read-only verification commands" }
if ($postEnvCommands.Count -eq 0) { Add-Unique -List $missingEvidence -Value "post-env read-only verification commands" }
if ($null -eq $createInputs) { Add-Unique -List $missingEvidence -Value "reviewed createGrid inputs" }

$completePacketReady = (
    $missingEvidence.Count -eq 0 -and
    $splitReady -and
    $requestReady -and
    $planReady
)
$status = if ($completePacketReady) {
    "READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION"
} else {
    "BLOCKED_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION"
}
$decision = if ($completePacketReady) {
    "AWAIT_SEPARATE_OPERATOR_AUTHORIZATIONS_AND_DEPLOY_CURRENTNESS"
} else {
    "REFRESH_GRID_OPEN_COMPLETE_OPERATOR_PACKET_EVIDENCE"
}

$originCommit = if ($null -ne $splitPacket) { Get-PropertyOrNull $splitPacket "originMainCommitFromMetadata" } else { $null }
$serverCommit = if ($null -ne $splitPacket) { Get-PropertyOrNull $splitPacket "serverWorktreeCommit" } else { $null }
$currentnessLine = if (-not [string]::IsNullOrWhiteSpace([string]$originCommit)) {
    "I authorize a separate deploy/restart of current origin/main $originCommit for $Symbol split/currentness only, followed by read-only split acceptance and grid readiness verification; no production env diff or createGrid is included unless separately named."
} else {
    "I authorize a separate deploy/restart of current origin/main for $Symbol split/currentness only, followed by read-only split acceptance and grid readiness verification; no production env diff or createGrid is included unless separately named."
}

$operatorSequence = @(
    "1. split/currentness deploy authorization for current origin/main only",
    "2. trend-regime override or fresh trend clearance",
    "3. capital-cap override if candidateCapitalUsdt remains above effectiveReviewCapitalCapUsdt",
    "4. production env diff authorization for OKX/grid flags while scheduler/recovery/Earn remain disabled",
    "5. deploy/restart after the separately authorized env diff",
    "6. post-env read-only verification with split acceptance, refreshed grid packets, and runtime-log smoke",
    "7. separate createGrid authorization using freshly reviewed createGrid inputs"
)

$packet = [ordered]@{
    packetType = "GRID_OPEN_COMPLETE_OPERATOR_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    completeOperatorPacketReady = $completePacketReady
    sourcePackets = [ordered]@{
        splitCurrentnessHandoff = [ordered]@{
            source = $splitEvidence.Source
            exitCode = $splitEvidence.ExitCode
            status = if ($null -ne $splitPacket) { $splitPacket.status } else { "UNKNOWN" }
            ready = $splitReady
        }
        operatorAuthorizationRequest = [ordered]@{
            source = $requestEvidence.Source
            exitCode = $requestEvidence.ExitCode
            status = if ($null -ne $requestPacket) { $requestPacket.status } else { "UNKNOWN" }
            ready = $requestReady
        }
        postEnvVerificationPlan = [ordered]@{
            source = $planEvidence.Source
            exitCode = $planEvidence.ExitCode
            status = if ($null -ne $planPacket) { $planPacket.status } else { "UNKNOWN" }
            ready = $planReady
        }
    }
    currentness = [ordered]@{
        serverWorktreeCommit = $serverCommit
        originMainCommit = $originCommit
        originDeltaStatus = if ($null -ne $splitPacket) { $splitPacket.originDeltaStatus } else { "UNKNOWN" }
        deploymentMetadataStatus = if ($null -ne $splitPacket) { $splitPacket.deploymentMetadataStatus } else { "UNKNOWN" }
        originRuntimeDeltaFiles = if ($null -ne $splitPacket) { $splitPacket.originRuntimeDeltaFiles } else { "UNKNOWN" }
    }
    readiness = [ordered]@{
        gridOpenableNow = if ($null -ne $splitPacket) { [bool]$splitPacket.gridOpenableNow } else { $false }
        scorePct = if ($null -ne $splitPacket) { $splitPacket.gridOpenReadinessScorePct } else { "" }
        passedGates = if ($null -ne $splitPacket) { $splitPacket.gridOpenReadinessPassedGates } else { "" }
        rankedBlockers = $rankedBlockers
        expectedPostDeployNextBlockers = if ($null -ne $splitPacket) { @($splitPacket.expectedPostDeployNextBlockers) } else { @() }
    }
    reviewedGridCandidateParameters = $candidateParams
    reviewedCreateGridInputs = $createInputs
    capitalOverrideRequest = if ($null -ne $requestPacket) { Get-PropertyOrNull $requestPacket "capitalOverrideRequest" } else { $null }
    proposedSeparateEnvDiff = if ($null -ne $requestPacket) { @($requestPacket.proposedSeparateEnvDiff) } else { @() }
    currentnessAuthorizationLine = $currentnessLine
    operatorAuthorizationLines = @($authorizationLines)
    requiredOperatorAuthorizationSequence = $operatorSequence
    remainingExecutionBlockers = @($remainingExecutionBlockers)
    coveredCreateReviewBlockers = $coveredCreateReviewBlockers
    uncoveredCreateReviewBlockers = $uncoveredCreateReviewBlockers
    requiredPostDeployReadOnlyVerification = @($postDeployCommands)
    requiredPostEnvCommands = @($postEnvCommands)
    missingEvidence = @($missingEvidence)
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    notAuthorization = "read-only grid open complete operator packet only; does not approve trend/capital/env/deploy/createGrid, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

$json = $packet | ConvertTo-Json -Depth 80 -Compress
Write-Host "[grid-open-complete-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; aggregates split/currentness handoff, operator authorization request, and post-env verification plan only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, deploy, restart, or nginx state changed."
Write-Host ("grid_open_complete_operator_packet=" + $json)
Write-Host ("grid_open_complete_operator_packet_status=" + $status)
Write-Host ("grid_open_complete_operator_packet_decision=" + $decision)
Write-Host ("grid_open_complete_operator_packet_ready=" + $completePacketReady.ToString().ToLowerInvariant())
Write-Host ("grid_open_complete_operator_packet_currentness_authorization_line=" + $currentnessLine)
Write-Host ("grid_open_complete_operator_packet_authorization_lines=" + (ConvertTo-Json -Compress @($authorizationLines)))
Write-Host ("grid_open_complete_operator_packet_remaining_execution_blockers=" + (ConvertTo-Json -Compress @($remainingExecutionBlockers)))
Write-Host ("grid_open_complete_operator_packet_missing_evidence=" + (($missingEvidence.ToArray()) -join "; "))
Write-Host ("grid_open_complete_operator_packet_post_deploy_commands=" + (ConvertTo-Json -Compress @($postDeployCommands)))
Write-Host ("grid_open_complete_operator_packet_post_env_commands=" + (ConvertTo-Json -Compress @($postEnvCommands)))
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("notAuthorization=" + $packet.notAuthorization)

if ($RequireCompletePacketReady -and -not $completePacketReady) {
    throw "Grid open complete operator packet is not ready: $($missingEvidence.ToArray() -join '; ')"
}
