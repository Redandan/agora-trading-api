param(
    [string]$AuthorizationRequestLogPath = "target/profit-review/profit-operator-authorization-request-latest.log",
    [string]$QuickStatusLogPath = "target/profit-review/profit-operator-quick-status-latest.log",
    [string]$NextExecutionLogPath = "target/profit-review/profit-next-execution-blocker-packet-latest.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [decimal]$MaxProbeNotionalUsdt = 10,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Convert-ToBool {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim() -match "^(?i:true|1|yes)$"
}

function Read-PacketLog {
    param([string]$PathValue, [string]$Prefix, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    $freshness = "MISSING"
    $ageMinutes = $null
    $text = ""
    $packet = $null
    if (-not [string]::IsNullOrWhiteSpace($resolved) -and (Test-Path -LiteralPath $resolved)) {
        $item = Get-Item -LiteralPath $resolved
        $ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
        $freshness = if ($ageMinutes -le $MaxAge) { "FRESH" } else { "STALE" }
        $text = Get-Content -Raw -LiteralPath $resolved
        $packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix $Prefix)
    }
    return [pscustomobject]@{
        Path = $resolved
        Freshness = $freshness
        AgeMinutes = $ageMinutes
        Text = $text
        Packet = $packet
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for aggressive activation packet arguments."
}
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) { throw "MaxProbeNotionalUsdt must be between 0 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$authSource = Read-PacketLog -PathValue $AuthorizationRequestLogPath -Prefix "profit_operator_authorization_request_packet=" -MaxAge $MaxAgeMinutes
$quickSource = Read-PacketLog -PathValue $QuickStatusLogPath -Prefix "profit_operator_quick_status_packet=" -MaxAge $MaxAgeMinutes
$nextSource = Read-PacketLog -PathValue $NextExecutionLogPath -Prefix "profit_next_execution_blocker_packet=" -MaxAge $MaxAgeMinutes

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$riskBlockers = [System.Collections.Generic.List[string]]::new()

if ($authSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit operator authorization request log present" }
if ($authSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit operator authorization request log fresh" }
if ($authSource.Freshness -ne "MISSING" -and $null -eq $authSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_operator_authorization_request_packet valid JSON" }

if ($quickSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit operator quick status log fresh" }
if ($quickSource.Freshness -ne "MISSING" -and $null -eq $quickSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_operator_quick_status_packet valid JSON" }
if ($nextSource.Freshness -eq "MISSING" -and $quickSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit next-execution blocker evidence present" }
if ($nextSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit next-execution blocker log fresh" }
if ($nextSource.Freshness -ne "MISSING" -and $null -eq $nextSource.Packet) { Add-Unique -List $missingEvidence -Value "profit_next_execution_blocker_packet valid JSON" }

$authPacket = $authSource.Packet
$quickPacket = $quickSource.Packet
$nextPacket = $nextSource.Packet

$authorizationReady = Convert-ToBool (Get-PropertyValue -Object $authPacket -Name "authorizationRequestReady")
$liveReadinessConclusion = Get-PropertyValue -Object $authPacket -Name "liveReadinessConclusion"
$nextAuthorizationRequired = Get-PropertyValue -Object $authPacket -Name "nextAuthorizationRequired"
if ($null -ne $authPacket -and -not $authorizationReady) { Add-Unique -List $riskBlockers -Value "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY" }
if ($null -ne $authPacket -and $liveReadinessConclusion -ne "NOT_READY_FOR_LIVE_ENABLEMENT") { Add-Unique -List $riskBlockers -Value "LIVE_READINESS_CONCLUSION_UNEXPECTED" }

$nextExecution = $null
if ($null -ne $quickPacket -and $null -ne $quickPacket.PSObject.Properties["nextExecutionStatus"]) {
    $nextExecution = $quickPacket.nextExecutionStatus
} elseif ($null -ne $nextPacket) {
    $nextExecution = $nextPacket
}

$nextExecutionStatus = Get-PropertyValue -Object $nextExecution -Name "status"
if ([string]::IsNullOrWhiteSpace($nextExecutionStatus)) { $nextExecutionStatus = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_blocker_status=" }
$nextExecutionRoute = Get-PropertyValue -Object $nextExecution -Name "route"
if ([string]::IsNullOrWhiteSpace($nextExecutionRoute)) { $nextExecutionRoute = Get-PropertyValue -Object $nextExecution -Name "profitRoute" }
if ([string]::IsNullOrWhiteSpace($nextExecutionRoute)) { $nextExecutionRoute = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_route=" }
$nextExecutionBlocker = Get-PropertyValue -Object $nextExecution -Name "uniqueBlocker"
if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { $nextExecutionBlocker = Get-PropertyValue -Object $nextExecution -Name "unique_blocker" }
if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { $nextExecutionBlocker = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_unique_blocker=" }
$openOcoPositions = Get-PropertyValue -Object $nextExecution -Name "openOcoPositions"
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-PropertyValue -Object $nextExecution -Name "currentOpenOcoPositions" }
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_open_oco_positions=" }
if ([string]::IsNullOrWhiteSpace($openOcoPositions)) { $openOcoPositions = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "profit_next_execution_open_oco_positions=" }
$replayCandidateRows = Get-PropertyValue -Object $nextExecution -Name "dataFreshnessReplayCandidateIdRows"
if ([string]::IsNullOrWhiteSpace($replayCandidateRows)) { $replayCandidateRows = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows=" }
if ([string]::IsNullOrWhiteSpace($replayCandidateRows)) { $replayCandidateRows = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "data_freshness_replay_candidate_id_rows=" }
$completeReplayableRows = Get-PropertyValue -Object $nextExecution -Name "dataFreshnessCompleteReplayableCandidateRows"
if ([string]::IsNullOrWhiteSpace($completeReplayableRows)) { $completeReplayableRows = Get-LastPrefixedValue -Text $quickSource.Text -Prefix "profit_operator_quick_next_execution_data_freshness_complete_replayable_candidate_rows=" }
if ([string]::IsNullOrWhiteSpace($completeReplayableRows)) { $completeReplayableRows = Get-LastPrefixedValue -Text $nextSource.Text -Prefix "data_freshness_complete_replayable_candidate_rows=" }

if ([string]::IsNullOrWhiteSpace($nextExecutionBlocker)) { Add-Unique -List $missingEvidence -Value "current profit next-execution blocker identified" }
if ($nextExecutionBlocker -eq "NO_OPEN_OCO_POSITIONS") { Add-Unique -List $riskBlockers -Value "NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_SAMPLE" }
if ($replayCandidateRows -eq "0" -or $completeReplayableRows -eq "0") { Add-Unique -List $riskBlockers -Value "DATAFRESHNESS_REPLAY_ROWS_MISSING" }

$microProbeRiskAcceptedText = "I explicitly authorize HIGH_RISK_MICRO_LIVE_PROBE for $Symbol with maxNotionalUsdt=$MaxProbeNotionalUsdt, maxOrders=1, no policy relaxation, no grid/fund/Earn actions, immediate rollback on any unexpected order/OCO/Telegram/exchange/DB mutation, and I accept that this can lose money."
$gridRiskAcceptedText = "I explicitly authorize SEPARATE_GRID10_ORDER_PATH_REVIEW for $Symbol with existing-grid activation risk accepted, TRADING_OKX_ENABLED=true reviewed separately, and no createGrid/order execution until the grid authorization bundle and post-env verification are current."
$evidenceAcceleratorText = "I authorize EVIDENCE_ONLY_ACCELERATOR for ${Symbol}: runtime/DataFreshness shadow evidence collection may be reviewed, while TRADING_OKX_ENABLED=false and all live/order/OCO/grid/fund/Earn/Telegram mutations remain disabled."

$aggressiveOptions = @(
    [pscustomobject]@{
        optionId = "HIGH_RISK_MICRO_LIVE_PROBE"
        priority = 1
        risk = "HIGH"
        recommendedNow = $false
        status = "BLOCKED_UNTIL_EXPLICIT_OPERATOR_CONFIRMATION_AND_CURRENT_BUY_OCO_EV_GATES"
        maxNotionalUsdt = $MaxProbeNotionalUsdt
        maxOrders = 1
        acceptsKnownBlockers = @($riskBlockers)
        requiredBeforeExecution = @(
            "exact operator confirmation text",
            "fresh live-readiness bundle",
            "current BUY/scout candidate",
            "OCO preflight pass or explicit no-OCO risk acceptance",
            "EV snapshot pass",
            "event risk R0/R1",
            "runtime evidence enabled and orderSentEvidence=0 before probe",
            "kill switch and rollback env diff prepared"
        )
        confirmationText = $microProbeRiskAcceptedText
    },
    [pscustomobject]@{
        optionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
        priority = 2
        risk = "MEDIUM_HIGH"
        recommendedNow = $false
        status = "SEPARATE_GRID_AUTHORIZATION_AND_POST_ENV_VERIFICATION_REQUIRED"
        maxCapitalUsdt = [math]::Max([decimal]10, $MaxProbeNotionalUsdt)
        requiredBeforeExecution = @(
            "fresh grid open blocker priority board",
            "grid authorization bundle ready",
            "TRADING_OKX_ENABLED=true env diff explicitly accepted",
            "existing active grid order-path activation risk explicitly accepted",
            "post-env read-only verification plan ready"
        )
        confirmationText = $gridRiskAcceptedText
    },
    [pscustomobject]@{
        optionId = "EVIDENCE_ONLY_ACCELERATOR"
        priority = 3
        risk = "MEDIUM"
        recommendedNow = $true
        status = "RECOMMENDED_AGGRESSIVE_NON_ORDER_STEP"
        requiredBeforeExecution = @(
            "separate runtime/DataFreshness evidence-only env review",
            "TRADING_OKX_ENABLED=false",
            "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
            "scheduler/order/OCO/grid/fund/Earn/Telegram mutation disabled",
            "post-env read-only verification commands accepted"
        )
        confirmationText = $evidenceAcceleratorText
    }
)

$packetReady = $missingEvidence.Count -eq 0
$status = if ($packetReady) {
    "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING"
}
$decision = if ($packetReady) {
    "REVIEW_HIGH_RISK_MICRO_PROBE_OR_EVIDENCE_ACCELERATOR_SEPARATELY"
} else {
    "REFRESH_PROFIT_SOURCE_EVIDENCE_BEFORE_AGGRESSIVE_REVIEW"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    authorizationRequestReady = $authorizationReady
    liveReadinessConclusion = $liveReadinessConclusion
    nextAuthorizationRequired = $nextAuthorizationRequired
    nextExecutionStatus = $nextExecutionStatus
    nextExecutionRoute = $nextExecutionRoute
    nextExecutionUniqueBlocker = $nextExecutionBlocker
    openOcoPositions = $openOcoPositions
    dataFreshnessReplayCandidateIdRows = $replayCandidateRows
    dataFreshnessCompleteReplayableCandidateRows = $completeReplayableRows
    aggressiveOptions = @($aggressiveOptions)
    primaryRecommendation = "Prefer EVIDENCE_ONLY_ACCELERATOR now; use HIGH_RISK_MICRO_LIVE_PROBE only after a separate exact operator confirmation and current BUY/OCO/EV gates."
    riskBlockers = @($riskBlockers)
    missingEvidence = @($missingEvidence)
    requiredExplicitAuthorizationTexts = @($microProbeRiskAcceptedText, $gridRiskAcceptedText, $evidenceAcceleratorText)
    rollbackCriteria = @(
        "any unexpected order/OCO/grid/fund/Earn/Telegram/exchange/DB mutation",
        "orderSentEvidence > 0 before a probe is explicitly authorized",
        "live-readiness bundle reports new blocker",
        "runtime logs show new errors",
        "daily loss or hard-stop gate trips",
        "OCO health abnormal without explicit acceptance"
    )
    allowedActions = @("operator review", "read-only evidence refresh", "prepare separate exact authorization")
    forbiddenActions = @(
        "enable live trading from this packet",
        "enable scheduler from this packet",
        "place orders from this packet",
        "execute TinyLive from this packet",
        "send Telegram from this packet",
        "modify or cancel OCO from this packet",
        "change production env from this packet",
        "deploy from this packet",
        "relax EntryDedup/DataFreshness/live policy from this packet"
    )
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    deployOrEnvChangeAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only aggressive activation operator packet only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-aggressive-activation-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads existing local profit authorization, quick-status, and next-execution logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "profit_aggressive_activation_status=$status"
Write-Host "profit_aggressive_activation_decision=$decision"
Write-Host "profit_aggressive_activation_authorization_request_ready=$(([string]$authorizationReady).ToLowerInvariant())"
Write-Host "profit_aggressive_activation_live_readiness_conclusion=$liveReadinessConclusion"
Write-Host "profit_aggressive_activation_next_authorization_required=$nextAuthorizationRequired"
Write-Host "profit_aggressive_activation_next_execution_status=$nextExecutionStatus"
Write-Host "profit_aggressive_activation_next_execution_route=$nextExecutionRoute"
Write-Host "profit_aggressive_activation_next_execution_unique_blocker=$nextExecutionBlocker"
Write-Host "profit_aggressive_activation_open_oco_positions=$openOcoPositions"
Write-Host "profit_aggressive_activation_data_freshness_replay_candidate_id_rows=$replayCandidateRows"
Write-Host "profit_aggressive_activation_data_freshness_complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host ("profit_aggressive_activation_options=" + (ConvertTo-Json -Compress -Depth 10 @($aggressiveOptions)))
Write-Host ("profit_aggressive_activation_risk_blockers=" + (ConvertTo-Json -Compress @($riskBlockers)))
Write-Host ("profit_aggressive_activation_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("profit_aggressive_activation_required_authorization_texts=" + (ConvertTo-Json -Compress @($packet.requiredExplicitAuthorizationTexts)))
Write-Host ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=read-only aggressive activation operator packet only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[profit-aggressive-activation-operator-packet] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Profit aggressive activation operator packet is not ready: $status; missingEvidence=$(@($missingEvidence) -join '; ')"
}
