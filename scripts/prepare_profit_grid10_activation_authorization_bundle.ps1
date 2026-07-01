param(
    [string]$Grid10HandoffLogPath = "",
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
    throw "Symbol contains unsupported characters for grid10 activation authorization bundle arguments."
}
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30) { throw "CandidateHalfWidthPct must be between 2.5 and 30." }
if ($MaxCapitalUsdt -lt 5 -or $MaxCapitalUsdt -gt 1000) { throw "MaxCapitalUsdt must be between 5 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$defaultGrid10Log = "target/profit-review/profit-grid10-order-path-handoff-latest.log"
if ([string]::IsNullOrWhiteSpace($Grid10HandoffLogPath)) { $Grid10HandoffLogPath = $defaultGrid10Log }

$missingRequirements = [System.Collections.Generic.List[string]]::new()
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$usingExplicitLog = $PSBoundParameters.ContainsKey("Grid10HandoffLogPath")
if (-not $worktreeClean -and -not ($usingExplicitLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before grid10 activation authorization review"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$handoffSource = Read-PacketLog -PathValue $Grid10HandoffLogPath -Name "grid10 order path handoff" -Prefix "profit_grid10_order_path_handoff_packet="
if ($handoffSource.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff log present" }
if ($handoffSource.Status -ne "MISSING" -and $null -eq $handoffSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_grid10_order_path_handoff_packet valid JSON" }

$handoffPacket = $handoffSource.Packet
$handoffStatus = Get-PropertyValue -Object $handoffPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($handoffStatus)) {
    $handoffStatus = Get-LastPrefixedValue -Text $handoffSource.Text -Prefix "profit_grid10_order_path_handoff_status=" -Default "UNKNOWN"
}

$handoffReady = Get-PropertyBool -Object $handoffPacket -Name "grid10OrderPathHandoffReady"
$executionNowAllowed = Get-PropertyBool -Object $handoffPacket -Name "grid10ExecutionNowAllowed"
$envDeployRequestAllowed = Get-PropertyBool -Object $handoffPacket -Name "grid10EnvDeployRequestAllowed"
$candidateCapitalUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "candidateCapitalUsdt")
$effectiveReviewCapitalCapUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "effectiveReviewCapitalCapUsdt")
$replayScore = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "replayScore")
$sourceGridCount = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "gridCount")
$sourcePerLevelUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "perLevelUsdt")
$sourceStopOutPct = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "stopOutPct")
$sourceHalfWidthPct = Get-DecimalOrNull (Get-PropertyValue -Object $handoffPacket -Name "candidateHalfWidthPct")
$trendGate = Get-PropertyValue -Object $handoffPacket -Name "trendGate"
$trendLaneReady = Get-PropertyBool -Object $handoffPacket -Name "trendLaneReady"
$capitalReviewReady = Get-PropertyBool -Object $handoffPacket -Name "capitalOverrideReviewReady"
$envDiffReviewReady = Get-PropertyBool -Object $handoffPacket -Name "envDiffReviewReady"
$createPreflightComplete = Get-PropertyBool -Object $handoffPacket -Name "createGridPreflightEvidenceComplete"
$candidatePlanComplete = Get-PropertyBool -Object $handoffPacket -Name "candidatePlanComplete"
$existingActiveGridOrderPathActivationRisk = Get-PropertyBool -Object $handoffPacket -Name "existingActiveGridOrderPathActivationRisk"
$exactOperatorAuthorizationTexts = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["exactOperatorAuthorizationTexts"]) { Get-StringArray $handoffPacket.exactOperatorAuthorizationTexts } else { @() }
$remainingExecutionBlockers = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["remainingExecutionBlockers"]) { Get-StringArray $handoffPacket.remainingExecutionBlockers } else { @() }
$proposedEnvDiff = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $handoffPacket.proposedEnvDiff } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $handoffPacket.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $handoffPacket.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $handoffPacket -and $null -ne $handoffPacket.PSObject.Properties["rollbackCommands"]) { Get-StringArray $handoffPacket.rollbackCommands } else { @() }

