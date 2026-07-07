Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_compact_status.ps1"
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
        "[profit-operator-compact-status] read-only compact status",
        "latest-profit-operator-matrix.path",
        "PROFIT_OPERATOR_COMPACT_STATUS",
        "Convert-JsonObjectOrNull",
        "profit_operator_compact_ready_lanes",
        "profit_operator_compact_blocked_lanes",
        "profit_operator_compact_exit_side_proposals",
        "profit_operator_compact_status_packet",
        "profit_operator_compact_status",
        "profit_operator_compact_matrix_invalid_reason",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        "STALE_MATRIX",
        "INVALID_MATRIX_PACKET",
        "matrixInvalidReason",
        "trailing-stop-rollout-review",
        "strategy485-risk-reduction-review",
        "do not relax EntryDedup/DataFreshness/live policy",
        "notAuthorization=read-only compact profit status only",
        "does not rerun SSH",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator compact status marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-compact-review-" + [guid]::NewGuid().ToString("N"))
$tempMatrixPath = Join-Path $tempReviewDir "profit-operator-matrix.log"
try {
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    $matrixPacket = [pscustomobject]@{
        packetType = "PROFIT_OPERATOR_REVIEW_MATRIX"
        status = "HAS_REVIEW_READY_ITEMS_NOT_LIVE"
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to operator review."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false")
                missingRequirements = @("signal policy review clear")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @(
                    "profit_evidence_watch_reason=PENDING_NO_NEW_DATAFRESHNESS_ROWS",
                    "data_freshness_shadow_candidate_packet_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
                    "counterfactual_evidence_class=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
                )
                missingRequirements = @("fresh replayCandidateId rows", "historical proxy is not shadow-reviewable")
                nextAction = "Collect replay snapshots."
            }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $matrixPacket)),
        "profit_operator_review_matrix_next_action=Review ready read-only items separately."
    )
    Set-Content -LiteralPath (Join-Path $tempReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $tempMatrixPath

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator compact status test"
    }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "profit operator compact status failed on fresh matrix:`n$text"
    }
    foreach ($marker in @(
            "profit_operator_compact_freshness_status=FRESH",
            "profit_operator_compact_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
            "profit_operator_compact_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            '"packetType":"PROFIT_OPERATOR_COMPACT_STATUS"',
            '"readyLaneCount":1',
            '"blockedLaneCount":2',
            '"proposalId":"trailing-stop-rollout-review"',
            '"proposalId":"strategy485-risk-reduction-review"',
            '"lane":"entry-filter"',
            '"lane":"data-freshness-replay"',
            '"status":"BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"',
            'data_freshness_shadow_candidate_packet_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE',
            "notAuthorization=read-only compact profit status only"
        )) {
        Assert-Contains -Name "profit operator compact status fresh output" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator compact status unexpectedly invoked child/SSH:`n$text"
    }

    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = (Get-Date).AddMinutes(-10)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $staleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -MatrixMaxAgeMinutes 1 -RequireReady 2>&1
        $staleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $staleText = ($staleOutput | Out-String)
    if ($staleExitCode -eq 0) {
        throw "profit operator compact status accepted stale matrix with -RequireReady:`n$staleText"
    }
    foreach ($marker in @(
            "profit_operator_compact_freshness_status=STALE",
            "profit_operator_compact_status=STALE_MATRIX",
            "Profit operator compact status is not ready: STALE_MATRIX"
        )) {
        Assert-Contains -Name "profit operator compact status stale output" -Text $staleText -Pattern ([regex]::Escape($marker))
    }

    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value "partial matrix output without packet"
    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = Get-Date
    $invalidOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir 2>&1
    $invalidExitCode = $LASTEXITCODE
    $invalidText = ($invalidOutput | Out-String)
    if ($invalidExitCode -ne 0) {
        throw "profit operator compact status should emit structured invalid matrix status without -RequireReady:`n$invalidText"
    }
    foreach ($marker in @(
            "profit_operator_compact_matrix_invalid_reason=missingPacket",
            "profit_operator_compact_status=INVALID_MATRIX_PACKET",
            '"matrixInvalidReason":"missingPacket"',
            "latest matrix output is not a reusable review matrix"
        )) {
        Assert-Contains -Name "profit operator compact status invalid output" -Text $invalidText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

foreach ($marker in @(
        "prepare_profit_operator_compact_status.ps1",
        "profit_operator_compact_status_packet",
        "profit_operator_compact_ready_lanes",
        "profit_operator_compact_blocked_lanes",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "does not rerun SSH",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention compact status" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Write-Host "[profit-operator-compact-status-test] OK"
