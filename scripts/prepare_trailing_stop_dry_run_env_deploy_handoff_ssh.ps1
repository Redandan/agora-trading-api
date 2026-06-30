param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [string]$ReplayIntervalCode = "1m",
    [int]$Days = 30,
    [int]$Limit = 500,
    [int]$ExpectedOptInStrategyId = 574,
    [string]$SourceLog = "",
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

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for trailing dry-run env/deploy handoff arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function ConvertFrom-JsonOrNull {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return $null }
    return ($Json | ConvertFrom-Json -ErrorAction Stop)
}

function Get-PacketValue {
    param([object]$Packet, [string]$Name)
    if ($null -eq $Packet) { return "" }
    $property = $Packet.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    return [string]$property.Value
}

function Get-PacketBoolValue {
    param([object]$Packet, [string]$Name)
    $value = Get-PacketValue -Packet $Packet -Name $Name
    if ([string]::IsNullOrWhiteSpace($value)) { return $false }
    return $value.ToLowerInvariant() -eq "true"
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Read-SourceLog {
    param([string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        throw "SourceLog not found: $resolved"
    }
    return [pscustomobject]@{
        Text = Get-Content -Raw -LiteralPath $resolved
        ExitCode = 0
        Source = $resolved
    }
}

function Invoke-PostOptInReadiness {
    $scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $arguments = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        IntervalCode = $IntervalCode
        ReplayIntervalCode = $ReplayIntervalCode
        Days = $Days
        Limit = $Limit
        ExpectedOptInStrategyId = $ExpectedOptInStrategyId
    }

    $output = @()
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @arguments *>&1
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
        Source = "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    }
}

if ($Days -lt 1 -or $Days -gt 90) { throw "Days must be between 1 and 90." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }
if ($ExpectedOptInStrategyId -lt 1 -or $ExpectedOptInStrategyId -gt 1000000) { throw "ExpectedOptInStrategyId must be between 1 and 1000000." }
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode

$repoRoot = Split-Path -Parent $PSScriptRoot
$usingSourceLog = -not [string]::IsNullOrWhiteSpace($SourceLog)
if (-not $usingSourceLog) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    if ($null -eq (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH. Install OpenSSH client or Git for Windows with ssh." }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
} elseif (-not $AllowDirtyLocalWorktreeForReplay.IsPresent) {
    # SourceLog replay is often used in local tests while new tooling is dirty.
    # Live handoff still requires a clean worktree through the missing-requirement check below.
}

$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$sourceResult = if ($usingSourceLog) {
    Read-SourceLog -PathValue $SourceLog
} else {
    Invoke-PostOptInReadiness
}

$postJson = Get-LastPrefixedValue -Text $sourceResult.Text -Prefix "trailing_stop_post_opt_in_readiness_packet="
$postPacket = ConvertFrom-JsonOrNull -Json $postJson
$sourceStatus = Get-PacketValue -Packet $postPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $sourceResult.Text -Prefix "trailing_stop_post_opt_in_readiness_status="
}
$sourceDecision = Get-PacketValue -Packet $postPacket -Name "sourceActivationDecision"
$postDecision = Get-LastPrefixedValue -Text $sourceResult.Text -Prefix "trailing_stop_post_opt_in_readiness_decision="
if ([string]::IsNullOrWhiteSpace($postDecision)) {
    $postDecision = Get-PacketValue -Packet $postPacket -Name "nextAction"
}

$trailingAcceptance = Get-PacketValue -Packet $postPacket -Name "trailingAcceptance"
$trailingImprovementPct = Get-PacketValue -Packet $postPacket -Name "trailingImprovementPct"
$trailingDeltaPnl = Get-PacketValue -Packet $postPacket -Name "trailingDeltaPnl"
$currentGlobalEnabled = Get-PacketValue -Packet $postPacket -Name "currentGlobalEnabled"
$currentGlobalDryRun = Get-PacketValue -Packet $postPacket -Name "currentGlobalDryRun"
$currentOpenOcoPositions = Get-PacketValue -Packet $postPacket -Name "currentOpenOcoPositions"
$expectedStrategyOptIn = Get-PacketBoolValue -Packet $postPacket -Name "expectedStrategyOptIn"
$envDiffReviewReady = Get-PacketBoolValue -Packet $postPacket -Name "envDiffReviewReady"
$alreadyActiveDryRun = Get-PacketBoolValue -Packet $postPacket -Name "alreadyActiveDryRun"

$sourceMissingRequirements = @()
if ($null -ne $postPacket) {
    $sourceMissingProperty = $postPacket.PSObject.Properties["missingRequirements"]
    if ($null -ne $sourceMissingProperty -and $null -ne $sourceMissingProperty.Value) {
        $sourceMissingRequirements = @($sourceMissingProperty.Value | ForEach-Object { [string]$_ })
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if (-not $worktreeClean -and -not ($usingSourceLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-Unique -List $missingRequirements -Value "local worktree clean before trailing dry-run env/deploy handoff"
}
if ($behindCount -gt 0) { Add-Unique -List $missingRequirements -Value "local branch not behind origin/main" }
if ($sourceResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "post-opt-in readiness packet completed" }
if ($null -eq $postPacket) { Add-Unique -List $missingRequirements -Value "trailing_stop_post_opt_in_readiness_packet valid JSON" }
if ($sourceStatus -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -and $sourceStatus -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
    Add-Unique -List $missingRequirements -Value "post-opt-in readiness status is env-diff ready or already active dry-run"
}
if ($trailingAcceptance -ne "PASS") { Add-Unique -List $missingRequirements -Value "trailing replay acceptance=PASS" }
if ($currentGlobalDryRun -ne "true") { Add-Unique -List $missingRequirements -Value "current global dry-run remains true" }
if ($sourceStatus -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -and $currentGlobalEnabled -ne "false") {
    Add-Unique -List $missingRequirements -Value "current global trailing enabled remains false before env diff"
}
if ($sourceStatus -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY" -and $currentGlobalEnabled -ne "true") {
    Add-Unique -List $missingRequirements -Value "current global trailing enabled is true for active dry-run verification"
}
if (-not $expectedStrategyOptIn) { Add-Unique -List $missingRequirements -Value "expected strategy trailingStopEnabled=true" }
if ($sourceMissingRequirements.Count -gt 0) { Add-Unique -List $missingRequirements -Value "source post-opt-in missingRequirements empty" }
if ($envDiffReviewReady -and $alreadyActiveDryRun) { Add-Unique -List $missingRequirements -Value "source cannot be both env-diff ready and already active" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready -and $envDiffReviewReady) {
    "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION"
} elseif ($ready -and $alreadyActiveDryRun) {
    "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED"
} else {
    "NOT_READY_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF"
}
$decision = if ($status -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION") {
    "REQUEST_EXACT_TRAILING_DRY_RUN_ENV_DEPLOY_AUTHORIZATION"
} elseif ($status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED") {
    "RUN_ACTIVE_DRY_RUN_READ_ONLY_VERIFICATION"
} else {
    "REFRESH_OR_FIX_TRAILING_DRY_RUN_ENV_DEPLOY_HANDOFF_EVIDENCE"
}

$requiredEnvDiff = @(
    "TRAILING_STOP_ENABLED=true",
    "TRAILING_STOP_DRY_RUN=true"
)
$mustRemainDisabled = @(
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "POSITION_EXIT_MANAGER_ENABLED=false",
    "TRADING_OCO_POLLER_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$postDeployVerification = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -ExpectedOptInStrategyId $ExpectedOptInStrategyId -RequireReady",
    ".\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1 -Symbol $Symbol -IntervalCode $IntervalCode -ReplayIntervalCode $ReplayIntervalCode -Days $Days -Limit $Limit -RequireAcceptance",
    ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
    ".\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady",
    "server-local MCP getTrailingStopStatus confirms global.enabled=true and global.dryRun=true",
    "runtime-log smoke confirms no order/OCO modification/close-position/Telegram/grid/fund/Earn/exchange mutation while dry-run=true"
)
$rollbackPlan = @(
    "set TRAILING_STOP_ENABLED=false",
    "keep TRAILING_STOP_DRY_RUN=true",
    "deploy/restart rollback env diff",
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -ExpectedOptInStrategyId $ExpectedOptInStrategyId -RequireReady",
    "do not rollback strategy trailingStopEnabled opt-in unless a separate strategy rollback packet is approved"
)
$exactAuthorizationText = "I authorize production env diff TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRAILING_STOP_DRY_RUN=false, live OCO mutation, order placement, position close, scheduler/live policy relaxation, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import."

$packet = [ordered]@{
    packetType = "TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    expectedOptInStrategyId = $ExpectedOptInStrategyId
    localHeadCommit = $headCommit
    localOriginMainCommit = $originCommit
    localAheadCount = $aheadCount
    localBehindCount = $behindCount
    localWorktreeClean = $worktreeClean
    sourcePostOptInPacket = if ($usingSourceLog) { $SourceLog } else { "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1" }
    sourcePostOptInExitCode = $sourceResult.ExitCode
    sourcePostOptInStatus = $sourceStatus
    sourcePostOptInDecision = $postDecision
    sourceActivationDecision = $sourceDecision
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovementPct
    trailingDeltaPnl = $trailingDeltaPnl
    currentGlobalEnabled = $currentGlobalEnabled
    currentGlobalDryRun = $currentGlobalDryRun
    currentOpenOcoPositions = $currentOpenOcoPositions
    expectedStrategyOptIn = $expectedStrategyOptIn
    envDiffReviewReady = $envDiffReviewReady
    alreadyActiveDryRun = $alreadyActiveDryRun
    requiredProductionEnvDiff = @($requiredEnvDiff)
    envFlagsThatMustRemainDisabled = @($mustRemainDisabled)
    exactOperatorAuthorizationText = $exactAuthorizationText
    exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
    requiredPostDeployReadOnlyVerification = @($postDeployVerification)
    rollbackPlan = @($rollbackPlan)
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes the exact env diff",
        "operator explicitly authorizes deploy/restart current origin/main",
        "operator accepts dry-run-only trailing scheduler boundary",
        "operator accepts rollback plan",
        "post-env read-only verification only"
    )
    forbiddenActions = @(
        "TRAILING_STOP_DRY_RUN=false",
        "live OCO mutation",
        "place orders",
        "close positions",
        "enable TinyLive or ScoreBuy execution",
        "enable POSITION_EXIT_MANAGER",
        "enable TRADING_OCO_POLLER",
        "send Telegram",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    schedulerEnablementAllowed = $false
    livePolicyChangeAllowed = $false
    positionOrOcoMutationAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    sourceMissingRequirements = @($sourceMissingRequirements)
    missingRequirements = @($missingRequirements)
    nextAction = if ($status -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION") {
        "Obtain the exact operator authorization text, then apply only the dry-run env diff, deploy/restart, and run the listed read-only verification commands."
    } elseif ($status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED") {
        "Skip env deploy handoff and run active dry-run read-only verification/observation."
    } else {
        "Refresh post-opt-in readiness and fix missing handoff evidence before requesting env/deploy authorization."
    }
    notAuthorization = "read-only trailing-stop dry-run env/deploy handoff packet only; does not change production env, deploy, restart, enable live trading, set dry-run false, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[trailing-stop-dry-run-env-deploy-handoff] read-only packet"
Write-Host "scope=READ_ONLY; consumes post-opt-in readiness packet and local git metadata only; no production env, deploy, restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("trailing_stop_dry_run_env_deploy_handoff_packet=" + ($packet | ConvertTo-Json -Compress -Depth 12))
Write-Host "trailing_stop_dry_run_env_deploy_handoff_status=$status"
Write-Host "trailing_stop_dry_run_env_deploy_handoff_decision=$decision"
Write-Host "source_post_opt_in_status=$sourceStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "trailing_stop_current_global_enabled=$currentGlobalEnabled"
Write-Host "trailing_stop_current_global_dry_run=$currentGlobalDryRun"
Write-Host "trailing_stop_current_open_oco_positions=$currentOpenOcoPositions"
Write-Host "trailing_stop_expected_strategy_opt_in=$($expectedStrategyOptIn.ToString().ToLowerInvariant())"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host ("trailing_stop_required_env_diff=" + (ConvertTo-Json -Compress @($requiredEnvDiff)))
Write-Host ("trailing_stop_env_flags_must_remain_disabled=" + (ConvertTo-Json -Compress @($mustRemainDisabled)))
Write-Host ("trailing_stop_post_deploy_read_only_verification=" + (ConvertTo-Json -Compress @($postDeployVerification)))
Write-Host ("trailing_stop_rollback_plan=" + (ConvertTo-Json -Compress @($rollbackPlan)))
Write-Host "trailing_stop_exact_authorization_text=$exactAuthorizationText"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_dry_run_env_deploy_handoff_missing_requirements=" + (@($missingRequirements) -join "; "))
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION" -and $status -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED") {
    throw "Trailing-stop dry-run env/deploy handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
