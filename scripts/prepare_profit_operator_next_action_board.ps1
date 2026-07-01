param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$PriorityDecisionLogPath = "target/profit-review/profit-operator-priority-decision-brief-latest.log",
    [string]$Strategy574GateLogPath = "target/profit-review/strategy574-signal-review-gate-refresh.log",
    [string]$TinyLiveLossRcaLogPath = "target/profit-review/tiny-live-loss-rca-refresh.log",
    [string]$NearThresholdShadowObservationLogPath = "target/profit-review/strategy574-near-threshold-shadow-observation-latest.log",
    [string]$AuditLogPath = "target/profit-review/profit-live-blocker-audit-packet-latest.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$Strategy574Id = 574,
    [string]$Side = "LONG",
    [decimal]$ReviewNotionalCapUsdt = 25,
    [int]$ObservationHours = 72,
    [switch]$RequireAudit,
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

function Invoke-LocalPacket {
    param(
        [string]$ScriptPath,
        [string[]]$Arguments
    )
    if (-not (Test-Path -LiteralPath $ScriptPath)) { throw "Missing packet script: $ScriptPath" }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit operator next action board." }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

function Get-LanePriority {
    param([string]$Lane)
    switch ($Lane) {
        "trailing-stop-dry-run" { return 1 }
        "strategy485-risk-reduction" { return 2 }
        "entry-dedup-semantics" { return 3 }
        "data-freshness-collector-activation" { return 4 }
        "data-freshness-replay-blocker" { return 5 }
        "tp-sl-oco-feasibility" { return 6 }
        "strategy574-tiny-live-governance" { return 7 }
        "profit-priority" { return 8 }
        default { return 99 }
    }
}

function Get-LaneDecisionFocus {
    param([string]$Lane)
    switch ($Lane) {
        "trailing-stop-dry-run" { return "TRAILING_STOP_DRY_RUN_REVIEW" }
        "strategy485-risk-reduction" { return "STRATEGY485_RISK_REDUCTION_SHADOW_REVIEW" }
        "entry-dedup-semantics" { return "ENTRY_DEDUP_SEMANTICS_SHADOW_REVIEW" }
        "data-freshness-collector-activation" { return "DATAFRESHNESS_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW" }
        "data-freshness-replay-blocker" { return "DATAFRESHNESS_REPLAY_BLOCKER_REVIEW" }
        "tp-sl-oco-feasibility" { return "TP_SL_OCO_FEASIBILITY_REVIEW" }
        "strategy574-tiny-live-governance" { return "STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW" }
        "profit-priority" { return "PROFIT_PRIORITY_CONTEXT_REVIEW" }
        default { return "REVIEW_ONLY_CONTEXT" }
    }
}

function Get-LanePriorityClass {
    param([string]$Lane)
    switch ($Lane) {
        "trailing-stop-dry-run" { return "P1_EXIT_DRY_RUN_REVIEW_WITH_STRONG_REPLAY_EVIDENCE" }
        "strategy485-risk-reduction" { return "P1_RISK_REDUCTION_SHADOW_REVIEW" }
        "entry-dedup-semantics" { return "P1_ENTRY_DEDUP_SHADOW_REVIEW" }
        "data-freshness-collector-activation" { return "P2_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_ONLY" }
        "data-freshness-replay-blocker" { return "P2_REPLAY_BLOCKER_REVIEW_ONLY" }
        "tp-sl-oco-feasibility" { return "P2_OCO_FEASIBILITY_REVIEW" }
        "strategy574-tiny-live-governance" { return "P2_TINY_LIVE_GOVERNANCE_REVIEW" }
        "profit-priority" { return "P3_AGGREGATE_CONTEXT" }
        default { return "P3_REVIEW_ONLY_CONTEXT" }
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator next action board arguments."
}
if ([string]::IsNullOrWhiteSpace($Side) -or $Side.Length -gt 32 -or $Side -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Side contains unsupported characters for profit operator next action board arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($Strategy574Id -lt 1 -or $Strategy574Id -gt 1000000) { throw "Strategy574Id must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$priorityScript = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$strategy574Script = Join-Path $PSScriptRoot "prepare_strategy574_tiny_live_governance_operator_packet.ps1"
$prioritySourceMode = "FRESH_LOCAL_PACKET"
$priorityLogFreshnessStatus = "NOT_REQUESTED"
$priorityLogAgeMinutes = $null
$priorityResult = $null
$auditLogFreshnessStatus = "MISSING"
$auditLogAgeMinutes = $null
$auditJson = ""
$auditPacket = $null
if (-not [string]::IsNullOrWhiteSpace($AuditLogPath) -and (Test-Path -LiteralPath $AuditLogPath)) {
    $auditItem = Get-Item -LiteralPath $AuditLogPath
    $auditLogAgeMinutes = [int]((Get-Date) - $auditItem.LastWriteTime).TotalMinutes
    $auditLogFreshnessStatus = if ($auditLogAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    $auditText = Get-Content -Raw -LiteralPath $AuditLogPath
    $auditJson = Get-LastPrefixedValue -Text $auditText -Prefix "profit_live_blocker_audit_packet="
    if (-not [string]::IsNullOrWhiteSpace($auditJson)) {
        $auditPacket = $auditJson | ConvertFrom-Json -ErrorAction Stop
    }
}
if (-not [string]::IsNullOrWhiteSpace($PriorityDecisionLogPath) -and (Test-Path -LiteralPath $PriorityDecisionLogPath)) {
    $priorityLogItem = Get-Item -LiteralPath $PriorityDecisionLogPath
    $priorityLogAgeMinutes = [int]((Get-Date) - $priorityLogItem.LastWriteTime).TotalMinutes
    $priorityLogFreshnessStatus = if ($priorityLogAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    if ($priorityLogFreshnessStatus -eq "FRESH") {
        $prioritySourceMode = "REUSED_PRIORITY_DECISION_LOG"
        $priorityResult = [pscustomobject]@{
            Text = Get-Content -Raw -LiteralPath $PriorityDecisionLogPath
            ExitCode = 0
        }
    }
}
if ($null -eq $priorityResult) {
    $priorityResult = Invoke-LocalPacket -ScriptPath $priorityScript -Arguments @(
        "-ReviewOutputDir", $ReviewOutputDir,
        "-MatrixMaxAgeMinutes", "$MaxAgeMinutes",
        "-Symbol", $Symbol,
        "-StrategyId", "$StrategyId",
        "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
        "-ObservationHours", "$ObservationHours",
        "-RequireReady"
    )
}

$strategy574Result = Invoke-LocalPacket -ScriptPath $strategy574Script -Arguments @(
    "-Strategy574GateLogPath", $Strategy574GateLogPath,
    "-TinyLiveLossRcaLogPath", $TinyLiveLossRcaLogPath,
    "-NearThresholdShadowObservationLogPath", $NearThresholdShadowObservationLogPath,
    "-MaxAgeMinutes", "$MaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$Strategy574Id",
    "-Side", $Side,
    "-RequireReady"
)

$priorityJson = Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "profit_operator_priority_decision_brief_packet="
$strategy574Json = Get-LastPrefixedValue -Text $strategy574Result.Text -Prefix "strategy574_tiny_live_governance_operator_packet="
$priorityPacket = $null
$strategy574Packet = $null
if (-not [string]::IsNullOrWhiteSpace($priorityJson)) {
    $priorityPacket = $priorityJson | ConvertFrom-Json -ErrorAction Stop
}
if (-not [string]::IsNullOrWhiteSpace($strategy574Json)) {
    $strategy574Packet = $strategy574Json | ConvertFrom-Json -ErrorAction Stop
}

$priorityMatrixFreshnessStatus = ""
$priorityMatrixAgeMinutes = $null
$priorityMatrixMaxAgeMinutes = $null
if ($null -ne $priorityPacket) {
    $priorityProperties = @($priorityPacket.PSObject.Properties.Name)
    if ($priorityProperties -contains "sourceMatrixFreshness" -and $null -ne $priorityPacket.sourceMatrixFreshness) {
        $freshnessProperties = @($priorityPacket.sourceMatrixFreshness.PSObject.Properties.Name)
        if ($freshnessProperties -contains "Status") {
            $priorityMatrixFreshnessStatus = [string]$priorityPacket.sourceMatrixFreshness.Status
        } elseif ($freshnessProperties -contains "status") {
            $priorityMatrixFreshnessStatus = [string]$priorityPacket.sourceMatrixFreshness.status
        }
        if ($freshnessProperties -contains "AgeMinutes") {
            $priorityMatrixAgeMinutes = Convert-ToNullableInt -Value $priorityPacket.sourceMatrixFreshness.AgeMinutes
        } elseif ($freshnessProperties -contains "ageMinutes") {
            $priorityMatrixAgeMinutes = Convert-ToNullableInt -Value $priorityPacket.sourceMatrixFreshness.ageMinutes
        }
        if ($freshnessProperties -contains "MaxAgeMinutes") {
            $priorityMatrixMaxAgeMinutes = Convert-ToNullableInt -Value $priorityPacket.sourceMatrixFreshness.MaxAgeMinutes
        } elseif ($freshnessProperties -contains "maxAgeMinutes") {
            $priorityMatrixMaxAgeMinutes = Convert-ToNullableInt -Value $priorityPacket.sourceMatrixFreshness.maxAgeMinutes
        }
    }
    if ([string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus) -and $priorityProperties -contains "sourceMatrixFreshnessStatus") {
        $priorityMatrixFreshnessStatus = [string]$priorityPacket.sourceMatrixFreshnessStatus
    }
}
if ([string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus)) {
    $priorityMatrixFreshnessStatus = Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "profit_operator_review_summary_freshness_status="
}
if ([string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus)) {
    $priorityMatrixFreshnessStatus = Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "source_matrix_freshness_status="
}
if ($null -eq $priorityMatrixAgeMinutes) {
    $priorityMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "profit_operator_review_summary_matrix_age_minutes=")
}
if ($null -eq $priorityMatrixAgeMinutes) {
    $priorityMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "source_matrix_age_minutes=")
}
if ($null -eq $priorityMatrixAgeMinutes) {
    $priorityMatrixAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "matrix_age_minutes=")
}
if ($null -eq $priorityMatrixMaxAgeMinutes) {
    $priorityMatrixMaxAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "source_matrix_max_age_minutes=")
}
if ($null -eq $priorityMatrixMaxAgeMinutes) {
    $priorityMatrixMaxAgeMinutes = Convert-ToNullableInt -Value (Get-LastPrefixedValue -Text $priorityResult.Text -Prefix "matrix_max_age_minutes=")
}
$priorityMatrixFreshnessAttached = (-not [string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus)) -or ($null -ne $priorityMatrixAgeMinutes)
$priorityMatrixFreshForBoardMaxAge = $true
if ($prioritySourceMode -eq "REUSED_PRIORITY_DECISION_LOG") {
    $priorityMatrixFreshForBoardMaxAge = $false
    if ($priorityMatrixFreshnessAttached -and $priorityMatrixFreshnessStatus -eq "FRESH" -and $null -ne $priorityMatrixAgeMinutes -and $priorityMatrixAgeMinutes -le $MaxAgeMinutes) {
        $priorityMatrixFreshForBoardMaxAge = $true
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($priorityResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "profit priority decision brief completed" }
if ($strategy574Result.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance packet completed" }
if ($null -eq $priorityPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_priority_decision_brief_packet valid JSON" }
if ($null -eq $strategy574Packet) { Add-MissingRequirement -List $missingRequirements -Value "strategy574_tiny_live_governance_operator_packet valid JSON" }
if ($prioritySourceMode -eq "REUSED_PRIORITY_DECISION_LOG") {
    if (-not $priorityMatrixFreshnessAttached) { Add-MissingRequirement -List $missingRequirements -Value "priority decision source matrix freshness attached" }
    if ([string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus)) { Add-MissingRequirement -List $missingRequirements -Value "priority decision source matrix freshness status attached" }
    if (-not [string]::IsNullOrWhiteSpace($priorityMatrixFreshnessStatus) -and $priorityMatrixFreshnessStatus -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "priority decision source matrix freshness is FRESH" }
    if ($null -eq $priorityMatrixAgeMinutes) { Add-MissingRequirement -List $missingRequirements -Value "priority decision source matrix age attached" }
    if (-not $priorityMatrixFreshForBoardMaxAge) { Add-MissingRequirement -List $missingRequirements -Value "priority decision source matrix fresh for board max age" }
}
if ($RequireAudit -and $auditLogFreshnessStatus -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "profit live blocker audit log present" }
if ($auditLogFreshnessStatus -eq "STALE") { Add-MissingRequirement -List $missingRequirements -Value "profit live blocker audit log fresh" }
if (($RequireAudit -or $auditLogFreshnessStatus -ne "MISSING") -and $null -eq $auditPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_live_blocker_audit_packet valid JSON" }
if ($null -ne $priorityPacket -and [string]$priorityPacket.status -ne "READY_FOR_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "profit priority decision brief ready"
}
if ($null -ne $strategy574Packet -and [string]$strategy574Packet.status -ne "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance packet ready"
}
if ($null -ne $auditPacket) {
    $auditMissingEvidenceCount = [int]$auditPacket.missingEvidenceCount
    $auditStaleEvidenceCount = [int]$auditPacket.staleEvidenceCount
    $auditIncompleteEvidenceCount = [int]$auditPacket.incompleteEvidenceCount
    if (($auditMissingEvidenceCount + $auditStaleEvidenceCount + $auditIncompleteEvidenceCount) -gt 0) {
        Add-MissingRequirement -List $missingRequirements -Value "profit live blocker audit sources complete and fresh"
    }
}

$rankedProfitItems = if ($null -ne $priorityPacket) { @($priorityPacket.rankedReviewItems) } else { @() }
$blockedPolicyLanes = if ($null -ne $priorityPacket) { @($priorityPacket.blockedPolicyLanes) } else { @() }
$strategy574RiskPosture = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.riskPosture } else { "" }
$strategy574Status = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.status } else { "" }
$strategy574NearThresholdRecommendation = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.nearThresholdShadowObservationEvidence.recommendation } else { "" }
$strategy574NearThresholdFalsePositiveRatePct = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.nearThresholdShadowObservationEvidence.falsePositiveRatePct } else { "" }

$strategy574BoardItem = [pscustomobject]@{
    rank = 4
    proposalId = "strategy574-tiny-live-governance-review"
    lane = "strategy574-tiny-live-governance"
    decisionFocus = "STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW"
    priorityClass = "P2_GOVERNANCE_BLOCKER_REVIEW_NOT_LIVE"
    sourcePacketStatus = $strategy574Status
    riskPosture = $strategy574RiskPosture
    nearThresholdRecommendation = $strategy574NearThresholdRecommendation
    nearThresholdFalsePositiveRatePct = $strategy574NearThresholdFalsePositiveRatePct
    thresholdRelaxationAllowed = $false
    evidenceStrength = "fresh_strategy574_and_tiny_live_read_only_packet"
    riskReason = if ($strategy574NearThresholdRecommendation -eq "STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH") { "near-threshold forward/TP-SL proxy evidence is negative; do not relax strategy574 threshold in this window" } else { "near-BUY/governance evidence remains blocked by DataFreshness or TinyLive rollout/execution gates; review only, no live enablement" }
    requiredBeforeDecision = @(
        "attach strategy574_tiny_live_governance_operator_packet",
        "confirm DataFreshness current snapshot is clean",
        "confirm current BUY candidate, OCO preflight, and EV pass sample",
        "confirm TinyLive rollout canEnableProduction and false-positive gates before any future live plan"
        "confirm near-threshold shadow observation is not high false-positive risk before any threshold relaxation review"
    )
    forbiddenFromThisBoard = @(
        "execute TinyLive orders",
        "enable live trading",
        "enable scheduler mutation",
        "send Telegram",
        "relax EntryDedup/DataFreshness/live policy",
        "deploy or change production env"
    )
    nextAction = "Keep as governance blocker review after the first three operator decisions; do not treat as TinyLive/live approval."
}

$auditLanes = if ($null -ne $auditPacket) { @($auditPacket.lanes) } else { @() }
$auditReviewQueue = @(
    $auditLanes |
        Where-Object { [bool]$_.readyForOperatorReview } |
        Sort-Object @{ Expression = { Get-LanePriority -Lane ([string]$_.lane) } }, @{ Expression = { [string]$_.lane } } |
        ForEach-Object {
            $laneName = [string]$_.lane
            [pscustomobject]@{
                rank = Get-LanePriority -Lane $laneName
                lane = $laneName
                decisionFocus = Get-LaneDecisionFocus -Lane $laneName
                priorityClass = Get-LanePriorityClass -Lane $laneName
                sourceStatus = [string]$_.sourceStatus
                classification = [string]$_.classification
                liveReady = [bool]$_.liveReady
                missingRequirements = @($_.missingRequirements)
                nextAction = [string]$_.nextAction
                notAuthorization = "review queue item only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, or policy relaxation"
            }
        }
)
$auditNoActionLanes = @(
    $auditLanes |
        Where-Object { [bool]$_.noActionRequired } |
        ForEach-Object {
            [pscustomobject]@{
                lane = [string]$_.lane
                sourceStatus = [string]$_.sourceStatus
                classification = [string]$_.classification
                nextAction = [string]$_.nextAction
            }
        }
)
$auditBlockedLanes = @(
    $auditLanes |
        Where-Object { -not [bool]$_.readyForOperatorReview -and -not [bool]$_.noActionRequired } |
        ForEach-Object {
            [pscustomobject]@{
                lane = [string]$_.lane
                sourceStatus = [string]$_.sourceStatus
                classification = [string]$_.classification
                missingRequirements = @($_.missingRequirements)
                nextAction = [string]$_.nextAction
            }
        }
)
$auditCounts = [pscustomobject]@{
    laneCount = if ($null -ne $auditPacket) { [int]$auditPacket.laneCount } else { 0 }
    readyReviewCount = if ($null -ne $auditPacket) { [int]$auditPacket.readyReviewCount } else { 0 }
    noActionCount = if ($null -ne $auditPacket) { [int]$auditPacket.noActionCount } else { 0 }
    blockedCount = if ($null -ne $auditPacket) { [int]$auditPacket.blockedCount } else { 0 }
    missingEvidenceCount = if ($null -ne $auditPacket) { [int]$auditPacket.missingEvidenceCount } else { 0 }
    staleEvidenceCount = if ($null -ne $auditPacket) { [int]$auditPacket.staleEvidenceCount } else { 0 }
    incompleteEvidenceCount = if ($null -ne $auditPacket) { [int]$auditPacket.incompleteEvidenceCount } else { 0 }
}
$auditOperatorDecisionOrder = @($auditReviewQueue | ForEach-Object { "$($_.rank). $($_.lane): $($_.decisionFocus)" })

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$primaryFocus = if ($null -ne $priorityPacket) { [string]$priorityPacket.primaryFocus } else { "" }
$nextAction = if ($ready) {
    "Use the ranked profit decisions first, then review strategy574/TinyLive governance as a blocked evidence lane; keep all mutation permissions false."
} else {
    "Refresh read-only priority and strategy574/TinyLive evidence before using the next-action board."
}

$board = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_NEXT_ACTION_BOARD"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    strategy574Id = $Strategy574Id
    sourcePriorityPacketStatus = if ($null -ne $priorityPacket) { [string]$priorityPacket.status } else { "" }
    sourceStrategy574TinyLivePacketStatus = $strategy574Status
    sourceAuditLogPath = $AuditLogPath
    sourceAuditLogFreshnessStatus = $auditLogFreshnessStatus
    sourceAuditLogAgeMinutes = $auditLogAgeMinutes
    sourceAuditStatus = if ($null -ne $auditPacket) { [string]$auditPacket.status } else { "" }
    auditLiveReadinessConclusion = if ($null -ne $auditPacket) { [string]$auditPacket.liveReadinessConclusion } else { "" }
    sourcePriorityMatrixFreshnessStatus = $priorityMatrixFreshnessStatus
    sourcePriorityMatrixAgeMinutes = $priorityMatrixAgeMinutes
    sourcePriorityMatrixMaxAgeMinutes = $priorityMatrixMaxAgeMinutes
    sourcePriorityBoardMaxAgeMinutes = $MaxAgeMinutes
    sourcePriorityMatrixFreshForBoardMaxAge = $priorityMatrixFreshForBoardMaxAge
    auditCounts = $auditCounts
    primaryFocus = $primaryFocus
    rankedProfitReviewItems = @($rankedProfitItems)
    auditReviewQueue = @($auditReviewQueue)
    auditNoActionLanes = @($auditNoActionLanes)
    auditBlockedLanes = @($auditBlockedLanes)
    auditOperatorDecisionOrder = @($auditOperatorDecisionOrder)
    auditPrimaryBlockers = if ($null -ne $auditPacket) { [object[]]@($auditPacket.primaryBlockers) } else { [object[]]@() }
    strategy574TinyLiveGovernanceItem = $strategy574BoardItem
    blockedPolicyLanes = @($blockedPolicyLanes)
    operatorDecisionOrder = @(
        "1. trailing-stop dry-run review",
        "2. strategy485 risk-reduction shadow review",
        "3. EntryDedup semantics shadow review",
        "4. strategy574/TinyLive governance blocker review"
    )
    blockedEvidenceLanes = @(
        "entry-filter remains blocked until signal/governance/missed-opportunity evidence clears",
        "data-freshness-replay remains blocked until replayCandidateId/counterfactual rows exist",
        "strategy574/TinyLive remains review-only until DataFreshness, current BUY, OCO preflight, EV, and rollout gates clear"
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
        "execute TinyLive",
        "send Telegram",
        "modify or cancel OCO",
        "close positions",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only profit operator next-action board only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[profit-operator-next-action-board] read-only board"
Write-Host "scope=READ_ONLY; invokes priority decision and strategy574/TinyLive governance packet only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $priorityResult.Text
Write-Host $strategy574Result.Text
Write-Host "source_priority_packet=prepare_profit_operator_priority_decision_brief.ps1 exitCode=$($priorityResult.ExitCode)"
Write-Host "source_priority_mode=$prioritySourceMode"
Write-Host "source_priority_log_path=$PriorityDecisionLogPath"
Write-Host "source_priority_log_freshness_status=$priorityLogFreshnessStatus"
Write-Host "source_priority_log_age_minutes=$priorityLogAgeMinutes"
Write-Host "source_priority_matrix_freshness_status=$priorityMatrixFreshnessStatus"
Write-Host "source_priority_matrix_age_minutes=$priorityMatrixAgeMinutes"
Write-Host "source_priority_matrix_max_age_minutes=$priorityMatrixMaxAgeMinutes"
Write-Host "source_priority_matrix_board_max_age_minutes=$MaxAgeMinutes"
Write-Host "source_priority_matrix_fresh_for_board_max_age=$(([string]$priorityMatrixFreshForBoardMaxAge).ToLowerInvariant())"
Write-Host "source_strategy574_tiny_live_packet=prepare_strategy574_tiny_live_governance_operator_packet.ps1 exitCode=$($strategy574Result.ExitCode)"
Write-Host "source_audit_log_path=$AuditLogPath"
Write-Host "source_audit_log_freshness_status=$auditLogFreshnessStatus"
Write-Host "source_audit_log_age_minutes=$auditLogAgeMinutes"
if ($null -ne $auditPacket) {
    Write-Host "source_audit_status=$([string]$auditPacket.status)"
    Write-Host "source_audit_live_readiness_conclusion=$([string]$auditPacket.liveReadinessConclusion)"
}
Write-Host "profit_operator_next_action_primary_focus=$primaryFocus"
Write-Host "strategy574_tiny_live_risk_posture=$strategy574RiskPosture"
Write-Host "strategy574_near_threshold_shadow_recommendation=$strategy574NearThresholdRecommendation"
Write-Host "strategy574_near_threshold_false_positive_rate_pct=$strategy574NearThresholdFalsePositiveRatePct"
Write-Host "strategy574_threshold_relaxation_allowed=false"
Write-Host ("profit_operator_next_action_audit_counts=" + (ConvertTo-Json -Compress -Depth 6 $auditCounts))
Write-Host ("profit_operator_next_action_audit_review_queue=" + (ConvertTo-Json -Compress -Depth 8 @($auditReviewQueue)))
Write-Host ("profit_operator_next_action_audit_no_action_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($auditNoActionLanes)))
Write-Host ("profit_operator_next_action_audit_blocked_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($auditBlockedLanes)))
Write-Host ("profit_operator_next_action_audit_operator_decision_order=" + (ConvertTo-Json -Compress -Depth 4 @($auditOperatorDecisionOrder)))
Write-Host ("profit_operator_next_action_ranked_profit_items=" + (ConvertTo-Json -Compress -Depth 10 @($rankedProfitItems)))
Write-Host ("profit_operator_next_action_strategy574_tiny_live_item=" + (ConvertTo-Json -Compress -Depth 8 $strategy574BoardItem))
Write-Host ("profit_operator_next_action_blocked_policy_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($blockedPolicyLanes)))
Write-Host ("profit_operator_next_action_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_operator_next_action_board_packet=" + (ConvertTo-Json -Compress -Depth 12 $board))
Write-Host "profit_operator_next_action_board_status=$status"
Write-Host "profit_operator_next_action_next_action=$nextAction"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only profit operator next-action board only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[profit-operator-next-action-board] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Profit operator next-action board is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
