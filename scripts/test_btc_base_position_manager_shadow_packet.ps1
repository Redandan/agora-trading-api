Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_btc_base_position_manager_shadow_packet_ssh.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "BTC_BASE_POSITION_MANAGER_PREDEPLOY_SHADOW_PACKET",
        "PREDEPLOY_PRODUCTION_READ_ONLY_SIMULATION",
        "EXACT_TRADED_QTY_OCO_QTY_PARITY_REQUIRES_POST_DEPLOY_MANAGER_TOOL",
        "READY_FOR_POST_DEPLOY_EXACT_ADOPTION_PREVIEW_NOT_LIVE_ACTION",
        "adoptionEligible = `$false",
        "databaseMutated = `$false",
        "orderSent = `$false",
        "ocoCancelled = `$false",
        "ocoModified = `$false",
        "telegramSent = `$false",
        "position_or_oco_mutation_allowed=false",
        "notAuthorization=")) {
    Assert-Contains -Name "shadow packet safety contract" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("btc-base-manager-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $decision = [ordered]@{
        strategyId = 508
        ocoHealthOk = $true
        positions = @(
            [ordered]@{ positionId = 260; suggestion = "MODIFY"; evUsdt = "-0.09"; paperPct = "-0.16"; entryAgeDays = 4.79 },
            [ordered]@{ positionId = 261; suggestion = "MODIFY"; evUsdt = "-0.28"; paperPct = "-2.06"; entryAgeDays = 3.95 },
            [ordered]@{ positionId = 262; suggestion = "MODIFY"; evUsdt = "-0.34"; paperPct = "-2.70"; entryAgeDays = 3.87 }
        )
    }
    $open = @"
openPositions==== 開倉清單 ===
ID: 260 Strategy ID: 508 Interval: 4h Signal source: LiveSignalEvaluator 入場價: 62762.00000000 數量: 0.00015933 TP: 66551.68000000 | SL: 55250.45000000 OCO 保護: active algoId=3727763466544136192 ---
ID: 261 Strategy ID: 508 Interval: 4h Signal source: LiveSignalEvaluator 入場價: 63979.30000000 數量: 0.00015630 TP: 67811.80000000 | SL: 56296.59000000 OCO 保護: active algoId=3730179375279816704 ---
ID: 262 Strategy ID: 508 Interval: 1h Signal source: LiveSignalEvaluator 入場價: 64400.20000000 數量: 0.00015527 TP: 68255.31000000 | SL: 56664.78000000 OCO 保護: active algoId=3730420950782099456 ---
"@
    Set-Content -LiteralPath $fixture -Encoding UTF8 -Value @(
        $open,
        ("strategy485_position_review_decision=" + (ConvertTo-Json -Compress -Depth 10 $decision))
    )

    $output = & $scriptPath -SourceLogPath $fixture -PositionIds "260,261,262" *>&1
    $text = $output | Out-String
    foreach ($marker in @(
            "requested_position_ids=[260,261,262]",
            "simulation_recommendation=ADOPT_KEEP_BTC_HIGH_RISK_REVIEW",
            "adoption_eligible=false",
            "EXACT_TRADED_QTY_OCO_QTY_PARITY_REQUIRES_POST_DEPLOY_MANAGER_TOOL",
            '"displayedOwnedQty":0.00047090',
            '"costUsdt":29.999253104',
            '"weightedEntry":63706.207483542',
            '"heuristicCombinedEvUsdt":-0.71',
            '"intervalCode":"1h"',
            '"orderSent":false',
            '"ocoModified":false',
            "btc_base_position_manager_status=READY_FOR_POST_DEPLOY_EXACT_ADOPTION_PREVIEW_NOT_LIVE_ACTION",
            "position_or_oco_mutation_allowed=false")) {
        Assert-Contains -Name "shadow packet fixture" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied") {
        throw "Fixture mode unexpectedly invoked SSH."
    }
} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Force }
}

Write-Host "[btc-base-position-manager-shadow-packet-test] OK"
