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
    [int]$ChildTimeoutSeconds = 600,
    [int]$ChildHeartbeatSeconds = 30,
    [switch]$RequireBoardReady
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
        throw "$Name contains unsupported characters for read-only smoke arguments."
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

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Add-Blocker {
    param(
        [System.Collections.Generic.List[object]]$List,
        [int]$Rank,
        [string]$Family,
        [string]$Priority,
        [string]$Blocker,
        [string]$Evidence,
        [string]$Action,
        [string]$Authorization
    )

    if ([string]::IsNullOrWhiteSpace($Blocker)) { return }
    $existing = @($List | Where-Object { $_.blocker -eq $Blocker } | Select-Object -First 1)
    if ($existing.Count -gt 0) { return }
    $List.Add([pscustomobject]@{
        rank = $Rank
        family = $Family
        priority = $Priority
        blocker = $Blocker
        evidence = $Evidence
        nextAction = $Action
        authorizationRequired = $Authorization
    })
}

function Get-DecimalOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    try {
        return [decimal]$Value
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

function Get-FirstPresentValue {
    param([object[]]$Values)
    foreach ($value in $Values) {
        if ($null -eq $value) { continue }
        if ($value -is [string] -and [string]::IsNullOrWhiteSpace($value)) { continue }
        if ($value -is [System.Array] -and $value.Count -eq 0) { continue }
        return $value
    }
    return $null
}

function Invoke-ChildScript {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string[]]$ChildArgs,
        [int]$TimeoutSeconds,
        [int]$HeartbeatSeconds
    )

    Write-Host "[grid-open-blocker-priority-board] child_start script=$Name timeoutSeconds=$TimeoutSeconds"
    $childArgsJson = ConvertTo-Json -Compress @($ChildArgs)
    $job = Start-Job -ScriptBlock {
        param(
            [string]$PowerShellPath,
            [string]$TargetScript,
            [string]$TargetArgsJson
        )

        $ErrorActionPreference = "Continue"
        $TargetArgs = @($TargetArgsJson | ConvertFrom-Json | ForEach-Object { [string]$_ })
        $childOutput = & $PowerShellPath -NoProfile -ExecutionPolicy Bypass -File $TargetScript @TargetArgs 2>&1
        [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output = @($childOutput | ForEach-Object { [string]$_ })
        }
    } -ArgumentList @($powerShell.Source, $ScriptPath, $childArgsJson)

    $elapsedSeconds = 0
    $timedOut = $false
    try {
        while ($job.State -eq "Running") {
            $remainingSeconds = $TimeoutSeconds - $elapsedSeconds
            if ($remainingSeconds -le 0) {
                $timedOut = $true
                break
            }

            $sleepSeconds = [Math]::Min($HeartbeatSeconds, $remainingSeconds)
            Start-Sleep -Seconds $sleepSeconds
            $elapsedSeconds += $sleepSeconds
            if ($job.State -eq "Running") {
                Write-Host "[grid-open-blocker-priority-board] child_heartbeat script=$Name elapsedSeconds=$elapsedSeconds"
            }
        }

        if ($timedOut -or $job.State -eq "Running") {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
            Write-Host "[grid-open-blocker-priority-board] child_complete script=$Name exitCode=124 timedOut=true elapsedSeconds=$elapsedSeconds"
            return [pscustomobject]@{
                ExitCode = 124
                Output = @("child_timeout script=$Name timeoutSeconds=$TimeoutSeconds")
                TimedOut = $true
            }
        }

        $result = Receive-Job -Job $job -ErrorAction Continue
        $childResult = @($result | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties["ExitCode"] } | Select-Object -Last 1)
        if ($childResult.Count -eq 0) {
            Write-Host "[grid-open-blocker-priority-board] child_complete script=$Name exitCode=1 timedOut=false elapsedSeconds=$elapsedSeconds"
            return [pscustomobject]@{
                ExitCode = 1
                Output = @($result | ForEach-Object { [string]$_ })
                TimedOut = $false
            }
        }

        $exitCode = [int]$childResult[0].ExitCode
        $output = @($childResult[0].Output | ForEach-Object { [string]$_ })
        Write-Host "[grid-open-blocker-priority-board] child_complete script=$Name exitCode=$exitCode timedOut=false elapsedSeconds=$elapsedSeconds"
        return [pscustomobject]@{
            ExitCode = $exitCode
            Output = $output
            TimedOut = $false
        }
    } finally {
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30." }
if ($ChildTimeoutSeconds -lt 30 -or $ChildTimeoutSeconds -gt 1800) { throw "ChildTimeoutSeconds must be between 30 and 1800." }
if ($ChildHeartbeatSeconds -lt 5 -or $ChildHeartbeatSeconds -gt 300) { throw "ChildHeartbeatSeconds must be between 5 and 300." }
if ($ChildHeartbeatSeconds -gt $ChildTimeoutSeconds) { throw "ChildHeartbeatSeconds must be less than or equal to ChildTimeoutSeconds." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open blocker priority board." }

$bundleScript = Join-Path $PSScriptRoot "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1"
if (-not (Test-Path -LiteralPath $bundleScript)) { throw "Missing grid post-env read-only verification bundle script: $bundleScript" }
$preEnvRequestScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"
if (-not (Test-Path -LiteralPath $preEnvRequestScript)) { throw "Missing grid open operator authorization request script: $preEnvRequestScript" }
$originDeltaScript = Join-Path $PSScriptRoot "smoke_live_origin_delta_local.ps1"
if (-not (Test-Path -LiteralPath $originDeltaScript)) { throw "Missing origin delta classifier script: $originDeltaScript" }

$bundleArgs = @(
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
    "-StopOutPct", "$StopOutPct",
    "-CandidateHalfWidthPct", "$CandidateHalfWidthPct"
)
$preEnvRequestArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct",
    "-CandidateHalfWidthPct", "$CandidateHalfWidthPct"
)
$originDeltaArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $preEnvRequestResult = Invoke-ChildScript -Name "prepare_grid_open_operator_authorization_request_ssh.ps1" -ScriptPath $preEnvRequestScript -ChildArgs $preEnvRequestArgs -TimeoutSeconds $ChildTimeoutSeconds -HeartbeatSeconds $ChildHeartbeatSeconds
    $preEnvRequestOutput = $preEnvRequestResult.Output
    $preEnvRequestExitCode = $preEnvRequestResult.ExitCode
    $bundleResult = Invoke-ChildScript -Name "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1" -ScriptPath $bundleScript -ChildArgs $bundleArgs -TimeoutSeconds $ChildTimeoutSeconds -HeartbeatSeconds $ChildHeartbeatSeconds
    $bundleOutput = $bundleResult.Output
    $bundleExitCode = $bundleResult.ExitCode
    $originDeltaResult = Invoke-ChildScript -Name "smoke_live_origin_delta_local.ps1" -ScriptPath $originDeltaScript -ChildArgs $originDeltaArgs -TimeoutSeconds $ChildTimeoutSeconds -HeartbeatSeconds $ChildHeartbeatSeconds
    $originDeltaOutput = $originDeltaResult.Output
    $originDeltaExitCode = $originDeltaResult.ExitCode
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$preEnvRequestText = ($preEnvRequestOutput | Out-String -Width 8192)
$preEnvRequestPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $preEnvRequestText -Prefix "grid_open_operator_authorization_request_packet=")
$bundleText = ($bundleOutput | Out-String -Width 8192)
$bundlePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $bundleText -Prefix "grid_post_env_read_only_verification_packet=")
$originDeltaText = ($originDeltaOutput | Out-String -Width 8192)
$originDeltaStatus = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_delta_status="
$originRuntimeDeltaFiles = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_runtime_delta_files="
$deploymentMetadataStatus = Get-LastPrefixedValue -Text $originDeltaText -Prefix "deployment_metadata_status="
$originMetadataStatus = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_metadata_status="
$serverWorktreeCommit = Get-LastPrefixedValue -Text $originDeltaText -Prefix "server_worktree_commit="
$originMainCommit = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_main_commit="
$originRuntimeDeltaFileCount = -1
if (-not [int]::TryParse([string]$originRuntimeDeltaFiles, [ref]$originRuntimeDeltaFileCount)) {
    $originRuntimeDeltaFileCount = -1
}

