param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$StrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1d",
    [int]$Days = 90,
    [string]$Source = "okx",
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
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for local TradingView OCO lifecycle handoff arguments."
    }
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success -or $match.Groups.Count -lt 2) { return $Default }
    return $match.Groups[1].Value.Trim()
}

function ConvertFrom-JsonArrayOrEmpty {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return @() }
    try {
        return @($Json | ConvertFrom-Json -ErrorAction Stop | ForEach-Object { [string]$_ })
    } catch {
        return @()
    }
}

function ConvertTo-BoolValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    return $Value.Trim().ToLowerInvariant() -in @("1", "true", "yes", "y", "on")
}

function ConvertTo-LongOrZero {
    param([string]$Value)
    $parsed = 0L
    if ([long]::TryParse([string]$Value, [ref]$parsed)) { return $parsed }
    return 0L
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

function Invoke-LocalTradingViewCandidateSmoke {
    $scriptPath = Join-Path $PSScriptRoot "smoke_local_tradingview_candidate_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $arguments = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        StrategyId = $StrategyId
        Symbol = $Symbol
        IntervalCode = $IntervalCode
        Days = $Days
        Source = $Source
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
        Source = "smoke_local_tradingview_candidate_ssh.ps1"
    }
}

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) { throw "StrategyId must be between 1 and 999999999." }
if ($Days -lt 7 -or $Days -gt 730) { throw "Days must be between 7 and 730." }
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16
Assert-McpSmokeTokenSafe -Name "Source" -Value $Source -MaxLength 32

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
    Invoke-LocalTradingViewCandidateSmoke
}

