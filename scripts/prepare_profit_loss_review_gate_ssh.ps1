param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$BlockedDays = 14,
    [int]$MissedHours = 168,
    [int]$TrailingLimit = 500,
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

function Convert-NullableDouble {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "N/A") {
        return $null
    }
    $parsed = 0.0
    if ([double]::TryParse($Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        return $parsed
    }
    return $null
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
if ($BlockedDays -lt 1 -or $BlockedDays -gt 60) {
    throw "BlockedDays must be between 1 and 60."
}
if ($MissedHours -lt 1 -or $MissedHours -gt 1440) {
    throw "MissedHours must be between 1 and 1440."
}
if ($TrailingLimit -lt 1 -or $TrailingLimit -gt 1000) {
    throw "TrailingLimit must be between 1 and 1000."
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
    throw "Unable to find powershell or pwsh for profit loss review gate."
}

$originScript = Join-Path $PSScriptRoot "smoke_live_origin_delta_local.ps1"
$profitScript = Join-Path $PSScriptRoot "smoke_profit_candidate_review_ssh.ps1"
foreach ($script in @($originScript, $profitScript)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Missing required read-only smoke: $script"
    }
}

function Invoke-ChildSmoke {
    param(
        [string]$Name,
        [string[]]$Arguments
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$text"
    }
    return $text
}

$originText = Invoke-ChildSmoke -Name "origin-delta" -Arguments @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $originScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)
$profitText = Invoke-ChildSmoke -Name "profit-candidate-review" -Arguments @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $profitScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ReviewDays", [string]$ReviewDays,
    "-BlockedDays", [string]$BlockedDays,
    "-MissedHours", [string]$MissedHours,
    "-TrailingLimit", [string]$TrailingLimit
)

$originDelta = Get-LastPrefixedValue -Text $originText -Prefix "origin_delta_status="
$monthlyPnlTotalUsdtText = Get-LastPrefixedValue -Text $profitText -Prefix "  monthlyPnlTotalUsdt="
$monthlyPnlTotalUsdt = Convert-NullableDouble -Value $monthlyPnlTotalUsdtText
$candidateItemsJson = Get-LastPrefixedValue -Text $profitText -Prefix "  profit_candidate_items="
$candidateItems = Convert-JsonArrayOrEmpty -Value $candidateItemsJson
$profitRecommendation = Get-LastPrefixedValue -Text $profitText -Prefix "  profit_candidate_review_recommendation="
$missedOpportunityStatus = Get-LastPrefixedValue -Text $profitText -Prefix "  missedOpportunityStatus="
$falseBlockRiskCount = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  falseBlockRiskCount=")
$suspiciousNoBuyCount = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  suspiciousNoBuyCount=")
$dataFreshnessFalseKillPct = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  dataFreshnessFalseKillPct=")
$dataFreshnessAvgRetPct = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  dataFreshnessAvgRetPct=")
$trailingReplayAcceptance = Get-LastPrefixedValue -Text $profitText -Prefix "  trailingReplayAcceptance="
$shadowReadyLowSampleCount = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  shadowReadyLowSampleCount=")
$shadowReadyActivationCount = Convert-NullableDouble -Value (Get-LastPrefixedValue -Text $profitText -Prefix "  shadowReadyActivationCount=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ([string]::IsNullOrWhiteSpace($originDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_status missing"
}
if ([string]::IsNullOrWhiteSpace($candidateItemsJson)) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_candidate_items missing"
}
if ([string]::IsNullOrWhiteSpace($profitRecommendation)) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_candidate_review_recommendation missing"
}
if ($null -eq $monthlyPnlTotalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "monthlyPnlTotalUsdt missing"
}
if ($originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($candidateItems -contains "REVIEW_DATAFRESHNESS_FALSE_KILL_IN_SHADOW") {
    foreach ($required in @(
            "fresh DataFreshness replayCandidateId rows",
            "entry/TP/SL candidate snapshot",
            "EV and OCO preflight snapshots",
            "shadow replay removing only DataFreshnessGuard"
        )) {
        Add-MissingRequirement -List $missingRequirements -Value $required
    }
}
if ($candidateItems -contains "REVIEW_MISSED_OPPORTUNITY_HOLD_ROWS") {
    foreach ($required in @(
            "row-level missed-opportunity evidence",
            "no-buy truth-table classification",
            "hard-gate preservation review"
        )) {
        Add-MissingRequirement -List $missingRequirements -Value $required
    }
}
if ($candidateItems -contains "DO_NOT_ENABLE_TRAILING_STOP_OVERLAY") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing replay acceptance PASS"
}
if ($candidateItems -contains "COLLECT_MORE_SHADOW_SAMPLES_BEFORE_ACTIVATION") {
    Add-MissingRequirement -List $missingRequirements -Value "sufficient shadow-ready sample count"
}