$missingEvidence = [System.Collections.Generic.List[string]]::new()
if ($preEnvRequestExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "pre-env grid open operator authorization request completed" }
if ($null -eq $preEnvRequestPacket) { Add-Unique -List $missingEvidence -Value "pre-env grid_open_operator_authorization_request_packet valid JSON" }
if ($bundleExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid post-env read-only verification bundle completed" }
if ($null -eq $bundlePacket) { Add-Unique -List $missingEvidence -Value "grid_post_env_read_only_verification_packet valid JSON" }
if ($originDeltaExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "origin delta metadata classifier completed" }
if ([string]::IsNullOrWhiteSpace($originDeltaStatus)) { Add-Unique -List $missingEvidence -Value "origin_delta_status evidence" }

$blockers = [System.Collections.Generic.List[object]]::new()
$verificationBlockers = if ($null -ne $bundlePacket) { @($bundlePacket.verificationBlockers) } else { @() }
$envReadiness = if ($null -ne $bundlePacket) { $bundlePacket.envReadiness } else { $null }
$requestPacket = if ($null -ne $bundlePacket) { Get-PropertyOrNull $bundlePacket "sourceAuthorizationRequestPacketSummary" } else { $null }
$planPacket = if ($null -ne $bundlePacket) { Get-PropertyOrNull $bundlePacket "sourcePlanPacketSummary" } else { $null }
$createPreflight = if ($null -ne $bundlePacket) { Get-PropertyOrNull $bundlePacket "sourceCreatePreflightPacketSummary" } else { $null }
$bundleFromRequest = Get-PropertyOrNull $requestPacket "sourceAuthorizationBundlePacketSummary"
$capitalPacket = Get-PropertyOrNull $bundleFromRequest "sourceCapitalOverridePacketSummary"
$createPreflightFromCapital = Get-PropertyOrNull $capitalPacket "sourceCreateAuthorizationPreflightPacketSummary"
$bundleCreateInputs = if ($null -ne $bundlePacket) { Get-PropertyOrNull $bundlePacket "refreshedCreateGridInputsMustMatch" } else { $null }
$planCreateInputs = if ($null -ne $planPacket) { Get-PropertyOrNull $planPacket "refreshedCreateGridInputsMustMatch" } else { $null }
$createInputs = Get-FirstPresentValue -Values @(
    $bundleCreateInputs,
    (Get-PropertyOrNull $requestPacket "reviewedCreateGridInputs"),
    (Get-PropertyOrNull $bundleFromRequest "reviewedCreateGridInputs"),
    (Get-PropertyOrNull $capitalPacket "reviewedCreateGridInputs"),
    (Get-PropertyOrNull $createPreflight "reviewedCreateGridInputs"),
    (Get-PropertyOrNull $createPreflightFromCapital "reviewedCreateGridInputs"),
    $planCreateInputs
)
$createPreflight = Get-FirstPresentValue @($createPreflight, $createPreflightFromCapital)
$splitSummary = if ($null -ne $bundlePacket) { @($bundlePacket.splitAcceptanceFailureSummary) } else { @() }

$splitOk = if ($null -ne $bundlePacket) { [bool]$bundlePacket.splitAcceptanceOk } else { $false }
$postEnvReady = if ($null -ne $bundlePacket) { [bool]$bundlePacket.postEnvVerificationReady } else { $false }
$okxEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.tradingOkxEnabled } else { "UNKNOWN" }
$gridEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.tradingGridEnabled } else { "UNKNOWN" }
$schedulerEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.gridAutoRebalanceSchedulerEnabled } else { "UNKNOWN" }
$recoveryEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.gridRecoveryEnabled } else { "UNKNOWN" }
$earnEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.okxEarnTopupEnabled } else { "UNKNOWN" }
$eventRiskGate = if ($null -ne $envReadiness) { [string]$envReadiness.eventRiskGate } else { "UNKNOWN" }
$replayScore = if ($null -ne $createInputs) { Get-DecimalOrNull $createInputs.replayScore } else { $null }
$candidateCapital = if ($null -ne $createInputs) { Get-DecimalOrNull $createInputs.candidateCapitalUsdt } else { $null }
$capitalCap = if ($null -ne $createPreflight -and $null -ne $createPreflight.capitalCapCheck) { Get-DecimalOrNull $createPreflight.capitalCapCheck.effectiveReviewCapitalCapUsdt } else { $null }
$capitalStatus = if ($null -ne $createPreflight -and $null -ne $createPreflight.capitalCapCheck) { [string]$createPreflight.capitalCapCheck.status } else { "UNKNOWN" }
$postEnvBundleReady = if ($null -ne $requestPacket) { [bool]$requestPacket.sourceAuthorizationBundleReady } else { $false }
$postEnvRequestReady = if ($null -ne $requestPacket) { [bool]$requestPacket.authorizationRequestReady } else { $false }
$preEnvBundleReady = if ($null -ne $preEnvRequestPacket) { [bool]$preEnvRequestPacket.sourceAuthorizationBundleReady } else { $false }
$preEnvRequestReady = if ($null -ne $preEnvRequestPacket) { [bool]$preEnvRequestPacket.authorizationRequestReady } else { $false }
$envDiffAppliedForPhase = (
    $okxEnabled -eq "true" -and
    $gridEnabled -eq "true" -and
    $schedulerEnabled -eq "false" -and
    $recoveryEnabled -eq "false" -and
    $earnEnabled -eq "false"
)
$authorizationReadinessPhase = if ($envDiffAppliedForPhase) {
    "POST_ENV"
} else {
    "PRE_ENV"
}
$bundleReady = if ($envDiffAppliedForPhase) { $postEnvBundleReady } else { $preEnvBundleReady }
$requestReady = if ($envDiffAppliedForPhase) { $postEnvRequestReady } else { $preEnvRequestReady }
$phaseRequestPacket = if ($envDiffAppliedForPhase) { $requestPacket } else { $preEnvRequestPacket }
$trendGate = if ($null -ne $phaseRequestPacket) { [string](Get-PropertyOrNull $phaseRequestPacket "trendGate") } else { "UNKNOWN" }
if ([string]::IsNullOrWhiteSpace($trendGate)) { $trendGate = "UNKNOWN" }
$trendGateClearanceAcceptedRaw = if ($null -ne $phaseRequestPacket) { Get-PropertyOrNull $phaseRequestPacket "trendGateClearanceAccepted" } else { $null }
$trendGateClearanceAccepted = ([string]$trendGateClearanceAcceptedRaw -eq "true")
$phaseExecutionBlockersRaw = if ($null -ne $phaseRequestPacket) { Get-PropertyOrNull $phaseRequestPacket "remainingExecutionBlockers" } else { $null }
$phaseExecutionBlockers = if ($null -eq $phaseExecutionBlockersRaw) { @() } else { @($phaseExecutionBlockersRaw) }
$trendOverrideRequired = (
    -not $trendGateClearanceAccepted -and
    (
        $trendGate -like "BLOCKED*" -or
        $phaseExecutionBlockers -contains "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE"
    )
)
$runtimeCurrentForGridOpen = (
    $originDeltaExitCode -eq 0 -and
    $originRuntimeDeltaFileCount -eq 0 -and
    $originDeltaStatus -in @("CURRENT_ORIGIN_MAIN", "DOCS_TOOLING_ONLY_DRIFT") -and
    $deploymentMetadataStatus -in @("CURRENT", "DOCS_TOOLING_ONLY_DRIFT")
)
$splitAcceptanceBlockedByToolingOnlyCurrentness = (-not $splitOk -and $runtimeCurrentForGridOpen)

