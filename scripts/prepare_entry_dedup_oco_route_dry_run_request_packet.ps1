param(
    [string]$MutationBlockerHandoffLogPath = "target/profit-review/entry-dedup-mutation-blocker-handoff-latest.log",
    [string]$OcoRoutePreflightLogPath = "target/profit-review/entry-dedup-oco-route-proof-preflight-latest.log",
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

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($MutationBlockerHandoffLogPath, $OcoRoutePreflightLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-preflight" -PathValue $OcoRoutePreflightLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($handoffLog, $ocoLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_proof_preflight_packet=")
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO route preflight packet JSON present" }

$handoffStatus = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
$ocoStatus = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
if ($handoffStatus -ne "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE") {
    Add-Missing -List $missing -Value "mutation blocker handoff packet ready"
}
if ($ocoStatus -ne "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "OCO route proof preflight packet ready"
}

$symbol = [string](Get-Prop -Object $handoffPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $handoffPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $handoffPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default 0)
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$handoffMutationReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)
$routeProofCleared = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "routeProofCleared") -Default $false)
$exchangeDryRunRequired = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "exchangeDryRunRequired") -Default $false)
$routeBlockerReason = [string](Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "routeBlockerReason") -Default "UNKNOWN")
$missingOcoRows = Get-IntValue (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "missingOcoRows") -Default (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "missingOcoRows") -Default 0))
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $handoffPacket -Path @("openExposureEvidence", "nonAutoZeroQtyRows") -Default (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "nonAutoZeroQtyRows") -Default 0))
$nextReviewActions = @((Get-Prop -Object $handoffPacket -Name "nextReviewActions" -Default @()))
$hasDryRunRequestAction = @($nextReviewActions | Where-Object {
        [string](Get-Prop -Object $_ -Name "action" -Default "") -eq "REQUEST_EXACT_OCO_ROUTE_DRY_RUN_REVIEW"
    }).Count -gt 0
$openExposureReviewRequired = $missingOcoRows -gt 0 -or $nonAutoZeroQtyRows -gt 0

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($handoffMutationReady) { Add-Missing -List $missing -Value "mutation remains blocked before dry-run request review" }
if ($routeProofCleared) { Add-Missing -List $missing -Value "OCO route proof is still uncleared and needs review" }
if (-not $exchangeDryRunRequired) { Add-Missing -List $missing -Value "OCO route evidence requires exchange-side dry-run or lifecycle review" }
if (-not $hasDryRunRequestAction) { Add-Missing -List $missing -Value "handoff includes exact OCO route dry-run review action" }

$requestReady = $missing.Count -eq 0
$status = if ($requestReady) {
    "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_INCOMPLETE_NOT_LIVE"
}
$decision = if ($requestReady) {
    "PREPARE_OPERATOR_REVIEW_FOR_OCO_ROUTE_DRY_RUN_NOT_EXECUTION"
} else {
    "REFRESH_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_EVIDENCE"
}

$confirmText = "AUTHORIZE_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REVIEW_ONLY"
$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
        ocoRoutePreflight = $OcoRoutePreflightLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $handoffLog.Name; ageMinutes = $handoffLog.AgeMinutes; fresh = $handoffLog.Fresh },
        [ordered]@{ name = $ocoLog.Name; ageMinutes = $ocoLog.AgeMinutes; fresh = $ocoLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        mutationBlockerHandoff = $handoffStatus
        ocoRoutePreflight = $ocoStatus
    }
    requestEvidence = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        routeProofCleared = $routeProofCleared
        exchangeDryRunRequired = $exchangeDryRunRequired
        routeBlockerReason = $routeBlockerReason
        openExposureReviewRequiredBeforeExecution = $openExposureReviewRequired
        missingOcoRows = $missingOcoRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
    }
    proposedOperatorRequest = [ordered]@{
        requestName = "ENTRY_DEDUP_EXACT_OCO_ROUTE_DRY_RUN_REVIEW"
        confirmText = $confirmText
        requestedScope = "review-only authorization to prepare exact OCO route dry-run evidence; this packet itself executes nothing"
        mustResolveBeforeAnyExecution = @(
            "separate open exposure review for non-auto/missing-OCO rows",
            "separate operator approval for any actual exchange-side dry-run",
            "fresh read-only preflight immediately before any approved dry-run execution",
            "runtime snapshot and daily cap/max-loss evidence remain separate blockers"
        )
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        requestReady = $requestReady
        shadowReviewReady = $shadowReviewReady
        mutationReady = $false
        exchangeDryRunExecutionAllowed = $false
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
    nextAction = if ($requestReady) {
        "Use this as an operator-review request template only. Do not execute an exchange dry-run until open exposure review and a separate exact dry-run authorization are complete."
    } else {
        "Refresh the missing OCO route dry-run request evidence before operator review."
    }
    notAuthorization = "read-only EntryDedup OCO route dry-run request packet only; does not execute dry-run, call SSH/MCP, deploy, change production env, activate collectors, write runtime evidence, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-oco-route-dry-run-request-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup mutation-blocker handoff and OCO route preflight logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_oco_route_dry_run_request_status=$status"
Write-Host "entry_dedup_oco_route_dry_run_request_decision=$decision"
Write-Host "entry_dedup_oco_route_dry_run_request_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_oco_route_dry_run_request_route_proof_cleared=$($routeProofCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_dry_run_request_exchange_dry_run_required=$($exchangeDryRunRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_dry_run_request_open_exposure_review_required=$($openExposureReviewRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_oco_route_dry_run_request_missing_oco_rows=$missingOcoRows"
Write-Host "entry_dedup_oco_route_dry_run_request_non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host "entry_dedup_oco_route_dry_run_request_confirm_text=$confirmText"
Write-Host ("entry_dedup_oco_route_dry_run_request_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_oco_route_dry_run_request_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "request_ready=$($requestReady.ToString().ToLowerInvariant())"
Write-Host "mutation_ready=false"
Write-Host "exchange_dry_run_execution_allowed=false"
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
Write-Host "notAuthorization=read-only EntryDedup OCO route dry-run request packet only; does not execute dry-run, call SSH/MCP, deploy, change production env, activate collectors, write runtime evidence, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-oco-route-dry-run-request-packet] read-only check complete"

if ($RequireReady -and -not $requestReady) {
    throw "EntryDedup OCO route dry-run request packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