if ($handoffStatus -ne "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 order path handoff ready"
}
if (-not $handoffReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 order path handoff ready flag true" }
if ($executionNowAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff keeps executionNowAllowed=false" }
if ($envDeployRequestAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff keeps envDeployRequestAllowed=false" }
if ($sourceGridCount -ne $GridCount) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff gridCount matches requested GridCount" }
if ($sourcePerLevelUsdt -ne $PerLevelUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff perLevelUsdt matches requested PerLevelUsdt" }
if ($sourceStopOutPct -ne $StopOutPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff stopOutPct matches requested StopOutPct" }
if ($sourceHalfWidthPct -ne $CandidateHalfWidthPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff candidateHalfWidthPct matches requested CandidateHalfWidthPct" }
if ($null -eq $candidateCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 candidateCapitalUsdt present"
} elseif ($candidateCapitalUsdt -gt $MaxCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 candidateCapitalUsdt <= MaxCapitalUsdt"
}
if ($null -eq $effectiveReviewCapitalCapUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 effectiveReviewCapitalCapUsdt present" }
if ($null -eq $replayScore -or $replayScore -lt 70) { Add-MissingRequirement -List $missingRequirements -Value "grid10 replayScore >= 70" }
if (-not $trendLaneReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 trend lane ready or fresh trend clearance accepted" }
if (-not $capitalReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 capital override review ready" }
if (-not $envDiffReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 env diff review ready" }
if (-not $createPreflightComplete) { Add-MissingRequirement -List $missingRequirements -Value "grid10 createGrid preflight evidence complete" }
if (-not $candidatePlanComplete) { Add-MissingRequirement -List $missingRequirements -Value "grid10 candidate plan complete" }
foreach ($flagName in @("productionEnvChangeAllowed", "deployAllowed", "livePolicyChangeAllowed", "schedulerEnablementAllowed", "orderAllowed", "positionOrOcoMutationAllowed", "createGridAllowed", "gridMutationAllowed", "telegramSendAllowed", "dbGridFundEarnExchangeMutationAllowed")) {
    if (Get-PropertyBool -Object $handoffPacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 handoff keeps $flagName=false"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 activation env diff includes $line"
    }
}
foreach ($blocker in @("OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED", "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED", "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED", "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED")) {
    if ($remainingExecutionBlockers -notcontains $blocker) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 remainingExecutionBlockers contains $blocker"
    }
}
foreach ($pattern in @("GRID10_CAPITAL_CAP_OVERRIDE_REVIEW", "GRID10_ENV_DIFF_REVIEW", "GRID10_CREATEGRID_REVIEW")) {
    if (@(@($exactOperatorAuthorizationTexts) -match $pattern).Count -eq 0) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 exact authorization texts include $pattern"
    }
}
if (@($postEnvReadOnlyVerification).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 post-env read-only verification commands present" }
if (@($killSwitchEnvDiff).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 kill-switch env diff present" }
if (@($rollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 rollback commands present" }

$activationReviewReady = $missingRequirements.Count -eq 0
$status = if ($activationReviewReady) {
    "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING"
}
$decision = if ($activationReviewReady) {
    "PRESENT_EXACT_GRID10_ENV_DEPLOY_CREATEGRID_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
} else {
    "REFRESH_GRID10_HANDOFF_EVIDENCE_BEFORE_ACTIVATION_AUTHORIZATION"
}

$activationAuthorizationText = "I explicitly authorize GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW for $Symbol with gridCount=$GridCount, perLevelUsdt=$PerLevelUsdt, candidateCapitalUsdt=$candidateCapitalUsdt, effectiveReviewCapitalCapUsdt=$effectiveReviewCapitalCapUsdt, stopOutPct=$StopOutPct, candidateHalfWidthPct=$CandidateHalfWidthPct, replayScore=$replayScore, trendGate=$trendGate, TRADING_OKX_ENABLED=true, TRADING_GRID_ENABLED=true, grid scheduler/recovery/Earn/Telegram live action flags disabled as listed, post-env read-only verification before createGrid, immediate kill-switch rollback on abnormal evidence, and I accept this can lose money."
$nextAction = if ($activationReviewReady) {
    "Ask the operator to confirm the exact grid10 activationAuthorizationText in the same session, then separately execute the approved env/deploy runbook, run every post-env read-only verification command, and only then review the createGrid authorization text."
} else {
    "Do not request env/deploy or createGrid activation. Refresh the missing grid10 handoff evidence and rerun this bundle."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE"
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
    trendLaneReady = $trendLaneReady
    capitalOverrideReviewReady = $capitalReviewReady
    envDiffReviewReady = $envDiffReviewReady
    createGridPreflightEvidenceComplete = $createPreflightComplete
    candidatePlanComplete = $candidatePlanComplete
    existingActiveGridOrderPathActivationRisk = $existingActiveGridOrderPathActivationRisk
    sourceGrid10HandoffStatus = $handoffStatus
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    activationAuthorizationReviewReady = $activationReviewReady
    exactActivationAuthorizationText = $activationAuthorizationText
    sourceExactOperatorAuthorizationTexts = @($exactOperatorAuthorizationTexts)
    remainingExecutionBlockers = @($remainingExecutionBlockers)
    proposedEnvDiff = @($proposedEnvDiff)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    activationExecutionAllowed = $false
    envDeployRequestAllowed = $false
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
    notAuthorization = "read-only profit grid10 activation authorization bundle only; it prepares exact operator text but does not push, deploy, restart, reload nginx, change production env, approve trend override, approve capital override, enable TRADING_OKX, call createGrid, enable scheduler/recovery/Earn, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-grid10-activation-authorization-bundle] read-only packet"
Write-Host "scope=READ_ONLY; consumes saved local grid10 handoff packet only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_grid10_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "profit_grid10_activation_authorization_status=$status"
Write-Host "profit_grid10_activation_authorization_decision=$decision"
Write-Host "source_grid10_handoff_status=$handoffStatus"
Write-Host "grid10_activation_authorization_review_ready=$($activationReviewReady.ToString().ToLowerInvariant())"
Write-Host "grid10_activation_authorization_text=$activationAuthorizationText"
Write-Host "grid10_candidate_capital_usdt=$candidateCapitalUsdt"
Write-Host "grid10_effective_review_capital_cap_usdt=$effectiveReviewCapitalCapUsdt"
Write-Host "grid10_replay_score=$replayScore"
Write-Host "grid10_trend_gate=$trendGate"
Write-Host ("grid10_activation_env_diff=" + (ConvertTo-Json -Compress @($proposedEnvDiff)))
Write-Host ("grid10_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("grid10_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("grid10_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "grid10_activation_execution_allowed=false"
Write-Host "grid10_env_deploy_request_allowed=false"
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
Write-Host "[profit-grid10-activation-authorization-bundle] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    throw "Profit grid10 activation authorization bundle is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
