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
        throw "$Name contains unsupported characters for trailing dry-run observation arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-PacketValue {
    param([object]$Packet, [string]$Name)
    if ($null -eq $Packet) { return "" }
    $property = $Packet.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-PostOptInReadiness {
    if (-not [string]::IsNullOrWhiteSpace($SourceLog)) {
        if (-not (Test-Path -LiteralPath $SourceLog)) {
            throw "SourceLog not found: $SourceLog"
        }
        return [pscustomobject]@{
            Text = (Get-Content -Raw -LiteralPath $SourceLog)
            ExitCode = 0
            Source = $SourceLog
        }
    }

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
        StrategyIds = $StrategyIds
        ExpectedOptInStrategyId = $ExpectedOptInStrategyId
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $output = @()
    $exitCode = 0
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @arguments *>&1
        if (-not $?) { $exitCode = 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
        Source = "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    }
}

if ($Days -lt 1 -or $Days -gt 90) { throw "Days must be between 1 and 90." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }
if ($StrategyIds.Count -lt 1 -or $StrategyIds.Count -gt 10) { throw "StrategyIds must include between 1 and 10 strategy ids." }
foreach ($strategyId in $StrategyIds) {
    if ($strategyId -lt 1 -or $strategyId -gt 1000000) { throw "StrategyIds contains unsupported strategy id: $strategyId" }
}
if ($ExpectedOptInStrategyId -lt 1 -or $ExpectedOptInStrategyId -gt 1000000) {
    throw "ExpectedOptInStrategyId must be between 1 and 1000000."
}
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

$postResult = Invoke-PostOptInReadiness
$postJson = Get-LastPrefixedValue -Text $postResult.Text -Prefix "trailing_stop_post_opt_in_readiness_packet="
$postPacket = $null
if (-not [string]::IsNullOrWhiteSpace($postJson)) {
    $postPacket = $postJson | ConvertFrom-Json -ErrorAction Stop
}

$sourceStatus = Get-PacketValue -Packet $postPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($sourceStatus)) {
    $sourceStatus = Get-LastPrefixedValue -Text $postResult.Text -Prefix "trailing_stop_post_opt_in_readiness_status="
}
$trailingAcceptance = Get-PacketValue -Packet $postPacket -Name "trailingAcceptance"
$trailingImprovementPct = Get-PacketValue -Packet $postPacket -Name "trailingImprovementPct"
$trailingDeltaPnl = Get-PacketValue -Packet $postPacket -Name "trailingDeltaPnl"
$currentGlobalEnabled = Get-PacketValue -Packet $postPacket -Name "currentGlobalEnabled"
$currentGlobalDryRun = Get-PacketValue -Packet $postPacket -Name "currentGlobalDryRun"
$currentOpenOcoPositions = Get-PacketValue -Packet $postPacket -Name "currentOpenOcoPositions"
$expectedStrategyOptIn = Get-PacketValue -Packet $postPacket -Name "expectedStrategyOptIn"
$alreadyActiveDryRun = Get-PacketValue -Packet $postPacket -Name "alreadyActiveDryRun"

