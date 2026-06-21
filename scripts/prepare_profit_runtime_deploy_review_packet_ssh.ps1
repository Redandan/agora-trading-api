param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$TinyLiveHours = 720,
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
        throw "$Name contains unsupported characters for read-only packet arguments."
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

function Invoke-LocalReadOnlyScript {
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
        throw "Unable to find powershell or pwsh for profit runtime deploy review packet."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String)
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
if ($ReviewDays -lt 1 -or $ReviewDays -gt 180) {
    throw "ReviewDays must be between 1 and 180."
}
if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 720) {
    throw "TinyLiveHours must be between 1 and 720."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$originArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)
$origin = Invoke-LocalReadOnlyScript -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments $originArgs

$profitArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ReviewDays", [string]$ReviewDays,
    "-TinyLiveHours", [string]$TinyLiveHours
)
$profit = Invoke-LocalReadOnlyScript -ScriptName "smoke_post_deploy_profit_validation_ssh.ps1" -Arguments $profitArgs

$originDelta = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_delta_status="
$serverWorktreeCommit = Get-LastPrefixedValue -Text $origin.Text -Prefix "server_worktree_commit="
$originMainCommit = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_main_commit="
$originRuntimeDeltaFiles = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_runtime_delta_files="
$originRuntimeDeltaPathsJson = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_runtime_delta_paths="
$originRuntimeDeltaPaths = Convert-JsonArrayOrEmpty -Value $originRuntimeDeltaPathsJson
$originNextAction = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_delta_next_action="

