Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_replay_observation_bundle_ssh.ps1"
$replayIdPath = Join-Path $PSScriptRoot "smoke_data_freshness_replay_candidate_id_ssh.ps1"
$counterfactualPath = Join-Path $PSScriptRoot "smoke_data_freshness_counterfactual_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$replayIdText = Get-Content -Raw -LiteralPath $replayIdPath
$counterfactualText = Get-Content -Raw -LiteralPath $counterfactualPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "smoke_live_origin_delta_local.ps1",
        "smoke_data_freshness_replay_candidate_id_ssh.ps1",
        "smoke_data_freshness_counterfactual_review_ssh.ps1",
        "server_worktree_commit",
        "deployment_metadata_status",
        "deployment_runtime_delta_files",
        "deployed_app_commit",
        "DOCS_TOOLING_ONLY_DRIFT",
        "-ExpectedCommit",
        "replay_expected_commit_source",
        "DEPLOYED_APP_COMMIT_RUNTIME_CURRENT",
        "SERVER_WORKTREE_COMMIT_DOCS_TOOLING_ONLY_DRIFT",
        "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_OBSERVATION",
        "WAIT_FOR_NEW_DATAFRESHNESS_SAMPLE",
        "FIX_REPLAY_CANDIDATE_ID_EVIDENCE",
        "REPLAY_CANDIDATE_ID_EVIDENCE_COLLECTED",
        "COLLECT_ENTRY_TP_SL_EV_OCO_REPLAY_SNAPSHOTS",
        "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "DEPLOY_CURRENT_RUNTIME_THEN_OBSERVE_REPLAY_ID",
        "COLLECT_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW",
        "replay_observation_review_items",
        "replay_observation_bundle_recommendation",
        "latest_data_freshness_row_time",
        "latest_data_freshness_row_age_hours",
        "data_freshness_rows_1d",
        "data_freshness_rows_3d",
        "data_freshness_rows_7d",
        "data_freshness_rows_14d",
        "data_freshness_rows_30d",
        "data_freshness_sample_gap_status",
        "replay_input_stage",
        "replay_input_next_action",
        "preview_only_input_rows",
        "preview_only_missing_counterfactual_fields",
        "collector_status_counts",
        "hard_gate_preview_status_counts",
        "notAuthorization",
        "OK read-only check complete",
        "Assert-SshHostSafe"
    )) {
    Assert-Contains -Name "DataFreshness replay observation bundle marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "DEPLOYED_RUNTIME_NOT_CURRENT",
        "REPLAY_CANDIDATE_ID_EVIDENCE_OK",
        "PENDING_NO_NEW_DATAFRESHNESS_ROWS"
    )) {
    Assert-Contains -Name "replay id smoke supports observation bundle" -Text $replayIdText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING",
        "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "complete_replayable_candidate_rows",
        "replay_input_stage",
        "replay_input_next_action",
        "preview_only_input_rows",
        "collector_status_counts"
    )) {
    Assert-Contains -Name "counterfactual smoke supports observation bundle" -Text $counterfactualText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention replay observation bundle" -Text $doc -Pattern "smoke_data_freshness_replay_observation_bundle_ssh\.ps1"
    Assert-Contains -Name "operator docs mention replay observation read-only" -Text $doc -Pattern "read-only"
}

Write-Host "[data-freshness-replay-observation-bundle-test] OK"
