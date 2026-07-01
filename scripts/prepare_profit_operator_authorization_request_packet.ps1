param(
    [string]$BoardLogPath = "target/profit-review/profit-operator-next-action-board-latest.log",
    [string]$AuditLogPath = "target/profit-review/profit-live-blocker-audit-packet-latest.log",
    [int]$MaxAgeMinutes = 180,
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

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-PropertyOrNull {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Convert-ToInt {
    param([object]$Value)
    if ($null -eq $Value) { return 0 }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return 0 }
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) { return $parsed }
    return 0
}

function Convert-ToBool {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    $text = ([string]$Value).Trim()
    if ($text -match "^(?i:true|1|yes)$") { return $true }
    return $false
}

function Get-StringArray {
    param($Values)
    $list = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $Values) { return @() }
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        if ($value -is [pscustomobject] -and @($value.PSObject.Properties).Count -eq 0) { continue }
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) { Add-Unique -List $list -Value $text }
    }
    return @($list)
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

function Get-LaneAuthorizationName {
    param([string]$Lane)
    switch ($Lane) {
        "trailing-stop-dry-run" { return "TRAILING_STOP_DRY_RUN_REVIEW_AUTHORIZATION" }
        "strategy485-risk-reduction" { return "STRATEGY485_RISK_REDUCTION_SHADOW_REVIEW_AUTHORIZATION" }
        "entry-dedup-semantics" { return "ENTRY_DEDUP_SEMANTICS_SHADOW_REVIEW_AUTHORIZATION" }
        "data-freshness-collector-activation" { return "DATAFRESHNESS_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_AUTHORIZATION" }
        "data-freshness-replay-blocker" { return "DATAFRESHNESS_REPLAY_BLOCKER_REVIEW_AUTHORIZATION" }
        "tp-sl-oco-feasibility" { return "TP_SL_OCO_FEASIBILITY_REVIEW_AUTHORIZATION" }
        "strategy574-tiny-live-governance" { return "STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW_AUTHORIZATION" }
        "profit-priority" { return "PROFIT_PRIORITY_CONTEXT_REVIEW_AUTHORIZATION" }
        default { return "REVIEW_ONLY_AUTHORIZATION" }
    }
}

function Get-LaneAuthorizationLine {
    param([string]$Lane)
    switch ($Lane) {
        "trailing-stop-dry-run" {
            return "I authorize operator review of trailing-stop dry-run evidence only; I do not authorize enabling trailing scheduler/live mode, orders, OCO mutation, deploy, or production env changes."
        }
        "strategy485-risk-reduction" {
            return "I authorize operator review of Strategy485 risk-reduction shadow evidence only; I do not authorize close-position, OCO modification, orders, deploy, or production env changes."
        }
        "entry-dedup-semantics" {
            return "I authorize operator review of EntryDedup semantics shadow evidence only; I do not authorize EntryDedup/DataFreshness/live policy relaxation, orders, deploy, or production env changes."
        }
        "data-freshness-collector-activation" {
            return "I authorize operator review of the DataFreshness evidence-only collector activation plan only; any env diff, deploy, scheduler, Telegram, live, or policy mutation still needs a later separate authorization."
        }
        "data-freshness-replay-blocker" {
            return "I authorize operator review of the DataFreshness replay blocker packet only; I do not authorize DataFreshnessGuard relaxation or live entry policy changes before fresh complete replayable candidate rows exist."
        }
        "tp-sl-oco-feasibility" {
            return "I authorize operator review of TP/SL/OCO feasibility evidence only; I do not authorize OCO creation, cancellation, modification, orders, close-position, deploy, or production env changes."
        }
        "strategy574-tiny-live-governance" {
            return "I authorize operator review of Strategy574/TinyLive governance evidence only; I do not authorize TinyLive execution, live trading, scheduler enablement, orders, Telegram, deploy, env, or policy changes."
        }
        "profit-priority" {
            return "I authorize review of the aggregate profit priority context only; I do not authorize any live, order, scheduler, OCO, Telegram, deploy, env, or policy mutation."
        }
        default {
            return "I authorize review-only discussion for this lane; I do not authorize live, order, scheduler, OCO, Telegram, deploy, env, DB/grid/fund/Earn/exchange, or policy mutation."
        }
    }
}

