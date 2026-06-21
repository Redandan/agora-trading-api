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

if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 720) {
    throw "TinyLiveHours must be between 1 and 720."
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

Write-Host "[profit-improvement-review-bundle] read-only review bundle"
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
    "-AppDir", $AppDir
)
$profitCandidate = Invoke-Smoke -Name "profit-candidate-review" -ScriptName "smoke_profit_candidate_review_ssh.ps1" -Arguments ($common + @("-ReviewDays", "$ReviewDays"))
$dataFreshnessFalseKill = Invoke-Smoke -Name "data-freshness-false-kill" -ScriptName "smoke_data_freshness_false_kill_review_ssh.ps1" -Arguments ($common + @("-LongDays", "14", "-ReviewDays", "7"))
$dataFreshnessExecutability = Invoke-Smoke -Name "data-freshness-executability" -ScriptName "smoke_data_freshness_executability_review_ssh.ps1" -Arguments $common
$strategy485 = Invoke-Smoke -Name "strategy485-position-risk" -ScriptName "smoke_strategy485_position_risk_ssh.ps1" -Arguments ($common + @("-Days", "$ReviewDays"))
$strategy574 = Invoke-Smoke -Name "strategy574-signal-governance" -ScriptName "smoke_strategy574_signal_governance_ssh.ps1" -Arguments $common
$tinyLive = Invoke-Smoke -Name "tiny-live-post-trade" -ScriptName "smoke_tiny_live_post_trade_ssh.ps1" -Arguments ($common + @("-Hours", "$TinyLiveHours"))

$originDelta = Get-Marker -Text $origin -Prefix "origin_delta_status="
$profitCandidateRecommendation = Get-Marker -Text $profitCandidate -Prefix "  profit_candidate_review_recommendation="
$dataFreshnessFalseKillRecommendation = Get-Marker -Text $dataFreshnessFalseKill -Prefix "  data_freshness_false_kill_recommendation="
$dataFreshnessExecutabilityRecommendation = Get-Marker -Text $dataFreshnessExecutability -Prefix "  data_freshness_executability_recommendation="
$strategy485Recommendation = Get-Marker -Text $strategy485 -Prefix "  strategy485_position_risk_recommendation="
$strategy574Recommendation = Get-Marker -Text $strategy574 -Prefix "  policy_change_recommendation="
$tinyLiveStatus = Get-Marker -Text $tinyLive -Prefix "post_trade_status="

$reviewItems = New-Object System.Collections.Generic.List[string]
if ($originDelta -eq "RUNTIME_DRIFT") {
    $reviewItems.Add("DEPLOY_CURRENT_RUNTIME_BEFORE_PROFIT_REVIEW")
}
if ($profitCandidateRecommendation -eq "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY") {
    $reviewItems.Add("REVIEW_DATAFRESHNESS_FALSE_KILL")
}
if ($dataFreshnessFalseKillRecommendation -eq "REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE") {
    $reviewItems.Add("REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE")
}
if ($dataFreshnessExecutabilityRecommendation -eq "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY") {
    $reviewItems.Add("COLLECT_EXECUTABILITY_COUNTERFACTUAL_BEFORE_POLICY_CHANGE")
}
if ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $reviewItems.Add("REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS")
}
if ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    $reviewItems.Add("KEEP_STRATEGY574_HARD_GATES_WAIT_THRESHOLD_CROSS")
}
if ($tinyLiveStatus -eq "PENDING_NO_NEW_TINY_LIVE_EXECUTION") {
    $reviewItems.Add("WAIT_FOR_NEW_TINYLIVE_EXECUTION_SAMPLE")
}

if ($reviewItems -contains "COLLECT_EXECUTABILITY_COUNTERFACTUAL_BEFORE_POLICY_CHANGE") {
    $recommendation = "COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE"
} elseif ($reviewItems -contains "REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS") {
    $recommendation = "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK"
} elseif ($reviewItems -contains "WAIT_FOR_NEW_TINYLIVE_EXECUTION_SAMPLE") {
    $recommendation = "CONTINUE_TINYLIVE_MONITORING"
} elseif ($reviewItems.Count -gt 0) {
    $recommendation = "REVIEW_PROFIT_IMPROVEMENT_ITEMS"
} else {
    $recommendation = "NO_PROFIT_IMPROVEMENT_ACTION_FROM_BUNDLE"
}

Write-Host ""
Write-Host "Profit Improvement Bundle Summary:"
Write-Host "  origin_delta_status=$originDelta"
Write-Host "  profit_candidate_review_recommendation=$profitCandidateRecommendation"
Write-Host "  data_freshness_false_kill_recommendation=$dataFreshnessFalseKillRecommendation"
Write-Host "  data_freshness_executability_recommendation=$dataFreshnessExecutabilityRecommendation"
Write-Host "  strategy485_position_risk_recommendation=$strategy485Recommendation"
Write-Host "  strategy574_policy_change_recommendation=$strategy574Recommendation"
Write-Host "  tiny_live_post_trade_status=$tinyLiveStatus"
Write-Host ("  profit_improvement_review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host "  profit_improvement_bundle_recommendation=$recommendation"
Write-Host "  notAuthorization=read-only review evidence only; does not authorize DataFreshnessGuard relaxation, closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host ""
Write-Host "[profit-improvement-review-bundle] OK read-only check complete"
