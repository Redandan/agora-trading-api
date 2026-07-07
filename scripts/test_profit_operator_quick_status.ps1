Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_quick_status.ps1"
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
        "[profit-operator-quick-status] read-only quick status",
        "PROFIT_OPERATOR_QUICK_STATUS",
        "latest-profit-operator-matrix.path",
        "prepare_profit_operator_compact_status.ps1",
        "NextExecutionLogPath",
        "profit-next-execution blocker log",
        "profit_operator_quick_status_packet",
        "profit_operator_quick_status",
        "profit_operator_quick_refresh_required",
        "profit_operator_quick_next_execution_status",
        "profit_operator_quick_next_execution_unique_blocker",
        "profit_operator_quick_next_execution_open_oco_positions",
        "profit_operator_quick_compact_failure_summary",
        "nextExecutionStatus",
        "compactFailureSummary",
        "REFRESH_REQUIRED_NO_MATRIX",
        "REFRESH_REQUIRED_STALE_MATRIX",
        "REFRESH_REQUIRED_INVALID_MATRIX_PACKET",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        "do not relax EntryDedup/DataFreshness/live policy",
        "notAuthorization=read-only quick profit status only",
        "does not rerun SSH",
        "RequireFreshMatrix",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator quick status marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-quick-review-" + [guid]::NewGuid().ToString("N"))
