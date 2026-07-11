Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$normalizer = Join-Path $PSScriptRoot "normalize_tradingview_golden_truth.ps1"
if (-not (Test-Path -LiteralPath $normalizer -PathType Leaf)) {
    throw "Normalizer script not found: $normalizer"
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-ThrowsLike {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    $threw = $false
    try {
        & $Action
    } catch {
        $threw = $true
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Message; unexpected error: $($_.Exception.Message)"
        }
    }
    if (-not $threw) { throw "$Message; expected an exception" }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-tv-golden-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $inputCsv = Join-Path $tempRoot "tradingview-export.csv"
    $output = Join-Path $tempRoot "golden.csv"
    $reasonMap = Join-Path $tempRoot "reason-map.json"
    $labelMap = Join-Path $tempRoot "label-map.json"

    @'
Entry Time,Entry ID,Entry Comment,Contracts,Neural network output,Order Type
2025-07-01T08:00:00+08:00,relative-low,relative-low,1000.0,0.3810000,Entry
2026-07-01T08:00:00+08:00,ai-buy,ai-buy,5000,0.4000002,Entry
2026-07-01T08:00:00+08:00,ignored-exit,ignored-exit,5000,0.4000002,Exit
'@ | Set-Content -LiteralPath $inputCsv -Encoding utf8
    '{"relative-low":"TRADINGVIEW_RELATIVE_LOW","ai-buy":"TRADINGVIEW_AI_BUY_SIGNAL"}' |
        Set-Content -LiteralPath $reasonMap -Encoding utf8
    '{"relative-low":"Relative low","ai-buy":"AI buy"}' |
        Set-Content -LiteralPath $labelMap -Encoding utf8

    $result = & $normalizer `
        -InputPath $inputCsv `
        -OutputPath $output `
        -WindowStartUtc "2025-07-01T00:00:00Z" `
        -WindowEndUtc "2026-07-01T00:00:00Z" `
        -ReasonMapPath $reasonMap `
        -LabelMapPath $labelMap `
        -FilterColumn "Order Type" `
        -FilterRegex '^Entry$' 6>&1

    $resultText = $result | Out-String
    Assert-True ($resultText -match "status=READY_FOR_LOCAL_PARITY_VERIFICATION_NOT_PRODUCTION_IMPORT") `
        "normalizer did not emit the local-only readiness marker"
    Assert-True ($resultText -match "productionImportAllowed=false") `
        "normalizer must deny production import authorization"
    Assert-True (Test-Path -LiteralPath $output -PathType Leaf) "golden CSV was not created"
    Assert-True (Test-Path -LiteralPath "$output.manifest.json" -PathType Leaf) "manifest was not created"

    $golden = @(Import-Csv -LiteralPath $output)
    Assert-True ($golden.Count -eq 2) "expected two filtered entry intents"
    Assert-True ($golden[0].time -eq "2025-07-01T00:00:00") "offset time was not normalized to UTC"
    Assert-True ($golden[0].reason -eq "TRADINGVIEW_RELATIVE_LOW") "reason map was not applied"
    Assert-True ($golden[0].label -eq "Relative low") "label map was not applied"
    Assert-True ($golden[0].qty -eq "1000") "qty was not canonically normalized"
    Assert-True ($golden[0].nn_output -eq "0.381") "NN output was not canonically normalized"
    Assert-True ($golden[1].time -eq "2026-07-01T00:00:00") "same-window end intent was not retained"

    $manifest = Get-Content -Raw -LiteralPath "$output.manifest.json" | ConvertFrom-Json
    Assert-True ($manifest.intentCount -eq 2) "manifest intent count is wrong"
    Assert-True ($manifest.windowDays -eq 365) "manifest must prove the 365-day export window"
    Assert-True ($manifest.nnEvidenceComplete -eq $true) "manifest must require complete NN evidence"
    Assert-True ($manifest.goldenCsvSha256 -match '^[0-9a-f]{64}$') "manifest golden SHA-256 is invalid"
    Assert-True ($manifest.productionEnvChangeAllowed -eq $false) "manifest must deny production env change"
    Assert-True ($manifest.livePromotionAllowed -eq $false) "manifest must deny live promotion"

    Assert-ThrowsLike -Action {
        & $normalizer `
            -InputPath $inputCsv `
            -OutputPath (Join-Path $tempRoot "too-short.csv") `
            -WindowStartUtc "2025-07-02T00:00:00Z" `
            -WindowEndUtc "2026-07-01T00:00:00Z" `
            -ReasonMapPath $reasonMap `
            -LabelMapPath $labelMap `
            -FilterColumn "Order Type" `
            -FilterRegex '^Entry$'
    } -Pattern "at least 365 days" -Message "short TradingView window must fail closed"

    $missingNnInput = Join-Path $tempRoot "missing-nn.csv"
    @'
time,reason,label,qty,nn_output
2026-01-01T00:00:00Z,TRADINGVIEW_RELATIVE_LOW,Relative low,1000,
'@ | Set-Content -LiteralPath $missingNnInput -Encoding utf8
    Assert-ThrowsLike -Action {
        & $normalizer `
            -InputPath $missingNnInput `
            -OutputPath (Join-Path $tempRoot "missing-nn-golden.csv") `
            -WindowStartUtc "2025-01-01T00:00:00Z" `
            -WindowEndUtc "2026-01-01T00:00:00Z"
    } -Pattern "invalid nn_output decimal" -Message "blank NN evidence must fail closed"

    Assert-ThrowsLike -Action {
        & $normalizer `
            -InputPath $inputCsv `
            -OutputPath $output `
            -WindowStartUtc "2025-07-01T00:00:00Z" `
            -WindowEndUtc "2026-07-01T00:00:00Z" `
            -ReasonMapPath $reasonMap `
            -LabelMapPath $labelMap `
            -FilterColumn "Order Type" `
            -FilterRegex '^Entry$'
    } -Pattern "Output already exists" -Message "existing golden output must require -Force"
} finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
    $expectedRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $resolvedTemp.StartsWith($expectedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected test path: $resolvedTemp"
    }
    Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
}

Write-Host "[tradingview-golden-normalizer-test] OK"
