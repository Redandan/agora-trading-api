param(
    [string]$MicroProbeHandoffLogPath = "",
    [string]$Strategy574TinyLivePreflightLogPath = "",
    [string]$TpSlOcoPreflightLogPath = "",
    [string]$LiveReviewPacketLogPath = "",
    [string]$RuntimeEvidenceRcaLogPath = "",
    [string]$Symbol = "BTCUSDT",
    [decimal]$MaxProbeNotionalUsdt = 10,
    [switch]$AllowDirtyLocalWorktreeForReplay,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Get-NestedPropertyValue {
    param([object]$Object, [string[]]$Path)
    $cursor = $Object
    foreach ($name in @($Path)) {
        if ($null -eq $cursor) { return "" }
        $property = $cursor.PSObject.Properties[$name]
        if ($null -eq $property) { return "" }
        $cursor = $property.Value
    }
    if ($null -eq $cursor) { return "" }
    if ($cursor -is [bool]) { return $cursor.ToString().ToLowerInvariant() }
    return [string]$cursor
}

function Read-TextLog {
    param([string]$PathValue, [string]$Name)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log path not found: $resolved"
    }
    return [pscustomobject]@{
        Text = Get-Content -Raw -LiteralPath $resolved
        ExitCode = 0
        Source = $resolved
    }
}

function Invoke-LocalScript {
    param([string]$ScriptName, [string[]]$Arguments)
    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing local preflight dependency script: $scriptPath"
    }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for high-risk micro live probe preflight." }

    $output = @()
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = if ($?) { 0 } else { 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 8192)
        ExitCode = $exitCode
        Source = $ScriptName
    }
}

function Get-SourceText {
    param(
        [string]$Name,
        [string]$LogPath,
        [string]$ScriptName,
        [string[]]$Arguments,
        [bool]$Required
    )
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        return Read-TextLog -PathValue $LogPath -Name $Name
    }
    if ([string]::IsNullOrWhiteSpace($ScriptName)) {
        if ($Required) { throw "$Name log path is required." }
        return [pscustomobject]@{ Text = ""; ExitCode = 0; Source = "MISSING_OPTIONAL_LOG" }
    }
    return Invoke-LocalScript -ScriptName $ScriptName -Arguments $Arguments
}

function Test-RuntimeEvidenceReady {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    if ($Text -match "orderSentEvidence=([1-9][0-9]*)") { return $false }
    return (
        $Text -match "diagnosis=CANONICAL_SHADOW_READY" -and
        $Text -match "shadowIntentCount=([1-9][0-9]*)" -and
        $Text -match "orderSentEvidence=0" -and
        ($Text -match "missing_runtime_evidence_fields=\[\]" -or $Text -match '"missing_runtime_evidence_fields"\s*:\s*\[\]')
    )
}

function Test-LiveReviewReady {
    param([string]$Text)
    return (
        -not [string]::IsNullOrWhiteSpace($Text) -and
        $Text -match "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED" -and
        $Text -match "live_review_packet_allowed=true" -and
        $Text -match "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED" -and
        $Text -match "packet_missing_requirements=\[\]"
    )
}

