Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_operator_decision_packet.ps1"
$priorityPath = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$entryDedupPath = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$priorityText = Get-Content -Raw -LiteralPath $priorityPath
$entryDedupText = Get-Content -Raw -LiteralPath $entryDedupPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-semantics-operator-decision-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_profit_operator_priority_decision_brief.ps1",
        "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1",
        "ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_PACKET",
        "READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE",
        "entry_dedup_semantics_operator_decision_packet",
        "entry_dedup_semantics_operator_decision_status",
        "entry_dedup_semantics_priority_rank",
        "entry-dedup-semantics-shadow-operator-review",
        "entryDedupPolicyChangeAllowed = `$false",
        "dataFreshnessPolicyChangeAllowed = `$false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "live_policy_change_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization=read-only EntryDedup semantics operator decision packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup semantics decision marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF",
        "entry-dedup-semantics-shadow-operator-review",
        "rank = 3"
    )) {
    Assert-Contains -Name "priority brief supports EntryDedup packet" -Text $priorityText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "entry_dedup_semantics_shadow_experiment_packet",
        "exactOpportunityCount",
        "exactDuplicateSuppressedRows",
        "stagedAddReviewCandidateOpportunities"
    )) {
    Assert-Contains -Name "EntryDedup shadow packet supports decision packet" -Text $entryDedupText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_entry_dedup_semantics_operator_decision_packet.ps1",
        "ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_PACKET",
        "entry_dedup_semantics_operator_decision_packet",
        "EntryDedup semantics operator decision packet",
        "entry_dedup_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup semantics decision packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-semantics-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-semantics-review-" + [guid]::NewGuid().ToString("N"))
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
        throw "Unable to find powershell or pwsh for EntryDedup semantics decision packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -ReviewNotionalCapUsdt 9 -ObservationHours 36 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup semantics decision packet failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "entry_dedup_semantics_priority_rank=3",
            "entry_dedup_semantics_operator_decision_status=READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE",
            '"packetType":"ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_PACKET"',
            '"status":"READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE"',
            '"proposalId":"entry-dedup-semantics-shadow-operator-review"',
            '"priorityRank":3',
            '"entryDedupPolicyChangeAllowed":false',
            '"dataFreshnessPolicyChangeAllowed":false',
            '"orderAllowed":false',
            '"positionOrOcoMutationAllowed":false',
            '"reviewNotionalCapUsdt":9',
            '"observationHours":36',
            '"entryDedupSkipRows":11',
            '"exactOpportunityCount":6',
            '"exactDuplicateSuppressedRows":5',
            '"stagedAddReviewCandidateOpportunities":6',
            '"NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT"',
            '"OCO_ROUTE_NOT_PROVEN_OR_MISSING"',
            '"tpHitRows":11',
            '"ambiguousSameBarRows":0',
            '"blockedPolicyLanes"',
            "entry_dedup_policy_change_allowed=false",
            "data_freshness_policy_change_allowed=false",
            "notAuthorization=read-only EntryDedup semantics operator decision packet only"
        )) {
        Assert-Contains -Name "EntryDedup semantics latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "EntryDedup semantics decision unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-semantics-operator-decision-packet-test] OK"
