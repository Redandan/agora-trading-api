param(
    [string]$SemanticResolutionLogPath = "target/profit-review/entry-dedup-open-exposure-semantic-resolution-latest.log",
    [string]$DefaultOffChangeRequestLogPath = "target/profit-review/entry-dedup-live-gate-default-off-change-request-latest.log",
    [string]$PriorityBoardLogPath = "target/profit-review/entry-dedup-post-semantic-blocker-priority-board-latest.log",
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

function Read-TextFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name file not found: $resolved"
    }
    return Get-Content -Raw -LiteralPath $resolved
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

foreach ($path in @(
        $SemanticResolutionLogPath,
        $DefaultOffChangeRequestLogPath,
        $PriorityBoardLogPath,
        $ReportPath,
        $RunbookPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$semanticLog = Read-FreshLog -Name "entry-dedup-open-exposure-semantic-resolution" -PathValue $SemanticResolutionLogPath -MaxAge $MaxAgeMinutes
$defaultOffLog = Read-FreshLog -Name "entry-dedup-live-gate-default-off-change-request" -PathValue $DefaultOffChangeRequestLogPath -MaxAge $MaxAgeMinutes
$priorityLog = Read-FreshLog -Name "entry-dedup-post-semantic-blocker-priority-board" -PathValue $PriorityBoardLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($semanticLog, $defaultOffLog, $priorityLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$semanticPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $semanticLog.Text -Prefix "entry_dedup_open_exposure_semantic_resolution_packet=")
$defaultOffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $defaultOffLog.Text -Prefix "entry_dedup_live_gate_default_off_change_request_packet=")
$priorityPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $priorityLog.Text -Prefix "entry_dedup_post_semantic_blocker_priority_board_packet=")
if ($null -eq $semanticPacket) { Add-Missing -List $missing -Value "semantic resolution packet JSON present" }
if ($null -eq $defaultOffPacket) { Add-Missing -List $missing -Value "default-off change request packet JSON present" }
if ($null -eq $priorityPacket) { Add-Missing -List $missing -Value "post-semantic priority board packet JSON present" }

$semanticStatus = [string](Get-Prop -Object $semanticPacket -Name "status" -Default "UNKNOWN")
$defaultOffStatus = [string](Get-Prop -Object $defaultOffPacket -Name "status" -Default "UNKNOWN")
$priorityStatus = [string](Get-Prop -Object $priorityPacket -Name "status" -Default "UNKNOWN")
if ($semanticStatus -ne "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "semantic resolution packet ready"
}
if ($defaultOffStatus -ne "READY_FOR_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_NOT_LIVE") {
    Add-Missing -List $missing -Value "default-off change request packet ready"
}
if ($priorityStatus -ne "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE") {
    Add-Missing -List $missing -Value "post-semantic priority board ready"
}

$symbol = [string](Get-Prop -Object $semanticPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $semanticPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $semanticPacket -Name "intervalCode" -Default "1h")
$actualAutoExposureClear = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "actualAutoExposureClear") -Default $false)
$semanticBlockerPresent = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "semanticBlockerPresent") -Default $false)
$zeroQtyMissingOcoReviewRequired = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "zeroQtyMissingOcoSemanticsReviewRequired") -Default $false)
$operatorSemanticsChoiceRequired = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "operatorSemanticsChoiceRequired") -Default $false)
$openSignalRows = Get-IntValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "openSignalRows") -Default 0)
$autoTradedOpenRows = Get-IntValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "autoTradedOpenRows") -Default 0)
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "nonAutoZeroQtyRows") -Default 0)
$missingOcoRows = Get-IntValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "missingOcoRows") -Default 0)

