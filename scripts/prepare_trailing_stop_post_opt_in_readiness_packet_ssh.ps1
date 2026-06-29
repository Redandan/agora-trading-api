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
    [int[]]$StrategyIds = @(485, 574),
    [int]$ExpectedOptInStrategyId = 574,
    [string]$SourceLog = "",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
        throw "$Name contains unsupported characters for trailing post-opt-in readiness arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-PacketValue {
    param([object]$Packet, [string]$Name)
    if ($null -eq $Packet) { return "" }
    $property = $Packet.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    return [string]$property.Value
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-ChildScript {
    param([string]$Name, [hashtable]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $Name
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $output = @()
    $exitCode = 0
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @Arguments *>&1
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

function Read-SourceLog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "SourceLog not found: $Path"
    }
    return [pscustomobject]@{
        Text = (Get-Content -Raw -LiteralPath $Path)
        ExitCode = 0
    }
}

if ($Days -lt 1 -or $Days -gt 90) { throw "Days must be between 1 and 90." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }
if ($StrategyIds.Count -lt 1 -or $StrategyIds.Count -gt 10) { throw "StrategyIds must include between 1 and 10 strategy ids." }
foreach ($strategyId in $StrategyIds) {
    if ($strategyId -lt 1 -or $strategyId -gt 1000000) { throw "StrategyIds contains unsupported strategy id: $strategyId" }
}
if ($ExpectedOptInStrategyId -lt 1 -or $ExpectedOptInStrategyId -gt 1000000) { throw "ExpectedOptInStrategyId must be between 1 and 1000000." }
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode

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

$activationResult = $null
if ($usingSourceLog) {
    $activationResult = Read-SourceLog -Path $SourceLog
} else {
    $activationArgs = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        IntervalCode = $IntervalCode
        ReplayIntervalCode = $ReplayIntervalCode
        Days = $Days
        Limit = $Limit
        StrategyIds = $StrategyIds
    }
    $activationResult = Invoke-ChildScript -Name "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1" -Arguments $activationArgs
}

$activationJson = Get-LastPrefixedValue -Text $activationResult.Text -Prefix "trailing_stop_dry_run_activation_review_packet="
$activationPacket = $null
if (-not [string]::IsNullOrWhiteSpace($activationJson)) {
    $activationPacket = $activationJson | ConvertFrom-Json -ErrorAction Stop
}

$sourceStatus = Get-PacketValue -Packet $activationPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $activationResult.Text -Prefix "trailing_stop_dry_run_activation_status="
}
$sourceDecision = Get-PacketValue -Packet $activationPacket -Name "activationDecision"
$trailingAcceptance = Get-PacketValue -Packet $activationPacket -Name "trailingAcceptance"
$trailingImprovementPct = Get-PacketValue -Packet $activationPacket -Name "trailingImprovementPct"
$trailingDeltaPnl = Get-PacketValue -Packet $activationPacket -Name "trailingDeltaPnl"
$currentGlobalEnabled = Get-PacketValue -Packet $activationPacket -Name "currentGlobalEnabled"
$currentGlobalDryRun = Get-PacketValue -Packet $activationPacket -Name "currentGlobalDryRun"
$currentOpenOcoPositions = Get-PacketValue -Packet $activationPacket -Name "currentOpenOcoPositions"

$reviewedStrategyOptInCount = -1
$countText = Get-PacketValue -Packet $activationPacket -Name "reviewedStrategyOptInCount"
if (-not [int]::TryParse($countText, [ref]$reviewedStrategyOptInCount)) {
    $reviewedStrategyOptInCount = -1
}

$sourceMissingRequirements = @()
if ($null -ne $activationPacket) {
    $sourceMissingProperty = $activationPacket.PSObject.Properties["missingRequirements"]
    if ($null -ne $sourceMissingProperty -and $null -ne $sourceMissingProperty.Value) {
        $sourceMissingRequirements = @($sourceMissingProperty.Value | ForEach-Object { [string]$_ })
    }
}

