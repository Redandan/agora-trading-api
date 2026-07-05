param(
    [string]$OpenExposureReviewLogPath = "target/profit-review/entry-dedup-open-exposure-review-latest.log",
    [string]$ObjectiveTraceabilityLogPath = "target/profit-review/entry-dedup-review-only-objective-traceability-latest.log",
    [string]$ReportPath = "target/profit-review/profit-optimization-report-20260705.md",
    [string]$RunbookPath = "docs/deploy-runbook.md",
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

function Read-TextFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name file not found: $resolved"
    }
    return Get-Content -Raw -LiteralPath $resolved
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

function Get-Prop {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    if ($null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-NestedProp {
    param([object]$Object, [string[]]$Path, [object]$Default = $null)
    $current = $Object
    foreach ($part in $Path) {
        $current = Get-Prop -Object $current -Name $part -Default $null
        if ($null -eq $current) { return $Default }
    }
    return $current
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ($Value -is [double]) { return [int]$Value }
    if ($Value -is [decimal]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([object]$Value)
    $parsed = [decimal]0
    if ($null -eq $Value) { return [decimal]0 }
    if ($Value -is [decimal]) { return [decimal]$Value }
    if ([decimal]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return [decimal]0
}

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($OpenExposureReviewLogPath, $ObjectiveTraceabilityLogPath, $ReportPath, $RunbookPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$openExposureLog = Read-FreshLog -Name "entry-dedup-open-exposure-review" -PathValue $OpenExposureReviewLogPath -MaxAge $MaxAgeMinutes
$traceabilityLog = Read-FreshLog -Name "entry-dedup-review-only-objective-traceability" -PathValue $ObjectiveTraceabilityLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($openExposureLog, $traceabilityLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$openExposurePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $openExposureLog.Text -Prefix "entry_dedup_open_exposure_review_packet=")
$traceabilityPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $traceabilityLog.Text -Prefix "entry_dedup_review_only_objective_traceability_packet=")
if ($null -eq $openExposurePacket) { Add-Missing -List $missing -Value "open exposure review packet JSON present" }
if ($null -eq $traceabilityPacket) { Add-Missing -List $missing -Value "objective traceability packet JSON present" }

$openExposureStatus = [string](Get-Prop -Object $openExposurePacket -Name "status" -Default "UNKNOWN")
$traceabilityStatus = [string](Get-Prop -Object $traceabilityPacket -Name "status" -Default "UNKNOWN")
if ($openExposureStatus -ne "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "open exposure review packet ready"
}
if ($traceabilityStatus -ne "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE") {
    Add-Missing -List $missing -Value "objective traceability packet ready"
}

$symbol = [string](Get-Prop -Object $openExposurePacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $openExposurePacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $openExposurePacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "exactOpportunityCount") -Default 0)
$classification = [string](Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "classification") -Default "UNKNOWN")
$openSignalRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "openSignalRows") -Default 0)
$autoTradedOpenRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "autoTradedOpenRows") -Default 0)
$nonAutoOpenRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoOpenRows") -Default 0)
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoZeroQtyRows") -Default 0)
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoEventRiskRows") -Default 0)
$missingOcoRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "missingOcoRows") -Default 0)
$openNotional = Get-DecimalValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "openNotional") -Default 0)
$autoTradedExposureClear = Get-BoolValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "autoTradedExposureClear") -Default $false)
$nonAutoExposureClear = Get-BoolValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoExposureClear") -Default $false)
$openExposureReviewRequired = Get-BoolValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "openExposureReviewRequiredBeforeMutation") -Default $false)

$traceabilityOrderReadiness = [string](Get-NestedProp -Object $traceabilityPacket -Path @("requirementTraceability", "nonLiveGuardrails", "orderReadiness") -Default "UNKNOWN")
$traceabilityAllMutationFlagsFalse = Get-BoolValue (Get-NestedProp -Object $traceabilityPacket -Path @("requirementTraceability", "nonLiveGuardrails", "allMutationFlagsFalse") -Default $false)
$traceabilityLiveExecutionReady = Get-BoolValue (Get-NestedProp -Object $traceabilityPacket -Path @("reviewEnvelope", "liveExecutionReady") -Default $true)
$traceabilityOrderAllowed = Get-BoolValue (Get-NestedProp -Object $traceabilityPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)