$postDeployStatus = Get-LastPrefixedValue -Text $profit.Text -Prefix "post_deploy_profit_validation_status="
$postDeployNextAction = Get-LastPrefixedValue -Text $profit.Text -Prefix "post_deploy_profit_validation_next_action="
$deployRequired = Get-LastPrefixedValue -Text $profit.Text -Prefix "deploy_required_before_post_deploy_profit_validation="
$monthlyPnlTotalUsdt = Get-LastPrefixedValue -Text $profit.Text -Prefix "monthlyPnlTotalUsdt="
$topProfitCandidate = Get-LastPrefixedValue -Text $profit.Text -Prefix "top_profit_improvement_candidate="
$runtimeImpactJson = Get-LastPrefixedValue -Text $profit.Text -Prefix "origin_runtime_delta_impact="
$runtimeImpact = Convert-JsonArrayOrEmpty -Value $runtimeImpactJson
$postDeployMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $profit.Text -Prefix "post_deploy_profit_validation_missing_requirements=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($origin.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "origin-delta classifier completed"
}
if ($profit.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "post-deploy profit validation completed"
}
if ([string]::IsNullOrWhiteSpace($originDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_status present"
}
if ([string]::IsNullOrWhiteSpace($postDeployStatus)) {
    Add-MissingRequirement -List $missingRequirements -Value "post_deploy_profit_validation_status present"
}
if ($originDelta -eq "NO_LOCAL_EVIDENCE") {
    Add-MissingRequirement -List $missingRequirements -Value "local git evidence for origin delta"
}
if ($originDelta -eq "RUNTIME_DRIFT" -and @($originRuntimeDeltaPaths).Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "runtime delta paths present"
}
if ($deployRequired -ne "true" -and $originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "post-deploy profit validation deploy-required marker"
}
if (@($postDeployMissing) -notcontains "deployed runtime current" -and $originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "post-deploy missing requirements include deployed runtime current"
}

$packetStatus = "NOT_READY"
$packetNextAction = "Resolve missing read-only evidence before drafting any deploy review."
if ($origin.ExitCode -ne 0 -or $profit.ExitCode -ne 0 -or $originDelta -eq "NO_LOCAL_EVIDENCE") {
    $packetStatus = "NO_EVIDENCE"
    $packetNextAction = "Fix read-only metadata/profit evidence collection, then rerun this packet."
} elseif ($originDelta -eq "RUNTIME_DRIFT" -and $postDeployStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME" -and $missingRequirements.Count -eq 0) {
    $packetStatus = "READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED"
    $packetNextAction = "Attach this packet to a separate deploy authorization request, then deploy and rerun post-deploy profit validation."
} elseif ($originDelta -eq "CURRENT_ORIGIN_MAIN" -or $originDelta -eq "DOCS_TOOLING_ONLY_DRIFT") {
    $packetStatus = "NO_RUNTIME_DEPLOY_REQUIRED_FROM_CURRENTNESS"
    $packetNextAction = "Rerun post-deploy profit validation and packet preflights; do not deploy solely from this packet."
} elseif ($postDeployStatus -eq "READY_FOR_READ_ONLY_PROFIT_REVIEW_NOT_LIVE") {
    $packetStatus = "PROFIT_REVIEW_READY_NOT_DEPLOY_PACKET"
    $packetNextAction = "Use the profit review packets; this deploy-review packet is no longer the next action."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_RUNTIME_DEPLOY_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    originDeltaStatus = $originDelta
    serverWorktreeCommit = $serverWorktreeCommit
    originMainCommit = $originMainCommit
    originRuntimeDeltaFiles = if ([string]::IsNullOrWhiteSpace($originRuntimeDeltaFiles)) { 0 } else { [int]$originRuntimeDeltaFiles }
    originRuntimeDeltaPaths = @($originRuntimeDeltaPaths)
    originRuntimeDeltaImpact = @($runtimeImpact)
    monthlyPnlTotalUsdt = $monthlyPnlTotalUsdt
    topProfitImprovementCandidate = $topProfitCandidate
    deployRequiredBeforePostDeployProfitValidation = ($deployRequired -eq "true")
    postDeployProfitValidationStatus = $postDeployStatus
    postDeployMissingRequirements = @($postDeployMissing)
    requiredPostDeployReadOnlyCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_post_deploy_profit_validation_ssh.ps1",
        ".\scripts\prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady",
        ".\scripts\prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $packetNextAction
    notAuthorization = "read-only deploy review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax policy, place orders, modify OCO, or mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
}

Write-Host "[profit-runtime-deploy-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs origin-delta classifier and post-deploy profit validation only; no deploy, restart, nginx reload, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or live policy state changed."
Write-Host "source_smoke=smoke_live_origin_delta_local.ps1"
Write-Host "source_smoke=smoke_post_deploy_profit_validation_ssh.ps1"
Write-Host "origin_delta_exit_code=$($origin.ExitCode)"
Write-Host "post_deploy_profit_validation_exit_code=$($profit.ExitCode)"
Write-Host "origin_delta_status=$originDelta"
Write-Host "server_worktree_commit=$serverWorktreeCommit"
Write-Host "origin_main_commit=$originMainCommit"
Write-Host "origin_runtime_delta_files=$originRuntimeDeltaFiles"
Write-Host ("origin_runtime_delta_paths=" + (ConvertTo-Json -Compress @($originRuntimeDeltaPaths)))
Write-Host ("origin_runtime_delta_impact=" + (ConvertTo-Json -Compress @($runtimeImpact)))
Write-Host "monthlyPnlTotalUsdt=$monthlyPnlTotalUsdt"
Write-Host "top_profit_improvement_candidate=$topProfitCandidate"
Write-Host "deploy_required_before_post_deploy_profit_validation=$deployRequired"
Write-Host "post_deploy_profit_validation_status=$postDeployStatus"
Write-Host "origin_delta_next_action=$originNextAction"
Write-Host "post_deploy_profit_validation_next_action=$postDeployNextAction"
Write-Host ("profit_runtime_deploy_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_runtime_deploy_review_packet=" + (ConvertTo-Json -Compress -Depth 6 $packet))
Write-Host "profit_runtime_deploy_packet_status=$packetStatus"
Write-Host "profit_runtime_deploy_packet_next_action=$packetNextAction"
Write-Host "notAuthorization=read-only deploy review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax policy, place orders, modify OCO, or mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
Write-Host "[profit-runtime-deploy-review-packet] read-only check complete"

if ($RequireReady -and $packetStatus -ne "READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED" -and $packetStatus -ne "NO_RUNTIME_DEPLOY_REQUIRED_FROM_CURRENTNESS") {
    throw "Profit runtime deploy review packet is not ready: $packetStatus; missing=$(@($missingRequirements) -join '; ')"
}
