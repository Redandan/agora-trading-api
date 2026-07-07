param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
    [int]$ChildTimeoutSeconds = 900,
    [int]$MatrixTimeoutSeconds = 0,
    [string]$MatrixOutputPath = "",
    [string]$SaveMatrixOutputPath = "",
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
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
        throw "$Name contains unsupported characters for profit operator action brief arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Get-MatrixFreshness {
    param([string]$Path, [int]$MaxAgeMinutes)

    $item = Get-Item -LiteralPath $Path
    $ageMinutes = [int]((Get-Date) - $item.LastWriteTime).TotalMinutes
    $status = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
    return [pscustomobject]@{
        AgeMinutes = $ageMinutes
        MaxAgeMinutes = $MaxAgeMinutes
        Status = $status
        LastWriteTime = $item.LastWriteTime.ToString("o")
    }
}

function Get-DefaultMatrixOutputPath {
    param([string]$OutputDir, [string]$SymbolValue, [int]$StrategyValue)

    $safeSymbol = ($SymbolValue -replace "[^A-Za-z0-9._-]", "_")
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
    $fileName = "profit-operator-matrix-$timestamp-$safeSymbol-strategy$StrategyValue.log"
    return (Join-Path $OutputDir $fileName)
}

function Save-MatrixOutput {
    param(
        [string]$Path,
        [string]$Text,
        [string]$OutputDir,
        [bool]$UpdateLatestPointer = $true
    )

    $parent = Split-Path -Parent $Path
    if ([string]::IsNullOrWhiteSpace($parent)) {
        $parent = "."
    }
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Set-Content -LiteralPath $Path -Value $Text -Encoding UTF8

    if (-not $UpdateLatestPointer) {
        return $null
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $pointerPath = Join-Path $OutputDir "latest-profit-operator-matrix.path"
    Set-Content -LiteralPath $pointerPath -Value $Path -Encoding UTF8
    return $pointerPath
}

function Get-MatrixRejectReason {
    param([object]$Matrix, [string]$MatrixStatus, [object]$MatrixPacket)

    $reasons = [System.Collections.Generic.List[string]]::new()
    if ($Matrix.ExitCode -ne 0) {
        $reasons.Add("exitCode=$($Matrix.ExitCode)")
    }
    if ($Matrix.TimedOut) {
        $reasons.Add("timedOut=true")
    }
    if ([string]::IsNullOrWhiteSpace($MatrixStatus)) {
        $reasons.Add("missingStatus")
    }
    if ($null -eq $MatrixPacket) {
        $reasons.Add("missingPacket")
    }
    if ($reasons.Count -eq 0) {
        return "none"
    }
    return ($reasons -join ",")
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments, [int]$TimeoutSeconds)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator action brief."
    }

    Write-Host "[profit-operator-action-brief] child_start script=$ScriptName timeoutSeconds=$TimeoutSeconds"
    $startedAt = Get-Date
    $timedOut = $false
    $stdout = ""
    $stderr = ""
    $exitCode = 1
    $job = $null
    try {
        $job = Start-Job -ScriptBlock {
            param(
                [string]$PowerShellSource,
                [string]$ChildScriptPath,
                [string]$WorkingDirectory,
                [object[]]$ChildArguments
            )
            $ErrorActionPreference = "Continue"
            Set-Location -LiteralPath $WorkingDirectory
            $output = & $PowerShellSource -NoProfile -ExecutionPolicy Bypass -File $ChildScriptPath @ChildArguments 2>&1
            $childSuccess = $?
            $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($childSuccess) { 0 } else { 1 }
            [pscustomobject]@{
                Text = ($output | Out-String -Width 4096)
                ExitCode = $code
            }
        } -ArgumentList @($powerShell.Source, $scriptPath, (Get-Location).Path, (, @($Arguments)))

        $lastHeartbeatSeconds = 0
        while ($job.State -eq "Running") {
            $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            if ($elapsedSeconds -ge $TimeoutSeconds) {
                $timedOut = $true
                Stop-Job -Job $job -ErrorAction SilentlyContinue
                break
            }
            if (($elapsedSeconds - $lastHeartbeatSeconds) -ge 30) {
                Write-Host "[profit-operator-action-brief] child_heartbeat script=$ScriptName elapsedSeconds=$elapsedSeconds"
                $lastHeartbeatSeconds = $elapsedSeconds
            }
            Start-Sleep -Seconds 5
            $job = Get-Job -Id $job.Id
        }

        if (-not $timedOut) {
            $jobOutput = @(Receive-Job -Job $job)
            $result = @($jobOutput | Where-Object { $null -ne $_ -and $null -ne $_.PSObject.Properties["ExitCode"] } | Select-Object -Last 1)
            if ($result) {
                $stdout = [string]$result[0].Text
                $exitCode = [int]$result[0].ExitCode
            } else {
                $exitCode = 1
                $stderr = ($jobOutput | Out-String -Width 4096)
            }
        } else {
            $exitCode = -1
        }

        $elapsedTotalSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
        Write-Host "[profit-operator-action-brief] child_complete script=$ScriptName exitCode=$exitCode timedOut=$($timedOut.ToString().ToLowerInvariant()) elapsedSeconds=$elapsedTotalSeconds"
        if ($exitCode -ne 0 -or $timedOut) {
            $summarySource = (($stderr, $stdout) -join "`n").Trim()
            $summary = if ($summarySource.Length -gt 600) { $summarySource.Substring(0, 600) } else { $summarySource }
            $summary = ($summary -replace "`r?`n", " | ")
            Write-Host "child_error_summary script=$ScriptName summary=$summary"
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }
    return [pscustomobject]@{
        Text = (($stdout, $stderr) -join "`n")
        ExitCode = $exitCode
        TimedOut = $timedOut
    }
}