function Read-PacketLog {
    param(
        [string]$Path,
        [string]$Prefix,
        [int]$MaxAge
    )

    $freshness = "MISSING"
    $ageMinutes = $null
    $text = ""
    $json = ""
    $packet = $null
    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path)) {
        $item = Get-Item -LiteralPath $Path
        $ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
        $freshness = if ($ageMinutes -le $MaxAge) { "FRESH" } else { "STALE" }
        $text = Get-Content -Raw -LiteralPath $Path
        $json = Get-LastPrefixedValue -Text $text -Prefix $Prefix
        $packet = Convert-JsonObjectOrNull -Value $json
    }

    return [pscustomobject]@{
        Path = $Path
        Freshness = $freshness
        AgeMinutes = $ageMinutes
        Text = $text
        Json = $json
        Packet = $packet
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }

$boardSource = Read-PacketLog -Path $BoardLogPath -Prefix "profit_operator_next_action_board_packet=" -MaxAge $MaxAgeMinutes
$auditSource = Read-PacketLog -Path $AuditLogPath -Prefix "profit_live_blocker_audit_packet=" -MaxAge $MaxAgeMinutes
$boardPacket = $boardSource.Packet
$auditPacket = $auditSource.Packet

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$requestBlockers = [System.Collections.Generic.List[string]]::new()

if ($boardSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit operator next-action board log present" }
if ($boardSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit operator next-action board log fresh" }
if ($boardSource.Freshness -ne "MISSING" -and $null -eq $boardPacket) { Add-Unique -List $missingEvidence -Value "profit_operator_next_action_board_packet valid JSON" }

if ($auditSource.Freshness -eq "MISSING") { Add-Unique -List $missingEvidence -Value "profit live blocker audit log present" }
if ($auditSource.Freshness -eq "STALE") { Add-Unique -List $missingEvidence -Value "profit live blocker audit log fresh" }
if ($auditSource.Freshness -ne "MISSING" -and $null -eq $auditPacket) { Add-Unique -List $missingEvidence -Value "profit_live_blocker_audit_packet valid JSON" }

$boardStatus = [string](Get-PropertyOrNull -Object $boardPacket -Name "status")
$auditStatus = [string](Get-PropertyOrNull -Object $auditPacket -Name "status")
$liveConclusion = [string](Get-PropertyOrNull -Object $auditPacket -Name "liveReadinessConclusion")

if ($null -ne $boardPacket -and $boardStatus -ne "READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE") {
    Add-Unique -List $requestBlockers -Value "PROFIT_OPERATOR_NEXT_ACTION_BOARD_NOT_READY"
}
if ($null -ne $auditPacket -and $auditStatus -ne "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT") {
    Add-Unique -List $requestBlockers -Value "PROFIT_LIVE_BLOCKER_AUDIT_STATUS_UNEXPECTED"
}
if ($null -ne $auditPacket -and $liveConclusion -ne "NOT_READY_FOR_LIVE_ENABLEMENT") {
    Add-Unique -List $requestBlockers -Value "PROFIT_LIVE_READINESS_CONCLUSION_NOT_NOT_READY"
}

$auditMissingEvidenceCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "missingEvidenceCount")
$auditStaleEvidenceCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "staleEvidenceCount")
$auditIncompleteEvidenceCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "incompleteEvidenceCount")
if (($auditMissingEvidenceCount + $auditStaleEvidenceCount + $auditIncompleteEvidenceCount) -gt 0) {
    Add-Unique -List $missingEvidence -Value "profit live blocker audit sources complete and fresh"
}

