param(
    [string]$ExecutionLogPath = "",
    [string]$PostOptInReadinessLogPath = "",
    [string]$Strategy574GovernanceLogPath = "target/profit-review/strategy574-tiny-live-governance-operator-packet-latest.log",
    [string]$DataFreshnessReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
    [string]$Strategy485RiskLogPath = "target/profit-review/strategy485-position-risk-current.log",
    [string]$SignalCorrectnessLogPath = "target/profit-review/signal-correctness-current-refresh.log",
    [string]$TrailingObservationLogPath = "",
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 574,
    [switch]$NoRefresh,
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
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function ConvertFrom-JsonOrNull {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return $null }
    return ($Json | ConvertFrom-Json -ErrorAction Stop)
}

function Get-PacketValue {
    param([object]$Packet, [string]$Name)
    if ($null -eq $Packet) { return "" }
    $property = $Packet.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Read-OptionalLog {
    param([string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{
            Path = $resolved
            Exists = $false
            Text = ""
        }
    }
    return [pscustomobject]@{
        Path = $resolved
        Exists = $true
        Text = (Get-Content -Raw -LiteralPath $resolved)
    }
}

function Invoke-TrailingExecutionDryRun {
    $scriptPath = Join-Path $PSScriptRoot "execute_trailing_stop_strategy_opt_in_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing trailing opt-in execution wrapper: $scriptPath"
    }

    $arguments = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        StrategyId = $StrategyId
    }

    $output = @()
    $exitCode = 0
    $scriptSucceeded = $true
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @arguments *>&1
        $scriptSucceeded = $?
        $exitCode = if ($scriptSucceeded) { 0 } else { 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
        Source = "execute_trailing_stop_strategy_opt_in_ssh.ps1"
    }
}

function Invoke-TrailingPostOptInReadinessForBlocker {
    $scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing trailing post-opt-in readiness wrapper: $scriptPath"
    }

    $arguments = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        ExpectedOptInStrategyId = $StrategyId
    }

    $output = @()
    $exitCode = 0
    $scriptSucceeded = $true
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @arguments *>&1
        $scriptSucceeded = $?
        $exitCode = if ($scriptSucceeded) { 0 } else { 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
        Source = "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    }
}

function Invoke-TrailingDryRunObservationForBlocker {
    if (-not [string]::IsNullOrWhiteSpace($TrailingObservationLogPath)) {
        $observationLog = Read-OptionalLog -PathValue $TrailingObservationLogPath
        if (-not $observationLog.Exists) { throw "TrailingObservationLogPath not found: $($observationLog.Path)" }
        return [pscustomobject]@{
            Text = $observationLog.Text
            ExitCode = 0
            Source = $observationLog.Path
        }
    }

    if ($NoRefresh.IsPresent) {
        return $null
    }

    $scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_observation_status_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing trailing dry-run observation wrapper: $scriptPath"
    }

    $arguments = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        ExpectedOptInStrategyId = $StrategyId
    }

    $output = @()
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @arguments *>&1
        if (-not $?) { $exitCode = 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
        Source = "prepare_trailing_stop_dry_run_observation_status_ssh.ps1"
    }
}