if ($missingEvidence.Count -gt 0) {
    Add-Blocker -List $blockers -Rank 0 -Family "evidence-collection" -Priority "P0" -Blocker "GRID_PRIORITY_BOARD_EVIDENCE_INCOMPLETE" -Evidence (@($missingEvidence) -join " | ") -Action "Rerun this board with a larger ChildTimeoutSeconds value or run the timed-out child packet directly before ranking market or env blockers." -Authorization "none until priority board evidence is complete"
}
if (-not $splitOk -and -not $splitAcceptanceBlockedByToolingOnlyCurrentness) {
    Add-Blocker -List $blockers -Rank 1 -Family "deployment/split-acceptance" -Priority "P0" -Blocker "SPLIT_ACCEPTANCE_NOT_PASSING" -Evidence ($splitSummary -join " | ") -Action "Deploy/restart only after separate authorization, then rerun split acceptance and this board." -Authorization "separate deploy/restart authorization"
}
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") {
    Add-Blocker -List $blockers -Rank 2 -Family "event-risk" -Priority "P0" -Blocker "EVENT_RISK_NOT_R0" -Evidence "eventRiskGate=$eventRiskGate" -Action "Wait for fresh R0 event-risk evidence; do not override while this board is blocked." -Authorization "fresh R0 evidence or separate event-risk override review"
}
if ($trendOverrideRequired) {
    Add-Blocker -List $blockers -Rank 3 -Family "trend-regime" -Priority "P0" -Blocker "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE" -Evidence "trendGate=$trendGate; trendGateClearanceAccepted=$trendGateClearanceAccepted" -Action "Wait for fresh trend clearance or request a separate trend-regime override before env/createGrid review." -Authorization "separate trend-regime override authorization or fresh trend clearance evidence"
}
if ($okxEnabled -ne "true" -or $gridEnabled -ne "true") {
    Add-Blocker -List $blockers -Rank 4 -Family "production-env" -Priority "P0" -Blocker "GRID_ENV_DIFF_NOT_APPLIED" -Evidence "TRADING_OKX_ENABLED=$okxEnabled; TRADING_GRID_ENABLED=$gridEnabled" -Action "Apply only a separately authorized env diff, deploy/restart, then rerun post-env read-only verification." -Authorization "separate production env diff authorization"
}
if ($null -eq $replayScore -or $replayScore -lt 70) {
    Add-Blocker -List $blockers -Rank 5 -Family "candidate-quality" -Priority "P1" -Blocker "REPLAY_SCORE_BELOW_GRID_REVIEW_FLOOR" -Evidence "replayScore=$replayScore; required>=70" -Action "Refresh candidate plan when market regime changes; do not createGrid with this replay score." -Authorization "none until replay evidence recovers"
}
if ($capitalStatus -eq "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP" -or ($null -ne $candidateCapital -and $null -ne $capitalCap -and $candidateCapital -gt $capitalCap)) {
    Add-Blocker -List $blockers -Rank 6 -Family "capital" -Priority "P1" -Blocker "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP" -Evidence "candidateCapitalUsdt=$candidateCapital; effectiveReviewCapitalCapUsdt=$capitalCap" -Action "Either lower candidate capital or request separate capital-cap override only after risk gates recover." -Authorization "separate capital-cap override authorization"
}
if ($schedulerEnabled -ne "false" -or $recoveryEnabled -ne "false" -or $earnEnabled -ne "false") {
    Add-Blocker -List $blockers -Rank 7 -Family "scheduler/recovery/earn" -Priority "P0" -Blocker "GRID_BACKGROUND_MUTATION_FLAGS_NOT_DISABLED" -Evidence "scheduler=$schedulerEnabled; recovery=$recoveryEnabled; earn=$earnEnabled" -Action "Keep scheduler/recovery/Earn disabled for initial grid-open review." -Authorization "separate scheduler/recovery/Earn authorization if ever needed"
}
if (-not $bundleReady -or -not $requestReady) {
    Add-Blocker -List $blockers -Rank 8 -Family "operator-authorization-chain" -Priority "P1" -Blocker "GRID_OPERATOR_AUTHORIZATION_CHAIN_NOT_READY" -Evidence "phase=$authorizationReadinessPhase; authorizationBundleReady=$bundleReady; authorizationRequestReady=$requestReady" -Action "Resolve upstream risk/env/capital blockers, then regenerate authorization bundle/request." -Authorization "none until upstream blockers clear"
}
foreach ($rawBlocker in $verificationBlockers) {
    $value = [string]$rawBlocker
    if ([string]::IsNullOrWhiteSpace($value)) { continue }
    if ($value -in @(
            "SPLIT_ACCEPTANCE_FAILED_OR_INCOMPLETE",
            "SPLIT_ACCEPTANCE_OK_MARKER_MISSING",
            "POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE",
            "POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE",
            "POST_ENV_EVENT_RISK_NOT_R0",
            "GRID_POST_ENV_VERIFICATION_PLAN_NOT_READY",
            "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY",
            "GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_READY"
        )) { continue }
    $known = @($blockers | Where-Object { $_.blocker -eq $value -or $_.evidence -like "*$value*" } | Select-Object -First 1)
    if ($known.Count -eq 0) {
        Add-Blocker -List $blockers -Rank 90 -Family "other" -Priority "P2" -Blocker $value -Evidence "reported by post-env verification bundle" -Action "Inspect source bundle packet for this blocker, refresh evidence after higher-priority blockers are resolved." -Authorization "depends on blocker family"
    }
}

