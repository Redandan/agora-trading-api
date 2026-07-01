param(
    [string]$Grid10ActivationBundleLogPath = "",
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
    throw "Symbol contains unsupported characters for grid10 same-session activation review arguments."
}
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30) { throw "CandidateHalfWidthPct must be between 2.5 and 30." }
if ($MaxCapitalUsdt -lt 5 -or $MaxCapitalUsdt -gt 1000) { throw "MaxCapitalUsdt must be between 5 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$defaultGrid10ActivationLog = "target/profit-review/profit-grid10-activation-authorization-bundle-latest.log"
if ([string]::IsNullOrWhiteSpace($Grid10ActivationBundleLogPath)) { $Grid10ActivationBundleLogPath = $defaultGrid10ActivationLog }

$missingRequirements = [System.Collections.Generic.List[string]]::new()
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$usingExplicitLog = $PSBoundParameters.ContainsKey("Grid10ActivationBundleLogPath")
if (-not $worktreeClean -and -not ($usingExplicitLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before grid10 same-session activation review"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$activationSource = Read-PacketLog -PathValue $Grid10ActivationBundleLogPath -Name "grid10 activation authorization bundle" -Prefix "profit_grid10_activation_authorization_bundle="
if ($activationSource.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation authorization bundle log present" }
if ($activationSource.Status -ne "MISSING" -and $null -eq $activationSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_grid10_activation_authorization_bundle valid JSON" }

$activationPacket = $activationSource.Packet
$activationStatus = Get-PropertyValue -Object $activationPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($activationStatus)) {
    $activationStatus = Get-LastPrefixedValue -Text $activationSource.Text -Prefix "profit_grid10_activation_authorization_status=" -Default "UNKNOWN"
}
$activationReviewReady = Get-PropertyBool -Object $activationPacket -Name "activationAuthorizationReviewReady"
$activationExecutionAllowed = Get-PropertyBool -Object $activationPacket -Name "activationExecutionAllowed"
$activationEnvDeployAllowed = Get-PropertyBool -Object $activationPacket -Name "envDeployRequestAllowed"
$sourceGridCount = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "gridCount")
$sourcePerLevelUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "perLevelUsdt")
$sourceStopOutPct = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "stopOutPct")
$sourceHalfWidthPct = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "candidateHalfWidthPct")
$candidateCapitalUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "candidateCapitalUsdt")
$effectiveReviewCapitalCapUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "effectiveReviewCapitalCapUsdt")
$replayScore = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "replayScore")
$trendGate = Get-PropertyValue -Object $activationPacket -Name "trendGate"
$exactActivationAuthorizationText = Get-PropertyValue -Object $activationPacket -Name "exactActivationAuthorizationText"
$proposedEnvDiff = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $activationPacket.proposedEnvDiff } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $activationPacket.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $activationPacket.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["rollbackCommands"]) { Get-StringArray $activationPacket.rollbackCommands } else { @() }
$remainingExecutionBlockers = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["remainingExecutionBlockers"]) { Get-StringArray $activationPacket.remainingExecutionBlockers } else { @() }