$nextBlocker = [string](Get-Prop -Object $priorityPacket -Name "nextBlocker" -Default "UNKNOWN")
$remainingBlockerCount = Get-IntValue (Get-Prop -Object $priorityPacket -Name "remainingBlockerCount" -Default 0)
$boardOpenExposureBlocked = Get-BoolValue (Get-NestedProp -Object $priorityPacket -Path @("boardEvidence", "openExposureSemanticBlocked") -Default $false)
$boardOrderAllowed = Get-BoolValue (Get-NestedProp -Object $priorityPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)

$configKey = [string](Get-NestedProp -Object $defaultOffPacket -Path @("proposedChange", "configKey") -Default "UNKNOWN")
$defaultScope = [string](Get-NestedProp -Object $defaultOffPacket -Path @("proposedChange", "defaultScope") -Default "UNKNOWN")
$requestedOptionalScope = [string](Get-NestedProp -Object $defaultOffPacket -Path @("proposedChange", "requestedOptionalScope") -Default "UNKNOWN")
$confirmText = [string](Get-NestedProp -Object $defaultOffPacket -Path @("proposedChange", "confirmText") -Default "")
$scopeMismatchPresent = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("evidence", "scopeMismatchPresent") -Default $false)
$explainsCurrentNoBuy = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("evidence", "explainsCurrentNoBuy") -Default $false)
$requestReady = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("reviewEnvelope", "requestReady") -Default $false)
$implementationAllowed = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("reviewEnvelope", "implementationAllowed") -Default $true)
$behaviorChangeAllowed = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("reviewEnvelope", "behaviorChangeAllowed") -Default $true)
$liveGateChangeAllowed = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("reviewEnvelope", "liveGateChangeAllowed") -Default $true)
$defaultOffOrderAllowed = Get-BoolValue (Get-NestedProp -Object $defaultOffPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Open Exposure Operator Choice Review") -and $reportText.Contains($confirmText)
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_open_exposure_operator_choice_packet.ps1") -and $runbookNormalizedText.Contains("operator choice review is not authorization to clear exposure")

if (-not $actualAutoExposureClear) { Add-Missing -List $missing -Value "actual auto exposure is clear" }
if (-not $semanticBlockerPresent) { Add-Missing -List $missing -Value "semantic blocker remains present" }
if (-not $zeroQtyMissingOcoReviewRequired) { Add-Missing -List $missing -Value "zero-qty missing-OCO review is required" }
if (-not $operatorSemanticsChoiceRequired) { Add-Missing -List $missing -Value "operator semantic choice is required" }
if ($openSignalRows -lt 1) { Add-Missing -List $missing -Value "open signal row evidence exists" }
if ($autoTradedOpenRows -ne 0) { Add-Missing -List $missing -Value "auto-traded open rows remain zero" }
if ($nonAutoZeroQtyRows -lt 1) { Add-Missing -List $missing -Value "non-auto zero-qty row evidence exists" }
if ($missingOcoRows -lt 1) { Add-Missing -List $missing -Value "missing-OCO row evidence exists" }
if ($nextBlocker -ne "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS") { Add-Missing -List $missing -Value "priority board next blocker is open exposure semantics" }
if ($remainingBlockerCount -lt 1) { Add-Missing -List $missing -Value "priority board still has blockers" }
if (-not $boardOpenExposureBlocked) { Add-Missing -List $missing -Value "priority board keeps open exposure blocked" }
if ($boardOrderAllowed) { Add-Missing -List $missing -Value "priority board keeps orders disallowed" }
if ($configKey -ne "entryDedupOpenExposureScope") { Add-Missing -List $missing -Value "default-off request config key is entryDedupOpenExposureScope" }
if ($defaultScope -ne "ALL_OPEN_ROWS") { Add-Missing -List $missing -Value "default-off request default scope preserves all open rows" }
if ($requestedOptionalScope -ne "AUTO_TRADED_OPEN_ROWS") { Add-Missing -List $missing -Value "default-off request optional scope is auto-traded open rows" }
if ($confirmText -ne "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW") { Add-Missing -List $missing -Value "default-off request confirm text is present" }
if (-not $scopeMismatchPresent) { Add-Missing -List $missing -Value "scope mismatch evidence is present" }
if (-not $explainsCurrentNoBuy) { Add-Missing -List $missing -Value "default-off request explains current no-buy" }
if (-not $requestReady) { Add-Missing -List $missing -Value "default-off request is ready" }
if ($implementationAllowed) { Add-Missing -List $missing -Value "implementation remains disallowed by source request" }
if ($behaviorChangeAllowed) { Add-Missing -List $missing -Value "behavior change remains disallowed by source request" }
if ($liveGateChangeAllowed) { Add-Missing -List $missing -Value "live gate change remains disallowed by source request" }
if ($defaultOffOrderAllowed) { Add-Missing -List $missing -Value "orders remain disallowed by source request" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes open exposure operator choice section" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes open exposure operator choice instructions" }

$ready = $missing.Count -eq 0
$activationOperatorNote = "Strategy 508 EntryDedup open exposure scope review: $confirmText; apply AUTO_TRADED_OPEN_ROWS only after confirming current blocker is zero-qty non-auto rows and auto-traded open rows remain zero."
$activationCommandPreview = "setStrategyFlags(strategyId=$strategyId, entryDedupOpenExposureScope=$requestedOptionalScope, note=<operator-note>)"
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_REVIEW_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "PRESENT_OPERATOR_CHOICES_FOR_ZERO_QTY_NON_AUTO_OPEN_EXPOSURE_NOT_IMPLEMENTATION"
} else {
    "REFRESH_ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_OPEN_EXPOSURE_OPERATOR_CHOICE_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceStatuses = [ordered]@{
        semanticResolution = $semanticStatus
        defaultOffChangeRequest = $defaultOffStatus
        postSemanticPriorityBoard = $priorityStatus
    }
    blockerEvidence = [ordered]@{
        nextBlocker = $nextBlocker
        remainingBlockerCount = $remainingBlockerCount
        actualAutoExposureClear = $actualAutoExposureClear
        semanticBlockerPresent = $semanticBlockerPresent
        zeroQtyMissingOcoReviewRequired = $zeroQtyMissingOcoReviewRequired
        operatorSemanticsChoiceRequired = $operatorSemanticsChoiceRequired
        openSignalRows = $openSignalRows
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        missingOcoRows = $missingOcoRows
        scopeMismatchPresent = $scopeMismatchPresent
        explainsCurrentNoBuy = $explainsCurrentNoBuy
    }
    operatorChoices = @(
        [ordered]@{
            choiceId = "KEEP_BLOCKED"
            effect = "Preserve current all-open-row gate and keep EntryDedup blocked."
            authorizationRequired = $false
        },
        [ordered]@{
            choiceId = "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION"
            effect = "Review a default-off code option that keeps ALL_OPEN_ROWS by default and only uses AUTO_TRADED_OPEN_ROWS when separately configured."
            exactAuthorizationText = $confirmText
            authorizationRequired = $true
        },
        [ordered]@{
            choiceId = "REFRESH_RUNTIME_SNAPSHOT_BEFORE_DECISION"
            effect = "Defer semantics choice and refresh runtime snapshots before any behavior review."
            authorizationRequired = $false
        }
    )
    proposedChange = [ordered]@{
        configKey = $configKey
        defaultScope = $defaultScope
        requestedOptionalScope = $requestedOptionalScope
        confirmText = $confirmText
        recommendedReviewChoice = "REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION"
    }
    activationPreflight = [ordered]@{
        preflightReady = $ready
        exactMcpTool = "setStrategyFlags"
        exactMcpArguments = [ordered]@{
            strategyId = $strategyId
            entryDedupOpenExposureScope = $requestedOptionalScope
            note = $activationOperatorNote
        }
        commandPreview = $activationCommandPreview
        expectedRuntimeEffect = "Only auto_traded=1 open rows count as same strategy/symbol/interval open exposure for EntryDedup after the strategy config is applied."
        rollbackConfigValue = $defaultScope
        preApplyChecks = @(
            "Confirm autoTradedOpenRows is 0 and nonAutoZeroQtyRows is greater than 0 in this packet.",
            "Confirm post-semantic priority board still names OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS as the next blocker.",
            "Confirm default-off implementation packet is ready and no order/live/env/deploy mutation has been performed."
        )
        postApplyReadOnlyChecks = @(
            "Read strategy config and verify entryDedupOpenExposureScope=AUTO_TRADED_OPEN_ROWS.",
            "Rerun smoke_strategy508_entry_dedup_exposure_ssh.ps1 and confirm shadow zero rows are ignored by EntryDedup scope.",
            "Rerun post-fix strategy monitoring packet and verify any remaining no-buy blocker is not zero-qty non-auto open exposure."
        )
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        choiceReviewReady = $ready
        openExposureClearanceAllowed = $false
        implementationAllowed = $false
        strategyConfigMutationAllowed = $false
        behaviorChangeAllowed = $false
        liveGateChangeAllowed = $false
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
    nextAction = "Present these options to the operator; do not clear exposure or implement gate changes without a separate explicit authorization."
    notAuthorization = "read-only EntryDedup open exposure operator choice review only; does not clear exposure, implement Java behavior, change live gate semantics, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-open-exposure-operator-choice-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved open-exposure semantics, default-off request, priority board, report, and runbook only; no Java behavior, SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_open_exposure_operator_choice_status=$status"
Write-Host "entry_dedup_open_exposure_operator_choice_decision=$decision"
Write-Host "entry_dedup_open_exposure_operator_choice_next_blocker=$nextBlocker"
Write-Host "entry_dedup_open_exposure_operator_choice_remaining_blocker_count=$remainingBlockerCount"
Write-Host "entry_dedup_open_exposure_operator_choice_actual_auto_exposure_clear=$($actualAutoExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_zero_qty_missing_oco_review_required=$($zeroQtyMissingOcoReviewRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_operator_semantics_choice_required=$($operatorSemanticsChoiceRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_scope_mismatch_present=$($scopeMismatchPresent.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_explains_current_no_buy=$($explainsCurrentNoBuy.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_config_key=$configKey"
Write-Host "entry_dedup_open_exposure_operator_choice_default_scope=$defaultScope"
Write-Host "entry_dedup_open_exposure_operator_choice_requested_optional_scope=$requestedOptionalScope"
Write-Host "entry_dedup_open_exposure_operator_choice_confirm_text=$confirmText"
Write-Host "entry_dedup_open_exposure_operator_choice_recommended_review_choice=REVIEW_DEFAULT_OFF_AUTO_TRADED_SCOPE_IMPLEMENTATION"
Write-Host "entry_dedup_open_exposure_operator_choice_activation_preflight_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_activation_mcp_tool=setStrategyFlags"
Write-Host "entry_dedup_open_exposure_operator_choice_activation_command_preview=$activationCommandPreview"
Write-Host "entry_dedup_open_exposure_operator_choice_activation_requested_scope=$requestedOptionalScope"
Write-Host "entry_dedup_open_exposure_operator_choice_activation_rollback_scope=$defaultScope"
Write-Host "entry_dedup_open_exposure_operator_choice_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_open_exposure_operator_choice_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_open_exposure_operator_choice_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_open_exposure_operator_choice_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "choice_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "open_exposure_clearance_allowed=false"
Write-Host "implementation_allowed=false"
Write-Host "strategy_config_mutation_allowed=false"
Write-Host "behavior_change_allowed=false"
Write-Host "live_gate_change_allowed=false"
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
Write-Host "notAuthorization=read-only EntryDedup open exposure operator choice review only; does not clear exposure, implement Java behavior, change live gate semantics, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-open-exposure-operator-choice-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup open exposure operator choice packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
