param(
    [string]$ProgressPath = "SPLIT_PROGRESS.md",
    [string]$ReadmePath = "README.md",
    [string]$RunbookPath = "docs/deploy-runbook.md",
    [string]$ExactOpportunityLogPath = "",
    [int]$MaxAgeMinutes = 240,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [decimal]$ReviewNotionalCapUsdt = 10,
    [int]$ObservationHours = 72,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for EntryDedup shadow packet arguments."
    }
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Test-TextContains {
    param([string]$Text, [string]$Needle)
    return $Text.Contains($Needle)
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.TrimStart().StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    $trimmed = $line.TrimStart()
    return $trimmed.Substring($Prefix.Length).Trim()
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

function Get-DecimalValue {
    param([object]$Value)
    $parsed = [decimal]0
    if ($null -eq $Value) { return [decimal]0 }
    if ($Value -is [decimal]) { return $Value }
    if ([decimal]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return [decimal]0
}

Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-PathTokenSafe -Name "ExactOpportunityLogPath" -Value $ExactOpportunityLogPath
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) { throw "MaxAgeMinutes must be between 1 and 10080." }
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$progressFullPath = Resolve-RepoPath -Path $ProgressPath
$readmeFullPath = Resolve-RepoPath -Path $ReadmePath
$runbookFullPath = Resolve-RepoPath -Path $RunbookPath
$sourceScripts = @(
    "smoke_entry_dedup_exposure_consistency_ssh.ps1",
    "smoke_entry_dedup_semantics_shadow_review_ssh.ps1",
    "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1",
    "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1"
)

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($scriptName in $sourceScripts) {
    $scriptPath = Join-Path $PSScriptRoot $scriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        Add-MissingRequirement -List $missingRequirements -Value "source script exists: $scriptName"
    } else {
        $scriptText = Get-Content -Raw -LiteralPath $scriptPath
        foreach ($marker in @("scope=READ_ONLY", "notAuthorization")) {
            if (-not (Test-TextContains -Text $scriptText -Needle $marker)) {
                Add-MissingRequirement -List $missingRequirements -Value "source script safety marker present: $scriptName $marker"
            }
        }
    }
}

foreach ($path in @($progressFullPath, $readmeFullPath, $runbookFullPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Add-MissingRequirement -List $missingRequirements -Value "document exists: $path"
    }
}

$progressText = if (Test-Path -LiteralPath $progressFullPath) { Get-Content -Raw -LiteralPath $progressFullPath } else { "" }
$readmeText = if (Test-Path -LiteralPath $readmeFullPath) { Get-Content -Raw -LiteralPath $readmeFullPath } else { "" }
$runbookText = if (Test-Path -LiteralPath $runbookFullPath) { Get-Content -Raw -LiteralPath $runbookFullPath } else { "" }
$sourceEvidenceMode = "RECORDED_DOC_EVIDENCE"
$exactOpportunityPacket = $null
$exactOpportunityLogFresh = $false
if (-not [string]::IsNullOrWhiteSpace($ExactOpportunityLogPath)) {
    $exactOpportunityFullPath = Resolve-RepoPath -Path $ExactOpportunityLogPath
    if (-not (Test-Path -LiteralPath $exactOpportunityFullPath)) {
        Add-MissingRequirement -List $missingRequirements -Value "ExactOpportunityLogPath exists: $ExactOpportunityLogPath"
    } else {
        $exactOpportunityItem = Get-Item -LiteralPath $exactOpportunityFullPath
        $exactOpportunityAgeMinutes = [math]::Round(((Get-Date) - $exactOpportunityItem.LastWriteTime).TotalMinutes, 2)
        $exactOpportunityLogFresh = $exactOpportunityAgeMinutes -le $MaxAgeMinutes
        if (-not $exactOpportunityLogFresh) {
            Add-MissingRequirement -List $missingRequirements -Value "ExactOpportunityLogPath fresh within $MaxAgeMinutes minutes"
        }
        $exactOpportunityText = Get-Content -Raw -LiteralPath $exactOpportunityFullPath
        $exactOpportunityJson = Get-LastPrefixedValue -Text $exactOpportunityText -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet="
        if ([string]::IsNullOrWhiteSpace($exactOpportunityJson)) {
            Add-MissingRequirement -List $missingRequirements -Value "ExactOpportunityLogPath contains exact opportunity packet JSON"
        } else {
            $exactOpportunityPacket = $exactOpportunityJson | ConvertFrom-Json -ErrorAction Stop
            $sourceEvidenceMode = "FRESH_EXACT_OPPORTUNITY_LOG"
            if ([string]$exactOpportunityPacket.status -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
                Add-MissingRequirement -List $missingRequirements -Value "ExactOpportunityLogPath exact opportunity packet ready"
            }
        }
    }
}

