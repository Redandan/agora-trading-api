param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 336,
    [int]$ForwardHours = 24,
    [int]$ShortForwardHours = 4,
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [decimal]$ReviewNotionalCapUsdt = 10,
    [int]$ObservationHours = 72,
    [int]$Limit = 30,
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
        throw "$Name contains unsupported characters for EntryDedup shadow experiment SSH packet arguments."
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
    if ([decimal]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return [decimal]0
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
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
        throw "Unable to find powershell or pwsh for EntryDedup shadow experiment SSH packet."
    }

    Write-Host "[entry-dedup-semantics-shadow-experiment-packet-ssh] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[entry-dedup-semantics-shadow-experiment-packet-ssh] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        ScriptName = $ScriptName
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
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($Hours -lt 1 -or $Hours -gt 720) { throw "Hours must be between 1 and 720." }
if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) { throw "ForwardHours must be between 1 and 168." }
if ($ShortForwardHours -lt 1 -or $ShortForwardHours -gt 72) { throw "ShortForwardHours must be between 1 and 72." }
if ($ShortForwardHours -gt $ForwardHours) { throw "ShortForwardHours must be less than or equal to ForwardHours." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }
if ($Limit -lt 1 -or $Limit -gt 100) { throw "Limit must be between 1 and 100." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$StrategyId,
    "-IntervalCode", $IntervalCode,
    "-Hours", [string]$Hours,
    "-Limit", [string]$Limit
)

$consistency = Invoke-ReadOnlyScript -ScriptName "smoke_entry_dedup_exposure_consistency_ssh.ps1" -Arguments $commonArgs
$shadow = Invoke-ReadOnlyScript -ScriptName "smoke_entry_dedup_semantics_shadow_review_ssh.ps1" -Arguments ($commonArgs + @(
    "-ForwardHours", [string]$ForwardHours,
    "-ShortForwardHours", [string]$ShortForwardHours
))
$feasibility = Invoke-ReadOnlyScript -ScriptName "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1" -Arguments ($commonArgs + @(
    "-ForwardHours", [string]$ForwardHours,
    "-TakeProfitPct", [string]$TakeProfitPct,
    "-StopLossPct", [string]$StopLossPct,
    "-RoundTripFeePct", [string]$RoundTripFeePct
))

$consistencyRecommendation = Get-LastPrefixedValue -Text $consistency.Text -Prefix "  entry_dedup_exposure_consistency_recommendation="
$entryDedupSkipRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  entry_dedup_skip_rows=")
$openSignalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  open_signal_rows=")
$autoTradedOpenRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  auto_traded_open_rows=")
$nonAutoOpenRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  non_auto_open_rows=")
$nonAutoZeroQtyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  non_auto_zero_qty_rows=")
$nonAutoEventRiskRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  non_auto_eventrisk_rows=")
$missingOcoRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $consistency.Text -Prefix "  missing_oco_rows=")

$shadowRecommendation = Get-LastPrefixedValue -Text $shadow.Text -Prefix "  entry_dedup_semantics_shadow_recommendation="
$reviewableForwardRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  reviewable_forward_rows=")
$positive24hRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  positive_24h_rows=")
$negative24hRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  negative_24h_rows=")
$avg4hReturnPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  avg_4h_return_pct=")
$avg24hReturnPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  avg_24h_return_pct=")
$median24hReturnPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  median_24h_return_pct=")
$avgMfe24hPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  avg_mfe_24h_pct=")
$avgMae24hPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $shadow.Text -Prefix "  avg_mae_24h_pct=")

