param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [string]$IntervalCode = "1h",
    [int]$ReviewDays = 7,
    [int]$Limit = 20,
    [decimal]$NearThresholdMaxGap = 2,
    [string]$SignalEvalNoBuyLogPath = "",
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
        throw "$Name contains unsupported characters for strategy574 near-threshold packet arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([string]$Value)
    $parsed = [decimal]0
    if ([decimal]::TryParse($Value, [System.Globalization.NumberStyles]::Any, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-SignalEvalSmoke {
    param([string[]]$Arguments)
    $scriptPath = Join-Path $PSScriptRoot "smoke_signal_eval_no_buy_generation_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) { throw "Missing read-only script: $scriptPath" }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy574 near-threshold packet." }

    Write-Host "[strategy574-near-threshold-decision-packet] child_start script=smoke_signal_eval_no_buy_generation_ssh.ps1"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[strategy574-near-threshold-decision-packet] child_complete script=smoke_signal_eval_no_buy_generation_ssh.ps1 exitCode=$exitCode"
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
        Source = "fresh-ssh"
    }
}

if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) { throw "ReviewDays must be between 1 and 30." }
if ($Limit -lt 1 -or $Limit -gt 50) { throw "Limit must be between 1 and 50." }
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($NearThresholdMaxGap -lt 0 -or $NearThresholdMaxGap -gt 20) { throw "NearThresholdMaxGap must be between 0 and 20." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode

if ([string]::IsNullOrWhiteSpace($SignalEvalNoBuyLogPath)) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost, set AGORA_SSH_HOST, or pass -SignalEvalNoBuyLogPath." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey, set AGORA_SSH_KEY, or pass -SignalEvalNoBuyLogPath." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
    $signalEval = Invoke-SignalEvalSmoke -Arguments @(
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir,
        "-EnvFile", $EnvFile,
        "-Symbol", $Symbol,
        "-ReviewDays", [string]$ReviewDays,
        "-Limit", [string]$Limit
    )
} else {
    if (-not (Test-Path -LiteralPath $SignalEvalNoBuyLogPath)) { throw "SignalEvalNoBuyLogPath not found: $SignalEvalNoBuyLogPath" }
    $signalEval = [pscustomobject]@{
        Text = Get-Content -Raw -LiteralPath $SignalEvalNoBuyLogPath
        ExitCode = 0
        Source = "existing-log"
    }
}

$recommendation = Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  signal_eval_no_buy_generation_recommendation="
$signalEvalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  signal_eval_rows=")
$buyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  buy_like_signal_eval_rows=")
$noBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  no_buy_signal_eval_rows=")
$strategyDecisionRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  strategy_decision_context_rows=")
$executionHoldRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEval.Text -Prefix "  execution_hold_rows=")

$thresholdRows = @()
$linePattern = "^\s*-\s+strategy=(?<strategy>\d+)\s+interval=(?<interval>\S+)\s+indicator=(?<indicator>\S+)\s+count=(?<count>\d+)\s+avg_mih_value=(?<value>-?[0-9.]+)\s+avg_buy_threshold=(?<threshold>-?[0-9.]+)\s+avg_buy_gap=(?<avgGap>-?[0-9.]+)\s+min_buy_gap=(?<minGap>-?[0-9.]+)"
foreach ($line in ($signalEval.Text -split "`r?`n")) {
    $match = [regex]::Match($line, $linePattern)
    if (-not $match.Success) { continue }
    if ([int]$match.Groups["strategy"].Value -ne $StrategyId) { continue }
    if ($match.Groups["interval"].Value -ne $IntervalCode) { continue }
    $thresholdRows += [pscustomobject]@{
        strategyId = [int]$match.Groups["strategy"].Value
        intervalCode = $match.Groups["interval"].Value
        indicator = $match.Groups["indicator"].Value
        count = [int]$match.Groups["count"].Value
        avgMihValue = Get-DecimalValue -Value $match.Groups["value"].Value
        avgBuyThreshold = Get-DecimalValue -Value $match.Groups["threshold"].Value
        avgBuyGap = Get-DecimalValue -Value $match.Groups["avgGap"].Value
        minBuyGap = Get-DecimalValue -Value $match.Groups["minGap"].Value
        sourceLine = $line.Trim()
    }
}

