param(
    [string]$MutationBlockerHandoffLogPath = "target/profit-review/entry-dedup-mutation-blocker-handoff-latest.log",
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
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

foreach ($path in @($MutationBlockerHandoffLogPath, $ExactOpportunityLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes
$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($handoffLog, $exactLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet=")
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }
if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact opportunity packet JSON present" }

$handoffStatus = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
$exactStatus = [string](Get-Prop -Object $exactPacket -Name "status" -Default "UNKNOWN")
if ($handoffStatus -ne "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE") {
    Add-Missing -List $missing -Value "mutation blocker handoff packet ready"
}
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact opportunity staged-add review packet ready"
}

$symbol = [string](Get-Prop -Object $handoffPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $handoffPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $handoffPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default (Get-Prop -Object $exactPacket -Name "exactOpportunityCount" -Default 0))
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)

$openSignalRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "open_signal_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "openSignalRows") -Default 0))
$autoTradedOpenRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "auto_traded_open_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "autoTradedOpenRows") -Default 0))
$nonAutoOpenRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_open_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "nonAutoOpenRows") -Default 0))
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_zero_qty_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "nonAutoZeroQtyRows") -Default 0))
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_eventrisk_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "nonAutoEventRiskRows") -Default 0))
$missingOcoRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "missing_oco_rows") -Default (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "missingOcoRows") -Default 0))
$openNotional = Get-DecimalValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "open_notional") -Default 0)

$autoTradedExposureClear = $autoTradedOpenRows -eq 0
$nonAutoExposureClear = $nonAutoOpenRows -eq 0 -and $nonAutoZeroQtyRows -eq 0 -and $missingOcoRows -eq 0 -and $nonAutoEventRiskRows -eq 0
$openExposureReviewRequired = -not $nonAutoExposureClear
$classification = if ($openExposureReviewRequired) {
    "OPEN_EXPOSURE_REVIEW_REQUIRED_BEFORE_MUTATION"
} elseif (-not $autoTradedExposureClear) {
    "AUTO_TRADED_OPEN_EXPOSURE_PROTECTIVE_BLOCK"
} else {
    "OPEN_EXPOSURE_CLEAR_FOR_REVIEW_NOT_AUTHORIZATION"
}

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked while open exposure is reviewed" }
if ($openSignalRows -lt 1) { Add-Missing -List $missing -Value "open exposure row evidence is present" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "REVIEW_NON_AUTO_MISSING_OCO_OPEN_SIGNAL_ROWS_NOT_EXECUTION"
} else {
    "REFRESH_ENTRY_DEDUP_OPEN_EXPOSURE_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $handoffLog.Name; ageMinutes = $handoffLog.AgeMinutes; fresh = $handoffLog.Fresh },
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        mutationBlockerHandoff = $handoffStatus
        exactOpportunityStagedAddReview = $exactStatus
    }
    openExposureEvidence = [ordered]@{
        classification = $classification
        exactOpportunityCount = $exactOpportunityCount
        openSignalRows = $openSignalRows
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoOpenRows = $nonAutoOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        missingOcoRows = $missingOcoRows
        openNotional = $openNotional
        autoTradedExposureClear = $autoTradedExposureClear
        nonAutoExposureClear = $nonAutoExposureClear
        openExposureReviewRequiredBeforeMutation = $openExposureReviewRequired
    }
    requiredBeforeClearing = @(
        "operator review of the non-auto/missing-OCO open signal row",
        "fresh read-only rerun proving the row is resolved, classified, or intentionally excluded from staged-add exposure semantics",
        "fresh OCO route proof and candidate runtime snapshots remain separate blockers",
        "separate explicit authorization before any deploy/env/runtime-evidence-write/policy/order/OCO mutation"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        openExposureReviewReady = $ready
        openExposureReviewRequiredBeforeMutation = $openExposureReviewRequired
        shadowReviewReady = $shadowReviewReady
        mutationReady = $false
        openExposureMutationAllowed = $false
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
    nextAction = if ($ready) {
        "Use this packet to review the non-auto/missing-OCO open signal blocker separately before any later EntryDedup mutation request."
    } else {
        "Refresh the missing EntryDedup open exposure evidence before review."
    }
    notAuthorization = "read-only EntryDedup open exposure review packet only; does not classify/resolve rows, deploy, change production env, activate collectors, write runtime evidence, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-open-exposure-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup mutation-blocker handoff and exact-opportunity logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_open_exposure_review_status=$status"
Write-Host "entry_dedup_open_exposure_review_decision=$decision"
Write-Host "entry_dedup_open_exposure_review_classification=$classification"
Write-Host "entry_dedup_open_exposure_review_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_open_exposure_review_open_signal_rows=$openSignalRows"
Write-Host "entry_dedup_open_exposure_review_auto_traded_open_rows=$autoTradedOpenRows"
Write-Host "entry_dedup_open_exposure_review_non_auto_open_rows=$nonAutoOpenRows"
Write-Host "entry_dedup_open_exposure_review_non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host "entry_dedup_open_exposure_review_non_auto_eventrisk_rows=$nonAutoEventRiskRows"
Write-Host "entry_dedup_open_exposure_review_missing_oco_rows=$missingOcoRows"
Write-Host "entry_dedup_open_exposure_review_open_notional=$openNotional"
Write-Host "entry_dedup_open_exposure_review_auto_traded_exposure_clear=$($autoTradedExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_review_non_auto_exposure_clear=$($nonAutoExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_review_required_before_mutation=$($openExposureReviewRequired.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_open_exposure_review_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_open_exposure_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "open_exposure_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "open_exposure_mutation_allowed=false"
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
Write-Host "notAuthorization=read-only EntryDedup open exposure review packet only; does not classify/resolve rows, deploy, change production env, activate collectors, write runtime evidence, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-open-exposure-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup open exposure review packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