$feasibilityRecommendation = Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  entry_dedup_semantics_feasibility_recommendation="
$replayReviewedRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  replay_reviewed_rows=")
$tpHitRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  tp_hit_rows=")
$slHitRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  sl_hit_rows=")
$timeoutRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  timeout_rows=")
$ambiguousSameBarRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  ambiguous_same_bar_rows=")
$missingKlineRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  missing_kline_rows=")
$netPositiveRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  net_positive_rows=")
$netWinRatePct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  net_win_rate_pct=")
$avgNetReturnPct = Get-DecimalValue -Value (Get-LastPrefixedValue -Text $feasibility.Text -Prefix "  avg_net_return_pct=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($consistency.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "entry-dedup exposure consistency exitCode=0" }
if ($shadow.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "entry-dedup semantics shadow review exitCode=0" }
if ($feasibility.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "entry-dedup semantics feasibility review exitCode=0" }
if ($consistencyRecommendation -ne "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW") { Add-MissingRequirement -List $missingRequirements -Value "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW" }
if ($shadowRecommendation -ne "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE") { Add-MissingRequirement -List $missingRequirements -Value "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE" }
if ($feasibilityRecommendation -ne "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE") { Add-MissingRequirement -List $missingRequirements -Value "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE" }
if ($entryDedupSkipRows -lt 3) { Add-MissingRequirement -List $missingRequirements -Value "entry_dedup_skip_rows >= 3" }
if ($reviewableForwardRows -lt 3) { Add-MissingRequirement -List $missingRequirements -Value "reviewable_forward_rows >= 3" }
if ($replayReviewedRows -lt 3) { Add-MissingRequirement -List $missingRequirements -Value "replay_reviewed_rows >= 3" }
if ($tpHitRows -lt 1 -or $netPositiveRows -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "positive TP/SL replay rows" }
if ($slHitRows -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "sl_hit_rows=0 for this shadow packet" }
if ($ambiguousSameBarRows -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "ambiguous_same_bar_rows=0" }
if ($missingKlineRows -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "missing_kline_rows=0" }
if ($avgNetReturnPct -le 0) { Add-MissingRequirement -List $missingRequirements -Value "avg_net_return_pct > 0" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this fresh SSH packet to a separate EntryDedup semantics shadow experiment review; keep order_allowed=false and live policy unchanged."
} else {
    "Resolve missing read-only evidence before attaching an EntryDedup semantics shadow experiment review packet."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    freshProductionRerun = $true
    sourceEvidenceScripts = @(
        "smoke_entry_dedup_exposure_consistency_ssh.ps1",
        "smoke_entry_dedup_semantics_shadow_review_ssh.ps1",
        "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1"
    )
    childExitCodes = @{
        exposureConsistency = $consistency.ExitCode
        semanticsShadowReview = $shadow.ExitCode
        tpSlFeasibilityReview = $feasibility.ExitCode
    }
    sourceEvidenceSummary = [pscustomobject]@{
        entryDedupExposureRecommendation = $consistencyRecommendation
        entryDedupSkipRows = $entryDedupSkipRows
        openSignalRows = $openSignalRows
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoOpenRows = $nonAutoOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        missingOcoRows = $missingOcoRows
        shadowRecommendation = $shadowRecommendation
        reviewableForwardRows = $reviewableForwardRows
        positive24hRows = $positive24hRows
        negative24hRows = $negative24hRows
        avg4hReturnPct = $avg4hReturnPct
        avg24hReturnPct = $avg24hReturnPct
        median24hReturnPct = $median24hReturnPct
        avgMfe24hPct = $avgMfe24hPct
        avgMae24hPct = $avgMae24hPct
        feasibilityRecommendation = $feasibilityRecommendation
        takeProfitPct = $TakeProfitPct
        stopLossPct = $StopLossPct
        roundTripFeePct = $RoundTripFeePct
        forwardHours = $ForwardHours
        replayReviewedRows = $replayReviewedRows
        tpHitRows = $tpHitRows
        slHitRows = $slHitRows
        timeoutRows = $timeoutRows
        ambiguousSameBarRows = $ambiguousSameBarRows
        missingKlineRows = $missingKlineRows
        netPositiveRows = $netPositiveRows
        netWinRatePct = $netWinRatePct
        avgNetReturnPct = $avgNetReturnPct
    }
    proposedEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        orderAllowed = $false
        livePolicyChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
    }
    minimumEvidence = @(
        "fresh production EntryDedup exposure semantics mismatch identified",
        "fresh forward K-line shadow review has reviewable rows and positive 24h skew",
        "fresh fee-adjusted TP/SL feasibility has no SL hits and no same-bar ambiguity under explicit assumptions"
    )
    requiredBeforeAnyMutation = @(
        "fresh read-only production rerun immediately before operator review",
        "ExpectedValueGate pass-like evidence",
        "EventRiskControl clear or separately approved",
        "duplicate-hash and same-candidate replay protection",
        "daily cap and max-loss budget evidence",
        "OCO feasibility with exact route and lower-timeframe or exchange-side proof",
        "explicit operator approval for any EntryDedup semantics change",
        "separate deploy/env authorization if runtime behavior changes"
    )
    operatorDecisionChoices = @(
        "approve review-only shadow experiment packet",
        "request another fresh read-only SSH rerun",
        "reject or defer; keep current EntryDedup policy unchanged"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup semantics shadow experiment SSH packet only; does not authorize EntryDedup relaxation, live trading, staged-add execution, orders, OCO modification, position close, scheduler enablement, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or policy relaxation"
}

Write-Host "[entry-dedup-semantics-shadow-experiment-packet-ssh] read-only packet"
Write-Host "scope=READ_ONLY; invokes EntryDedup exposure, shadow, and feasibility smokes only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_exposure_consistency=smoke_entry_dedup_exposure_consistency_ssh.ps1 exitCode=$($consistency.ExitCode)"
Write-Host "source_semantics_shadow_review=smoke_entry_dedup_semantics_shadow_review_ssh.ps1 exitCode=$($shadow.ExitCode)"
Write-Host "source_feasibility_review=smoke_entry_dedup_semantics_feasibility_review_ssh.ps1 exitCode=$($feasibility.ExitCode)"
Write-Host "entry_dedup_exposure_consistency_recommendation=$consistencyRecommendation"
Write-Host "entry_dedup_semantics_shadow_recommendation=$shadowRecommendation"
Write-Host "entry_dedup_semantics_feasibility_recommendation=$feasibilityRecommendation"
Write-Host "entry_dedup_skip_rows=$entryDedupSkipRows"
Write-Host "open_signal_rows=$openSignalRows"
Write-Host "auto_traded_open_rows=$autoTradedOpenRows"
Write-Host "non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host "non_auto_eventrisk_rows=$nonAutoEventRiskRows"
Write-Host "reviewable_forward_rows=$reviewableForwardRows"
Write-Host "positive_24h_rows=$positive24hRows"
Write-Host "negative_24h_rows=$negative24hRows"
Write-Host "avg_24h_return_pct=$avg24hReturnPct"
Write-Host "replay_reviewed_rows=$replayReviewedRows"
Write-Host "tp_hit_rows=$tpHitRows"
Write-Host "sl_hit_rows=$slHitRows"
Write-Host "ambiguous_same_bar_rows=$ambiguousSameBarRows"
Write-Host "avg_net_return_pct=$avgNetReturnPct"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("entry_dedup_shadow_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_semantics_shadow_experiment_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "entry_dedup_semantics_shadow_packet_status=$status"
Write-Host "entry_dedup_semantics_shadow_packet_next_action=$nextAction"
Write-Host "notAuthorization=read-only EntryDedup semantics shadow experiment SSH packet only; does not authorize EntryDedup relaxation, live trading, staged-add execution, orders, OCO modification, position close, scheduler enablement, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or policy relaxation"
Write-Host "[entry-dedup-semantics-shadow-experiment-packet-ssh] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup semantics shadow experiment SSH packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
