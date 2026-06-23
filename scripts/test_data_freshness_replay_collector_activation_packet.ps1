Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_collector_activation_packet.ps1"
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
        "[data-freshness-replay-collector-activation-packet] read-only packet",
        "scope=READ_ONLY",
        "DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET",
        "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE",
        "PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
        "collector_activation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "data_freshness_policy_relaxation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "notAuthorization=read-only DataFreshness collector activation decision packet only",
        "RequireDecisionReady"
    )) {
    Assert-Contains -Name "DataFreshness collector activation packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_data_freshness_replay_collector_activation_packet.ps1",
        "DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET",
        "data_freshness_collector_activation_packet",
        "DataFreshness replay collector activation decision packet",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
    )) {
    Assert-Contains -Name "docs mention DataFreshness collector activation packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("datafreshness-collector-activation-" + [guid]::NewGuid().ToString("N") + ".log")
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
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness collector activation packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReadinessLogPath $tempLogPath -RequireDecisionReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "DataFreshness collector activation packet failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS",
            "data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS",
            "data_freshness_rows_1d=0",
            "data_freshness_rows_3d=0",
            "replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
            "complete_replayable_candidate_rows=0",
            "collector_activation_operator_decision=PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW",
            "evidence_only_collector_flag=TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
            "default_collector_flag=TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
            "collector_activation_allowed=false",
            "deploy_or_env_change_allowed=false",
            "order_allowed=false",
            "data_freshness_collector_activation_status=READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE",
            '"packetType":"DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET"',
            '"operatorDecision":"PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW"',
            '"completeReplayableCandidateRows":0',
            "notAuthorization=read-only DataFreshness collector activation decision packet only"
        )) {
        Assert-Contains -Name "DataFreshness collector activation temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness collector activation packet unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) {
        Remove-Item -LiteralPath $tempLogPath -Force
    }
}

Write-Host "[data-freshness-replay-collector-activation-packet-test] OK"