function Convert-PostOptInReadinessToExecutionResult {
    param([object]$PostResult)

    $postJson = Get-LastPrefixedValue -Text $PostResult.Text -Prefix "trailing_stop_post_opt_in_readiness_packet="
    $postPacket = ConvertFrom-JsonOrNull -Json $postJson
    if ($PostResult.ExitCode -ne 0 -or $null -eq $postPacket) {
        return $null
    }

    $postStatus = Get-PacketValue -Packet $postPacket -Name "status"
    if ($postStatus -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -and
        $postStatus -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
        return $null
    }

    $executionStatus = if ($postStatus -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
        "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY"
    } else {
        "ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW"
    }
    $executionDecision = if ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY") {
        "VERIFY_ACTIVE_DRY_RUN_OBSERVATION_ONLY"
    } else {
        "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
    }
    $nextRequiredAuthorization = if ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY") {
        "dry-run is already active; continue read-only observation and runtime-log verification only"
    } else {
        "request separate authorization for TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, then deploy/restart and run read-only verification"
    }

    $syntheticPacket = [pscustomobject]@{
        packetType = "TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET"
        status = $executionStatus
        symbol = $Symbol
        strategyId = $StrategyId
        executeRequested = $false
        requiredConfirmText = "EXECUTE_TRAILING_STOP_OPT_IN_$StrategyId"
        trailingAcceptance = Get-PacketValue -Packet $postPacket -Name "trailingAcceptance"
        trailingImprovementPct = Get-PacketValue -Packet $postPacket -Name "trailingImprovementPct"
        trailingDeltaPnl = Get-PacketValue -Packet $postPacket -Name "trailingDeltaPnl"
        strategyOptInWritePerformed = $false
        sourcePostOptInStatus = $postStatus
        postOptInReadinessStatus = $postStatus
        postOptInReadinessDecision = Get-LastPrefixedValue -Text $PostResult.Text -Prefix "trailing_stop_post_opt_in_readiness_decision="
        currentGlobalEnabled = Get-PacketValue -Packet $postPacket -Name "currentGlobalEnabled"
        currentGlobalDryRun = Get-PacketValue -Packet $postPacket -Name "currentGlobalDryRun"
        nextRequiredAuthorization = $nextRequiredAuthorization
        missingRequirements = @()
    }

    $syntheticText = @(
        "trailing_stop_strategy_opt_in_execution_status=$executionStatus",
        "trailing_stop_strategy_opt_in_execution_decision=$executionDecision",
        "trailing_stop_strategy_opt_in_execution_required_confirm_text=EXECUTE_TRAILING_STOP_OPT_IN_$StrategyId",
        ("trailing_stop_acceptance=" + $syntheticPacket.trailingAcceptance),
        ("trailing_stop_improvement_pct=" + $syntheticPacket.trailingImprovementPct),
        ("trailing_stop_delta_pnl=" + $syntheticPacket.trailingDeltaPnl),
        "trailing_stop_strategy_opt_in_execution_write_performed=false",
        ("trailing_stop_strategy_opt_in_execution_packet=" + (ConvertTo-Json -Compress -Depth 8 $syntheticPacket))
    ) -join "`n"

    return [pscustomobject]@{
        Text = $syntheticText
        ExitCode = 0
        Source = "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    }
}

if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit next execution blocker packet arguments."
}

$repoRoot = Split-Path -Parent $PSScriptRoot

$executionSource = "child_dry_run"
$executionResult = $null
if (-not [string]::IsNullOrWhiteSpace($ExecutionLogPath)) {
    $executionLog = Read-OptionalLog -PathValue $ExecutionLogPath
    if (-not $executionLog.Exists) { throw "ExecutionLogPath not found: $($executionLog.Path)" }
    $executionResult = [pscustomobject]@{
        Text = $executionLog.Text
        ExitCode = 0
        Source = $executionLog.Path
    }
    $executionSource = "source_log"
} elseif (-not [string]::IsNullOrWhiteSpace($PostOptInReadinessLogPath)) {
    $postOptInLog = Read-OptionalLog -PathValue $PostOptInReadinessLogPath
    if (-not $postOptInLog.Exists) { throw "PostOptInReadinessLogPath not found: $($postOptInLog.Path)" }
    $postProbeResult = [pscustomobject]@{
        Text = $postOptInLog.Text
        ExitCode = 0
        Source = $postOptInLog.Path
    }
    $executionResult = Convert-PostOptInReadinessToExecutionResult -PostResult $postProbeResult
    if ($null -eq $executionResult) {
        throw "PostOptInReadinessLogPath did not contain a usable trailing_stop_post_opt_in_readiness_packet: $($postOptInLog.Path)"
    }
    $executionSource = "post_opt_in_readiness_log"
} elseif ($NoRefresh.IsPresent) {
    throw "ExecutionLogPath or PostOptInReadinessLogPath is required when -NoRefresh is used."
} else {
    $postProbeResult = Invoke-TrailingPostOptInReadinessForBlocker
    $executionResult = Convert-PostOptInReadinessToExecutionResult -PostResult $postProbeResult
    if ($null -ne $executionResult) {
        $executionSource = "post_opt_in_readiness"
    } else {
        $executionResult = Invoke-TrailingExecutionDryRun
    }
}