$openOcoCount = -1
if (-not [int]::TryParse($currentOpenOcoPositions, [ref]$openOcoCount)) {
    $openOcoCount = -1
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($postResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "post-opt-in readiness packet completed" }
if ($null -eq $postPacket) { Add-MissingRequirement -List $missingRequirements -Value "trailing_stop_post_opt_in_readiness_packet valid JSON" }
if ($sourceStatus -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing dry-run is already active in read-only verify state"
}
if ($trailingAcceptance -ne "PASS") { Add-MissingRequirement -List $missingRequirements -Value "trailing replay acceptance=PASS" }
if ($currentGlobalEnabled -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "TRAILING_STOP_ENABLED currently true" }
if ($currentGlobalDryRun -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "TRAILING_STOP_DRY_RUN currently true" }
if ($expectedStrategyOptIn -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "expected strategy trailingStopEnabled=true" }

$preconditionsReady = $missingRequirements.Count -eq 0
$sampleReady = $preconditionsReady -and $openOcoCount -gt 0
$sampleCollectionBlockedBy = if (-not $preconditionsReady) {
    "TRAILING_DRY_RUN_NOT_ACTIVE_OR_NOT_VERIFIED"
} elseif ($openOcoCount -eq 0) {
    "NO_OPEN_OCO_POSITIONS"
} elseif ($openOcoCount -lt 0) {
    "OPEN_OCO_POSITION_COUNT_UNKNOWN"
} else {
    "NONE"
}
$status = if ($sampleReady) {
    "ACTIVE_OPEN_OCO_SAMPLE_AVAILABLE"
} elseif ($preconditionsReady -and $sampleCollectionBlockedBy -eq "NO_OPEN_OCO_POSITIONS") {
    "ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE"
} elseif ($preconditionsReady) {
    "ACTIVE_WAITING_FOR_OBSERVATION_COUNT_REVIEW"
} else {
    "BLOCKED_TRAILING_DRY_RUN_NOT_ACTIVE"
}
$uniqueBlocker = if ($status -eq "ACTIVE_OPEN_OCO_SAMPLE_AVAILABLE") {
    "NONE"
} elseif ($status -eq "ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE") {
    "NO_OPEN_OCO_POSITIONS"
} elseif ($status -eq "ACTIVE_WAITING_FOR_OBSERVATION_COUNT_REVIEW") {
    "OPEN_OCO_POSITION_COUNT_UNKNOWN"
} else {
    "TRAILING_DRY_RUN_NOT_ACTIVE_OR_NOT_VERIFIED"
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_DRY_RUN_OBSERVATION_STATUS_PACKET"
    status = $status
    symbol = $Symbol
    sourcePostOptInReadiness = $postResult.Source
    sourcePostOptInStatus = $sourceStatus
    sourcePostOptInExitCode = $postResult.ExitCode
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovementPct
    trailingDeltaPnl = $trailingDeltaPnl
    currentGlobalEnabled = $currentGlobalEnabled
    currentGlobalDryRun = $currentGlobalDryRun
    currentOpenOcoPositions = $currentOpenOcoPositions
    expectedOptInStrategyId = $ExpectedOptInStrategyId
    expectedStrategyOptIn = $expectedStrategyOptIn
    alreadyActiveDryRun = $alreadyActiveDryRun
    observationPreconditionsReady = $preconditionsReady
    observationSampleReady = $sampleReady
    sampleCollectionBlockedBy = $sampleCollectionBlockedBy
    uniqueBlocker = $uniqueBlocker
    exactRefreshCommand = ".\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId $ExpectedOptInStrategyId -RequireReady"
    requiredBeforeLivePromotion = @(
        "one or more real open OCO positions observed while TRAILING_STOP_DRY_RUN=true",
        "dry-run suggested exit actions captured without OCO mutation",
        "runtime-log smoke shows no order, OCO modification, close-position, grid, fund, Earn, Telegram, or exchange mutation",
        "estimated saved loss or captured profit remains positive after fees and slippage assumptions",
        "OCO health is normal before any live trailing review"
    )
    readOnlyVerificationCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId $ExpectedOptInStrategyId -RequireReady",
        ".\scripts\prepare_profit_next_execution_blocker_packet.ps1 -RequireReady",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol"
    )
    allowedActions = @(
        "read-only SSH/MCP evidence refresh",
        "runtime-log smoke",
        "operator review"
    )
    forbiddenActions = @(
        "set TRAILING_STOP_DRY_RUN=false",
        "enable live OCO mutation",
        "place orders",
        "close positions",
        "modify or cancel OCO",
        "enable scheduler/live policy relaxation",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange state",
        "run external backfill/import"
    )
    proposedRuntimeBoundary = [pscustomobject]@{
        boundary = "READ_ONLY"
        dryRunOnly = $true
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        schedulerEnablementAllowed = $false
        liveTradingAllowed = $false
        orderAllowed = $false
        ocoMutationAllowed = $false
        positionCloseAllowed = $false
        gridMutationAllowed = $false
        fundOrEarnMutationAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    missingRequirements = @($missingRequirements)
    nextAction = if ($status -eq "ACTIVE_WAITING_FOR_OPEN_OCO_SAMPLE") {
        "Keep dry-run active and wait for an open OCO position; do not promote to live trailing without a real dry-run sample."
    } elseif ($status -eq "ACTIVE_OPEN_OCO_SAMPLE_AVAILABLE") {
        "Collect dry-run suggested exit evidence and runtime-log smoke, then compare against actual forward outcome before any live trailing review."
    } elseif ($status -eq "ACTIVE_WAITING_FOR_OBSERVATION_COUNT_REVIEW") {
        "Refresh server-local MCP status because open OCO position count was not parseable."
    } else {
        "Restore or verify A0 trailing dry-run active state before collecting observation evidence."
    }
    notAuthorization = "read-only trailing-stop dry-run observation status packet only; does not change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[trailing-stop-dry-run-observation-status] read-only packet"
Write-Host "scope=READ_ONLY; consumes trailing post-opt-in readiness evidence or a saved SourceLog; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_post_opt_in_readiness=$($postResult.Source) exitCode=$($postResult.ExitCode)"
Write-Host "source_post_opt_in_readiness_status=$sourceStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "trailing_stop_dry_run_observation_current_global_enabled=$currentGlobalEnabled"
Write-Host "trailing_stop_dry_run_observation_current_global_dry_run=$currentGlobalDryRun"
Write-Host "trailing_stop_dry_run_observation_current_open_oco_positions=$currentOpenOcoPositions"
Write-Host "trailing_stop_dry_run_observation_expected_strategy_opt_in=$expectedStrategyOptIn"
Write-Host "trailing_stop_dry_run_observation_preconditions_ready=$($preconditionsReady.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_dry_run_observation_sample_ready=$($sampleReady.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_dry_run_observation_sample_collection_blocked_by=$sampleCollectionBlockedBy"
Write-Host "trailing_stop_dry_run_observation_unique_blocker=$uniqueBlocker"
Write-Host ("trailing_stop_dry_run_observation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("trailing_stop_dry_run_observation_status_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_dry_run_observation_status=$status"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only trailing-stop dry-run observation status packet only; does not change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[trailing-stop-dry-run-observation-status] read-only check complete"

if ($RequireReady -and -not $preconditionsReady) {
    throw "Trailing-stop dry-run observation status is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
