Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_exit_side_experiment_operator_review_packet.ps1"
$readinessPath = Join-Path $PSScriptRoot "prepare_exit_side_verified_experiment_readiness.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$readinessText = Get-Content -Raw -LiteralPath $readinessPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[exit-side-experiment-operator-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_exit_side_verified_experiment_readiness.ps1",
        "EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET",
        "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE",
        "exit_side_experiment_operator_review_items",
        "exit_side_experiment_operator_review_packet",
        "exit_side_experiment_operator_review_status",
        "trailing-stop-dry-run-operator-review",
        "strategy485-risk-reduction-shadow-operator-review",
        "small_experiment_review_cap_usdt",
        "ReviewNotionalCapUsdt",
        "ObservationHours",
        "order_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only exit-side experiment operator review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "exit-side experiment operator review marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS",
        "READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE",
        "trailing-stop-dry-run-readiness",
        "strategy485-risk-reduction-shadow-readiness"
    )) {
    Assert-Contains -Name "readiness supports operator review packet" -Text $readinessText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_exit_side_experiment_operator_review_packet.ps1",
        "EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET",
        "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE",
        "exit_side_experiment_operator_review_packet",
        "small_experiment_review_cap_usdt",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention exit-side experiment operator review packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-review-packet-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-review-packet-review-" + [guid]::NewGuid().ToString("N"))
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
        throw "Unable to find powershell or pwsh for exit-side experiment operator review packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -ReviewNotionalCapUsdt 15 -ObservationHours 48 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "exit-side experiment operator review packet failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "small_experiment_review_cap_usdt=15",
            "observation_hours=48",
            "exit_side_experiment_operator_review_status=READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE",
            '"packetType":"EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET"',
            '"reviewItemCount":2',
            '"proposalId":"trailing-stop-dry-run-operator-review"',
            '"proposalId":"strategy485-risk-reduction-shadow-operator-review"',
            '"proposedMaxNotionalUsdt":15',
            '"observationHours":48',
            '"orderAllowed":false',
            "order_allowed=false",
            "notAuthorization=read-only exit-side experiment operator review packet only"
        )) {
        Assert-Contains -Name "exit-side experiment operator review latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "exit-side experiment operator review unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[exit-side-experiment-operator-review-packet-test] OK"
