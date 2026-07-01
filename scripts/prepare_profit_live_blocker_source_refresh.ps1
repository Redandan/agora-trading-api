param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$EntryDedupStrategyId = 508,
    [string]$EntryDedupIntervalCode = "1h",
    [int]$EntryDedupHours = 720,
    [int]$EntryDedupForwardHours = 24,
    [int]$EntryDedupLimit = 50,
    [int]$MatrixTimeoutSeconds = 3900,
    [int]$ChildTimeoutSeconds = 900,
    [int]$StepTimeoutSeconds = 5400,
    [int]$MatrixMaxAgeMinutes = 180,
    [switch]$ReuseLatestProfitOperatorMatrix,
    [switch]$ForceFreshProfitOperatorMatrix,
    [switch]$PlanOnly,
    [switch]$ContinueOnStepFailure,
    [switch]$AllowBlockedStepFailures
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-TokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function New-Step {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments,
        [string]$OutputPath,
        [bool]$Ssh,
        [bool]$Required
    )
    return [pscustomobject]@{
        name = $Name
        script = $ScriptName
        arguments = @($Arguments)
        outputPath = $OutputPath
        usesSsh = $Ssh
        required = $Required
    }
}

function Invoke-RefreshStep {
    param([object]$Step, [object]$PowerShellCommand, [int]$TimeoutSeconds)

    $scriptPath = Join-Path $PSScriptRoot ([string]$Step.script)
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing refresh step script: $scriptPath"
    }

    $outputPath = if ([System.IO.Path]::IsPathRooted([string]$Step.outputPath)) {
        [string]$Step.outputPath
    } else {
        Join-Path $repoRoot ([string]$Step.outputPath)
    }
    $outputParent = Split-Path -Parent $outputPath
    if (-not [string]::IsNullOrWhiteSpace($outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }

    Write-Host ("[profit-live-blocker-source-refresh] step_start name={0} script={1} output={2}" -f $Step.name, $Step.script, $Step.outputPath)
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
                Text = ($childOutput | Out-String -Width 4096)
                ExitCode = $code
            }
        } -ArgumentList @($PowerShellCommand.Source, $scriptPath, (Get-Location).Path, (, @($Step.arguments)))

        $lastHeartbeatSeconds = 0
        while ($job.State -eq "Running") {
            $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            if ($elapsedSeconds -ge $TimeoutSeconds) {
                $timedOut = $true
                Stop-Job -Job $job -ErrorAction SilentlyContinue
                break
            }
            if ($elapsedSeconds -ge ($lastHeartbeatSeconds + 30)) {
                $lastHeartbeatSeconds = $elapsedSeconds
                Write-Host ("[profit-live-blocker-source-refresh] step_heartbeat name={0} elapsedSeconds={1}" -f $Step.name, $elapsedSeconds)
            }
            Start-Sleep -Seconds 2
        }

        if ($timedOut) {
            $text = "timed out after $TimeoutSeconds second(s)"
            $exitCode = 124
            Write-Host ("[profit-live-blocker-source-refresh] step_timeout name={0} timeoutSeconds={1}" -f $Step.name, $TimeoutSeconds)
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
    if (-not [string]::IsNullOrWhiteSpace($outputParent) -and -not (Test-Path -LiteralPath $outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }
    Set-Content -LiteralPath $outputPath -Encoding UTF8 -Value $text

    $elapsedTotalSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
    Write-Host ("[profit-live-blocker-source-refresh] step_complete name={0} exitCode={1} timedOut={2} elapsedSeconds={3} output={4}" -f $Step.name, $exitCode, $timedOut.ToString().ToLowerInvariant(), $elapsedTotalSeconds, $Step.outputPath)
    return [pscustomobject]@{
        name = $Step.name
        script = $Step.script
        outputPath = $Step.outputPath
        exitCode = [int]$exitCode
        timedOut = $timedOut
        success = ([int]$exitCode -eq 0)
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

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
Assert-TokenSafe -Name "Symbol" -Value $Symbol
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($EntryDedupStrategyId -lt 1 -or $EntryDedupStrategyId -gt 1000000) { throw "EntryDedupStrategyId must be between 1 and 1000000." }
Assert-TokenSafe -Name "EntryDedupIntervalCode" -Value $EntryDedupIntervalCode
if ($EntryDedupHours -lt 1 -or $EntryDedupHours -gt 720) { throw "EntryDedupHours must be between 1 and 720." }
if ($EntryDedupForwardHours -lt 1 -or $EntryDedupForwardHours -gt 168) { throw "EntryDedupForwardHours must be between 1 and 168." }
if ($EntryDedupLimit -lt 1 -or $EntryDedupLimit -gt 100) { throw "EntryDedupLimit must be between 1 and 100." }
if ($MatrixTimeoutSeconds -lt 60 -or $MatrixTimeoutSeconds -gt 7200) { throw "MatrixTimeoutSeconds must be between 60 and 7200." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }
if ($StepTimeoutSeconds -lt 60 -or $StepTimeoutSeconds -gt 7200) { throw "StepTimeoutSeconds must be between 60 and 7200." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$reviewDir = if ([System.IO.Path]::IsPathRooted($ReviewOutputDir)) { $ReviewOutputDir } else { Join-Path $repoRoot $ReviewOutputDir }
$out = { param([string]$Name) (Join-Path $ReviewOutputDir $Name) }

$profitOperatorActionArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-MatrixTimeoutSeconds", "$MatrixTimeoutSeconds",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds",
    "-RequireReady"
)
$reusedProfitOperatorMatrixPath = ""
$autoReusedFreshProfitOperatorMatrix = $false
$latestMatrixPointerPath = Join-Path $reviewDir "latest-profit-operator-matrix.path"
if ($ForceFreshProfitOperatorMatrix -and $ReuseLatestProfitOperatorMatrix) {
    throw "ForceFreshProfitOperatorMatrix cannot be combined with ReuseLatestProfitOperatorMatrix."
}
if (-not $ForceFreshProfitOperatorMatrix -and -not $ReuseLatestProfitOperatorMatrix -and (Test-Path -LiteralPath $latestMatrixPointerPath)) {
    $autoPointerValue = (Get-Content -Raw -LiteralPath $latestMatrixPointerPath).Trim()
    if (-not [string]::IsNullOrWhiteSpace($autoPointerValue)) {
        $autoMatrixPath = if ([System.IO.Path]::IsPathRooted($autoPointerValue)) { $autoPointerValue } else { Join-Path $repoRoot $autoPointerValue }
        if (Test-Path -LiteralPath $autoMatrixPath) {
            $autoMatrixItem = Get-Item -LiteralPath $autoMatrixPath
            $autoMatrixAgeMinutes = [int]((Get-Date) - $autoMatrixItem.LastWriteTime).TotalMinutes
            if ($autoMatrixAgeMinutes -le $MatrixMaxAgeMinutes) {
                $ReuseLatestProfitOperatorMatrix = $true
                $autoReusedFreshProfitOperatorMatrix = $true
            }
        }
    }
}
if ($ReuseLatestProfitOperatorMatrix) {
    if (-not (Test-Path -LiteralPath $latestMatrixPointerPath)) {
        throw "ReuseLatestProfitOperatorMatrix requested but latest matrix pointer was not found: $latestMatrixPointerPath"
    }
    $pointerValue = (Get-Content -Raw -LiteralPath $latestMatrixPointerPath).Trim()
    if ([string]::IsNullOrWhiteSpace($pointerValue)) {
        throw "ReuseLatestProfitOperatorMatrix requested but latest matrix pointer is empty: $latestMatrixPointerPath"
    }
    $matrixPath = if ([System.IO.Path]::IsPathRooted($pointerValue)) { $pointerValue } else { Join-Path $repoRoot $pointerValue }
    if (-not (Test-Path -LiteralPath $matrixPath)) {
        throw "ReuseLatestProfitOperatorMatrix requested but latest matrix output was not found: $matrixPath"
    }
    $matrixItem = Get-Item -LiteralPath $matrixPath
    $matrixAgeMinutes = [int]((Get-Date) - $matrixItem.LastWriteTime).TotalMinutes
    if ($matrixAgeMinutes -gt $MatrixMaxAgeMinutes) {
        throw "ReuseLatestProfitOperatorMatrix requested but latest matrix is stale: ageMinutes=$matrixAgeMinutes maxAgeMinutes=$MatrixMaxAgeMinutes path=$matrixPath"
    }
    $reusedProfitOperatorMatrixPath = $matrixPath
    $profitOperatorActionArgs += @("-MatrixOutputPath", $matrixPath, "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes")
}

$steps = @(
    New-Step -Name "profit-operator-action-brief" -ScriptName "prepare_profit_operator_action_brief_ssh.ps1" -Arguments $profitOperatorActionArgs -OutputPath (& $out "profit-operator-action-brief-latest.log") -Ssh $true -Required $true
    New-Step -Name "profit-operator-priority-decision" -ScriptName "prepare_profit_operator_priority_decision_brief.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes", "-RequireReady") -OutputPath (& $out "profit-operator-priority-decision-brief-latest.log") -Ssh $false -Required $true
    New-Step -Name "trailing-stop-dry-run-decision" -ScriptName "prepare_trailing_stop_dry_run_operator_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes", "-RequireReady") -OutputPath (& $out "trailing-stop-dry-run-operator-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "strategy485-risk-reduction-decision" -ScriptName "prepare_strategy485_risk_reduction_operator_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes", "-RequireReady") -OutputPath (& $out "strategy485-risk-reduction-operator-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "entry-dedup-semantics-decision" -ScriptName "prepare_entry_dedup_operator_decision_brief_ssh.ps1" -Arguments @("-Symbol", $Symbol, "-StrategyId", "$EntryDedupStrategyId", "-IntervalCode", $EntryDedupIntervalCode, "-Hours", "$EntryDedupHours", "-ForwardHours", "$EntryDedupForwardHours", "-Limit", "$EntryDedupLimit", "-RequireDecisionReady") -OutputPath (& $out "entry-dedup-semantics-operator-decision-packet-latest.log") -Ssh $true -Required $true
    New-Step -Name "data-freshness-replay-blocker-decision" -ScriptName "prepare_data_freshness_replay_blocker_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes", "-RequireBlocked") -OutputPath (& $out "data-freshness-replay-blocker-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "data-freshness-replay-evidence-readiness" -ScriptName "prepare_data_freshness_replay_evidence_readiness_ssh.ps1" -Arguments @("-Symbol", $Symbol) -OutputPath (& $out "data-freshness-replay-evidence-readiness-refresh.log") -Ssh $true -Required $true
    New-Step -Name "data-freshness-collector-activation" -ScriptName "prepare_data_freshness_replay_collector_activation_packet.ps1" -Arguments @("-ReadinessLogPath", (& $out "data-freshness-replay-evidence-readiness-refresh.log"), "-Symbol", $Symbol, "-RequireDecisionReady") -OutputPath (& $out "data-freshness-replay-collector-activation-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "exit-side-operator-decision" -ScriptName "prepare_exit_side_operator_decision_brief_ssh.ps1" -Arguments @("-RequireDecisionReady") -OutputPath (& $out "exit-side-operator-decision-brief-refresh.log") -Ssh $true -Required $true
    New-Step -Name "tp-sl-oco-feasibility" -ScriptName "prepare_tp_sl_oco_feasibility_operator_packet.ps1" -Arguments @("-ExitSideDecisionLogPath", (& $out "exit-side-operator-decision-brief-refresh.log"), "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "tp-sl-oco-feasibility-operator-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "strategy485-risk-escalation" -ScriptName "prepare_strategy485_risk_escalation_brief.ps1" -Arguments @("-ExitSideDecisionLogPath", (& $out "exit-side-operator-decision-brief-refresh.log"), "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "strategy485-risk-escalation-brief-latest.log") -Ssh $false -Required $true
    New-Step -Name "strategy574-signal-review-gate" -ScriptName "prepare_strategy574_signal_review_gate_ssh.ps1" -Arguments @() -OutputPath (& $out "strategy574-signal-review-gate-refresh.log") -Ssh $true -Required $true
    New-Step -Name "tiny-live-loss-rca" -ScriptName "smoke_tiny_live_loss_rca_ssh.ps1" -Arguments @() -OutputPath (& $out "tiny-live-loss-rca-refresh.log") -Ssh $true -Required $true
    New-Step -Name "strategy574-near-threshold-observation" -ScriptName "smoke_strategy574_near_threshold_shadow_observation_ssh.ps1" -Arguments @() -OutputPath (& $out "strategy574-near-threshold-shadow-observation-latest.log") -Ssh $true -Required $true
    New-Step -Name "strategy574-tiny-live-governance" -ScriptName "prepare_strategy574_tiny_live_governance_operator_packet.ps1" -Arguments @("-RequireReady") -OutputPath (& $out "strategy574-tiny-live-governance-operator-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "strategy574-tiny-live-governance-preflight" -ScriptName "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1" -Arguments @("-RequireReady") -OutputPath (& $out "strategy574-tiny-live-governance-preflight-review-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "no-buy-attention-flow-review" -ScriptName "prepare_no_buy_attention_flow_review_packet_ssh.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-RequireReviewReady") -OutputPath (& $out "no-buy-attention-flow-review-packet-latest.log") -Ssh $true -Required $true
    New-Step -Name "governance-relaxation-review" -ScriptName "prepare_governance_relaxation_review_packet_ssh.ps1" -Arguments @() -OutputPath (& $out "governance-relaxation-review-packet-latest.log") -Ssh $true -Required $true
    New-Step -Name "governance-relaxation-preflight" -ScriptName "prepare_governance_relaxation_preflight_review_packet.ps1" -Arguments @("-ReviewLogPath", (& $out "governance-relaxation-review-packet-latest.log"), "-NoBuyAttentionLogPath", (& $out "no-buy-attention-flow-review-packet-latest.log")) -OutputPath (& $out "governance-relaxation-preflight-review-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "profit-live-blocker-audit" -ScriptName "prepare_profit_live_blocker_audit_packet.ps1" -Arguments @("-PriorityDecisionLogPath", (& $out "profit-operator-priority-decision-brief-latest.log"), "-TrailingDryRunLogPath", (& $out "trailing-stop-dry-run-operator-decision-packet-latest.log"), "-Strategy485RiskLogPath", (& $out "strategy485-risk-reduction-operator-decision-packet-latest.log"), "-Strategy485RiskEscalationLogPath", (& $out "strategy485-risk-escalation-brief-latest.log"), "-EntryDedupLogPath", (& $out "entry-dedup-semantics-operator-decision-packet-latest.log"), "-DataFreshnessReplayBlockerLogPath", (& $out "data-freshness-replay-blocker-decision-packet-latest.log"), "-DataFreshnessCollectorLogPath", (& $out "data-freshness-replay-collector-activation-packet-latest.log"), "-TpSlOcoLogPath", (& $out "tp-sl-oco-feasibility-operator-packet-latest.log"), "-Strategy574TinyLivePreflightLogPath", (& $out "strategy574-tiny-live-governance-preflight-review-packet-latest.log"), "-GovernanceRelaxationPreflightLogPath", (& $out "governance-relaxation-preflight-review-packet-latest.log"), "-GovernanceRelaxationReviewLogPath", (& $out "governance-relaxation-review-packet-latest.log"), "-Symbol", $Symbol, "-RequireAuditReady") -OutputPath (& $out "profit-live-blocker-audit-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "profit-operator-next-action-board" -ScriptName "prepare_profit_operator_next_action_board.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-PriorityDecisionLogPath", (& $out "profit-operator-priority-decision-brief-latest.log"), "-Strategy574GateLogPath", (& $out "strategy574-signal-review-gate-refresh.log"), "-TinyLiveLossRcaLogPath", (& $out "tiny-live-loss-rca-refresh.log"), "-NearThresholdShadowObservationLogPath", (& $out "strategy574-near-threshold-shadow-observation-latest.log"), "-AuditLogPath", (& $out "profit-live-blocker-audit-packet-latest.log"), "-MaxAgeMinutes", "$MatrixMaxAgeMinutes", "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-Strategy574Id", "574", "-RequireAudit", "-RequireReady") -OutputPath (& $out "profit-operator-next-action-board-latest.log") -Ssh $false -Required $true
)

$plan = [pscustomobject]@{
    packetType = "PROFIT_LIVE_BLOCKER_SOURCE_REFRESH_PLAN"
    reviewOutputDir = $ReviewOutputDir
    symbol = $Symbol
    strategyId = $StrategyId
    entryDedupHours = $EntryDedupHours
    entryDedupForwardHours = $EntryDedupForwardHours
    entryDedupLimit = $EntryDedupLimit
    stepTimeoutSeconds = $StepTimeoutSeconds
    reuseLatestProfitOperatorMatrix = [bool]$ReuseLatestProfitOperatorMatrix
    autoReusedFreshProfitOperatorMatrix = $autoReusedFreshProfitOperatorMatrix
    forceFreshProfitOperatorMatrix = [bool]$ForceFreshProfitOperatorMatrix
    reusedProfitOperatorMatrixPath = $reusedProfitOperatorMatrixPath
    matrixMaxAgeMinutes = $MatrixMaxAgeMinutes
    allowBlockedStepFailures = [bool]$AllowBlockedStepFailures
    stepCount = @($steps).Count
    sshStepCount = @($steps | Where-Object { $_.usesSsh }).Count
    localStepCount = @($steps | Where-Object { -not $_.usesSsh }).Count
    steps = @($steps)
    allowedActions = @("read-only SSH/MCP/SELECT evidence refresh", "local packet assembly", "operator review")
    forbiddenActions = @("deploy", "production env change", "enable live trading", "enable scheduler", "place orders", "execute TinyLive", "send Telegram", "modify or cancel OCO", "close positions", "mutate DB/grid/fund/Earn/exchange/external backfill state", "relax EntryDedup/DataFreshness/live policy")
    notAuthorization = "read-only source refresh orchestration only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-live-blocker-source-refresh] read-only refresh orchestration"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/MCP/SELECT evidence scripts and local packet assembly only; no deploy, production env, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, restart, or nginx state changed."
Write-Host "profit_live_blocker_source_refresh_step_count=$(@($steps).Count)"
Write-Host "profit_live_blocker_source_refresh_ssh_step_count=$(@($steps | Where-Object { $_.usesSsh }).Count)"
Write-Host "profit_live_blocker_source_refresh_local_step_count=$(@($steps | Where-Object { -not $_.usesSsh }).Count)"
Write-Host "profit_live_blocker_source_refresh_reuse_latest_profit_operator_matrix=$([bool]$ReuseLatestProfitOperatorMatrix)"
Write-Host "profit_live_blocker_source_refresh_auto_reused_fresh_profit_operator_matrix=$autoReusedFreshProfitOperatorMatrix"
Write-Host "profit_live_blocker_source_refresh_force_fresh_profit_operator_matrix=$([bool]$ForceFreshProfitOperatorMatrix)"
Write-Host "profit_live_blocker_source_refresh_allow_blocked_step_failures=$([bool]$AllowBlockedStepFailures)"
Write-Host "entry_dedup_refresh_hours=$EntryDedupHours"
Write-Host "entry_dedup_refresh_forward_hours=$EntryDedupForwardHours"
Write-Host "entry_dedup_refresh_limit=$EntryDedupLimit"
Write-Host "profit_live_blocker_source_refresh_step_timeout_seconds=$StepTimeoutSeconds"
if ($ReuseLatestProfitOperatorMatrix) {
    Write-Host "profit_live_blocker_source_refresh_reused_profit_operator_matrix_path=$reusedProfitOperatorMatrixPath"
}
Write-Host ("profit_live_blocker_source_refresh_plan=" + (ConvertTo-Json -Compress -Depth 8 $plan))
Write-Host "notAuthorization=read-only source refresh orchestration only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"

if ($PlanOnly) {
    Write-Host "profit_live_blocker_source_refresh_status=PLAN_ONLY_NOT_EXECUTED"
    return
}

New-Item -ItemType Directory -Force -Path $reviewDir | Out-Null
$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit live blocker source refresh." }

$results = [System.Collections.Generic.List[object]]::new()
foreach ($step in $steps) {
    try {
        $result = Invoke-RefreshStep -Step $step -PowerShellCommand $powerShell -TimeoutSeconds $StepTimeoutSeconds
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
$auditStep = @($steps | Where-Object { $_.name -eq "profit-live-blocker-audit" } | Select-Object -Last 1)
$auditResult = @($results | Where-Object { $_.name -eq "profit-live-blocker-audit" } | Select-Object -Last 1)
$auditStatus = ""
$auditConclusion = ""
if ($auditStep) {
    $auditOutputPath = if ([System.IO.Path]::IsPathRooted([string]$auditStep.outputPath)) {
        [string]$auditStep.outputPath
    } else {
        Join-Path $repoRoot ([string]$auditStep.outputPath)
    }
    if (Test-Path -LiteralPath $auditOutputPath) {
        $auditText = Get-Content -Raw -LiteralPath $auditOutputPath
        $auditStatus = Get-LastPrefixedValue -Text $auditText -Prefix "profit_live_blocker_audit_status="
        $auditConclusion = Get-LastPrefixedValue -Text $auditText -Prefix "profit_live_readiness_conclusion="
    }
}
$blockedStepFailuresAllowed = $AllowBlockedStepFailures -and $ContinueOnStepFailure -and $auditResult -and $auditResult.success -and $auditStatus -eq "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT"
$status = if ($failed.Count -eq 0) {
    "COMPLETE_REFRESHED_SOURCES_NOT_LIVE_READY"
} elseif ($blockedStepFailuresAllowed) {
    "COMPLETE_REFRESHED_SOURCES_WITH_BLOCKED_LANES_NOT_LIVE_READY"
} else {
    "INCOMPLETE_REFRESH_FAILED_STEPS"
}
Write-Host ("profit_live_blocker_source_refresh_results=" + (ConvertTo-Json -Compress -Depth 6 @($results)))
Write-Host "profit_live_blocker_source_refresh_failed_count=$($failed.Count)"
Write-Host "profit_live_blocker_source_refresh_audit_status=$auditStatus"
Write-Host "profit_live_blocker_source_refresh_audit_conclusion=$auditConclusion"
Write-Host "profit_live_blocker_source_refresh_blocked_step_failures_allowed=$blockedStepFailuresAllowed"
Write-Host "profit_live_blocker_source_refresh_status=$status"

if ($failed.Count -gt 0 -and -not $blockedStepFailuresAllowed) {
    throw "Profit live blocker source refresh failed step count=$($failed.Count)"
}
