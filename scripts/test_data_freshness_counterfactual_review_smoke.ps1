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
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_counterfactual_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "data_freshness_counterfactual_rows",
        "runtime_evidence_linked_rows",
        "live_signal_linked_rows",
        "replay_candidate_id_rows",
        "explicit_candidate_entry_rows",
        "explicit_candidate_tp_rows",
        "explicit_candidate_sl_rows",
        "ev_snapshot_rows",
        "oco_plan_snapshot_rows",
        "hard_gate_snapshot_rows",
        "complete_replayable_candidate_rows",
        "positive_forward_24h_rows",
        "avg_forward_24h_pct",
        "missing_counterfactual_fields",
        "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING",
        "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "counterfactual_required_evidence",
        "replayCandidateId for DataFreshness L0 rows",
        "data_freshness_counterfactual_recommendation",
        "notAuthorization",
        "OK read-only check complete",
        "Assert-SshHostSafe",
        "refusing to query unexpected database"
    )) {
    Assert-Contains -Name "DataFreshness counterfactual smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "DataFreshness counterfactual docs mention smoke" -Text $path -Pattern "smoke_data_freshness_counterfactual_review_ssh\.ps1"
    Assert-Contains -Name "DataFreshness counterfactual docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[data-freshness-counterfactual-review-smoke-test] OK"
