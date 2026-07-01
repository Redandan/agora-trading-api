param(
    [string]$AggressivePacketLogPath = "",
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
    if ([string]::IsNullOrWhiteSpace($value)) { return $false }
    return $value.Trim().ToLowerInvariant() -eq "true"
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

function Get-OptionById {
    param($Options, [string]$OptionId)
    foreach ($option in @($Options)) {
        if ((Get-PropertyValue -Object $option -Name "optionId") -eq $OptionId) {
            return $option
        }
    }
    return $null
}

function Invoke-AggressivePacket {
    $scriptPath = Join-Path $PSScriptRoot "prepare_profit_aggressive_activation_operator_packet.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing aggressive activation packet script: $scriptPath"
    }

    $output = @()
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath -RequireReady *>&1
        $exitCode = if ($?) { 0 } else { 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 8192)
        ExitCode = $exitCode
        Source = "prepare_profit_aggressive_activation_operator_packet.ps1"
    }
}

function Read-AggressivePacketLog {
    param([string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        throw "AggressivePacketLogPath not found: $resolved"
    }
    return [pscustomobject]@{
        Text = Get-Content -Raw -LiteralPath $resolved
        ExitCode = 0
        Source = $resolved
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$selectedOptionId = "EVIDENCE_ONLY_ACCELERATOR"
$missingRequirements = [System.Collections.Generic.List[string]]::new()

$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)
$usingSourceLog = -not [string]::IsNullOrWhiteSpace($AggressivePacketLogPath)

if (-not $worktreeClean -and -not ($usingSourceLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before evidence-only accelerator env/deploy handoff"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}

$source = if ($usingSourceLog) {
    Read-AggressivePacketLog -PathValue $AggressivePacketLogPath
} else {
    Invoke-AggressivePacket
}

if ($source.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "profit aggressive activation packet completed"
}
$packetJson = Get-LastPrefixedValue -Text $source.Text -Prefix "profit_aggressive_activation_packet="
$aggressivePacket = Convert-JsonObjectOrNull -Value $packetJson
if ($null -eq $aggressivePacket) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_aggressive_activation_packet valid JSON"
}

$sourceStatus = Get-PropertyValue -Object $aggressivePacket -Name "status"
$sourceDecision = Get-PropertyValue -Object $aggressivePacket -Name "decision"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $source.Text -Prefix "profit_aggressive_activation_status=" -Default "UNKNOWN"
}
if ($sourceStatus -ne "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive activation packet ready"
}
if (Get-PropertyBool -Object $aggressivePacket -Name "orderAllowed") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive packet keeps orderAllowed=false"
}
if (Get-PropertyBool -Object $aggressivePacket -Name "deployOrEnvChangeAllowed") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive packet keeps deployOrEnvChangeAllowed=false"
}
if (Get-PropertyBool -Object $aggressivePacket -Name "livePolicyChangeAllowed") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive packet keeps livePolicyChangeAllowed=false"
}

$aggressiveOptions = if ($null -ne $aggressivePacket -and $null -ne $aggressivePacket.PSObject.Properties["aggressiveOptions"]) {
    $aggressivePacket.aggressiveOptions
} else {
    @()
}
$selectedOption = Get-OptionById -Options $aggressiveOptions -OptionId $selectedOptionId
if ($null -eq $selectedOption) {
    Add-MissingRequirement -List $missingRequirements -Value "EVIDENCE_ONLY_ACCELERATOR option present"
}
$optionStatus = Get-PropertyValue -Object $selectedOption -Name "status"
$optionRecommended = Get-PropertyBool -Object $selectedOption -Name "recommendedNow"
if ($optionStatus -ne "RECOMMENDED_AGGRESSIVE_NON_ORDER_STEP") {
    Add-MissingRequirement -List $missingRequirements -Value "EVIDENCE_ONLY_ACCELERATOR remains recommended non-order step"
}
if (-not $optionRecommended) {
    Add-MissingRequirement -List $missingRequirements -Value "EVIDENCE_ONLY_ACCELERATOR recommendedNow=true"
}

$proposedEnvDiff = if ($null -ne $selectedOption -and $null -ne $selectedOption.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $selectedOption.proposedEnvDiff } else { @() }
$riskAcceptanceConditions = if ($null -ne $selectedOption -and $null -ne $selectedOption.PSObject.Properties["riskAcceptanceConditions"]) { Get-StringArray $selectedOption.riskAcceptanceConditions } else { @() }
$postEnvReadOnlyVerification = if ($null -ne $selectedOption -and $null -ne $selectedOption.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $selectedOption.postEnvReadOnlyVerificationCommands } else { @() }
$killSwitchEnvDiff = if ($null -ne $selectedOption -and $null -ne $selectedOption.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $selectedOption.killSwitchEnvDiff } else { @() }
$rollbackCommands = if ($null -ne $selectedOption -and $null -ne $selectedOption.PSObject.Properties["rollbackCommands"]) { Get-StringArray $selectedOption.rollbackCommands } else { @() }