if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) {
    throw "ChildTimeoutSeconds must be between 60 and 3600."
}
if ($MatrixTimeoutSeconds -ne 0 -and ($MatrixTimeoutSeconds -lt $ChildTimeoutSeconds -or $MatrixTimeoutSeconds -gt 14400)) {
    throw "MatrixTimeoutSeconds must be 0 or between ChildTimeoutSeconds and 14400."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}
if (-not [string]::IsNullOrWhiteSpace($MatrixOutputPath) -and -not (Test-Path -LiteralPath $MatrixOutputPath)) {
    throw "MatrixOutputPath not found: $MatrixOutputPath"
}

Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$effectiveMatrixTimeoutSeconds = if ($MatrixTimeoutSeconds -gt 0) {
    $MatrixTimeoutSeconds
} else {
    [Math]::Min(14400, [Math]::Max($ChildTimeoutSeconds, ($ChildTimeoutSeconds * 4) + 300))
}

$sourceMatrixMode = "FRESH_CHILD_RUN"
$matrixSavePath = ""
$matrixSavedOutputPath = $null
$matrixLatestPointerPath = $null
$matrixLatestPointerUpdated = $false
$matrixRejectReason = $null
$matrixFreshness = [pscustomobject]@{
    AgeMinutes = $null
    MaxAgeMinutes = $MatrixMaxAgeMinutes
    Status = "NOT_APPLICABLE"
    LastWriteTime = $null
}
if (-not [string]::IsNullOrWhiteSpace($MatrixOutputPath)) {
    $sourceMatrixMode = "REUSED_OUTPUT_FILE"
    $matrixFreshness = Get-MatrixFreshness -Path $MatrixOutputPath -MaxAgeMinutes $MatrixMaxAgeMinutes
    Write-Host "[profit-operator-action-brief] matrix_reuse path=$MatrixOutputPath"
    Write-Host "matrix_freshness_status=$($matrixFreshness.Status)"
    Write-Host "matrix_age_minutes=$($matrixFreshness.AgeMinutes)"
    Write-Host "matrix_max_age_minutes=$($matrixFreshness.MaxAgeMinutes)"
    if ($matrixFreshness.Status -ne "FRESH") {
        throw "MatrixOutputPath is stale: ageMinutes=$($matrixFreshness.AgeMinutes) maxAgeMinutes=$($matrixFreshness.MaxAgeMinutes). Rerun without -MatrixOutputPath or raise -MatrixMaxAgeMinutes only for explicit diagnostic review."
    }
    $matrix = [pscustomobject]@{
        Text = Get-Content -Raw -LiteralPath $MatrixOutputPath
        ExitCode = 0
        TimedOut = $false
    }
} else {
    if ([string]::IsNullOrWhiteSpace($SshHost)) {
        throw "SshHost is required. Pass -SshHost, set AGORA_SSH_HOST, or pass -MatrixOutputPath."
    }
    if ([string]::IsNullOrWhiteSpace($SshKey)) {
        throw "SshKey is required. Pass -SshKey, set AGORA_SSH_KEY, or pass -MatrixOutputPath."
    }
    if (-not (Test-Path -LiteralPath $SshKey)) {
        throw "SSH key not found: $SshKey"
    }

    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

    $matrix = Invoke-ReadOnlyScript -ScriptName "prepare_profit_operator_review_matrix_ssh.ps1" -Arguments @(
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir,
        "-EnvFile", $EnvFile,
        "-Symbol", $Symbol,
        "-StrategyId", "$StrategyId",
        "-ReplayDays", "$ReplayDays",
        "-ReplayLimit", "$ReplayLimit",
        "-ChildTimeoutSeconds", "$ChildTimeoutSeconds"
    ) -TimeoutSeconds $effectiveMatrixTimeoutSeconds

    if (-not [string]::IsNullOrWhiteSpace($SaveMatrixOutputPath)) {
        $matrixSavePath = $SaveMatrixOutputPath
    } else {
        $matrixSavePath = Get-DefaultMatrixOutputPath -OutputDir $ReviewOutputDir -SymbolValue $Symbol -StrategyValue $StrategyId
    }
}

