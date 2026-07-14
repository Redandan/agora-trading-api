Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "execute_btc_base_position_adoption_ssh.ps1"
if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "Missing operator script: $scriptPath"
}

$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$null,
    [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "Operator script has PowerShell parse errors: $($parseErrors.Message -join '; ')"
}

$text = Get-Content -Raw -LiteralPath $scriptPath

function Assert-Contains {
    param([string]$Name, [string]$Pattern)
    if ($text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Pattern)
    if ($text -match $Pattern) {
        throw "$Name contains forbidden pattern: $Pattern"
    }
}

Assert-Contains -Name "default execute is opt-in" -Pattern '\[switch\]\$Execute'
Assert-Contains -Name "exact local confirmation" -Pattern 'EXECUTE_BTC_BASE_ADOPTION_KEEP_BTC_CANCEL_OCO'
Assert-Contains -Name "feature gate" -Pattern 'TRADING_BTC_BASE_ADOPTION_ENABLED'
Assert-Contains -Name "live action gate" -Pattern 'TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED'
Assert-Contains -Name "single runtime assertion" -Pattern 'assert_single_runtime'
Assert-Contains -Name "clean stop" -Pattern 'runtime PID \$pid did not stop cleanly'
Assert-Contains -Name "original env hash" -Pattern 'ORIGINAL_ENV_SHA'
Assert-Contains -Name "atomic env replacement" -Pattern 'os\.replace\(temp_path, path\)'
Assert-Contains -Name "automatic failure recovery" -Pattern 'failure recovery: restoring original env and gates-off runtime'
Assert-Contains -Name "dynamic service confirmation" -Pattern 'requiredConfirmText'
Assert-Contains -Name "protected write tool" -Pattern 'adoptBtcBasePositionsKeepBtc'
Assert-Contains -Name "exact aggregate assertion" -Pattern 'aggregate quantity drift'
Assert-Contains -Name "OCO cancellation count assertion" -Pattern 'ocoCancelConfirmedCount'
Assert-Contains -Name "retained BTC assertion" -Pattern 'btcRetainedConfirmed'
Assert-Contains -Name "market sell false assertion" -Pattern 'marketSellAttempted'
Assert-Contains -Name "post-restart gates off assertion" -Pattern 'PASS_GATES_OFF_MANAGED_BTC_RETAINED'
Assert-Contains -Name "sanitized execution evidence" -Pattern 'dynamicConfirmTextStored'
Assert-Contains -Name "read-only default status" -Pattern 'READY_FOR_EXPLICIT_EXECUTION_NOT_MUTATED'
Assert-Contains -Name "runtime log verifier filename compatibility" -Pattern 'btc-base-adoption-port\$\{ACTIVE_PORT\}\.log'
Assert-NotContains -Name "operator never submits a sell" -Pattern 'placeMarketSell|closeAtMarket\s*\('
Assert-NotContains -Name "operator never sends Telegram" -Pattern 'TelegramService|broadcast\s*\('

$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$verifyLocal = Get-Content -Raw -LiteralPath $verifyLocalPath
if ($verifyLocal -notmatch [regex]::Escape('test_btc_base_position_adoption_execution.ps1')) {
    throw "verify_local.ps1 does not invoke the BTC Base adoption execution test"
}

Write-Host "[btc-base-position-adoption-execution-test] OK"
