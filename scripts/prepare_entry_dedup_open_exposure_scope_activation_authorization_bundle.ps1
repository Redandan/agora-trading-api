param(
    [string]$OperatorChoiceLogPath = "target/profit-review/entry-dedup-open-exposure-operator-choice-latest.log",
    [string]$PriorityBoardLogPath = "target/profit-review/entry-dedup-post-semantic-blocker-priority-board-latest.log",
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

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
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

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($OperatorChoiceLogPath, $PriorityBoardLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$operatorChoiceLog = Read-FreshLog -Name "entry-dedup-open-exposure-operator-choice" -PathValue $OperatorChoiceLogPath -MaxAge $MaxAgeMinutes
$priorityLog = Read-FreshLog -Name "entry-dedup-post-semantic-blocker-priority-board" -PathValue $PriorityBoardLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($operatorChoiceLog, $priorityLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$operatorChoicePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $operatorChoiceLog.Text -Prefix "entry_dedup_open_exposure_operator_choice_packet=")
$priorityPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $priorityLog.Text -Prefix "entry_dedup_post_semantic_blocker_priority_board_packet=")
if ($null -eq $operatorChoicePacket) { Add-Missing -List $missing -Value "operator choice packet JSON present" }
if ($null -eq $priorityPacket) { Add-Missing -List $missing -Value "post-semantic priority board packet JSON present" }

$operatorChoiceStatus = [string](Get-Prop -Object $operatorChoicePacket -Name "status" -Default "UNKNOWN")
$priorityStatus = [string](Get-Prop -Object $priorityPacket -Name "status" -Default "UNKNOWN")
if ($operatorChoiceStatus -ne "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "operator choice packet ready"
}
if ($priorityStatus -ne "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE") {
    Add-Missing -List $missing -Value "post-semantic priority board ready"
}

$symbol = [string](Get-Prop -Object $operatorChoicePacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $operatorChoicePacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $operatorChoicePacket -Name "intervalCode" -Default "1h")
$nextBlocker = [string](Get-Prop -Object $priorityPacket -Name "nextBlocker" -Default "UNKNOWN")
$remainingBlockerCount = Get-IntValue (Get-Prop -Object $priorityPacket -Name "remainingBlockerCount" -Default 0)
$operatorChoiceReady = Get-BoolValue (Get-NestedProp -Object $priorityPacket -Path @("boardEvidence", "openExposureOperatorChoiceReady") -Default $false)
$activationPreflightReady = Get-BoolValue (Get-NestedProp -Object $operatorChoicePacket -Path @("activationPreflight", "preflightReady") -Default $false)
$exactMcpTool = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("activationPreflight", "exactMcpTool") -Default "")
$commandPreview = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("activationPreflight", "commandPreview") -Default "")
$requestedScope = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("activationPreflight", "exactMcpArguments", "entryDedupOpenExposureScope") -Default "")
$rollbackScope = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("activationPreflight", "rollbackConfigValue") -Default "")
$confirmText = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("proposedChange", "confirmText") -Default "")
$recommendedReviewChoice = [string](Get-NestedProp -Object $operatorChoicePacket -Path @("proposedChange", "recommendedReviewChoice") -Default "")
$actualAutoExposureClear = Get-BoolValue (Get-NestedProp -Object $operatorChoicePacket -Path @("blockerEvidence", "actualAutoExposureClear") -Default $false)
$autoTradedOpenRows = Get-IntValue (Get-NestedProp -Object $operatorChoicePacket -Path @("blockerEvidence", "autoTradedOpenRows") -Default -1)
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $operatorChoicePacket -Path @("blockerEvidence", "nonAutoZeroQtyRows") -Default 0)
$sourceStrategyConfigMutationAllowed = Get-BoolValue (Get-NestedProp -Object $operatorChoicePacket -Path @("reviewEnvelope", "strategyConfigMutationAllowed") -Default $true)
$sourceOrderAllowed = Get-BoolValue (Get-NestedProp -Object $operatorChoicePacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)
$priorityOrderAllowed = Get-BoolValue (Get-NestedProp -Object $priorityPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)

if ($nextBlocker -ne "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS") { Add-Missing -List $missing -Value "priority board next blocker is open exposure semantics" }
if ($remainingBlockerCount -lt 1) { Add-Missing -List $missing -Value "priority board still has blockers" }
if (-not $operatorChoiceReady) { Add-Missing -List $missing -Value "priority board marks operator choice ready" }
if (-not $activationPreflightReady) { Add-Missing -List $missing -Value "activation preflight ready" }
if ($exactMcpTool -ne "setStrategyFlags") { Add-Missing -List $missing -Value "activation MCP tool is setStrategyFlags" }
if ($commandPreview -notmatch "setStrategyFlags\(strategyId=508, entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS") { Add-Missing -List $missing -Value "activation command preview targets Strategy 508 AUTO_TRADED_OPEN_ROWS" }
if ($requestedScope -ne "AUTO_TRADED_OPEN_ROWS") { Add-Missing -List $missing -Value "requested scope is AUTO_TRADED_OPEN_ROWS" }
if ($rollbackScope -ne "ALL_OPEN_ROWS") { Add-Missing -List $missing -Value "rollback scope is ALL_OPEN_ROWS" }
if ($confirmText -ne "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW") { Add-Missing -List $missing -Value "confirm text is present" }
if ($recommendedReviewChoice -ne "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION") { Add-Missing -List $missing -Value "recommended review choice is default-off auto-traded scope" }
if (-not $actualAutoExposureClear) { Add-Missing -List $missing -Value "actual auto exposure is clear" }
if ($autoTradedOpenRows -ne 0) { Add-Missing -List $missing -Value "auto-traded open rows remain zero" }
if ($nonAutoZeroQtyRows -lt 1) { Add-Missing -List $missing -Value "non-auto zero-qty row evidence exists" }
if ($sourceStrategyConfigMutationAllowed) { Add-Missing -List $missing -Value "source operator choice keeps config mutation disallowed" }
if ($sourceOrderAllowed) { Add-Missing -List $missing -Value "source operator choice keeps orders disallowed" }
if ($priorityOrderAllowed) { Add-Missing -List $missing -Value "priority board keeps orders disallowed" }