$matrixStatus = Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_status="
$matrixPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_packet=")
$matrixNextAction = Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_next_action="

if ($sourceMatrixMode -eq "FRESH_CHILD_RUN") {
    $matrixRejectReason = Get-MatrixRejectReason -Matrix $matrix -MatrixStatus $matrixStatus -MatrixPacket $matrixPacket
    if ($matrixRejectReason -eq "none") {
        $matrixLatestPointerPath = Save-MatrixOutput -Path $matrixSavePath -Text $matrix.Text -OutputDir $ReviewOutputDir -UpdateLatestPointer $true
        $matrixSavedOutputPath = $matrixSavePath
        $matrixLatestPointerUpdated = $true
        Write-Host "[profit-operator-action-brief] matrix_saved path=$matrixSavePath"
        Write-Host "[profit-operator-action-brief] matrix_latest_pointer path=$matrixLatestPointerPath"
    } else {
        $null = Save-MatrixOutput -Path $matrixSavePath -Text $matrix.Text -OutputDir $ReviewOutputDir -UpdateLatestPointer $false
        $matrixSavedOutputPath = $matrixSavePath
        Write-Host "[profit-operator-action-brief] matrix_output_rejected reason=$matrixRejectReason"
        Write-Host "[profit-operator-action-brief] matrix_failed_output_saved path=$matrixSavePath"
        Write-Host "[profit-operator-action-brief] matrix_latest_pointer_skipped reason=$matrixRejectReason"
    }
}

$signalMissedMode = "NOT_COLLECTED_REUSED_MATRIX"
$signalMissed = [pscustomobject]@{
    Text = ""
    ExitCode = 0
    TimedOut = $false
}
if ($sourceMatrixMode -eq "FRESH_CHILD_RUN") {
    $signalMissedMode = "FRESH_CHILD_RUN"
    $signalMissed = Invoke-ReadOnlyScript -ScriptName "prepare_signal_missed_blocker_decision_brief_ssh.ps1" -Arguments @(
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir,
        "-EnvFile", $EnvFile,
        "-Symbol", $Symbol,
        "-ChildTimeoutSeconds", "$ChildTimeoutSeconds",
        "-RequireBrief"
    ) -TimeoutSeconds $ChildTimeoutSeconds
}

$signalMissedStatus = if ($signalMissedMode -eq "FRESH_CHILD_RUN") {
    Get-LastPrefixedValue -Text $signalMissed.Text -Prefix "signal_missed_blocker_decision_brief_status="
} else {
    "NOT_COLLECTED_REUSED_MATRIX"
}
if ([string]::IsNullOrWhiteSpace($signalMissedStatus)) {
    $signalMissedStatus = "NO_EVIDENCE"
}
$signalMissedPacket = if ($signalMissedMode -eq "FRESH_CHILD_RUN") {
    Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $signalMissed.Text -Prefix "signal_missed_blocker_decision_brief_packet=")
} else {
    $null
}
$signalMissedNextAction = if ($signalMissedMode -eq "FRESH_CHILD_RUN") {
    Get-LastPrefixedValue -Text $signalMissed.Text -Prefix "signal_missed_blocker_decision_next_action="
} else {
    "Rerun without -MatrixOutputPath to collect fresh signal/missed blocker detail."
}

$actionItems = [System.Collections.Generic.List[object]]::new()
$blockedItems = [System.Collections.Generic.List[object]]::new()
$decisionLanes = [System.Collections.Generic.List[object]]::new()
$exitSideActionProposals = [System.Collections.Generic.List[object]]::new()
$exitSideReady = $false

