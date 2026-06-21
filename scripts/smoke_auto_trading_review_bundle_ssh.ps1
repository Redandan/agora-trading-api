param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$TinyLiveHours = 720
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 4320) {
    throw "TinyLiveHours must be between 1 and 4320."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$scriptDir = $PSScriptRoot

function Invoke-Smoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if (-not $powerShell) {
        throw "PowerShell is not available for child smoke invocation."
    }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exit = $LASTEXITCODE
    $text = ($output | Out-String).Trim()

    Write-Host ""
    Write-Host "===== $Name ====="
    if ($text.Length -gt 5000) {
        Write-Host ($text.Substring(0, 5000) + "`n...[truncated]")
    } else {
        Write-Host $text
    }
    Write-Host "===== $Name exitCode=$exit ====="

    if ($exit -ne 0) {
        throw "$Name failed with exit code $exit"
    }
    return $text
}

function Get-Marker {
    param([string]$Text, [string]$Prefix)
    $matches = [regex]::Matches($Text, "(?m)^$([regex]::Escape($Prefix))(.+)$")
    if ($matches.Count -eq 0) {
        return ""
    }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Convert-MarkerJsonOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "failed to parse marker JSON: $($_.Exception.Message)"
    }
}

Write-Host "[auto-trading-review-bundle] read-only review bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol reviewDays=$ReviewDays tinyLiveHours=$TinyLiveHours"

$common = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$origin = Invoke-Smoke -Name "origin-delta" -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile
)
$audit = Invoke-Smoke -Name "live-authorized-audit" -ScriptName "audit_live_readiness_ssh.ps1" -Arguments ($common + @("-LiveAuthorized"))
$strategy485 = Invoke-Smoke -Name "strategy485-position-risk" -ScriptName "smoke_strategy485_position_risk_ssh.ps1" -Arguments ($common + @("-Days", "$ReviewDays"))
$strategy574 = Invoke-Smoke -Name "strategy574-signal-governance" -ScriptName "smoke_strategy574_signal_governance_ssh.ps1" -Arguments $common
$tinyLive = Invoke-Smoke -Name "tiny-live-post-trade" -ScriptName "smoke_tiny_live_post_trade_ssh.ps1" -Arguments ($common + @("-Hours", "$TinyLiveHours"))

$originDelta = Get-Marker -Text $origin -Prefix "origin_delta_status="
$auditVerdict = Get-Marker -Text $audit -Prefix "verdict="
$strategy485Recommendation = Get-Marker -Text $strategy485 -Prefix "  strategy485_position_risk_recommendation="
$strategy485DecisionJson = Get-Marker -Text $strategy485 -Prefix "  strategy485_position_review_decision="
$strategy485Decision = Convert-MarkerJsonOrNull -Value $strategy485DecisionJson
$strategy574Recommendation = Get-Marker -Text $strategy574 -Prefix "  policy_change_recommendation="
$tinyLiveStatus = Get-Marker -Text $tinyLive -Prefix "post_trade_status="

$reviewItems = New-Object System.Collections.Generic.List[string]
if ($originDelta -eq "RUNTIME_DRIFT") {
    $reviewItems.Add("DEPLOY_CURRENT_RUNTIME_BEFORE_LIVE_REVIEW")
}
if ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $reviewItems.Add("REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS")
}
if ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    $reviewItems.Add("KEEP_STRATEGY574_HARD_GATES_WAIT_THRESHOLD_CROSS")
}
if ($tinyLiveStatus -eq "PENDING_NO_NEW_TINY_LIVE_EXECUTION") {
    $reviewItems.Add("WAIT_FOR_NEW_TINY_LIVE_EXECUTION")
}
if ($auditVerdict -ne "LIVE_AUTHORIZED_MONITORING") {
    $reviewItems.Add("REVIEW_LIVE_AUTHORIZED_AUDIT_VERDICT")
}

if ($reviewItems.Count -eq 0) {
    $recommendation = "NO_REVIEW_ACTION_FROM_BUNDLE"
} elseif ($reviewItems -contains "REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS") {
    $recommendation = "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK"
} elseif ($reviewItems -contains "WAIT_FOR_NEW_TINY_LIVE_EXECUTION") {
    $recommendation = "CONTINUE_TINYLIVE_MONITORING"
} else {
    $recommendation = "REVIEW_BUNDLE_ITEMS"
}

Write-Host ""
Write-Host "Bundle Summary:"
Write-Host "  origin_delta_status=$originDelta"
Write-Host "  live_authorized_audit_verdict=$auditVerdict"
Write-Host "  strategy485_position_risk_recommendation=$strategy485Recommendation"
Write-Host "  strategy485_position_review_decision=$strategy485DecisionJson"
if ($null -ne $strategy485Decision) {
    Write-Host "  strategy485_negative_ev_positions=$($strategy485Decision.negativeEvPositionCount)"
    Write-Host "  strategy485_close_or_modify_suggestions=$($strategy485Decision.closeOrModifySuggestionCount)"
    Write-Host "  strategy485_position_timeout_events=$($strategy485Decision.positionTimeoutEventCount)"
}
Write-Host "  strategy574_policy_change_recommendation=$strategy574Recommendation"
Write-Host "  tiny_live_post_trade_status=$tinyLiveStatus"
Write-Host ("  review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host "  auto_trading_review_recommendation=$recommendation"
Write-Host "  notAuthorization=read-only review evidence only; does not authorize closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, or policy relaxation"
Write-Host ""
Write-Host "[auto-trading-review-bundle] OK read-only check complete"