$reviewedStrategyOptIn = @()
$expectedStrategyOptIn = $false
if ($null -ne $activationPacket) {
    $reviewedProperty = $activationPacket.PSObject.Properties["reviewedStrategyOptIn"]
    if ($null -ne $reviewedProperty -and $null -ne $reviewedProperty.Value) {
        foreach ($entry in @($reviewedProperty.Value)) {
            $strategyValue = ""
            $enabledValue = ""
            if ($null -ne $entry.PSObject.Properties["strategyId"]) { $strategyValue = [string]$entry.PSObject.Properties["strategyId"].Value }
            if ($null -ne $entry.PSObject.Properties["trailingStopEnabled"]) { $enabledValue = [string]$entry.PSObject.Properties["trailingStopEnabled"].Value }
            if ([string]::IsNullOrWhiteSpace($strategyValue)) { continue }
            $parsedStrategyId = 0
            if (-not [int]::TryParse($strategyValue, [ref]$parsedStrategyId)) { continue }
            $normalizedEnabled = if ([string]::IsNullOrWhiteSpace($enabledValue)) { "UNKNOWN" } else { $enabledValue.ToLowerInvariant() }
            $reviewedStrategyOptIn += [pscustomobject]@{
                strategyId = $parsedStrategyId
                trailingStopEnabled = $normalizedEnabled
            }
            if ($parsedStrategyId -eq $ExpectedOptInStrategyId -and $normalizedEnabled -eq "true") {
                $expectedStrategyOptIn = $true
            }
        }
    }
}

