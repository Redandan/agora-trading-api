param(
    [string]$Grid10SourceRefreshLogPath = "target/profit-review/profit-grid10-activation-source-refresh-latest.log",
    [string]$SameSessionActivationReviewLogPath = "",
    [string]$Symbol = "BTCUSDT",
    [int]$GridCount = 2,
    [decimal]$PerLevelUsdt = 5,
    [decimal]$StopOutPct = 5.0,
    [decimal]$CandidateHalfWidthPct = 10.0,
    [decimal]$MaxCapitalUsdt = 10,
    [switch]$AllowDirtyLocalWorktreeForReplay,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Get-PropertyBool {
    param([object]$Object, [string]$Name)
    $value = Get-PropertyValue -Object $Object -Name $Name
    return $value.Trim().ToLowerInvariant() -eq "true"
}

function Get-DecimalOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    try { return [decimal]$Value } catch { return $null }
}

function Get-StringArray {
    param($Values)
    $rows = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) { $rows.Add($text) }
    }
    return @($rows)
}

function Read-PacketLog {
    param([string]$PathValue, [string]$Name, [string]$Prefix)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{
            Path = $resolved
            Text = ""
            Packet = $null
            Status = "MISSING"
            Source = $Name
        }
    }
    $text = Get-Content -Raw -LiteralPath $resolved
    return [pscustomobject]@{
        Path = $resolved
        Text = $text
        Packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix $Prefix)
        Status = "PRESENT"
        Source = $Name
    }
}

if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for grid10 execution preflight arguments."
}
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30) { throw "CandidateHalfWidthPct must be between 2.5 and 30." }
if ($MaxCapitalUsdt -lt 5 -or $MaxCapitalUsdt -gt 1000) { throw "MaxCapitalUsdt must be between 5 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$missingRequirements = [System.Collections.Generic.List[string]]::new()
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$usingExplicitLogs = $PSBoundParameters.ContainsKey("Grid10SourceRefreshLogPath") -or $PSBoundParameters.ContainsKey("SameSessionActivationReviewLogPath")
if (-not $worktreeClean -and -not ($usingExplicitLogs -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before grid10 execution preflight"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$source = Read-PacketLog -PathValue $Grid10SourceRefreshLogPath -Name "grid10 activation source refresh" -Prefix "profit_grid10_activation_source_refresh_packet="
if ($source.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation source refresh log present" }
if ($source.Status -ne "MISSING" -and $null -eq $source.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_grid10_activation_source_refresh_packet valid JSON" }

$sourcePacket = $source.Packet
$sourceStatus = Get-PropertyValue -Object $sourcePacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $source.Text -Prefix "profit_grid10_activation_source_refresh_status=" -Default "UNKNOWN"
}
$sourceReady = Get-PropertyBool -Object $sourcePacket -Name "ready"
if (-not $sourceReady) {
    $sourceReady = (Get-LastPrefixedValue -Text $source.Text -Prefix "profit_grid10_activation_source_refresh_same_session_ready=" -Default "false") -eq "true"
}
$sourceFailedCountText = Get-PropertyValue -Object $sourcePacket -Name "failedStepCount"
$sourceFailedCount = 0
if (-not [string]::IsNullOrWhiteSpace($sourceFailedCountText)) {
    try { $sourceFailedCount = [int]$sourceFailedCountText } catch { $sourceFailedCount = 999 }
}

if ([string]::IsNullOrWhiteSpace($SameSessionActivationReviewLogPath)) {
    $SameSessionActivationReviewLogPath = Get-PropertyValue -Object $sourcePacket -Name "sameSessionActivationReviewLogPath"
    if ([string]::IsNullOrWhiteSpace($SameSessionActivationReviewLogPath)) {
        $SameSessionActivationReviewLogPath = "target/profit-review/profit-grid10-same-session-activation-review-latest.log"
    }
}

$sameSession = Read-PacketLog -PathValue $SameSessionActivationReviewLogPath -Name "grid10 same-session activation review" -Prefix "profit_grid10_same_session_activation_review_packet="
if ($sameSession.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session activation review log present" }
if ($sameSession.Status -ne "MISSING" -and $null -eq $sameSession.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_grid10_same_session_activation_review_packet valid JSON" }

$samePacket = $sameSession.Packet
$sameStatus = Get-PropertyValue -Object $samePacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sameStatus)) {
    $sameStatus = Get-LastPrefixedValue -Text $sameSession.Text -Prefix "profit_grid10_same_session_activation_review_status=" -Default "UNKNOWN"
}
$sameReady = Get-PropertyBool -Object $samePacket -Name "sameSessionOperatorChecklistReady"
$sameExecutionAllowed = Get-PropertyBool -Object $samePacket -Name "sameSessionExecutionAllowed"
$sameEnvDeployAllowed = Get-PropertyBool -Object $samePacket -Name "sameSessionEnvDeployAllowed"
$candidateCapitalUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "candidateCapitalUsdt")
$effectiveReviewCapitalCapUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "effectiveReviewCapitalCapUsdt")
$replayScore = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "replayScore")
$trendGate = Get-PropertyValue -Object $samePacket -Name "trendGate"
$sourceGridCount = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "gridCount")
$sourcePerLevelUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "perLevelUsdt")
$sourceStopOutPct = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "stopOutPct")
$sourceHalfWidthPct = Get-DecimalOrNull (Get-PropertyValue -Object $samePacket -Name "candidateHalfWidthPct")
$exactAuthorizationText = Get-PropertyValue -Object $samePacket -Name "exactSameSessionAuthorizationText"
$operatorChecklist = if ($null -ne $samePacket -and $null -ne $samePacket.PSObject.Properties["sameSessionOperatorChecklist"]) { Get-StringArray $samePacket.sameSessionOperatorChecklist } else { @() }
$proposedEnvDiff = if ($null -ne $samePacket -and $null -ne $samePacket.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $samePacket.proposedEnvDiff } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $samePacket -and $null -ne $samePacket.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $samePacket.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $samePacket -and $null -ne $samePacket.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $samePacket.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $samePacket -and $null -ne $samePacket.PSObject.Properties["rollbackCommands"]) { Get-StringArray $samePacket.rollbackCommands } else { @() }

