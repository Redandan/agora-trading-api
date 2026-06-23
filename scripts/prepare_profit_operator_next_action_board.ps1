param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$Strategy574GateLogPath = "target/profit-review/strategy574-signal-review-gate-refresh.log",
    [string]$TinyLiveLossRcaLogPath = "target/profit-review/tiny-live-loss-rca-refresh.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$Strategy574Id = 574,
    [string]$Side = "LONG",
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

$priorityResult = Invoke-LocalPacket -ScriptPath $priorityScript -Arguments @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$strategy574Result = Invoke-LocalPacket -ScriptPath $strategy574Script -Arguments @(
    "-Strategy574GateLogPath", $Strategy574GateLogPath,
    "-TinyLiveLossRcaLogPath", $TinyLiveLossRcaLogPath,
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

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($priorityResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "profit priority decision brief completed" }
if ($strategy574Result.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance packet completed" }
if ($null -eq $priorityPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_priority_decision_brief_packet valid JSON" }
if ($null -eq $strategy574Packet) { Add-MissingRequirement -List $missingRequirements -Value "strategy574_tiny_live_governance_operator_packet valid JSON" }
if ($null -ne $priorityPacket -and [string]$priorityPacket.status -ne "READY_FOR_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "profit priority decision brief ready"
}
if ($null -ne $strategy574Packet -and [string]$strategy574Packet.status -ne "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance packet ready"
}

$rankedProfitItems = if ($null -ne $priorityPacket) { @($priorityPacket.rankedReviewItems) } else { @() }
$blockedPolicyLanes = if ($null -ne $priorityPacket) { @($priorityPacket.blockedPolicyLanes) } else { @() }
$strategy574RiskPosture = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.riskPosture } else { "" }
$strategy574Status = if ($null -ne $strategy574Packet) { [string]$strategy574Packet.status } else { "" }

$strategy574BoardItem = [pscustomobject]@{
    rank = 4
    proposalId = "strategy574-tiny-live-governance-review"
    lane = "strategy574-tiny-live-governance"
    decisionFocus = "STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW"
    priorityClass = "P2_GOVERNANCE_BLOCKER_REVIEW_NOT_LIVE"
    sourcePacketStatus = $strategy574Status
    riskPosture = $strategy574RiskPosture
    evidenceStrength = "fresh_strategy574_and_tiny_live_read_only_packet"
    riskReason = "near-BUY/governance evidence remains blocked by DataFreshness or TinyLive rollout/execution gates; review only, no live enablement"
    requiredBeforeDecision = @(
        "attach strategy574_tiny_live_governance_operator_packet",
        "confirm DataFreshness current snapshot is clean",
        "confirm current BUY candidate, OCO preflight, and EV pass sample",
        "confirm TinyLive rollout canEnableProduction and false-positive gates before any future live plan"
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
    primaryFocus = $primaryFocus
    rankedProfitReviewItems = @($rankedProfitItems)
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
Write-Host "source_strategy574_tiny_live_packet=prepare_strategy574_tiny_live_governance_operator_packet.ps1 exitCode=$($strategy574Result.ExitCode)"
Write-Host "profit_operator_next_action_primary_focus=$primaryFocus"
Write-Host "strategy574_tiny_live_risk_posture=$strategy574RiskPosture"
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
