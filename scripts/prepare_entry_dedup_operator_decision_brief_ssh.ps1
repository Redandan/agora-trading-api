param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 720,
    [int]$ForwardHours = 24,
    [int]$ShortForwardHours = 4,
    [int]$Limit = 50,
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [decimal]$ReviewNotionalCapUsdt = 10,
    [int]$ObservationHours = 72,
    [switch]$RequireDecisionReady
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
        throw "$Name contains unsupported characters for EntryDedup decision brief arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return @() }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
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
        throw "Unable to find powershell or pwsh for EntryDedup operator decision brief."
    }

    Write-Host "[entry-dedup-operator-decision-brief] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[entry-dedup-operator-decision-brief] child_complete script=$ScriptName exitCode=$exitCode"
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
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}
if ($Hours -lt 1 -or $Hours -gt 720) {
    throw "Hours must be between 1 and 720."
}
if ($ForwardHours -lt 1 -or $ForwardHours -gt 168) {
    throw "ForwardHours must be between 1 and 168."
}
if ($ShortForwardHours -lt 1 -or $ShortForwardHours -gt 72) {
    throw "ShortForwardHours must be between 1 and 72."
}
if ($ShortForwardHours -gt $ForwardHours) {
    throw "ShortForwardHours must be less than or equal to ForwardHours."
}
if ($Limit -lt 1 -or $Limit -gt 100) {
    throw "Limit must be between 1 and 100."
}
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) {
    throw "TakeProfitPct must be greater than 0 and at most 20."
}
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) {
    throw "StopLossPct must be greater than 0 and at most 20."
}
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) {
    throw "RoundTripFeePct must be between 0 and 2."
}
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) {
    throw "ReviewNotionalCapUsdt must be between 1 and 100."
}
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) {
    throw "ObservationHours must be between 1 and 720."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode

$packetResult = Invoke-ReadOnlyScript -ScriptName "prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-IntervalCode", $IntervalCode,
    "-Hours", "$Hours",
    "-ForwardHours", "$ForwardHours",
    "-ShortForwardHours", "$ShortForwardHours",
    "-Limit", "$Limit",
    "-TakeProfitPct", "$TakeProfitPct",
    "-StopLossPct", "$StopLossPct",
    "-RoundTripFeePct", "$RoundTripFeePct",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$packetStatus = Get-LastPrefixedValue -Text $packetResult.Text -Prefix "entry_dedup_semantics_shadow_packet_status="
$packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $packetResult.Text -Prefix "entry_dedup_semantics_shadow_experiment_packet=")
$missingRequirements = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $packetResult.Text -Prefix "entry_dedup_shadow_packet_missing_requirements=")

$summary = if ($null -ne $packet -and $null -ne $packet.sourceEvidenceSummary) { $packet.sourceEvidenceSummary } else { $null }
$entryDedupSkipRows = if ($null -ne $summary) { [string]$summary.entryDedupSkipRows } else { "N/A" }
$positive24hRows = if ($null -ne $summary) { [string]$summary.positive24hRows } else { "N/A" }
$negative24hRows = if ($null -ne $summary) { [string]$summary.negative24hRows } else { "N/A" }
$avg24hReturnPct = if ($null -ne $summary) { [string]$summary.avg24hReturnPct } else { "N/A" }
$tpHitRows = if ($null -ne $summary) { [string]$summary.tpHitRows } else { "N/A" }
$slHitRows = if ($null -ne $summary) { [string]$summary.slHitRows } else { "N/A" }
$ambiguousSameBarRows = if ($null -ne $summary) { [string]$summary.ambiguousSameBarRows } else { "N/A" }
$avgNetReturnPct = if ($null -ne $summary) { [string]$summary.avgNetReturnPct } else { "N/A" }

