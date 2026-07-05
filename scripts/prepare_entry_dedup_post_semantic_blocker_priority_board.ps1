param(
    [string]$OpenExposureSemanticResolutionLogPath = "target/profit-review/entry-dedup-open-exposure-semantic-resolution-latest.log",
    [string]$OpenExposureOperatorChoiceLogPath = "target/profit-review/entry-dedup-open-exposure-operator-choice-latest.log",
    [string]$RuntimeSnapshotCollectorRequestLogPath = "target/profit-review/entry-dedup-runtime-snapshot-collector-activation-request-latest.log",
    [string]$BudgetSnapshotReviewRequestLogPath = "target/profit-review/entry-dedup-budget-snapshot-review-request-latest.log",
    [string]$OcoRouteDryRunRequestLogPath = "target/profit-review/entry-dedup-oco-route-dry-run-request-latest.log",
    [string]$HistoricalEventRiskRowReviewLogPath = "target/profit-review/entry-dedup-historical-event-risk-row-review-latest.log",
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

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

function Add-PriorityItem {
    param(
        [System.Collections.Generic.List[object]]$List,
        [int]$Rank,
        [string]$Blocker,
        [string]$Status,
        [string]$Evidence,
        [string]$RequiredNextAction
    )
    $List.Add([ordered]@{
            rank = $Rank
            blocker = $Blocker
            status = $Status
            evidence = $Evidence
            requiredNextAction = $RequiredNextAction
        })
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @(
        $OpenExposureSemanticResolutionLogPath,
        $OpenExposureOperatorChoiceLogPath,
        $RuntimeSnapshotCollectorRequestLogPath,
        $BudgetSnapshotReviewRequestLogPath,
        $OcoRouteDryRunRequestLogPath,
        $HistoricalEventRiskRowReviewLogPath,
        $ObjectiveTraceabilityLogPath,
        $ReportPath,
        $RunbookPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$semanticLog = Read-FreshLog -Name "entry-dedup-open-exposure-semantic-resolution" -PathValue $OpenExposureSemanticResolutionLogPath -MaxAge $MaxAgeMinutes
$operatorChoiceLog = $null
if (Test-Path -LiteralPath (Resolve-RepoPath -PathValue $OpenExposureOperatorChoiceLogPath)) {
    $operatorChoiceLog = Read-FreshLog -Name "entry-dedup-open-exposure-operator-choice" -PathValue $OpenExposureOperatorChoiceLogPath -MaxAge $MaxAgeMinutes
}
$runtimeLog = Read-FreshLog -Name "entry-dedup-runtime-snapshot-collector-request" -PathValue $RuntimeSnapshotCollectorRequestLogPath -MaxAge $MaxAgeMinutes
$budgetLog = Read-FreshLog -Name "entry-dedup-budget-snapshot-review-request" -PathValue $BudgetSnapshotReviewRequestLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-dry-run-request" -PathValue $OcoRouteDryRunRequestLogPath -MaxAge $MaxAgeMinutes
$historicalLog = Read-FreshLog -Name "entry-dedup-historical-event-risk-row-review" -PathValue $HistoricalEventRiskRowReviewLogPath -MaxAge $MaxAgeMinutes
$traceabilityLog = Read-FreshLog -Name "entry-dedup-objective-traceability" -PathValue $ObjectiveTraceabilityLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($semanticLog, $runtimeLog, $budgetLog, $ocoLog, $historicalLog, $traceabilityLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}
if ($null -ne $operatorChoiceLog -and -not $operatorChoiceLog.Fresh) {
    Add-Missing -List $missing -Value "$($operatorChoiceLog.Name) log fresh within $MaxAgeMinutes minutes"
}

$semanticPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $semanticLog.Text -Prefix "entry_dedup_open_exposure_semantic_resolution_packet=")
$operatorChoicePacket = if ($null -ne $operatorChoiceLog) {
    Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $operatorChoiceLog.Text -Prefix "entry_dedup_open_exposure_operator_choice_packet=")
} else {
    $null
}
$runtimePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $runtimeLog.Text -Prefix "entry_dedup_runtime_snapshot_collector_activation_request_packet=")
$budgetPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $budgetLog.Text -Prefix "entry_dedup_budget_snapshot_review_request_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_dry_run_request_packet=")
$historicalPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $historicalLog.Text -Prefix "entry_dedup_historical_event_risk_row_review_packet=")
$traceabilityPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $traceabilityLog.Text -Prefix "entry_dedup_review_only_objective_traceability_packet=")
if ($null -eq $semanticPacket) { Add-Missing -List $missing -Value "open exposure semantic resolution packet JSON present" }
if ($null -ne $operatorChoiceLog -and $null -eq $operatorChoicePacket) { Add-Missing -List $missing -Value "open exposure operator choice packet JSON present" }
if ($null -eq $runtimePacket) { Add-Missing -List $missing -Value "runtime snapshot collector request packet JSON present" }
if ($null -eq $budgetPacket) { Add-Missing -List $missing -Value "budget snapshot review request packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO route dry-run request packet JSON present" }
if ($null -eq $historicalPacket) { Add-Missing -List $missing -Value "historical EventRisk row review packet JSON present" }
if ($null -eq $traceabilityPacket) { Add-Missing -List $missing -Value "objective traceability packet JSON present" }