$executionJson = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_strategy_opt_in_execution_packet="
$executionPacket = ConvertFrom-JsonOrNull -Json $executionJson
$executionStatus = Get-PacketValue -Packet $executionPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($executionStatus)) {
    $executionStatus = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_strategy_opt_in_execution_status="
}
$executionDecision = Get-PacketValue -Packet $executionPacket -Name "decision"
if ([string]::IsNullOrWhiteSpace($executionDecision)) {
    $executionDecision = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_strategy_opt_in_execution_decision="
}
$requiredConfirmText = Get-PacketValue -Packet $executionPacket -Name "requiredConfirmText"
if ([string]::IsNullOrWhiteSpace($requiredConfirmText)) {
    $requiredConfirmText = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_strategy_opt_in_execution_required_confirm_text="
}
$trailingAcceptance = Get-PacketValue -Packet $executionPacket -Name "trailingAcceptance"
if ([string]::IsNullOrWhiteSpace($trailingAcceptance)) {
    $trailingAcceptance = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_acceptance="
}
$trailingImprovementPct = Get-PacketValue -Packet $executionPacket -Name "trailingImprovementPct"
if ([string]::IsNullOrWhiteSpace($trailingImprovementPct)) {
    $trailingImprovementPct = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_improvement_pct="
}
$trailingDeltaPnl = Get-PacketValue -Packet $executionPacket -Name "trailingDeltaPnl"
if ([string]::IsNullOrWhiteSpace($trailingDeltaPnl)) {
    $trailingDeltaPnl = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_delta_pnl="
}
$writePerformed = Get-PacketValue -Packet $executionPacket -Name "strategyOptInWritePerformed"
if ([string]::IsNullOrWhiteSpace($writePerformed)) {
    $writePerformed = Get-LastPrefixedValue -Text $executionResult.Text -Prefix "trailing_stop_strategy_opt_in_execution_write_performed="
}
$nextRequiredAuthorization = Get-PacketValue -Packet $executionPacket -Name "nextRequiredAuthorization"

$observationResult = $null
$observationPacket = $null
$observationStatus = ""
$observationSampleReady = ""
$observationSampleCollectionBlockedBy = ""
$observationUniqueBlocker = ""
$observationOpenOcoPositions = ""
$observationPreconditionsReady = ""
$observationExactRefreshCommand = ""
$observationNextAction = ""
if ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY") {
    $observationResult = Invoke-TrailingDryRunObservationForBlocker
    if ($null -ne $observationResult) {
        $observationJson = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_status_packet="
        $observationPacket = ConvertFrom-JsonOrNull -Json $observationJson
        $observationStatus = Get-PacketValue -Packet $observationPacket -Name "status"
        if ([string]::IsNullOrWhiteSpace($observationStatus)) {
            $observationStatus = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_status="
        }
        $observationSampleReady = Get-PacketValue -Packet $observationPacket -Name "observationSampleReady"
        if ([string]::IsNullOrWhiteSpace($observationSampleReady)) {
            $observationSampleReady = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_sample_ready="
        }
        $observationSampleCollectionBlockedBy = Get-PacketValue -Packet $observationPacket -Name "sampleCollectionBlockedBy"
        if ([string]::IsNullOrWhiteSpace($observationSampleCollectionBlockedBy)) {
            $observationSampleCollectionBlockedBy = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_sample_collection_blocked_by="
        }
        $observationUniqueBlocker = Get-PacketValue -Packet $observationPacket -Name "uniqueBlocker"
        if ([string]::IsNullOrWhiteSpace($observationUniqueBlocker)) {
            $observationUniqueBlocker = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_unique_blocker="
        }
        $observationOpenOcoPositions = Get-PacketValue -Packet $observationPacket -Name "currentOpenOcoPositions"
        if ([string]::IsNullOrWhiteSpace($observationOpenOcoPositions)) {
            $observationOpenOcoPositions = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_current_open_oco_positions="
        }
        $observationPreconditionsReady = Get-PacketValue -Packet $observationPacket -Name "observationPreconditionsReady"
        if ([string]::IsNullOrWhiteSpace($observationPreconditionsReady)) {
            $observationPreconditionsReady = Get-LastPrefixedValue -Text $observationResult.Text -Prefix "trailing_stop_dry_run_observation_preconditions_ready="
        }
        $observationExactRefreshCommand = Get-PacketValue -Packet $observationPacket -Name "exactRefreshCommand"
        $observationNextAction = Get-PacketValue -Packet $observationPacket -Name "nextAction"
    }
}

