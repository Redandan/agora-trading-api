param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator latest action brief arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$pointerPath = Join-Path $ReviewOutputDir "latest-profit-operator-matrix.path"
if (-not (Test-Path -LiteralPath $pointerPath)) {
    throw "Latest matrix pointer not found: $pointerPath. Run prepare_profit_operator_action_brief_ssh.ps1 without -MatrixOutputPath first."
}

$matrixOutputPath = (Get-Content -Raw -LiteralPath $pointerPath).Trim()
if ([string]::IsNullOrWhiteSpace($matrixOutputPath)) {
    throw "Latest matrix pointer is empty: $pointerPath"
}
if (-not (Test-Path -LiteralPath $matrixOutputPath)) {
    throw "Latest matrix output not found: $matrixOutputPath"
}

$actionScript = Join-Path $PSScriptRoot "prepare_profit_operator_action_brief_ssh.ps1"
if (-not (Test-Path -LiteralPath $actionScript)) {
    throw "Missing action brief script: $actionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for profit operator latest action brief."
}

Write-Host "[profit-operator-latest-action-brief] read-only latest brief"
Write-Host "scope=READ_ONLY; reads latest-profit-operator-matrix.path and invokes prepare_profit_operator_action_brief_ssh.ps1 with -MatrixOutputPath only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "latest_matrix_pointer=$pointerPath"
Write-Host "latest_matrix_output_path=$matrixOutputPath"

$arguments = @(
    "-MatrixOutputPath", $matrixOutputPath,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId"
)
if ($RequireReady) {
    $arguments += "-RequireReady"
}

$output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $actionScript @arguments 2>&1
$exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
$text = ($output | Out-String -Width 4096)
Write-Host $text
Write-Host "profit_operator_latest_action_brief_exit_code=$exitCode"
Write-Host "[profit-operator-latest-action-brief] read-only check complete"

if ($exitCode -ne 0) {
    throw "Profit operator latest action brief failed with exit code $exitCode"
}