$gateChecks = @(
    [pscustomobject]@{ gate = "splitAcceptance"; pass = $splitOk },
    [pscustomobject]@{ gate = "tradingOkxEnabled"; pass = ($okxEnabled -eq "true") },
    [pscustomobject]@{ gate = "tradingGridEnabled"; pass = ($gridEnabled -eq "true") },
    [pscustomobject]@{ gate = "gridAutoRebalanceSchedulerDisabled"; pass = ($schedulerEnabled -eq "false") },
    [pscustomobject]@{ gate = "gridRecoveryDisabled"; pass = ($recoveryEnabled -eq "false") },
    [pscustomobject]@{ gate = "okxEarnTopupDisabled"; pass = ($earnEnabled -eq "false") },
    [pscustomobject]@{ gate = "eventRiskR0"; pass = ($eventRiskGate -eq "CLEAR_EVENT_RISK_R0") },
    [pscustomobject]@{ gate = "replayScoreAtLeast70"; pass = ($null -ne $replayScore -and $replayScore -ge 70) },
    [pscustomobject]@{ gate = "capitalWithinCap"; pass = ($capitalStatus -eq "CAPITAL_WITHIN_EFFECTIVE_REVIEW_CAP") },
    [pscustomobject]@{ gate = "authorizationBundleReady"; pass = $bundleReady },
    [pscustomobject]@{ gate = "authorizationRequestReady"; pass = $requestReady },
    [pscustomobject]@{ gate = "postEnvVerificationReady"; pass = $postEnvReady }
)
$passedGateCount = @($gateChecks | Where-Object { $_.pass }).Count
$openReadinessScorePct = [math]::Round(($passedGateCount / [decimal]$gateChecks.Count) * 100, 2)
$gridOpenableNow = ($missingEvidence.Count -eq 0 -and $blockers.Count -eq 0 -and $postEnvReady)
$status = if ($missingEvidence.Count -eq 0) {
    "READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_GRID_OPEN_BLOCKER_PRIORITY_BOARD_NOT_MUTATION"
}
$decision = if ($gridOpenableNow) {
    "AWAIT_SEPARATE_CREATEGRID_AUTHORIZATION"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_OPEN_BLOCKER_PRIORITY_BOARD_EVIDENCE"
} elseif (@($blockers | Where-Object { $_.blocker -eq "SPLIT_ACCEPTANCE_NOT_PASSING" }).Count -gt 0) {
    "DEPLOY_CURRENT_MAIN_AND_RERUN_READ_ONLY_VERIFICATION_AFTER_SEPARATE_AUTHORIZATION"
} elseif (@($blockers | Where-Object { $_.blocker -eq "EVENT_RISK_NOT_R0" }).Count -gt 0) {
    "WAIT_EVENT_RISK_R0_BEFORE_ENV_OR_CREATEGRID_REVIEW"
} elseif (@($blockers | Where-Object { $_.blocker -eq "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE" }).Count -gt 0) {
    "WAIT_TREND_CLEARANCE_OR_PREPARE_SEPARATE_TREND_OVERRIDE"
} elseif (@($blockers | Where-Object { $_.blocker -eq "GRID_ENV_DIFF_NOT_APPLIED" }).Count -gt 0) {
    "PREPARE_SEPARATE_GRID_ENV_DIFF_AUTHORIZATION"
} elseif ($splitAcceptanceBlockedByToolingOnlyCurrentness) {
    "CONTINUE_GRID_OPEN_REVIEW_WITH_RUNTIME_CURRENT_TOOLING_DRIFT"
} else {
    "RESOLVE_TOP_GRID_OPEN_BLOCKER_AND_RERUN"
}

