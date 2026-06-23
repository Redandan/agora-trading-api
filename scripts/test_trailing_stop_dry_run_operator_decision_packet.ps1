Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_operator_decision_packet.ps1"
$priorityPath = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$exitSidePath = Join-Path $PSScriptRoot "prepare_exit_side_experiment_operator_review_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$priorityText = Get-Content -Raw -LiteralPath $priorityPath
$exitSideText = Get-Content -Raw -LiteralPath $exitSidePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[trailing-stop-dry-run-operator-decision-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_profit_operator_priority_decision_brief.ps1",
        "prepare_exit_side_experiment_operator_review_packet.ps1",
        "TRAILING_STOP_DRY_RUN_OPERATOR_DECISION_PACKET",
        "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE",
        "trailing_stop_dry_run_operator_decision_packet",
        "trailing_stop_dry_run_operator_decision_status",
        "trailing_stop_dry_run_primary_focus",
        "trailing-stop-dry-run-operator-review",
        "schedulerEnablementAllowed = `$false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only trailing-stop dry-run operator decision packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "trailing dry-run decision marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF",
        "profit_operator_priority_primary_focus",
        "trailing-stop-dry-run-operator-review"
    )) {
    Assert-Contains -Name "priority brief supports trailing dry-run packet" -Text $priorityText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET",
        "trailing-stop-dry-run-operator-review",
        "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE"
    )) {
    Assert-Contains -Name "exit-side packet supports trailing dry-run packet" -Text $exitSideText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_trailing_stop_dry_run_operator_decision_packet.ps1",
        "TRAILING_STOP_DRY_RUN_OPERATOR_DECISION_PACKET",
        "trailing_stop_dry_run_operator_decision_packet",
        "trailing-stop dry-run operator decision packet",
        "scheduler_enablement_allowed=false"
    )) {
    Assert-Contains -Name "docs mention trailing dry-run decision packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-dry-run-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-dry-run-review-" + [guid]::NewGuid().ToString("N"))
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
        throw "Unable to find powershell or pwsh for trailing dry-run decision packet test"
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
        throw "trailing dry-run decision packet failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "trailing_stop_dry_run_primary_focus=trailing-stop-dry-run-operator-review",
            "trailing_stop_dry_run_operator_decision_status=READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE",
            '"packetType":"TRAILING_STOP_DRY_RUN_OPERATOR_DECISION_PACKET"',
            '"status":"READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE"',
            '"proposalId":"trailing-stop-dry-run-operator-review"',
            '"schedulerEnablementAllowed":false',
            '"orderAllowed":false',
            '"positionOrOcoMutationAllowed":false',
            '"reviewNotionalCapUsdt":15',
            '"observationHours":48',
            '"blockedPolicyLanes"',
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "notAuthorization=read-only trailing-stop dry-run operator decision packet only"
        )) {
        Assert-Contains -Name "trailing dry-run latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing dry-run decision unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[trailing-stop-dry-run-operator-decision-packet-test] OK"
