Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) { throw "$Name contains forbidden pattern: $Pattern" }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$paths = @{
    Properties = Join-Path $repoRoot "src/main/java/com/agora/config/properties/BtcDonchianShadowProperties.java"
    Policy = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcDonchianShadowPolicy.java"
    Engine = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcDonchianShadowEngine.java"
    Golden = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcDonchianShadowGoldenParityService.java"
    Lane = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcDonchianShadowLaneService.java"
    Readiness = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcDonchianShadowReadinessService.java"
    Listener = Join-Path $repoRoot "src/main/java/com/agora/service/backtest/KlineClosedEventListener.java"
    Mcp = Join-Path $repoRoot "src/main/java/com/agora/mcp/BtcDonchianShadowMcpTools.java"
    McpConfig = Join-Path $repoRoot "src/main/java/com/agora/mcp/McpToolsConfig.java"
    EngineTest = Join-Path $repoRoot "src/test/java/com/agora/service/trading/BtcDonchianShadowEngineTest.java"
    App = Join-Path $repoRoot "src/main/resources/application.yml"
    Env = Join-Path $repoRoot ".env.trading.secrets.example"
    SshSmoke = Join-Path $PSScriptRoot "smoke_btc_donchian_shadow_ssh.ps1"
    OffReadiness = Join-Path $PSScriptRoot "prepare_btc_donchian_off_deploy_readiness_ssh.ps1"
    Repair = Join-Path $PSScriptRoot "repair_btc_donchian_golden_data_ssh.ps1"
    Rollout = Join-Path $repoRoot "docs/btc-donchian-shadow-runtime-rollout-2026-07-13.md"
    OffReadinessDoc = Join-Path $repoRoot "docs/btc-donchian-off-deploy-readiness-2026-07-13.md"
    Runbook = Join-Path $repoRoot "docs/deploy-runbook.md"
}
$text = @{}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { throw "Missing $($entry.Key): $($entry.Value)" }
    $text[$entry.Key] = Get-Content -Raw -LiteralPath $entry.Value
}