if ($null -ne $matrixPacket -and $null -ne $matrixPacket.reviewItems) {
    foreach ($item in @($matrixPacket.reviewItems)) {
        $lane = [string]$item.lane
        $ready = $item.readyForOperatorReview -eq $true
        $recommendation = "KEEP_COLLECTING_EVIDENCE"
        $actionClass = "BLOCKED_OR_PENDING"
        $operatorAction = [string]$item.nextAction
        if ($lane -eq "exit-side" -and $ready) {
            $exitSideReady = $true
            $recommendation = "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION"
            $actionClass = "OPERATOR_REVIEW_READY_NOT_LIVE"
            $exitSideActionProposals.Add([pscustomobject]@{
                proposalId = "trailing-stop-rollout-review"
                lane = "trailing-stop-rollout"
                proposalClass = "DRY_RUN_OR_ROLLOUT_REVIEW_NOT_LIVE"
                status = "READY_TO_DRAFT_REVIEW_NOT_LIVE"
                reviewContract = "docs/exit-side-operator-review-plan.md"
                sourceMatrixLane = $lane
                sourceEvidenceMarkers = @($item.evidenceMarkers)
                requiredFreshEvidence = @(
                    "trailing_stop_acceptance=PASS",
                    "exit_side_operator_decision_checklist includes trailing-stop-rollout",
                    "scheduler remains disabled or dry-run until separately approved",
                    "strategy opt-in scope is explicit",
                    "ambiguous same-bar rows excluded from acceptance"
                )
                allowedProposalOutput = @(
                    "draft trailing-stop dry-run review",
                    "draft separately authorized trailing rollout review"
                )
                forbiddenActions = @(
                    "enable trailing scheduler",
                    "enable live trading",
                    "modify OCO",
                    "change production env",
                    "deploy"
                )
                nextAction = "Draft a separate trailing-stop rollout review from fresh exit-side evidence; do not enable trailing from this brief."
                notAuthorization = "proposal only; does not authorize live trailing, scheduler enablement, OCO modification, deploy, or production env changes"
            })
            $exitSideActionProposals.Add([pscustomobject]@{
                proposalId = "strategy485-risk-reduction-review"
                lane = "strategy485-risk-reduction"
                proposalClass = "RISK_REDUCTION_REVIEW_NOT_MUTATION"
                status = "READY_TO_DRAFT_REVIEW_NOT_MUTATION"
                reviewContract = "docs/exit-side-operator-review-plan.md"
                sourceMatrixLane = $lane
                sourceEvidenceMarkers = @($item.evidenceMarkers)
                requiredFreshEvidence = @(
                    "strategy485_oco_health_ok=True",
                    "strategy485_negative_ev_position_count > 0",
                    "strategy485_close_or_modify_suggestion_count > 0",
                    "fresh active-position EV reassessment",
                    "TP stretch and timeout evidence attached",
                    "recent-closed and monthly PnL context attached"
                )
                allowedProposalOutput = @(
                    "draft strategy 485 risk-reduction decision packet",
                    "attach aged negative-EV position evidence to operator review"
                )
                forbiddenActions = @(
                    "close positions",
                    "modify OCO",
                    "cancel OCO",
                    "place orders",
                    "change production env",
                    "deploy"
                )
                nextAction = "Draft a separate strategy 485 risk-reduction review from fresh OCO/EV/timeout evidence; do not close positions or modify OCO from this brief."
                notAuthorization = "proposal only; does not authorize close-position, OCO modification, orders, deploy, or production env changes"
            })
        } elseif ($lane -eq "entry-filter") {
            $recommendation = "DO_NOT_RELAX_ENTRY_FILTERS_KEEP_GOVERNANCE_REVIEW"
            if ($signalMissedStatus -eq "BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW") {
                $operatorAction = $signalMissedNextAction
            }
        } elseif ($lane -eq "data-freshness-replay") {
            $recommendation = "COLLECT_DATAFRESHNESS_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW"
        }

        $briefItem = [pscustomobject]@{
            lane = $lane
            priority = [string]$item.priority
            status = [string]$item.status
            readyForOperatorReview = $ready
            actionClass = $actionClass
            recommendation = $recommendation
            evidenceMarkers = @($item.evidenceMarkers)
            missingRequirements = @($item.missingRequirements)
            operatorAction = $operatorAction
            notAuthorization = "this action item is read-only review routing only and does not authorize live trading, policy relaxation, orders, OCO, close-position, DB/grid/fund/Earn/Telegram/exchange mutation, deploy, restart, or production env changes"
        }
        $actionItems.Add($briefItem)
        if (-not $ready) {
            $blockedItems.Add($briefItem)
        }

        $decisionClass = "EVIDENCE_COLLECTION"
        $separateAuthorizationRequired = @("separate operator review before any live or mutation change")
        $allowedFromThisBrief = @("route read-only evidence")
        $forbiddenFromThisBrief = @("enable live trading", "change production env", "deploy", "place orders", "modify OCO", "close positions")
        if ($lane -eq "exit-side") {
            $decisionClass = if ($ready) { "EXIT_SIDE_REVIEW_READY_NOT_LIVE" } else { "EXIT_SIDE_REVIEW_BLOCKED" }
            $separateAuthorizationRequired = @("enable trailing scheduler or trailing live mode", "change strategy opt-in or exit policy", "close any position", "modify or cancel OCO", "deploy runtime changes")
            $allowedFromThisBrief = if ($ready) { @("prepare separate exit-side operator review", "attach trailing and strategy 485 evidence") } else { @("collect exit-side replay and position-risk evidence") }
            $forbiddenFromThisBrief = @("enable trailing scheduler", "enable live trading", "close positions", "modify OCO", "change production env", "deploy")
        } elseif ($lane -eq "entry-filter") {
            $decisionClass = if ($ready) { "ENTRY_FILTER_REVIEW_READY_NOT_LIVE" } else { "ENTRY_FILTER_POLICY_BLOCKED" }
            $separateAuthorizationRequired = @("relax EntryDedup/DataFreshness/live policy", "enable live entry policy changes", "approve governance relaxation")
            $allowedFromThisBrief = @("keep entry/filter policy unchanged", "route governance and missed-opportunity evidence", "use signal/missed blocker decision brief when collected")
            $forbiddenFromThisBrief = @("relax EntryDedup", "relax DataFreshnessGuard", "enable TinyLive or entry execution", "change live policy")
        } elseif ($lane -eq "data-freshness-replay") {
            $decisionClass = if ($ready) { "DATAFRESHNESS_REPLAY_REVIEW_READY_NOT_LIVE" } else { "DATAFRESHNESS_REPLAY_BLOCKED" }
            $separateAuthorizationRequired = @("approve DataFreshness shadow/replay policy", "enable replay collector", "relax DataFreshnessGuard", "enable live entry policy changes")
            $allowedFromThisBrief = @("keep DataFreshnessGuard strict", "collect replayCandidateId and counterfactual snapshots", "route DataFreshness replay evidence")
            $forbiddenFromThisBrief = @("relax DataFreshnessGuard", "create live signals", "send Telegram", "place orders", "modify OCO", "change scheduler/live policy")
        }
        $decisionLanes.Add([pscustomobject]@{
            lane = $lane
            priority = [string]$item.priority
            decisionClass = $decisionClass
            status = [string]$item.status
            readyForOperatorReview = $ready
            recommendation = $recommendation
            evidenceMarkers = @($item.evidenceMarkers)
            missingRequirements = @($item.missingRequirements)
            separateAuthorizationRequired = @($separateAuthorizationRequired)
            allowedFromThisBrief = @($allowedFromThisBrief)
            forbiddenFromThisBrief = @($forbiddenFromThisBrief)
            nextAction = $operatorAction
        })
    }
}

