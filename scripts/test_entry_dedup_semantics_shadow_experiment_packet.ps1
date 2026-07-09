Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
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
        "[entry-dedup-semantics-shadow-experiment-packet] read-only packet",
        "scope=READ_ONLY",
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "smoke_entry_dedup_exposure_consistency_ssh.ps1",
        "smoke_entry_dedup_semantics_shadow_review_ssh.ps1",
        "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1",
        "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1",
        "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW",
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE",
        "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE",
        "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET",
        "ExactOpportunityLogPath",
        "FRESH_EXACT_OPPORTUNITY_LOG",
        "entry_dedup_semantics_shadow_source_mode",
        "entry_dedup_skip_rows=11",
        "exact_opportunity_count",
        "staged_add_review_candidate_opportunities",
        "tp_hit_rows=11",
        "avg_net_return_pct=0.8000",
        "ambiguous_same_bar_rows=0",
        "entry_dedup_semantics_shadow_experiment_packet",
        "entry_dedup_semantics_shadow_packet_status",
        "entry_dedup_shadow_packet_missing_requirements",
        "entry_dedup_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "notAuthorization=read-only EntryDedup semantics shadow experiment review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup semantics shadow experiment packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1",
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "entry_dedup_semantics_shadow_experiment_packet",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup semantics shadow experiment packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for EntryDedup semantics shadow experiment packet test"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$text = ($output | Out-String)
if ($exitCode -ne 0) {
    throw "EntryDedup semantics shadow experiment packet should be ready from recorded evidence:`n$text"
}
foreach ($marker in @(
        "entry_dedup_semantics_shadow_packet_status=READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "entry_dedup_semantics_shadow_source_mode=RECORDED_DOC_EVIDENCE",
        '"packetType":"ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET"',
        '"sourceEvidenceMode":"RECORDED_DOC_EVIDENCE"',
        '"rawAuditRows":11',
        '"exactOpportunityCount":6',
        '"exactDuplicateSuppressedRows":5',
        '"entryDedupSkipRows":11',
        '"exactOpportunityReviewedRows":6',
        '"tpHitRows":11',
        '"tpHitOpportunities":6',
        '"slHitOpportunities":0',
        '"stagedAddReviewCandidateOpportunities":6',
        '"NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT"',
        '"OCO_ROUTE_NOT_PROVEN_OR_MISSING"',
        '"avgNetReturnPct":0.8',
        '"ambiguousSameBarRows":0',
        '"orderAllowed":false',
        "entry_dedup_policy_change_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "EntryDedup semantics shadow experiment ready output" -Text $text -Pattern ([regex]::Escape($marker))
}
if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
    throw "EntryDedup semantics shadow experiment packet unexpectedly invoked SSH or a fresh child run:`n$text"
}

$tempExactOpportunityLog = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-shadow-exact-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $exactPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE"
        rawAuditRows = 13
        exactOpportunityCount = 8
        exactDuplicateSuppressedRows = 5
        stagedAddBudgetProxyAllowedOpportunities = 8
        stagedAddReviewCandidateOpportunities = 8
        tpHitOpportunities = 8
        slHitOpportunities = 0
        ambiguousOpportunities = 0
        avgExpectedRProxy = 0.8
        avgNetReturnPct = 0.8
        openExposure = [pscustomobject]@{
            open_signal_rows = 5
            auto_traded_open_rows = 0
            non_auto_zero_qty_rows = 5
            non_auto_eventrisk_rows = 1
        }
        opportunities = @(
            [pscustomobject]@{
                reviewBlockers = @("NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT", "OCO_ROUTE_NOT_PROVEN_OR_MISSING")
            }
        )
    }
    Set-Content -LiteralPath $tempExactOpportunityLog -Encoding UTF8 -Value ("entry_dedup_exact_opportunity_staged_add_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $exactPacket))
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $freshOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ExactOpportunityLogPath $tempExactOpportunityLog -RequireReady 2>&1
        $freshExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $freshText = ($freshOutput | Out-String)
    if ($freshExitCode -ne 0) {
        throw "EntryDedup semantics shadow experiment packet failed fresh exact opportunity mode:`n$freshText"
    }
    foreach ($marker in @(
            "entry_dedup_semantics_shadow_source_mode=FRESH_EXACT_OPPORTUNITY_LOG",
            '"sourceEvidenceMode":"FRESH_EXACT_OPPORTUNITY_LOG"',
            '"rawAuditRows":13',
            '"exactOpportunityCount":8',
            '"exactDuplicateSuppressedRows":5',
            '"entryDedupSkipRows":13',
            '"exactOpportunityReviewedRows":8',
            '"tpHitRows":13',
            '"tpHitOpportunities":8',
            '"stagedAddReviewCandidateOpportunities":8',
            '"NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT"',
            '"OCO_ROUTE_NOT_PROVEN_OR_MISSING"'
        )) {
        Assert-Contains -Name "EntryDedup semantics shadow fresh exact output" -Text $freshText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempExactOpportunityLog) {
        Remove-Item -LiteralPath $tempExactOpportunityLog -Force
    }
}

$tempProgress = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-shadow-packet-progress-" + [guid]::NewGuid().ToString("N") + ".md")
try {
    Set-Content -LiteralPath $tempProgress -Encoding UTF8 -Value "no matching evidence markers"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ProgressPath $tempProgress -RequireReady 2>&1
        $blockedExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $blockedText = ($blockedOutput | Out-String)
    if ($blockedExitCode -eq 0) {
        throw "EntryDedup semantics shadow experiment packet unexpectedly passed with missing progress evidence"
    }
    Assert-Contains -Name "EntryDedup semantics shadow experiment RequireReady failure" -Text $blockedText -Pattern "SPLIT_PROGRESS evidence marker present"
} finally {
    if (Test-Path -LiteralPath $tempProgress) {
        Remove-Item -LiteralPath $tempProgress -Force
    }
}

Write-Host "[entry-dedup-semantics-shadow-experiment-packet-test] OK"
