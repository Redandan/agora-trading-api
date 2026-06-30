Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_next_execution_blocker_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-next-execution-blocker-packet] read-only packet",
        "PROFIT_NEXT_EXECUTION_BLOCKER_PACKET",
        "profit_next_execution_blocker_packet",
        "profit_next_execution_blocker_status",
        "profit_next_execution_goal_satisfied=false",
        "TRAILING_STOP_STRATEGY574_OPT_IN",
        "BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
        "AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
        "EXECUTE_TRAILING_STOP_OPT_IN_",
        "BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW",
        "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY",
        "COLLECT_TRAILING_DRY_RUN_OBSERVATION_SAMPLE",
        "strategy574-threshold-or-tinylive-relaxation",
        "data-freshness-entry-policy-relaxation",
        "strategy485-position-risk-mutation",
        "general-live-policy-relaxation",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only profit next execution blocker packet only"
    )) {
    Assert-Contains -Name "profit next execution blocker script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_next_execution_blocker_packet.ps1",
        "PROFIT_NEXT_EXECUTION_BLOCKER_PACKET",
        "profit_next_execution_blocker_packet",
        "profit_next_execution_goal_satisfied=false",
        "BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
        "TRAILING_STOP_STRATEGY574_OPT_IN"
    )) {
    Assert-Contains -Name "docs mention profit next execution blocker packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempExecutionLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-execution-" + [guid]::NewGuid().ToString("N") + ".log")
$tempStrategy574Log = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-strategy574-" + [guid]::NewGuid().ToString("N") + ".log")
$tempDfLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-df-" + [guid]::NewGuid().ToString("N") + ".log")
$tempStrategy485Log = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-485-" + [guid]::NewGuid().ToString("N") + ".log")
$tempSignalLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-next-signal-" + [guid]::NewGuid().ToString("N") + ".log")

