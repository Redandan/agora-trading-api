param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$Symbol = "BTCUSDT",
    [decimal]$MaxProbeNotionalUsdt = 10,
    [int]$MaxAgeMinutes = 1440,
    [int]$ChildTimeoutSeconds = 1800,
    [string]$LiveReviewPacketLogPath = "",
    [string]$RuntimeEvidenceRcaLogPath = "",
    [switch]$RefreshLiveReviewFromSsh,
    [switch]$RefreshRuntimeEvidenceFromSsh,
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [switch]$PlanOnly,
    [switch]$ContinueOnStepFailure,
    [switch]$AllowDirtyLocalWorktreeForReplay,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-TokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters."
    }
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

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function New-Step {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments,
        [string]$OutputPath,
        [bool]$UsesSsh,
        [bool]$Required
    )
    return [pscustomobject]@{
        name = $Name
        script = $ScriptName
        arguments = @($Arguments)
        outputPath = $OutputPath
        usesSsh = $UsesSsh
        required = $Required
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Invoke-RefreshStep {
    param([object]$Step, [object]$PowerShellCommand)
    $scriptPath = Join-Path $PSScriptRoot ([string]$Step.script)
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing refresh step script: $scriptPath"
    }

    $outputPath = Resolve-RepoPath -PathValue ([string]$Step.outputPath)
    $outputParent = Split-Path -Parent $outputPath
    if (-not [string]::IsNullOrWhiteSpace($outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }

    Write-Host ("[profit-high-risk-micro-live-probe-activation-source-refresh] step_start name={0} script={1} output={2}" -f $Step.name, $Step.script, $Step.outputPath)
    $startedAt = Get-Date
    $timedOut = $false
    $text = ""
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
        } -ArgumentList @($PowerShellCommand.Source, $scriptPath, $repoRoot, (, @($Step.arguments)))

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
                Write-Host ("[profit-high-risk-micro-live-probe-activation-source-refresh] step_heartbeat name={0} elapsedSeconds={1}" -f $Step.name, $elapsedSeconds)
            }
            Start-Sleep -Seconds 2
        }

        if ($timedOut) {
            $text = "timed out after $ChildTimeoutSeconds second(s)"
            $exitCode = 124
            Write-Host ("[profit-high-risk-micro-live-probe-activation-source-refresh] step_timeout name={0} timeoutSeconds={1}" -f $Step.name, $ChildTimeoutSeconds)
        } else {
            $result = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($null -ne $result) {
                $text = [string]$result.Text
                $exitCode = [int]$result.ExitCode
            }
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    Set-Content -LiteralPath $outputPath -Encoding UTF8 -Value $text
    $elapsedTotalSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
    Write-Host ("[profit-high-risk-micro-live-probe-activation-source-refresh] step_complete name={0} exitCode={1} timedOut={2} elapsedSeconds={3} output={4}" -f $Step.name, $exitCode, $timedOut.ToString().ToLowerInvariant(), $elapsedTotalSeconds, $Step.outputPath)

    return [pscustomobject]@{
        name = $Step.name
        script = $Step.script
        outputPath = $Step.outputPath
        exitCode = [int]$exitCode
        timedOut = $timedOut
        success = ([int]$exitCode -eq 0)
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
Assert-TokenSafe -Name "Symbol" -Value $Symbol
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) { throw "MaxProbeNotionalUsdt must be between 0 and 1000." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 7200) { throw "ChildTimeoutSeconds must be between 60 and 7200." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$reviewDir = Resolve-RepoPath -PathValue $ReviewOutputDir
$out = { param([string]$Name) (Join-Path $ReviewOutputDir $Name) }

if ([string]::IsNullOrWhiteSpace($LiveReviewPacketLogPath)) {
    $LiveReviewPacketLogPath = & $out "live-review-packet-latest.log"
}
if ([string]::IsNullOrWhiteSpace($RuntimeEvidenceRcaLogPath)) {
    $latestRuntimeEvidenceLog = & $out "runtime-evidence-rca-latest.log"
    $postDeployRuntimeEvidenceLog = & $out "runtime-evidence-rca-post-deploy-current.log"
    $RuntimeEvidenceRcaLogPath = if ($RefreshRuntimeEvidenceFromSsh) {
        $latestRuntimeEvidenceLog
    } elseif (Test-Path -LiteralPath (Resolve-RepoPath -PathValue $latestRuntimeEvidenceLog)) {
        $latestRuntimeEvidenceLog
    } else {
        $postDeployRuntimeEvidenceLog
    }
}

$needsSsh = $RefreshLiveReviewFromSsh -or $RefreshRuntimeEvidenceFromSsh
if ($needsSsh) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required when refreshing SSH evidence. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required when refreshing SSH evidence. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
}

$handoffLog = & $out "profit-high-risk-micro-live-probe-handoff-latest.log"
$aggressiveLog = & $out "profit-aggressive-activation-operator-packet-latest.log"
$strategyPreflightLog = & $out "strategy574-tiny-live-governance-preflight-review-packet-latest.log"
$tpSlOcoPreflightLog = & $out "tp-sl-oco-feasibility-preflight-review-packet-latest.log"
$preflightLog = & $out "profit-high-risk-micro-live-probe-preflight-review-latest.log"
$activationLog = & $out "profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log"

$dirtyReplayArg = @()
if ($AllowDirtyLocalWorktreeForReplay.IsPresent) { $dirtyReplayArg += "-AllowDirtyLocalWorktreeForReplay" }

$steps = [System.Collections.Generic.List[object]]::new()
if ($RefreshLiveReviewFromSsh) {
    $steps.Add((New-Step -Name "live-review-packet" -ScriptName "prepare_live_review_packet_ssh.ps1" -Arguments @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol) -OutputPath $LiveReviewPacketLogPath -UsesSsh $true -Required $true))
}
if ($RefreshRuntimeEvidenceFromSsh) {
    $steps.Add((New-Step -Name "runtime-evidence-rca" -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-StrategyId", "574", "-Side", "LONG") -OutputPath $RuntimeEvidenceRcaLogPath -UsesSsh $true -Required $true))
}
$steps.Add((New-Step -Name "aggressive-activation-packet" -ScriptName "prepare_profit_aggressive_activation_operator_packet.ps1" -Arguments @("-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxProbeNotionalUsdt", "-MaxAgeMinutes", "$MaxAgeMinutes") -OutputPath $aggressiveLog -UsesSsh $false -Required $true))
$steps.Add((New-Step -Name "micro-probe-handoff" -ScriptName "prepare_profit_high_risk_micro_live_probe_handoff.ps1" -Arguments (@("-AggressivePacketLogPath", $aggressiveLog, "-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxProbeNotionalUsdt") + $dirtyReplayArg) -OutputPath $handoffLog -UsesSsh $false -Required $true))
$steps.Add((New-Step -Name "strategy574-tiny-live-preflight" -ScriptName "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1" -Arguments @("-Symbol", $Symbol, "-StrategyId", "574", "-Side", "LONG", "-MaxAgeMinutes", "$MaxAgeMinutes") -OutputPath $strategyPreflightLog -UsesSsh $false -Required $true))
$steps.Add((New-Step -Name "tp-sl-oco-preflight" -ScriptName "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1" -Arguments @("-Symbol", $Symbol, "-StrategyId", "485", "-MaxAgeMinutes", "$MaxAgeMinutes") -OutputPath $tpSlOcoPreflightLog -UsesSsh $false -Required $true))
$steps.Add((New-Step -Name "micro-probe-preflight" -ScriptName "prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1" -Arguments (@("-MicroProbeHandoffLogPath", $handoffLog, "-Strategy574TinyLivePreflightLogPath", $strategyPreflightLog, "-TpSlOcoPreflightLogPath", $tpSlOcoPreflightLog, "-LiveReviewPacketLogPath", $LiveReviewPacketLogPath, "-RuntimeEvidenceRcaLogPath", $RuntimeEvidenceRcaLogPath, "-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxProbeNotionalUsdt") + $dirtyReplayArg) -OutputPath $preflightLog -UsesSsh $false -Required $true))
$steps.Add((New-Step -Name "activation-authorization-bundle" -ScriptName "prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1" -Arguments (@("-MicroProbeHandoffLogPath", $handoffLog, "-PreflightReviewLogPath", $preflightLog, "-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxProbeNotionalUsdt") + $dirtyReplayArg) -OutputPath $activationLog -UsesSsh $false -Required $true))

$plan = [pscustomobject]@{
    packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PLAN"
    reviewOutputDir = $ReviewOutputDir
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    maxAgeMinutes = $MaxAgeMinutes
    childTimeoutSeconds = $ChildTimeoutSeconds
    refreshLiveReviewFromSsh = [bool]$RefreshLiveReviewFromSsh
    refreshRuntimeEvidenceFromSsh = [bool]$RefreshRuntimeEvidenceFromSsh
    liveReviewPacketLogPath = $LiveReviewPacketLogPath
    runtimeEvidenceRcaLogPath = $RuntimeEvidenceRcaLogPath
    activationBundleLogPath = $activationLog
    aggressiveActivationLogPath = $aggressiveLog
    stepCount = $steps.Count
    sshStepCount = @($steps | Where-Object { $_.usesSsh }).Count
    localStepCount = @($steps | Where-Object { -not $_.usesSsh }).Count
    steps = @($steps)
    forbiddenActions = @("deploy", "production env change", "enable live trading", "enable scheduler", "place orders", "execute TinyLive", "send Telegram", "modify or cancel OCO", "close positions", "mutate DB/grid/fund/Earn/exchange/external backfill state", "relax EntryDedup/DataFreshness/live policy")
    notAuthorization = "read-only activation source refresh only; writes local target evidence logs and does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-high-risk-micro-live-probe-activation-source-refresh] read-only refresh orchestration"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local evidence scripts and writes local target/profit-review logs only; no deploy, production env, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, restart, or nginx state changed."
Write-Host "profit_micro_probe_activation_source_refresh_step_count=$($steps.Count)"
Write-Host "profit_micro_probe_activation_source_refresh_ssh_step_count=$(@($steps | Where-Object { $_.usesSsh }).Count)"
Write-Host "profit_micro_probe_activation_source_refresh_local_step_count=$(@($steps | Where-Object { -not $_.usesSsh }).Count)"
Write-Host "profit_micro_probe_activation_source_refresh_live_review_log=$LiveReviewPacketLogPath"
Write-Host "profit_micro_probe_activation_source_refresh_runtime_evidence_log=$RuntimeEvidenceRcaLogPath"
Write-Host "profit_micro_probe_activation_source_refresh_activation_log=$activationLog"
Write-Host ("profit_micro_probe_activation_source_refresh_plan=" + (ConvertTo-Json -Compress -Depth 8 $plan))
Write-Host "notAuthorization=$($plan.notAuthorization)"

if ($PlanOnly) {
    Write-Host "profit_micro_probe_activation_source_refresh_status=PLAN_ONLY_NOT_EXECUTED"
    return
}

New-Item -ItemType Directory -Force -Path $reviewDir | Out-Null
$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for micro live probe activation source refresh." }

$results = [System.Collections.Generic.List[object]]::new()
foreach ($step in $steps) {
    try {
        $result = Invoke-RefreshStep -Step $step -PowerShellCommand $powerShell
        $results.Add($result)
        if (-not $result.success -and -not $ContinueOnStepFailure) {
            throw "Refresh step failed: $($step.name)"
        }
    } catch {
        $failure = [pscustomobject]@{
            name = $step.name
            script = $step.script
            outputPath = $step.outputPath
            exitCode = 1
            success = $false
            error = $_.Exception.Message
        }
        $results.Add($failure)
        if (-not $ContinueOnStepFailure) { throw }
    }
}

$failed = @($results | Where-Object { -not $_.success })
$activationStatus = "UNKNOWN"
$activationReady = $false
$activationMissing = "[]"
$activationPath = Resolve-RepoPath -PathValue $activationLog
if (Test-Path -LiteralPath $activationPath) {
    $activationText = Get-Content -Raw -LiteralPath $activationPath
    $activationStatus = Get-LastPrefixedValue -Text $activationText -Prefix "profit_high_risk_micro_live_probe_activation_authorization_status=" -Default "UNKNOWN"
    $activationReady = (Get-LastPrefixedValue -Text $activationText -Prefix "micro_probe_activation_authorization_review_ready=" -Default "false") -eq "true"
    $activationMissing = Get-LastPrefixedValue -Text $activationText -Prefix "micro_probe_activation_missing_requirements=" -Default "[]"
}

$status = if ($failed.Count -gt 0) {
    "INCOMPLETE_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS"
} elseif ($activationReady) {
    "READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_REQUIREMENTS_MISSING"
}
$decision = if ($activationReady) {
    "REVIEW_EXACT_MICRO_PROBE_ACTIVATION_AUTHORIZATION_TEXT_WITH_OPERATOR_DO_NOT_EXECUTE_FROM_REFRESH"
} elseif ($failed.Count -gt 0) {
    "FIX_FAILED_REFRESH_STEPS_BEFORE_ACTIVATION_AUTHORIZATION_REVIEW"
} else {
    "USE_REFRESHED_ACTIVATION_BUNDLE_BLOCKERS_BEFORE_REQUESTING_ENV_DEPLOY_AUTHORIZATION"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    ready = $activationReady
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    refreshLiveReviewFromSsh = [bool]$RefreshLiveReviewFromSsh
    refreshRuntimeEvidenceFromSsh = [bool]$RefreshRuntimeEvidenceFromSsh
    liveReviewPacketLogPath = $LiveReviewPacketLogPath
    runtimeEvidenceRcaLogPath = $RuntimeEvidenceRcaLogPath
    aggressiveActivationLogPath = $aggressiveLog
    handoffLogPath = $handoffLog
    strategy574PreflightLogPath = $strategyPreflightLog
    tpSlOcoPreflightLogPath = $tpSlOcoPreflightLog
    preflightLogPath = $preflightLog
    activationBundleLogPath = $activationLog
    activationBundleStatus = $activationStatus
    activationMissingRequirements = $activationMissing
    failedStepCount = $failed.Count
    results = @($results)
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = $plan.notAuthorization
}

Write-Host ("profit_micro_probe_activation_source_refresh_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host ("profit_micro_probe_activation_source_refresh_results=" + (ConvertTo-Json -Compress -Depth 6 @($results)))
Write-Host "profit_micro_probe_activation_source_refresh_failed_count=$($failed.Count)"
Write-Host "profit_micro_probe_activation_source_refresh_activation_status=$activationStatus"
Write-Host "profit_micro_probe_activation_source_refresh_activation_ready=$($activationReady.ToString().ToLowerInvariant())"
Write-Host "profit_micro_probe_activation_source_refresh_activation_missing_requirements=$activationMissing"
Write-Host "profit_micro_probe_activation_source_refresh_status=$status"
Write-Host "profit_micro_probe_activation_source_refresh_decision=$decision"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-high-risk-micro-live-probe-activation-source-refresh] read-only refresh complete"

if ($RequireReady -and $status -ne "READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    throw "High-risk micro live probe activation source refresh is not ready: $status; activationStatus=$activationStatus; failedStepCount=$($failed.Count)"
}