$deployRequired = ($originDelta -eq "RUNTIME_DRIFT" -or @($missingRequirements) -contains "deployed runtime current")
$lossSourceReviewAllowed = $false
$gateStatus = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun this gate."

if ([string]::IsNullOrWhiteSpace($candidateItemsJson) -or [string]::IsNullOrWhiteSpace($profitRecommendation)) {
    $gateStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only profit candidate collection before drawing any loss-source conclusion."
} elseif ($deployRequired) {
    $gateStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun profit loss review gate."
} elseif ($monthlyPnlTotalUsdt -lt 0 -and $candidateItems.Count -gt 0) {
    $lossSourceReviewAllowed = $true
    $gateStatus = "READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE"
    $nextAction = "Draft a separate read-only loss-source review packet with candidate evidence and preserved hard gates."
} elseif ($monthlyPnlTotalUsdt -ge 0 -and $candidateItems.Count -gt 0) {
    $gateStatus = "OBSERVE_CANDIDATES_NO_LOSS_PACKET"
    $nextAction = "Continue read-only monitoring; current monthly PnL is not negative enough to route a loss-source packet."
} else {
    $gateStatus = "NO_LOSS_SOURCE_ACTION_FROM_CURRENT_EVIDENCE"
    $nextAction = "No loss-source packet is routed by the current read-only evidence."
}

Write-Host "[profit-loss-review-gate] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs origin-delta and profit-candidate review only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_live_origin_delta_local.ps1"
Write-Host "source_smoke=smoke_profit_candidate_review_ssh.ps1"
Write-Host "origin_delta_status=$originDelta"
Write-Host "monthlyPnlTotalUsdt=$monthlyPnlTotalUsdtText"
Write-Host "profit_candidate_review_recommendation=$profitRecommendation"
Write-Host "missedOpportunityStatus=$missedOpportunityStatus"
Write-Host "falseBlockRiskCount=$falseBlockRiskCount"
Write-Host "suspiciousNoBuyCount=$suspiciousNoBuyCount"
Write-Host "dataFreshnessFalseKillPct=$dataFreshnessFalseKillPct"
Write-Host "dataFreshnessAvgRetPct=$dataFreshnessAvgRetPct"
Write-Host "trailingReplayAcceptance=$trailingReplayAcceptance"
Write-Host "shadowReadyLowSampleCount=$shadowReadyLowSampleCount"
Write-Host "shadowReadyActivationCount=$shadowReadyActivationCount"
Write-Host ("profit_loss_candidate_items=" + (ConvertTo-Json -Compress @($candidateItems)))
Write-Host "deploy_required_before_profit_loss_review=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "loss_source_review_allowed=$($lossSourceReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host ("profit_loss_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "profit_loss_review_gate_status=$gateStatus"
Write-Host "profit_loss_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only gate only; does not authorize DataFreshnessGuard relaxation, close-position, OCO modification, pre-buying, TinyLive order execution, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[profit-loss-review-gate] read-only check complete"

if ($RequireReady -and -not $lossSourceReviewAllowed) {
    throw "Profit loss review gate is not ready: $gateStatus; missing=$(@($missingRequirements) -join '; ')"
}