$bestRow = @($thresholdRows | Sort-Object @{ Expression = { if ($null -eq $_.minBuyGap) { [decimal]999999 } else { [math]::Abs([decimal]$_.minBuyGap) } } }, @{ Expression = { -1 * $_.count } } | Select-Object -First 1)
$nearThreshold = $false
if ($bestRow -and $null -ne $bestRow[0].minBuyGap) {
    $nearThreshold = ([decimal]$bestRow[0].minBuyGap -ge 0 -and [decimal]$bestRow[0].minBuyGap -le $NearThresholdMaxGap)
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($signalEval.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "signal-eval no-buy smoke completed" }
if ($signalEvalRows -le 0) { Add-Unique -List $missingRequirements -Value "recent SIGNAL_EVAL rows present" }
if ($buyLikeRows -ne 0) { Add-Unique -List $missingRequirements -Value "review window has no BUY-like SIGNAL_EVAL rows" }
if ($recommendation -ne "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT") {
    Add-Unique -List $missingRequirements -Value "signal-eval recommendation is threshold-miss dominated"
}
if (@($thresholdRows).Count -eq 0) { Add-Unique -List $missingRequirements -Value "strategy574 threshold-gap row present" }
if (-not $nearThreshold) { Add-Unique -List $missingRequirements -Value "strategy574 min threshold gap is within near-threshold bound" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$primaryDecision = if ($ready) { "PREPARE_STRATEGY574_NEAR_THRESHOLD_SHADOW_DECISION_REVIEW" } else { "REFRESH_SIGNAL_EVAL_THRESHOLD_GAP_EVIDENCE" }
$riskPosture = if ($ready) { "REVIEW_ONLY_NEAR_THRESHOLD_NOT_LIVE_APPROVAL" } elseif ($recommendation -eq "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT") { "THRESHOLD_MISS_EVIDENCE_PRESENT_BUT_NOT_NEAR_BOUND" } else { "SIGNAL_EVAL_EVIDENCE_NOT_THRESHOLD_MISS_DOMINATED" }
$nextAction = if ($ready) {
    "Prepare a separate review-only strategy574 near-threshold shadow observation plan; do not change thresholds, activate strategy, execute TinyLive, relax DataFreshnessGuard or EntryDedup, or enable live policy from this packet."
} else {
    "Refresh SIGNAL_EVAL no-buy threshold-gap evidence before drafting strategy574 near-threshold shadow review."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    reviewDays = $ReviewDays
    sourceSignalEvalNoBuy = $signalEval.Source
    signalEvalNoBuyGenerationRecommendation = $recommendation
    signalEvalRows = $signalEvalRows
    signalEvalBuyLikeRows = $buyLikeRows
    signalEvalNoBuyRows = $noBuyRows
    signalEvalStrategyDecisionContextRows = $strategyDecisionRows
    signalEvalExecutionHoldRows = $executionHoldRows
    nearThresholdMaxGap = $NearThresholdMaxGap
    nearThreshold = $nearThreshold
    thresholdGapEvidence = @($thresholdRows)
    selectedThresholdGap = if ($bestRow) { $bestRow[0] } else { $null }
    primaryDecision = $primaryDecision
    riskPosture = $riskPosture
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        shadowObservationReviewAllowed = $ready
        strategyThresholdChangeAllowed = $false
        strategyActivationAllowed = $false
        tinyLiveOrderAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        deployOrEnvChangeAllowed = $false
        orderAllowed = $false
        telegramSendAllowed = $false
        positionOrOcoMutationAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
    }
    operatorDecisionChecklist = @(
        "confirm fresh SIGNAL_EVAL threshold-gap evidence",
        "confirm strategy574 near-threshold rows are not BUY-like execution candidates",
        "review forward-return and false-positive evidence before any future threshold experiment",
        "keep strategy activation, TinyLive, order, scheduler, deploy, env, Telegram, EntryDedup, and DataFreshness permissions false"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy574 near-threshold decision packet only; does not authorize strategy threshold changes, strategy activation, live trading, TinyLive execution, scheduler enablement, orders, OCO modification, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[strategy574-near-threshold-decision-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes or reuses smoke_signal_eval_no_buy_generation_ssh.ps1 only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_signal_eval_no_buy=$($signalEval.Source) exitCode=$($signalEval.ExitCode)"
Write-Host "signal_eval_no_buy_generation_recommendation=$recommendation"
Write-Host "signal_eval_rows=$signalEvalRows"
Write-Host "signal_eval_buy_like_rows=$buyLikeRows"
Write-Host "signal_eval_no_buy_rows=$noBuyRows"
Write-Host "signal_eval_strategy_decision_context_rows=$strategyDecisionRows"
Write-Host "signal_eval_execution_hold_rows=$executionHoldRows"
Write-Host "strategy574_threshold_gap_row_count=$(@($thresholdRows).Count)"
if ($bestRow) {
    Write-Host "strategy574_threshold_gap_indicator=$($bestRow[0].indicator)"
    Write-Host "strategy574_threshold_gap_count=$($bestRow[0].count)"
    Write-Host "strategy574_avg_mih_value=$($bestRow[0].avgMihValue)"
    Write-Host "strategy574_avg_buy_threshold=$($bestRow[0].avgBuyThreshold)"
    Write-Host "strategy574_avg_buy_gap=$($bestRow[0].avgBuyGap)"
    Write-Host "strategy574_min_buy_gap=$($bestRow[0].minBuyGap)"
}
Write-Host "strategy574_near_threshold=$($nearThreshold.ToString().ToLowerInvariant())"
Write-Host "strategy574_near_threshold_max_gap=$NearThresholdMaxGap"
Write-Host "strategy574_near_threshold_primary_decision=$primaryDecision"
Write-Host "strategy574_near_threshold_risk_posture=$riskPosture"
Write-Host "strategy574_shadow_observation_review_allowed=$($ready.ToString().ToLowerInvariant())"
Write-Host "strategy_threshold_change_allowed=false"
Write-Host "strategy_activation_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host ("strategy574_near_threshold_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy574_near_threshold_decision_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "strategy574_near_threshold_decision_status=$status"
Write-Host "strategy574_near_threshold_next_action=$nextAction"
Write-Host "notAuthorization=read-only strategy574 near-threshold decision packet only; does not authorize strategy threshold changes, strategy activation, live trading, TinyLive execution, scheduler enablement, orders, OCO modification, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[strategy574-near-threshold-decision-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy574 near-threshold decision packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
