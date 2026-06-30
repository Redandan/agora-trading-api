Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy485_risk_escalation_brief.ps1"
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
        "[strategy485-risk-escalation-brief] read-only brief",
        "scope=READ_ONLY",
        "STRATEGY485_RISK_ESCALATION_BRIEF",
        "READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION",
        "NO_POSITION_RISK_ACTION",
        "WATCH_ONLY",
        "CURRENT_POSITION_RISK_ESCALATED_SEVERE_PAPER_LOSS",
        "strategy485_risk_escalation_brief_packet",
        "strategy485_risk_escalation_brief_status",
        "strategy485_risk_escalation_no_action",
        "strategy485_severe_paper_loss_count",
        "strategy485_total_ev_usdt",
        "strategy485_worst_paper_pct",
        "strategy485_position_risk_rows",
        "close_position_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only strategy485 risk escalation brief only",
        "RequireReady"
    )) {
    Assert-Contains -Name "strategy485 risk escalation marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy485_risk_escalation_brief.ps1",
        "STRATEGY485_RISK_ESCALATION_BRIEF",
        "strategy485_risk_escalation_brief_packet",
        "strategy485 risk escalation brief"
    )) {
    Assert-Contains -Name "docs mention strategy485 risk escalation brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLog = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy485-risk-escalation-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $packet = [pscustomobject]@{
        packetType = "EXIT_SIDE_OPERATOR_DECISION_BRIEF"
        status = "READY_FOR_OPERATOR_DECISION_NOT_MUTATION"
        evidenceSummary = [pscustomobject]@{
            strategy485PositionSummaries = @(
                [pscustomobject]@{ positionId = 148; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.54"; paperPct = "-6.85" },
                [pscustomobject]@{ positionId = 149; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.53"; paperPct = "-6.79" },
                [pscustomobject]@{ positionId = 150; decision = "WATCH"; suggestion = "CLOSE"; evUsdt = "-0.39"; paperPct = "-6.43" }
            )
        }
    }
    Set-Content -LiteralPath $tempLog -Encoding UTF8 -Value @(
        "[exit-side-operator-decision-brief] read-only brief",
        "exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION",
        "trailing_stop_acceptance=PASS",
        "strategy485_oco_health_ok=True",
        "strategy485_negative_ev_position_count=3",
        "strategy485_close_or_modify_suggestion_count=3",
        ("exit_side_operator_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet)),
        "exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "notAuthorization=read-only exit-side operator decision brief only"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy485 risk escalation test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExitSideDecisionLogPath $tempLog -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "strategy485 risk escalation brief failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "strategy485_severe_paper_loss_count=3",
            "strategy485_total_ev_usdt=-1.46",
            "strategy485_worst_paper_pct=-6.85",
            "strategy485_avg_paper_pct=-6.69",
            "strategy485_risk_escalation_class=CURRENT_POSITION_RISK_ESCALATED_SEVERE_PAPER_LOSS",
            "strategy485_risk_escalation_brief_status=READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION",
            '"packetType":"STRATEGY485_RISK_ESCALATION_BRIEF"',
            '"severePaperLossCount":3',
            '"totalEvUsdt":"-1.46"',
            '"riskBucket":"SEVERE_PAPER_LOSS"',
            '"closePositionAllowed":false',
            '"positionOrOcoMutationAllowed":false',
            "close_position_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "notAuthorization=read-only strategy485 risk escalation brief only"
        )) {
        Assert-Contains -Name "strategy485 risk escalation temp-log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy485 risk escalation unexpectedly invoked SSH or a fresh child run:`n$text"
    }

    $noActionPacket = [pscustomobject]@{
        packetType = "EXIT_SIDE_OPERATOR_DECISION_BRIEF"
        status = "READY_FOR_OPERATOR_DECISION_NOT_MUTATION"
        evidenceSummary = [pscustomobject]@{
            strategy485PositionSummaries = @()
        }
    }
    Set-Content -LiteralPath $tempLog -Encoding UTF8 -Value @(
        "[exit-side-operator-decision-brief] read-only brief",
        "exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION",
        "trailing_stop_acceptance=PASS",
        "strategy485_oco_health_ok=True",
        "strategy485_negative_ev_position_count=0",
        "strategy485_close_or_modify_suggestion_count=0",
        ("exit_side_operator_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 8 $noActionPacket)),
        "exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "notAuthorization=read-only exit-side operator decision brief only"
    )

    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExitSideDecisionLogPath $tempLog -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "strategy485 risk escalation no-action case failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "strategy485_negative_ev_position_count=0",
            "strategy485_close_or_modify_suggestion_count=0",
            "strategy485_risk_escalation_class=NO_POSITION_RISK_EVIDENCE",
            "strategy485_risk_escalation_no_action=true",
            "strategy485_risk_escalation_missing_requirements=[]",
            "strategy485_risk_escalation_brief_status=NO_POSITION_RISK_ACTION",
            '"status":"NO_POSITION_RISK_ACTION"',
            '"noAction":true',
            '"positionRiskRows":[]',
            "close_position_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "strategy485 risk escalation no-action temp-log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy485 risk escalation no-action unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLog) {
        Remove-Item -LiteralPath $tempLog -Force
    }
}

Write-Host "[strategy485-risk-escalation-brief-test] OK"
