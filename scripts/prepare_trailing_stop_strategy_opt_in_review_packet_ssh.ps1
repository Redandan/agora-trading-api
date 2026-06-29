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
    [int]$PreferredStrategyId = 574,
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
        throw "$Name contains unsupported characters for trailing strategy opt-in review arguments."
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
if ($PreferredStrategyId -lt 1 -or $PreferredStrategyId -gt 1000000) { throw "PreferredStrategyId must be between 1 and 1000000." }
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
$trailingAcceptance = Get-PacketValue -Packet $activationPacket -Name "trailingAcceptance"
$trailingImprovementPct = Get-PacketValue -Packet $activationPacket -Name "trailingImprovementPct"
$trailingDeltaPnl = Get-PacketValue -Packet $activationPacket -Name "trailingDeltaPnl"
$currentGlobalEnabled = Get-PacketValue -Packet $activationPacket -Name "currentGlobalEnabled"
$currentGlobalDryRun = Get-PacketValue -Packet $activationPacket -Name "currentGlobalDryRun"
$currentOpenOcoPositions = Get-PacketValue -Packet $activationPacket -Name "currentOpenOcoPositions"
$sourceDecision = Get-PacketValue -Packet $activationPacket -Name "activationDecision"
$sourceNextAction = Get-PacketValue -Packet $activationPacket -Name "nextAction"

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
$strategyIdsNeedingOptIn = @()
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
            if ($normalizedEnabled -eq "false") {
                $strategyIdsNeedingOptIn += $parsedStrategyId
            }
        }
    }
}

