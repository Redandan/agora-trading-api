param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for exit-side review arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for exit-side review packet."
    }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$trailing = Invoke-ReadOnlyScript -ScriptName "prepare_trailing_stop_operator_review_packet_ssh.ps1" -Arguments ($commonArgs + @(
    "-LookbackDays", "$ReplayDays",
    "-Limit", "$ReplayLimit",
    "-RequireReady"
))

$strategy485 = Invoke-ReadOnlyScript -ScriptName "prepare_strategy485_operator_review_packet_ssh.ps1" -Arguments ($commonArgs + @(
    "-StrategyId", "$StrategyId",
    "-RequireReady"
))

$trailingStatus = Get-LastPrefixedValue -Text $trailing.Text -Prefix "trailing_stop_operator_packet_status="
$trailingPacketJson = Get-LastPrefixedValue -Text $trailing.Text -Prefix "trailing_stop_operator_review_packet="
$trailingPacket = Convert-JsonObjectOrNull -Value $trailingPacketJson
$trailingMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $trailing.Text -Prefix "trailing_stop_operator_packet_missing_requirements=")

$strategyStatus = Get-LastPrefixedValue -Text $strategy485.Text -Prefix "strategy485_operator_packet_status="
$strategyPacketJson = Get-LastPrefixedValue -Text $strategy485.Text -Prefix "strategy485_operator_review_packet="
$strategyPacket = Convert-JsonObjectOrNull -Value $strategyPacketJson
$strategyMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $strategy485.Text -Prefix "strategy485_operator_packet_missing_requirements=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($item in @($trailingMissing + $strategyMissing)) {
    Add-MissingRequirement -List $missingRequirements -Value ([string]$item)
}
if ($trailing.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop operator packet completed"
}
if ($strategy485.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy 485 operator packet completed"
}
if ($trailingStatus -ne "READY_FOR_OPERATOR_PACKET_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing stop PnL acceptance ready"
}
if ($strategyStatus -ne "READY_FOR_OPERATOR_PACKET_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy 485 aged negative-EV packet ready"
}

$trailingAcceptance = if ($null -ne $trailingPacket) { [string]$trailingPacket.acceptance } else { "N/A" }
$trailingImprovement = if ($null -ne $trailingPacket) { [string]$trailingPacket.improvementPct } else { "N/A" }
$trailingDeltaPnl = if ($null -ne $trailingPacket) { [string]$trailingPacket.acceptanceDeltaPnl } else { "N/A" }
$strategyDecision = if ($null -ne $strategyPacket) { $strategyPacket.strategy485PositionReviewDecision } else { $null }
$negativeEvCount = if ($null -ne $strategyDecision) { [string]$strategyDecision.negativeEvPositionCount } else { "N/A" }
$closeSuggestionCount = if ($null -ne $strategyDecision) { [string]$strategyDecision.closeOrModifySuggestionCount } else { "N/A" }
$ocoHealthOk = if ($null -ne $strategyDecision) { [string]$strategyDecision.ocoHealthOk } else { "N/A" }

$packetReady = $missingRequirements.Count -eq 0
$packetStatus = "NOT_READY"
$nextAction = "Resolve missing read-only exit-side evidence before drafting an operator review."
if ($packetReady) {
    $packetStatus = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
    $nextAction = "Attach this exit-side packet to a separate operator review; this does not authorize trailing enablement, close-position, or OCO modification."
}

$packet = [pscustomobject]@{
    packetType = "EXIT_SIDE_PROFIT_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    strategyId = $StrategyId
    trailingPacketStatus = $trailingStatus
    strategy485PacketStatus = $strategyStatus
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovement
    trailingDeltaPnl = $trailingDeltaPnl
    strategy485OcoHealthOk = $ocoHealthOk
    strategy485NegativeEvPositionCount = $negativeEvCount
    strategy485CloseOrModifySuggestionCount = $closeSuggestionCount
    trailingStopOperatorPacket = $trailingPacket
    strategy485OperatorPacket = $strategyPacket
    missingRequirements = @($missingRequirements)
    requiredOperatorChecks = @(
        "review trailing-stop acceptance separately from live trailing scheduler enablement",
        "review strategy 485 aged negative-EV positions separately from any close-position or OCO mutation",
        "confirm OCO health remains protected before any risk-reducing action is separately authorized",
        "confirm no entry/filter policy relaxation is bundled into exit-side review"
    )
    nextAction = $nextAction
    notAuthorization = "read-only exit-side profit review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
}

Write-Host "[exit-side-profit-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs prepare_trailing_stop_operator_review_packet_ssh.ps1 and prepare_strategy485_operator_review_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_trailing_stop_operator_review_packet_ssh.ps1 exitCode=$($trailing.ExitCode)"
Write-Host "source_packet=prepare_strategy485_operator_review_packet_ssh.ps1 exitCode=$($strategy485.ExitCode)"
Write-Host "trailing_stop_operator_packet_status=$trailingStatus"
Write-Host "strategy485_operator_packet_status=$strategyStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovement"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "strategy485_oco_health_ok=$ocoHealthOk"
Write-Host "strategy485_negative_ev_position_count=$negativeEvCount"
Write-Host "strategy485_close_or_modify_suggestion_count=$closeSuggestionCount"
Write-Host ("exit_side_profit_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("exit_side_profit_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "exit_side_profit_review_packet_status=$packetStatus"
Write-Host "exit_side_profit_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only exit-side profit review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
Write-Host "[exit-side-profit-review-packet] read-only check complete"

if ($RequireReady -and $packetStatus -ne "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION") {
    throw "Exit-side profit review packet is not ready: missing=$(@($missingRequirements) -join '; ')"
}
