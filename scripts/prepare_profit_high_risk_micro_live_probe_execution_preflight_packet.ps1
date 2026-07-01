param(
    [string]$MicroProbeSourceRefreshLogPath = "target/profit-review/profit-high-risk-micro-live-probe-activation-source-refresh-latest.log",
    [string]$ActivationAuthorizationBundleLogPath = "",
    [string]$Symbol = "BTCUSDT",
    [decimal]$MaxProbeNotionalUsdt = 10,
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
    throw "Symbol contains unsupported characters for high-risk micro live probe execution preflight arguments."
}
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) {
    throw "MaxProbeNotionalUsdt must be between 0 and 1000."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$missingRequirements = [System.Collections.Generic.List[string]]::new()
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$usingExplicitLogs = $PSBoundParameters.ContainsKey("MicroProbeSourceRefreshLogPath") -or $PSBoundParameters.ContainsKey("ActivationAuthorizationBundleLogPath")
if (-not $worktreeClean -and -not ($usingExplicitLogs -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before micro probe execution preflight"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$source = Read-PacketLog -PathValue $MicroProbeSourceRefreshLogPath -Name "micro probe activation source refresh" -Prefix "profit_micro_probe_activation_source_refresh_packet="
if ($source.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "micro probe activation source refresh log present" }
if ($source.Status -ne "MISSING" -and $null -eq $source.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_micro_probe_activation_source_refresh_packet valid JSON" }

$sourcePacket = $source.Packet
$sourceStatus = Get-PropertyValue -Object $sourcePacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $source.Text -Prefix "profit_micro_probe_activation_source_refresh_status=" -Default "UNKNOWN"
}
$sourceReady = Get-PropertyBool -Object $sourcePacket -Name "ready"
if (-not $sourceReady) {
    $sourceReady = (Get-LastPrefixedValue -Text $source.Text -Prefix "profit_micro_probe_activation_source_refresh_activation_ready=" -Default "false") -eq "true"
}
$sourceFailedCountText = Get-PropertyValue -Object $sourcePacket -Name "failedStepCount"
$sourceFailedCount = 0
if (-not [string]::IsNullOrWhiteSpace($sourceFailedCountText)) {
    try { $sourceFailedCount = [int]$sourceFailedCountText } catch { $sourceFailedCount = 999 }
}

if ([string]::IsNullOrWhiteSpace($ActivationAuthorizationBundleLogPath)) {
    $ActivationAuthorizationBundleLogPath = Get-PropertyValue -Object $sourcePacket -Name "activationBundleLogPath"
    if ([string]::IsNullOrWhiteSpace($ActivationAuthorizationBundleLogPath)) {
        $ActivationAuthorizationBundleLogPath = "target/profit-review/profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log"
    }
}

$activation = Read-PacketLog -PathValue $ActivationAuthorizationBundleLogPath -Name "micro probe activation authorization bundle" -Prefix "profit_high_risk_micro_live_probe_activation_authorization_bundle="
if ($activation.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "micro probe activation authorization bundle log present" }
if ($activation.Status -ne "MISSING" -and $null -eq $activation.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_high_risk_micro_live_probe_activation_authorization_bundle valid JSON" }

$activationPacket = $activation.Packet
$activationStatus = Get-PropertyValue -Object $activationPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($activationStatus)) {
    $activationStatus = Get-LastPrefixedValue -Text $activation.Text -Prefix "profit_high_risk_micro_live_probe_activation_authorization_status=" -Default "UNKNOWN"
}
$activationReviewReady = Get-PropertyBool -Object $activationPacket -Name "activationAuthorizationReviewReady"
$activationExecutionAllowed = Get-PropertyBool -Object $activationPacket -Name "activationExecutionAllowed"
$envDeployRequestAllowed = Get-PropertyBool -Object $activationPacket -Name "envDeployRequestAllowed"
$hardGateClear = Get-PropertyBool -Object $activationPacket -Name "hardGateClear"
$exactAuthorizationReviewAllowed = Get-PropertyBool -Object $activationPacket -Name "exactAuthorizationReviewAllowed"
$runtimeOrderSentEvidence = Get-PropertyValue -Object $activationPacket -Name "runtimeOrderSentEvidence"
$exactAuthorizationText = Get-PropertyValue -Object $activationPacket -Name "exactActivationAuthorizationText"
$deployCommand = Get-PropertyValue -Object $activationPacket -Name "exactDeployCommand"
if ([string]::IsNullOrWhiteSpace($deployCommand)) { $deployCommand = ".\scripts\deploy_ssh.ps1 -Branch main" }
$sourceMaxProbeNotionalUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $activationPacket -Name "maxProbeNotionalUsdt")
$proposedEnvDiff = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $activationPacket.proposedEnvDiff } else { @() }
$riskAcceptanceConditions = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["riskAcceptanceConditions"]) { Get-StringArray $activationPacket.riskAcceptanceConditions } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $activationPacket.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $activationPacket.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $activationPacket -and $null -ne $activationPacket.PSObject.Properties["rollbackCommands"]) { Get-StringArray $activationPacket.rollbackCommands } else { @() }

