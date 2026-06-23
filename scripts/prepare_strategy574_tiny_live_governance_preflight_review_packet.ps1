param(
    [string]$Strategy574GateLogPath = "target/profit-review/strategy574-signal-review-gate-refresh.log",
    [string]$TinyLiveLossRcaLogPath = "target/profit-review/tiny-live-loss-rca-refresh.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [string]$Side = "LONG",
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
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy574 TinyLive governance preflight." }

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

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for strategy574 TinyLive governance preflight arguments."
}
if ([string]::IsNullOrWhiteSpace($Side) -or $Side.Length -gt 32 -or $Side -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Side contains unsupported characters for strategy574 TinyLive governance preflight arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }

$operatorScript = Join-Path $PSScriptRoot "prepare_strategy574_tiny_live_governance_operator_packet.ps1"
$operatorResult = Invoke-LocalPacket -ScriptPath $operatorScript -Arguments @(
    "-Strategy574GateLogPath", $Strategy574GateLogPath,
    "-TinyLiveLossRcaLogPath", $TinyLiveLossRcaLogPath,
    "-MaxAgeMinutes", "$MaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-Side", $Side,
    "-RequireReady"
)

$operatorJson = Get-LastPrefixedValue -Text $operatorResult.Text -Prefix "strategy574_tiny_live_governance_operator_packet="
$operatorPacket = $null
if (-not [string]::IsNullOrWhiteSpace($operatorJson)) {
    $operatorPacket = $operatorJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($operatorResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance operator packet completed" }
if ($null -eq $operatorPacket) { Add-MissingRequirement -List $missingRequirements -Value "strategy574_tiny_live_governance_operator_packet valid JSON" }

$operatorStatus = ""
$riskPosture = ""
$primaryDecision = ""
$dataFreshnessClean = ""
$canEnableProduction = ""
$falsePositiveCount = ""
$completedTinyLiveSamples = ""
$hardStopDetected = ""
$tinyLiveMissingFields = @()
if ($null -ne $operatorPacket) {
    $operatorStatus = [string]$operatorPacket.status
    $riskPosture = [string]$operatorPacket.riskPosture
    $primaryDecision = [string]$operatorPacket.primaryDecision
    $dataFreshnessClean = [string]$operatorPacket.strategy574Evidence.dataFreshnessCurrentClean
    $canEnableProduction = [string]$operatorPacket.tinyLiveEvidence.canEnableProduction
    $falsePositiveCount = [string]$operatorPacket.tinyLiveEvidence.falsePositiveCount
    $completedTinyLiveSamples = [string]$operatorPacket.tinyLiveEvidence.completedTinyLiveSamples
    $hardStopDetected = [string]$operatorPacket.tinyLiveEvidence.hardStopDetected
    $tinyLiveMissingFields = @($operatorPacket.tinyLiveEvidence.missingTinyLiveFields)
}

if ($operatorStatus -ne "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance operator packet ready"
}
if ($primaryDecision -ne "PREPARE_STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive governance decision is review-only"
}
if (@($tinyLiveMissingFields).Count -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "TinyLive RCA required fields complete"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$preflightDecision = if ($ready) {
    "PREPARE_REVIEW_ONLY_STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW"
} else {
    "REFRESH_STRATEGY574_TINY_LIVE_GOVERNANCE_EVIDENCE"
}
$nextAction = if ($ready) {
    "Attach this preflight packet to strategy574/TinyLive governance operator review; require separate explicit authorization before any TinyLive, live, order, scheduler, deploy, env, Telegram, or policy change."
} else {
    "Refresh strategy574 gate and TinyLive loss RCA evidence before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    side = $Side
    sourceOperatorPacket = "prepare_strategy574_tiny_live_governance_operator_packet.ps1"
    sourceOperatorPacketStatus = $operatorStatus
    sourceRiskPosture = $riskPosture
    sourcePrimaryDecision = $primaryDecision
    sourceDataFreshnessCurrentClean = $dataFreshnessClean
    sourceTinyLiveCanEnableProduction = $canEnableProduction
    sourceTinyLiveFalsePositiveCount = $falsePositiveCount
    sourceTinyLiveCompletedSamples = $completedTinyLiveSamples
    sourceTinyLiveHardStopDetected = $hardStopDetected
    preflightDecision = $preflightDecision
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        strategy574TinyLiveGovernanceReviewAllowed = $true
        tinyLiveOrderAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        deployOrEnvChangeAllowed = $false
        orderAllowed = $false
        telegramSendAllowed = $false
        positionOrOcoMutationAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        externalBackfillOrImportAllowed = $false
    }
    operatorPreflightChecklist = @(
        "attach strategy574_tiny_live_governance_operator_packet",
        "confirm DataFreshness current snapshot and TinyLive rollout blockers are reviewed",
        "confirm current BUY candidate, OCO preflight, EV, sample count, and false-positive gates before any future live plan",
        "keep TinyLive/order/scheduler/env/Telegram permissions false without separate explicit authorization"
    )
    requiredBeforeAnyFutureMutation = @(
        "fresh production read-only strategy574 signal gate",
        "fresh TinyLive loss RCA",
        "current DataFreshness clean",
        "current BUY candidate and EV/OCO preflight pass",
        "TinyLive canEnableProduction=true with acceptable false-positive sample",
        "separate explicit operator approval for deploy/env/live/order/scheduler/Telegram/policy changes",
        "rollback criteria and post-change read-only verification plan"
    )
    explicitNonAuthorizations = @(
        "does not enable TinyLive execution",
        "does not enable live trading",
        "does not enable scheduler",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not send Telegram",
        "does not deploy",
        "does not change production env",
        "does not relax EntryDedup",
        "does not relax DataFreshnessGuard",
        "does not relax live policy"
    )
    sourceOperatorPacketSummary = $operatorPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy574/TinyLive governance preflight review packet only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[strategy574-tiny-live-governance-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_strategy574_tiny_live_governance_operator_packet.ps1 only against existing logs; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $operatorResult.Text
Write-Host "source_operator_packet=prepare_strategy574_tiny_live_governance_operator_packet.ps1 exitCode=$($operatorResult.ExitCode)"
Write-Host "source_operator_packet_status=$operatorStatus"
Write-Host "strategy574_tiny_live_preflight_risk_posture=$riskPosture"
Write-Host "strategy574_tiny_live_preflight_decision=$preflightDecision"
Write-Host "strategy574_data_freshness_current_clean=$dataFreshnessClean"
Write-Host "tiny_live_can_enable_production=$canEnableProduction"
Write-Host "tiny_live_completed_samples=$completedTinyLiveSamples"
Write-Host "tiny_live_false_positive_count=$falsePositiveCount"
Write-Host "tiny_live_hard_stop_detected=$hardStopDetected"
Write-Host "strategy574_tiny_live_governance_review_allowed=true"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host ("strategy574_tiny_live_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy574_tiny_live_governance_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "strategy574_tiny_live_governance_preflight_status=$status"
Write-Host "strategy574_tiny_live_governance_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only strategy574/TinyLive governance preflight review packet only; does not authorize live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[strategy574-tiny-live-governance-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy574/TinyLive governance preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
