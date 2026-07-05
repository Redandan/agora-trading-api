param(
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
    [string]$SyntheticPreviewLogPath = "target/profit-review/entry-dedup-synthetic-ev-oco-preview-fresh.log",
    [string]$GatePreflightLogPath = "target/profit-review/entry-dedup-semantics-gate-preflight-fresh.log",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path (Split-Path -Parent $PSScriptRoot) $PathValue
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Read-FreshLog {
    param([string]$Name, [string]$PathValue, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
        AgeMinutes = $age
        Fresh = $age -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([object]$Value)
    $parsed = [decimal]0
    if ($null -eq $Value) { return [decimal]0 }
    if ($Value -is [decimal]) { return $Value }
    if ([decimal]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return [decimal]0
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($ExactOpportunityLogPath, $SyntheticPreviewLogPath, $GatePreflightLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes
$syntheticLog = Read-FreshLog -Name "entry-dedup-synthetic-ev-oco-preview" -PathValue $SyntheticPreviewLogPath -MaxAge $MaxAgeMinutes
$gateLog = Read-FreshLog -Name "entry-dedup-semantics-gate-preflight" -PathValue $GatePreflightLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($exactLog, $syntheticLog, $gateLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet=")
$syntheticPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $syntheticLog.Text -Prefix "entry_dedup_synthetic_ev_oco_preview_packet=")
$gatePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $gateLog.Text -Prefix "entry_dedup_semantics_gate_preflight_packet=")

if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact opportunity staged-add packet JSON present" }
if ($null -eq $syntheticPacket) { Add-Missing -List $missing -Value "synthetic EV/OCO preview packet JSON present" }
if ($null -eq $gatePacket) { Add-Missing -List $missing -Value "gate preflight packet JSON present" }

$exactStatus = if ($null -ne $exactPacket) { [string]$exactPacket.status } else { "UNKNOWN" }
$syntheticStatus = if ($null -ne $syntheticPacket) { [string]$syntheticPacket.status } else { "UNKNOWN" }
$gateStatus = if ($null -ne $gatePacket) { [string]$gatePacket.status } else { "UNKNOWN" }
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EntryDedup staged-add review packet ready"
}
if ($syntheticStatus -ne "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "synthetic EV/OCO preview ready"
}
if ($gateStatus -ne "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE") {
    Add-Missing -List $missing -Value "gate preflight blocker packet present"
}

$opportunities = if ($null -ne $exactPacket -and $null -ne $exactPacket.opportunities) { @($exactPacket.opportunities) } else { @() }
$exactOpportunityCount = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.exactOpportunityCount } else { 0 }
$validExactPlanShapeRows = 0
$invalidPlanExamples = @()
foreach ($opportunity in $opportunities) {
    $entry = Get-DecimalValue $opportunity.entry
    $tp = Get-DecimalValue $opportunity.tp
    $sl = Get-DecimalValue $opportunity.sl
    if ($entry -gt 0 -and $sl -gt 0 -and $tp -gt 0 -and $sl -lt $entry -and $entry -lt $tp) {
        $validExactPlanShapeRows += 1
    } else {
        $invalidPlanExamples += [ordered]@{
            opportunityKey = [string]$opportunity.opportunityKey
            entry = [string]$opportunity.entry
            tp = [string]$opportunity.tp
            sl = [string]$opportunity.sl
        }
    }
}
$allExactPlanShapesValid = $exactOpportunityCount -gt 0 -and $validExactPlanShapeRows -eq $exactOpportunityCount
if (-not $allExactPlanShapesValid) {
    Add-Missing -List $missing -Value "all exact opportunities have valid LONG TP/SL/OCO plan shape"
}

$syntheticCandidateRows = if ($null -ne $syntheticPacket) { Get-IntValue $syntheticPacket.candidateRows } else { 0 }
$syntheticValidOcoPlanRows = if ($null -ne $syntheticPacket) { Get-IntValue $syntheticPacket.validOcoPlanShapeRows } else { 0 }
$syntheticPlanShapeCoverageReady = $syntheticCandidateRows -gt 0 -and $syntheticValidOcoPlanRows -eq $syntheticCandidateRows
if (-not $syntheticPlanShapeCoverageReady) {
    Add-Missing -List $missing -Value "synthetic OCO plan-shape coverage complete"
}

$gateOcoStatus = if ($null -ne $gatePacket -and $null -ne $gatePacket.gateStatuses) {
    [string]$gatePacket.gateStatuses.ocoFeasibility
} else {
    "UNKNOWN"
}
$openExposure = if ($null -ne $exactPacket -and $null -ne $exactPacket.openExposure) { $exactPacket.openExposure } elseif ($null -ne $syntheticPacket) { $syntheticPacket.openExposure } else { $null }
$missingOcoRows = if ($null -ne $openExposure) { Get-IntValue $openExposure.missing_oco_rows } else { 0 }
$nonAutoZeroQtyRows = if ($null -ne $openExposure) { Get-IntValue $openExposure.non_auto_zero_qty_rows } else { 0 }
$autoTradedOpenRows = if ($null -ne $openExposure) { Get-IntValue $openExposure.auto_traded_open_rows } else { 0 }
$routeProofCleared = $false
$exchangeDryRunRequired = $true
$routeBlockerReason = if ($missingOcoRows -gt 0 -or $nonAutoZeroQtyRows -gt 0) {
    "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
} else {
    "EXCHANGE_SIDE_DRY_RUN_REQUIRED"
}

$preflightReady = $missing.Count -eq 0
$status = if ($preflightReady) {
    "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_INCOMPLETE_NOT_LIVE"
}
$decision = if ($preflightReady) {
    "REVIEW_OCO_PLAN_SHAPE_READY_ROUTE_PROOF_STILL_REQUIRED_NOT_LIVE"
} else {
    "COLLECT_ENTRY_DEDUP_OCO_ROUTE_PREFLIGHT_EVIDENCE_NOT_LIVE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_PACKET"
    status = $status
    decision = $decision
    symbol = if ($null -ne $exactPacket) { [string]$exactPacket.symbol } else { "BTCUSDT" }
    strategyId = if ($null -ne $exactPacket) { Get-IntValue $exactPacket.strategyId } else { 508 }
    intervalCode = if ($null -ne $exactPacket) { [string]$exactPacket.intervalCode } else { "1h" }
    sourceLogs = [ordered]@{
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
        syntheticEvOcoPreview = $SyntheticPreviewLogPath
        gatePreflight = $GatePreflightLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh },
        [ordered]@{ name = $syntheticLog.Name; ageMinutes = $syntheticLog.AgeMinutes; fresh = $syntheticLog.Fresh },
        [ordered]@{ name = $gateLog.Name; ageMinutes = $gateLog.AgeMinutes; fresh = $gateLog.Fresh }
    )
    planShapeEvidence = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        validExactPlanShapeRows = $validExactPlanShapeRows
        allExactPlanShapesValid = $allExactPlanShapesValid
        syntheticCandidateRows = $syntheticCandidateRows
        syntheticValidOcoPlanShapeRows = $syntheticValidOcoPlanRows
        syntheticPlanShapeCoverageReady = $syntheticPlanShapeCoverageReady
        invalidPlanExamples = @($invalidPlanExamples)
    }
    routeEvidence = [ordered]@{
        gateOcoStatus = $gateOcoStatus
        routeProofCleared = $routeProofCleared
        exchangeDryRunRequired = $exchangeDryRunRequired
        routeBlockerReason = $routeBlockerReason
        missingOcoRows = $missingOcoRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        autoTradedOpenRows = $autoTradedOpenRows
    }
    requiredBeforeRouteProofCleared = @(
        "separate explicit exchange-side OCO dry-run or lifecycle authorization",
        "fresh OCO lifecycle/poller evidence for the exact route",
        "candidate-level runtime OCO plan snapshot for each exact opportunity",
        "proof that existing open exposure is auto-traded or non-auto/missing-OCO exposure is separately resolved",
        "fresh read-only rerun before any staged-add/live mutation request"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        ocoRouteProofCleared = $false
        exchangeDryRunRequired = $true
        runtimeEvidenceWriteAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this preflight only to review TP/SL/OCO plan-shape readiness; OCO route proof still requires separately authorized exchange-side dry-run or lifecycle evidence."
    notAuthorization = "read-only EntryDedup OCO route proof preflight packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-oco-route-proof-preflight-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup exact-opportunity, synthetic EV/OCO, and gate preflight logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_oco_route_proof_preflight_status=$status"
Write-Host "entry_dedup_oco_route_proof_preflight_decision=$decision"
Write-Host "entry_dedup_oco_route_proof_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_oco_route_proof_valid_exact_plan_shape_rows=$validExactPlanShapeRows"
Write-Host "entry_dedup_oco_route_proof_all_exact_plan_shapes_valid=$($allExactPlanShapesValid.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_proof_synthetic_candidate_rows=$syntheticCandidateRows"
Write-Host "entry_dedup_oco_route_proof_synthetic_valid_oco_plan_shape_rows=$syntheticValidOcoPlanRows"
Write-Host "entry_dedup_oco_route_proof_gate_oco_status=$gateOcoStatus"
Write-Host "entry_dedup_oco_route_proof_route_cleared=$($routeProofCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_proof_exchange_dry_run_required=$($exchangeDryRunRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_proof_blocker_reason=$routeBlockerReason"
Write-Host "entry_dedup_oco_route_proof_missing_oco_rows=$missingOcoRows"
Write-Host "entry_dedup_oco_route_proof_non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host ("entry_dedup_oco_route_proof_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_oco_route_proof_preflight_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "oco_route_proof_cleared=false"
Write-Host "exchange_dry_run_required=true"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup OCO route proof preflight packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-oco-route-proof-preflight-packet] read-only check complete"

if ($RequireReady -and -not $preflightReady) {
    throw "EntryDedup OCO route proof preflight packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