try {
    $executionPacket = [pscustomobject]@{
        packetType = "TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET"
        status = "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION"
        symbol = "BTCUSDT"
        strategyId = 574
        executeRequested = $false
        requiredConfirmText = "EXECUTE_TRAILING_STOP_OPT_IN_574"
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        strategyOptInWritePerformed = $false
        nextRequiredAuthorization = "rerun with -Execute -ConfirmText EXECUTE_TRAILING_STOP_OPT_IN_574 to perform only the reviewed setTrailingStopOptIn write"
        missingRequirements = @()
    }
    Set-Content -LiteralPath $tempExecutionLog -Encoding UTF8 -Value @(
        "trailing_stop_strategy_opt_in_execution_status=DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION",
        "trailing_stop_strategy_opt_in_execution_decision=AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
        "trailing_stop_strategy_opt_in_execution_required_confirm_text=EXECUTE_TRAILING_STOP_OPT_IN_574",
        "trailing_stop_acceptance=PASS",
        "trailing_stop_improvement_pct=52.753%",
        "trailing_stop_delta_pnl=13391.79229093",
        ("trailing_stop_strategy_opt_in_execution_packet=" + (ConvertTo-Json -Compress -Depth 8 $executionPacket))
    )

    $strategy574Packet = [pscustomobject]@{
        packetType = "STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET"
        status = "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE"
        riskPosture = "BLOCKED_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH"
    }
    Set-Content -LiteralPath $tempStrategy574Log -Encoding UTF8 -Value @(
        "strategy574_tiny_live_governance_status=READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE",
        "strategy574_tiny_live_risk_posture=BLOCKED_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH",
        "strategy574_near_threshold_shadow_recommendation=STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH",
        "strategy574_near_threshold_false_positive_rate_pct=86.67",
        "tiny_live_can_enable_production=false",
        "strategy574_near_threshold_threshold_relaxation_allowed=false",
        ("strategy574_tiny_live_governance_operator_packet=" + (ConvertTo-Json -Compress -Depth 8 $strategy574Packet))
    )

    $dfPacket = [pscustomobject]@{
        packetType = "DATAFRESHNESS_REPLAY_EVIDENCE_READINESS_PACKET"
        status = "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS"
    }
    Set-Content -LiteralPath $tempDfLog -Encoding UTF8 -Value @(
        "data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS",
        "replay_candidate_id_rows=0",
        "complete_replayable_candidate_rows=0",
        "latest_data_freshness_row_age_hours=360",
        ("data_freshness_replay_evidence_readiness_packet=" + (ConvertTo-Json -Compress -Depth 8 $dfPacket))
    )

    Set-Content -LiteralPath $tempStrategy485Log -Encoding UTF8 -Value @(
        "  negativeEvPositions=0",
        "  strategy485_position_risk_recommendation=NO_OPEN_STRATEGY485_POSITION",
        '  strategy485_position_review_decision={"decision":"NO_OPEN_STRATEGY485_POSITION","negativeEvPositionCount":0}'
    )

    Set-Content -LiteralPath $tempSignalLog -Encoding UTF8 -Value @(
        "signalPolicyClear=false",
        "recommendedFix=No regression fix required; current no-buy reasons are expected under configured gates."
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit next execution blocker packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ExecutionLogPath $tempExecutionLog `
            -Strategy574GovernanceLogPath $tempStrategy574Log `
            -DataFreshnessReadinessLogPath $tempDfLog `
            -Strategy485RiskLogPath $tempStrategy485Log `
            -SignalCorrectnessLogPath $tempSignalLog `
            -NoRefresh `
            -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "profit next execution blocker packet failed dry-run replay:`n$text"
    }

    foreach ($marker in @(
            "profit_next_execution_goal_satisfied=false",
            "profit_next_execution_route=TRAILING_STOP_STRATEGY574_OPT_IN",
            "profit_next_execution_source_status=DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION",
            "profit_next_execution_source_decision=AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
            "profit_next_execution_trailing_acceptance=PASS",
            "profit_next_execution_trailing_improvement_pct=52.753%",
            "profit_next_execution_unique_blocker=AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
            "profit_next_execution_exact_unlock_command=.\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId 574 -Execute -ConfirmText EXECUTE_TRAILING_STOP_OPT_IN_574 -RequireReady",
            "strategy574_near_threshold_shadow_recommendation=STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH",
            "strategy574_near_threshold_false_positive_rate_pct=86.67",
            "tiny_live_can_enable_production=false",
            "data_freshness_replay_candidate_id_rows=0",
            "data_freshness_complete_replayable_candidate_rows=0",
            "strategy485_negative_ev_positions=0",
            '"packetType":"PROFIT_NEXT_EXECUTION_BLOCKER_PACKET"',
            '"goalSatisfied":false',
            '"profitRoute":"TRAILING_STOP_STRATEGY574_OPT_IN"',
            '"uniqueBlocker":"AWAIT_EXPLICIT_EXECUTE_CONFIRMATION"',
            '"route":"strategy574-threshold-or-tinylive-relaxation"',
            '"currentVerdict":"NOT_RECOMMENDED"',
            '"route":"data-freshness-entry-policy-relaxation"',
            '"currentVerdict":"BLOCKED"',
            "profit_next_execution_blocker_status=BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only profit next execution blocker packet only"
        )) {
        Assert-Contains -Name "profit next execution blocker dry-run replay" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "mcp_write_status=OK|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit next execution blocker dry-run replay unexpectedly invoked remote write or SSH:`n$text"
    }

    $executionPacket.status = "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW"
    $executionPacket.strategyOptInWritePerformed = $true
    Set-Content -LiteralPath $tempExecutionLog -Encoding UTF8 -Value @(
        "trailing_stop_strategy_opt_in_execution_status=EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW",
        "trailing_stop_strategy_opt_in_execution_decision=REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "trailing_stop_strategy_opt_in_execution_required_confirm_text=EXECUTE_TRAILING_STOP_OPT_IN_574",
        "trailing_stop_acceptance=PASS",
        "trailing_stop_improvement_pct=52.753%",
        "trailing_stop_delta_pnl=13391.79229093",
        ("trailing_stop_strategy_opt_in_execution_packet=" + (ConvertTo-Json -Compress -Depth 8 $executionPacket))
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $postOptInOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ExecutionLogPath $tempExecutionLog `
            -Strategy574GovernanceLogPath $tempStrategy574Log `
            -DataFreshnessReadinessLogPath $tempDfLog `
            -Strategy485RiskLogPath $tempStrategy485Log `
            -SignalCorrectnessLogPath $tempSignalLog `
            -NoRefresh `
            -RequireReady 2>&1
        $postOptInExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $postOptInText = ($postOptInOutput | Out-String -Width 4096)
    if ($postOptInExitCode -ne 0) {
        throw "profit next execution blocker post-opt-in replay failed:`n$postOptInText"
    }
    foreach ($marker in @(
            "profit_next_execution_source_status=EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW",
            "profit_next_execution_unique_blocker=AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
            "profit_next_execution_blocker_status=BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
            "Request separate env/deploy authorization for TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true"
        )) {
        Assert-Contains -Name "profit next execution blocker post-opt-in replay" -Text $postOptInText -Pattern ([regex]::Escape($marker))
    }

    $executionPacket.status = "ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW"
    $executionPacket.strategyOptInWritePerformed = $false
    Set-Content -LiteralPath $tempExecutionLog -Encoding UTF8 -Value @(
        "trailing_stop_strategy_opt_in_execution_status=ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW",
        "trailing_stop_strategy_opt_in_execution_decision=REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "trailing_stop_strategy_opt_in_execution_required_confirm_text=EXECUTE_TRAILING_STOP_OPT_IN_574",
        "trailing_stop_acceptance=PASS",
        "trailing_stop_improvement_pct=52.753%",
        "trailing_stop_delta_pnl=13391.79229093",
        ("trailing_stop_strategy_opt_in_execution_packet=" + (ConvertTo-Json -Compress -Depth 8 $executionPacket))
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $alreadyOptedInOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ExecutionLogPath $tempExecutionLog `
            -Strategy574GovernanceLogPath $tempStrategy574Log `
            -DataFreshnessReadinessLogPath $tempDfLog `
            -Strategy485RiskLogPath $tempStrategy485Log `
            -SignalCorrectnessLogPath $tempSignalLog `
            -NoRefresh `
            -RequireReady 2>&1
        $alreadyOptedInExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $alreadyOptedInText = ($alreadyOptedInOutput | Out-String -Width 4096)
    if ($alreadyOptedInExitCode -ne 0) {
        throw "profit next execution blocker already-opted-in replay failed:`n$alreadyOptedInText"
    }
    foreach ($marker in @(
            "profit_next_execution_source_status=ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW",
            "profit_next_execution_unique_blocker=AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
            "profit_next_execution_blocker_status=BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
            "Strategy opt-in is already applied; request separate trailing dry-run env diff and deploy authorization."
        )) {
        Assert-Contains -Name "profit next execution blocker already-opted-in replay" -Text $alreadyOptedInText -Pattern ([regex]::Escape($marker))
    }
} finally {
    foreach ($path in @($tempExecutionLog, $tempStrategy574Log, $tempDfLog, $tempStrategy485Log, $tempSignalLog)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }
}

Write-Host "[profit-next-execution-blocker-packet-test] OK"
