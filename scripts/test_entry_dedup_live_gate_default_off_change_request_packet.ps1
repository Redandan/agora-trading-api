Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_live_gate_default_off_change_request_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_PACKET",
        "READY_FOR_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_INCOMPLETE_NOT_LIVE",
        "PREPARE_OPERATOR_REVIEW_FOR_DEFAULT_OFF_AUTO_TRADED_GATE_SCOPE_NOT_IMPLEMENTATION",
        "entryDedupOpenExposureScope",
        "ALL_OPEN_ROWS",
        "AUTO_TRADED_OPEN_ROWS",
        "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
        "implementationAllowed = `$false",
        "behaviorChangeAllowed = `$false",
        "orderAllowed = `$false",
        "entry_dedup_live_gate_default_off_change_request_packet",
        "implementation_allowed=false",
        "behavior_change_allowed=false",
        "order_allowed=false",
        "notAuthorization",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup live gate default-off change request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-live-gate-default-off-request-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $diffPath = Join-Path $tempDir "diff.log"
    $repoPath = Join-Path $tempDir "BtLiveSignalRepository.java"
    $testPath = Join-Path $tempDir "LocalTradingViewExecutionServiceTest.java"
    $reportPath = Join-Path $tempDir "report.md"
    $runbookPath = Join-Path $tempDir "runbook.md"

    $diffPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_REVIEW_NOT_LIVE"
        sourceEvidence = [pscustomobject]@{
            liveSignalGateScope = "ALL_EXIT_TIME_NULL_ROWS"
            stagedAddGateScope = "AUTO_TRADED_EXIT_TIME_NULL_ROWS"
            scopeMismatchPresent = $true
        }
        blockerEvidence = [pscustomobject]@{
            explainsCurrentNoBuy = $true
            remainingBlockerCount = 5
        }
        reviewEnvelope = [pscustomobject]@{
            behaviorChangeAllowed = $false
            orderAllowed = $false
        }
    }

    Set-Content -LiteralPath $diffPath -Encoding UTF8 -Value @("entry_dedup_live_gate_semantics_diff_packet=$((ConvertTo-Json -Compress -Depth 8 $diffPacket))")
    Set-Content -LiteralPath $repoPath -Encoding UTF8 -Value "interface BtLiveSignalRepository { boolean existsOpenAutoTradedPosition(); }"
    Set-Content -LiteralPath $testPath -Encoding UTF8 -Value "class LocalTradingViewExecutionServiceTest { void liveEnabledIgnoresNonAutoTradedOpenSignalWhenCheckingOpenPositionGate(){ existsOpenAutoTradedPosition(); } }"
    Set-Content -LiteralPath $reportPath -Encoding UTF8 -Value "EntryDedup Live Gate Default-Off Change Request`nAUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"
    Set-Content -LiteralPath $runbookPath -Encoding UTF8 -Value "prepare_entry_dedup_live_gate_default_off_change_request_packet.ps1`ndefault-off request is not authorization to change runtime behavior"

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction Stop
    }
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -LiveGateSemanticsDiffLogPath $diffPath `
        -BtLiveSignalRepositoryPath $repoPath `
        -LocalTradingViewExecutionServiceTestPath $testPath `
        -ReportPath $reportPath `
        -RunbookPath $runbookPath `
        -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "EntryDedup live gate default-off change request packet unexpectedly failed: $text"
    }

    foreach ($marker in @(
            "entry_dedup_live_gate_default_off_change_request_status=READY_FOR_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_NOT_LIVE",
            "entry_dedup_live_gate_default_off_change_request_decision=PREPARE_OPERATOR_REVIEW_FOR_DEFAULT_OFF_AUTO_TRADED_GATE_SCOPE_NOT_IMPLEMENTATION",
            "entry_dedup_live_gate_default_off_change_request_config_key=entryDedupOpenExposureScope",
            "entry_dedup_live_gate_default_off_change_request_default_scope=ALL_OPEN_ROWS",
            "entry_dedup_live_gate_default_off_change_request_requested_optional_scope=AUTO_TRADED_OPEN_ROWS",
            "entry_dedup_live_gate_default_off_change_request_confirm_text=AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW",
            "entry_dedup_live_gate_default_off_change_request_scope_mismatch_present=true",
            "entry_dedup_live_gate_default_off_change_request_explains_current_no_buy=true",
            "entry_dedup_live_gate_default_off_change_request_repository_has_auto_traded_gate_method=true",
            "entry_dedup_live_gate_default_off_change_request_local_tv_has_non_auto_ignored_test=true",
            "entry_dedup_live_gate_default_off_change_request_report_updated=true",
            "entry_dedup_live_gate_default_off_change_request_runbook_updated=true",
            "entry_dedup_live_gate_default_off_change_request_missing_requirements=[]",
            "request_ready=true",
            "implementation_allowed=false",
            "behavior_change_allowed=false",
            "live_gate_change_allowed=false",
            "order_allowed=false",
            "read-only EntryDedup live gate default-off change request only"
        )) {
        Assert-Contains -Name "EntryDedup live gate default-off change request output" -Text $text -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-live-gate-default-off-change-request-packet-test] OK"
