Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-FailsBeforeExternalWork {
    param([string[]]$Arguments, [string]$ExpectedPattern)

    $script = Join-Path $PSScriptRoot "verify_strategy485_tradingview_exact_parity.ps1"
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find PowerShell for strategy 485 exact parity guard test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = $output | Out-String
    if ($exitCode -eq 0) {
        throw "Exact parity script accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "Exact parity script did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Scanning for projects|Binance Vision cursor|Invoke-RestMethod") {
        throw "Exact parity script reached Maven or external data work before its local guard`n$text"
    }
}

$scriptText = Get-Content -LiteralPath (Join-Path $PSScriptRoot "verify_strategy485_tradingview_exact_parity.ps1") -Raw
foreach ($marker in @(
        "tradingview.require.full.daily.nn",
        "RequireFullDailyNnParity",
        "data-api.binance.vision",
        "OutputDirectory must stay under the project target directory",
        "no production import/env/live/order mutation")) {
    if ($scriptText -notmatch [regex]::Escape($marker)) {
        throw "Exact parity script missing marker: $marker"
    }
}

Assert-FailsBeforeExternalWork `
    -Arguments @("-RestBaseUrl", "http://example.invalid/api/v3/klines") `
    -ExpectedPattern "official Binance Vision HTTPS kline endpoint"

$outsideTarget = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy485-exact-parity-" + [guid]::NewGuid().ToString("N"))
Assert-FailsBeforeExternalWork `
    -Arguments @("-OutputDirectory", $outsideTarget) `
    -ExpectedPattern "OutputDirectory must stay under the project target directory"

Write-Host "[strategy485-tradingview-exact-parity-test] OK"