if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for high-risk micro live probe preflight arguments."
}
if ($MaxProbeNotionalUsdt -le 0 -or $MaxProbeNotionalUsdt -gt 1000) {
    throw "MaxProbeNotionalUsdt must be between 0 and 1000."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$missingRequirements = [System.Collections.Generic.List[string]]::new()

$handoffArgs = @("-Symbol", $Symbol, "-MaxProbeNotionalUsdt", "$MaxProbeNotionalUsdt", "-RequireReady")
if ($AllowDirtyLocalWorktreeForReplay.IsPresent) { $handoffArgs += "-AllowDirtyLocalWorktreeForReplay" }
$microSource = Get-SourceText -Name "Micro probe handoff" -LogPath $MicroProbeHandoffLogPath -ScriptName "prepare_profit_high_risk_micro_live_probe_handoff.ps1" -Arguments $handoffArgs -Required $true
$strategySource = Get-SourceText -Name "Strategy574 TinyLive preflight" -LogPath $Strategy574TinyLivePreflightLogPath -ScriptName "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1" -Arguments @("-Symbol", $Symbol, "-StrategyId", "574", "-Side", "LONG", "-RequireReady") -Required $true
$tpSource = Get-SourceText -Name "TP/SL/OCO preflight" -LogPath $TpSlOcoPreflightLogPath -ScriptName "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1" -Arguments @("-Symbol", $Symbol, "-RequireReady") -Required $true
$liveReviewSource = Get-SourceText -Name "Live review packet" -LogPath $LiveReviewPacketLogPath -ScriptName "" -Arguments @() -Required $true
$runtimeSource = Get-SourceText -Name "Runtime evidence RCA" -LogPath $RuntimeEvidenceRcaLogPath -ScriptName "" -Arguments @() -Required $false

foreach ($source in @($microSource, $strategySource, $tpSource, $liveReviewSource)) {
    if ([int]$source.ExitCode -ne 0) {
        Add-MissingRequirement -List $missingRequirements -Value "$($source.Source) completed"
    }
}

$microPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $microSource.Text -Prefix "profit_high_risk_micro_live_probe_handoff_packet=")
$strategyPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $strategySource.Text -Prefix "strategy574_tiny_live_governance_preflight_review_packet=")
$tpPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $tpSource.Text -Prefix "tp_sl_oco_feasibility_preflight_review_packet=")

if ($null -eq $microPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_high_risk_micro_live_probe_handoff_packet valid JSON" }
if ($null -eq $strategyPacket) { Add-MissingRequirement -List $missingRequirements -Value "strategy574_tiny_live_governance_preflight_review_packet valid JSON" }
if ($null -eq $tpPacket) { Add-MissingRequirement -List $missingRequirements -Value "tp_sl_oco_feasibility_preflight_review_packet valid JSON" }

$microStatus = Get-PropertyValue -Object $microPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($microStatus)) {
    $microStatus = Get-LastPrefixedValue -Text $microSource.Text -Prefix "profit_high_risk_micro_live_probe_handoff_status=" -Default "UNKNOWN"
}
$microMaxOrders = Get-PropertyValue -Object $microPacket -Name "optionMaxOrders"
$microEnvDeployAllowed = Get-PropertyValue -Object $microPacket -Name "envDeployRequestAllowed"
$microOrderAllowed = Get-PropertyValue -Object $microPacket -Name "orderAllowed"
$microDeployAllowed = Get-PropertyValue -Object $microPacket -Name "deployAllowed"

$strategyStatus = Get-PropertyValue -Object $strategyPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($strategyStatus)) {
    $strategyStatus = Get-LastPrefixedValue -Text $strategySource.Text -Prefix "strategy574_tiny_live_governance_preflight_status=" -Default "UNKNOWN"
}
$strategyRiskPosture = Get-PropertyValue -Object $strategyPacket -Name "sourceRiskPosture"
$strategyDataFreshnessClean = Get-PropertyValue -Object $strategyPacket -Name "sourceDataFreshnessCurrentClean"
$strategyCanEnableProduction = Get-PropertyValue -Object $strategyPacket -Name "sourceTinyLiveCanEnableProduction"
$strategyHardStopDetected = Get-PropertyValue -Object $strategyPacket -Name "sourceTinyLiveHardStopDetected"
$strategyFalsePositiveCount = Get-PropertyValue -Object $strategyPacket -Name "sourceTinyLiveFalsePositiveCount"

$tpStatus = Get-PropertyValue -Object $tpPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($tpStatus)) {
    $tpStatus = Get-LastPrefixedValue -Text $tpSource.Text -Prefix "tp_sl_oco_feasibility_preflight_status=" -Default "UNKNOWN"
}
$tpTrailingAcceptance = Get-PropertyValue -Object $tpPacket -Name "trailingStopAcceptance"
$tpOcoHealthOk = Get-PropertyValue -Object $tpPacket -Name "strategy485OcoHealthOk"

