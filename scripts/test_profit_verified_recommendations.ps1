Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_verified_recommendations.ps1"
$exitSidePacketPath = Join-Path $PSScriptRoot "prepare_exit_side_operator_experiment_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$exitSidePacketText = Get-Content -Raw -LiteralPath $exitSidePacketPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-verified-recommendations] read-only packet",
        "scope=READ_ONLY",
        "prepare_exit_side_operator_experiment_packet.ps1",
        "PROFIT_VERIFIED_RECOMMENDATIONS",
        "READY_WITH_REVIEW_ONLY_RECOMMENDATIONS",
        "profit_verified_ready_recommendations",
        "profit_verified_blocked_items",
        "profit_verified_recommendations_packet",
        "profit_verified_recommendations_status",
        "trailing-stop-dry-run-experiment-review",
        "strategy485-risk-reduction-shadow-review",
        "notAuthorization=read-only verified recommendations only",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit verified recommendations marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW",
        "READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE",
        "exit_side_operator_experiment_packet"
    )) {
    Assert-Contains -Name "exit-side packet supports verified recommendations" -Text $exitSidePacketText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_verified_recommendations.ps1",
        "PROFIT_VERIFIED_RECOMMENDATIONS",
        "READY_WITH_REVIEW_ONLY_RECOMMENDATIONS",
        "profit_verified_recommendations_packet"
    )) {
    Assert-Contains -Name "operator docs mention verified recommendations" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-verified-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-verified-review-" + [guid]::NewGuid().ToString("N"))
try {
    $matrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS", "exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false", "data_freshness_current_status=NO_CURRENT_SAMPLE")
                missingRequirements = @("governance drift and missed-opportunity review")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE")
                missingRequirements = @("fresh replayCandidateId rows", "counterfactual snapshots")
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
        throw "Unable to find powershell or pwsh for profit verified recommendations test"
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
        throw "profit verified recommendations failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "profit_verified_recommendations_status=READY_WITH_REVIEW_ONLY_RECOMMENDATIONS",
            '"packetType":"PROFIT_VERIFIED_RECOMMENDATIONS"',
            '"readyRecommendationCount":2',
            '"blockedItemCount":2',
            '"recommendationId":"trailing-stop-dry-run-experiment-review"',
            '"recommendationId":"strategy485-risk-reduction-shadow-review"',
            '"lane":"entry-filter"',
            '"lane":"data-freshness-replay"',
            "notAuthorization=read-only verified recommendations only"
        )) {
        Assert-Contains -Name "profit verified recommendations latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit verified recommendations unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[profit-verified-recommendations-test] OK"
