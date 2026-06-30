Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) {
        throw "$Name unexpectedly contained pattern: $Pattern"
    }
}

function Invoke-BlockedPacketScript {
    param(
        [string]$ScriptName,
        [string[]]$Arguments,
        [string[]]$RequiredMarkers,
        [string[]]$ForbiddenMarkers
    )

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:PowerShellPath -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String -Width 4096)
    if ($exitCode -eq 0) {
        throw "$ScriptName unexpectedly passed with a blocked matrix:`n$text"
    }

    foreach ($marker in $RequiredMarkers) {
        Assert-Contains -Name "$ScriptName blocked packet" -Text $text -Pattern ([regex]::Escape($marker))
    }
    foreach ($marker in $ForbiddenMarkers) {
        Assert-NotContains -Name "$ScriptName false completed blocker" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "$ScriptName unexpectedly invoked SSH or a fresh child run:`n$text"
    }
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for blocked packet preservation test"
}
$script:PowerShellPath = $powerShell.Source

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-blocked-chain-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-blocked-chain-review-" + [guid]::NewGuid().ToString("N"))
try {
    $blockedMatrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "NOT_READY"
                readyForOperatorReview = $false
                evidenceMarkers = @("trailing_stop_acceptance=PASS", "exit_side_profit_review_packet_status=NOT_READY")
                missingRequirements = @("operator_review_packet_allowed true")
                nextAction = "Keep collecting exit-side evidence."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "REVIEW_SIGNAL_POLICY"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false")
                missingRequirements = @("signal policy review clear")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("complete_replayable_candidate_rows=0")
                missingRequirements = @("complete DataFreshness replayable candidate rows")
                nextAction = "Collect replay snapshots before policy review."
            }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=NO_REVIEW_READY_ITEMS",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $blockedMatrixPacket)),
        "profit_operator_review_matrix_next_action=Continue read-only evidence collection."
    )
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    Set-Content -LiteralPath (Join-Path $tempReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $tempMatrixPath

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_exit_side_operator_experiment_packet.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-RequireReady") `
        -RequiredMarkers @(
            "source_matrix_freshness_status=FRESH",
            "profit_operator_review_summary_packet=",
            "exit_side_operator_experiment_packet_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "profit operator review summary completed",
            "profit_operator_review_summary_packet valid JSON"
        )

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_profit_verified_recommendations.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-RequireReady") `
        -RequiredMarkers @(
            "source_matrix_freshness_status=FRESH",
            "exit_side_operator_experiment_packet_status=NOT_READY",
            "profit_verified_recommendations_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "exit-side operator experiment packet completed",
            "EntryDedup semantics shadow experiment packet completed",
            "exit_side_operator_experiment_packet valid JSON",
            "entry_dedup_semantics_shadow_experiment_packet valid JSON"
        )

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_exit_side_verified_experiment_readiness.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-RequireReady") `
        -RequiredMarkers @(
            "profit_verified_recommendations_status=NOT_READY",
            "exit_side_verified_experiment_readiness_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "profit verified recommendations completed",
            "profit_verified_recommendations_packet valid JSON"
        )

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_exit_side_experiment_operator_review_packet.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-ReviewNotionalCapUsdt", "15", "-ObservationHours", "48", "-RequireReady") `
        -RequiredMarkers @(
            "exit_side_verified_experiment_readiness_status=NOT_READY",
            "exit_side_experiment_operator_review_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "exit-side verified experiment readiness completed",
            "exit_side_verified_experiment_readiness_packet valid JSON"
        )

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_profit_operator_consolidated_review_packet.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-ReviewNotionalCapUsdt", "15", "-ObservationHours", "48", "-RequireReady") `
        -RequiredMarkers @(
            "exit_side_experiment_operator_review_status=NOT_READY",
            "profit_operator_consolidated_review_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "exit-side experiment operator review packet completed",
            "EntryDedup semantics shadow experiment packet completed",
            "exit_side_experiment_operator_review_packet valid JSON",
            "entry_dedup_semantics_shadow_experiment_packet valid JSON"
        )

    Invoke-BlockedPacketScript `
        -ScriptName "prepare_profit_operator_priority_decision_brief.ps1" `
        -Arguments @("-ReviewOutputDir", $tempReviewDir, "-ReviewNotionalCapUsdt", "15", "-ObservationHours", "48", "-RequireReady") `
        -RequiredMarkers @(
            "profit_operator_consolidated_review_status=NOT_READY",
            "profit_operator_priority_decision_brief_status=NOT_READY"
        ) `
        -ForbiddenMarkers @(
            "consolidated review packet completed",
            "profit_operator_consolidated_review_packet valid JSON"
        )

    foreach ($case in @(
            [pscustomobject]@{
                ScriptName = "prepare_trailing_stop_dry_run_operator_decision_packet.ps1"
                RequiredStatus = "trailing_stop_dry_run_operator_decision_status=NOT_READY"
            },
            [pscustomobject]@{
                ScriptName = "prepare_strategy485_risk_reduction_operator_decision_packet.ps1"
                RequiredStatus = "strategy485_risk_reduction_operator_decision_status=NOT_READY"
            }
        )) {
        Invoke-BlockedPacketScript `
            -ScriptName $case.ScriptName `
            -Arguments @("-ReviewOutputDir", $tempReviewDir, "-ReviewNotionalCapUsdt", "15", "-ObservationHours", "48", "-RequireReady") `
            -RequiredMarkers @(
                "profit_operator_priority_decision_brief_status=NOT_READY",
                $case.RequiredStatus
            ) `
            -ForbiddenMarkers @(
                "priority decision brief completed",
                "exit-side experiment operator review packet completed",
                "profit_operator_priority_decision_brief_packet valid JSON",
                "exit_side_experiment_operator_review_packet valid JSON"
            )
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[profit-review-chain-blocked-packet-preservation-test] OK"
