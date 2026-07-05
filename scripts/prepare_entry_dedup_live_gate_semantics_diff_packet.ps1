param(
    [string]$LiveSignalEvaluatorPath = "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java",
    [string]$StagedAddPolicyServicePath = "src/main/java/com/agora/service/trading/StagedAddPolicyService.java",
    [string]$OpenExposureSemanticResolutionLogPath = "target/profit-review/entry-dedup-open-exposure-semantic-resolution-latest.log",
    [string]$PostSemanticBlockerPriorityBoardLogPath = "target/profit-review/entry-dedup-post-semantic-blocker-priority-board-latest.log",
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
        $LiveSignalEvaluatorPath,
        $StagedAddPolicyServicePath,
        $OpenExposureSemanticResolutionLogPath,
        $PostSemanticBlockerPriorityBoardLogPath,
        $ReportPath,
        $RunbookPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$liveSignalSource = Read-TextFile -Name "LiveSignalEvaluator source" -PathValue $LiveSignalEvaluatorPath
$stagedAddSource = Read-TextFile -Name "StagedAddPolicyService source" -PathValue $StagedAddPolicyServicePath
$semanticLog = Read-FreshLog -Name "entry-dedup-open-exposure-semantic-resolution" -PathValue $OpenExposureSemanticResolutionLogPath -MaxAge $MaxAgeMinutes
$boardLog = Read-FreshLog -Name "entry-dedup-post-semantic-blocker-priority-board" -PathValue $PostSemanticBlockerPriorityBoardLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($semanticLog, $boardLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$semanticPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $semanticLog.Text -Prefix "entry_dedup_open_exposure_semantic_resolution_packet=")
$boardPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $boardLog.Text -Prefix "entry_dedup_post_semantic_blocker_priority_board_packet=")
if ($null -eq $semanticPacket) { Add-Missing -List $missing -Value "open exposure semantic resolution packet JSON present" }
if ($null -eq $boardPacket) { Add-Missing -List $missing -Value "post-semantic blocker priority board packet JSON present" }

$semanticStatus = [string](Get-Prop -Object $semanticPacket -Name "status" -Default "UNKNOWN")
$boardStatus = [string](Get-Prop -Object $boardPacket -Name "status" -Default "UNKNOWN")
if ($semanticStatus -ne "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_SEMANTIC_RESOLUTION_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "semantic resolution packet ready"
}
if ($boardStatus -ne "READY_FOR_ENTRY_DEDUP_POST_SEMANTIC_BLOCKER_PRIORITY_BOARD_NOT_LIVE") {
    Add-Missing -List $missing -Value "post-semantic blocker board ready"
}

$liveSignalHasCoarseAllOpenGate = $liveSignalSource.Contains("existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull") `
    -and $liveSignalSource.Contains("hasOpenLongExposure")
$stagedAddUsesAutoTradedOpenRows = $stagedAddSource.Contains("findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull") `
    -and $stagedAddSource.Contains("findByAutoTradedIsTrueAndExitTimeIsNull")
$liveSignalGateScope = if ($liveSignalHasCoarseAllOpenGate) { "ALL_EXIT_TIME_NULL_ROWS" } else { "UNKNOWN" }
$stagedAddGateScope = if ($stagedAddUsesAutoTradedOpenRows) { "AUTO_TRADED_EXIT_TIME_NULL_ROWS" } else { "UNKNOWN" }
$scopeMismatchPresent = $liveSignalHasCoarseAllOpenGate -and $stagedAddUsesAutoTradedOpenRows -and $liveSignalGateScope -ne $stagedAddGateScope

$actualAutoExposureClear = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "actualAutoExposureClear") -Default $false)
$operatorSemanticsChoiceRequired = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("semanticEvidence", "operatorSemanticsChoiceRequired") -Default $false)
$openExposureClearanceAllowed = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("reviewEnvelope", "openExposureClearanceAllowed") -Default $true)
$semanticOrderAllowed = Get-BoolValue (Get-NestedProp -Object $semanticPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)
$remainingBlockerCount = Get-IntValue (Get-Prop -Object $boardPacket -Name "remainingBlockerCount" -Default 0)
$nextBlocker = [string](Get-Prop -Object $boardPacket -Name "nextBlocker" -Default "UNKNOWN")
$boardOrderAllowed = Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)
$boardLiveExecutionReady = Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "liveExecutionReady") -Default $true)