$briefStatus = "NO_REVIEW_READY_ITEMS"
$primaryRecommendation = "CONTINUE_READ_ONLY_EVIDENCE_COLLECTION"
if ($matrix.ExitCode -ne 0 -or $null -eq $matrixPacket) {
    if ($signalMissedMode -eq "FRESH_CHILD_RUN" -and $signalMissed.ExitCode -eq 0 -and $signalMissedStatus -ne "NO_EVIDENCE") {
        $briefStatus = "MATRIX_COLLECTION_INCOMPLETE_SIGNAL_MISSED_BLOCKER_COLLECTED"
    } else {
        $briefStatus = "NO_EVIDENCE"
    }
    $primaryRecommendation = "FIX_PROFIT_MATRIX_COLLECTION"
} elseif ($exitSideReady) {
    $briefStatus = "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"
    $primaryRecommendation = "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION"
} elseif ($matrixStatus -eq "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    $briefStatus = "HAS_REVIEW_READY_ITEMS_NOT_LIVE"
    $primaryRecommendation = "REVIEW_READY_READ_ONLY_ITEMS_SEPARATELY"
}

$brief = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_ACTION_BRIEF"
    status = $briefStatus
    symbol = $Symbol
    matrixStatus = $matrixStatus
    primaryRecommendation = $primaryRecommendation
    recommendedNextReview = if ($exitSideReady) { "EXIT_SIDE_OPERATOR_REVIEW" } else { "READ_ONLY_EVIDENCE_COLLECTION" }
    decisionLanes = @($decisionLanes)
    exitSideActionProposals = @($exitSideActionProposals)
    signalMissedBlockerDecision = [pscustomobject]@{
        mode = $signalMissedMode
        status = $signalMissedStatus
        exitCode = $signalMissed.ExitCode
        timedOut = $signalMissed.TimedOut
        packet = $signalMissedPacket
        nextAction = $signalMissedNextAction
    }
    actionItems = @($actionItems)
    blockedItems = @($blockedItems)
    doNotActions = @(
        "do not enable live trading from this brief",
        "do not enable trailing scheduler from this brief",
        "do not close positions or modify OCO from this brief",
        "do not relax EntryDedup/DataFreshness/live policy from this brief",
        "do not deploy or change production env from this brief"
    )
    sourceMatrix = "prepare_profit_operator_review_matrix_ssh.ps1"
    sourceMatrixMode = $sourceMatrixMode
    sourceMatrixOutputPath = if ([string]::IsNullOrWhiteSpace($MatrixOutputPath)) { $null } else { $MatrixOutputPath }
    sourceMatrixSavedOutputPath = $matrixSavedOutputPath
    sourceMatrixLatestPointerUpdated = $matrixLatestPointerUpdated
    sourceMatrixRejectReason = $matrixRejectReason
    sourceMatrixFreshness = $matrixFreshness
    sourceMatrixExitCode = $matrix.ExitCode
    sourceMatrixTimeoutSeconds = $effectiveMatrixTimeoutSeconds
    childTimeoutSeconds = $ChildTimeoutSeconds
    sourceSignalMissedBlocker = "prepare_signal_missed_blocker_decision_brief_ssh.ps1"
    nextAction = if ($exitSideReady) { "Prepare a separate exit-side operator review using the attached read-only evidence; keep entry/filter and DataFreshness lanes blocked until their evidence clears." } else { $matrixNextAction }
    notAuthorization = "read-only profit operator action brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-action-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_review_matrix_ssh.ps1 and, for fresh child runs, prepare_signal_missed_blocker_decision_brief_ssh.ps1; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_matrix=prepare_profit_operator_review_matrix_ssh.ps1 exitCode=$($matrix.ExitCode)"
