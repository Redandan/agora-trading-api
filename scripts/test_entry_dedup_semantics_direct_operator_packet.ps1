Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_direct_operator_packet.ps1"
$shadowPath = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$shadowText = Get-Content -Raw -LiteralPath $shadowPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-semantics-direct-operator-packet] read-only packet",
        "scope=READ_ONLY",
        "ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET",
        "READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE",
        "entry_dedup_semantics_direct_operator_packet",
        "entry_dedup_semantics_direct_operator_status",
        "entry_dedup_semantics_direct_operator_decision=PREPARE_ENTRY_DEDUP_SHADOW_REVIEW_NOT_LIVE",
        "NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW",
        "notFullProfitPriorityDecision = `$true",
        "NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT remains a live-preflight warning",
        "OCO_ROUTE_NOT_PROVEN_OR_MISSING remains a hard blocker",
        "entryDedupPolicyChangeAllowed = `$false",
        "dataFreshnessPolicyChangeAllowed = `$false",
        "stagedAddExecutionAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "staged_add_execution_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "grid_mutation_allowed=false",
        "notAuthorization=read-only EntryDedup semantics direct operator packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup direct operator marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "entry_dedup_semantics_shadow_experiment_packet",
        "exactOpportunityCount",
        "exactDuplicateSuppressedRows",
        "stagedAddReviewCandidateOpportunities"
    )) {
    Assert-Contains -Name "EntryDedup shadow packet supports direct packet" -Text $shadowText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_entry_dedup_semantics_direct_operator_packet.ps1",
        "ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET",
        "entry_dedup_semantics_direct_operator_packet",
        "EntryDedup semantics direct operator packet",
        "sourcePriorityDependency=NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW",
        "entry_dedup_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup direct packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for EntryDedup direct operator packet test"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewNotionalCapUsdt 9 -ObservationHours 36 -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$text = ($output | Out-String)
if ($exitCode -ne 0) {
    throw "EntryDedup direct operator packet should be ready from recorded shadow evidence:`n$text"
}
foreach ($marker in @(
        "source_priority_dependency=NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW",
        "not_full_profit_priority_decision=true",
        "entry_dedup_semantics_direct_operator_status=READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE",
        '"packetType":"ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET"',
        '"status":"READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE"',
        '"sourcePriorityDependency":"NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW"',
        '"notFullProfitPriorityDecision":true',
        '"reviewNotionalCapUsdt":9',
        '"observationHours":36',
        '"entryDedupPolicyChangeAllowed":false',
        '"dataFreshnessPolicyChangeAllowed":false',
        '"stagedAddExecutionAllowed":false',
        '"orderAllowed":false',
        '"gridMutationAllowed":false',
        '"telegramSendAllowed":false',
        '"rawAuditRows":11',
        '"exactOpportunityCount":6',
        '"exactDuplicateSuppressedRows":5',
        '"stagedAddReviewCandidateOpportunities":6',
        '"tpHitOpportunities":6',
        '"slHitOpportunities":0',
        '"NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT"',
        '"OCO_ROUTE_NOT_PROVEN_OR_MISSING"',
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "staged_add_execution_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "grid_mutation_allowed=false",
        "notAuthorization=read-only EntryDedup semantics direct operator packet only"
    )) {
    Assert-Contains -Name "EntryDedup direct operator ready output" -Text $text -Pattern ([regex]::Escape($marker))
}
if ($text -match "prepare_profit_operator_priority_decision_brief|profit_operator_priority|source_matrix_freshness_status|child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
    throw "EntryDedup direct operator packet unexpectedly invoked priority matrix or SSH:`n$text"
}

$tempShadowLog = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-direct-shadow-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $shadowPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE"
        sourceEvidenceSummary = [pscustomobject]@{
            rawAuditRows = 2
            exactOpportunityCount = 1
            exactDuplicateSuppressedRows = 1
            stagedAddReviewCandidateOpportunities = 1
            tpHitOpportunities = 1
            slHitOpportunities = 0
            exactOpportunityReviewBlockers = @("OCO_ROUTE_NOT_PROVEN_OR_MISSING")
        }
    }
    Set-Content -LiteralPath $tempShadowLog -Encoding UTF8 -Value ("entry_dedup_semantics_shadow_experiment_packet=" + (ConvertTo-Json -Compress -Depth 6 $shadowPacket))
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $sourceOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceShadowLogPath $tempShadowLog -RequireReady 2>&1
        $sourceExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $sourceText = ($sourceOutput | Out-String)
    if ($sourceExitCode -ne 0) {
        throw "EntryDedup direct operator packet failed SourceShadowLogPath mode:`n$sourceText"
    }
    Assert-Contains -Name "EntryDedup direct SourceShadowLogPath mode" -Text $sourceText -Pattern "source_mode=SOURCE_SHADOW_LOG_PATH"
    Assert-Contains -Name "EntryDedup direct SourceShadowLogPath ready" -Text $sourceText -Pattern "entry_dedup_semantics_direct_operator_status=READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE"
} finally {
    if (Test-Path -LiteralPath $tempShadowLog) {
        Remove-Item -LiteralPath $tempShadowLog -Force
    }
}

foreach ($badArgs in @(
        @("-Symbol", "BTCUSDT;bad"),
        @("-IntervalCode", "1h bad"),
        @("-ReviewNotionalCapUsdt", "0"),
        @("-ObservationHours", "0")
    )) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $badOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @badArgs 2>&1
        $badExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($badExitCode -eq 0) {
        throw "EntryDedup direct operator packet accepted invalid args: $($badArgs -join ' ')`n$($badOutput | Out-String)"
    }
}

Write-Host "[entry-dedup-semantics-direct-operator-packet-test] OK"