$requiredTrueEnvDiff = @(
    "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
    "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
)
$requiredFalseEnvDiff = @(
    "TRADING_OKX_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
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
foreach ($line in $requiredTrueEnvDiff) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "proposed env diff contains $line"
    }
}
foreach ($line in $requiredFalseEnvDiff) {
    if ($proposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "proposed env diff keeps $line"
    }
}
foreach ($line in @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
        ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
        ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
    )) {
    if ($postEnvReadOnlyVerification -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "post-env read-only verification includes $line"
    }
}
foreach ($line in @(
        "TRADING_RUNTIME_EVIDENCE_ENABLED=false",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
        "TRADING_OKX_ENABLED=false",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false"
    )) {
    if ($killSwitchEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "kill-switch env diff includes $line"
    }
}
if (@($riskAcceptanceConditions).Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "risk acceptance conditions present"
}
if (@($rollbackCommands).Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "rollback commands present"
}

$exactAuthorizationText = "I authorize evidence-only production env diff TRADING_RUNTIME_EVIDENCE_ENABLED=true and TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRADING_OKX_ENABLED=true, TinyLive/ScoreBuy execution, OCO/grid/fund/Earn actions, Telegram send, guardian live actions, scheduler mutation, external backfill/import, DB migration, policy relaxation, or order placement."
$exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
$exactPushCommand = "git push origin main"
$requiredAuthorization = if ($aheadCount -gt 0) {
    @($exactPushCommand, $exactDeployCommand, "post-env read-only verification only")
} else {
    @($exactDeployCommand, "post-env read-only verification only")
}

$status = if ($missingRequirements.Count -eq 0) {
    "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION"
} else {
    "BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_HANDOFF_REQUIREMENTS_MISSING"
}
$decision = if ($status -eq "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION") {
    "REQUEST_EXACT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_AUTHORIZATION"
} else {
    "REFRESH_AGGRESSIVE_ACTIVATION_PACKET_BEFORE_ENV_DEPLOY_HANDOFF"
}
$nextAction = if ($status -eq "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION") {
    "Request exact operator authorization text, then deploy/restart only the evidence-only env diff and run post-env read-only verification."
} else {
    "Resolve missing handoff requirements before requesting evidence-only env/deploy authorization."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    selectedOptionId = $selectedOptionId
    sourceAggressivePacketStatus = $sourceStatus
    sourceAggressivePacketDecision = $sourceDecision
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    requiredAuthorization = @($requiredAuthorization)
    exactOperatorAuthorizationText = $exactAuthorizationText
    exactPushCommand = $exactPushCommand
    exactDeployCommand = $exactDeployCommand
    proposedEnvDiff = @($proposedEnvDiff)
    requiredTrueEnvDiff = @($requiredTrueEnvDiff)
    envFlagsMustRemainDisabled = @($requiredFalseEnvDiff)
    riskAcceptanceConditions = @($riskAcceptanceConditions)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only profit evidence-only accelerator env/deploy handoff packet only; does not push, deploy, restart, reload nginx, change production env, enable TRADING_OKX, enable TinyLive/ScoreBuy execution, enable OCO/grid/fund/Earn actions, enable scheduler, send Telegram, place orders, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-evidence-only-accelerator-env-deploy-handoff] read-only packet"
Write-Host "scope=READ_ONLY; reads local git metadata and the aggressive activation packet only; no push, deploy, restart, nginx reload, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("profit_evidence_only_accelerator_env_deploy_handoff_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_evidence_only_accelerator_env_deploy_handoff_status=$status"
Write-Host "profit_evidence_only_accelerator_env_deploy_handoff_decision=$decision"
Write-Host "profit_evidence_only_accelerator_selected_option=$selectedOptionId"
Write-Host "source_aggressive_activation_status=$sourceStatus"
Write-Host "source_aggressive_activation_decision=$sourceDecision"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host ("profit_evidence_only_required_env_diff=" + (ConvertTo-Json -Compress @($requiredTrueEnvDiff)))
Write-Host ("profit_evidence_only_env_flags_must_remain_disabled=" + (ConvertTo-Json -Compress @($requiredFalseEnvDiff)))
Write-Host ("profit_evidence_only_risk_acceptance_conditions=" + (ConvertTo-Json -Compress @($riskAcceptanceConditions)))
Write-Host ("profit_evidence_only_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerification)))
Write-Host ("profit_evidence_only_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("profit_evidence_only_rollback_commands=" + (ConvertTo-Json -Compress @($rollbackCommands)))
Write-Host "profit_evidence_only_exact_authorization_text=$exactAuthorizationText"
Write-Host "profit_evidence_only_exact_deploy_command=$exactDeployCommand"
Write-Host ("profit_evidence_only_handoff_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "profit_evidence_only_handoff_next_action=$nextAction"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION") {
    throw "Profit evidence-only accelerator env/deploy handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