Write-Host "source_matrix_mode=$sourceMatrixMode"
Write-Host "source_matrix_freshness_status=$($matrixFreshness.Status)"
Write-Host "source_matrix_timeout_seconds=$effectiveMatrixTimeoutSeconds"
Write-Host "child_timeout_seconds=$ChildTimeoutSeconds"
Write-Host "source_signal_missed_blocker=prepare_signal_missed_blocker_decision_brief_ssh.ps1 mode=$signalMissedMode exitCode=$($signalMissed.ExitCode)"
Write-Host "signal_missed_blocker_decision_brief_status=$signalMissedStatus"
Write-Host "profit_operator_review_matrix_status=$matrixStatus"
Write-Host "profit_operator_action_primary_recommendation=$primaryRecommendation"
Write-Host ("profit_operator_signal_missed_blocker_decision=" + (ConvertTo-Json -Compress -Depth 10 $brief.signalMissedBlockerDecision))
Write-Host ("profit_operator_decision_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($decisionLanes)))
Write-Host ("exit_side_operator_action_proposals=" + (ConvertTo-Json -Compress -Depth 8 @($exitSideActionProposals)))
Write-Host ("profit_operator_action_items=" + (ConvertTo-Json -Compress -Depth 8 @($actionItems)))
Write-Host ("profit_operator_action_blocked_items=" + (ConvertTo-Json -Compress -Depth 8 @($blockedItems)))
Write-Host ("profit_operator_action_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "profit_operator_action_brief_status=$briefStatus"
Write-Host "profit_operator_action_next_action=$($brief.nextAction)"
Write-Host "notAuthorization=read-only profit operator action brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-action-brief] read-only check complete"

if ($RequireReady -and $briefStatus -ne "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE" -and $briefStatus -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator action brief has no review-ready items."
}