$expectedStatuses = [ordered]@{
    semanticResolution = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE"
    openExposureOperatorChoice = "OPTIONAL_NOT_PROVIDED"
    runtimeSnapshotRequest = "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
    budgetSnapshotRequest = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
    ocoRouteRequest = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
    historicalEventRisk = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
    objectiveTraceability = "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE"
}
$actualStatuses = [ordered]@{
    semanticResolution = [string](Get-Prop -Object $semanticPacket -Name "status" -Default "UNKNOWN")
    openExposureOperatorChoice = if ($null -ne $operatorChoicePacket) { [string](Get-Prop -Object $operatorChoicePacket -Name "status" -Default "UNKNOWN") } else { "OPTIONAL_NOT_PROVIDED" }
    runtimeSnapshotRequest = [string](Get-Prop -Object $runtimePacket -Name "status" -Default "UNKNOWN")
    budgetSnapshotRequest = [string](Get-Prop -Object $budgetPacket -Name "status" -Default "UNKNOWN")
    ocoRouteRequest = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
    historicalEventRisk = [string](Get-Prop -Object $historicalPacket -Name "status" -Default "UNKNOWN")
    objectiveTraceability = [string](Get-Prop -Object $traceabilityPacket -Name "status" -Default "UNKNOWN")
}
foreach ($key in $expectedStatuses.Keys) {
    if ($key -eq "openExposureOperatorChoice") {
        if ($actualStatuses[$key] -eq "OPTIONAL_NOT_PROVIDED" -or $actualStatuses[$key] -eq "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE") {
            continue
        }
    }
    if ($actualStatuses[$key] -ne $expectedStatuses[$key]) {
        Add-Missing -List $missing -Value "$key packet ready"
    }
}

$symbol = [string](Get-Prop -Object $semanticPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $semanticPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $semanticPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "exactOpportunityCount") -Default 0)
$semanticOperatorChoiceRequired = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "operatorSemanticsChoiceRequired") -Default $false)
$actualAutoExposureClear = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "actualAutoExposureClear") -Default $false)
$openExposureOperatorChoiceReady = $actualStatuses["openExposureOperatorChoice"] -eq "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
$openExposureOperatorChoiceConfirmText = if ($openExposureOperatorChoiceReady) {
    [string](Get-NestedProp -Object $operatorChoicePacket -Path @("proposedChange", "confirmText") -Default "")
} else {
    ""
}
$openExposureOperatorChoiceRecommendedReviewChoice = if ($openExposureOperatorChoiceReady) {
    [string](Get-NestedProp -Object $operatorChoicePacket -Path @("proposedChange", "recommendedReviewChoice") -Default "")
} else {
    ""
}
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $runtimePacket -Path @("requestEvidence", "runtimeSnapshotCoverageCleared") -Default $true)
$candidateEntryPlanRows = Get-IntValue (Get-NestedProp -Object $runtimePacket -Path @("requestEvidence", "candidateRuntimeEntryPlanRows") -Default 0)
$budgetRuntimeCleared = Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "budgetSnapshotRuntimeCleared") -Default $true)
$budgetContractReady = (Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "contractIncludesDailyCapSnapshot") -Default $false)) -and (Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "contractIncludesMaxLossSnapshot") -Default $false))
$ocoRouteProofCleared = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("requestEvidence", "routeProofCleared") -Default $true)
$ocoExchangeDryRunRequired = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("requestEvidence", "exchangeDryRunRequired") -Default $false)
$historicalEventRiskReviewRequired = Get-BoolValue (Get-NestedProp -Object $historicalPacket -Path @("historicalEventRiskReview", "reviewRequiredBeforeMutation") -Default $false)
$currentEventRiskR0Clear = Get-BoolValue (Get-NestedProp -Object $historicalPacket -Path @("historicalEventRiskReview", "currentR0Clear") -Default $false)
$traceabilityOrderReadiness = [string](Get-NestedProp -Object $traceabilityPacket -Path @("requirementTraceability", "nonLiveGuardrails", "orderReadiness") -Default "UNKNOWN")
$traceabilityAllMutationFlagsFalse = Get-BoolValue (Get-NestedProp -Object $traceabilityPacket -Path @("requirementTraceability", "nonLiveGuardrails", "allMutationFlagsFalse") -Default $false)

