param(
    [string]$ReviewOutputDir = "target/profit-review",
    [string]$NextExecutionLogPath = "target/profit-review/profit-next-execution-blocker-packet-latest.log",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [switch]$RequireFreshMatrix,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Read-NextExecutionStatus {
    param([string]$PathValue)

    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{
            exists = $false
            path = $resolved
            status = "NEXT_EXECUTION_LOG_MISSING"
            route = ""
            uniqueBlocker = ""
            sampleCollectionBlockedBy = ""
            openOcoPositions = ""
            dataFreshnessReplayCandidateIdRows = ""
            dataFreshnessCompleteReplayableCandidateRows = ""
            orderAllowed = ""
            livePolicyChangeAllowed = ""
            deployAllowed = ""
            schedulerEnablementAllowed = ""
            telegramSendAllowed = ""
            nextAction = "Run prepare_profit_next_execution_blocker_packet.ps1 from the source refresh before using execution blocker status."
        }
    }

    $text = Get-Content -Raw -LiteralPath $resolved
    $packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_blocker_packet=")
    $packetNextAction = ""
    if ($null -ne $packet -and $null -ne $packet.PSObject.Properties["nextAction"]) {
        $packetNextAction = [string]$packet.nextAction
    }

    return [pscustomobject]@{
        exists = $true
        path = $resolved
        status = Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_blocker_status="
        route = Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_route="
        uniqueBlocker = Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_unique_blocker="
        sampleCollectionBlockedBy = Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_sample_collection_blocked_by="
        openOcoPositions = Get-LastPrefixedValue -Text $text -Prefix "profit_next_execution_open_oco_positions="
        dataFreshnessReplayCandidateIdRows = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_replay_candidate_id_rows="
        dataFreshnessCompleteReplayableCandidateRows = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_complete_replayable_candidate_rows="
        orderAllowed = Get-LastPrefixedValue -Text $text -Prefix "order_allowed="
        livePolicyChangeAllowed = Get-LastPrefixedValue -Text $text -Prefix "live_policy_change_allowed="
        deployAllowed = Get-LastPrefixedValue -Text $text -Prefix "deploy_allowed="
        schedulerEnablementAllowed = Get-LastPrefixedValue -Text $text -Prefix "scheduler_enablement_allowed="
        telegramSendAllowed = Get-LastPrefixedValue -Text $text -Prefix "telegram_send_allowed="
        nextAction = $packetNextAction
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator quick status arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$pointerPath = Join-Path $ReviewOutputDir "latest-profit-operator-matrix.path"
$status = "REFRESH_REQUIRED_NO_MATRIX"
$refreshRequired = $true
$compactStatus = ""
$compactPacket = $null
$compactExitCode = $null
$compactFailureSummary = ""
$matrixOutputPath = ""
$nextAction = "Run prepare_profit_operator_action_brief_ssh.ps1 to collect a fresh read-only matrix before using profit operator status."
$nextExecutionStatus = Read-NextExecutionStatus -PathValue $NextExecutionLogPath

if (Test-Path -LiteralPath $pointerPath) {
    $matrixOutputPath = (Get-Content -Raw -LiteralPath $pointerPath).Trim()
    if ([string]::IsNullOrWhiteSpace($matrixOutputPath)) {
        $status = "REFRESH_REQUIRED_EMPTY_MATRIX_POINTER"
        $nextAction = "Refresh the read-only profit operator matrix because latest-profit-operator-matrix.path is empty."
    } elseif (-not (Test-Path -LiteralPath $matrixOutputPath)) {
        $status = "REFRESH_REQUIRED_MATRIX_OUTPUT_MISSING"
        $nextAction = "Refresh the read-only profit operator matrix because the latest matrix output file is missing."
    } else {
        $compactScript = Join-Path $PSScriptRoot "prepare_profit_operator_compact_status.ps1"
        if (-not (Test-Path -LiteralPath $compactScript)) {
            throw "Missing compact status script: $compactScript"
        }
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
        if ($null -eq $powerShell) {
            $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
        }
        if ($null -eq $powerShell) {
            throw "Unable to find powershell or pwsh for profit operator quick status."
        }

        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $compactScript `
                -ReviewOutputDir $ReviewOutputDir `
                -MatrixMaxAgeMinutes $MatrixMaxAgeMinutes `
                -Symbol $Symbol `
                -StrategyId $StrategyId 2>&1
            $compactExitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
        } catch {
            $output = @($_)
            $compactExitCode = 1
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $compactText = ($output | Out-String -Width 4096)
        if ($compactExitCode -ne 0) {
            $compactFailureSummary = ($compactText.Trim() -replace "`r?`n", " | ")
            if ($compactFailureSummary.Length -gt 500) {
                $compactFailureSummary = $compactFailureSummary.Substring(0, 500)
            }
        }
        $compactStatus = Get-LastPrefixedValue -Text $compactText -Prefix "profit_operator_compact_status="
        $compactPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $compactText -Prefix "profit_operator_compact_status_packet=")

        if ($compactExitCode -ne 0) {
            $status = "REFRESH_REQUIRED_COMPACT_STATUS_FAILED"
            $nextAction = "Refresh the read-only profit operator matrix or inspect compact status failure before using profit operator status."
        } elseif ($compactStatus -eq "INVALID_MATRIX_PACKET") {
            $status = "REFRESH_REQUIRED_INVALID_MATRIX_PACKET"
            $nextAction = "Refresh the read-only profit operator matrix because latest-profit-operator-matrix.path points at an invalid matrix packet."
        } elseif ($compactStatus -eq "STALE_MATRIX") {
            $status = "REFRESH_REQUIRED_STALE_MATRIX"
            $nextAction = "Refresh the read-only profit operator matrix before using this quick status."
        } elseif (-not [string]::IsNullOrWhiteSpace($compactStatus)) {
            $status = $compactStatus
            $refreshRequired = $false
            if ($null -ne $compactPacket -and -not [string]::IsNullOrWhiteSpace([string]$compactPacket.nextAction)) {
                $nextAction = [string]$compactPacket.nextAction
            } else {
                $nextAction = "Use the latest saved read-only matrix status; rerun fresh matrix only when evidence is stale or a new sample is expected."
            }
        } else {
            $status = "REFRESH_REQUIRED_COMPACT_STATUS_MISSING"
            $nextAction = "Refresh the read-only profit operator matrix because compact status did not emit profit_operator_compact_status."
        }
    }
}

$operatorNextAction = $nextAction
if ($nextExecutionStatus.exists -and -not [string]::IsNullOrWhiteSpace($nextExecutionStatus.uniqueBlocker)) {
    $nextAction = "Current execution blocker: $($nextExecutionStatus.route) waits on $($nextExecutionStatus.uniqueBlocker). Operator matrix next action: $operatorNextAction"
} elseif (-not $nextExecutionStatus.exists -and -not $refreshRequired) {
    $nextAction = "Execution blocker log is missing; refresh source evidence. Operator matrix next action: $operatorNextAction"
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_QUICK_STATUS"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    reviewOutputDir = $ReviewOutputDir
    latestMatrixPointer = $pointerPath
    latestMatrixOutputPath = $matrixOutputPath
    matrixMaxAgeMinutes = $MatrixMaxAgeMinutes
    refreshRequired = $refreshRequired
    compactStatus = $compactStatus
    compactExitCode = $compactExitCode
    compactFailureSummary = $compactFailureSummary
    compactStatusPacket = $compactPacket
    nextExecutionStatus = $nextExecutionStatus
    doNotActions = @(
        "do not enable live trading from this quick status",
        "do not enable trailing scheduler from this quick status",
        "do not close positions or modify OCO from this quick status",
        "do not relax EntryDedup/DataFreshness/live policy from this quick status",
        "do not deploy or change production env from this quick status"
    )
    nextAction = $nextAction
    notAuthorization = "read-only quick profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-quick-status] read-only quick status"
Write-Host "scope=READ_ONLY; reads latest-profit-operator-matrix.path, may invoke prepare_profit_operator_compact_status.ps1, and reads the latest saved profit-next-execution blocker log only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "profit_operator_quick_source_matrix_pointer=$pointerPath"
Write-Host "profit_operator_quick_source_matrix_output_path=$matrixOutputPath"
Write-Host "profit_operator_quick_compact_status=$compactStatus"
Write-Host "profit_operator_quick_compact_exit_code=$compactExitCode"
Write-Host "profit_operator_quick_compact_failure_summary=$compactFailureSummary"
Write-Host "profit_operator_quick_refresh_required=$($refreshRequired.ToString().ToLowerInvariant())"
Write-Host "profit_operator_quick_next_execution_log_path=$($nextExecutionStatus.path)"
Write-Host "profit_operator_quick_next_execution_status=$($nextExecutionStatus.status)"
Write-Host "profit_operator_quick_next_execution_route=$($nextExecutionStatus.route)"
Write-Host "profit_operator_quick_next_execution_unique_blocker=$($nextExecutionStatus.uniqueBlocker)"
Write-Host "profit_operator_quick_next_execution_sample_collection_blocked_by=$($nextExecutionStatus.sampleCollectionBlockedBy)"
Write-Host "profit_operator_quick_next_execution_open_oco_positions=$($nextExecutionStatus.openOcoPositions)"
Write-Host "profit_operator_quick_next_execution_data_freshness_replay_candidate_id_rows=$($nextExecutionStatus.dataFreshnessReplayCandidateIdRows)"
Write-Host "profit_operator_quick_next_execution_data_freshness_complete_replayable_candidate_rows=$($nextExecutionStatus.dataFreshnessCompleteReplayableCandidateRows)"
Write-Host ("profit_operator_quick_status_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_operator_quick_status=$status"
Write-Host "profit_operator_quick_next_action=$nextAction"
Write-Host "notAuthorization=read-only quick profit status only; does not rerun SSH, deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-quick-status] read-only check complete"

if ($RequireFreshMatrix -and $refreshRequired) {
    throw "Profit operator quick status requires a fresh matrix: $status"
}
if ($RequireReady -and $status -ne "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE" -and $status -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator quick status is not ready: $status"
}
