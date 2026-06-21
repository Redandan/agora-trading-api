param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 485,
    [int]$Days = 30,
    [int]$PositionAgeWarnDays = 5,
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
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Get-LastPrefixedValue {
    param(
        [string]$Text,
        [string]$Prefix
    )

    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Invoke-LocalSmoke {
    param(
        [string]$ScriptName,
        [string[]]$Arguments
    )

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy 485 gate."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [PSCustomObject]@{
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
if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}
if ($Days -lt 1 -or $Days -gt 180) {
    throw "Days must be between 1 and 180."
}
if ($PositionAgeWarnDays -lt 1 -or $PositionAgeWarnDays -gt 90) {
    throw "PositionAgeWarnDays must be between 1 and 90."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$common = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)
$origin = Invoke-LocalSmoke -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments $common

$positionArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$StrategyId,
    "-Days", [string]$Days,
    "-PositionAgeWarnDays", [string]$PositionAgeWarnDays
)
$position = Invoke-LocalSmoke -ScriptName "smoke_strategy485_position_risk_ssh.ps1" -Arguments $positionArgs

$originDelta = Get-LastPrefixedValue -Text $origin.Text -Prefix "origin_delta_status="
$recommendation = Get-LastPrefixedValue -Text $position.Text -Prefix "  strategy485_position_risk_recommendation="
$openPositions = Get-LastPrefixedValue -Text $position.Text -Prefix "  openStrategy485Positions="
$negativeEvPositions = Get-LastPrefixedValue -Text $position.Text -Prefix "  negativeEvPositions="
$closeOrModifySuggestions = Get-LastPrefixedValue -Text $position.Text -Prefix "  closeOrModifySuggestions="
$positionTimeoutEvents = Get-LastPrefixedValue -Text $position.Text -Prefix "  positionTimeoutEvents="

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($origin.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "origin-delta classifier completed"
}
if ($position.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy 485 position-risk smoke completed"
}
if ($originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ([string]::IsNullOrWhiteSpace($recommendation)) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485_position_risk_recommendation present"
}
if ($recommendation -eq "FIX_OCO_PROTECTION_FIRST") {
    Add-MissingRequirement -List $missingRequirements -Value "OCO health restored before profit review"
}
if ($recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    foreach ($required in @(
            "fresh OCO health OK evidence",
            "fresh active-position EV evidence",
            "TP stretch and timeout evidence",
            "separate operator approval before close/OCO mutation"
        )) {
        Add-MissingRequirement -List $missingRequirements -Value $required
    }
}

$deployRequired = $originDelta -eq "RUNTIME_DRIFT"
$operatorReviewAllowed = $false
$gateStatus = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun this gate."
if ($origin.ExitCode -ne 0 -or $position.ExitCode -ne 0) {
    $gateStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only SSH smoke collection before drawing a strategy 485 position-risk conclusion."
} elseif ($deployRequired) {
    $gateStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun strategy 485 position-risk gate before drafting an operator review packet."
} elseif ($recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $operatorReviewAllowed = $true
    $gateStatus = "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION"
    $nextAction = "Draft a separate operator review packet with fresh evidence; this gate does not authorize close or OCO modification."
} elseif ($recommendation -eq "NO_OPEN_STRATEGY485_POSITION" -or $recommendation -eq "NO_POSITION_RISK_ACTION") {
    $gateStatus = "NO_POSITION_RISK_ACTION"
    $nextAction = "No strategy 485 position-risk packet is needed from current evidence."
} elseif ($recommendation -eq "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED" -or $recommendation -eq "WATCH_TP_STRETCH") {
    $gateStatus = "WATCH_ONLY"
    $nextAction = "Continue read-only monitoring; do not draft an action packet yet."
} elseif ($recommendation -eq "FIX_OCO_PROTECTION_FIRST") {
    $gateStatus = "BLOCKED_OCO_PROTECTION_FIRST"
    $nextAction = "Review OCO protection through a separately authorized safety path before any profit optimization."
}

Write-Host "[strategy485-position-review-gate] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs origin-delta classifier and strategy 485 position-risk smoke only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_strategy485_position_risk_ssh.ps1"
Write-Host "origin_delta_classifier=smoke_live_origin_delta_local.ps1"
Write-Host "origin_delta_exit_code=$($origin.ExitCode)"
Write-Host "position_risk_exit_code=$($position.ExitCode)"
Write-Host "origin_delta_status=$originDelta"
Write-Host "strategyId=$StrategyId"
Write-Host "symbol=$Symbol"
Write-Host "openStrategy485Positions=$openPositions"
Write-Host "negativeEvPositions=$negativeEvPositions"
Write-Host "closeOrModifySuggestions=$closeOrModifySuggestions"
Write-Host "positionTimeoutEvents=$positionTimeoutEvents"
Write-Host "strategy485_position_risk_recommendation=$recommendation"
Write-Host "deploy_required_before_strategy485_review=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "operator_review_packet_allowed=$($operatorReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host ("strategy485_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "strategy485_position_review_gate_status=$gateStatus"
Write-Host "strategy485_position_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only gate only; does not authorize closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[strategy485-position-review-gate] read-only check complete"

if ($RequireReady -and -not $operatorReviewAllowed) {
    throw "Strategy 485 position review gate is not ready: $gateStatus; missing=$(@($missingRequirements) -join '; ')"
}