if ($sourceStatus -ne "READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe source refresh ready"
}
if (-not $sourceReady) { Add-MissingRequirement -List $missingRequirements -Value "micro probe source refresh activation ready flag true" }
if ($sourceFailedCount -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "micro probe source refresh failedStepCount=0" }
if ($activationStatus -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe activation authorization bundle ready"
}
if (-not $activationReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "micro probe activationAuthorizationReviewReady=true" }
if (-not $hardGateClear) { Add-MissingRequirement -List $missingRequirements -Value "micro probe hardGateClear=true" }
if (-not $exactAuthorizationReviewAllowed) { Add-MissingRequirement -List $missingRequirements -Value "micro probe exactAuthorizationReviewAllowed=true" }
if ($runtimeOrderSentEvidence -ne "0") { Add-MissingRequirement -List $missingRequirements -Value "runtime orderSentEvidence=0 before micro probe execution" }
if ($activationExecutionAllowed) { Add-MissingRequirement -List $missingRequirements -Value "micro probe activation bundle keeps activationExecutionAllowed=false" }
if ($envDeployRequestAllowed) { Add-MissingRequirement -List $missingRequirements -Value "micro probe activation bundle keeps envDeployRequestAllowed=false" }
if ($null -eq $sourceMaxProbeNotionalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe maxProbeNotionalUsdt present"
} elseif ($sourceMaxProbeNotionalUsdt -gt $MaxProbeNotionalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe maxProbeNotionalUsdt <= requested cap"
}
if ([string]::IsNullOrWhiteSpace($exactAuthorizationText) -or $exactAuthorizationText -notmatch "MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION" -or $exactAuthorizationText -notmatch "can lose money") {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe exact activation authorization text present"
}
foreach ($flagName in @("productionEnvChangeAllowed", "deployAllowed", "livePolicyChangeAllowed", "schedulerEnablementAllowed", "orderAllowed", "positionOrOcoMutationAllowed", "gridMutationAllowed", "telegramSendAllowed", "dbGridFundEarnExchangeMutationAllowed")) {
    if (Get-PropertyBool -Object $activationPacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "micro probe activation bundle keeps $flagName=false"
    }
}
foreach ($line in @("TRADING_RUNTIME_EVIDENCE_ENABLED=true", "TRADING_OKX_ENABLED=true", "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false")) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "micro probe preflight env diff includes $line"
    }
}
foreach ($line in @("TRADING_OKX_ENABLED=false", "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($killSwitchEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "micro probe preflight kill-switch env diff includes $line"
    }
}
foreach ($pattern in @("verify_split_acceptance_ssh.ps1", "smoke_live_background_automation_ssh.ps1", "smoke_runtime_evidence_rca_ssh.ps1", "audit_live_readiness_ssh.ps1", "smoke_live_readiness_bundle_ssh.ps1", "smoke_tiny_live_loss_rca_ssh.ps1", "smoke_tiny_live_post_trade_ssh.ps1", "prepare_profit_live_blocker_source_refresh.ps1", "prepare_profit_aggressive_activation_operator_packet.ps1")) {
    if (@(@($postEnvReadOnlyVerification) -match $pattern).Count -eq 0) {
        Add-MissingRequirement -List $missingRequirements -Value "micro probe post-env read-only verification includes $pattern"
    }
}
if (@($riskAcceptanceConditions).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "micro probe risk acceptance conditions present" }
if (@($rollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "micro probe rollback commands present" }

$preflightReady = $missingRequirements.Count -eq 0
$status = if ($preflightReady) {
    "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION"
} else {
    "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_REQUIREMENTS_MISSING"
}
$decision = if ($preflightReady) {
    "PRESENT_EXACT_MICRO_PROBE_AUTHORIZATION_AND_EXECUTION_ORDER_TO_OPERATOR_DO_NOT_EXECUTE"
} else {
    "REFRESH_MICRO_PROBE_SOURCE_CHAIN_BEFORE_ENV_DEPLOY_OR_ORDER_PREFLIGHT"
}

$executionOrder = @(
    "1. Operator confirms exactActivationAuthorizationText in this chat session.",
    "2. Apply only proposedEnvDiff through the approved deploy runbook after explicit confirmation.",
    "3. Deploy/restart current origin/main only after the exact env diff is confirmed.",
    "4. Run every postEnvReadOnlyVerificationCommand and preserve logs.",
    "5. Do not allow a micro probe order unless post-env verification is clean, hard gates remain clear, and runtimeOrderSentEvidence remains 0 before activation.",
    "6. If any abnormal order/OCO/grid/scheduler/Earn/Telegram/DB/fund/exchange mutation or input drift appears, apply killSwitchEnvDiff and rollbackCommands.",
    "7. Review the actual probe order path as a separate operator action; this packet never places an order."
)
$preDeployLocalReviewCommands = @(
    ".\scripts\prepare_profit_high_risk_micro_live_probe_activation_source_refresh.ps1 -ContinueOnStepFailure",
    ".\scripts\prepare_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1 -RequireReady",
    "git status --short --branch",
    "git diff --check"
)
$nextAction = if ($preflightReady) {
    "The high-risk micro live probe execution preflight is ready to present to the operator for exact same-session authorization. This packet still does not deploy, change env, enable TinyLive execution, or place an order."
} else {
    "Do not request env/deploy/order authorization. Refresh the micro probe source chain and rerun this preflight."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    sourceMaxProbeNotionalUsdt = $sourceMaxProbeNotionalUsdt
    sourceRefreshLogPath = $MicroProbeSourceRefreshLogPath
    activationAuthorizationBundleLogPath = $ActivationAuthorizationBundleLogPath
    sourceRefreshStatus = $sourceStatus
    activationAuthorizationBundleStatus = $activationStatus
    sourceRefreshReady = $sourceReady
    activationAuthorizationReviewReady = $activationReviewReady
    hardGateClear = $hardGateClear
    exactAuthorizationReviewAllowed = $exactAuthorizationReviewAllowed
    runtimeOrderSentEvidence = $runtimeOrderSentEvidence
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    preflightReady = $preflightReady
    exactActivationAuthorizationText = $exactAuthorizationText
    executionOrder = @($executionOrder)
    preDeployLocalReviewCommands = @($preDeployLocalReviewCommands)
    deployCommandAfterExplicitAuthorization = $deployCommand
    proposedEnvDiff = @($proposedEnvDiff)
    riskAcceptanceConditions = @($riskAcceptanceConditions)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    microProbeExecutionPreflightReady = $preflightReady
    microProbeEnvDeployExecutionAllowed = $false
    microProbeOrderExecutionAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only high-risk micro live probe execution preflight only; it prepares exact operator execution order but does not push, deploy, restart, reload nginx, change production env, enable TRADING_OKX, enable TinyLive execution, enable scheduler, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-high-risk-micro-live-probe-execution-preflight] read-only packet"
Write-Host "scope=READ_ONLY; consumes saved local micro-probe source-refresh and activation bundle logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_high_risk_micro_live_probe_execution_preflight_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "profit_high_risk_micro_live_probe_execution_preflight_status=$status"
Write-Host "profit_high_risk_micro_live_probe_execution_preflight_decision=$decision"
Write-Host "source_micro_probe_activation_source_refresh_status=$sourceStatus"
Write-Host "source_micro_probe_activation_authorization_status=$activationStatus"
Write-Host "micro_probe_execution_preflight_ready=$($preflightReady.ToString().ToLowerInvariant())"
Write-Host "micro_probe_execution_exact_authorization_text=$exactAuthorizationText"
Write-Host ("micro_probe_execution_order=" + (ConvertTo-Json -Compress @($executionOrder)))
Write-Host ("micro_probe_execution_pre_deploy_local_review_commands=" + (ConvertTo-Json -Compress @($preDeployLocalReviewCommands)))
Write-Host "micro_probe_execution_deploy_command_after_explicit_authorization=$deployCommand"
Write-Host ("micro_probe_execution_env_diff=" + (ConvertTo-Json -Compress @($proposedEnvDiff)))
Write-Host ("micro_probe_execution_risk_acceptance_conditions=" + (ConvertTo-Json -Compress @($riskAcceptanceConditions)))
Write-Host ("micro_probe_execution_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("micro_probe_execution_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("micro_probe_execution_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "micro_probe_max_notional_usdt=$sourceMaxProbeNotionalUsdt"
Write-Host "runtime_order_sent_evidence=$runtimeOrderSentEvidence"
Write-Host "micro_probe_hard_gate_clear=$($hardGateClear.ToString().ToLowerInvariant())"
Write-Host "micro_probe_env_deploy_execution_allowed=false"
Write-Host "micro_probe_order_execution_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-high-risk-micro-live-probe-execution-preflight] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION") {
    throw "High-risk micro live probe execution preflight is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
