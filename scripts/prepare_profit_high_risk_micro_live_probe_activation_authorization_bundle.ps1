param(
    [string]$MicroProbeHandoffLogPath = "",
    [string]$PreflightReviewLogPath = "",
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
    throw "Symbol contains unsupported characters for high-risk micro live probe activation bundle arguments."
}
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) {
    throw "MaxProbeNotionalUsdt must be between 0 and 1000."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$defaultMicroLog = "target/profit-review/profit-high-risk-micro-live-probe-handoff-latest.log"
$defaultPreflightLog = "target/profit-review/profit-high-risk-micro-live-probe-preflight-review-latest.log"
if ([string]::IsNullOrWhiteSpace($MicroProbeHandoffLogPath)) { $MicroProbeHandoffLogPath = $defaultMicroLog }
if ([string]::IsNullOrWhiteSpace($PreflightReviewLogPath)) { $PreflightReviewLogPath = $defaultPreflightLog }

$missingRequirements = [System.Collections.Generic.List[string]]::new()
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$usingExplicitLogs = $PSBoundParameters.ContainsKey("MicroProbeHandoffLogPath") -or $PSBoundParameters.ContainsKey("PreflightReviewLogPath")
if (-not $worktreeClean -and -not ($usingExplicitLogs -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before activation authorization review"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$microSource = Read-PacketLog -PathValue $MicroProbeHandoffLogPath -Name "micro probe handoff" -Prefix "profit_high_risk_micro_live_probe_handoff_packet="
$preflightSource = Read-PacketLog -PathValue $PreflightReviewLogPath -Name "micro probe preflight" -Prefix "profit_high_risk_micro_live_probe_preflight_review_packet="

if ($microSource.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "micro probe handoff log present" }
if ($preflightSource.Status -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "micro probe preflight review log present" }
if ($microSource.Status -ne "MISSING" -and $null -eq $microSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_high_risk_micro_live_probe_handoff_packet valid JSON" }
if ($preflightSource.Status -ne "MISSING" -and $null -eq $preflightSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_high_risk_micro_live_probe_preflight_review_packet valid JSON" }

$microPacket = $microSource.Packet
$preflightPacket = $preflightSource.Packet

$microStatus = Get-PropertyValue -Object $microPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($microStatus)) {
    $microStatus = Get-LastPrefixedValue -Text $microSource.Text -Prefix "profit_high_risk_micro_live_probe_handoff_status=" -Default "UNKNOWN"
}
$preflightStatus = Get-PropertyValue -Object $preflightPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($preflightStatus)) {
    $preflightStatus = Get-LastPrefixedValue -Text $preflightSource.Text -Prefix "profit_high_risk_micro_live_probe_preflight_status=" -Default "UNKNOWN"
}

$hardGateClear = (Get-PropertyValue -Object $preflightPacket -Name "hardGateClear") -eq "true"
$preflightAuthorizationReviewAllowed = (Get-PropertyValue -Object $preflightPacket -Name "exactAuthorizationReviewAllowed") -eq "true"
$runtimeOrderSentEvidence = Get-PropertyValue -Object $preflightPacket -Name "runtimeOrderSentEvidence"
if ([string]::IsNullOrWhiteSpace($runtimeOrderSentEvidence)) {
    $runtimeOrderSentEvidence = Get-LastPrefixedValue -Text $preflightSource.Text -Prefix "runtime_order_sent_evidence=" -Default "UNKNOWN"
}

$exactOperatorAuthorizationText = Get-PropertyValue -Object $microPacket -Name "exactOperatorAuthorizationText"
$exactDeployCommand = Get-PropertyValue -Object $microPacket -Name "exactDeployCommand"
if ([string]::IsNullOrWhiteSpace($exactDeployCommand)) { $exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main" }

$optionMaxOrders = Get-PropertyValue -Object $microPacket -Name "optionMaxOrders"
$optionMaxNotional = Get-PropertyValue -Object $microPacket -Name "optionMaxNotionalUsdt"
$proposedEnvDiff = if ($null -ne $microPacket -and $null -ne $microPacket.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $microPacket.proposedEnvDiff } else { @() }
$riskAcceptanceConditions = if ($null -ne $microPacket -and $null -ne $microPacket.PSObject.Properties["riskAcceptanceConditions"]) { Get-StringArray $microPacket.riskAcceptanceConditions } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $microPacket -and $null -ne $microPacket.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $microPacket.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $microPacket -and $null -ne $microPacket.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $microPacket.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $microPacket -and $null -ne $microPacket.PSObject.Properties["rollbackCommands"]) { Get-StringArray $microPacket.rollbackCommands } else { @() }

$requiredEnvDiff = @(
    "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
    "TRADING_OKX_ENABLED=true",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false"
)
$requiredPostEnvCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
    ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
    ".\scripts\smoke_tiny_live_loss_rca_ssh.ps1",
    ".\scripts\smoke_tiny_live_post_trade_ssh.ps1 -Symbol $Symbol -StrategyId 574 -Side LONG",
    ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
)
$requiredKillSwitchEnvDiff = @(
    "TRADING_OKX_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)

if ($microStatus -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe handoff ready"
}
if ($preflightStatus -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe preflight exact authorization review ready"
}
if (-not $hardGateClear) { Add-MissingRequirement -List $missingRequirements -Value "micro probe hard gate clear" }
if (-not $preflightAuthorizationReviewAllowed) { Add-MissingRequirement -List $missingRequirements -Value "micro probe exact authorization review allowed" }
if ($runtimeOrderSentEvidence -ne "0") { Add-MissingRequirement -List $missingRequirements -Value "runtime orderSentEvidence=0 before activation" }
if ([string]::IsNullOrWhiteSpace($exactOperatorAuthorizationText) -or $exactOperatorAuthorizationText -notmatch "HIGH_RISK_MICRO_LIVE_PROBE" -or $exactOperatorAuthorizationText -notmatch "maxOrders=1" -or $exactOperatorAuthorizationText -notmatch "can lose money") {
    Add-MissingRequirement -List $missingRequirements -Value "exact high-risk micro probe operator authorization text present"
}
if ($optionMaxOrders -ne "1") { Add-MissingRequirement -List $missingRequirements -Value "micro probe maxOrders=1" }
try {
    if ([decimal]$optionMaxNotional -gt $MaxProbeNotionalUsdt) {
        Add-MissingRequirement -List $missingRequirements -Value "micro probe maxNotionalUsdt <= requested cap"
    }
} catch {
    Add-MissingRequirement -List $missingRequirements -Value "micro probe maxNotionalUsdt numeric"
}

foreach ($line in $requiredEnvDiff) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "activation env diff includes $line"
    }
}
foreach ($line in $requiredPostEnvCommands) {
    if ($postEnvReadOnlyVerification -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "post-env read-only verification includes $line"
    }
}
foreach ($line in $requiredKillSwitchEnvDiff) {
    if ($killSwitchEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "kill-switch env diff includes $line"
    }
}
if (@($riskAcceptanceConditions).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "risk acceptance conditions present" }
if (@($rollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "rollback commands present" }

$activationReviewReady = $missingRequirements.Count -eq 0
$status = if ($activationReviewReady) {
    "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING"
}
$decision = if ($activationReviewReady) {
    "PRESENT_EXACT_MICRO_PROBE_ENV_DEPLOY_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
} else {
    "REFRESH_MICRO_PROBE_HANDOFF_AND_PREFLIGHT_EVIDENCE_BEFORE_ACTIVATION_AUTHORIZATION"
}

$activationAuthorizationText = "I explicitly authorize MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION for $Symbol with maxNotionalUsdt=$MaxProbeNotionalUsdt, maxOrders=1, env diff TRADING_RUNTIME_EVIDENCE_ENABLED=true; TRADING_OKX_ENABLED=true; TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true; ScoreBuy/Grid/Earn/Telegram mutation flags disabled as listed, immediate kill-switch rollback on abnormal post-env evidence, and I accept that this can lose money."
$nextAction = if ($activationReviewReady) {
    "Ask the operator to confirm the exact activationAuthorizationText in the same session, then separately execute the approved env/deploy runbook and run every post-env read-only verification command before allowing any probe order path."
} else {
    "Do not request env/deploy or order activation. Refresh the missing handoff/preflight evidence and rerun this bundle."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    sourceMicroHandoffStatus = $microStatus
    sourcePreflightStatus = $preflightStatus
    hardGateClear = $hardGateClear
    exactAuthorizationReviewAllowed = $preflightAuthorizationReviewAllowed
    runtimeOrderSentEvidence = $runtimeOrderSentEvidence
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    activationAuthorizationReviewReady = $activationReviewReady
    exactActivationAuthorizationText = $activationAuthorizationText
    sourceOperatorAuthorizationText = $exactOperatorAuthorizationText
    exactDeployCommand = $exactDeployCommand
    proposedEnvDiff = @($proposedEnvDiff)
    riskAcceptanceConditions = @($riskAcceptanceConditions)
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
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only high-risk micro live probe activation authorization bundle only; it prepares exact operator text but does not push, deploy, restart, reload nginx, change production env, enable TRADING_OKX, enable TinyLive execution, enable scheduler, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-high-risk-micro-live-probe-activation-authorization-bundle] read-only packet"
Write-Host "scope=READ_ONLY; consumes saved local packet logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_high_risk_micro_live_probe_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_high_risk_micro_live_probe_activation_authorization_status=$status"
Write-Host "profit_high_risk_micro_live_probe_activation_authorization_decision=$decision"
Write-Host "source_micro_probe_handoff_status=$microStatus"
Write-Host "source_micro_probe_preflight_status=$preflightStatus"
Write-Host "micro_probe_hard_gate_clear=$($hardGateClear.ToString().ToLowerInvariant())"
Write-Host "micro_probe_exact_authorization_review_allowed=$($preflightAuthorizationReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "runtime_order_sent_evidence=$runtimeOrderSentEvidence"
Write-Host "micro_probe_activation_authorization_review_ready=$($activationReviewReady.ToString().ToLowerInvariant())"
Write-Host "micro_probe_activation_authorization_text=$activationAuthorizationText"
Write-Host ("micro_probe_activation_env_diff=" + (ConvertTo-Json -Compress @($proposedEnvDiff)))
Write-Host ("micro_probe_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("micro_probe_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("micro_probe_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "micro_probe_activation_execution_allowed=false"
Write-Host "micro_probe_env_deploy_request_allowed=false"
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
Write-Host "[profit-high-risk-micro-live-probe-activation-authorization-bundle] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    throw "High-risk micro live probe activation authorization bundle is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
