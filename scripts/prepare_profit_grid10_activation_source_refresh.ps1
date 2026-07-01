param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$GridAuthorizationBundleLogPath = "target/profit-review/grid-open-authorization-bundle-microgrid-current.log",
    [string]$GridBlockerPriorityBoardLogPath = "target/profit-review/grid-open-blocker-priority-board-latest.log",
    [string]$Symbol = "BTCUSDT",
    [int]$GridCount = 2,
    [decimal]$PerLevelUsdt = 5,
    [decimal]$StopOutPct = 5.0,
    [decimal]$CandidateHalfWidthPct = 10.0,
    [decimal]$MaxCapitalUsdt = 10,
    [int]$MaxAgeMinutes = 1440,
    [int]$ChildTimeoutSeconds = 1800,
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
        [bool]$Required
    )
    return [pscustomobject]@{
        name = $Name
        script = $ScriptName
        arguments = @($Arguments)
        outputPath = $OutputPath
        usesSsh = $false
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

    Write-Host ("[profit-grid10-activation-source-refresh] step_start name={0} script={1} output={2}" -f $Step.name, $Step.script, $Step.outputPath)
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
                Write-Host ("[profit-grid10-activation-source-refresh] step_heartbeat name={0} elapsedSeconds={1}" -f $Step.name, $elapsedSeconds)
            }
            Start-Sleep -Seconds 2
        }

        if ($timedOut) {
            $text = "timed out after $ChildTimeoutSeconds second(s)"
            $exitCode = 124
            Write-Host ("[profit-grid10-activation-source-refresh] step_timeout name={0} timeoutSeconds={1}" -f $Step.name, $ChildTimeoutSeconds)
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
    Write-Host ("[profit-grid10-activation-source-refresh] step_complete name={0} exitCode={1} timedOut={2} elapsedSeconds={3} output={4}" -f $Step.name, $exitCode, $timedOut.ToString().ToLowerInvariant(), $elapsedTotalSeconds, $Step.outputPath)

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
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30) { throw "CandidateHalfWidthPct must be between 2.5 and 30." }
if ($MaxCapitalUsdt -lt 5 -or $MaxCapitalUsdt -gt 1000) { throw "MaxCapitalUsdt must be between 5 and 1000." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 7200) { throw "ChildTimeoutSeconds must be between 60 and 7200." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$reviewDir = Resolve-RepoPath -PathValue $ReviewOutputDir
$out = { param([string]$Name) (Join-Path $ReviewOutputDir $Name) }

$aggressiveLog = & $out "profit-aggressive-activation-operator-packet-latest.log"
$handoffLog = & $out "profit-grid10-order-path-handoff-latest.log"
$activationLog = & $out "profit-grid10-activation-authorization-bundle-latest.log"
$sameSessionLog = & $out "profit-grid10-same-session-activation-review-latest.log"

$dirtyReplayArg = @()
if ($AllowDirtyLocalWorktreeForReplay.IsPresent) { $dirtyReplayArg += "-AllowDirtyLocalWorktreeForReplay" }

$steps = [System.Collections.Generic.List[object]]::new()
$steps.Add((New-Step -Name "aggressive-activation-packet" -ScriptName "prepare_profit_aggressive_activation_operator_packet.ps1" -Arguments @("-GridBlockerPriorityBoardLogPath", $GridBlockerPriorityBoardLogPath, "-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxCapitalUsdt", "-MaxAgeMinutes", "$MaxAgeMinutes") -OutputPath $aggressiveLog -Required $true))
$steps.Add((New-Step -Name "grid10-order-path-handoff" -ScriptName "prepare_profit_grid10_order_path_handoff.ps1" -Arguments (@("-AggressivePacketLogPath", $aggressiveLog, "-GridAuthorizationBundleLogPath", $GridAuthorizationBundleLogPath, "-MaxAgeMinutes", "$MaxAgeMinutes", "-Symbol", $Symbol, "-GridCount", "$GridCount", "-PerLevelUsdt", "$PerLevelUsdt", "-StopOutPct", "$StopOutPct", "-CandidateHalfWidthPct", "$CandidateHalfWidthPct", "-MaxCapitalUsdt", "$MaxCapitalUsdt") + $dirtyReplayArg) -OutputPath $handoffLog -Required $true))
$steps.Add((New-Step -Name "grid10-activation-authorization-bundle" -ScriptName "prepare_profit_grid10_activation_authorization_bundle.ps1" -Arguments (@("-Grid10HandoffLogPath", $handoffLog, "-Symbol", $Symbol, "-GridCount", "$GridCount", "-PerLevelUsdt", "$PerLevelUsdt", "-StopOutPct", "$StopOutPct", "-CandidateHalfWidthPct", "$CandidateHalfWidthPct", "-MaxCapitalUsdt", "$MaxCapitalUsdt") + $dirtyReplayArg) -OutputPath $activationLog -Required $true))
$steps.Add((New-Step -Name "grid10-same-session-activation-review" -ScriptName "prepare_profit_grid10_same_session_activation_review_packet.ps1" -Arguments (@("-Grid10ActivationBundleLogPath", $activationLog, "-Symbol", $Symbol, "-GridCount", "$GridCount", "-PerLevelUsdt", "$PerLevelUsdt", "-StopOutPct", "$StopOutPct", "-CandidateHalfWidthPct", "$CandidateHalfWidthPct", "-MaxCapitalUsdt", "$MaxCapitalUsdt") + $dirtyReplayArg) -OutputPath $sameSessionLog -Required $true))

$plan = [pscustomobject]@{
    packetType = "PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PLAN"
    reviewOutputDir = $ReviewOutputDir
    symbol = $Symbol
    gridCount = $GridCount
    perLevelUsdt = $PerLevelUsdt
    stopOutPct = $StopOutPct
    candidateHalfWidthPct = $CandidateHalfWidthPct
    maxCapitalUsdt = $MaxCapitalUsdt
    maxAgeMinutes = $MaxAgeMinutes
    childTimeoutSeconds = $ChildTimeoutSeconds
    gridAuthorizationBundleLogPath = $GridAuthorizationBundleLogPath
    gridBlockerPriorityBoardLogPath = $GridBlockerPriorityBoardLogPath
    aggressiveActivationLogPath = $aggressiveLog
    handoffLogPath = $handoffLog
    activationBundleLogPath = $activationLog
    sameSessionActivationReviewLogPath = $sameSessionLog
    stepCount = $steps.Count
    localStepCount = $steps.Count
    steps = @($steps)
    forbiddenActions = @("deploy", "production env change", "enable live trading", "enable scheduler", "place orders", "createGrid", "modify or cancel OCO", "close positions", "send Telegram", "mutate DB/grid/fund/Earn/exchange/external backfill state", "relax EntryDedup/DataFreshness/live policy")
    notAuthorization = "read-only grid10 activation source refresh only; writes local target evidence logs and does not authorize live trading, scheduler enablement, orders, createGrid, OCO modification, close-position, deploy, production env change, Telegram send, policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-grid10-activation-source-refresh] read-only refresh orchestration"
Write-Host "scope=READ_ONLY; invokes existing local evidence scripts and writes local target/profit-review logs only; no SSH, deploy, production env, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, restart, or nginx state changed."
Write-Host "profit_grid10_activation_source_refresh_step_count=$($steps.Count)"
Write-Host "profit_grid10_activation_source_refresh_local_step_count=$($steps.Count)"
Write-Host "profit_grid10_activation_source_refresh_grid_authorization_bundle_log=$GridAuthorizationBundleLogPath"
Write-Host "profit_grid10_activation_source_refresh_aggressive_log=$aggressiveLog"
Write-Host "profit_grid10_activation_source_refresh_handoff_log=$handoffLog"
Write-Host "profit_grid10_activation_source_refresh_activation_log=$activationLog"
Write-Host "profit_grid10_activation_source_refresh_same_session_log=$sameSessionLog"
Write-Host ("profit_grid10_activation_source_refresh_plan=" + (ConvertTo-Json -Compress -Depth 8 $plan))
Write-Host "notAuthorization=$($plan.notAuthorization)"

if ($PlanOnly) {
    Write-Host "profit_grid10_activation_source_refresh_status=PLAN_ONLY_NOT_EXECUTED"
    return
}

New-Item -ItemType Directory -Force -Path $reviewDir | Out-Null
$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid10 activation source refresh." }

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
$sameSessionStatus = "UNKNOWN"
$sameSessionReady = $false
$sameSessionMissing = "[]"
$sameSessionPath = Resolve-RepoPath -PathValue $sameSessionLog
if (Test-Path -LiteralPath $sameSessionPath) {
    $sameSessionText = Get-Content -Raw -LiteralPath $sameSessionPath
    $sameSessionStatus = Get-LastPrefixedValue -Text $sameSessionText -Prefix "profit_grid10_same_session_activation_review_status=" -Default "UNKNOWN"
    $sameSessionReady = (Get-LastPrefixedValue -Text $sameSessionText -Prefix "grid10_same_session_operator_checklist_ready=" -Default "false") -eq "true"
    $sameSessionMissing = Get-LastPrefixedValue -Text $sameSessionText -Prefix "grid10_same_session_missing_requirements=" -Default "[]"
}

$activationStatus = "UNKNOWN"
$activationPath = Resolve-RepoPath -PathValue $activationLog
if (Test-Path -LiteralPath $activationPath) {
    $activationText = Get-Content -Raw -LiteralPath $activationPath
    $activationStatus = Get-LastPrefixedValue -Text $activationText -Prefix "profit_grid10_activation_authorization_status=" -Default "UNKNOWN"
}

$status = if ($failed.Count -gt 0) {
    "INCOMPLETE_PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS"
} elseif ($sameSessionReady) {
    "READY_REFRESHED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_REFRESHED_PROFIT_GRID10_ACTIVATION_REQUIREMENTS_MISSING"
}
$decision = if ($sameSessionReady) {
    "REVIEW_EXACT_GRID10_SAME_SESSION_AUTHORIZATION_TEXT_WITH_OPERATOR_DO_NOT_EXECUTE_FROM_REFRESH"
} elseif ($failed.Count -gt 0) {
    "FIX_FAILED_GRID10_REFRESH_STEPS_BEFORE_ACTIVATION_AUTHORIZATION_REVIEW"
} else {
    "USE_REFRESHED_GRID10_SAME_SESSION_BLOCKERS_BEFORE_REQUESTING_ENV_DEPLOY_OR_CREATEGRID_AUTHORIZATION"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    ready = $sameSessionReady
    symbol = $Symbol
    gridCount = $GridCount
    perLevelUsdt = $PerLevelUsdt
    stopOutPct = $StopOutPct
    candidateHalfWidthPct = $CandidateHalfWidthPct
    maxCapitalUsdt = $MaxCapitalUsdt
    gridAuthorizationBundleLogPath = $GridAuthorizationBundleLogPath
    gridBlockerPriorityBoardLogPath = $GridBlockerPriorityBoardLogPath
    aggressiveActivationLogPath = $aggressiveLog
    handoffLogPath = $handoffLog
    activationBundleLogPath = $activationLog
    sameSessionActivationReviewLogPath = $sameSessionLog
    activationBundleStatus = $activationStatus
    sameSessionActivationReviewStatus = $sameSessionStatus
    sameSessionMissingRequirements = $sameSessionMissing
    failedStepCount = $failed.Count
    results = @($results)
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    createGridAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = $plan.notAuthorization
}

Write-Host ("profit_grid10_activation_source_refresh_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host ("profit_grid10_activation_source_refresh_results=" + (ConvertTo-Json -Compress -Depth 6 @($results)))
Write-Host "profit_grid10_activation_source_refresh_failed_count=$($failed.Count)"
Write-Host "profit_grid10_activation_source_refresh_activation_status=$activationStatus"
Write-Host "profit_grid10_activation_source_refresh_same_session_status=$sameSessionStatus"
Write-Host "profit_grid10_activation_source_refresh_same_session_ready=$($sameSessionReady.ToString().ToLowerInvariant())"
Write-Host "profit_grid10_activation_source_refresh_same_session_missing_requirements=$sameSessionMissing"
Write-Host "profit_grid10_activation_source_refresh_status=$status"
Write-Host "profit_grid10_activation_source_refresh_decision=$decision"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-grid10-activation-source-refresh] read-only refresh complete"

if ($RequireReady -and $status -ne "READY_REFRESHED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION") {
    throw "Profit grid10 activation source refresh is not ready: $status; activationStatus=$activationStatus; sameSessionStatus=$sameSessionStatus; failedStepCount=$($failed.Count)"
}
