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
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_replay_candidate_id_ssh.ps1"
$counterfactualPath = Join-Path $PSScriptRoot "smoke_data_freshness_counterfactual_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$counterfactualText = Get-Content -Raw -LiteralPath $counterfactualPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "RequireObserved",
        "ExpectedCommit must be a git hex commit prefix/full SHA",
        "expected_origin_commit",
        "deployed_app_commit",
        "deployment_runtime_current_for_replay_id",
        "replayCandidateId",
        "dfsr1_[0-9a-f]{24}",
        "replay_candidate_id_rows",
        "replay_candidate_id_missing_rows",
        "replay_candidate_version_rows",
        "replay_candidate_status_rows",
        "order_sent_false_rows",
        "intent_created_false_rows",
        "oco_plan_created_false_rows",
        "DataFreshness Sample Recency",
        "latest_data_freshness_row_time",
        "latest_data_freshness_row_age_hours",
        "data_freshness_rows_1d",
        "data_freshness_rows_3d",
        "data_freshness_rows_7d",
        "data_freshness_rows_14d",
        "data_freshness_rows_30d",
        "data_freshness_sample_gap_status",
        "NO_DATAFRESHNESS_ROWS_FOUND",
        "NO_ROWS_IN_REVIEW_WINDOW",
        "RECENT_ROWS_MISSING_REPLAY_ID",
        "RECENT_ROWS_WITH_REPLAY_ID",
        "PENDING_NO_NEW_DATAFRESHNESS_ROWS",
        "DEPLOYED_RUNTIME_NOT_CURRENT",
        "REPLAY_CANDIDATE_ID_EVIDENCE_OK",
        "REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE",
        "data_freshness_replay_candidate_id_recommendation",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "DataFreshness replay candidate id smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "replay_candidate_id_rows",
        "replayCandidateId for DataFreshness L0 rows"
    )) {
    Assert-Contains -Name "DataFreshness counterfactual smoke links replay id marker" -Text $counterfactualText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention replay candidate id smoke" -Text $doc -Pattern "smoke_data_freshness_replay_candidate_id_ssh\.ps1"
    Assert-Contains -Name "operator docs mention replay id read-only" -Text $doc -Pattern "read-only"
}

Write-Host "[data-freshness-replay-candidate-id-smoke-test] OK"