$strategy574Log = Read-OptionalLog -PathValue $Strategy574GovernanceLogPath
$strategy574Packet = ConvertFrom-JsonOrNull -Json (Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_tiny_live_governance_operator_packet=")
$strategy574Status = Get-PacketValue -Packet $strategy574Packet -Name "status"
if ([string]::IsNullOrWhiteSpace($strategy574Status)) {
    $strategy574Status = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_tiny_live_governance_status="
}
$strategy574RiskPosture = Get-PacketValue -Packet $strategy574Packet -Name "riskPosture"
if ([string]::IsNullOrWhiteSpace($strategy574RiskPosture)) {
    $strategy574RiskPosture = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_tiny_live_risk_posture="
}
$nearThresholdRecommendation = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_near_threshold_shadow_recommendation="
$nearThresholdFalsePositiveRatePct = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_near_threshold_false_positive_rate_pct="
$tinyLiveCanEnableProduction = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "tiny_live_can_enable_production="
$strategy574ThresholdRelaxationAllowed = Get-LastPrefixedValue -Text $strategy574Log.Text -Prefix "strategy574_near_threshold_threshold_relaxation_allowed="

$dfLog = Read-OptionalLog -PathValue $DataFreshnessReadinessLogPath
$dfPacket = ConvertFrom-JsonOrNull -Json (Get-LastPrefixedValue -Text $dfLog.Text -Prefix "data_freshness_replay_evidence_readiness_packet=")
$dfStatus = Get-PacketValue -Packet $dfPacket -Name "status"
if ([string]::IsNullOrWhiteSpace($dfStatus)) {
    $dfStatus = Get-LastPrefixedValue -Text $dfLog.Text -Prefix "data_freshness_replay_evidence_readiness_status="
}
$replayCandidateRows = Get-LastPrefixedValue -Text $dfLog.Text -Prefix "replay_candidate_id_rows="
$completeReplayableRows = Get-LastPrefixedValue -Text $dfLog.Text -Prefix "complete_replayable_candidate_rows="
$latestDataFreshnessAgeHours = Get-LastPrefixedValue -Text $dfLog.Text -Prefix "latest_data_freshness_row_age_hours="

$strategy485Log = Read-OptionalLog -PathValue $Strategy485RiskLogPath
$strategy485Decision = ConvertFrom-JsonOrNull -Json (Get-LastPrefixedValue -Text $strategy485Log.Text -Prefix "  strategy485_position_review_decision=")
if ($null -eq $strategy485Decision) {
    $strategy485Decision = ConvertFrom-JsonOrNull -Json (Get-LastPrefixedValue -Text $strategy485Log.Text -Prefix "strategy485_position_review_decision=")
}
$strategy485Recommendation = Get-LastPrefixedValue -Text $strategy485Log.Text -Prefix "  strategy485_position_risk_recommendation="
if ([string]::IsNullOrWhiteSpace($strategy485Recommendation)) {
    $strategy485Recommendation = Get-LastPrefixedValue -Text $strategy485Log.Text -Prefix "strategy485_position_risk_recommendation="
}
$strategy485NegativeEvPositions = Get-LastPrefixedValue -Text $strategy485Log.Text -Prefix "  negativeEvPositions="
if ([string]::IsNullOrWhiteSpace($strategy485NegativeEvPositions) -and $null -ne $strategy485Decision) {
    $strategy485NegativeEvPositions = Get-PacketValue -Packet $strategy485Decision -Name "negativeEvPositionCount"
}

$signalLog = Read-OptionalLog -PathValue $SignalCorrectnessLogPath
$signalPolicyClear = Get-LastPrefixedValue -Text $signalLog.Text -Prefix "signalPolicyClear="
$signalRecommendedFix = Get-LastPrefixedValue -Text $signalLog.Text -Prefix "recommendedFix="

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($executionResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "trailing opt-in execution dry-run wrapper completed" }
if ($null -eq $executionPacket) { Add-Unique -List $missingRequirements -Value "trailing_stop_strategy_opt_in_execution_packet valid JSON" }
if ([string]::IsNullOrWhiteSpace($executionStatus)) { Add-Unique -List $missingRequirements -Value "trailing opt-in execution status present" }
if ($trailingAcceptance -ne "PASS") { Add-Unique -List $missingRequirements -Value "trailing replay acceptance=PASS" }
if ([string]::IsNullOrWhiteSpace($requiredConfirmText)) { Add-Unique -List $missingRequirements -Value "required confirm text present" }
if (-not [string]::IsNullOrWhiteSpace($requiredConfirmText) -and $requiredConfirmText -ne "EXECUTE_TRAILING_STOP_OPT_IN_$StrategyId") {
    Add-Unique -List $missingRequirements -Value "required confirm text matches EXECUTE_TRAILING_STOP_OPT_IN_$StrategyId"
}

$executionEvidenceRefreshCommand = ".\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId $StrategyId -RequireReady"
$unlockCommand = if ([string]::IsNullOrWhiteSpace($requiredConfirmText)) {
    $executionEvidenceRefreshCommand
} else {
    ".\scripts\execute_trailing_stop_strategy_opt_in_ssh.ps1 -StrategyId $StrategyId -Execute -ConfirmText $requiredConfirmText -RequireReady"
}
$uniqueBlocker = ""
$status = "NOT_READY"
$goalSatisfied = $false
$nextAction = ""
if ($executionStatus -eq "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION" -and $executionDecision -eq "AWAIT_EXPLICIT_EXECUTE_CONFIRMATION" -and $missingRequirements.Count -eq 0) {
    $status = "BLOCKED_AWAIT_EXPLICIT_EXECUTE_CONFIRMATION"
    $uniqueBlocker = "AWAIT_EXPLICIT_EXECUTE_CONFIRMATION"
    $nextAction = "Obtain separate explicit authorization, then run exactly the unlock command; follow with post-opt-in read-only verification."
} elseif ($executionStatus -eq "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW" -and $missingRequirements.Count -eq 0) {
    $status = "BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
    $uniqueBlocker = "AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
    $unlockCommand = ".\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady"
    $nextAction = "Do not enable live orders; request separate trailing dry-run env diff and deploy authorization."
} elseif ($executionStatus -eq "ALREADY_OPTED_IN_READY_FOR_ENV_DIFF_REVIEW" -and $missingRequirements.Count -eq 0) {
    $status = "BLOCKED_AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
    $uniqueBlocker = "AWAIT_SEPARATE_TRAILING_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
    $unlockCommand = ".\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady"
    $nextAction = "Strategy opt-in is already applied; request separate trailing dry-run env diff and deploy authorization."
} elseif ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY" -and $missingRequirements.Count -eq 0) {
    $status = "TRAILING_DRY_RUN_ACTIVE_READ_ONLY_OBSERVATION"
    $uniqueBlocker = "COLLECT_TRAILING_DRY_RUN_OBSERVATION_SAMPLE"
    $unlockCommand = ".\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId $StrategyId -RequireReady"
    $nextAction = "Collect dry-run observation evidence and runtime-log smoke before any later live trailing review."
    if (-not [string]::IsNullOrWhiteSpace($observationExactRefreshCommand)) {
        $unlockCommand = $observationExactRefreshCommand
    }
    if (-not [string]::IsNullOrWhiteSpace($observationNextAction)) {
        $nextAction = $observationNextAction
    }
    if (-not [string]::IsNullOrWhiteSpace($observationUniqueBlocker) -and $observationUniqueBlocker -ne "NONE") {
        $uniqueBlocker = $observationUniqueBlocker
    } elseif ($observationSampleReady -eq "true") {
        $uniqueBlocker = "COLLECT_TRAILING_DRY_RUN_SUGGESTED_EXIT_AND_FORWARD_OUTCOME"
    } elseif ($null -ne $observationResult -and ($observationResult.ExitCode -ne 0 -or $null -eq $observationPacket)) {
        $uniqueBlocker = "FIX_TRAILING_DRY_RUN_OBSERVATION_EVIDENCE"
        $nextAction = "Refresh the trailing dry-run observation status packet before any later live trailing review."
    }
} else {
    $uniqueBlocker = "FIX_TRAILING_OPT_IN_EVIDENCE"
    $unlockCommand = $executionEvidenceRefreshCommand
    $nextAction = "Refresh the non-mutating trailing opt-in execution dry-run and fix missing evidence before any execution request."
}

