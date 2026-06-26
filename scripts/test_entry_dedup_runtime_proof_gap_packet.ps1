Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_runtime_proof_gap_packet.ps1"
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
        "[entry-dedup-runtime-proof-gap-packet] read-only packet",
        "ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET",
        "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE",
        "entry_dedup_runtime_proof_gap_packet",
        "entry_dedup_runtime_proof_gap_status",
        "entry_dedup_runtime_proof_gap_top_review_evidence_gap",
        "entry_dedup_runtime_proof_gap_top_mutation_blocker",
        "entry_dedup_runtime_proof_gap_blocker_semantics",
        "entry_dedup_runtime_proof_gap_review_progress_allowed",
        "OCO_ROUTE_NOT_PROVEN_OR_MISSING",
        "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
        "EXACT_DUPLICATE_REPLAY_PROTECTION_NOT_PROVEN",
        "DAILY_CAP_MAX_LOSS_CANDIDATE_SNAPSHOT_PARTIAL",
        "REVIEW_AND_MUTATION_SPLIT_V1",
        "reviewGapRanking",
        "mutationBlockerRanking",
        "shadowEvidenceCollectorAllowed",
        "entryDedupPolicyChangeAllowed = `$false",
        "dataFreshnessPolicyChangeAllowed = `$false",
        "stagedAddExecutionAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "order_allowed=false",
        "notAuthorization=read-only EntryDedup runtime proof gap packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup runtime proof gap marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_entry_dedup_runtime_proof_gap_packet.ps1",
        "ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET",
        "entry_dedup_runtime_proof_gap_packet",
        "EntryDedup runtime proof gap packet",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup runtime proof gap packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-runtime-proof-gap-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $directPath = Join-Path $tempDir "direct.log"
    $gatePath = Join-Path $tempDir "gate.log"
    $syntheticPath = Join-Path $tempDir "synthetic.log"

    $directPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        entryDedupStrategyId = 508
        intervalCode = "1h"
        sourceEvidenceSummary = [pscustomobject]@{
            exactOpportunityCount = 6
            exactDuplicateSuppressedRows = 5
            tpHitOpportunities = 6
            slHitOpportunities = 0
            stagedAddReviewCandidateOpportunities = 6
        }
    }
    $gatePacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET"
        status = "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE"
        gateStatuses = [pscustomobject]@{
            expectedValueGate = "PARTIAL_RUNTIME_PASS_CANDIDATE_SNAPSHOT_MISSING"
            eventRiskControl = "CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
            duplicateProtection = "PARTIAL_ENTRY_DEDUP_CANDIDATES_SEEN_EXACT_HASH_NOT_PROVEN"
            dailyCapMaxLossBudget = "PARTIAL_GLOBAL_CAP_OR_LOSS_ROWS_NOT_CANDIDATE_BLOCKER"
            ocoFeasibility = "BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY"
            runtimeEvidenceCoverage = "MISSING_CANDIDATE_RUNTIME_EVIDENCE_SNAPSHOTS"
        }
        dbEvidence = [pscustomobject]@{
            missingOcoRows = 1
            nonAutoZeroQtyRows = 1
            candidateGateRows = [pscustomobject]@{
                runtimeEvidenceRows = 0
                runtimeEvEvaluatedRows = 0
                runtimeOcoPlanRows = 0
                capOrLossRows = 0
            }
            runtimeEvidenceRows = [pscustomobject]@{
                runtime_evidence_rows = 1139
            }
        }
    }
    $syntheticPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SYNTHETIC_EV_OCO_PREVIEW_PACKET"
        status = "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE"
        candidateRows = 11
        tpHitRows = 11
        slHitRows = 0
        avgExpectedRProxy = 0.8
        orderAllowed = $false
    }

    Set-Content -LiteralPath $directPath -Encoding UTF8 -Value ("entry_dedup_semantics_direct_operator_packet=" + (ConvertTo-Json -Compress -Depth 8 $directPacket))
    Set-Content -LiteralPath $gatePath -Encoding UTF8 -Value ("  entry_dedup_semantics_gate_preflight_packet=" + (ConvertTo-Json -Compress -Depth 8 $gatePacket))
    Set-Content -LiteralPath $syntheticPath -Encoding UTF8 -Value ("  entry_dedup_synthetic_ev_oco_preview_packet=" + (ConvertTo-Json -Compress -Depth 8 $syntheticPacket))

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for EntryDedup runtime proof gap packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -DirectOperatorLogPath $directPath -GatePreflightLogPath $gatePath -SyntheticPreviewLogPath $syntheticPath -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup runtime proof gap packet should be ready from fixture logs:`n$text"
    }
    foreach ($marker in @(
            "entry_dedup_runtime_proof_gap_status=READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE",
            "entry_dedup_runtime_proof_gap_top_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
            "entry_dedup_runtime_proof_gap_second_blocker=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
            "entry_dedup_runtime_proof_gap_top_review_evidence_gap=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
            "entry_dedup_runtime_proof_gap_top_mutation_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
            "entry_dedup_runtime_proof_gap_blocker_semantics=REVIEW_AND_MUTATION_SPLIT_V1",
            "entry_dedup_runtime_proof_gap_review_progress_allowed=true",
            "entry_dedup_runtime_proof_gap_shadow_evidence_collector_allowed=true",
            '"packetType":"ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET"',
            '"exactOpportunityCount":6',
            '"candidateRows":11',
            '"blocker":"OCO_ROUTE_NOT_PROVEN_OR_MISSING"',
            '"blocker":"CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"',
            '"reviewGapRanking"',
            '"mutationBlockerRanking"',
            '"topReviewEvidenceGap":"CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"',
            '"topMutationBlocker":"OCO_ROUTE_NOT_PROVEN_OR_MISSING"',
            '"reviewProgressAllowed":true',
            '"shadowEvidenceCollectorAllowed":true',
            '"entryDedupPolicyChangeAllowed":false',
            '"orderAllowed":false',
            "order_allowed=false",
            "telegram_send_allowed=false",
            "grid_mutation_allowed=false"
        )) {
        Assert-Contains -Name "EntryDedup runtime proof gap ready output" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "EntryDedup runtime proof gap packet unexpectedly invoked SSH:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-runtime-proof-gap-packet-test] OK"