$decisionLanes = @(
    [pscustomobject]@{
        proposalId = "entry-dedup-semantics-shadow-operator-review"
        lane = "entry-dedup-shadow-experiment"
        decisionClass = "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_EXPERIMENT"
        status = if ($packetStatus -eq "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") { "READY_FOR_OPERATOR_DECISION_NOT_LIVE" } else { "NOT_READY" }
        sourcePacket = "prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1"
        evidence = @(
            "entry_dedup_skip_rows=$entryDedupSkipRows",
            "positive_24h_rows=$positive24hRows",
            "negative_24h_rows=$negative24hRows",
            "avg_24h_return_pct=$avg24hReturnPct",
            "tp_hit_rows=$tpHitRows",
            "sl_hit_rows=$slHitRows",
            "ambiguous_same_bar_rows=$ambiguousSameBarRows",
            "avg_net_return_pct=$avgNetReturnPct"
        )
        allowedFromThisBrief = @(
            "attach fresh EntryDedup shadow evidence to operator review",
            "draft a review-only shadow experiment plan",
            "request another read-only production rerun before decision"
        )
        separateAuthorizationRequired = @(
            "relax EntryDedup semantics",
            "enable staged-add or live entry execution",
            "change DataFreshness/live policy",
            "deploy runtime behavior changes",
            "place orders or attach/modify OCO"
        )
        forbiddenFromThisBrief = @(
            "relax EntryDedup",
            "enable live trading",
            "enable staged-add execution",
            "place orders",
            "modify OCO",
            "change production env",
            "deploy"
        )
        nextAction = "Prepare a separate review-only EntryDedup shadow experiment plan; keep EntryDedup/live policy unchanged."
    },
    [pscustomobject]@{
        proposalId = "entry-filter-datafreshness-policy-blocked"
        lane = "entry-filter-datafreshness-policy"
        decisionClass = "NOT_APPROVED_BY_ENTRY_DEDUP_SHADOW_BRIEF"
        status = "BLOCKED_POLICY_REVIEW_REQUIRED"
        sourcePacket = "profit operator action brief"
        evidence = @(
            "entry_dedup_shadow_brief_scope=semantics_shadow_review_only",
            "DataFreshnessGuard remains unchanged",
            "live_entry_policy remains unchanged"
        )
        allowedFromThisBrief = @(
            "keep entry/filter and DataFreshness policy unchanged",
            "route policy relaxation questions to a separate signal-policy/DataFreshness review"
        )
        separateAuthorizationRequired = @(
            "relax DataFreshnessGuard",
            "relax live entry policy",
            "enable TinyLive or entry execution"
        )
        forbiddenFromThisBrief = @(
            "relax DataFreshnessGuard",
            "enable TinyLive or entry execution",
            "change live policy"
        )
        nextAction = "Use signal-correctness/DataFreshness evidence for policy decisions; do not infer policy approval from EntryDedup shadow alpha."
    }
)

$decisionChecklist = @(
    [pscustomobject]@{
        proposalId = "entry-dedup-semantics-shadow-operator-review"
        lane = "entry-dedup-shadow-experiment"
        checklistType = "SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW_NOT_LIVE"
        mustVerify = @(
            "fresh production rerun immediately before operator review",
            "ExpectedValueGate pass-like evidence",
            "EventRiskControl clear or separately approved",
            "duplicate-hash and same-candidate replay protection",
            "daily cap and max-loss budget evidence",
            "OCO feasibility with exact route and lower-timeframe or exchange-side proof",
            "explicit operator approval before any EntryDedup semantics change"
        )
        separateAuthorizationRequired = @(
            "relax EntryDedup semantics",
            "enable staged-add or live entry execution",
            "deploy runtime behavior changes",
            "place orders or attach/modify OCO"
        )
        forbiddenWithoutAuthorization = @(
            "relax EntryDedup",
            "enable live trading",
            "enable staged-add execution",
            "place orders",
            "modify OCO",
            "change production env",
            "deploy"
        )
        nextAction = "Use this checklist to draft a separate EntryDedup shadow experiment review; do not change EntryDedup behavior from this brief."
        notAuthorization = "checklist only; does not authorize EntryDedup relaxation, live trading, staged-add execution, orders, OCO modification, deploy, or production env changes"
    },
    [pscustomobject]@{
        proposalId = "entry-filter-datafreshness-policy-blocked"
        lane = "entry-filter-datafreshness-policy"
        checklistType = "POLICY_BLOCKED_OUTSIDE_ENTRY_DEDUP_SHADOW_REVIEW"
        mustVerify = @(
            "signal policy gaps are clear",
            "DataFreshness replay evidence is complete",
            "missed-opportunity and governance drift evidence is separately reviewed"
        )
        separateAuthorizationRequired = @(
            "relax DataFreshnessGuard",
            "relax live entry policy",
            "enable TinyLive or entry execution"
        )
        forbiddenWithoutAuthorization = @(
            "relax DataFreshnessGuard",
            "enable TinyLive or entry execution",
            "change live policy"
        )
        nextAction = "Keep policy changes blocked until signal-correctness/DataFreshness gates are separately ready."
        notAuthorization = "checklist only; does not authorize DataFreshness/live policy relaxation or TinyLive execution"
    }
)

