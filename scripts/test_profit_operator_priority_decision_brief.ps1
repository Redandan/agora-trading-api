Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$consolidatedPath = Join-Path $PSScriptRoot "prepare_profit_operator_consolidated_review_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$consolidatedText = Get-Content -Raw -LiteralPath $consolidatedPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-operator-priority-decision-brief] read-only brief",
        "scope=READ_ONLY",
        "prepare_profit_operator_consolidated_review_packet.ps1",
        "PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF",
        "READY_FOR_OPERATOR_DECISION_NOT_LIVE",
        "profit_operator_priority_primary_focus",
        "profit_operator_priority_ranked_items",
        "profit_operator_priority_blocked_policy_lanes",
        "profit_operator_priority_decision_brief_packet",
        "profit_operator_priority_decision_brief_status",
        "trailing-stop-dry-run-operator-review",
        "strategy485-risk-reduction-shadow-operator-review",
        "entry-dedup-semantics-shadow-operator-review",
        "P1_LOW_MUTATION_REVIEW_WITH_STRONG_EXIT_EVIDENCE",
        "P1_CURRENT_POSITION_RISK_REVIEW_NOT_MUTATION",
        "P2_SHADOW_ALPHA_REVIEW_POLICY_RISK",
        "entry-filter remains blocked",
        "data-freshness-replay remains blocked",
        "order_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only profit operator priority decision brief only",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator priority decision marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "PROFIT_OPERATOR_CONSOLIDATED_REVIEW_PACKET",
        "READY_FOR_OPERATOR_REVIEW_NOT_LIVE",
        "profit_operator_consolidated_review_packet",
        "trailing-stop-dry-run-operator-review",
        "strategy485-risk-reduction-shadow-operator-review",
        "entry-dedup-semantics-shadow-operator-review"
    )) {
    Assert-Contains -Name "consolidated packet supports priority brief" -Text $consolidatedText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_operator_priority_decision_brief.ps1",
        "PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF",
        "profit_operator_priority_decision_brief_packet",
        "profit_operator_priority_primary_focus",
        "trailing-stop dry-run review first"
    )) {
    Assert-Contains -Name "docs mention priority decision brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-priority-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-priority-review-" + [guid]::NewGuid().ToString("N"))
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
        throw "Unable to find powershell or pwsh for profit operator priority decision brief test"
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
        throw "profit operator priority decision brief failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "profit_operator_priority_primary_focus=trailing-stop-dry-run-operator-review",
            "profit_operator_priority_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_LIVE",
            '"packetType":"PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF"',
            '"rank":1',
            '"proposalId":"trailing-stop-dry-run-operator-review"',
            '"rank":2',
            '"proposalId":"strategy485-risk-reduction-shadow-operator-review"',
            '"rank":3',
            '"proposalId":"entry-dedup-semantics-shadow-operator-review"',
            '"primaryFocus":"trailing-stop-dry-run-operator-review"',
            '"operatorDecisionOrder":["1. trailing-stop dry-run review","2. strategy485 risk-reduction shadow review","3. EntryDedup semantics shadow review"]',
            '"lane":"entry-filter"',
            '"lane":"data-freshness-replay"',
            "order_allowed=false",
            "notAuthorization=read-only profit operator priority decision brief only"
        )) {
        Assert-Contains -Name "profit operator priority latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator priority decision unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[profit-operator-priority-decision-brief-test] OK"
