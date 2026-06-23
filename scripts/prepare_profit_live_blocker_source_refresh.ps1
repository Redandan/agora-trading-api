param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$MatrixTimeoutSeconds = 3900,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$PlanOnly,
    [switch]$ContinueOnStepFailure
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
    param([object]$Step, [object]$PowerShellCommand)

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
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $PowerShellCommand.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @($Step.arguments) 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String -Width 4096)
    Set-Content -LiteralPath $outputPath -Encoding UTF8 -Value $text
    if ($null -eq $exitCode) { $exitCode = 0 }

    Write-Host ("[profit-live-blocker-source-refresh] step_complete name={0} exitCode={1} output={2}" -f $Step.name, $exitCode, $Step.outputPath)
    return [pscustomobject]@{
        name = $Step.name
        script = $Step.script
        outputPath = $Step.outputPath
        exitCode = [int]$exitCode
        success = ([int]$exitCode -eq 0)
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
Assert-TokenSafe -Name "Symbol" -Value $Symbol
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($MatrixTimeoutSeconds -lt 60 -or $MatrixTimeoutSeconds -gt 7200) { throw "MatrixTimeoutSeconds must be between 60 and 7200." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$reviewDir = if ([System.IO.Path]::IsPathRooted($ReviewOutputDir)) { $ReviewOutputDir } else { Join-Path $repoRoot $ReviewOutputDir }
$out = { param([string]$Name) (Join-Path $ReviewOutputDir $Name) }

$steps = @(
    New-Step -Name "profit-operator-action-brief" -ScriptName "prepare_profit_operator_action_brief_ssh.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-MatrixTimeoutSeconds", "$MatrixTimeoutSeconds", "-ChildTimeoutSeconds", "$ChildTimeoutSeconds", "-RequireReady") -OutputPath (& $out "profit-operator-action-brief-latest.log") -Ssh $true -Required $true
    New-Step -Name "profit-operator-priority-decision" -ScriptName "prepare_profit_operator_priority_decision_brief.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "profit-operator-priority-decision-brief-latest.log") -Ssh $false -Required $true
    New-Step -Name "trailing-stop-dry-run-decision" -ScriptName "prepare_trailing_stop_dry_run_operator_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "trailing-stop-dry-run-operator-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "strategy485-risk-reduction-decision" -ScriptName "prepare_strategy485_risk_reduction_operator_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "strategy485-risk-reduction-operator-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "entry-dedup-semantics-decision" -ScriptName "prepare_entry_dedup_semantics_operator_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-PriorityStrategyId", "$StrategyId", "-RequireReady") -OutputPath (& $out "entry-dedup-semantics-operator-decision-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "data-freshness-replay-blocker-decision" -ScriptName "prepare_data_freshness_replay_blocker_decision_packet.ps1" -Arguments @("-ReviewOutputDir", $ReviewOutputDir, "-Symbol", $Symbol, "-RequireBlocked") -OutputPath (& $out "data-freshness-replay-blocker-decision-packet-latest.log") -Ssh $false -Required $true
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
    New-Step -Name "governance-relaxation-review" -ScriptName "prepare_governance_relaxation_review_packet_ssh.ps1" -Arguments @("-RequireReview") -OutputPath (& $out "governance-relaxation-review-packet-latest.log") -Ssh $true -Required $true
    New-Step -Name "governance-relaxation-preflight" -ScriptName "prepare_governance_relaxation_preflight_review_packet.ps1" -Arguments @("-ReviewLogPath", (& $out "governance-relaxation-review-packet-latest.log"), "-RequireReady") -OutputPath (& $out "governance-relaxation-preflight-review-packet-latest.log") -Ssh $false -Required $true
    New-Step -Name "profit-live-blocker-audit" -ScriptName "prepare_profit_live_blocker_audit_packet.ps1" -Arguments @("-PriorityDecisionLogPath", (& $out "profit-operator-priority-decision-brief-latest.log"), "-TrailingDryRunLogPath", (& $out "trailing-stop-dry-run-operator-decision-packet-latest.log"), "-Strategy485RiskLogPath", (& $out "strategy485-risk-reduction-operator-decision-packet-latest.log"), "-Strategy485RiskEscalationLogPath", (& $out "strategy485-risk-escalation-brief-latest.log"), "-EntryDedupLogPath", (& $out "entry-dedup-semantics-operator-decision-packet-latest.log"), "-DataFreshnessReplayBlockerLogPath", (& $out "data-freshness-replay-blocker-decision-packet-latest.log"), "-DataFreshnessCollectorLogPath", (& $out "data-freshness-replay-collector-activation-packet-latest.log"), "-TpSlOcoLogPath", (& $out "tp-sl-oco-feasibility-operator-packet-latest.log"), "-Strategy574TinyLivePreflightLogPath", (& $out "strategy574-tiny-live-governance-preflight-review-packet-latest.log"), "-GovernanceRelaxationPreflightLogPath", (& $out "governance-relaxation-preflight-review-packet-latest.log"), "-GovernanceRelaxationReviewLogPath", (& $out "governance-relaxation-review-packet-latest.log"), "-Symbol", $Symbol, "-RequireAuditReady") -OutputPath (& $out "profit-live-blocker-audit-packet-latest.log") -Ssh $false -Required $true
)

$plan = [pscustomobject]@{
    packetType = "PROFIT_LIVE_BLOCKER_SOURCE_REFRESH_PLAN"
    reviewOutputDir = $ReviewOutputDir
    symbol = $Symbol
    strategyId = $StrategyId
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
$status = if ($failed.Count -eq 0) { "COMPLETE_REFRESHED_SOURCES_NOT_LIVE_READY" } else { "INCOMPLETE_REFRESH_FAILED_STEPS" }
Write-Host ("profit_live_blocker_source_refresh_results=" + (ConvertTo-Json -Compress -Depth 6 @($results)))
Write-Host "profit_live_blocker_source_refresh_failed_count=$($failed.Count)"
Write-Host "profit_live_blocker_source_refresh_status=$status"

if ($failed.Count -gt 0) {
    throw "Profit live blocker source refresh failed step count=$($failed.Count)"
}