$boardMissingRequirements = @(Get-StringArray (Get-PropertyOrNull -Object $boardPacket -Name "missingRequirements"))
if ($boardMissingRequirements.Count -gt 0) {
    Add-Unique -List $missingEvidence -Value "profit operator next-action board missing requirements clear"
}

$sourceReviewQueue = @()
$boardReviewQueue = Get-PropertyOrNull -Object $boardPacket -Name "auditReviewQueue"
if ($null -ne $boardReviewQueue) {
    $sourceReviewQueue = @($boardReviewQueue)
}
if ($sourceReviewQueue.Count -eq 0 -and $null -ne $auditPacket) {
    $auditLanes = @(Get-PropertyOrNull -Object $auditPacket -Name "lanes")
    $sourceReviewQueue = @($auditLanes | Where-Object { Convert-ToBool (Get-PropertyOrNull -Object $_ -Name "readyForOperatorReview") })
}

if ($sourceReviewQueue.Count -eq 0) {
    Add-Unique -List $missingEvidence -Value "profit operator review queue has at least one review-ready lane"
}

$reviewQueue = @(
    $sourceReviewQueue |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string](Get-PropertyOrNull -Object $_ -Name "lane")) } |
        Sort-Object @{ Expression = { Get-LanePriority -Lane ([string](Get-PropertyOrNull -Object $_ -Name "lane")) } }, @{ Expression = { [string](Get-PropertyOrNull -Object $_ -Name "lane") } } |
        ForEach-Object {
            $lane = [string](Get-PropertyOrNull -Object $_ -Name "lane")
            $decisionFocus = [string](Get-PropertyOrNull -Object $_ -Name "decisionFocus")
            if ([string]::IsNullOrWhiteSpace($decisionFocus)) { $decisionFocus = Get-LaneDecisionFocus -Lane $lane }
            [pscustomobject]@{
                rank = Get-LanePriority -Lane $lane
                lane = $lane
                decisionFocus = $decisionFocus
                requiredAuthorization = Get-LaneAuthorizationName -Lane $lane
                authorizationLine = Get-LaneAuthorizationLine -Lane $lane
                sourceStatus = [string](Get-PropertyOrNull -Object $_ -Name "sourceStatus")
                classification = [string](Get-PropertyOrNull -Object $_ -Name "classification")
                liveReady = Convert-ToBool (Get-PropertyOrNull -Object $_ -Name "liveReady")
                mutationAllowed = $false
                missingRequirements = @(Get-StringArray (Get-PropertyOrNull -Object $_ -Name "missingRequirements"))
                nextAction = [string](Get-PropertyOrNull -Object $_ -Name "nextAction")
                notAuthorization = "review queue item only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, external backfill/import, or policy relaxation"
            }
        }
)

$authorizationLines = @($reviewQueue | ForEach-Object { [string]$_.authorizationLine })
$authorizationSequence = @($reviewQueue | ForEach-Object { "$($_.rank). $($_.lane): $($_.requiredAuthorization)" })
$nextAuthorizationRequired = if ($reviewQueue.Count -gt 0) { [string]$reviewQueue[0].requiredAuthorization } else { "" }
$requestReady = (
    $missingEvidence.Count -eq 0 -and
    $requestBlockers.Count -eq 0 -and
    $reviewQueue.Count -gt 0
)
$status = if ($requestReady) {
    "READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE"
} elseif ($missingEvidence.Count -gt 0) {
    "BLOCKED_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE_MISSING"
} else {
    "BLOCKED_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE"
}
$decision = if ($requestReady) {
    "AWAIT_SEPARATE_OPERATOR_REVIEW_AUTHORIZATION"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE"
} else {
    "RESOLVE_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_BLOCKERS"
}

