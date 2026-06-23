Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy485_risk_reduction_preflight_review_packet.ps1"
$decisionPath = Join-Path $PSScriptRoot "prepare_strategy485_risk_reduction_operator_decision_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$decisionText = Get-Content -Raw -LiteralPath $decisionPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[strategy485-risk-reduction-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_strategy485_risk_reduction_operator_decision_packet.ps1",
        "STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION",
        "PREPARE_REVIEW_ONLY_RISK_REDUCTION_OPERATOR_REVIEW",
        "strategy485_risk_reduction_preflight_review_packet",
        "strategy485_risk_reduction_preflight_status",
        "close_position_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only strategy485 risk-reduction preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "strategy485 risk preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_PACKET",
        "READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION",
        "strategy485_risk_reduction_operator_decision_packet"
    )) {
    Assert-Contains -Name "strategy485 decision packet supports preflight" -Text $decisionText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy485_risk_reduction_preflight_review_packet.ps1",
        "STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_PACKET",
        "strategy485_risk_reduction_preflight_review_packet",
        "strategy485 risk-reduction preflight review packet",
        "READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION"
    )) {
    Assert-Contains -Name "docs mention strategy485 risk preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy485-risk-preflight-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy485-risk-preflight-review-" + [guid]::NewGuid().ToString("N"))
try {
    $matrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{ lane = "exit-side"; priority = "P1"; status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"; readyForOperatorReview = $true; evidenceMarkers = @("trailing_stop_acceptance=PASS", "strategy485_oco_health_ok=true"); missingRequirements = @(); nextAction = "Attach exit-side packet to a separate operator review." },
            [pscustomobject]@{ lane = "entry-filter"; priority = "P2"; status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"; readyForOperatorReview = $false; evidenceMarkers = @("signal_policy_clear=false"); missingRequirements = @("governance drift and missed-opportunity review"); nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged." },
            [pscustomobject]@{ lane = "data-freshness-replay"; priority = "P2"; status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"; readyForOperatorReview = $false; evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE"); missingRequirements = @("fresh replayCandidateId rows"); nextAction = "Collect replay snapshots before policy review." }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $matrixPacket)),
        "profit_operator_review_matrix_next_action=Review ready read-only items separately."
    )
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    Set-Content -LiteralPath (Join-Path $tempReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $tempMatrixPath

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy485 risk preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -ReviewNotionalCapUsdt 15 -ObservationHours 48 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) { throw "strategy485 risk preflight failed latest-pointer reuse:`n$text" }
    foreach ($marker in @(
            "source_decision_packet_status=READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION",
            "source_matrix_freshness_status=FRESH",
            "strategy485_risk_reduction_preflight_priority_rank=2",
            "strategy485_risk_reduction_preflight_decision=PREPARE_REVIEW_ONLY_RISK_REDUCTION_OPERATOR_REVIEW",
            "strategy485_risk_reduction_preflight_status=READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION",
            '"packetType":"STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_PACKET"',
            '"status":"READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION"',
            '"preflightDecision":"PREPARE_REVIEW_ONLY_RISK_REDUCTION_OPERATOR_REVIEW"',
            '"closePositionAllowed":false',
            '"telegramSendAllowed":false',
            '"reviewNotionalCapUsdt":15',
            '"observationHours":48',
            "close_position_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only strategy485 risk-reduction preflight review packet only"
        )) {
        Assert-Contains -Name "strategy485 risk preflight latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy485 risk preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) { Remove-Item -LiteralPath $tempMatrixPath -Force }
    if (Test-Path -LiteralPath $tempReviewDir) { Remove-Item -LiteralPath $tempReviewDir -Recurse -Force }
}

Write-Host "[strategy485-risk-reduction-preflight-review-packet-test] OK"