$candidateStrategyId = 0
if ($strategyIdsNeedingOptIn -contains $PreferredStrategyId) {
    $candidateStrategyId = $PreferredStrategyId
} elseif ($strategyIdsNeedingOptIn.Count -gt 0) {
    $candidateStrategyId = [int]$strategyIdsNeedingOptIn[0]
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($activationResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run activation packet completed" }
if ($null -eq $activationPacket) { Add-MissingRequirement -List $missingRequirements -Value "trailing_stop_dry_run_activation_review_packet valid JSON" }
if ($sourceStatus -ne "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED") {
    Add-MissingRequirement -List $missingRequirements -Value "source activation status is BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED"
}
if ($trailingAcceptance -ne "PASS") { Add-MissingRequirement -List $missingRequirements -Value "trailing replay acceptance=PASS" }
if ($currentGlobalDryRun -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "current global dry-run remains true" }
if ($currentGlobalEnabled -ne "false") { Add-MissingRequirement -List $missingRequirements -Value "current global trailing enabled remains false before env diff" }
if ($reviewedStrategyOptInCount -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "reviewed strategy trailingStopEnabled opt-in count is 0" }
if ($strategyIdsNeedingOptIn.Count -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "at least one reviewed strategy is available for opt-in" }
if ($candidateStrategyId -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "candidate strategy id selected for opt-in review" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) {
    "READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION"
} elseif ($reviewedStrategyOptInCount -gt 0) {
    "NOT_NEEDED_STRATEGY_TRAILING_OPT_IN_ALREADY_PRESENT"
} else {
    "NOT_READY"
}
$decision = if ($ready) {
    "REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION"
} elseif ($status -eq "NOT_NEEDED_STRATEGY_TRAILING_OPT_IN_ALREADY_PRESENT") {
    "FOLLOW_ACTIVATION_PACKET_ENV_DIFF_REVIEW_OR_REFRESH"
} else {
    "FIX_STRATEGY_OPT_IN_REVIEW_EVIDENCE"
}

$candidateWrite = if ($candidateStrategyId -gt 0) {
    "setTrailingStopOptIn(strategyId=$candidateStrategyId, enabled=true, notes='trailing dry-run observation only; no order or OCO mutation')"
} else {
    ""
}
$candidateRollback = if ($candidateStrategyId -gt 0) {
    "setTrailingStopOptIn(strategyId=$candidateStrategyId, enabled=false, notes='rollback trailing dry-run opt-in observation')"
} else {
    ""
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    sourceActivationPacket = if ($usingSourceLog) { $SourceLog } else { "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1" }
    sourceActivationStatus = $sourceStatus
    sourceActivationDecision = $sourceDecision
    sourceActivationNextAction = $sourceNextAction
    sourceActivationMissingRequirements = @($sourceMissingRequirements)
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovementPct
    trailingDeltaPnl = $trailingDeltaPnl
    currentGlobalEnabled = $currentGlobalEnabled
    currentGlobalDryRun = $currentGlobalDryRun
    currentOpenOcoPositions = $currentOpenOcoPositions
    reviewedStrategyIds = @($StrategyIds)
    reviewedStrategyOptInCount = $reviewedStrategyOptInCount
    reviewedStrategyOptIn = @($reviewedStrategyOptIn)
    preferredStrategyId = $PreferredStrategyId
    recommendedStrategyId = $candidateStrategyId
    operatorMaySelectStrategyIds = @($strategyIdsNeedingOptIn)
    proposedSeparateMcpWrite = $candidateWrite
    rollbackMcpWrite = $candidateRollback
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes exactly one strategy trailingStopEnabled opt-in write",
        "operator names the reviewed strategy id before execution",
        "operator accepts dry-run observation only; no order/OCO/live policy relaxation",
        "operator reviews rollback write before execution",
        "post-opt-in verification is read-only"
    )
    postOptInReadOnlyVerification = @(
        ".\scripts\prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1 -Symbol $Symbol -IntervalCode $IntervalCode -ReplayIntervalCode $ReplayIntervalCode -Days $Days -Limit $Limit",
        "server-local MCP getStrategyConfig confirms selected strategy trailingStopEnabled=true",
        "server-local MCP getTrailingStopStatus confirms global enabled=false and dryRun=true until separate env diff",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
        "runtime-log smoke: no order, OCO modification, close-position, Telegram send, grid, fund, Earn, or exchange mutation lines"
    )
    nextAuthorizationAfterOptIn = @(
        "rerun activation packet",
        "if activation status becomes READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED, request separate env/deploy authorization",
        "env diff remains TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true only",
        "scheduler/live/order/OCO mutation remains disallowed until dry-run evidence is reviewed"
    )
    proposedRuntimeBoundary = [pscustomobject]@{
        strategyOptInChangeAllowedByThisPacket = $false
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        schedulerEnablementAllowed = $false
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
        "does not enable scheduler",
        "does not enable live trading",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not close positions",
        "does not send Telegram",
        "does not mutate DB/grid/fund/Earn/exchange state",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    missingRequirements = @($missingRequirements)
    nextAction = if ($ready) {
        "Request separate operator authorization for the proposed setTrailingStopOptIn write, then execute only that write and run the post-opt-in read-only verification."
    } elseif ($status -eq "NOT_NEEDED_STRATEGY_TRAILING_OPT_IN_ALREADY_PRESENT") {
        "Strategy opt-in is already present; rerun the activation packet and follow its env-diff review path."
    } else {
        "Refresh or fix the activation packet evidence before requesting a strategy opt-in write."
    }
    notAuthorization = "read-only trailing-stop strategy opt-in review packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[trailing-stop-strategy-opt-in-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; consumes prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1 output or a saved SourceLog; no strategy config, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_activation_packet=$($packet.sourceActivationPacket) exitCode=$($activationResult.ExitCode)"
Write-Host "source_activation_packet_status=$sourceStatus"
Write-Host "source_activation_decision=$sourceDecision"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "trailing_stop_strategy_opt_in_review_current_global_enabled=$currentGlobalEnabled"
Write-Host "trailing_stop_strategy_opt_in_review_current_global_dry_run=$currentGlobalDryRun"
Write-Host "trailing_stop_strategy_opt_in_review_current_open_oco_positions=$currentOpenOcoPositions"
Write-Host "trailing_stop_strategy_opt_in_review_current_strategy_opt_in_count=$reviewedStrategyOptInCount"
Write-Host ("trailing_stop_strategy_opt_in_review_reviewed_strategy_opt_in=" + (ConvertTo-Json -Compress -Depth 8 -InputObject @($reviewedStrategyOptIn)))
Write-Host ("trailing_stop_strategy_opt_in_review_operator_selectable_strategy_ids=" + (ConvertTo-Json -Compress -InputObject @($strategyIdsNeedingOptIn)))
Write-Host "trailing_stop_strategy_opt_in_review_recommended_strategy_id=$candidateStrategyId"
Write-Host "trailing_stop_strategy_opt_in_review_proposed_mcp_write=$candidateWrite"
Write-Host "trailing_stop_strategy_opt_in_review_rollback_mcp_write=$candidateRollback"
Write-Host "trailing_stop_strategy_opt_in_review_allowed=false"
Write-Host "trailing_stop_strategy_opt_in_change_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_strategy_opt_in_review_missing_requirements=" + (ConvertTo-Json -Compress -InputObject @($missingRequirements)))
Write-Host ("trailing_stop_strategy_opt_in_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_strategy_opt_in_review_status=$status"
Write-Host "trailing_stop_strategy_opt_in_review_decision=$decision"
Write-Host "notAuthorization=read-only trailing-stop strategy opt-in review packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler/live trading, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[trailing-stop-strategy-opt-in-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop strategy opt-in review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
