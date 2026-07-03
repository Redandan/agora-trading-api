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
        throw "$Name contains unsupported characters for local TradingView dry-run handoff arguments."
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
$lastOrderAt = Get-RegexValue -Text $sourceText -Pattern "^\s*lastOrderAt=([^\r\n]+)" -Default "UNKNOWN"
$firstOrderAt = Get-RegexValue -Text $sourceText -Pattern "^\s*firstOrderAt=([^\r\n]+)" -Default "UNKNOWN"
$orderBars = Get-RegexValue -Text $sourceText -Pattern "^\s*orderBars=([0-9]+)" -Default "0"
$orderIntents = Get-RegexValue -Text $sourceText -Pattern "^\s*orderIntents=([0-9]+)" -Default "0"
$coverage = Get-RegexValue -Text $sourceText -Pattern "^\s*coverage=([^\r\n]+)" -Default "UNKNOWN"
$trailingGapHours = Get-RegexValue -Text $sourceText -Pattern "^\s*trailingGapHours=([^\r\n]+)" -Default "UNKNOWN"
$coverageWarning = Get-RegexValue -Text $sourceText -Pattern "^\s*coverageWarning=([^\r\n]+)" -Default "UNKNOWN"
$primary = (Get-RegexValue -Text $sourceText -Pattern "^\s*primary=([^\r\n]+)" -Default "UNKNOWN").ToUpperInvariant()
$localEnabledText = Get-RegexValue -Text $sourceText -Pattern "^\s*localEnabled=([^\r\n]+)" -Default "false"
$executionMode = (Get-RegexValue -Text $sourceText -Pattern "^\s*executionMode=([^\r\n]+)" -Default "UNKNOWN").ToUpperInvariant()
$effectiveExecutionEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*effectiveExecutionEnabled=([^\r\n]+)" -Default "false")
$effectiveExecutionDryRun = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*effectiveExecutionDryRun=([^\r\n]+)" -Default "false")
$effectiveLiveOrderEnabled = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*effectiveLiveOrderEnabled=([^\r\n]+)" -Default "false")
$localEvaluatorActive = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewEvaluatorActive=([^\r\n]+)" -Default "false")
$dryRunArmed = ConvertTo-BoolValue (Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewExecutionDryRunArmed=([^\r\n]+)" -Default "false")
$netPnlUsdt = Get-RegexValue -Text $sourceText -Pattern "^\s*netPnlUsdt=([^\r\n]+)" -Default "UNKNOWN"
$totalReturn = Get-RegexValue -Text $sourceText -Pattern "^\s*totalReturn=([^\r\n]+)" -Default "UNKNOWN"
$sourceReadiness = Get-RegexValue -Text $sourceText -Pattern "^\s*localTradingViewReadiness=([^\r\n]+)" -Default "UNKNOWN"
$sourceBlockersJson = Get-RegexValue -Text $sourceText -Pattern "local_tradingview_blockers=(\[[^\r\n]*\])" -Default "[]"
$sourceBlockers = ConvertFrom-JsonArrayOrEmpty -Json $sourceBlockersJson
$orderIntentCount = ConvertTo-LongOrZero -Value $orderIntents
$localEnabled = ConvertTo-BoolValue $localEnabledText

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if (-not $worktreeClean -and -not ($usingSourceLog -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-Unique -List $missingRequirements -Value "local worktree clean before local TradingView dry-run receipt env handoff"
}
if ($behindCount -gt 0) { Add-Unique -List $missingRequirements -Value "local branch not behind origin/main" }
if ($sourceResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "local TradingView candidate smoke completed" }
if ($primary -ne "LOCAL_TRADINGVIEW") { Add-Unique -List $missingRequirements -Value "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW" }
if (-not $localEnabled) { Add-Unique -List $missingRequirements -Value "TRADINGVIEW_LOCAL_ENABLED=true" }
if (-not $localEvaluatorActive) { Add-Unique -List $missingRequirements -Value "local TradingView evaluator active" }
if ($coverage -notin @("OK", "WARN")) { Add-Unique -List $missingRequirements -Value "local TradingView data coverage OK or WARN" }
if ($orderIntentCount -le 0) { Add-Unique -List $missingRequirements -Value "local TradingView preview has historical order intents" }
if ($effectiveLiveOrderEnabled) { Add-Unique -List $missingRequirements -Value "effective live order remains disabled" }
if ($executionMode -eq "LIVE_MICRO") { Add-Unique -List $missingRequirements -Value "current execution mode is not LIVE_MICRO" }
if ($executionMode -notin @("LEGACY", "DRY_RUN", "OFF")) { Add-Unique -List $missingRequirements -Value "current execution mode is LEGACY, OFF, or already DRY_RUN" }
if (-not $effectiveExecutionDryRun -and $executionMode -ne "DRY_RUN") { Add-Unique -List $missingRequirements -Value "dry-run guard remains true before handoff" }

$alreadyArmed = $executionMode -eq "DRY_RUN" -and $dryRunArmed -and -not $effectiveLiveOrderEnabled
$ready = $missingRequirements.Count -eq 0
$status = if ($ready -and $alreadyArmed) {
    "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY"
} elseif ($ready) {
    "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION"
} else {
    "NOT_READY_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF"
}
$decision = if ($status -eq "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION") {
    "REQUEST_EXACT_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_AUTHORIZATION"
} elseif ($status -eq "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY") {
    "RUN_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_OBSERVATION"
} else {
    "REFRESH_OR_FIX_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_EVIDENCE"
}

$requiredEnvState = @(
    "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW",
    "TRADINGVIEW_LOCAL_ENABLED=true",
    "TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN",
    "TRADINGVIEW_LOCAL_EXECUTION_ENABLED=false",
    "TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN=true",
    "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false"
)
$mustRemainDisabled = @(
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "POSITION_EXIT_MANAGER_ENABLED=false",
    "TRADING_OCO_POLLER_ENABLED=false",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "TRADING_FUNDING_ARB_ENABLED=false"
)
$notChangedByThisPacket = @(
    "TRADING_OKX_ENABLED",
    "TRADING_GRID_ENABLED",
    "TRAILING_STOP_ENABLED",
    "TRAILING_STOP_DRY_RUN",
    "TradingView webhook flags",
    "ScoreBuy execution scheduler flags",
    "grid/fund/Earn/OCO policy"
)
$postEnvVerification = @(
    ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed",
    ".\scripts\audit_live_readiness_ssh.ps1",
    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
    "When currentCandidateStatus=HAS_CURRENT_BUY_CANDIDATE, run .\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireCurrentCandidate -RequireDryRunArmed",
    "runtime-log smoke confirms no live order/OCO/grid/fund/Earn/Telegram/exchange mutation while LOCAL_TRADINGVIEW remains dry-run"
)
$rollbackPlan = @(
    "set TRADINGVIEW_LOCAL_EXECUTION_MODE=LEGACY",
    "keep TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN=true",
    "keep TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false",
    "deploy/restart rollback env diff",
    ".\scripts\smoke_local_tradingview_candidate_ssh.ps1",
    ".\scripts\audit_live_readiness_ssh.ps1"
)
$exactAuthorizationText = "I authorize production env state TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW, TRADINGVIEW_LOCAL_ENABLED=true, TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN, TRADINGVIEW_LOCAL_EXECUTION_ENABLED=false, TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN=true, and TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false, deploy/restart current origin/main, and post-env read-only verification only. I do not authorize TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO, TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=true, live order placement, OCO mutation, grid/fund/Earn mutation, Telegram send, scheduler/live policy relaxation, DB mutation, exchange mutation, or external backfill/import."

$packet = [ordered]@{
    packetType = "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_PACKET"
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
    lastOrderAt = $lastOrderAt
    firstOrderAt = $firstOrderAt
    orderBars = $orderBars
    orderIntents = $orderIntents
    coverage = $coverage
    trailingGapHours = $trailingGapHours
    coverageWarning = $coverageWarning
    primary = $primary
    localEnabled = $localEnabled
    executionMode = $executionMode
    effectiveExecutionEnabled = $effectiveExecutionEnabled
    effectiveExecutionDryRun = $effectiveExecutionDryRun
    effectiveLiveOrderEnabled = $effectiveLiveOrderEnabled
    localTradingViewEvaluatorActive = $localEvaluatorActive
    localTradingViewExecutionDryRunArmed = $dryRunArmed
    historicalParityNetPnlUsdt = $netPnlUsdt
    historicalParityTotalReturn = $totalReturn
    requiredProductionEnvState = @($requiredEnvState)
    envFlagsThatMustRemainDisabled = @($mustRemainDisabled)
    envFlagsNotChangedByThisPacket = @($notChangedByThisPacket)
    exactOperatorAuthorizationText = $exactAuthorizationText
    exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
    requiredPostEnvReadOnlyVerification = @($postEnvVerification)
    rollbackPlan = @($rollbackPlan)
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes the exact LOCAL_TRADINGVIEW dry-run receipt env state",
        "operator explicitly authorizes deploy/restart current origin/main",
        "operator accepts no live-order mutation and no LIVE_MICRO mode",
        "operator accepts rollback plan",
        "post-env read-only verification only"
    )
    forbiddenActions = @(
        "TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO",
        "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=true",
        "place live orders",
        "create or modify OCO",
        "enable ScoreBuy or TinyLive execution schedulers",
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
    liveOrderMutationAllowed = $false
    ocoMutationAllowed = $false
    gridMutationAllowed = $false
    fundOrEarnMutationAllowed = $false
    dbMutationAllowed = $false
    exchangeMutationAllowed = $false
    orderAllowed = $false
    telegramSendAllowed = $false
    missingRequirements = @($missingRequirements)
    nextAction = if ($status -eq "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION") {
        "Obtain the exact operator authorization text, then apply only the LOCAL_TRADINGVIEW dry-run receipt env state, deploy/restart, and run the listed read-only verification commands."
    } elseif ($status -eq "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY") {
        "Skip env handoff and keep collecting LOCAL_TRADINGVIEW dry-run receipt evidence; require a current BUY candidate before any live plan."
    } else {
        "Refresh local TradingView candidate smoke and fix missing handoff evidence before requesting env/deploy authorization."
    }
    notAuthorization = "read-only LOCAL_TRADINGVIEW dry-run receipt env handoff packet only; does not change production env, deploy, restart, enable live trading, switch to LIVE_MICRO, place orders, modify OCO, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[local-tradingview-dry-run-receipt-env-handoff] read-only packet"
Write-Host "scope=READ_ONLY; consumes local TradingView candidate smoke and local git metadata only; no production env, deploy, restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("local_tradingview_dry_run_receipt_env_handoff_packet=" + ($packet | ConvertTo-Json -Compress -Depth 12))
Write-Host "local_tradingview_dry_run_receipt_env_handoff_status=$status"
Write-Host "local_tradingview_dry_run_receipt_env_handoff_decision=$decision"
Write-Host "source_candidate_smoke_status=$sourceReadiness"
Write-Host "current_candidate_status=$currentCandidateStatus"
Write-Host "current_candidate_required_for_env_handoff=false"
Write-Host "local_tradingview_primary=$primary"
Write-Host "local_tradingview_enabled=$($localEnabled.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_execution_mode=$executionMode"
Write-Host "local_tradingview_dry_run_armed=$($dryRunArmed.ToString().ToLowerInvariant())"
Write-Host "local_tradingview_effective_live_order_enabled=$($effectiveLiveOrderEnabled.ToString().ToLowerInvariant())"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host ("local_tradingview_required_env_state=" + (ConvertTo-Json -Compress @($requiredEnvState)))
Write-Host ("local_tradingview_env_flags_must_remain_disabled=" + (ConvertTo-Json -Compress @($mustRemainDisabled)))
Write-Host ("local_tradingview_env_flags_not_changed_by_this_packet=" + (ConvertTo-Json -Compress @($notChangedByThisPacket)))
Write-Host ("local_tradingview_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvVerification)))
Write-Host ("local_tradingview_rollback_plan=" + (ConvertTo-Json -Compress @($rollbackPlan)))
Write-Host "local_tradingview_exact_authorization_text=$exactAuthorizationText"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "live_order_mutation_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "fund_or_earn_mutation_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("local_tradingview_dry_run_receipt_env_handoff_missing_requirements=" + (@($missingRequirements) -join "; "))
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ENV_HANDOFF_NOT_MUTATION" -and $status -ne "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_ALREADY_ARMED_READ_ONLY_VERIFY") {
    throw "LOCAL_TRADINGVIEW dry-run receipt env handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
