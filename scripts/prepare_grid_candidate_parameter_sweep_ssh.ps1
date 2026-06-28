param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int[]]$GridCounts = @(2, 4, 6, 8),
    [decimal[]]$PerLevelUsdtValues = @(5),
    [decimal[]]$StopOutPctValues = @(3.0, 5.0, 8.0),
    [decimal[]]$CandidateHalfWidthPctValues = @(0),
    [int]$ChildTimeoutSeconds = 900,
    [int]$MaxCombinations = 24,
    [switch]$RequireCandidate
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
        throw "$Name contains unsupported characters for grid candidate sweep arguments."
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
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Convert-DecimalOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    try {
        return [decimal]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        return $null
    }
}

function Invoke-OperatorPacket {
    param([string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only operator packet script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid candidate sweep." }

    $startedAt = Get-Date
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
            $childOutput = & $PowerShellSource -NoProfile -ExecutionPolicy Bypass -File $ChildScriptPath @ChildArguments 2>&1
            $childSuccess = $?
            $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($childSuccess) { 0 } else { 1 }
            [pscustomobject]@{
                Text = ($childOutput | Out-String -Width 8192)
                ExitCode = $code
            }
        } -ArgumentList @($powerShell.Source, $scriptPath, (Get-Location).Path, (, @($Arguments)))

        while ($job.State -eq "Running") {
            $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            if ($elapsedSeconds -ge $ChildTimeoutSeconds) {
                Stop-Job -Job $job -ErrorAction SilentlyContinue
                return [pscustomobject]@{
                    Text = "timed out after $ChildTimeoutSeconds second(s)"
                    ExitCode = 124
                    ElapsedSeconds = $elapsedSeconds
                }
            }
            Start-Sleep -Seconds 2
        }

        $result = Receive-Job -Job $job -ErrorAction SilentlyContinue
        if ($null -eq $result) {
            return [pscustomobject]@{
                Text = ""
                ExitCode = 1
                ElapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            }
        }

        return [pscustomobject]@{
            Text = [string]$result.Text
            ExitCode = [int]$result.ExitCode
            ElapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }
if ($MaxCombinations -lt 1 -or $MaxCombinations -gt 100) { throw "MaxCombinations must be between 1 and 100." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$combinations = [System.Collections.Generic.List[object]]::new()
foreach ($gridCount in $GridCounts) {
    if ($gridCount -lt 2 -or $gridCount -gt 24) { throw "GridCounts values must be between 2 and 24." }
    foreach ($perLevelUsdt in $PerLevelUsdtValues) {
        if ($perLevelUsdt -lt 5 -or $perLevelUsdt -gt 1000) { throw "PerLevelUsdtValues values must be between 5 and 1000." }
        foreach ($stopOutPct in $StopOutPctValues) {
            if ($stopOutPct -lt 1 -or $stopOutPct -gt 20) { throw "StopOutPctValues values must be between 1 and 20." }
            foreach ($candidateHalfWidthPct in $CandidateHalfWidthPctValues) {
                if ($candidateHalfWidthPct -ne 0 -and ($candidateHalfWidthPct -lt 2.5 -or $candidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPctValues values must be 0 or between 2.5 and 30." }
                $combinations.Add([pscustomobject]@{
                    gridCount = [int]$gridCount
                    perLevelUsdt = [decimal]$perLevelUsdt
                    stopOutPct = [decimal]$stopOutPct
                    candidateHalfWidthPct = [decimal]$candidateHalfWidthPct
                })
            }
        }
    }
}

if ($combinations.Count -gt $MaxCombinations) {
    throw "Grid candidate sweep combination count $($combinations.Count) exceeds MaxCombinations=$MaxCombinations."
}

Write-Host "[grid-candidate-parameter-sweep] read-only sweep"
Write-Host "scope=READ_ONLY; invokes prepare_grid_open_operator_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol lookbackHours=$LookbackHours candidateLookbackHours=$CandidateLookbackHours combinations=$($combinations.Count)"

$rows = [System.Collections.Generic.List[object]]::new()
$index = 0
foreach ($combo in $combinations) {
    $index++
    Write-Host "[grid-candidate-parameter-sweep] combination=$index/$($combinations.Count) gridCount=$($combo.gridCount) perLevelUsdt=$($combo.perLevelUsdt) stopOutPct=$($combo.stopOutPct) candidateHalfWidthPct=$($combo.candidateHalfWidthPct)"

    $args = @(
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir,
        "-EnvFile", $EnvFile,
        "-Symbol", $Symbol,
        "-LookbackHours", "$LookbackHours",
        "-CandidateLookbackHours", "$CandidateLookbackHours",
        "-GridCount", "$($combo.gridCount)",
        "-PerLevelUsdt", "$($combo.perLevelUsdt)",
        "-StopOutPct", "$($combo.stopOutPct)",
        "-CandidateHalfWidthPct", "$($combo.candidateHalfWidthPct)"
    )
    $result = Invoke-OperatorPacket -Arguments $args
    $packet = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $result.Text -Prefix "grid_open_operator_packet=")

    $candidatePlan = if ($null -ne $packet) { $packet.candidatePlan } else { $null }
    $combinedEnvelope = if ($null -ne $packet) { $packet.combinedOverrideRiskEnvelope } else { $null }
    $candidateCapital = if ($null -ne $candidatePlan) { Convert-DecimalOrNull $candidatePlan.candidateCapitalUsdt } else { $null }
    $capitalCap = if ($null -ne $combinedEnvelope) { Convert-DecimalOrNull $combinedEnvelope.effectiveReviewCapitalCapUsdt } else { $null }
    $replayScore = if ($null -ne $candidatePlan) { Convert-DecimalOrNull $candidatePlan.replayScore } else { $null }
    $stopBreakRows = if ($null -ne $candidatePlan) { [int](Convert-DecimalOrNull $candidatePlan.stopBreakRows) } else { $null }
    $capitalWithinCap = ($null -ne $candidateCapital -and $null -ne $capitalCap -and $candidateCapital -le $capitalCap)
    $qualityCandidate = (
        $result.ExitCode -eq 0 -and
        $null -ne $packet -and
        $null -ne $candidatePlan -and
        [bool]$packet.candidatePlanComplete -and
        $null -ne $replayScore -and
        $replayScore -ge 70 -and
        $null -ne $stopBreakRows -and
        $stopBreakRows -eq 0
    )
    $reviewCandidate = (
        $qualityCandidate -and
        $capitalWithinCap
    )

    $row = [pscustomobject]@{
        rank = 0
        gridCount = $combo.gridCount
        perLevelUsdt = [double]$combo.perLevelUsdt
        stopOutPct = [double]$combo.stopOutPct
        candidateHalfWidthPct = [double]$combo.candidateHalfWidthPct
        childExitCode = $result.ExitCode
        elapsedSeconds = $result.ElapsedSeconds
        operatorStatus = if ($null -ne $packet) { [string]$packet.status } else { "PACKET_UNAVAILABLE" }
        candidatePlanComplete = if ($null -ne $packet) { [bool]$packet.candidatePlanComplete } else { $false }
        candidatePlanStatus = if ($null -ne $packet) { [string]$packet.candidatePlanStatus } else { "UNKNOWN" }
        trendGateStatus = if ($null -ne $packet) { [string]$packet.gateStatuses.trendGate } else { "UNKNOWN" }
        eventRiskGateStatus = if ($null -ne $packet) { [string]$packet.gateStatuses.eventRiskGate } else { "UNKNOWN" }
        okxGateStatus = if ($null -ne $packet) { [string]$packet.gateStatuses.okxGate } else { "UNKNOWN" }
        trend = if ($null -ne $candidatePlan) { [string]$candidatePlan.trend } else { "UNKNOWN" }
        trendPct = if ($null -ne $candidatePlan) { $candidatePlan.trendPct } else { $null }
        atrPct = if ($null -ne $candidatePlan) { $candidatePlan.atrPct } else { $null }
        replayRows = if ($null -ne $candidatePlan) { $candidatePlan.replayRows } else { $null }
        replayScore = $replayScore
        insidePct = if ($null -ne $candidatePlan) { $candidatePlan.insidePct } else { $null }
        touchedPct = if ($null -ne $candidatePlan) { $candidatePlan.touchedPct } else { $null }
        stopBreakRows = $stopBreakRows
        candidateLower = if ($null -ne $candidatePlan) { $candidatePlan.candidateLower } else { $null }
        candidateUpper = if ($null -ne $candidatePlan) { $candidatePlan.candidateUpper } else { $null }
        observedCandidateHalfWidthPct = if ($null -ne $candidatePlan) { $candidatePlan.candidateHalfWidthPct } else { $null }
        candidateHalfWidthSource = if ($null -ne $candidatePlan) { $candidatePlan.candidateHalfWidthSource } else { "UNKNOWN" }
        stopLow = if ($null -ne $candidatePlan) { $candidatePlan.stopLow } else { $null }
        stopHigh = if ($null -ne $candidatePlan) { $candidatePlan.stopHigh } else { $null }
        candidateCapitalUsdt = $candidateCapital
        effectiveReviewCapitalCapUsdt = $capitalCap
        capitalWithinCap = $capitalWithinCap
        combinedRiskGrade = if ($null -ne $combinedEnvelope) { [string]$combinedEnvelope.riskGrade } else { "UNKNOWN" }
        qualityCandidate = $qualityCandidate
        reviewCandidate = $reviewCandidate
        missingRequirements = if ($null -ne $packet) { @($packet.missingRequirements) } else { @("grid_open_operator_packet valid JSON") }
    }
    $rows.Add($row)
}

$rankedRows = @($rows | Sort-Object -Property `
    @{ Expression = { if ($_.reviewCandidate) { 0 } else { 1 } }; Ascending = $true }, `
    @{ Expression = { if ($null -ne $_.stopBreakRows) { $_.stopBreakRows } else { 999999 } }; Ascending = $true }, `
    @{ Expression = { if ($null -ne $_.replayScore) { -1 * [decimal]$_.replayScore } else { 999999 } }; Ascending = $true }, `
    @{ Expression = { if ($_.capitalWithinCap) { 0 } else { 1 } }; Ascending = $true }, `
    @{ Expression = { [decimal]$_.candidateCapitalUsdt }; Ascending = $true })

$rank = 0
foreach ($row in $rankedRows) {
    $rank++
    $row.rank = $rank
}

$bestCandidate = @($rankedRows | Where-Object { $_.reviewCandidate } | Select-Object -First 1)
$bestQualityCandidate = @($rankedRows | Where-Object { $_.qualityCandidate } | Select-Object -First 1)
$bestRow = @($rankedRows | Select-Object -First 1)
$sweepStatus = if ($bestCandidate.Count -gt 0) {
    "READY_GRID_CANDIDATE_PARAMETER_FOUND_NOT_MUTATION"
} elseif ($bestQualityCandidate.Count -gt 0) {
    "BLOCKED_GRID_CANDIDATE_REPLAY_QUALITY_READY_CAPITAL_OR_TREND_NOT_MUTATION"
} else {
    "BLOCKED_NO_GRID_CANDIDATE_PARAMETER_FOUND_NOT_MUTATION"
}
$decision = if ($bestCandidate.Count -gt 0) {
    "REFRESH_GRID_ENV_DIFF_PREFLIGHT_AND_OPERATOR_AUTHORIZATION_WITH_BEST_CANDIDATE"
} elseif ($bestQualityCandidate.Count -gt 0) {
    "REPLAY_BLOCKER_CAN_CLEAR_WITH_BEST_QUALITY_CANDIDATE_RESOLVE_TREND_AND_CAPITAL_REVIEW"
} else {
    "WAIT_FOR_MARKET_REGIME_OR_REPLAY_QUALITY_RECOVERY_BEFORE_GRID_OPEN"
}
$remainingBlockers = [System.Collections.Generic.List[string]]::new()
if ($bestCandidate.Count -eq 0) {
    if (@($rankedRows | Where-Object { $null -eq $_.replayScore -or $_.replayScore -lt 70 }).Count -eq $rankedRows.Count) {
        $remainingBlockers.Add("NO_PARAMETER_REPLAY_SCORE_AT_LEAST_70")
    }
    if (@($rankedRows | Where-Object { $null -ne $_.stopBreakRows -and $_.stopBreakRows -gt 0 }).Count -gt 0) {
        $remainingBlockers.Add("SOME_PARAMETERS_HAVE_STOP_BREAK_ROWS")
    }
    if (@($rankedRows | Where-Object { -not $_.capitalWithinCap }).Count -eq $rankedRows.Count) {
        $remainingBlockers.Add("NO_PARAMETER_CAPITAL_WITHIN_EFFECTIVE_REVIEW_CAP")
    }
    if (@($rankedRows | Where-Object { $_.trendGateStatus -like "BLOCKED_*" }).Count -gt 0) {
        $remainingBlockers.Add("TREND_GATE_REMAINS_BLOCKED_OR_REQUIRES_SEPARATE_OVERRIDE_REVIEW")
    }
}

$packet = [pscustomobject]@{
    packetType = "GRID_CANDIDATE_PARAMETER_SWEEP_PACKET"
    scope = "READ_ONLY"
    status = $sweepStatus
    decision = $decision
    symbol = $Symbol
    lookbackHours = $LookbackHours
    candidateLookbackHours = $CandidateLookbackHours
    minimumReviewGridCount = 2
    maximumReviewGridCount = 24
    microGridReviewAllowed = $true
    microGridReviewNote = "gridCount=2 is reviewable because runtime createGrid allows 2-50; it remains read-only evidence and still requires separate trend/env/capital/createGrid authorization."
    combinationCount = $combinations.Count
    qualityCandidateCount = @($rankedRows | Where-Object { $_.qualityCandidate }).Count
    reviewCandidateCount = @($rankedRows | Where-Object { $_.reviewCandidate }).Count
    bestCandidate = if ($bestCandidate.Count -gt 0) { $bestCandidate[0] } else { $null }
    bestQualityCandidate = if ($bestQualityCandidate.Count -gt 0) { $bestQualityCandidate[0] } else { $null }
    bestObservedRow = if ($bestRow.Count -gt 0) { $bestRow[0] } else { $null }
    remainingBlockers = @($remainingBlockers)
    rows = @($rankedRows)
    explicitNonAuthorizations = @(
        "does not change production env",
        "does not enable TRADING_OKX_ENABLED",
        "does not enable TRADING_GRID_ENABLED",
        "does not call createGrid",
        "does not enable scheduler or recovery",
        "does not place orders",
        "does not modify OCO",
        "does not send Telegram",
        "does not mutate DB/grid/fund/Earn/exchange state"
    )
    nextAction = $decision
    notAuthorization = "read-only grid candidate parameter sweep only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
}

Write-Host ("grid_candidate_parameter_sweep_rows=" + (ConvertTo-Json -Compress -Depth 12 @($rankedRows)))
Write-Host ("grid_candidate_parameter_sweep_best_candidate=" + (ConvertTo-Json -Compress -Depth 12 $(if ($bestCandidate.Count -gt 0) { $bestCandidate[0] } else { $null })))
Write-Host ("grid_candidate_parameter_sweep_best_quality_candidate=" + (ConvertTo-Json -Compress -Depth 12 $(if ($bestQualityCandidate.Count -gt 0) { $bestQualityCandidate[0] } else { $null })))
Write-Host ("grid_candidate_parameter_sweep_remaining_blockers=" + (ConvertTo-Json -Compress @($remainingBlockers)))
Write-Host ("grid_candidate_parameter_sweep_packet=" + (ConvertTo-Json -Compress -Depth 16 $packet))
Write-Host "grid_candidate_parameter_sweep_status=$sweepStatus"
Write-Host "grid_candidate_parameter_sweep_decision=$decision"
Write-Host "grid_candidate_parameter_sweep_quality_candidate_count=$(@($rankedRows | Where-Object { $_.qualityCandidate }).Count)"
Write-Host "grid_candidate_parameter_sweep_review_candidate_count=$(@($rankedRows | Where-Object { $_.reviewCandidate }).Count)"
Write-Host "grid_candidate_parameter_sweep_micro_grid_review_allowed=true"
Write-Host "production_env_change_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only grid candidate parameter sweep only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
Write-Host "[grid-candidate-parameter-sweep] read-only check complete"

if ($RequireCandidate -and $bestCandidate.Count -eq 0) {
    throw "No grid candidate parameter set passed review filters: $($remainingBlockers -join '; ')"
}