$rankedBlockers = @($blockers | Sort-Object -Property @{ Expression = "rank"; Ascending = $true }, @{ Expression = "blocker"; Ascending = $true })
$topBlocker = if ($rankedBlockers.Count -gt 0) { $rankedBlockers[0] } else { $null }
$nextAuthorizationRequired = if ($null -ne $topBlocker) { [string]$topBlocker.authorizationRequired } else { "separate createGrid authorization" }
$existingActiveGridActivationReview = if ($null -ne $preEnvRequestPacket) {
    Get-PropertyOrNull $preEnvRequestPacket "existingActiveGridActivationReview"
} else {
    $null
}
$existingActiveGridOrderPathActivationRisk = if ($null -ne $existingActiveGridActivationReview) {
    [bool](Get-PropertyOrNull $existingActiveGridActivationReview "orderPathWillBeActivatedByPendingOkxEnablement")
} else {
    $false
}
$operatorAuthorizationSequence = @(
    $(if ($runtimeCurrentForGridOpen) { "split runtime currentness accepted by zero runtime delta; server tooling sync remains a follow-up" } else { "separate split/currentness deploy authorization" }),
    $(if ($trendGateClearanceAccepted) { "fresh trend gate clearance accepted; separate trend override not required unless the gate becomes blocked" } else { "separate trend-regime override or fresh trend clearance" }),
    "separate capital-cap override if candidate capital remains above effective review cap",
    $(if ($existingActiveGridOrderPathActivationRisk) { "separate production env diff authorization explicitly naming existing ACTIVE grid OKX order-path activation" } else { "separate production env diff authorization for OKX/grid flags" }),
    "deploy/restart only after the separately authorized env diff",
    "post-env read-only verification with split acceptance, refreshed grid packets, and runtime-log smoke",
    "separate createGrid authorization naming refreshedCreateGridInputs only if an additional grid is still desired"
)
$compactAuthorizationBrief = [pscustomobject]@{
    nextAuthorizationRequired = $nextAuthorizationRequired
    existingActiveGridOrderPathActivationRisk = $existingActiveGridOrderPathActivationRisk
    remainingRankedBlockers = @($rankedBlockers | ForEach-Object { $_.blocker })
    operatorAuthorizationSequence = @($operatorAuthorizationSequence)
}
$board = [pscustomobject]@{
    packetType = "GRID_OPEN_BLOCKER_PRIORITY_BOARD"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceBundle = "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1"
    sourceBundleExitCode = $bundleExitCode
    sourceBundleStatus = if ($null -ne $bundlePacket) { [string]$bundlePacket.status } else { "UNKNOWN" }
    sourceOriginDelta = "smoke_live_origin_delta_local.ps1"
    sourceOriginDeltaExitCode = $originDeltaExitCode
    originDeltaStatus = $originDeltaStatus
    deploymentMetadataStatus = $deploymentMetadataStatus
    originMetadataStatus = $originMetadataStatus
    originRuntimeDeltaFiles = $originRuntimeDeltaFiles
    serverWorktreeCommit = $serverWorktreeCommit
    originMainCommit = $originMainCommit
    splitStrictAcceptanceOk = $splitOk
    splitRuntimeCurrentForGridOpen = $runtimeCurrentForGridOpen
    splitToolingOnlyCurrentnessFollowUp = $splitAcceptanceBlockedByToolingOnlyCurrentness
    sourcePreEnvAuthorizationRequest = "prepare_grid_open_operator_authorization_request_ssh.ps1"
    sourcePreEnvAuthorizationRequestExitCode = $preEnvRequestExitCode
    sourcePreEnvAuthorizationRequestStatus = if ($null -ne $preEnvRequestPacket) { [string]$preEnvRequestPacket.status } else { "UNKNOWN" }
    authorizationReadinessPhase = $authorizationReadinessPhase
    preEnvAuthorizationBundleReady = $preEnvBundleReady
    preEnvAuthorizationRequestReady = $preEnvRequestReady
    postEnvAuthorizationBundleReady = $postEnvBundleReady
    postEnvAuthorizationRequestReady = $postEnvRequestReady
    trendGate = $trendGate
    trendGateClearanceAccepted = $trendGateClearanceAccepted
    trendOverrideRequired = $trendOverrideRequired
    gridOpenableNow = $gridOpenableNow
    openReadinessScorePct = $openReadinessScorePct
    passedGateCount = $passedGateCount
    totalGateCount = $gateChecks.Count
    gateChecks = @($gateChecks)
    rankedBlockers = @($rankedBlockers)
    topBlocker = $topBlocker
    nextAuthorizationRequired = $nextAuthorizationRequired
    existingActiveGridActivationReview = $existingActiveGridActivationReview
    existingActiveGridOrderPathActivationRisk = $existingActiveGridOrderPathActivationRisk
    operatorAuthorizationSequence = @($operatorAuthorizationSequence)
    compactAuthorizationBrief = $compactAuthorizationBrief
    envReadiness = $envReadiness
    refreshedCreateGridInputs = $createInputs
    splitAcceptanceFailureSummary = @($splitSummary)
    missingEvidence = @($missingEvidence)
    requiredBeforeOpen = @(
        $(if ($runtimeCurrentForGridOpen) { "split runtime currentness accepted by zero runtime delta; server tooling sync remains a follow-up before relying on server-side scripts" } else { "current main deployed and split acceptance passes" }),
        "TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true only after separate authorization",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false",
        "event risk gate is CLEAR_EVENT_RISK_R0",
        "replayScore >= 70",
        "candidate capital within cap or separate capital override",
        "fresh authorization bundle/request ready",
        "separate createGrid authorization naming refreshedCreateGridInputs"
    )
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceBundlePacketSummary = $bundlePacket
    sourcePreEnvAuthorizationRequestPacketSummary = $preEnvRequestPacket
    notAuthorization = "read-only grid open blocker priority board only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
}