$liveReviewReady = Test-LiveReviewReady -Text $liveReviewSource.Text
$runtimeEvidenceReady = if (-not [string]::IsNullOrWhiteSpace($runtimeSource.Text)) {
    Test-RuntimeEvidenceReady -Text $runtimeSource.Text
} else {
    $liveReviewReady
}
$runtimeDiagnosis = if ($runtimeSource.Text -match "diagnosis=([A-Z0-9_]+)") { $Matches[1] } elseif ($liveReviewReady) { "COVERED_BY_LIVE_REVIEW_PACKET" } else { "UNKNOWN" }
$runtimeOrderSentEvidence = if ($runtimeSource.Text -match "orderSentEvidence=([0-9]+)") { $Matches[1] } elseif ($liveReviewReady) { "0" } else { "UNKNOWN" }
$liveBundleVerdict = Get-LastPrefixedValue -Text $liveReviewSource.Text -Prefix "bundle_verdict=" -Default "UNKNOWN"

if ($microStatus -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "micro live probe handoff ready"
}
if ($microMaxOrders -ne "1") {
    Add-MissingRequirement -List $missingRequirements -Value "micro live probe maxOrders=1"
}
if ($microEnvDeployAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "micro handoff keeps envDeployRequestAllowed=false"
}
if ($microOrderAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "micro handoff keeps orderAllowed=false"
}
if ($microDeployAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "micro handoff keeps deployAllowed=false"
}
if ($strategyStatus -ne "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 TinyLive preflight ready"
}
if ($strategyRiskPosture -ne "REVIEW_ONLY_READY_NOT_LIVE_APPROVAL") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 risk posture review-only ready"
}
if ($strategyDataFreshnessClean -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy574 current DataFreshness clean"
}
if ($strategyCanEnableProduction -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "TinyLive canEnableProduction=true"
}
if ($strategyHardStopDetected -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "TinyLive hard stop clear"
}
if ($tpStatus -ne "READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "TP/SL/OCO preflight ready"
}
if ($tpTrailingAcceptance -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "TP/SL/OCO trailing acceptance PASS"
}
if ($tpOcoHealthOk -notin @("true", "True", "TRUE")) {
    Add-MissingRequirement -List $missingRequirements -Value "TP/SL/OCO OCO health OK"
}
if (-not $liveReviewReady) {
    Add-MissingRequirement -List $missingRequirements -Value "live review packet ready"
}
if (-not $runtimeEvidenceReady) {
    Add-MissingRequirement -List $missingRequirements -Value "runtime evidence canonical shadow ready with orderSentEvidence=0"
}
if ($runtimeOrderSentEvidence -ne "0") {
    Add-MissingRequirement -List $missingRequirements -Value "runtime orderSentEvidence=0"
}