$alternativeEvidence = @(
    [pscustomobject]@{
        route = "strategy574-threshold-or-tinylive-relaxation"
        currentVerdict = "NOT_RECOMMENDED"
        sourceStatus = $strategy574Status
        evidence = @(
            "riskPosture=$strategy574RiskPosture",
            "nearThresholdRecommendation=$nearThresholdRecommendation",
            "falsePositiveRatePct=$nearThresholdFalsePositiveRatePct",
            "tinyLiveCanEnableProduction=$tinyLiveCanEnableProduction",
            "thresholdRelaxationAllowed=$strategy574ThresholdRelaxationAllowed"
        )
        reason = "near-threshold evidence is high false-positive risk and TinyLive cannot enable production from current evidence"
    },
    [pscustomobject]@{
        route = "data-freshness-entry-policy-relaxation"
        currentVerdict = "BLOCKED"
        sourceStatus = $dfStatus
        evidence = @(
            "replay_candidate_id_rows=$replayCandidateRows",
            "complete_replayable_candidate_rows=$completeReplayableRows",
            "latest_data_freshness_row_age_hours=$latestDataFreshnessAgeHours"
        )
        reason = "counterfactual replay evidence is missing; live DataFreshness relaxation remains unsafe"
    },
    [pscustomobject]@{
        route = "strategy485-position-risk-mutation"
        currentVerdict = "NO_CURRENT_MUTATION_ROUTE"
        sourceStatus = $strategy485Recommendation
        evidence = @(
            "negativeEvPositions=$strategy485NegativeEvPositions"
        )
        reason = "no open negative-EV strategy485 position is available for a current close or OCO review"
    },
    [pscustomobject]@{
        route = "general-live-policy-relaxation"
        currentVerdict = "BLOCKED"
        sourceStatus = if ([string]::IsNullOrWhiteSpace($signalPolicyClear)) { "unknown" } else { "signalPolicyClear=$signalPolicyClear" }
        evidence = @(
            "signalPolicyClear=$signalPolicyClear",
            "recommendedFix=$signalRecommendedFix"
        )
        reason = "current signal-policy evidence has not cleared live relaxation"
    }
)

