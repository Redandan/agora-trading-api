Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_oco_sync_reconciliation_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[oco-sync-reconciliation-packet] read-only packet",
        "scope=READ_ONLY",
        "OCO_SYNC_RECONCILIATION_PACKET",
        "READY_FOR_OPERATOR_RECONCILIATION_REVIEW_NOT_MUTATION",
        "PREPARE_SEPARATE_OCO_SYNC_RECONCILIATION_AUTHORIZATION",
        "positionsRequiringWrite",
        "requiredAuthorization",
        "force_close_position_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "notAuthorization=read-only OCO sync reconciliation packet only"
    )) {
    Assert-Contains -Name "OCO sync reconciliation packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_oco_sync_reconciliation_packet_ssh.ps1",
        "OCO_SYNC_RECONCILIATION_PACKET",
        "oco_sync_reconciliation_status",
        "READY_FOR_OPERATOR_RECONCILIATION_REVIEW_NOT_MUTATION"
    )) {
    Assert-Contains -Name "docs mention OCO sync reconciliation packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("oco-sync-reconciliation-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $oco = @"
OCO health summary: 0 OK | 3 SYNC_ERROR | 0 abnormal
Position #148 BTCUSDT: child 3687405218501287941 filled @ 58809.4, parent=effective, DB still OPEN.
Position #149 BTCUSDT: child 3687405350571532290 filled @ 58777.1, parent=effective, DB still OPEN.
Position #150 BTCUSDT: child 3687408430868389888 filled @ 58521.4, parent=effective, DB still OPEN.
"@
    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[oco-sync-reconciliation-source] fixture",
        "scope=READ_ONLY",
        ("oco_health_raw_json=" + (ConvertTo-Json -Compress $oco)),
        ("open_positions_raw_json=" + (ConvertTo-Json -Compress "Position #148/#149/#150 DB OPEN fixture")),
        ("execution_risk_snapshot_raw_json=" + (ConvertTo-Json -Compress "openPositionCount=3 fixture"))
    )

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for OCO sync reconciliation packet test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLogPath $tempLogPath -RequireReviewReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "OCO sync reconciliation packet failed fixture reuse:`n$text"
    }
    foreach ($marker in @(
            "sync_error_count=3",
            "positionsRequiringWrite=[148,149,150]",
            '"positionId":148',
            '"okxChildOrderId":"3687405218501287941"',
            '"proposedCloseReason":"SL"',
            "complete_reconciliation_rows=3",
            "oco_sync_reconciliation_decision=PREPARE_SEPARATE_OCO_SYNC_RECONCILIATION_AUTHORIZATION",
            "force_close_position_allowed=false",
            "close_position_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "oco_sync_reconciliation_status=READY_FOR_OPERATOR_RECONCILIATION_REVIEW_NOT_MUTATION",
            "notAuthorization=read-only OCO sync reconciliation packet only"
        )) {
        Assert-Contains -Name "OCO sync reconciliation fixture output" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "OCO sync reconciliation packet unexpectedly invoked SSH during fixture mode:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) {
        Remove-Item -LiteralPath $tempLogPath -Force
    }
}

Write-Host "[oco-sync-reconciliation-packet-test] OK"