if ($sourceStatus -ne "READY_REFRESHED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 source refresh ready"
}
if (-not $sourceReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 source refresh same-session ready flag true" }
if ($sourceFailedCount -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 source refresh failedStepCount=0" }
if ($sameStatus -ne "READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session activation review ready"
}
if (-not $sameReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 sameSessionOperatorChecklistReady=true" }
if ($sameExecutionAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session packet keeps sameSessionExecutionAllowed=false" }
if ($sameEnvDeployAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session packet keeps sameSessionEnvDeployAllowed=false" }
if ($sourceGridCount -ne $GridCount) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session gridCount matches requested GridCount" }
if ($sourcePerLevelUsdt -ne $PerLevelUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session perLevelUsdt matches requested PerLevelUsdt" }
if ($sourceStopOutPct -ne $StopOutPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session stopOutPct matches requested StopOutPct" }
if ($sourceHalfWidthPct -ne $CandidateHalfWidthPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session candidateHalfWidthPct matches requested CandidateHalfWidthPct" }
if ($null -eq $candidateCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 candidateCapitalUsdt present"
} elseif ($candidateCapitalUsdt -gt $MaxCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 candidateCapitalUsdt <= MaxCapitalUsdt"
}
if ($null -eq $effectiveReviewCapitalCapUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 effectiveReviewCapitalCapUsdt present" }
if ($null -eq $replayScore -or $replayScore -lt 70) { Add-MissingRequirement -List $missingRequirements -Value "grid10 replayScore >= 70" }
if ([string]::IsNullOrWhiteSpace($exactAuthorizationText) -or $exactAuthorizationText -notmatch "GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW" -or $exactAuthorizationText -notmatch "can lose money") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 exact same-session authorization text present"
}
foreach ($flagName in @("productionEnvChangeAllowed", "deployAllowed", "livePolicyChangeAllowed", "schedulerEnablementAllowed", "orderAllowed", "positionOrOcoMutationAllowed", "createGridAllowed", "gridMutationAllowed", "telegramSendAllowed", "dbGridFundEarnExchangeMutationAllowed")) {
    if (Get-PropertyBool -Object $samePacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session packet keeps $flagName=false"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 preflight env diff includes $line"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=false", "TRADING_GRID_ENABLED=false", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($killSwitchEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 preflight kill-switch env diff includes $line"
    }
}
foreach ($pattern in @("verify_split_acceptance_ssh.ps1", "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1", "prepare_grid_open_authorization_bundle_ssh.ps1", "prepare_profit_grid10_order_path_handoff.ps1")) {
    if (@(@($postEnvReadOnlyVerification) -match $pattern).Count -eq 0) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 preflight post-env read-only verification includes $pattern"
    }
}
if (@($operatorChecklist).Count -lt 7) { Add-MissingRequirement -List $missingRequirements -Value "grid10 same-session checklist has all ordered steps" }
if (@($rollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 rollback commands present" }

$preflightReady = $missingRequirements.Count -eq 0
$status = if ($preflightReady) {
    "READY_FOR_PROFIT_GRID10_ENV_DEPLOY_CREATEGRID_EXECUTION_PREFLIGHT_NOT_MUTATION"
} else {
    "BLOCKED_PROFIT_GRID10_EXECUTION_PREFLIGHT_REQUIREMENTS_MISSING"
}
$decision = if ($preflightReady) {
    "PRESENT_GRID10_EXACT_AUTHORIZATION_AND_EXECUTION_ORDER_TO_OPERATOR_DO_NOT_EXECUTE"
} else {
    "REFRESH_GRID10_SOURCE_CHAIN_BEFORE_ENV_DEPLOY_OR_CREATEGRID_PREFLIGHT"
}

$executionOrder = @(
    "1. Operator confirms exactSameSessionAuthorizationText in this chat session.",
    "2. Apply only proposedEnvDiff through the approved deploy runbook after explicit confirmation.",
    "3. Deploy/restart current origin/main only after the exact env diff is confirmed.",
    "4. Run every postEnvReadOnlyVerificationCommand and preserve logs.",
    "5. Do not review createGrid unless post-env verification is clean and candidate inputs still match this packet.",
    "6. If any abnormal order/OCO/grid/scheduler/Earn/Telegram/DB/fund/exchange mutation or input drift appears, apply killSwitchEnvDiff and rollbackCommands.",
    "7. Review createGrid as a separate operator action; this packet never calls createGrid."
)
$preDeployLocalReviewCommands = @(
    ".\scripts\prepare_profit_grid10_activation_source_refresh.ps1 -RequireReady",
    ".\scripts\prepare_profit_grid10_execution_preflight_packet.ps1 -RequireReady",
    "git status --short --branch",
    "git diff --check"
)
$deployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
$nextAction = if ($preflightReady) {
    "The grid10 execution preflight is ready to present to the operator for exact same-session authorization. This packet still does not deploy, change env, place orders, or call createGrid."
} else {
    "Do not request env/deploy/createGrid authorization. Refresh the grid10 source chain and rerun this preflight."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_GRID10_EXECUTION_PREFLIGHT_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    gridCount = $GridCount
    perLevelUsdt = $PerLevelUsdt
    stopOutPct = $StopOutPct
    candidateHalfWidthPct = $CandidateHalfWidthPct
    maxCapitalUsdt = $MaxCapitalUsdt
    candidateCapitalUsdt = $candidateCapitalUsdt
    effectiveReviewCapitalCapUsdt = $effectiveReviewCapitalCapUsdt
    replayScore = $replayScore
    trendGate = $trendGate
    sourceRefreshLogPath = $Grid10SourceRefreshLogPath
    sameSessionActivationReviewLogPath = $SameSessionActivationReviewLogPath
    sourceRefreshStatus = $sourceStatus
    sameSessionActivationReviewStatus = $sameStatus
    sourceRefreshReady = $sourceReady
    sameSessionOperatorChecklistReady = $sameReady
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    preflightReady = $preflightReady
    exactSameSessionAuthorizationText = $exactAuthorizationText
    executionOrder = @($executionOrder)
    preDeployLocalReviewCommands = @($preDeployLocalReviewCommands)
    deployCommandAfterExplicitAuthorization = $deployCommand
    proposedEnvDiff = @($proposedEnvDiff)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    grid10ExecutionPreflightReady = $preflightReady
    grid10EnvDeployExecutionAllowed = $false
    grid10CreateGridExecutionAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    createGridAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only profit grid10 execution preflight only; it prepares exact operator execution order but does not push, deploy, restart, reload nginx, change production env, approve trend override, approve capital override, enable TRADING_OKX, call createGrid, enable scheduler/recovery/Earn, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-grid10-execution-preflight] read-only packet"
Write-Host "scope=READ_ONLY; consumes saved local grid10 source-refresh and same-session review logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_grid10_execution_preflight_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "profit_grid10_execution_preflight_status=$status"
Write-Host "profit_grid10_execution_preflight_decision=$decision"
Write-Host "source_grid10_activation_source_refresh_status=$sourceStatus"
Write-Host "source_grid10_same_session_activation_review_status=$sameStatus"
Write-Host "grid10_execution_preflight_ready=$($preflightReady.ToString().ToLowerInvariant())"
Write-Host "grid10_execution_exact_authorization_text=$exactAuthorizationText"
Write-Host ("grid10_execution_order=" + (ConvertTo-Json -Compress @($executionOrder)))
Write-Host ("grid10_execution_pre_deploy_local_review_commands=" + (ConvertTo-Json -Compress @($preDeployLocalReviewCommands)))
Write-Host "grid10_execution_deploy_command_after_explicit_authorization=$deployCommand"
Write-Host ("grid10_execution_env_diff=" + (ConvertTo-Json -Compress @($proposedEnvDiff)))
Write-Host ("grid10_execution_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("grid10_execution_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("grid10_execution_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "grid10_candidate_capital_usdt=$candidateCapitalUsdt"
Write-Host "grid10_effective_review_capital_cap_usdt=$effectiveReviewCapitalCapUsdt"
Write-Host "grid10_replay_score=$replayScore"
Write-Host "grid10_trend_gate=$trendGate"
Write-Host "grid10_env_deploy_execution_allowed=false"
Write-Host "grid10_create_grid_execution_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-grid10-execution-preflight] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_GRID10_ENV_DEPLOY_CREATEGRID_EXECUTION_PREFLIGHT_NOT_MUTATION") {
    throw "Profit grid10 execution preflight is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
