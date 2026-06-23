Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_collector_activation_preflight_review_packet.ps1"
$decisionPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_collector_activation_packet.ps1"
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
        "[data-freshness-collector-activation-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_data_freshness_replay_collector_activation_packet.ps1",
        "DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE",
        "PREPARE_REVIEW_ONLY_EVIDENCE_COLLECTOR_ACTIVATION",
        "data_freshness_collector_activation_preflight_review_packet",
        "data_freshness_collector_activation_preflight_status",
        "evidence_only_collector_review_allowed=true",
        "collector_activation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "data_freshness_policy_relaxation_allowed=false",
        "data_freshness_shadow_review_allowed=false",
        "staged_add_execution_allowed=false",
        "tiny_live_execution_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only DataFreshness collector activation preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "DataFreshness collector activation preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET",
        "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE",
        "data_freshness_collector_activation_packet",
        "PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW"
    )) {
    Assert-Contains -Name "DataFreshness collector activation decision supports preflight" -Text $decisionText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_data_freshness_collector_activation_preflight_review_packet.ps1",
        "DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_PACKET",
        "data_freshness_collector_activation_preflight_review_packet",
        "DataFreshness collector activation preflight review packet",
        "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention DataFreshness collector activation preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-collector-activation-preflight-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[data-freshness-replay-evidence-readiness] read-only packet",
        "scope=READ_ONLY; invokes smoke_data_freshness_replay_observation_bundle_ssh.ps1 and conditionally smoke_data_freshness_sample_gap_rca_ssh.ps1 only",
        "data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS",
        "latest_data_freshness_row_time=2026-06-14T15:38:16",
        "latest_data_freshness_row_age_hours=214.03",
        "data_freshness_rows_1d=0",
        "data_freshness_rows_3d=0",
        "data_freshness_rows_7d=0",
        "data_freshness_sample_gap_status=NO_ROWS_IN_REVIEW_WINDOW",
        "data_freshness_sample_gap_rca_recommendation=WAIT_FOR_BUY_STYLE_DATAFRESHNESS_SAMPLE",
        "data_freshness_counterfactual_recommendation=COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING",
        "replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "replay_input_next_action=wait_for_new_replay_id_rows_before_shadow_review",
        "collector_status_counts=N/A:74",
        "hard_gate_preview_status_counts=N/A:74",
        "complete_replayable_candidate_rows=0",
        "missing_counterfactual_fields=[`"liveSignalId`",`"replayCandidateId`",`"explicit entry/TP/SL candidate plan`",`"EV snapshot`",`"OCO plan`",`"complete replayable candidate rows`"]",
        "data_freshness_replay_evidence_blockers=[`"FRESH_DATAFRESHNESS_REPLAY_ROWS_MISSING`",`"PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE`",`"COUNTERFACTUAL_REPLAY_SNAPSHOTS_MISSING`"]",
        "data_freshness_replay_evidence_required=[`"fresh DataFreshnessGuard terminal rows after replay-id runtime`",`"complete_replayable_candidate_rows > 0`"]",
        "data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for DataFreshness collector activation preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReadinessLogPath $tempLogPath -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "DataFreshness collector activation preflight failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "source_decision_packet_status=READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE",
            "source_collector_activation_operator_decision=PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW",
            "source_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS",
            "source_replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "source_complete_replayable_candidate_rows=0",
            "data_freshness_collector_activation_preflight_decision=PREPARE_REVIEW_ONLY_EVIDENCE_COLLECTOR_ACTIVATION",
            "data_freshness_collector_activation_preflight_status=READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE",
            '"packetType":"DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_PACKET"',
            '"preflightDecision":"PREPARE_REVIEW_ONLY_EVIDENCE_COLLECTOR_ACTIVATION"',
            '"evidenceOnlyCollectorReviewAllowed":true',
            '"collectorActivationAllowed":false',
            '"deployOrEnvChangeAllowed":false',
            '"dataFreshnessPolicyRelaxationAllowed":false',
            '"dataFreshnessShadowReviewAllowed":false',
            '"tinyLiveExecutionAllowed":false',
            '"telegramSendAllowed":false',
            "evidence_only_collector_review_allowed=true",
            "collector_activation_allowed=false",
            "deploy_or_env_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only DataFreshness collector activation preflight review packet only"
        )) {
        Assert-Contains -Name "DataFreshness collector activation preflight temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness collector activation preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) { Remove-Item -LiteralPath $tempLogPath -Force }
}

Write-Host "[data-freshness-collector-activation-preflight-review-packet-test] OK"