$actualAutoExposureClear = $autoTradedExposureClear -and $autoTradedOpenRows -eq 0 -and $openNotional -eq [decimal]0
$semanticBlockerPresent = $openExposureReviewRequired -and (-not $nonAutoExposureClear) -and ($nonAutoOpenRows -gt 0 -or $nonAutoZeroQtyRows -gt 0 -or $missingOcoRows -gt 0 -or $nonAutoEventRiskRows -gt 0)
$zeroQtyMissingOcoSemanticsReviewRequired = $actualAutoExposureClear -and $nonAutoZeroQtyRows -gt 0 -and $missingOcoRows -gt 0 -and $openNotional -eq [decimal]0
$operatorSemanticsChoiceRequired = $semanticBlockerPresent -and $zeroQtyMissingOcoSemanticsReviewRequired

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Open Exposure Semantic Resolution") -and $reportText.Contains("ZERO_QTY_NON_AUTO_SEMANTICS_REVIEW")
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_open_exposure_semantic_resolution_packet.ps1") -and $runbookNormalizedText.Contains("zero-qty non-auto row is a semantic blocker, not order readiness")

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if ($classification -ne "OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION") { Add-Missing -List $missing -Value "open exposure remains classified as review-required" }
if ($openSignalRows -lt 1) { Add-Missing -List $missing -Value "open signal row evidence is present" }
if (-not $actualAutoExposureClear) { Add-Missing -List $missing -Value "auto-traded exposure is clear and open notional is zero" }
if (-not $semanticBlockerPresent) { Add-Missing -List $missing -Value "non-auto semantic blocker remains visible" }
if (-not $zeroQtyMissingOcoSemanticsReviewRequired) { Add-Missing -List $missing -Value "zero-qty missing-OCO semantic review is required" }
if (-not $operatorSemanticsChoiceRequired) { Add-Missing -List $missing -Value "operator semantic choice is required before clearing exposure" }
if ($traceabilityOrderReadiness -ne "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE") { Add-Missing -List $missing -Value "objective traceability keeps order readiness blocked" }
if (-not $traceabilityAllMutationFlagsFalse) { Add-Missing -List $missing -Value "objective traceability mutation flags remain false" }
if ($traceabilityLiveExecutionReady) { Add-Missing -List $missing -Value "live execution remains not ready" }
if ($traceabilityOrderAllowed) { Add-Missing -List $missing -Value "orders remain disallowed" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes semantic resolution section" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes semantic resolution instructions" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "KEEP_OPEN_EXPOSURE_BLOCKED_PENDING_ZERO_QTY_NON_AUTO_SEMANTICS_REVIEW"
} else {
    "REFRESH_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        openExposureReview = $OpenExposureReviewLogPath
        objectiveTraceability = $ObjectiveTraceabilityLogPath
    }
    sourceStatuses = [ordered]@{
        openExposureReview = $openExposureStatus
        objectiveTraceability = $traceabilityStatus
    }
    semanticEvidence = [ordered]@{
        classification = $classification
        exactOpportunityCount = $exactOpportunityCount
        openSignalRows = $openSignalRows
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoOpenRows = $nonAutoOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        missingOcoRows = $missingOcoRows
        openNotional = $openNotional
        actualAutoExposureClear = $actualAutoExposureClear
        semanticBlockerPresent = $semanticBlockerPresent
        zeroQtyMissingOcoSemanticsReviewRequired = $zeroQtyMissingOcoSemanticsReviewRequired
        operatorSemanticsChoiceRequired = $operatorSemanticsChoiceRequired
    }
    safeOperatorChoices = @(
        "keep blocked and collect fresher runtime snapshots",
        "separately review and resolve the non-auto zero-qty/missing-OCO row outside this packet",
        "separately authorize a code change to exclude zero-qty non-auto rows from staged-add open-exposure semantics",
        "separately authorize an exchange/OCO route dry-run only after open exposure semantics are resolved"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        semanticResolutionReviewReady = $ready
        openExposureClearanceAllowed = $false
        autoClearAllowed = $false
        operatorSemanticsChoiceRequired = $operatorSemanticsChoiceRequired
        mutationReady = $false
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        livePolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this packet to decide, outside this run, whether the zero-qty non-auto missing-OCO row should remain a blocker, be resolved manually, or become a separately authorized semantics change."
    notAuthorization = "read-only EntryDedup open exposure semantic resolution packet only; does not clear exposure, classify/resolve rows, change code semantics, deploy, change production env, activate collectors, write runtime evidence, relax policy, enable staged-add/live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-open-exposure-semantic-resolution-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup open-exposure and objective-traceability logs plus local report/runbook only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_open_exposure_semantic_resolution_status=$status"
Write-Host "entry_dedup_open_exposure_semantic_resolution_decision=$decision"
Write-Host "entry_dedup_open_exposure_semantic_resolution_classification=$classification"
Write-Host "entry_dedup_open_exposure_semantic_resolution_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_open_exposure_semantic_resolution_open_signal_rows=$openSignalRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_auto_traded_open_rows=$autoTradedOpenRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_non_auto_open_rows=$nonAutoOpenRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_non_auto_eventrisk_rows=$nonAutoEventRiskRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_missing_oco_rows=$missingOcoRows"
Write-Host "entry_dedup_open_exposure_semantic_resolution_open_notional=$openNotional"
Write-Host "entry_dedup_open_exposure_semantic_resolution_actual_auto_exposure_clear=$($actualAutoExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_semantic_resolution_semantic_blocker_present=$($semanticBlockerPresent.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_semantic_resolution_zero_qty_missing_oco_semantics_review_required=$($zeroQtyMissingOcoSemanticsReviewRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_semantic_resolution_operator_semantics_choice_required=$($operatorSemanticsChoiceRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_semantic_resolution_traceability_order_readiness=$traceabilityOrderReadiness"
Write-Host "entry_dedup_open_exposure_semantic_resolution_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_semantic_resolution_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_open_exposure_semantic_resolution_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_open_exposure_semantic_resolution_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "semantic_resolution_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "open_exposure_clearance_allowed=false"
Write-Host "auto_clear_allowed=false"
Write-Host "mutation_ready=false"
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup open exposure semantic resolution packet only; does not clear exposure, classify/resolve rows, change code semantics, deploy, change production env, activate collectors, write runtime evidence, relax policy, enable staged-add/live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-open-exposure-semantic-resolution-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup open exposure semantic resolution packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