$hardGateClear = $missingRequirements.Count -eq 0
$status = if ($hardGateClear) {
    "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REQUIREMENTS_MISSING"
}
$decision = if ($hardGateClear) {
    "REVIEW_EXACT_MICRO_PROBE_AUTHORIZATION_TEXT_WITH_OPERATOR_DO_NOT_DEPLOY"
} else {
    "REFRESH_MICRO_PROBE_HARD_GATE_EVIDENCE_BEFORE_AUTHORIZATION_REVIEW"
}
$nextAction = if ($hardGateClear) {
    "Review the exact high-risk micro probe authorization text with the operator. Do not deploy or place orders from this packet; any env/deploy/order action still needs a separate explicit same-session authorization."
} else {
    "Do not request env/deploy or order authorization; refresh the missing hard-gate evidence and rerun this preflight."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    maxProbeNotionalUsdt = $MaxProbeNotionalUsdt
    sourceMicroHandoffStatus = $microStatus
    sourceStrategy574PreflightStatus = $strategyStatus
    sourceTpSlOcoPreflightStatus = $tpStatus
    liveReviewPacketReady = $liveReviewReady
    liveReadinessBundleVerdict = $liveBundleVerdict
    runtimeEvidenceReady = $runtimeEvidenceReady
    runtimeEvidenceDiagnosis = $runtimeDiagnosis
    runtimeOrderSentEvidence = $runtimeOrderSentEvidence
    strategy574RiskPosture = $strategyRiskPosture
    strategy574DataFreshnessClean = $strategyDataFreshnessClean
    tinyLiveCanEnableProduction = $strategyCanEnableProduction
    tinyLiveHardStopDetected = $strategyHardStopDetected
    tinyLiveFalsePositiveCount = $strategyFalsePositiveCount
    tpSlOcoTrailingAcceptance = $tpTrailingAcceptance
    tpSlOcoHealthOk = $tpOcoHealthOk
    hardGateClear = $hardGateClear
    exactAuthorizationReviewAllowed = $hardGateClear
    envDeployRequestAllowed = $false
    hardGateChecklist = @(
        "micro live probe handoff ready",
        "maxOrders=1 and maxNotional cap preserved",
        "strategy574/TinyLive risk posture ready",
        "current DataFreshness clean",
        "TinyLive canEnableProduction=true",
        "TinyLive hard stop clear",
        "TP/SL/OCO preflight ready",
        "live review packet ready",
        "runtime evidence canonical shadow ready",
        "orderSentEvidence=0"
    )
    requiredBeforeAnyFutureMutation = @(
        "separate exact same-session operator authorization",
        "separate env/deploy authorization",
        "fresh post-env read-only verification plan",
        "fresh rollback/kill-switch readiness",
        "separate order-capable execution confirmation"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only high-risk micro live probe preflight review packet only; does not push, deploy, restart, reload nginx, change production env, enable TRADING_OKX, enable TinyLive execution, enable scheduler, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-high-risk-micro-live-probe-preflight-review] read-only packet"
Write-Host "scope=READ_ONLY; consumes local/read-only packet logs and optional runtime evidence logs only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host ("profit_high_risk_micro_live_probe_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_high_risk_micro_live_probe_preflight_status=$status"
Write-Host "profit_high_risk_micro_live_probe_preflight_decision=$decision"
Write-Host "source_micro_probe_handoff_status=$microStatus"
Write-Host "source_strategy574_tiny_live_preflight_status=$strategyStatus"
Write-Host "source_tp_sl_oco_preflight_status=$tpStatus"
Write-Host "strategy574_tiny_live_risk_posture=$strategyRiskPosture"
Write-Host "strategy574_data_freshness_current_clean=$strategyDataFreshnessClean"
Write-Host "tiny_live_can_enable_production=$strategyCanEnableProduction"
Write-Host "tiny_live_hard_stop_detected=$strategyHardStopDetected"
Write-Host "tp_sl_oco_trailing_acceptance=$tpTrailingAcceptance"
Write-Host "tp_sl_oco_health_ok=$tpOcoHealthOk"
Write-Host "live_review_packet_ready=$($liveReviewReady.ToString().ToLowerInvariant())"
Write-Host "live_readiness_bundle_verdict=$liveBundleVerdict"
Write-Host "runtime_evidence_ready=$($runtimeEvidenceReady.ToString().ToLowerInvariant())"
Write-Host "runtime_evidence_diagnosis=$runtimeDiagnosis"
Write-Host "runtime_order_sent_evidence=$runtimeOrderSentEvidence"
Write-Host ("micro_probe_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "micro_probe_hard_gate_clear=$($hardGateClear.ToString().ToLowerInvariant())"
Write-Host "micro_probe_exact_authorization_review_allowed=$($hardGateClear.ToString().ToLowerInvariant())"
Write-Host "micro_probe_env_deploy_request_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-high-risk-micro-live-probe-preflight-review] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION") {
    throw "High-risk micro live probe preflight is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