if ($activationStatus -ne "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 activation authorization bundle ready"
}
if (-not $activationReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activationAuthorizationReviewReady=true" }
if ($activationExecutionAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle keeps activationExecutionAllowed=false" }
if ($activationEnvDeployAllowed) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle keeps envDeployRequestAllowed=false" }
if ($sourceGridCount -ne $GridCount) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle gridCount matches requested GridCount" }
if ($sourcePerLevelUsdt -ne $PerLevelUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle perLevelUsdt matches requested PerLevelUsdt" }
if ($sourceStopOutPct -ne $StopOutPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle stopOutPct matches requested StopOutPct" }
if ($sourceHalfWidthPct -ne $CandidateHalfWidthPct) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle candidateHalfWidthPct matches requested CandidateHalfWidthPct" }
if ($null -eq $candidateCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle candidateCapitalUsdt present"
} elseif ($candidateCapitalUsdt -gt $MaxCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle candidateCapitalUsdt <= MaxCapitalUsdt"
}
if ($null -eq $effectiveReviewCapitalCapUsdt) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle effectiveReviewCapitalCapUsdt present" }
if ($null -eq $replayScore -or $replayScore -lt 70) { Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle replayScore >= 70" }
if ([string]::IsNullOrWhiteSpace($exactActivationAuthorizationText) -or $exactActivationAuthorizationText -notmatch "GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW" -or $exactActivationAuthorizationText -notmatch "can lose money") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 exact activation authorization text present"
}
foreach ($flagName in @("productionEnvChangeAllowed", "deployAllowed", "livePolicyChangeAllowed", "schedulerEnablementAllowed", "orderAllowed", "positionOrOcoMutationAllowed", "createGridAllowed", "gridMutationAllowed", "telegramSendAllowed", "dbGridFundEarnExchangeMutationAllowed")) {
    if (Get-PropertyBool -Object $activationPacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 activation bundle keeps $flagName=false"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 activation env diff includes $line"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=false", "TRADING_GRID_ENABLED=false", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($killSwitchEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 kill-switch env diff includes $line"
    }
}
foreach ($blocker in @("OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED", "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED", "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED", "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED")) {
    if ($remainingExecutionBlockers -notcontains $blocker) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 activation remainingExecutionBlockers contains $blocker"
    }
}
foreach ($pattern in @("verify_split_acceptance_ssh.ps1", "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1", "prepare_grid_open_authorization_bundle_ssh.ps1", "prepare_profit_grid10_order_path_handoff.ps1")) {
    if (@(@($postEnvReadOnlyVerification) -match $pattern).Count -eq 0) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 post-env read-only verification includes $pattern"
    }
}
if (@($rollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 rollback commands present" }

$sameSessionChecklistReady = $missingRequirements.Count -eq 0
$status = if ($sameSessionChecklistReady) {
    "READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_REQUIREMENTS_MISSING"
}
$decision = if ($sameSessionChecklistReady) {
    "PRESENT_GRID10_SAME_SESSION_OPERATOR_CHECKLIST_DO_NOT_EXECUTE"
} else {
    "REFRESH_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE_BEFORE_SAME_SESSION_REVIEW"
}

$sameSessionOperatorChecklist = @(
    "1. Confirm the exact grid10_activation_authorization_text in this same session; no paraphrase is enough.",
    "2. Confirm trendGate=$trendGate and explicitly accept any trend override plus capital cap override from $effectiveReviewCapitalCapUsdt to $candidateCapitalUsdt USDT.",
    "3. Confirm env diff exactly: $(@($proposedEnvDiff) -join '; ').",
    "4. Deploy/restart only after the exact env diff authorization is confirmed; do not createGrid before post-env verification.",
    "5. Run every post-env read-only verification command and preserve the logs.",
    "6. Review createGrid only after post-env verification is clean and inputs remain gridCount=$GridCount, perLevelUsdt=$PerLevelUsdt, stopOutPct=$StopOutPct, candidateHalfWidthPct=$CandidateHalfWidthPct.",
    "7. Apply the kill-switch env diff immediately if verification shows abnormal orders, OCO/grid mutation, scheduler/recovery/Earn, Telegram, DB/fund/exchange mutation, or input drift."
)
$preExecutionReviewCommands = @(
    ".\scripts\prepare_profit_grid10_activation_authorization_bundle.ps1 -RequireReady",
    ".\scripts\prepare_profit_grid10_same_session_activation_review_packet.ps1 -RequireReady"
)
$nextAction = if ($sameSessionChecklistReady) {
    "Use this packet as the same-session operator checklist. It still does not authorize env/deploy or createGrid by itself; execute only after the exact text is explicitly confirmed and then run post-env read-only verification before any createGrid review."
} else {
    "Do not request env/deploy or createGrid activation. Refresh the grid10 activation authorization bundle and rerun this same-session review packet."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_PACKET"
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
    sourceGrid10ActivationAuthorizationStatus = $activationStatus
    sourceGrid10ActivationReviewReady = $activationReviewReady
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    sameSessionOperatorChecklistReady = $sameSessionChecklistReady
    exactSameSessionAuthorizationText = $exactActivationAuthorizationText
    sameSessionOperatorChecklist = @($sameSessionOperatorChecklist)
    proposedEnvDiff = @($proposedEnvDiff)
    preExecutionReviewCommands = @($preExecutionReviewCommands)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    remainingExecutionBlockers = @($remainingExecutionBlockers)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    sameSessionExecutionAllowed = $false
    sameSessionEnvDeployAllowed = $false
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
    notAuthorization = "read-only profit grid10 same-session activation review packet only; it prepares an operator checklist but does not push, deploy, restart, reload nginx, change production env, approve trend override, approve capital override, enable TRADING_OKX, call createGrid, enable scheduler/recovery/Earn, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-grid10-same-session-activation-review] read-only packet"
Write-Host "scope=READ_ONLY; consumes saved local grid10 activation authorization bundle only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_grid10_same_session_activation_review_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "profit_grid10_same_session_activation_review_status=$status"
Write-Host "profit_grid10_same_session_activation_review_decision=$decision"
Write-Host "source_grid10_activation_authorization_status=$activationStatus"
Write-Host "grid10_same_session_operator_checklist_ready=$($sameSessionChecklistReady.ToString().ToLowerInvariant())"
Write-Host "grid10_same_session_exact_authorization_text=$exactActivationAuthorizationText"
Write-Host ("grid10_same_session_operator_checklist=" + (ConvertTo-Json -Compress @($sameSessionOperatorChecklist)))
Write-Host ("grid10_same_session_env_diff=" + (ConvertTo-Json -Compress @($proposedEnvDiff)))
Write-Host ("grid10_same_session_pre_execution_review_commands=" + (ConvertTo-Json -Compress @($preExecutionReviewCommands)))
Write-Host ("grid10_same_session_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("grid10_same_session_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("grid10_same_session_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "grid10_candidate_capital_usdt=$candidateCapitalUsdt"
Write-Host "grid10_effective_review_capital_cap_usdt=$effectiveReviewCapitalCapUsdt"
Write-Host "grid10_replay_score=$replayScore"
Write-Host "grid10_trend_gate=$trendGate"
Write-Host "grid10_same_session_execution_allowed=false"
Write-Host "grid10_same_session_env_deploy_allowed=false"
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
Write-Host "[profit-grid10-same-session-activation-review] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION") {
    throw "Profit grid10 same-session activation review is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