$tempMatrixPath = Join-Path $tempReviewDir "profit-operator-matrix.log"
$tempNextExecutionPath = Join-Path $tempReviewDir "profit-next-execution.log"
try {
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator quick status test"
    }

    $missingOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -NextExecutionLogPath $tempNextExecutionPath 2>&1
    $missingExitCode = $LASTEXITCODE
    $missingText = ($missingOutput | Out-String)
    if ($missingExitCode -ne 0) {
        throw "profit operator quick status should not fail on missing matrix without -RequireFreshMatrix:`n$missingText"
    }
    foreach ($marker in @(
            "profit_operator_quick_status=REFRESH_REQUIRED_NO_MATRIX",
            "profit_operator_quick_refresh_required=true",
            "profit_operator_quick_next_execution_status=NEXT_EXECUTION_LOG_MISSING",
            '"packetType":"PROFIT_OPERATOR_QUICK_STATUS"',
            '"refreshRequired":true',
            '"nextExecutionStatus":'
        )) {
        Assert-Contains -Name "profit operator quick status missing matrix" -Text $missingText -Pattern ([regex]::Escape($marker))
    }

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
                lane = "data-freshness-replay"
                priority = "P2"
                status = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("data_freshness_shadow_candidate_packet_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE")
                missingRequirements = @("fresh replayCandidateId rows")
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
    $nextExecutionPacket = [pscustomobject]@{
        packetType = "PROFIT_NEXT_EXECUTION_BLOCKER_PACKET"
        status = "TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION"
        profitRoute = "TRAILING_STOP_DRY_RUN_OBSERVATION"
        uniqueBlocker = "NO_OPEN_OCO_POSITIONS"
        nextAction = "Keep dry-run active and wait for an open OCO position."
    }
    Set-Content -LiteralPath $tempNextExecutionPath -Encoding UTF8 -Value @(
        "profit_next_execution_route=TRAILING_STOP_DRY_RUN_OBSERVATION",
        "profit_next_execution_sample_collection_blocked_by=NO_OPEN_OCO_POSITIONS",
        "profit_next_execution_open_oco_positions=0",
        "profit_next_execution_unique_blocker=NO_OPEN_OCO_POSITIONS",
        "data_freshness_replay_candidate_id_rows=0",
        "data_freshness_complete_replayable_candidate_rows=0",
        ("profit_next_execution_blocker_packet=" + (ConvertTo-Json -Compress -Depth 8 $nextExecutionPacket)),
        "profit_next_execution_blocker_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false"
    )

    $freshOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -NextExecutionLogPath $tempNextExecutionPath -RequireReady 2>&1
    $freshExitCode = $LASTEXITCODE
    $freshText = ($freshOutput | Out-String)
    if ($freshExitCode -ne 0) {
        throw "profit operator quick status failed on fresh matrix:`n$freshText"
    }
    foreach ($marker in @(
            "profit_operator_quick_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "profit_operator_quick_compact_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "profit_operator_quick_refresh_required=false",
            "profit_operator_quick_next_execution_status=TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION",
            "profit_operator_quick_next_execution_route=TRAILING_STOP_DRY_RUN_OBSERVATION",
            "profit_operator_quick_next_execution_unique_blocker=NO_OPEN_OCO_POSITIONS",
            "profit_operator_quick_next_execution_open_oco_positions=0",
            "profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows=0",
            "Current execution blocker: TRAILING_STOP_DRY_RUN_OBSERVATION waits on NO_OPEN_OCO_POSITIONS.",
            '"compactStatus":"READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"',
            '"refreshRequired":false',
            '"nextExecutionStatus":',
            '"uniqueBlocker":"NO_OPEN_OCO_POSITIONS"',
            "notAuthorization=read-only quick profit status only"
        )) {
        Assert-Contains -Name "profit operator quick status fresh matrix" -Text $freshText -Pattern ([regex]::Escape($marker))
    }
    if ($freshText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator quick status unexpectedly invoked child/SSH:`n$freshText"
    }

    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = (Get-Date).AddMinutes(-10)
    $staleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -NextExecutionLogPath $tempNextExecutionPath -MatrixMaxAgeMinutes 1 2>&1
    $staleExitCode = $LASTEXITCODE
    $staleText = ($staleOutput | Out-String)
    if ($staleExitCode -ne 0) {
        throw "profit operator quick status should not fail on stale matrix without -RequireFreshMatrix:`n$staleText"
    }
    foreach ($marker in @(
            "profit_operator_quick_compact_status=STALE_MATRIX",
            "profit_operator_quick_status=REFRESH_REQUIRED_STALE_MATRIX",
            "profit_operator_quick_refresh_required=true"
        )) {
        Assert-Contains -Name "profit operator quick status stale matrix" -Text $staleText -Pattern ([regex]::Escape($marker))
    }

    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value "partial matrix output without packet"
    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = Get-Date
    $invalidOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -NextExecutionLogPath $tempNextExecutionPath 2>&1
    $invalidExitCode = $LASTEXITCODE
    $invalidText = ($invalidOutput | Out-String)
    if ($invalidExitCode -ne 0) {
        throw "profit operator quick status should not fail on invalid matrix without -RequireFreshMatrix:`n$invalidText"
    }
    foreach ($marker in @(
            "profit_operator_quick_compact_status=INVALID_MATRIX_PACKET",
            "profit_operator_quick_status=REFRESH_REQUIRED_INVALID_MATRIX_PACKET",
            "profit_operator_quick_refresh_required=true",
            '"compactStatus":"INVALID_MATRIX_PACKET"',
            '"status":"REFRESH_REQUIRED_INVALID_MATRIX_PACKET"'
        )) {
        Assert-Contains -Name "profit operator quick status invalid matrix" -Text $invalidText -Pattern ([regex]::Escape($marker))
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $requireFreshOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -NextExecutionLogPath $tempNextExecutionPath -MatrixMaxAgeMinutes 1 -RequireFreshMatrix 2>&1
        $requireFreshExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $requireFreshText = ($requireFreshOutput | Out-String)
    if ($requireFreshExitCode -eq 0) {
        throw "profit operator quick status accepted stale matrix with -RequireFreshMatrix:`n$requireFreshText"
    }
    Assert-Contains -Name "profit operator quick status require fresh guard" -Text $requireFreshText -Pattern "requires a fresh matrix"
} finally {
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

foreach ($marker in @(
        "prepare_profit_operator_quick_status.ps1",
        "profit_operator_quick_status_packet",
        "profit_operator_quick_next_execution_unique_blocker",
        "profit-next-execution blocker log",
        "REFRESH_REQUIRED_NO_MATRIX",
        "REFRESH_REQUIRED_STALE_MATRIX",
        "REFRESH_REQUIRED_INVALID_MATRIX_PACKET",
        "does not rerun SSH",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention quick status" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Write-Host "[profit-operator-quick-status-test] OK"