$runtimeSnapshotBlocked = -not $runtimeSnapshotCleared
$budgetRuntimeBlocked = -not $budgetRuntimeCleared
$ocoRouteBlocked = (-not $ocoRouteProofCleared) -or $ocoExchangeDryRunRequired
$historicalEventRiskBlocked = $historicalEventRiskReviewRequired
$openExposureSemanticBlocked = $semanticOperatorChoiceRequired

$priorityItems = [System.Collections.Generic.List[object]]::new()
if ($openExposureSemanticBlocked) {
    $openExposureEvidencePath = if ($openExposureOperatorChoiceReady) { $OpenExposureOperatorChoiceLogPath } else { $OpenExposureSemanticResolutionLogPath }
    $openExposureStatus = if ($openExposureOperatorChoiceReady) { "BLOCKED_OPERATOR_CHOICE_READY_NOT_AUTHORIZED" } else { "BLOCKED_OPERATOR_CHOICE_REQUIRED" }
    $openExposureRequiredNextAction = if ($openExposureOperatorChoiceReady) {
        "Review the open-exposure operator choice packet; exact authorization text for the default-off implementation review is $openExposureOperatorChoiceConfirmText."
    } else {
        "Review whether the zero-qty non-auto missing-OCO row remains a blocker, is resolved outside this packet, or needs a separately authorized semantics change."
    }
    Add-PriorityItem -List $priorityItems -Rank 1 -Blocker "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS" -Status $openExposureStatus -Evidence $openExposureEvidencePath -RequiredNextAction $openExposureRequiredNextAction
}
if ($runtimeSnapshotBlocked) {
    Add-PriorityItem -List $priorityItems -Rank 2 -Blocker "CANDIDATE_RUNTIME_ENTRY_PLAN_SNAPSHOT" -Status "BLOCKED_COLLECTOR_ACTIVATION_NOT_AUTHORIZED" -Evidence $RuntimeSnapshotCollectorRequestLogPath -RequiredNextAction "Separately authorize and verify shadow runtime snapshot collection before any mutation request."
}
if ($budgetRuntimeBlocked) {
    Add-PriorityItem -List $priorityItems -Rank 3 -Blocker "DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT" -Status "BLOCKED_RUNTIME_BUDGET_SNAPSHOT_MISSING" -Evidence $BudgetSnapshotReviewRequestLogPath -RequiredNextAction "Collect and verify daily-cap/max-loss shadow snapshots only after separate runtime-evidence authorization."
}
if ($ocoRouteBlocked) {
    Add-PriorityItem -List $priorityItems -Rank 4 -Blocker "EXACT_OCO_ROUTE_PROOF" -Status "BLOCKED_EXCHANGE_DRY_RUN_NOT_AUTHORIZED" -Evidence $OcoRouteDryRunRequestLogPath -RequiredNextAction "Run no exchange dry-run until open-exposure semantics and a separate exact OCO dry-run authorization are complete."
}
if ($historicalEventRiskBlocked) {
    Add-PriorityItem -List $priorityItems -Rank 5 -Blocker "HISTORICAL_EVENT_RISK_ROW" -Status "BLOCKED_HISTORICAL_ROW_REVIEW_REQUIRED" -Evidence $HistoricalEventRiskRowReviewLogPath -RequiredNextAction "Review the historical/non-auto EventRisk row identity and classify or resolve it outside this packet."
}