Write-Host "[grid-open-blocker-priority-board] read-only board"
Write-Host "scope=READ_ONLY; invokes grid pre-env authorization request and post-env read-only verification bundle only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_pre_env_authorization_request=prepare_grid_open_operator_authorization_request_ssh.ps1 exitCode=$preEnvRequestExitCode"
Write-Host "source_bundle=prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 exitCode=$bundleExitCode"
Write-Host "source_origin_delta=smoke_live_origin_delta_local.ps1 exitCode=$originDeltaExitCode"
Write-Host "grid_open_blocker_priority_board_status=$status"
Write-Host "grid_open_blocker_priority_board_decision=$decision"
Write-Host "origin_delta_status=$originDeltaStatus"
Write-Host "deployment_metadata_status=$deploymentMetadataStatus"
Write-Host "origin_runtime_delta_files=$originRuntimeDeltaFiles"
Write-Host "grid_split_runtime_current_for_grid_open=$($runtimeCurrentForGridOpen.ToString().ToLowerInvariant())"
Write-Host "grid_split_tooling_only_currentness_follow_up=$($splitAcceptanceBlockedByToolingOnlyCurrentness.ToString().ToLowerInvariant())"
Write-Host "grid_openable_now=$($gridOpenableNow.ToString().ToLowerInvariant())"
Write-Host "grid_open_readiness_score_pct=$openReadinessScorePct"
Write-Host "grid_open_readiness_passed_gates=$passedGateCount/$($gateChecks.Count)"
Write-Host "grid_authorization_readiness_phase=$authorizationReadinessPhase"
Write-Host "grid_pre_env_authorization_request_ready=$($preEnvRequestReady.ToString().ToLowerInvariant())"
Write-Host "grid_post_env_authorization_request_ready=$($postEnvRequestReady.ToString().ToLowerInvariant())"
Write-Host "grid_trend_gate=$trendGate"
Write-Host "grid_trend_gate_clearance_accepted=$($trendGateClearanceAccepted.ToString().ToLowerInvariant())"
Write-Host "grid_trend_override_required=$($trendOverrideRequired.ToString().ToLowerInvariant())"
Write-Host "grid_open_blocker_priority_next_authorization_required=$nextAuthorizationRequired"
Write-Host "grid_open_blocker_priority_existing_active_grid_order_path_activation_risk=$($existingActiveGridOrderPathActivationRisk.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_open_blocker_priority_top_blocker=" + (ConvertTo-Json -Compress -Depth 6 $board.topBlocker))
Write-Host ("grid_open_blocker_priority_ranked_blockers=" + (ConvertTo-Json -Compress -Depth 8 @($rankedBlockers)))
Write-Host ("grid_open_blocker_priority_authorization_sequence=" + (ConvertTo-Json -Compress -Depth 6 @($operatorAuthorizationSequence)))
Write-Host ("grid_open_blocker_priority_compact_authorization_brief=" + (ConvertTo-Json -Compress -Depth 8 $compactAuthorizationBrief))
Write-Host ("grid_open_blocker_priority_gate_checks=" + (ConvertTo-Json -Compress -Depth 6 @($gateChecks)))
Write-Host ("grid_open_blocker_priority_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_open_blocker_priority_board_packet=" + (ConvertTo-Json -Compress -Depth 18 $board))
Write-Host "notAuthorization=read-only grid open blocker priority board only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
Write-Host "[grid-open-blocker-priority-board] read-only check complete"

if ($RequireBoardReady -and $missingEvidence.Count -gt 0) {
    throw "Grid open blocker priority board is not ready: $status; missingEvidence=$(@($missingEvidence) -join '; ')"
}