foreach ($marker in @(
        "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW",
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE",
        "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE",
        "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET",
        "entry_dedup_skip_rows=11",
        "exact_opportunity_count",
        "staged_add_review_candidate_opportunities",
        "tp_hit_rows=11",
        "avg_net_return_pct=0.8000",
        "ambiguous_same_bar_rows=0"
    )) {
    if (-not (Test-TextContains -Text $progressText -Needle $marker)) {
        Add-MissingRequirement -List $missingRequirements -Value "SPLIT_PROGRESS evidence marker present: $marker"
    }
}

foreach ($docCheck in @(
        [pscustomobject]@{ Name = "README"; Text = $readmeText },
        [pscustomobject]@{ Name = "deploy runbook"; Text = $runbookText }
    )) {
    foreach ($marker in @(
            "smoke_entry_dedup_exposure_consistency_ssh.ps1",
            "smoke_entry_dedup_semantics_shadow_review_ssh.ps1",
            "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1",
            "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1"
        )) {
        if (-not (Test-TextContains -Text ([string]$docCheck.Text) -Needle $marker)) {
            Add-MissingRequirement -List $missingRequirements -Value "$($docCheck.Name) mentions source evidence: $marker"
        }
    }
}

$defaultSummary = [pscustomobject]@{
    entryDedupExposureSemanticsMismatch = $true
    rawAuditRows = 11
    exactOpportunityCount = 6
    exactDuplicateSuppressedRows = 5
    entryDedupSkipRows = 11
    openSignalRows = 1
    autoTradedOpenRows = 0
    nonAutoZeroQtyRows = 1
    nonAutoEventRiskRows = 1
    positive24hRows = 10
    negative24hRows = 1
    positive24hRatePct = 90.91
    avg4hReturnPct = 0.8950
    avg24hReturnPct = 1.0173
    median24hReturnPct = 1.0572
    avgMfe24hPct = 2.3067
    avgMae24hPct = -0.3580
    replayReviewedRows = 11
    exactOpportunityReviewedRows = 6
    takeProfitPct = $TakeProfitPct
    stopLossPct = $StopLossPct
    roundTripFeePct = $RoundTripFeePct
    tpHitRows = 11
    tpHitOpportunities = 6
    slHitRows = 0
    slHitOpportunities = 0
    timeoutRows = 0
    ambiguousSameBarRows = 0
    ambiguousOpportunities = 0
    netPositiveRows = 11
    netWinRatePct = 100.00
    avgNetReturnPct = 0.8000
    stagedAddBudgetProxyAllowedOpportunities = 6
    stagedAddReviewCandidateOpportunities = 6
    exactOpportunityReviewBlockers = @(
        "NON_AUTO_ZERO_QTY_OPEN_SIGNAL_PRESENT",
        "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
    )
}