$exactAuthorizationText = "I explicitly authorize AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW for Strategy $strategyId $symbol ${intervalCode}: set entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS by setStrategyFlags only, then run read-only post-apply verification; no order, live execution, env/deploy, exchange, OCO, grid, fund, Earn, Telegram, external backfill, or DB mutation other than this strategy config field is authorized."
$rollbackAuthorizationText = "I explicitly authorize rollback for Strategy $strategyId $symbol ${intervalCode}: set entryDedupOpenExposureScope=ALL_OPEN_ROWS by setStrategyFlags only, then rerun read-only verification."
$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING"
}
$decision = if ($ready) {
    "PRESENT_EXACT_SCOPE_CONFIG_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
} else {
    "REFRESH_OPEN_EXPOSURE_SCOPE_ACTIVATION_EVIDENCE_BEFORE_AUTHORIZATION"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_SCOPE_ACTIVATION_AUTHORIZATION_BUNDLE"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceStatuses = [ordered]@{
        openExposureOperatorChoice = $operatorChoiceStatus
        postSemanticPriorityBoard = $priorityStatus
    }
    activationEvidence = [ordered]@{
        nextBlocker = $nextBlocker
        remainingBlockerCount = $remainingBlockerCount
        operatorChoiceReady = $operatorChoiceReady
        activationPreflightReady = $activationPreflightReady
        actualAutoExposureClear = $actualAutoExposureClear
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        exactMcpTool = $exactMcpTool
        commandPreview = $commandPreview
        requestedScope = $requestedScope
        rollbackScope = $rollbackScope
        confirmText = $confirmText
        recommendedReviewChoice = $recommendedReviewChoice
    }
    exactAuthorizationText = $exactAuthorizationText
    exactMcpTool = $exactMcpTool
    exactMcpArguments = [ordered]@{
        strategyId = $strategyId
        entryDedupOpenExposureScope = $requestedScope
        note = $exactAuthorizationText
    }
    commandPreview = $commandPreview
    rollback = [ordered]@{
        scope = $rollbackScope
        exactAuthorizationText = $rollbackAuthorizationText
        commandPreview = "setStrategyFlags(strategyId=$strategyId, entryDedupOpenExposureScope=$rollbackScope, note=<operator-note>)"
    }
    preApplyReadOnlyChecks = @(
        ".\scripts\prepare_entry_dedup_open_exposure_operator_choice_packet.ps1 -RequireReady",
        ".\scripts\prepare_entry_dedup_post_semantic_blocker_priority_board.ps1 -RequireReady"
    )
    postApplyReadOnlyChecks = @(
        "Read strategy config and verify entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS.",
        ".\scripts\smoke_strategy508_entry_dedup_exposure_ssh.ps1",
        ".\scripts\prepare_entry_dedup_post_semantic_blocker_priority_board.ps1 -RequireReady"
    )
    activationAuthorizationReviewReady = $ready
    activationExecutionAllowed = $false
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        activationAuthorizationReviewReady = $ready
        activationExecutionAllowed = $false
        strategyConfigMutationAllowed = $false
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
    nextAction = "Present the exactAuthorizationText to the operator. This packet does not execute setStrategyFlags."
    notAuthorization = "read-only EntryDedup open exposure scope activation authorization bundle only; prepares exact operator text but does not execute setStrategyFlags, change strategy config, clear blockers, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-open-exposure-scope-activation-authorization-bundle] read-only packet"
Write-Host "scope=READ_ONLY; reads saved open-exposure operator choice and priority board logs only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host ("entry_dedup_open_exposure_scope_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 14 $packet))
Write-Host "entry_dedup_open_exposure_scope_activation_authorization_status=$status"
Write-Host "entry_dedup_open_exposure_scope_activation_authorization_decision=$decision"
Write-Host "source_open_exposure_operator_choice_status=$operatorChoiceStatus"
Write-Host "source_post_semantic_priority_board_status=$priorityStatus"
Write-Host "entry_dedup_open_exposure_scope_activation_authorization_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_scope_activation_authorization_text=$exactAuthorizationText"
Write-Host "entry_dedup_open_exposure_scope_activation_mcp_tool=$exactMcpTool"
Write-Host "entry_dedup_open_exposure_scope_activation_command_preview=$commandPreview"
Write-Host "entry_dedup_open_exposure_scope_activation_requested_scope=$requestedScope"
Write-Host "entry_dedup_open_exposure_scope_activation_rollback_scope=$rollbackScope"
Write-Host ("entry_dedup_open_exposure_scope_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host "scope_activation_execution_allowed=false"
Write-Host "strategy_config_mutation_allowed=false"
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
Write-Host "notAuthorization=read-only EntryDedup open exposure scope activation authorization bundle only"
Write-Host "[entry-dedup-open-exposure-scope-activation-authorization-bundle] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup open exposure scope activation authorization bundle is not ready: $status; missing=$(@($missing) -join '; ')"
}