$auditCounts = [pscustomobject]@{
    laneCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "laneCount")
    readyReviewCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "readyReviewCount")
    noActionCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "noActionCount")
    blockedCount = Convert-ToInt -Value (Get-PropertyOrNull -Object $auditPacket -Name "blockedCount")
    missingEvidenceCount = $auditMissingEvidenceCount
    staleEvidenceCount = $auditStaleEvidenceCount
    incompleteEvidenceCount = $auditIncompleteEvidenceCount
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    authorizationRequestReady = $requestReady
    sourceBoardLogPath = $BoardLogPath
    sourceBoardLogFreshnessStatus = $boardSource.Freshness
    sourceBoardLogAgeMinutes = $boardSource.AgeMinutes
    sourceBoardStatus = $boardStatus
    sourceAuditLogPath = $AuditLogPath
    sourceAuditLogFreshnessStatus = $auditSource.Freshness
    sourceAuditLogAgeMinutes = $auditSource.AgeMinutes
    sourceAuditStatus = $auditStatus
    liveReadinessConclusion = $liveConclusion
    auditCounts = $auditCounts
    primaryFocus = [string](Get-PropertyOrNull -Object $boardPacket -Name "primaryFocus")
    reviewQueue = @($reviewQueue)
    operatorAuthorizationSequence = @($authorizationSequence)
    authorizationRequestLines = @($authorizationLines)
    nextAuthorizationRequired = $nextAuthorizationRequired
    requestBlockers = @($requestBlockers)
    missingEvidence = @($missingEvidence)
    boardMissingRequirements = @($boardMissingRequirements)
    primaryBlockers = @(Get-StringArray (Get-PropertyOrNull -Object $auditPacket -Name "primaryBlockers"))
    allowedActions = @(
        "operator review",
        "read-only evidence refresh",
        "shadow or dry-run design discussion"
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
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    deployOrEnvChangeAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    sourceBoardPacketSummary = $boardPacket
    sourceAuditPacketSummary = $auditPacket
    notAuthorization = "read-only profit operator authorization request only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-operator-authorization-request] read-only packet"
Write-Host "scope=READ_ONLY; reads existing local profit next-action board and live-blocker audit logs only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_board_log_path=$BoardLogPath"
Write-Host "source_board_log_freshness_status=$($boardSource.Freshness)"
Write-Host "source_board_log_age_minutes=$($boardSource.AgeMinutes)"
Write-Host "source_board_status=$boardStatus"
Write-Host "source_audit_log_path=$AuditLogPath"
Write-Host "source_audit_log_freshness_status=$($auditSource.Freshness)"
Write-Host "source_audit_log_age_minutes=$($auditSource.AgeMinutes)"
Write-Host "source_audit_status=$auditStatus"
Write-Host "profit_live_readiness_conclusion=$liveConclusion"
Write-Host "profit_operator_authorization_request_status=$status"
Write-Host "profit_operator_authorization_request_decision=$decision"
Write-Host "profit_operator_authorization_request_ready=$(([string]$requestReady).ToLowerInvariant())"
Write-Host "profit_operator_authorization_request_next_authorization_required=$nextAuthorizationRequired"
Write-Host ("profit_operator_authorization_request_review_queue=" + (ConvertTo-Json -Compress -Depth 10 @($reviewQueue)))
Write-Host ("profit_operator_authorization_request_authorization_sequence=" + (ConvertTo-Json -Compress -Depth 6 @($authorizationSequence)))
Write-Host ("profit_operator_authorization_request_authorization_lines=" + (ConvertTo-Json -Compress -Depth 6 @($authorizationLines)))
Write-Host ("profit_operator_authorization_request_blockers=" + (ConvertTo-Json -Compress @($requestBlockers)))
Write-Host ("profit_operator_authorization_request_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("profit_operator_authorization_request_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=read-only profit operator authorization request only; does not approve live trading, TinyLive execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[profit-operator-authorization-request] read-only check complete"

if ($RequireReady -and -not $requestReady) {
    throw "Profit operator authorization request is not ready: $status; blockers=$(@($requestBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
