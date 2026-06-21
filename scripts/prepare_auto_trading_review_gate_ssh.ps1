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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for auto-trading review gate."
}

$bundleScript = Join-Path $PSScriptRoot "smoke_auto_trading_review_bundle_ssh.ps1"
if (-not (Test-Path -LiteralPath $bundleScript)) {
    throw "Missing auto-trading review bundle: $bundleScript"
}

$bundleArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $bundleScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ReviewDays", [string]$ReviewDays,
    "-TinyLiveHours", [string]$TinyLiveHours
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $bundleOutput = & $powerShell.Source @bundleArgs 2>&1
    $bundleExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$bundleText = ($bundleOutput | Out-String)
$originDelta = Get-LastPrefixedValue -Text $bundleText -Prefix "  origin_delta_status="
$auditVerdict = Get-LastPrefixedValue -Text $bundleText -Prefix "  live_authorized_audit_verdict="
$strategy485Recommendation = Get-LastPrefixedValue -Text $bundleText -Prefix "  strategy485_position_risk_recommendation="
$strategy574Recommendation = Get-LastPrefixedValue -Text $bundleText -Prefix "  strategy574_policy_change_recommendation="
$tinyLiveStatus = Get-LastPrefixedValue -Text $bundleText -Prefix "  tiny_live_post_trade_status="
$reviewItemsJson = Get-LastPrefixedValue -Text $bundleText -Prefix "  review_items="
$bundleRecommendation = Get-LastPrefixedValue -Text $bundleText -Prefix "  auto_trading_review_recommendation="
$reviewItems = Convert-JsonArrayOrEmpty -Value $reviewItemsJson

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($bundleExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "auto-trading review bundle exited non-zero"
}
if ([string]::IsNullOrWhiteSpace($originDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_status missing"
}
if ([string]::IsNullOrWhiteSpace($auditVerdict)) {
    Add-MissingRequirement -List $missingRequirements -Value "live_authorized_audit_verdict missing"
}
if ([string]::IsNullOrWhiteSpace($reviewItemsJson)) {
    Add-MissingRequirement -List $missingRequirements -Value "review_items missing"
}
if ($originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    foreach ($required in @(
            "current strategy 485 OCO health",
            "current strategy 485 active-position EV",
            "TP stretch and timeout evidence",
            "separate operator approval before any position/OCO mutation"
        )) {
        Add-MissingRequirement -List $missingRequirements -Value $required
    }
}
if ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    foreach ($required in @(
            "current strategy 574 BUY candidate",
            "strategy 574 OCO preflight pass",
            "strategy 574 EV pass sample",
            "post-trade OCO protection evidence"
        )) {
        Add-MissingRequirement -List $missingRequirements -Value $required
    }
}
if ($tinyLiveStatus -eq "PENDING_NO_NEW_TINY_LIVE_EXECUTION") {
    Add-MissingRequirement -List $missingRequirements -Value "new TinyLive execution and post-trade evidence"
}

$deployRequired = ($originDelta -eq "RUNTIME_DRIFT" -or @($missingRequirements) -contains "deployed runtime current")
$operatorReviewPacketAllowed = $false
$gateStatus = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun this gate."

if ($bundleExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($reviewItemsJson)) {
    $gateStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only auto-trading bundle collection before drawing any review conclusion."
} elseif ($deployRequired) {
    $gateStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun the auto-trading review gate."
} elseif ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $operatorReviewPacketAllowed = $true
    $gateStatus = "READY_FOR_OPERATOR_POSITION_REVIEW_NOT_MUTATION"
    $nextAction = "Draft a separate operator review packet for strategy 485 position risk; no close or OCO modification is authorized."
} elseif ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    $gateStatus = "WAIT_STRATEGY574_THRESHOLD_CROSS"
    $nextAction = "Continue read-only strategy 574 observation until a current BUY candidate and hard-gate pass evidence exist."
} elseif ($tinyLiveStatus -eq "PENDING_NO_NEW_TINY_LIVE_EXECUTION") {
    $gateStatus = "WAIT_TINYLIVE_POST_TRADE_EVIDENCE"
    $nextAction = "Continue read-only TinyLive monitoring until new post-trade evidence exists."
} elseif ($auditVerdict -ne "LIVE_AUTHORIZED_MONITORING") {
    $gateStatus = "BLOCKED_REVIEW_LIVE_AUTHORIZED_AUDIT"
    $nextAction = "Resolve the live-authorized audit verdict before drafting any review packet."
} else {
    $gateStatus = "NO_OPERATOR_ACTION_FROM_BUNDLE"
    $nextAction = "No operator packet is routed by the current read-only bundle."
}

Write-Host "[auto-trading-review-gate] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs the auto-trading review bundle only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_auto_trading_review_bundle_ssh.ps1"
Write-Host "source_smoke_exit_code=$bundleExitCode"
Write-Host "origin_delta_status=$originDelta"
Write-Host "live_authorized_audit_verdict=$auditVerdict"
Write-Host "strategy485_position_risk_recommendation=$strategy485Recommendation"
Write-Host "strategy574_policy_change_recommendation=$strategy574Recommendation"
Write-Host "tiny_live_post_trade_status=$tinyLiveStatus"
Write-Host "auto_trading_review_recommendation=$bundleRecommendation"
Write-Host "deploy_required_before_auto_trading_review=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "operator_review_packet_allowed=$($operatorReviewPacketAllowed.ToString().ToLowerInvariant())"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host ("auto_trading_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "auto_trading_review_gate_status=$gateStatus"
Write-Host "auto_trading_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only gate only; does not authorize close-position, OCO modification, pre-buying, TinyLive order execution, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[auto-trading-review-gate] read-only check complete"

if ($RequireReady -and -not $operatorReviewPacketAllowed) {
    throw "Auto-trading review gate is not ready: $gateStatus; missing=$(@($missingRequirements) -join '; ')"
}