$decisionStatus = "NOT_READY"
$primaryRecommendation = "COLLECT_ENTRY_DEDUP_SHADOW_EVIDENCE"
if ($packetResult.ExitCode -ne 0 -or $null -eq $packet) {
    $decisionStatus = "NO_EVIDENCE"
    $primaryRecommendation = "FIX_ENTRY_DEDUP_PACKET_COLLECTION"
} elseif ($packetStatus -eq "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
    $decisionStatus = "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE"
    $primaryRecommendation = "PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW"
}

$brief = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_OPERATOR_DECISION_BRIEF"
    status = $decisionStatus
    symbol = $Symbol
    strategyId = $StrategyId
    intervalCode = $IntervalCode
    sourcePacket = "prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1"
    sourcePacketExitCode = $packetResult.ExitCode
    sourcePacketStatus = $packetStatus
    primaryRecommendation = $primaryRecommendation
    linkedActionProposalIds = @("entry-dedup-semantics-shadow-operator-review")
    decisionLanes = @($decisionLanes)
    decisionChecklist = @($decisionChecklist)
    separateAuthorizationsRequired = @(
        "relax EntryDedup semantics",
        "enable staged-add or live entry execution",
        "change DataFreshness/live policy",
        "place orders or attach/modify OCO",
        "change production env or deploy runtime changes"
    )
    doNotActions = @(
        "do not relax EntryDedup from this brief",
        "do not enable live trading from this brief",
        "do not enable staged-add execution from this brief",
        "do not place orders from this brief",
        "do not modify OCO from this brief",
        "do not deploy or change production env from this brief"
    )
    evidenceSummary = @{
        entryDedupSkipRows = $entryDedupSkipRows
        positive24hRows = $positive24hRows
        negative24hRows = $negative24hRows
        avg24hReturnPct = $avg24hReturnPct
        tpHitRows = $tpHitRows
        slHitRows = $slHitRows
        ambiguousSameBarRows = $ambiguousSameBarRows
        avgNetReturnPct = $avgNetReturnPct
        hours = $Hours
        forwardHours = $ForwardHours
        shortForwardHours = $ShortForwardHours
        limit = $Limit
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
    }
    missingRequirements = @($missingRequirements)
    sourceEntryDedupPacket = $packet
    nextAction = if ($decisionStatus -eq "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE") { "Attach this decision brief and the fresh EntryDedup shadow packet to a separate operator review; keep every mutation behind separate explicit authorization." } else { "Resolve missing EntryDedup shadow evidence and rerun this brief." }
    notAuthorization = "read-only EntryDedup operator decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable staged-add execution, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize policy changes"
}

Write-Host "[entry-dedup-operator-decision-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1 exitCode=$($packetResult.ExitCode)"
Write-Host "entry_dedup_review_hours=$Hours"
Write-Host "entry_dedup_review_forward_hours=$ForwardHours"
Write-Host "entry_dedup_review_limit=$Limit"
Write-Host "entry_dedup_semantics_shadow_packet_status=$packetStatus"
Write-Host "entry_dedup_operator_primary_recommendation=$primaryRecommendation"
Write-Host "entry_dedup_skip_rows=$entryDedupSkipRows"
Write-Host "positive_24h_rows=$positive24hRows"
Write-Host "negative_24h_rows=$negative24hRows"
Write-Host "avg_24h_return_pct=$avg24hReturnPct"
Write-Host "tp_hit_rows=$tpHitRows"
Write-Host "sl_hit_rows=$slHitRows"
Write-Host "ambiguous_same_bar_rows=$ambiguousSameBarRows"
Write-Host "avg_net_return_pct=$avgNetReturnPct"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("entry_dedup_operator_decision_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($decisionLanes)))
Write-Host ("entry_dedup_operator_decision_checklist=" + (ConvertTo-Json -Compress -Depth 8 @($decisionChecklist)))
Write-Host ("entry_dedup_operator_decision_brief_packet=" + (ConvertTo-Json -Compress -Depth 12 $brief))
Write-Host "entry_dedup_operator_decision_brief_status=$decisionStatus"
Write-Host "entry_dedup_operator_decision_next_action=$($brief.nextAction)"
Write-Host "notAuthorization=read-only EntryDedup operator decision brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable staged-add execution, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize policy changes"
Write-Host "[entry-dedup-operator-decision-brief] read-only check complete"

if ($RequireDecisionReady -and $decisionStatus -ne "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE") {
    throw "EntryDedup operator decision brief is not ready: status=$decisionStatus missing=$(@($missingRequirements) -join '; ')"
}