$envDiffReviewReady = $sourceStatus -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED"
$alreadyActive = $sourceStatus -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_REVIEW_ONLY"

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($activationResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run activation packet completed" }
if ($null -eq $activationPacket) { Add-MissingRequirement -List $missingRequirements -Value "trailing_stop_dry_run_activation_review_packet valid JSON" }
if (-not $envDiffReviewReady -and -not $alreadyActive) {
    Add-MissingRequirement -List $missingRequirements -Value "source activation status is env-diff ready or already active dry-run"
}
if ($trailingAcceptance -ne "PASS") { Add-MissingRequirement -List $missingRequirements -Value "trailing replay acceptance=PASS" }
if ($currentGlobalDryRun -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "current global dry-run remains true" }
if ($envDiffReviewReady -and $currentGlobalEnabled -ne "false") { Add-MissingRequirement -List $missingRequirements -Value "current global trailing enabled remains false before env diff" }
if ($alreadyActive -and $currentGlobalEnabled -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "current global trailing enabled is true for active dry-run verification" }
if ($reviewedStrategyOptInCount -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "at least one reviewed strategy trailingStopEnabled opt-in is true" }
if (-not $expectedStrategyOptIn) { Add-MissingRequirement -List $missingRequirements -Value "expected strategy trailingStopEnabled=true" }
if ($sourceMissingRequirements.Count -gt 0) { Add-MissingRequirement -List $missingRequirements -Value "source activation missingRequirements empty" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready -and $envDiffReviewReady) {
    "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION"
} elseif ($ready -and $alreadyActive) {
    "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY"
} elseif ($sourceStatus -eq "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED" -or -not $expectedStrategyOptIn) {
    "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED"
} else {
    "NOT_READY"
}
$decision = if ($status -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION") {
    "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
} elseif ($status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
    "VERIFY_ACTIVE_DRY_RUN_OBSERVATION_ONLY"
} elseif ($status -eq "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED") {
    "WAIT_FOR_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION"
} else {
    "FIX_POST_OPT_IN_READINESS_EVIDENCE"
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET"
    status = $status
    symbol = $Symbol
    sourceActivationPacket = if ($usingSourceLog) { $SourceLog } else { "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1" }
    sourceActivationStatus = $sourceStatus
    sourceActivationDecision = $sourceDecision
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovementPct
    trailingDeltaPnl = $trailingDeltaPnl
    currentGlobalEnabled = $currentGlobalEnabled
    currentGlobalDryRun = $currentGlobalDryRun
    currentOpenOcoPositions = $currentOpenOcoPositions
    reviewedStrategyIds = @($StrategyIds)
    expectedOptInStrategyId = $ExpectedOptInStrategyId
    expectedStrategyOptIn = $expectedStrategyOptIn
    reviewedStrategyOptInCount = $reviewedStrategyOptInCount
    reviewedStrategyOptIn = @($reviewedStrategyOptIn)
    envDiffReviewReady = $envDiffReviewReady
    alreadyActiveDryRun = $alreadyActive
    proposedSeparateEnvDiff = @(
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true"
    )
    envDiffMustRemain = @(
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "TRADING_OCO_POLLER_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false"
    )
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes production env diff",
        "operator explicitly authorizes deploy/restart",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "post-env read-only verification only"
    )
    postEnvDiffReadOnlyVerification = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1 -Symbol $Symbol -IntervalCode $IntervalCode -ReplayIntervalCode $ReplayIntervalCode -Days $Days -Limit $Limit -RequireAcceptance",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
        "server-local MCP getTrailingStopStatus confirms global enabled=true and dryRun=true",
        "server-local MCP getStrategyConfig confirms strategy $ExpectedOptInStrategyId trailingStopEnabled=true",
        "runtime-log smoke: no order, OCO modification, close-position, Telegram send, grid, fund, Earn, or exchange mutation lines"
    )
    proposedRuntimeBoundary = [pscustomobject]@{
        productionEnvChangeAllowedByThisPacket = $false
        deployAllowedByThisPacket = $false
        schedulerEnablementAllowedByThisPacket = $false
        liveTradingAllowed = $false
        orderAllowed = $false
        ocoMutationAllowed = $false
        positionCloseAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    explicitNonAuthorizations = @(
        "does not call setTrailingStopOptIn",
        "does not change production env",
        "does not deploy or restart",
        "does not enable scheduler by itself",
        "does not enable live trading",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not close positions",
        "does not send Telegram",
        "does not mutate DB/grid/fund/Earn/exchange state",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    sourceActivationMissingRequirements = @($sourceMissingRequirements)
    missingRequirements = @($missingRequirements)
    nextAction = if ($status -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION") {
        "Request separate operator authorization for TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, then deploy/restart and run post-env read-only verification."
    } elseif ($status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
        "Continue active dry-run observation only; do not treat this as live or OCO mutation approval."
    } elseif ($status -eq "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED") {
        "Wait for the separate strategy opt-in authorization/write, then rerun this read-only packet."
    } else {
        "Refresh or fix post-opt-in readiness evidence before requesting env/deploy authorization."
    }
    notAuthorization = "read-only trailing-stop post-opt-in readiness packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[trailing-stop-post-opt-in-readiness-packet] read-only packet"
Write-Host "scope=READ_ONLY; consumes prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1 output or a saved SourceLog; no strategy config, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_activation_packet=$($packet.sourceActivationPacket) exitCode=$($activationResult.ExitCode)"
Write-Host "source_activation_packet_status=$sourceStatus"
Write-Host "source_activation_decision=$sourceDecision"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "trailing_stop_post_opt_in_current_global_enabled=$currentGlobalEnabled"
Write-Host "trailing_stop_post_opt_in_current_global_dry_run=$currentGlobalDryRun"
Write-Host "trailing_stop_post_opt_in_current_open_oco_positions=$currentOpenOcoPositions"
Write-Host "trailing_stop_post_opt_in_expected_strategy_id=$ExpectedOptInStrategyId"
Write-Host "trailing_stop_post_opt_in_expected_strategy_opt_in=$($expectedStrategyOptIn.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_post_opt_in_reviewed_strategy_opt_in_count=$reviewedStrategyOptInCount"
Write-Host ("trailing_stop_post_opt_in_reviewed_strategy_opt_in=" + (ConvertTo-Json -Compress -Depth 8 -InputObject @($reviewedStrategyOptIn)))
Write-Host "trailing_stop_post_opt_in_env_diff_review_ready=$($envDiffReviewReady.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_post_opt_in_already_active_dry_run=$($alreadyActive.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_post_opt_in_missing_requirements=" + (ConvertTo-Json -Compress -InputObject @($missingRequirements)))
Write-Host ("trailing_stop_post_opt_in_readiness_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_post_opt_in_readiness_status=$status"
Write-Host "trailing_stop_post_opt_in_readiness_decision=$decision"
Write-Host "notAuthorization=read-only trailing-stop post-opt-in readiness packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[trailing-stop-post-opt-in-readiness-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop post-opt-in readiness packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