foreach ($marker in @(
        'POLICY_MODE = "BTC_DONCHIAN_20D_10D_V1"',
        'ENTRY_LOOKBACK_DAYS = 20',
        'EXIT_LOOKBACK_DAYS = 10',
        'ATR_LOOKBACK_DAYS = 14',
        'INITIAL_STOP_ATR_MULTIPLE = 2.0',
        'EQUITY_RISK_PER_TRADE = 0.01',
        'MAX_CATCH_UP_BARS = 24 * 30',
        'FORWARD_MIN_DAYS = 30',
        'FORWARD_MIN_UNIQUE_ENTRIES = 5',
        'GOLDEN_ROW_COUNT = 66_009',
        '74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8',
        '361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d',
        'f63b7418c42082fcaa05e45e244e1293ae0e9109dc6bb0925d123d73a17120b8',
        '44e4b7bbab2e428c43acad4051e7561317008c84c5b84a42edb92829487f14e5')) {
    Assert-Contains -Name "frozen policy" -Text $text.Policy -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "mode is OFF or SHADOW" -Text $text.Properties -Pattern 'enum Mode\s*\{\s*OFF,\s*SHADOW\s*\}'
Assert-NotContains -Name "no LIVE mode" -Text $text.Properties -Pattern '\bLIVE\b'
foreach ($marker in @(
        'DONCHIAN_20D_BREAKOUT_ENTRY',
        'DONCHIAN_10D_BREAKDOWN_EXIT',
        'ATR_STOP',
        'UTC_DAY_NOT_CONTIGUOUS',
        'ENTRY_SIGNAL',
        'VIRTUAL_TRADE_CLOSED',
        'powerShellLedgerJson')) {
    Assert-Contains -Name "deterministic engine" -Text $text.Engine -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'READ_ONLY_REPLAY_NO_ORDER_NO_OCO_NO_TELEGRAM_NO_BACKFILL',
        'PASS_EXACT_RESEARCH_RUNTIME_GOLDEN_PARITY',
        'GOLDEN_PRICE_BAR_LEDGER_MISMATCH',
        'GOLDEN_DATASET_INCOMPLETE_FAIL_CLOSED',
        'liveImplementationPresent',
        'externalBackfillPerformed')) {
    Assert-Contains -Name "golden parity" -Text $text.Golden -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'BTC_DONCHIAN_SHADOW',
        'BTC_DONCHIAN_BLOCKED',
        'SHADOW_ONLY_NO_ORDER_CAPABILITY',
        'BOOTSTRAP_HISTORY_INCOMPLETE',
        'CATCH_UP_HISTORY_INCOMPLETE',
        'STATE_RESTORE_SCAN_LIMIT',
        'stateAfterSha256',
        'orderSent',
        'ocoModified',
        'telegramSent')) {
    Assert-Contains -Name "closed-bar lane" -Text $text.Lane -Pattern ([regex]::Escape($marker))
}
Assert-NotContains -Name "lane has no scheduler" -Text $text.Lane -Pattern '@Scheduled'
Assert-NotContains -Name "lane has no order dependency" -Text $text.Lane -Pattern 'OkxAutoTradeService|SpotPositionCloseService|OkxOcoService|TelegramService'
foreach ($marker in @(
        'READ_ONLY_SHADOW_EVIDENCE_NO_LIVE_IMPLEMENTATION',
        'SHADOW_ORDER_SENT_VIOLATION',
        'UNRESOLVED_DATA_QUALITY_BLOCKER_PRESENT',
        'STATE_HASH_MISMATCH',
        'HOURLY_LATTICE_NOT_COMPLETE',
        'READY_FOR_SHADOW_EVIDENCE_REVIEW_NOT_LIVE',
        'liveImplementationPresent',
        'promotionAuthorizationGranted')) {
    Assert-Contains -Name "readiness fail closed" -Text $text.Readiness -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "listener invokes lane" -Text $text.Listener -Pattern 'btcDonchianShadowLaneService\.evaluate\(kline\)'
foreach ($tool in @("analyzeBtcDonchianShadowGoldenParity", "getBtcDonchianShadowReadiness")) {
    Assert-Contains -Name "MCP tools" -Text $text.Mcp -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "SSH smoke tools" -Text $text.SshSmoke -Pattern ([regex]::Escape($tool))
}
Assert-Contains -Name "MCP auth" -Text $text.Mcp -Pattern 'McpAuthLevel\.OPS'
Assert-Contains -Name "MCP callback registration" -Text $text.McpConfig -Pattern 'btcDonchianShadowMcpToolCallbacks\(BtcDonchianShadowMcpTools tools\)'
Assert-Contains -Name "official full ledger test" -Text $text.EngineTest -Pattern 'officialResearchDatasetMatchesFrozenPowerShellLedgersWhenAvailable'
Assert-Contains -Name "application default OFF" -Text $text.App -Pattern 'TRADING_BTC_DONCHIAN_SHADOW_MODE:OFF'
Assert-Contains -Name "env default OFF" -Text $text.Env -Pattern 'TRADING_BTC_DONCHIAN_SHADOW_MODE=OFF'
Assert-Contains -Name "runbook smoke" -Text $text.Runbook -Pattern 'smoke_btc_donchian_shadow_ssh\.ps1'
foreach ($marker in @(
        'local-only',
        'TRADING_BTC_DONCHIAN_SHADOW_MODE=OFF',
        'TRADING_BTC_DONCHIAN_SHADOW_MODE=SHADOW',
        'No live implementation',
        'separate explicit authorization')) {
    Assert-Contains -Name "rollout boundary" -Text $text.Rollout -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'ExpectedMode = "OFF"',
        'RequireGoldenParity',
        'RequireForwardReady',
        'unsupported Donchian runtime keys present',
        'orderSent=false; ocoModified=false; telegramSent=false; liveImplementationPresent=false',
        'notAuthorization=read-only SHADOW evidence only; no production mutation performed')) {
    Assert-Contains -Name "SSH smoke boundary" -Text $text.SshSmoke -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        'ValidateSet("PREDEPLOY", "POSTDEPLOY_OFF")',
        'READ_ONLY_NO_COMMIT_NO_DEPLOY_NO_RESTART_NO_ENV_OR_DB_WRITE_NO_ORDER_NO_OCO_NO_TELEGRAM_NO_BACKFILL',
        'READY_FOR_OFF_DEPLOY_AUTHORIZATION',
        '361ab6910872079db4e58c45897828b3399c5d9cb8346afcd1970536d1ee6a6d',
        'EXPECTED_ROWS = 66009',
        'GOLDEN_UTC_LATTICE_GAPS_PRESENT',
        'GOLDEN_OHLC_INVARIANT_FAILURE_PRESENT',
        'GOLDEN_CANONICAL_PRICE_BAR_HASH_MISMATCH',
        'DONCHIAN_TOOLS_MISSING_AFTER_DEPLOY',
        'migrationRequired": False',
        'off_deploy_authorized=false',
        'shadow_activation_authorized=false',
        'order_allowed=false',
        'a new explicit authorization is required to commit/deploy')) {
    Assert-Contains -Name "OFF deploy readiness boundary" -Text $text.OffReadiness -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        '`READY_FOR_OFF_DEPLOY_AUTHORIZATION`',
        '55,405',
        '2026-05-01T17:00:00Z',
        '96b08185fa83574705e5ddbf1149407dc8a169ad7fb4098b4cf1c009f45558fa',
        '6e369d07c88c5fc641f495fdad7a5ee499cb49b3',
        'OFF Postdeploy Acceptance',
        'Later SHADOW Boundary')) {
    Assert-Contains -Name "OFF deployment handoff" -Text $text.OffReadinessDoc -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        '[switch]$Apply',
        'DONCHIAN_MODE_NOT_OFF',
        'EXPECTED_MISSING_ROWS = 55405',
        'START TRANSACTION',
        'PRODUCTION_PRESTATE_CONTRACT_MISMATCH',
        'TRANSACTIONAL_POSTSTATE_CONTRACT_MISMATCH',
        'ROLLBACK',
        'COMMIT',
        'transactionalCanonicalParityPassed')) {
    Assert-Contains -Name "bounded golden-data repair" -Text $text.Repair -Pattern ([regex]::Escape($marker))
}
foreach ($forbidden in @(
        'data-api\.binance|www\.okx\.com|api\.okx\.com',
        '\bcurl\b',
        '\bwget\b',
        '\bDELETE\s+FROM\b',
        '\bDROP\s+TABLE\b',
        '\bALTER\s+TABLE\b')) {
    Assert-NotContains -Name "golden-data repair uses only the verified local CSV" -Text $text.Repair -Pattern $forbidden
}
foreach ($forbidden in @(
        '\bINSERT\s+INTO\b',
        '\bREPLACE\s+INTO\b',
        '\bDELETE\s+FROM\b',
        '\bALTER\s+TABLE\b',
        '\bDROP\s+TABLE\b')) {
    Assert-NotContains -Name "OFF deploy readiness stays SQL read-only" -Text $text.OffReadiness -Pattern $forbidden
}

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($paths.SshSmoke, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { throw "BTC Donchian SSH smoke PowerShell parse failed: $($errors -join '; ')" }
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($paths.OffReadiness, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { throw "BTC Donchian OFF readiness PowerShell parse failed: $($errors -join '; ')" }
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($paths.Repair, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { throw "BTC Donchian golden-data repair PowerShell parse failed: $($errors -join '; ')" }

Write-Host "[btc-donchian-shadow-smoke-test] OK"
