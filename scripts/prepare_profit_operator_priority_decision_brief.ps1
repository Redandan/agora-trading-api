param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [decimal]$ReviewNotionalCapUsdt = 25,
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

function Convert-ToNullableInt {
    param([object]$Value)
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) { return $parsed }
    return $null
}

function Get-ReviewItem {
    param([object[]]$Items, [string]$ProposalId)
    $match = @($Items | Where-Object { [string]$_.proposalId -eq $ProposalId } | Select-Object -First 1)
    if ($match.Count -lt 1) { return $null }
    return $match[0]
}

function Get-SafeLane {
    param([object]$Item)
    if ($null -eq $Item) { return "" }
    $properties = @($Item.PSObject.Properties.Name)
    if ($properties -contains "lane") { return [string]$Item.lane }
    if ($Item -is [string]) { return [string]$Item }
    return ""
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator priority decision brief arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$consolidatedScript = Join-Path $PSScriptRoot "prepare_profit_operator_consolidated_review_packet.ps1"
if (-not (Test-Path -LiteralPath $consolidatedScript)) {
    throw "Missing consolidated review packet script: $consolidatedScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit operator priority decision brief." }

$consolidatedArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $consolidatedOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $consolidatedScript @consolidatedArgs 2>&1
    $consolidatedExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$consolidatedText = ($consolidatedOutput | Out-String -Width 4096)
$consolidatedJson = Get-LastPrefixedValue -Text $consolidatedText -Prefix "profit_operator_consolidated_review_packet="
$consolidatedPacket = $null
if (-not [string]::IsNullOrWhiteSpace($consolidatedJson)) {
    $consolidatedPacket = $consolidatedJson | ConvertFrom-Json -ErrorAction Stop
}

$sourceMatrixFreshnessStatus = ""
$sourceMatrixAgeMinutes = $null
if ($null -ne $consolidatedPacket) {
    if ($null -ne $consolidatedPacket.PSObject.Properties["sourceMatrixFreshnessStatus"]) {
        $sourceMatrixFreshnessStatus = [string]$consolidatedPacket.sourceMatrixFreshnessStatus
    }
}
if ([string]::IsNullOrWhiteSpace($sourceMatrixFreshnessStatus)) {
    $sourceMatrixFreshnessStatus = Get-LastPrefixedValue -Text $consolidatedText -Prefix "profit_operator_review_summary_freshness_status="
}
if ([string]::IsNullOrWhiteSpace($sourceMatrixFreshnessStatus)) {
    $sourceMatrixFreshnessStatus = Get-LastPrefixedValue -Text $consolidatedText -Prefix "source_matrix_freshness_status="
}
if ($null -eq $sourceMatrixAgeMinutes) {
    $sourceMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $consolidatedText -Prefix "profit_operator_review_summary_matrix_age_minutes=")
}
if ($null -eq $sourceMatrixAgeMinutes) {
    $sourceMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $consolidatedText -Prefix "source_matrix_age_minutes=")
}
if ($null -eq $sourceMatrixAgeMinutes) {
    $sourceMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $consolidatedText -Prefix "matrix_age_minutes=")
}
$sourceMatrixFreshness = [pscustomobject]@{
    Status = $sourceMatrixFreshnessStatus
    AgeMinutes = $sourceMatrixAgeMinutes
    MaxAgeMinutes = $MatrixMaxAgeMinutes
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($consolidatedExitCode -ne 0 -and $null -eq $consolidatedPacket) { Add-MissingRequirement -List $missingRequirements -Value "consolidated review packet completed" }
if ($null -eq $consolidatedPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_consolidated_review_packet valid JSON" }

$readyReviewItems = @()
$blockedPolicyLanes = @()
if ($null -ne $consolidatedPacket) {
    if ([string]$consolidatedPacket.status -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
        Add-MissingRequirement -List $missingRequirements -Value "consolidated review packet ready"
    }
    if ([string]$consolidatedPacket.sourceMatrixFreshnessStatus -ne "FRESH") {
        Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
    }
    $readyReviewItems = @($consolidatedPacket.readyReviewItems)
    $blockedPolicyLanes = @($consolidatedPacket.blockedPolicyLanes)
}

$trailingItem = Get-ReviewItem -Items $readyReviewItems -ProposalId "trailing-stop-dry-run-operator-review"
$strategy485Item = Get-ReviewItem -Items $readyReviewItems -ProposalId "strategy485-risk-reduction-shadow-operator-review"
$entryDedupItem = Get-ReviewItem -Items $readyReviewItems -ProposalId "entry-dedup-semantics-shadow-operator-review"

foreach ($required in @(
        "trailing-stop-dry-run-operator-review",
        "strategy485-risk-reduction-shadow-operator-review",
        "entry-dedup-semantics-shadow-operator-review"
    )) {
    if (@($readyReviewItems | Where-Object { [string]$_.proposalId -eq $required }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "ready review item present: $required"
    }
}
foreach ($requiredBlocked in @("entry-filter", "data-freshness-replay")) {
    if (@($blockedPolicyLanes | Where-Object { (Get-SafeLane -Item $_) -eq $requiredBlocked }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "blocked policy lane present: $requiredBlocked"
    }
}

$rankedItems = @(
    [pscustomobject]@{
        rank = 1
        proposalId = "trailing-stop-dry-run-operator-review"
        lane = if ($null -ne $trailingItem) { [string]$trailingItem.lane } else { "trailing-stop-dry-run" }
        decisionFocus = "TRAILING_STOP_DRY_RUN_REVIEW"
        priorityClass = "P1_LOW_MUTATION_REVIEW_WITH_STRONG_EXIT_EVIDENCE"
        recommendedNextPacket = "prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady"
        evidenceStrength = "strongest_quantified_exit_side_sample"
        riskReason = "dry-run/operator review can proceed without order, OCO, scheduler, deploy, or policy mutation"
        requiredBeforeDecision = @(
            "confirm source matrix is still fresh",
            "attach trailing_stop_acceptance=PASS",
            "attach improvement pct, delta PnL, accepted rows, and ambiguous same-bar exclusion",
            "keep scheduler disabled or dry-run until separately authorized"
        )
        forbiddenFromThisBrief = @(
            "enable trailing scheduler",
            "enable live trading",
            "modify OCO",
            "place orders",
            "deploy or change production env"
        )
        nextAction = "Use as the first operator decision focus; review dry-run rollout scope only."
    },
    [pscustomobject]@{
        rank = 2
        proposalId = "strategy485-risk-reduction-shadow-operator-review"
        lane = if ($null -ne $strategy485Item) { [string]$strategy485Item.lane } else { "strategy485-risk-reduction-shadow" }
        decisionFocus = "STRATEGY485_RISK_REDUCTION_SHADOW_REVIEW"
        priorityClass = "P1_CURRENT_POSITION_RISK_REVIEW_NOT_MUTATION"
        recommendedNextPacket = "prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady"
        evidenceStrength = "current_position_risk_evidence"
        riskReason = "negative-EV active positions are time-sensitive, but every close-position or OCO action requires separate explicit mutation authorization"
        requiredBeforeDecision = @(
            "confirm current OCO health is still OK",
            "attach negative-EV position count and per-position summaries",
            "attach TP stretch and timeout evidence",
            "keep close-position and OCO mutation out of this brief"
        )
        forbiddenFromThisBrief = @(
            "close positions",
            "modify or cancel OCO",
            "place orders",
            "deploy or change production env"
        )
        nextAction = "Keep as second operator focus; prepare risk review only, not a mutation instruction."
    },
    [pscustomobject]@{
        rank = 3
        proposalId = "entry-dedup-semantics-shadow-operator-review"
        lane = if ($null -ne $entryDedupItem) { [string]$entryDedupItem.lane } else { "entry-filter-shadow" }
        decisionFocus = "ENTRY_DEDUP_SEMANTICS_SHADOW_REVIEW"
        priorityClass = "P2_SHADOW_ALPHA_REVIEW_POLICY_RISK"
        recommendedNextPacket = "prepare_entry_dedup_operator_decision_brief_ssh.ps1 -RequireDecisionReady"
        evidenceStrength = "shadow_alpha_candidate_requires_fresh_rerun"
        riskReason = "EntryDedup semantics can affect live entry policy, so it stays behind fresh SSH evidence, ExpectedValueGate, EventRiskControl, and OCO feasibility review"
        requiredBeforeDecision = @(
            "rerun fresh production EntryDedup shadow packet before operator decision",
            "confirm positive skew and no same-bar TP/SL ambiguity",
            "collect ExpectedValueGate and EventRiskControl evidence",
            "prove TP/SL/OCO feasibility before any future mutation request"
        )
        forbiddenFromThisBrief = @(
            "relax EntryDedup",
            "relax DataFreshnessGuard",
            "enable staged-add or live entry execution",
            "place orders",
            "modify OCO",
            "deploy or change production env"
        )
        nextAction = "Keep reviewable as shadow-only evidence; do not convert it into policy approval."
    }
)

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_OPERATOR_DECISION_NOT_LIVE" } else { "NOT_READY" }
$primaryFocus = if ($ready) { "trailing-stop-dry-run-operator-review" } else { "" }
$nextAction = if ($ready) {
    "Prioritize trailing-stop dry-run operator review first, then strategy485 risk shadow review, then EntryDedup semantics shadow review; keep blocked policy lanes unchanged."
} else {
    "Refresh consolidated read-only evidence and resolve missing requirements before prioritizing operator decisions."
}

$brief = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_PRIORITY_DECISION_BRIEF"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_profit_operator_consolidated_review_packet.ps1"
    sourcePacketStatus = if ($null -ne $consolidatedPacket) { [string]$consolidatedPacket.status } else { "" }
    sourceMatrixFreshnessStatus = $sourceMatrixFreshnessStatus
    sourceMatrixFreshness = $sourceMatrixFreshness
    primaryFocus = $primaryFocus
    rankedReviewItems = @($rankedItems)
    blockedPolicyLanes = @($blockedPolicyLanes)
    blockedPolicyHandling = @(
        "entry-filter remains blocked by signal/governance/missed-opportunity evidence",
        "data-freshness-replay remains blocked until replayCandidateId and counterfactual rows are complete",
        "blocked lanes are evidence-collection work, not policy approval"
    )
    operatorDecisionOrder = @(
        "1. trailing-stop dry-run review",
        "2. strategy485 risk-reduction shadow review",
        "3. EntryDedup semantics shadow review"
    )
    allowedActions = @(
        "operator review",
        "read-only evidence refresh",
        "dry-run or shadow design discussion"
    )
    forbiddenActions = @(
        "enable live trading",
        "enable scheduler",
        "place orders",
        "modify or cancel OCO",
        "close positions",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only profit operator priority decision brief only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[profit-operator-priority-decision-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_consolidated_review_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $consolidatedText
Write-Host "source_packet=prepare_profit_operator_consolidated_review_packet.ps1 exitCode=$consolidatedExitCode"
Write-Host "source_matrix_freshness_status=$sourceMatrixFreshnessStatus"
Write-Host "source_matrix_age_minutes=$sourceMatrixAgeMinutes"
Write-Host "source_matrix_max_age_minutes=$MatrixMaxAgeMinutes"
Write-Host "profit_operator_priority_primary_focus=$primaryFocus"
Write-Host ("profit_operator_priority_ranked_items=" + (ConvertTo-Json -Compress -Depth 10 @($rankedItems)))
Write-Host ("profit_operator_priority_blocked_policy_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($blockedPolicyLanes)))
Write-Host ("profit_operator_priority_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_operator_priority_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 12 $brief))
Write-Host "profit_operator_priority_decision_brief_status=$status"
Write-Host "profit_operator_priority_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only profit operator priority decision brief only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[profit-operator-priority-decision-brief] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Profit operator priority decision brief is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