$sourceText = [string]$sourceResult.Text
$currentCandidateStatus = Get-RegexValue -Text $sourceText -Pattern "^\s*currentCandidateStatus=([^\r\n]+)" -Default "UNKNOWN"
$dataEnd = Get-RegexValue -Text $sourceText -Pattern "^\s*dataEnd=([^\r\n]+)" -Default "UNKNOWN"
$dataClose = Get-RegexValue -Text $sourceText -Pattern "^\s*dataClose=([^\r\n]+)" -Default "UNKNOWN"
$lastOrderAt = Get-RegexValue -Text $sourceText -Pattern "^\s*lastOrderAt=([^\r\n]+)" -Default "UNKNOWN"
$orderBars = Get-RegexValue -Text $sourceText -Pattern "^\s*orderBars=([0-9]+)" -Default "0"
$orderIntents = Get-RegexValue -Text $sourceText -Pattern "^\s*orderIntents=([0-9]+)" -Default "0"
$coverage = Get-RegexValue -Text $sourceText -Pattern "^\s*coverage=([^\r\n]+)" -Default "UNKNOWN"
$trailingGapHours = Get-RegexValue -Text $sourceText -Pattern "^\s*trailingGapHours=([^\r\n]+)" -Default "UNKNOWN"
$coverageWarning = Get-RegexValue -Text $sourceText -Pattern "^\s*coverageWarning=([^\r\n]+)" -Default "UNKNOWN"
$primary = (Get-RegexValue -Text $sourceText -Pattern "^\s*primary=([^\r\n]+)" -Default "UNKNOWN").ToUpperInvariant()
$localEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localEnabled=([^\r\n]+)" -Default "false")
$executionMode = (Get-RegexValue -Text $sourceText -Pattern "^\s*executionMode=([^\r\n]+)" -Default "UNKNOWN").ToUpperInvariant()
$effectiveExecutionEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*effectiveExecutionEnabled=([^\r\n]+)" -Default "false")
$effectiveLiveOrderEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*effectiveLiveOrderEnabled=([^\r\n]+)" -Default "false")
$localEvaluatorActive = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewEvaluatorActive=([^\r\n]+)" -Default "false")
$liveMicroArmed = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewLiveMicroArmed=([^\r\n]+)" -Default "false")
$executionPathArmed = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewExecutionPathArmed=([^\r\n]+)" -Default "false")
$tradingOcoPollerEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*tradingOcoPollerEnabled=([^\r\n]+)" -Default "false")
$positionExitManagerEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*positionExitManagerEnabled=([^\r\n]+)" -Default "false")
$ocoLifecycleTracked = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewOcoLifecycleTracked=([^\r\n]+)" -Default "false")
$ocoLifecycleStatus = Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewOcoLifecycleStatus=([^\r\n]+)" -Default "UNKNOWN"
$sourceReadiness = Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewReadiness=([^\r\n]+)" -Default "UNKNOWN"
$sourceBlockersJson = Get-RegexValue -Text $sourceText -Pattern "local_tradingview_blockers=(\[[^\r\n]*\])" -Default "[]"
$sourceBlockers = ConvertFrom-JsonArrayOrEmpty -Json $sourceBlockersJson
$orderIntentCount = ConvertTo-LongOrZero -Value $orderIntents

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if (-not $worktreeClean -and -not ($usingSourceLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-Unique -List $missingRequirements -Value "local worktree clean before local TradingView OCO lifecycle env handoff"
}
if ($behindCount -gt 0) { Add-Unique -List $missingRequirements -Value "local branch not behind origin/main" }
if ($sourceResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "local TradingView candidate smoke completed" }
if ($primary -ne "LOCAL_TRADINGVIEW") { Add-Unique -List $missingRequirements -Value "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW" }
if (-not $localEnabled) { Add-Unique -List $missingRequirements -Value "TRADINGVIEW_LOCAL_ENABLED=true" }
if (-not $localEvaluatorActive) { Add-Unique -List $missingRequirements -Value "local TradingView evaluator active" }
if ($executionMode -ne "LIVE_MICRO") { Add-Unique -List $missingRequirements -Value "TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO" }
if (-not $effectiveExecutionEnabled) { Add-Unique -List $missingRequirements -Value "effective LOCAL_TRADINGVIEW execution enabled" }
if (-not $effectiveLiveOrderEnabled) { Add-Unique -List $missingRequirements -Value "effective LIVE_MICRO live order path enabled" }
if (-not $liveMicroArmed) { Add-Unique -List $missingRequirements -Value "localTradingViewLiveMicroArmed=true" }
if (-not $executionPathArmed) { Add-Unique -List $missingRequirements -Value "localTradingViewExecutionPathArmed=true" }
if ($coverage -notin @("OK", "WARN")) { Add-Unique -List $missingRequirements -Value "local TradingView data coverage OK or WARN" }
if ($orderIntentCount -le 0) { Add-Unique -List $missingRequirements -Value "local TradingView preview has historical order intents" }
if ($positionExitManagerEnabled) { Add-Unique -List $missingRequirements -Value "POSITION_EXIT_MANAGER_ENABLED=false before OCO lifecycle handoff" }
if ($tradingOcoPollerEnabled -and -not $ocoLifecycleTracked) { Add-Unique -List $missingRequirements -Value "TRADING_OCO_POLLER_ENABLED=true produces tracked OCO lifecycle evidence" }

$alreadyTracked = $tradingOcoPollerEnabled -and $ocoLifecycleTracked -and $ocoLifecycleStatus -eq "TRACKED_BY_OCO_POLLER"
$ready = $missingRequirements.Count -eq 0
$status = if ($ready -and $alreadyTracked) {
    "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY"
} elseif ($ready) {
    "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION"
} else {
    "NOT_READY_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF"
}
$decision = if ($status -eq "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION") {
    "REQUEST_EXACT_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_AUTHORIZATION"
} elseif ($status -eq "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY") {
    "RUN_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_POST_ENV_VERIFICATION"
} else {
    "REFRESH_OR_FIX_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_EVIDENCE"
}

$requiredEnvDiff = @(
    "TRADING_OCO_POLLER_ENABLED=true",
    "POSITION_EXIT_MANAGER_ENABLED=false"
)
$mustRemainDisabled = @(
    "POSITION_EXIT_MANAGER_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "TRADING_FUNDING_ARB_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false"
)
$notChangedByThisPacket = @(
    "TRADING_SIGNAL_SOURCE_PRIMARY",
    "TRADINGVIEW_LOCAL_ENABLED",
    "TRADINGVIEW_LOCAL_EXECUTION_MODE",
    "TRADING_OKX_ENABLED",
    "TRADING_GRID_ENABLED",
    "TRAILING_STOP_ENABLED",
    "TRAILING_STOP_DRY_RUN",
    "TradingView webhook flags",
    "ScoreBuy execution scheduler flags",
    "grid/fund/Earn/trailing policy",
    "entry sizing and risk caps"
)
$postEnvVerification = @(
    ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked",
    ".\scripts\smoke_strategy485_position_risk_ssh.ps1",
    ".\scripts\audit_live_readiness_ssh.ps1",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
    "If getOcoHealth reports SYNC_ERROR, run .\scripts\prepare_oco_sync_reconciliation_packet_ssh.ps1 before any separate reconciliation write request",
    "runtime-log smoke confirms no unexpected order/grid/fund/Earn/position-exit mutation beyond reviewed OCO lifecycle tracking"
)
$rollbackPlan = @(
    "set TRADING_OCO_POLLER_ENABLED=false",
    "keep POSITION_EXIT_MANAGER_ENABLED=false",
    "deploy/restart rollback env diff after separate rollback authorization",
    ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed",
    ".\scripts\audit_live_readiness_ssh.ps1",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1"
)
$exactAuthorizationText = "I authorize production env diff for LOCAL_TRADINGVIEW LIVE_MICRO OCO lifecycle tracking only: set TRADING_OCO_POLLER_ENABLED=true and keep POSITION_EXIT_MANAGER_ENABLED=false, deploy/restart current origin/main, and run the listed post-env read-only verification. I understand this lets the trading service own OCO close detection, auto retry, reconciliation writes, OKX private WS OCO handling, and related Telegram alerts for OCO positions. I do not authorize unrelated env changes, new non-LOCAL_TRADINGVIEW entry paths, grid/fund/Earn mutation, position-exit manager enablement, manual position reconciliation writes, external backfill/import, or live policy relaxation."

$packet = [ordered]@{
    packetType = "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    strategyId = $StrategyId
    symbol = $Symbol
    intervalCode = $IntervalCode
    days = $Days
    source = $Source
    localHeadCommit = $headCommit
    localOriginMainCommit = $originCommit
    localAheadCount = $aheadCount
    localBehindCount = $behindCount
    localWorktreeClean = $worktreeClean
    sourceCandidateSmoke = if ($usingSourceLog) { $SourceLog } else { "smoke_local_tradingview_candidate_ssh.ps1" }
    sourceCandidateSmokeExitCode = $sourceResult.ExitCode
    sourceReadiness = $sourceReadiness
    sourceBlockers = @($sourceBlockers)
    currentCandidateStatus = $currentCandidateStatus
    currentCandidateRequiredForEnvHandoff = $false
    dataEnd = $dataEnd
    dataClose = $dataClose
    lastOrderAt = $lastOrderAt
    orderBars = $orderBars
    orderIntents = $orderIntents
    coverage = $coverage
    trailingGapHours = $trailingGapHours
    coverageWarning = $coverageWarning
    primary = $primary
    localEnabled = $localEnabled
    executionMode = $executionMode
    effectiveExecutionEnabled = $effectiveExecutionEnabled
    effectiveLiveOrderEnabled = $effectiveLiveOrderEnabled
    localTradingViewEvaluatorActive = $localEvaluatorActive
    localTradingViewLiveMicroArmed = $liveMicroArmed
    localTradingViewExecutionPathArmed = $executionPathArmed
    tradingOcoPollerEnabled = $tradingOcoPollerEnabled
    positionExitManagerEnabled = $positionExitManagerEnabled
    localTradingViewOcoLifecycleTracked = $ocoLifecycleTracked
    localTradingViewOcoLifecycleStatus = $ocoLifecycleStatus
    requiredProductionEnvDiff = @($requiredEnvDiff)
    envFlagsThatMustRemainDisabled = @($mustRemainDisabled)
    envFlagsNotChangedByThisPacket = @($notChangedByThisPacket)
    exactOcoLifecycleAuthorizationText = $exactAuthorizationText
    exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
    requiredPostEnvReadOnlyVerification = @($postEnvVerification)
    rollbackPlan = @($rollbackPlan)
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes the exact LOCAL_TRADINGVIEW OCO lifecycle env diff",
        "operator explicitly authorizes deploy/restart current origin/main",
        "operator accepts OCO close detection, auto retry, reconciliation writes, OKX private WS OCO handling, and related Telegram alerts",
        "operator keeps POSITION_EXIT_MANAGER_ENABLED=false unless separately authorized",
        "operator accepts rollback plan",
        "post-env verification is read-only"
    )
    forbiddenActions = @(
        "enable POSITION_EXIT_MANAGER",
        "enable TinyLive or ScoreBuy execution schedulers",
        "create or rebalance grid",
        "mutate fund or Earn state",
        "manual position reconciliation writes from this packet",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/exchange/external backfill state outside the reviewed OCO lifecycle scope"
    )
    localTradingViewOcoLifecycleEnvRequestAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    schedulerEnablementAllowed = $false
    livePolicyChangeAllowed = $false
    liveOrderMutationAllowed = $false
    ocoMutationAllowed = $false
    positionMutationAllowed = $false
    gridMutationAllowed = $false
    fundOrEarnMutationAllowed = $false
    dbMutationAllowed = $false
    exchangeMutationAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    missingRequirements = @($missingRequirements)
    nextAction = if ($status -eq "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION") {
        "Obtain the exact operator authorization text, then apply only TRADING_OCO_POLLER_ENABLED=true while keeping POSITION_EXIT_MANAGER_ENABLED=false, deploy/restart, and run the listed read-only verification commands."
    } elseif ($status -eq "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY") {
        "Keep OCO lifecycle tracking under observation and run post-env read-only verification; no env handoff is needed."
    } else {
        "Refresh local TradingView candidate smoke and fix missing LIVE_MICRO/OCO lifecycle evidence before requesting env/deploy authorization."
    }
    notAuthorization = "read-only LOCAL_TRADINGVIEW OCO lifecycle env handoff packet only; does not change production env, deploy, restart, enable additional entry paths, place orders, enable position-exit manager, create/rebalance grid, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[local-tradingview-oco-lifecycle-env-handoff] read-only packet"
Write-Host "scope=READ_ONLY; consumes local TradingView candidate smoke and local git metadata only; no production env, deploy, restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("local_tradingview_oco_lifecycle_env_handoff_packet=" + ($packet | ConvertTo-Json -Compress -Depth 12))
Write-Host "local_tradingview_oco_lifecycle_env_handoff_status=$status"
Write-Host "local_tradingview_oco_lifecycle_env_handoff_decision=$decision"
Write-Host "source_candidate_smoke_status=$sourceReadiness"
Write-Host "current_candidate_status=$currentCandidateStatus"
Write-Host "current_candidate_required_for_env_handoff=false"
Write-Host "local_tradingview_primary=$primary"
Write-Host "local_tradingview_enabled=$($localEnabled.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_execution_mode=$executionMode"
Write-Host "local_tradingview_live_micro_armed=$($liveMicroArmed.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_execution_path_armed=$($executionPathArmed.ToString().ToLowerInvariant())"
Write-Host "trading_oco_poller_enabled=$($tradingOcoPollerEnabled.ToString().ToLowerInvariant())"
Write-Host "position_exit_manager_enabled=$($positionExitManagerEnabled.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_oco_lifecycle_tracked=$($ocoLifecycleTracked.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_oco_lifecycle_status=$ocoLifecycleStatus"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host ("local_tradingview_oco_required_env_diff=" + (ConvertTo-Json -Compress @($requiredEnvDiff)))
Write-Host ("local_tradingview_oco_env_flags_must_remain_disabled=" + (ConvertTo-Json -Compress @($mustRemainDisabled)))
Write-Host ("local_tradingview_oco_env_flags_not_changed_by_this_packet=" + (ConvertTo-Json -Compress @($notChangedByThisPacket)))
Write-Host ("local_tradingview_oco_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvVerification)))
Write-Host ("local_tradingview_oco_rollback_plan=" + (ConvertTo-Json -Compress @($rollbackPlan)))
Write-Host "local_tradingview_oco_exact_authorization_text=$exactAuthorizationText"
Write-Host "local_tradingview_oco_lifecycle_env_request_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "live_order_mutation_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "position_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "fund_or_earn_mutation_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("local_tradingview_oco_lifecycle_env_handoff_missing_requirements=" + (@($missingRequirements) -join "; "))
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION" -and $status -ne "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ALREADY_TRACKED_READ_ONLY_VERIFY") {
    throw "LOCAL_TRADINGVIEW OCO lifecycle env handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
