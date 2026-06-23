Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1"
$sourcePath = Join-Path $PSScriptRoot "prepare_tp_sl_oco_feasibility_operator_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$sourceText = Get-Content -Raw -LiteralPath $sourcePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[tp-sl-oco-feasibility-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_tp_sl_oco_feasibility_operator_packet.ps1",
        "TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION",
        "PREPARE_REVIEW_ONLY_TP_SL_OCO_FEASIBILITY_REVIEW",
        "tp_sl_oco_feasibility_preflight_review_packet",
        "tp_sl_oco_feasibility_preflight_status",
        "close_position_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only TP/SL/OCO feasibility preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "TP/SL/OCO preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "TP_SL_OCO_FEASIBILITY_OPERATOR_PACKET",
        "READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION",
        "tp_sl_oco_feasibility_operator_packet"
    )) {
    Assert-Contains -Name "TP/SL/OCO source packet supports preflight" -Text $sourceText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1",
        "TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET",
        "tp_sl_oco_feasibility_preflight_review_packet",
        "TP/SL/OCO feasibility preflight review packet",
        "READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION"
    )) {
    Assert-Contains -Name "docs mention TP/SL/OCO preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("tp-sl-oco-preflight-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $briefPacket = [pscustomobject]@{
        packetType = "EXIT_SIDE_OPERATOR_DECISION_BRIEF"
        status = "READY_FOR_OPERATOR_DECISION_NOT_MUTATION"
        symbol = "BTCUSDT"
        strategyId = 485
        decisionLanes = @(
            [pscustomobject]@{ proposalId = "trailing-stop-rollout-review"; lane = "trailing-stop-rollout"; status = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" },
            [pscustomobject]@{ proposalId = "strategy485-risk-reduction-review"; lane = "strategy485-risk-reduction"; status = "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION" }
        )
        evidenceSummary = [pscustomobject]@{
            trailingStopAcceptance = "PASS"
            trailingStopImprovementPct = "54.044%"
            strategy485OcoHealthOk = "True"
            strategy485NegativeEvPositionCount = "3"
        }
        missingRequirements = @()
        notAuthorization = "read-only"
    }
    $positions = @(
        [pscustomobject]@{ positionId = 148; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.32"; paperPct = "-4.18" },
        [pscustomobject]@{ positionId = 149; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.31"; paperPct = "-4.12" },
        [pscustomobject]@{ positionId = 150; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.22"; paperPct = "-3.76" }
    )
    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[exit-side-operator-decision-brief] read-only packet",
        "scope=READ_ONLY",
        "exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION",
        "trailing_stop_acceptance=PASS",
        "trailing_stop_improvement_pct=54.044%",
        "strategy485_oco_health_ok=True",
        "strategy485_negative_ev_position_count=3",
        ("strategy485_position_summaries=" + (ConvertTo-Json -Compress -Depth 5 @($positions))),
        ("exit_side_operator_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 8 $briefPacket)),
        "exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for TP/SL/OCO preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExitSideDecisionLogPath $tempLogPath -ReviewNotionalCapUsdt 15 -ObservationHours 48 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) { throw "TP/SL/OCO preflight failed temp-log reuse:`n$text" }
    foreach ($marker in @(
            "source_operator_packet_status=READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION",
            "source_exit_side_decision_log_freshness=FRESH",
            "source_tp_sl_oco_feasibility_decision=PREPARE_SEPARATE_TP_SL_OCO_FEASIBILITY_REVIEW",
            "trailing_stop_acceptance=PASS",
            "strategy485_oco_health_ok=True",
            "strategy485_negative_ev_position_count=3",
            "tp_sl_oco_feasibility_preflight_decision=PREPARE_REVIEW_ONLY_TP_SL_OCO_FEASIBILITY_REVIEW",
            "tp_sl_oco_feasibility_preflight_status=READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION",
            '"packetType":"TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET"',
            '"status":"READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION"',
            '"preflightDecision":"PREPARE_REVIEW_ONLY_TP_SL_OCO_FEASIBILITY_REVIEW"',
            '"orderAllowed":false',
            '"positionOrOcoMutationAllowed":false',
            '"telegramSendAllowed":false',
            '"reviewNotionalCapUsdt":15',
            '"observationHours":48',
            "close_position_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only TP/SL/OCO feasibility preflight review packet only"
        )) {
        Assert-Contains -Name "TP/SL/OCO preflight temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "TP/SL/OCO preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) { Remove-Item -LiteralPath $tempLogPath -Force }
}

Write-Host "[tp-sl-oco-feasibility-preflight-review-packet-test] OK"
