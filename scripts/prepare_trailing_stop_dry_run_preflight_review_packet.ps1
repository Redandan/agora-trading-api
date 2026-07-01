param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$DecisionLogPath = "",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [decimal]$ReviewNotionalCapUsdt = 25,
    [int]$ObservationHours = 72,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for trailing-stop dry-run preflight arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$decisionScript = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_operator_decision_packet.ps1"
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing trailing-stop dry-run decision packet script: $decisionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for trailing-stop dry-run preflight." }

$decisionArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$decisionSourceMode = "FRESH_LOCAL_PACKET"
$decisionLogFreshnessStatus = "NOT_REQUESTED"
$decisionLogAgeMinutes = $null
$decisionOutput = @()
$decisionExitCode = 1
if (-not [string]::IsNullOrWhiteSpace($DecisionLogPath)) {
    $decisionSourceMode = "REUSED_DECISION_LOG"
    $resolvedDecisionLogPath = Resolve-RepoPath -PathValue $DecisionLogPath
    if (Test-Path -LiteralPath $resolvedDecisionLogPath) {
        $decisionLogItem = Get-Item -LiteralPath $resolvedDecisionLogPath
        $decisionLogAgeMinutes = [int]((Get-Date) - $decisionLogItem.LastWriteTime).TotalMinutes
        $decisionLogFreshnessStatus = if ($decisionLogAgeMinutes -le $MatrixMaxAgeMinutes) { "FRESH" } else { "STALE" }
        $decisionOutput = @(Get-Content -Raw -LiteralPath $resolvedDecisionLogPath)
        $decisionExitCode = 0
    } else {
        $decisionLogFreshnessStatus = "MISSING"
        $decisionOutput = @("trailing_stop_dry_run_decision_log_missing=$resolvedDecisionLogPath")
        $decisionExitCode = 1
    }
} else {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $decisionOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $decisionScript @decisionArgs 2>&1
        $decisionExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

$decisionText = ($decisionOutput | Out-String -Width 4096)
$decisionJson = Get-LastPrefixedValue -Text $decisionText -Prefix "trailing_stop_dry_run_operator_decision_packet="
$decisionPacket = $null
if (-not [string]::IsNullOrWhiteSpace($decisionJson)) {
    $decisionPacket = $decisionJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($decisionExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run operator decision packet completed" }
if ($decisionSourceMode -eq "REUSED_DECISION_LOG" -and $decisionLogFreshnessStatus -eq "MISSING") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run decision log present"
}
if ($decisionSourceMode -eq "REUSED_DECISION_LOG" -and $decisionLogFreshnessStatus -eq "STALE") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run decision log fresh"
}
if ($null -eq $decisionPacket) { Add-MissingRequirement -List $missingRequirements -Value "trailing_stop_dry_run_operator_decision_packet valid JSON" }

$sourceStatus = ""
$sourceMatrixFreshness = ""
$sourcePrimaryFocus = ""
if ($null -ne $decisionPacket) {
    $sourceStatus = [string]$decisionPacket.status
    $sourceMatrixFreshness = [string]$decisionPacket.sourceMatrixFreshnessStatus
    $sourcePrimaryFocus = [string]$decisionPacket.primaryFocus
}
if ($sourceStatus -ne "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing dry-run decision packet ready"
}
if ($sourceMatrixFreshness -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($sourcePrimaryFocus -ne "trailing-stop-dry-run-operator-review") {
    Add-MissingRequirement -List $missingRequirements -Value "source primary focus is trailing-stop-dry-run-operator-review"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_TRAILING_DRY_RUN_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to a trailing dry-run operator review; require separate explicit approval before any scheduler/env/runtime change."
} else {
    "Refresh the trailing dry-run decision packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_DRY_RUN_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourceDecisionPacket = "prepare_trailing_stop_dry_run_operator_decision_packet.ps1"
    sourceDecisionPacketStatus = $sourceStatus
    sourceMatrixFreshnessStatus = $sourceMatrixFreshness
    sourcePrimaryFocus = $sourcePrimaryFocus
    preflightDecision = if ($ready) { "PREPARE_DRY_RUN_ONLY_OPERATOR_REVIEW" } else { "REFRESH_SOURCE_DECISION_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        schedulerEnablementAllowed = $false
        liveTradingAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source trailing dry-run decision packet is ready",
        "source matrix freshness is FRESH",
        "review scope is dry-run-only and does not change runtime state",
        "operator names symbol, strategy id, observation window, and notional cap",
        "operator separately approves any future scheduler/env/runtime change",
        "post-review verification plan is read-only before any later mutation request"
    )
    requiredBeforeAnyFutureDryRunActivation = @(
        "separate explicit deploy/env authorization",
        "separate explicit scheduler dry-run authorization",
        "runtime env diff showing live/order/OCO mutation remains disabled",
        "fresh server-local health and MCP parity",
        "fresh trailing status and OCO health evidence",
        "rollback criteria for scheduler disablement"
    )
    explicitNonAuthorizations = @(
        "does not enable trailing scheduler",
        "does not enable live trading",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not close positions",
        "does not deploy",
        "does not change production env",
        "does not send Telegram",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    sourceDecisionPacketSummary = $decisionPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only trailing-stop dry-run preflight review packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[trailing-stop-dry-run-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; builds from prepare_trailing_stop_dry_run_operator_decision_packet.ps1 local output or a saved decision log only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $decisionText
Write-Host "source_decision_packet=prepare_trailing_stop_dry_run_operator_decision_packet.ps1 exitCode=$decisionExitCode"
Write-Host "source_decision_packet_mode=$decisionSourceMode"
Write-Host "source_decision_log_path=$DecisionLogPath"
Write-Host "source_decision_log_freshness_status=$decisionLogFreshnessStatus"
Write-Host "source_decision_log_age_minutes=$decisionLogAgeMinutes"
Write-Host "source_decision_packet_status=$sourceStatus"
Write-Host "source_matrix_freshness_status=$sourceMatrixFreshness"
Write-Host "trailing_stop_dry_run_preflight_primary_focus=$sourcePrimaryFocus"
Write-Host "trailing_stop_dry_run_preflight_decision=$($packet.preflightDecision)"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_dry_run_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("trailing_stop_dry_run_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_dry_run_preflight_status=$status"
Write-Host "trailing_stop_dry_run_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only trailing-stop dry-run preflight review packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[trailing-stop-dry-run-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop dry-run preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
