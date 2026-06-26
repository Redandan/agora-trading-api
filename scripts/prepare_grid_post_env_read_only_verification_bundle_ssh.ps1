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
    [switch]$RequireVerificationReady
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

function Get-DiagnosticLines {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    @(
        $Text -split "`r?`n" |
            Where-Object { $_ -match "FAIL|Exception|does not match|runtime log smoke failed|server verification failed" } |
            Select-Object -First 12
    )
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptPath, [string[]]$Arguments)

    $scriptName = Split-Path -Leaf $ScriptPath
    Write-Host "[grid-post-env-read-only-verification-bundle] child_start script=$scriptName timeoutSeconds=$ChildTimeoutSeconds"
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
                Write-Host "[grid-post-env-read-only-verification-bundle] child_heartbeat script=$scriptName elapsedSeconds=$elapsedSeconds"
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
    Write-Host "[grid-post-env-read-only-verification-bundle] child_complete script=$scriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotal"

    [pscustomobject]@{
        ExitCode = $exitCode
        Text = $output
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$script:PowerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $script:PowerShell) { $script:PowerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $script:PowerShell) { throw "Unable to find powershell or pwsh for grid post-env read-only verification bundle." }

$planScript = Join-Path $PSScriptRoot "prepare_grid_post_env_verification_plan_ssh.ps1"
$splitScript = Join-Path $PSScriptRoot "verify_split_acceptance_ssh.ps1"
$decisionScript = Join-Path $PSScriptRoot "prepare_grid_open_decision_snapshot_ssh.ps1"
$trendScript = Join-Path $PSScriptRoot "prepare_grid_trend_override_review_packet_ssh.ps1"
$envDiffScript = Join-Path $PSScriptRoot "prepare_grid_env_diff_preflight_packet_ssh.ps1"
$createPreflightScript = Join-Path $PSScriptRoot "prepare_grid_create_authorization_preflight_packet_ssh.ps1"
$bundleScript = Join-Path $PSScriptRoot "prepare_grid_open_authorization_bundle_ssh.ps1"
$requestScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"

foreach ($scriptPath in @($planScript, $splitScript, $decisionScript, $trendScript, $envDiffScript, $createPreflightScript, $bundleScript, $requestScript)) {
    if (-not (Test-Path -LiteralPath $scriptPath)) { throw "Missing read-only grid post-env dependency script: $scriptPath" }
}

$commonArgs = @(
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
$postEnvArgs = @($commonArgs)
$postEnvArgs += "-AcceptAlreadyAppliedEnvDiff"

$splitArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-TradingAppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-AgoraMarketApiToolsDir", $AgoraMarketApiToolsDir
)

$planResult = Invoke-ReadOnlyScript -ScriptPath $planScript -Arguments $postEnvArgs
$splitResult = Invoke-ReadOnlyScript -ScriptPath $splitScript -Arguments $splitArgs
$decisionResult = Invoke-ReadOnlyScript -ScriptPath $decisionScript -Arguments $commonArgs
$trendResult = Invoke-ReadOnlyScript -ScriptPath $trendScript -Arguments $commonArgs
$envDiffResult = Invoke-ReadOnlyScript -ScriptPath $envDiffScript -Arguments $postEnvArgs
$createPreflightResult = Invoke-ReadOnlyScript -ScriptPath $createPreflightScript -Arguments $postEnvArgs
$bundleResult = Invoke-ReadOnlyScript -ScriptPath $bundleScript -Arguments $postEnvArgs
$requestResult = Invoke-ReadOnlyScript -ScriptPath $requestScript -Arguments $postEnvArgs

$planPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $planResult.Text -Prefix "grid_post_env_verification_plan_packet=")
$decisionPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $decisionResult.Text -Prefix "grid_open_decision_snapshot_packet=")
$trendPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $trendResult.Text -Prefix "grid_trend_override_review_packet=")
$envDiffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $envDiffResult.Text -Prefix "grid_env_diff_preflight_packet=")
$createPreflightPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $createPreflightResult.Text -Prefix "grid_create_authorization_preflight_packet=")
$bundlePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $bundleResult.Text -Prefix "grid_open_authorization_bundle_packet=")
$requestPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $requestResult.Text -Prefix "grid_open_operator_authorization_request_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$verificationBlockers = [System.Collections.Generic.List[string]]::new()
if ($planResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "post-env verification plan completed" }
if ($splitResult.ExitCode -ne 0) { Add-Unique -List $verificationBlockers -Value "SPLIT_ACCEPTANCE_FAILED_OR_INCOMPLETE" }
if ($decisionResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open decision snapshot completed" }
if ($trendResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid trend override review completed" }
if ($envDiffResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid env diff preflight completed" }
if ($createPreflightResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid create authorization preflight completed" }
if ($bundleResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open authorization bundle completed" }
if ($requestResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open operator authorization request completed" }
if ($splitResult.Text -notmatch "\[split-acceptance\] OK") { Add-Unique -List $verificationBlockers -Value "SPLIT_ACCEPTANCE_OK_MARKER_MISSING" }
if ($null -eq $planPacket) { Add-Unique -List $missingEvidence -Value "grid_post_env_verification_plan_packet valid JSON" }
if ($null -eq $decisionPacket) { Add-Unique -List $missingEvidence -Value "grid_open_decision_snapshot_packet valid JSON" }
if ($null -eq $trendPacket) { Add-Unique -List $missingEvidence -Value "grid_trend_override_review_packet valid JSON" }
if ($null -eq $envDiffPacket) { Add-Unique -List $missingEvidence -Value "grid_env_diff_preflight_packet valid JSON" }
if ($null -eq $createPreflightPacket) { Add-Unique -List $missingEvidence -Value "grid_create_authorization_preflight_packet valid JSON" }
if ($null -eq $bundlePacket) { Add-Unique -List $missingEvidence -Value "grid_open_authorization_bundle_packet valid JSON" }
if ($null -eq $requestPacket) { Add-Unique -List $missingEvidence -Value "grid_open_operator_authorization_request_packet valid JSON" }

$planReady = if ($null -ne $planPacket) { [bool]$planPacket.postEnvVerificationPlanReady } else { $false }
$requestReady = if ($null -ne $requestPacket) { [bool]$requestPacket.authorizationRequestReady } else { $false }
$bundleReady = if ($null -ne $bundlePacket) { [bool]$bundlePacket.gridOpenAuthorizationBundleReady } else { $false }
$splitAcceptanceFailureSummary = Get-DiagnosticLines -Text $splitResult.Text
$envReadiness = if ($null -ne $envDiffPacket) { $envDiffPacket.envReadiness } else { $null }
$okxEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.tradingOkxEnabled } else { "UNKNOWN" }
$gridEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.tradingGridEnabled } else { "UNKNOWN" }
$schedulerEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.gridAutoRebalanceSchedulerEnabled } else { "UNKNOWN" }
$recoveryEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.gridRecoveryEnabled } else { "UNKNOWN" }
$earnEnabled = if ($null -ne $envReadiness) { [string]$envReadiness.okxEarnTopupEnabled } else { "UNKNOWN" }
$eventRiskGate = if ($null -ne $envReadiness) { [string]$envReadiness.eventRiskGate } else { "UNKNOWN" }
$createPreflightMissingEvidence = @($(if ($null -ne $createPreflightPacket) { $createPreflightPacket.missingEvidence } else { @() }))

if (-not $planReady) { Add-Unique -List $verificationBlockers -Value "GRID_POST_ENV_VERIFICATION_PLAN_NOT_READY" }
if (-not $requestReady) { Add-Unique -List $verificationBlockers -Value "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY" }
if (-not $bundleReady) { Add-Unique -List $verificationBlockers -Value "GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_READY" }
if ($okxEnabled -ne "true") { Add-Unique -List $verificationBlockers -Value "POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE" }
if ($gridEnabled -ne "true") { Add-Unique -List $verificationBlockers -Value "POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE" }
if ($schedulerEnabled -ne "false") { Add-Unique -List $verificationBlockers -Value "POST_ENV_GRID_AUTO_REBALANCE_SCHEDULER_NOT_FALSE" }
if ($recoveryEnabled -ne "false") { Add-Unique -List $verificationBlockers -Value "POST_ENV_GRID_RECOVERY_NOT_FALSE" }
if ($earnEnabled -ne "false") { Add-Unique -List $verificationBlockers -Value "POST_ENV_OKX_EARN_TOPUP_NOT_FALSE" }
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") { Add-Unique -List $verificationBlockers -Value "POST_ENV_EVENT_RISK_NOT_R0" }
if (@($createPreflightMissingEvidence).Count -gt 0) { Add-Unique -List $missingEvidence -Value "fresh createGrid preflight missingEvidence=[]" }

$postEnvVerificationReady = (
    $missingEvidence.Count -eq 0 -and
    $verificationBlockers.Count -eq 0 -and
    $planReady -and
    $requestReady -and
    $bundleReady
)
$status = if ($postEnvVerificationReady) {
    "READY_FOR_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION"
} else {
    "BLOCKED_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION"
}
$decision = if ($postEnvVerificationReady) {
    "AWAIT_SEPARATE_CREATEGRID_AUTHORIZATION"
} elseif ($verificationBlockers -contains "POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE" -or $verificationBlockers -contains "POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE") {
    "APPLY_SEPARATELY_AUTHORIZED_ENV_DIFF_AND_DEPLOY_THEN_RERUN"
} elseif ($verificationBlockers -contains "SPLIT_ACCEPTANCE_FAILED_OR_INCOMPLETE" -or $verificationBlockers -contains "SPLIT_ACCEPTANCE_OK_MARKER_MISSING") {
    "FIX_SPLIT_ACCEPTANCE_BEFORE_CREATEGRID_REVIEW"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_POST_ENV_READ_ONLY_VERIFICATION_EVIDENCE"
} else {
    "RESOLVE_GRID_POST_ENV_READ_ONLY_VERIFICATION_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceScripts = @(
        "prepare_grid_post_env_verification_plan_ssh.ps1",
        "verify_split_acceptance_ssh.ps1",
        "prepare_grid_open_decision_snapshot_ssh.ps1",
        "prepare_grid_trend_override_review_packet_ssh.ps1",
        "prepare_grid_env_diff_preflight_packet_ssh.ps1",
        "prepare_grid_create_authorization_preflight_packet_ssh.ps1",
        "prepare_grid_open_authorization_bundle_ssh.ps1",
        "prepare_grid_open_operator_authorization_request_ssh.ps1"
    )
    sourceExitCodes = [pscustomobject]@{
        plan = $planResult.ExitCode
        splitAcceptance = $splitResult.ExitCode
        decisionSnapshot = $decisionResult.ExitCode
        trendOverrideReview = $trendResult.ExitCode
        envDiffPreflight = $envDiffResult.ExitCode
        createAuthorizationPreflight = $createPreflightResult.ExitCode
        authorizationBundle = $bundleResult.ExitCode
        authorizationRequest = $requestResult.ExitCode
    }
    splitAcceptanceOk = ($splitResult.ExitCode -eq 0 -and $splitResult.Text -match "\[split-acceptance\] OK")
    splitAcceptanceFailureSummary = @($splitAcceptanceFailureSummary)
    envReadiness = [pscustomobject]@{
        tradingOkxEnabled = $okxEnabled
        tradingGridEnabled = $gridEnabled
        gridAutoRebalanceSchedulerEnabled = $schedulerEnabled
        gridRecoveryEnabled = $recoveryEnabled
        okxEarnTopupEnabled = $earnEnabled
        eventRiskGate = $eventRiskGate
    }
    refreshedCreateGridInputsMustMatch = if ($null -ne $requestPacket) { $requestPacket.reviewedCreateGridInputs } else { $null }
    freshDecisionSnapshotStatus = if ($null -ne $decisionPacket) { $decisionPacket.status } else { "UNKNOWN" }
    freshTrendOverrideStatus = if ($null -ne $trendPacket) { $trendPacket.status } else { "UNKNOWN" }
    freshEnvDiffStatus = if ($null -ne $envDiffPacket) { $envDiffPacket.status } else { "UNKNOWN" }
    freshCreateAuthorizationPreflightStatus = if ($null -ne $createPreflightPacket) { $createPreflightPacket.status } else { "UNKNOWN" }
    freshAuthorizationBundleStatus = if ($null -ne $bundlePacket) { $bundlePacket.status } else { "UNKNOWN" }
    freshAuthorizationRequestStatus = if ($null -ne $requestPacket) { $requestPacket.status } else { "UNKNOWN" }
    postEnvVerificationReady = $postEnvVerificationReady
    verificationBlockers = @($verificationBlockers)
    missingEvidence = @($missingEvidence)
    nextRequiredOperatorAuthorization = @(
        "separate written createGrid authorization naming refreshedCreateGridInputsMustMatch after this bundle is ready",
        "keep TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false unless separately authorized",
        "keep GRID_RECOVERY_ENABLED=false unless separately authorized",
        "keep OKX_EARN_TOPUP_ENABLED=false unless separately authorized"
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
    sourcePlanPacketSummary = $planPacket
    sourceEnvDiffPacketSummary = $envDiffPacket
    sourceCreatePreflightPacketSummary = $createPreflightPacket
    sourceAuthorizationRequestPacketSummary = $requestPacket
    notAuthorization = "read-only grid post-env verification bundle only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
}

Write-Host "[grid-post-env-read-only-verification-bundle] read-only packet"
Write-Host "scope=READ_ONLY; invokes split acceptance and existing grid read-only packets only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_plan=prepare_grid_post_env_verification_plan_ssh.ps1 exitCode=$($planResult.ExitCode)"
Write-Host "source_split_acceptance=verify_split_acceptance_ssh.ps1 exitCode=$($splitResult.ExitCode)"
Write-Host "source_decision_snapshot=prepare_grid_open_decision_snapshot_ssh.ps1 exitCode=$($decisionResult.ExitCode)"
Write-Host "source_trend_override=prepare_grid_trend_override_review_packet_ssh.ps1 exitCode=$($trendResult.ExitCode)"
Write-Host "source_env_diff=prepare_grid_env_diff_preflight_packet_ssh.ps1 exitCode=$($envDiffResult.ExitCode)"
Write-Host "source_create_preflight=prepare_grid_create_authorization_preflight_packet_ssh.ps1 exitCode=$($createPreflightResult.ExitCode)"
Write-Host "source_authorization_bundle=prepare_grid_open_authorization_bundle_ssh.ps1 exitCode=$($bundleResult.ExitCode)"
Write-Host "source_authorization_request=prepare_grid_open_operator_authorization_request_ssh.ps1 exitCode=$($requestResult.ExitCode)"
Write-Host "grid_post_env_read_only_verification_status=$status"
Write-Host "grid_post_env_read_only_verification_decision=$decision"
Write-Host "grid_post_env_read_only_verification_ready=$($postEnvVerificationReady.ToString().ToLowerInvariant())"
Write-Host "split_acceptance_ok=$((($splitResult.ExitCode -eq 0 -and $splitResult.Text -match "\[split-acceptance\] OK")).ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_post_env_read_only_verification_env_readiness=" + (ConvertTo-Json -Compress $packet.envReadiness))
Write-Host ("grid_post_env_read_only_verification_split_failure_summary=" + (ConvertTo-Json -Compress @($splitAcceptanceFailureSummary)))
Write-Host ("grid_post_env_read_only_verification_blockers=" + (ConvertTo-Json -Compress @($verificationBlockers)))
Write-Host ("grid_post_env_read_only_verification_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_post_env_read_only_verification_next_authorization=" + (ConvertTo-Json -Compress @($packet.nextRequiredOperatorAuthorization)))
Write-Host ("grid_post_env_read_only_verification_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid post-env verification bundle only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
Write-Host "[grid-post-env-read-only-verification-bundle] read-only check complete"

if ($RequireVerificationReady -and -not $postEnvVerificationReady) {
    throw "Grid post-env read-only verification is not ready: $status; blockers=$(@($verificationBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