$packet = [pscustomobject]@{
    packetType = "PROFIT_NEXT_EXECUTION_BLOCKER_PACKET"
    status = $status
    symbol = $Symbol
    goal = "make-money"
    goalSatisfied = $goalSatisfied
    profitRoute = if ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY") { "TRAILING_STOP_DRY_RUN_OBSERVATION" } else { "TRAILING_STOP_STRATEGY574_OPT_IN" }
    profitRouteReason = if ($executionStatus -eq "ALREADY_OPTED_IN_DRY_RUN_ACTIVE_READ_ONLY_VERIFY") {
        "highest-ROI trailing lane is deployed in dry-run; next value is observation evidence before any live OCO mutation"
    } elseif ($trailingAcceptance -ne "PASS") {
        "trailing opt-in evidence is not ready; refresh replay and post-opt-in readiness before treating this lane as executable"
    } else {
        "highest-ROI route with quantified trailing replay PASS and no live order/OCO/scheduler/env mutation in the first step"
    }
    uniqueBlocker = $uniqueBlocker
    exactUnlockCommand = $unlockCommand
    sourceExecution = [pscustomobject]@{
        sourceType = $executionSource
        source = $executionResult.Source
        exitCode = $executionResult.ExitCode
        status = $executionStatus
        decision = $executionDecision
        writePerformed = $writePerformed
        requiredConfirmText = $requiredConfirmText
        trailingAcceptance = $trailingAcceptance
        trailingImprovementPct = $trailingImprovementPct
        trailingDeltaPnl = $trailingDeltaPnl
        nextRequiredAuthorization = $nextRequiredAuthorization
    }
    sourceObservation = if ($null -eq $observationResult) {
        $null
    } else {
        [pscustomobject]@{
            source = $observationResult.Source
            exitCode = $observationResult.ExitCode
            status = $observationStatus
            preconditionsReady = $observationPreconditionsReady
            sampleReady = $observationSampleReady
            sampleCollectionBlockedBy = $observationSampleCollectionBlockedBy
            uniqueBlocker = $observationUniqueBlocker
            currentOpenOcoPositions = $observationOpenOcoPositions
            exactRefreshCommand = $observationExactRefreshCommand
        }
    }
    negativeAlternativeEvidence = @($alternativeEvidence)
    postUnlockReadOnlyVerification = @(
        ".\scripts\prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1 -RequireReady",
        ".\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -ExpectedOptInStrategyId $StrategyId -RequireReady",
        ".\scripts\prepare_trailing_stop_dry_run_observation_status_ssh.ps1 -ExpectedOptInStrategyId $StrategyId -RequireReady",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
        "server-local MCP getStrategyConfig confirms strategy $StrategyId trailingStopEnabled=true",
        "runtime-log smoke confirms no order/OCO/grid/fund/Earn/Telegram/exchange mutation"
    )
    allowedActions = @(
        "read-only evidence refresh",
        "operator review",
        "request separate explicit execute authorization"
    )
    forbiddenActions = @(
        "enable live trading",
        "enable scheduler",
        "place orders",
        "execute TinyLive",
        "send Telegram",
        "modify or cancel OCO",
        "close positions",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only profit next execution blocker packet only; does not authorize setTrailingStopOptIn execution, live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[profit-next-execution-blocker-packet] read-only packet"
Write-Host "scope=READ_ONLY; default refresh invokes post-opt-in readiness first, then the non-mutating trailing opt-in dry-run wrapper only when needed; no -Execute, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "profit_next_execution_goal_satisfied=false"
Write-Host ("profit_next_execution_route=" + $packet.profitRoute)
Write-Host "profit_next_execution_source_status=$executionStatus"
Write-Host "profit_next_execution_source_decision=$executionDecision"
Write-Host "profit_next_execution_trailing_acceptance=$trailingAcceptance"
Write-Host "profit_next_execution_trailing_improvement_pct=$trailingImprovementPct"
Write-Host "profit_next_execution_trailing_delta_pnl=$trailingDeltaPnl"
Write-Host "profit_next_execution_observation_status=$observationStatus"
Write-Host "profit_next_execution_observation_preconditions_ready=$observationPreconditionsReady"
Write-Host "profit_next_execution_observation_sample_ready=$observationSampleReady"
Write-Host "profit_next_execution_sample_collection_blocked_by=$observationSampleCollectionBlockedBy"
Write-Host "profit_next_execution_open_oco_positions=$observationOpenOcoPositions"
Write-Host "profit_next_execution_observation_unique_blocker=$observationUniqueBlocker"
Write-Host "profit_next_execution_unique_blocker=$uniqueBlocker"
Write-Host "profit_next_execution_exact_unlock_command=$unlockCommand"
Write-Host "strategy574_near_threshold_shadow_recommendation=$nearThresholdRecommendation"
Write-Host "strategy574_near_threshold_false_positive_rate_pct=$nearThresholdFalsePositiveRatePct"
Write-Host "tiny_live_can_enable_production=$tinyLiveCanEnableProduction"
Write-Host "data_freshness_replay_candidate_id_rows=$replayCandidateRows"
Write-Host "data_freshness_complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host "strategy485_negative_ev_positions=$strategy485NegativeEvPositions"
Write-Host ("profit_next_execution_alternative_evidence=" + (ConvertTo-Json -Compress -Depth 8 @($alternativeEvidence)))
Write-Host ("profit_next_execution_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_next_execution_blocker_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_next_execution_blocker_status=$status"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only profit next execution blocker packet only; does not authorize setTrailingStopOptIn execution, live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[profit-next-execution-blocker-packet] read-only check complete"

if ($RequireReady -and $status -eq "NOT_READY") {
    throw "Profit next execution blocker packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