$explainsCurrentNoBuy = $scopeMismatchPresent `
    -and $actualAutoExposureClear `
    -and $operatorSemanticsChoiceRequired `
    -and $nextBlocker -eq "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS"

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Live Gate Semantics Diff") -and $reportText.Contains("LIVE_GATE_SEMANTICS_DIFF")
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_live_gate_semantics_diff_packet.ps1") -and $runbookNormalizedText.Contains("gate semantics diff is not a behavior change")

if (-not $liveSignalHasCoarseAllOpenGate) { Add-Missing -List $missing -Value "LiveSignalEvaluator coarse all-open-row gate marker present" }
if (-not $stagedAddUsesAutoTradedOpenRows) { Add-Missing -List $missing -Value "StagedAddPolicyService auto-traded-only gate marker present" }
if (-not $scopeMismatchPresent) { Add-Missing -List $missing -Value "gate scope mismatch is visible" }
if (-not $actualAutoExposureClear) { Add-Missing -List $missing -Value "actual auto exposure is clear in semantic packet" }
if (-not $operatorSemanticsChoiceRequired) { Add-Missing -List $missing -Value "operator semantic choice remains required" }
if ($openExposureClearanceAllowed) { Add-Missing -List $missing -Value "open exposure clearance remains disallowed" }
if ($semanticOrderAllowed -or $boardOrderAllowed) { Add-Missing -List $missing -Value "orders remain disallowed" }
if ($boardLiveExecutionReady) { Add-Missing -List $missing -Value "live execution remains not ready" }
if ($remainingBlockerCount -lt 1) { Add-Missing -List $missing -Value "post-semantic blocker board still has blockers" }
if ($nextBlocker -ne "OPEN_EXPOSURE_ZERO_QTY_NON_AUTO_SEMANTICS") { Add-Missing -List $missing -Value "post-semantic next blocker is open exposure semantics" }
if (-not $explainsCurrentNoBuy) { Add-Missing -List $missing -Value "gate scope mismatch explains current no-buy blocker" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes live gate semantics diff" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes live gate semantics diff instructions" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "REVIEW_GATE_SCOPE_MISMATCH_BEFORE_ANY_ENTRY_DEDUP_POLICY_CHANGE"
} else {
    "REFRESH_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_PACKET"
    status = $status
    decision = $decision
    sourceEvidence = [ordered]@{
        liveSignalEvaluator = $LiveSignalEvaluatorPath
        stagedAddPolicyService = $StagedAddPolicyServicePath
        liveSignalGateScope = $liveSignalGateScope
        stagedAddGateScope = $stagedAddGateScope
        scopeMismatchPresent = $scopeMismatchPresent
    }
    blockerEvidence = [ordered]@{
        actualAutoExposureClear = $actualAutoExposureClear
        operatorSemanticsChoiceRequired = $operatorSemanticsChoiceRequired
        remainingBlockerCount = $remainingBlockerCount
        nextBlocker = $nextBlocker
        explainsCurrentNoBuy = $explainsCurrentNoBuy
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        sourceReviewReady = $ready
        behaviorChangeAllowed = $false
        liveGateChangeAllowed = $false
        openExposureClearanceAllowed = $false
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
    nextAction = "Use this packet to review whether LiveSignalEvaluator should keep the all-open-row dedup gate or receive a separately authorized, default-off auto-traded-only gate option."
    notAuthorization = "read-only EntryDedup live gate semantics diff packet only; does not change Java behavior, clear exposure, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-live-gate-semantics-diff-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads Java source plus saved EntryDedup semantic/priority packets and local report/runbook only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_live_gate_semantics_diff_status=$status"
Write-Host "entry_dedup_live_gate_semantics_diff_decision=$decision"
Write-Host "entry_dedup_live_gate_semantics_diff_live_signal_gate_scope=$liveSignalGateScope"
Write-Host "entry_dedup_live_gate_semantics_diff_staged_add_gate_scope=$stagedAddGateScope"
Write-Host "entry_dedup_live_gate_semantics_diff_scope_mismatch_present=$($scopeMismatchPresent.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_semantics_diff_actual_auto_exposure_clear=$($actualAutoExposureClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_semantics_diff_operator_semantics_choice_required=$($operatorSemanticsChoiceRequired.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_semantics_diff_remaining_blocker_count=$remainingBlockerCount"
Write-Host "entry_dedup_live_gate_semantics_diff_next_blocker=$nextBlocker"
Write-Host "entry_dedup_live_gate_semantics_diff_explains_current_no_buy=$($explainsCurrentNoBuy.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_semantics_diff_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_semantics_diff_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_live_gate_semantics_diff_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_live_gate_semantics_diff_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "source_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "behavior_change_allowed=false"
Write-Host "live_gate_change_allowed=false"
Write-Host "open_exposure_clearance_allowed=false"
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
Write-Host "notAuthorization=read-only EntryDedup live gate semantics diff packet only; does not change Java behavior, clear exposure, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-live-gate-semantics-diff-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup live gate semantics diff packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
