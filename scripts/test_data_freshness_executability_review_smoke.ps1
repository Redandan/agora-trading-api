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
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_executability_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "diagnoseDataFreshnessGuardBlocks",
        "analyzeBlockedSignalOutcomes",
        "listDecisionWindow",
        "getAutonomousReadinessDashboard",
        "listRuntimeDecisionEvidence",
        "previewTinyLiveAutoExecution",
        "validateAutonomousOpportunityReadiness"
    )) {
    Assert-Contains -Name "DataFreshness executability MCP calls" -Text $scriptText -Pattern ([regex]::Escape("call_tool(`"$toolName`""))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "Decision Window Evidence",
        "DataFreshness / Alpha Evidence",
        "Executability Gates",
        "Counterfactual Proof Gap",
        "missing_executability_evidence",
        "counterfactual_required_evidence",
        "windowOnlyDataFreshness",
        "windowHasLiveSignalIds",
        "evSamples",
        "ocoPlansCreated",
        "shadowIntentCount",
        "currentExecutionEligible",
        "currentOpportunityEligible",
        "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY",
        "REVIEW_BOUNDED_SHADOW_OR_TINY_LIVE_EXPERIMENT",
        "data_freshness_executability_recommendation",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "DataFreshness executability markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "DataFreshness RCA read-only boundary",
        "blocked-signal read-only boundary",
        "autonomous dashboard read-only boundary",
        "runtime evidence read-only boundary",
        "auto execution read-only boundary",
        "auto execution no-order marker",
        "opportunity read-only boundary",
        "opportunity no-order marker"
    )) {
    Assert-Contains -Name "DataFreshness executability hard-fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "DataFreshness executability docs mention smoke" -Text $path -Pattern "smoke_data_freshness_executability_review_ssh\.ps1"
    Assert-Contains -Name "DataFreshness executability docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[data-freshness-executability-review-smoke-test] OK"