$remainingBlockerCount = $priorityItems.Count
$nextBlocker = if ($remainingBlockerCount -gt 0) { [string]$priorityItems[0].blocker } else { "NONE" }

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Post-Semantic Blocker Priority Board") -and $reportText.Contains("POST_SEMANTIC_BLOCKER_PRIORITY_BOARD")
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_post_semantic_blocker_priority_board.ps1") -and $runbookNormalizedText.Contains("priority board is not order readiness")

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $actualAutoExposureClear) { Add-Missing -List $missing -Value "actual auto exposure is clear before blocker ordering" }
if (-not $semanticOperatorChoiceRequired) { Add-Missing -List $missing -Value "open exposure semantic choice remains required" }
if (-not $runtimeSnapshotBlocked) { Add-Missing -List $missing -Value "runtime snapshot blocker remains visible" }
if ($candidateEntryPlanRows -ne 0) { Add-Missing -List $missing -Value "candidate runtime entry-plan rows remain missing in current evidence" }
if (-not $budgetContractReady) { Add-Missing -List $missing -Value "budget snapshot local contract is ready" }
if (-not $budgetRuntimeBlocked) { Add-Missing -List $missing -Value "budget runtime blocker remains visible" }
if (-not $ocoRouteBlocked) { Add-Missing -List $missing -Value "OCO route blocker remains visible" }
if (-not $historicalEventRiskBlocked) { Add-Missing -List $missing -Value "historical EventRisk blocker remains visible" }
if (-not $currentEventRiskR0Clear) { Add-Missing -List $missing -Value "current EventRisk R0 remains clear" }
if ($traceabilityOrderReadiness -ne "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE") { Add-Missing -List $missing -Value "objective traceability keeps order readiness blocked" }
if (-not $traceabilityAllMutationFlagsFalse) { Add-Missing -List $missing -Value "objective traceability mutation flags remain false" }
if ($remainingBlockerCount -lt 5) { Add-Missing -List $missing -Value "all five post-semantic blockers remain represented" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes post-semantic priority board" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes post-semantic priority board instructions" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "KEEP_ENTRY_DEDUP_BLOCKED_AND_PRIORITIZE_REVIEW_ONLY_CLEARANCE_SEQUENCE"
} else {
    "REFRESH_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceStatuses = $actualStatuses
    priorityBoard = @($priorityItems)
    remainingBlockerCount = $remainingBlockerCount
    nextBlocker = $nextBlocker
    boardEvidence = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        actualAutoExposureClear = $actualAutoExposureClear
        openExposureSemanticBlocked = $openExposureSemanticBlocked
        openExposureOperatorChoiceReady = $openExposureOperatorChoiceReady
        openExposureOperatorChoiceConfirmText = $openExposureOperatorChoiceConfirmText
        openExposureOperatorChoiceRecommendedReviewChoice = $openExposureOperatorChoiceRecommendedReviewChoice
        runtimeSnapshotBlocked = $runtimeSnapshotBlocked
        budgetRuntimeBlocked = $budgetRuntimeBlocked
        budgetContractReady = $budgetContractReady
        ocoRouteBlocked = $ocoRouteBlocked
        historicalEventRiskBlocked = $historicalEventRiskBlocked
        currentEventRiskR0Clear = $currentEventRiskR0Clear
        traceabilityOrderReadiness = $traceabilityOrderReadiness
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        priorityBoardReady = $ready
        liveExecutionReady = $false
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
    nextAction = "Use this board to follow the post-semantic review-only clearance order; do not treat the board or request packets as order readiness."
    notAuthorization = "read-only EntryDedup post-semantic blocker priority board only; does not clear blockers, activate collectors, write runtime evidence, change semantics/policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-post-semantic-blocker-priority-board] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup review-only blocker packets plus local report/runbook only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_post_semantic_blocker_priority_board_status=$status"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_decision=$decision"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_remaining_blocker_count=$remainingBlockerCount"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_next_blocker=$nextBlocker"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_actual_auto_exposure_clear=$($actualAutoExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_open_exposure_semantic_blocked=$($openExposureSemanticBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_ready=$($openExposureOperatorChoiceReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_confirm_text=$openExposureOperatorChoiceConfirmText"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_open_exposure_operator_choice_recommended_review_choice=$openExposureOperatorChoiceRecommendedReviewChoice"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_runtime_snapshot_blocked=$($runtimeSnapshotBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_budget_runtime_blocked=$($budgetRuntimeBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_oco_route_blocked=$($ocoRouteBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_historical_event_risk_blocked=$($historicalEventRiskBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_traceability_order_readiness=$traceabilityOrderReadiness"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_post_semantic_blocker_priority_board_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_post_semantic_blocker_priority_board_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_post_semantic_blocker_priority_board_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "priority_board_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "live_execution_ready=false"
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
Write-Host "notAuthorization=read-only EntryDedup post-semantic blocker priority board only; does not clear blockers, activate collectors, write runtime evidence, change semantics/policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-post-semantic-blocker-priority-board] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup post-semantic blocker priority board is not ready: $status; missing=$(@($missing) -join '; ')"
}
