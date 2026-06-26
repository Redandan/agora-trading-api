param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$SourceShadowLogPath = "",
    [string]$Symbol = "BTCUSDT",
    [int]$EntryDedupStrategyId = 508,
    [string]$IntervalCode = "1h",
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [decimal]$ReviewNotionalCapUsdt = 10,
    [int]$ObservationHours = 72,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for EntryDedup direct operator packet arguments."
    }
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
if ($EntryDedupStrategyId -lt 1 -or $EntryDedupStrategyId -gt 1000000) { throw "EntryDedupStrategyId must be between 1 and 1000000." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$entryDedupScript = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
if (-not (Test-Path -LiteralPath $entryDedupScript)) { throw "Missing read-only source script: $entryDedupScript" }

$shadowExitCode = 0
$shadowText = ""
$sourceMode = ""
if ([string]::IsNullOrWhiteSpace($SourceShadowLogPath)) {
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for EntryDedup direct operator packet." }

    $shadowArgs = @(
        "-Symbol", $Symbol,
        "-StrategyId", "$EntryDedupStrategyId",
        "-IntervalCode", $IntervalCode,
        "-TakeProfitPct", "$TakeProfitPct",
        "-StopLossPct", "$StopLossPct",
        "-RoundTripFeePct", "$RoundTripFeePct",
        "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
        "-ObservationHours", "$ObservationHours",
        "-RequireReady"
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $shadowOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $entryDedupScript @shadowArgs 2>&1
        $shadowExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $shadowText = ($shadowOutput | Out-String -Width 4096)
    $sourceMode = "LOCAL_SHADOW_PACKET_SCRIPT"
} else {
    $sourceShadowFullPath = Resolve-RepoPath -Path $SourceShadowLogPath
    if (-not (Test-Path -LiteralPath $sourceShadowFullPath)) { throw "SourceShadowLogPath does not exist: $sourceShadowFullPath" }
    $shadowText = Get-Content -Raw -LiteralPath $sourceShadowFullPath
    $sourceMode = "SOURCE_SHADOW_LOG_PATH"
}

$shadowJson = Get-LastPrefixedValue -Text $shadowText -Prefix "entry_dedup_semantics_shadow_experiment_packet="
$shadowPacket = $null
if (-not [string]::IsNullOrWhiteSpace($shadowJson)) {
    $shadowPacket = $shadowJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($shadowExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics shadow experiment packet completed" }
if ($null -eq $shadowPacket) { Add-MissingRequirement -List $missingRequirements -Value "entry_dedup_semantics_shadow_experiment_packet valid JSON" }

$shadowStatus = ""
if ($null -ne $shadowPacket) {
    $shadowStatus = [string]$shadowPacket.status
}
if ($shadowStatus -ne "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics shadow experiment packet ready"
}

$summary = if ($null -ne $shadowPacket -and $null -ne $shadowPacket.sourceEvidenceSummary) { $shadowPacket.sourceEvidenceSummary } else { $null }
if ($null -eq $summary) {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup source evidence summary attached"
} else {
    if ([int]$summary.exactOpportunityCount -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "exact EntryDedup opportunity count present" }
    if ([int]$summary.stagedAddReviewCandidateOpportunities -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "staged-add review candidate opportunities present" }
    if ([int]$summary.tpHitOpportunities -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "TP-hit opportunity evidence present" }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this direct EntryDedup-only packet to a separate shadow review; keep full profit priority, live policy, staged-add execution, and orders unchanged."
} else {
    "Refresh the EntryDedup semantics shadow packet and resolve missing requirements before direct operator review."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_PACKET"
    status = $status
    symbol = $Symbol
    entryDedupStrategyId = $EntryDedupStrategyId
    intervalCode = $IntervalCode
    sourceMode = $sourceMode
    sourceEntryDedupPacket = "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
    sourceEntryDedupPacketStatus = $shadowStatus
    sourcePriorityDependency = "NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW"
    notFullProfitPriorityDecision = $true
    reviewQuestion = "Approve review-only EntryDedup semantics shadow experiment packet for separate operator review?"
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        liveTradingAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        deployOrEnvChangeAllowed = $false
        policyRelaxationAllowed = $false
        gridMutationAllowed = $false
        telegramSendAllowed = $false
    }
    sourceEvidenceSummary = $summary
    evidenceInterpretation = @(
        "EntryDedup exact opportunities are reviewable without waiting for unrelated profit-priority lanes",
        "NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT remains a live-preflight warning, not a blocker for read-only operator review",
        "OCO_ROUTE_NOT_PROVEN_OR_MISSING remains a hard blocker before any staged-add, order, or policy mutation"
    )
    requiredBeforeAnyMutation = @(
        "fresh read-only production rerun immediately before operator review",
        "runtime EV snapshot for each exact opportunity",
        "EventRiskControl clear or separately approved",
        "duplicate-hash and same-candidate replay protection",
        "daily cap and max-loss budget evidence",
        "OCO feasibility with exact route and lower-timeframe or exchange-side proof",
        "explicit operator approval for any EntryDedup semantics change",
        "separate deploy/env authorization if runtime behavior changes"
    )
    operatorDecisionChoices = @(
        "approve review-only EntryDedup semantics shadow packet",
        "request fresh read-only SSH rerun",
        "reject or defer; keep current EntryDedup policy unchanged"
    )
    forbiddenActions = @(
        "relax EntryDedup",
        "relax DataFreshnessGuard",
        "enable live trading",
        "enable staged-add execution",
        "enable scheduler",
        "place orders",
        "modify or cancel OCO",
        "change production env",
        "deploy",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup semantics direct operator packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[entry-dedup-semantics-direct-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes only prepare_entry_dedup_semantics_shadow_experiment_packet.ps1 or reads SourceShadowLogPath; no full profit priority matrix, SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_entry_dedup_packet=prepare_entry_dedup_semantics_shadow_experiment_packet.ps1 exitCode=$shadowExitCode"
Write-Host "source_mode=$sourceMode"
Write-Host "source_priority_dependency=NOT_REQUIRED_ENTRY_DEDUP_DIRECT_REVIEW"
Write-Host "not_full_profit_priority_decision=true"
Write-Host "source_entry_dedup_packet_status=$shadowStatus"
Write-Host ("entry_dedup_semantics_direct_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_semantics_direct_operator_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "entry_dedup_semantics_direct_operator_status=$status"
Write-Host "entry_dedup_semantics_direct_operator_decision=PREPARE_ENTRY_DEDUP_SHADOW_REVIEW_NOT_LIVE"
Write-Host "entry_dedup_semantics_direct_next_action=$nextAction"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup semantics direct operator packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[entry-dedup-semantics-direct-operator-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup semantics direct operator packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
