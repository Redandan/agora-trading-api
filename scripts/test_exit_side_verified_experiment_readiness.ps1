Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_exit_side_verified_experiment_readiness.ps1"
$verifiedPath = Join-Path $PSScriptRoot "prepare_profit_verified_recommendations.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$verifiedText = Get-Content -Raw -LiteralPath $verifiedPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[exit-side-verified-experiment-readiness] read-only packet",
        "scope=READ_ONLY",
        "prepare_profit_verified_recommendations.ps1",
        "EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS",
        "READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE",
        "exit_side_verified_experiment_plans",
        "exit_side_verified_experiment_readiness_packet",
        "exit_side_verified_experiment_readiness_status",
        "trailing-stop-dry-run-readiness",
        "strategy485-risk-reduction-shadow-readiness",
        "DRY_RUN_REVIEW_ONLY",
        "SHADOW_REVIEW_ONLY",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only exit-side verified experiment readiness only",
        "RequireReady"
    )) {
    Assert-Contains -Name "exit-side verified readiness marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "PROFIT_VERIFIED_RECOMMENDATIONS",
        "READY_WITH_REVIEW_ONLY_RECOMMENDATIONS",
        "trailing-stop-dry-run-experiment-review",
        "strategy485-risk-reduction-shadow-review"
    )) {
    Assert-Contains -Name "profit verified recommendations supports readiness" -Text $verifiedText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_exit_side_verified_experiment_readiness.ps1",
        "EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS",
        "READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE",
        "exit_side_verified_experiment_readiness_packet",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention exit-side verified readiness" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-readiness-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-readiness-review-" + [guid]::NewGuid().ToString("N"))
try {
    $matrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS", "strategy485_oco_health_ok=true")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false")
                missingRequirements = @("governance drift and missed-opportunity review")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE")
                missingRequirements = @("fresh replayCandidateId rows")
                nextAction = "Collect replay snapshots before policy review."
            }
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
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for exit-side verified experiment readiness test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "exit-side verified experiment readiness failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "exit_side_verified_experiment_readiness_status=READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE",
            '"packetType":"EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS"',
            '"experimentPlanCount":2',
            '"planId":"trailing-stop-dry-run-readiness"',
            '"planId":"strategy485-risk-reduction-shadow-readiness"',
            '"mode":"DRY_RUN_REVIEW_ONLY"',
            '"mode":"SHADOW_REVIEW_ONLY"',
            '"status":"READY_FOR_OPERATOR_REVIEW_NOT_LIVE"',
            "live_policy_change_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "deploy_or_env_change_allowed=false",
            "notAuthorization=read-only exit-side verified experiment readiness only"
        )) {
        Assert-Contains -Name "exit-side verified readiness latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "exit-side verified readiness unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[exit-side-verified-experiment-readiness-test] OK"