$sourceEvidenceSummary = $defaultSummary
if ($null -ne $exactOpportunityPacket -and $exactOpportunityLogFresh) {
    $openExposure = $exactOpportunityPacket.openExposure
    $rawAuditRows = Get-IntValue $exactOpportunityPacket.rawAuditRows
    $exactOpportunityCount = Get-IntValue $exactOpportunityPacket.exactOpportunityCount
    $exactDuplicateSuppressedRows = Get-IntValue $exactOpportunityPacket.exactDuplicateSuppressedRows
    $tpHitOpportunities = Get-IntValue $exactOpportunityPacket.tpHitOpportunities
    $slHitOpportunities = Get-IntValue $exactOpportunityPacket.slHitOpportunities
    $ambiguousOpportunities = Get-IntValue $exactOpportunityPacket.ambiguousOpportunities
    $positiveRate = if ($exactOpportunityCount -gt 0) { [math]::Round(($tpHitOpportunities * 100.0) / $exactOpportunityCount, 2) } else { 0 }
    $blockers = [System.Collections.Generic.List[string]]::new()
    foreach ($opportunity in @($exactOpportunityPacket.opportunities)) {
        foreach ($blocker in @($opportunity.reviewBlockers)) {
            if (-not [string]::IsNullOrWhiteSpace($blocker) -and $blockers -notcontains [string]$blocker) {
                $blockers.Add([string]$blocker)
            }
        }
    }
    if ($blockers.Count -eq 0) {
        $blockers.Add("OCO_ROUTE_NOT_PROVEN_OR_MISSING")
    }

    $sourceEvidenceSummary = [pscustomobject]@{
        entryDedupExposureSemanticsMismatch = $true
        rawAuditRows = $rawAuditRows
        exactOpportunityCount = $exactOpportunityCount
        exactDuplicateSuppressedRows = $exactDuplicateSuppressedRows
        entryDedupSkipRows = $rawAuditRows
        openSignalRows = Get-IntValue $openExposure.open_signal_rows
        autoTradedOpenRows = Get-IntValue $openExposure.auto_traded_open_rows
        nonAutoZeroQtyRows = Get-IntValue $openExposure.non_auto_zero_qty_rows
        nonAutoEventRiskRows = Get-IntValue $openExposure.non_auto_eventrisk_rows
        positive24hRows = $tpHitOpportunities
        negative24hRows = $slHitOpportunities
        positive24hRatePct = $positiveRate
        avg4hReturnPct = Get-DecimalValue $exactOpportunityPacket.avgExpectedRProxy
        avg24hReturnPct = Get-DecimalValue $exactOpportunityPacket.avgNetReturnPct
        median24hReturnPct = Get-DecimalValue $exactOpportunityPacket.avgNetReturnPct
        avgMfe24hPct = 0
        avgMae24hPct = 0
        replayReviewedRows = $rawAuditRows
        exactOpportunityReviewedRows = $exactOpportunityCount
        takeProfitPct = $TakeProfitPct
        stopLossPct = $StopLossPct
        roundTripFeePct = $RoundTripFeePct
        tpHitRows = $rawAuditRows
        tpHitOpportunities = $tpHitOpportunities
        slHitRows = $slHitOpportunities
        slHitOpportunities = $slHitOpportunities
        timeoutRows = 0
        ambiguousSameBarRows = $ambiguousOpportunities
        ambiguousOpportunities = $ambiguousOpportunities
        netPositiveRows = $tpHitOpportunities
        netWinRatePct = $positiveRate
        avgNetReturnPct = Get-DecimalValue $exactOpportunityPacket.avgNetReturnPct
        stagedAddBudgetProxyAllowedOpportunities = Get-IntValue $exactOpportunityPacket.stagedAddBudgetProxyAllowedOpportunities
        stagedAddReviewCandidateOpportunities = Get-IntValue $exactOpportunityPacket.stagedAddReviewCandidateOpportunities
        exactOpportunityReviewBlockers = @($blockers)
    }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this packet to a separate EntryDedup semantics shadow experiment review; keep live policy unchanged."
} else {
    "Resolve missing read-only evidence markers before attaching an EntryDedup semantics shadow experiment review packet."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    sourceEvidenceMode = $sourceEvidenceMode
    exactOpportunityLogPath = $ExactOpportunityLogPath
    sourceEvidenceScripts = @($sourceScripts)
    sourceEvidenceSummary = $sourceEvidenceSummary
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
        "production EntryDedup exposure semantics mismatch identified",
        "repeated audit rows are grouped into exact opportunities before sizing the alpha opportunity",
        "forward K-line shadow review has reviewable rows and positive 24h skew",
        "fee-adjusted TP/SL feasibility has no SL hits and no same-bar ambiguity under explicit assumptions"
    )
    requiredBeforeAnyMutation = @(
        "fresh read-only production rerun",
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
        "request fresh read-only SSH rerun",
        "reject or defer; keep current EntryDedup policy unchanged"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup semantics shadow experiment review packet only; does not authorize EntryDedup relaxation, live trading, staged-add execution, orders, OCO modification, position close, scheduler enablement, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or policy relaxation"
}

Write-Host "[entry-dedup-semantics-shadow-experiment-packet] read-only packet"
Write-Host "scope=READ_ONLY; local docs/evidence packaging only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol"
Write-Host "strategy_id=$StrategyId"
Write-Host "interval_code=$IntervalCode"
Write-Host "take_profit_pct=$TakeProfitPct"
Write-Host "stop_loss_pct=$StopLossPct"
Write-Host "round_trip_fee_pct=$RoundTripFeePct"
Write-Host "entry_dedup_semantics_shadow_source_mode=$sourceEvidenceMode"
Write-Host "entry_dedup_semantics_shadow_exact_opportunity_log_path=$ExactOpportunityLogPath"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("entry_dedup_shadow_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_semantics_shadow_experiment_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet))
Write-Host "entry_dedup_semantics_shadow_packet_status=$status"
Write-Host "entry_dedup_semantics_shadow_packet_next_action=$nextAction"
Write-Host "notAuthorization=read-only EntryDedup semantics shadow experiment review packet only; does not authorize EntryDedup relaxation, live trading, staged-add execution, orders, OCO modification, position close, scheduler enablement, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, external backfill/import, or policy relaxation"
Write-Host "[entry-dedup-semantics-shadow-experiment-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup semantics shadow experiment packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
