Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-BashCommand {
    $fromPath = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) {
        return $fromPath.Source
    }

    foreach ($candidate in @(
            "C:\Program Files\Git\bin\bash.exe",
            "C:\Program Files\Git\usr\bin\bash.exe",
            "C:\Program Files (x86)\Git\bin\bash.exe"
        )) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "bash is required for runtime log smoke classification tests"
}

function New-RuntimeLogFixture {
    param([string[]]$Lines)

    $root = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-runtime-log-smoke-" + [System.Guid]::NewGuid().ToString("N"))
    $runDir = Join-Path $root "logs\runs"
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $root "app.port") -Value "18084" -NoNewline
    Set-Content -LiteralPath (Join-Path $runDir "app-20260618T000000Z-port18084.log") -Value $Lines
    return $root
}

function Invoke-RuntimeLogSmoke {
    param(
        [string]$AppDir,
        [hashtable]$Environment = @{}
    )

    $repoRoot = Split-Path -Parent $PSScriptRoot
    $scriptPath = Join-Path $repoRoot "scripts\check_server_runtime_log.sh"
    $bash = Resolve-BashCommand
    $oldAppDir = $env:APP_DIR
    $oldValues = @{}
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $env:APP_DIR = $AppDir
        foreach ($key in $Environment.Keys) {
            $oldValues[$key] = [System.Environment]::GetEnvironmentVariable($key, "Process")
            [System.Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], "Process")
        }
        $ErrorActionPreference = "Continue"
        $output = & $bash $scriptPath 2>&1
        [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Text = ($output | Out-String)
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        foreach ($key in $Environment.Keys) {
            [System.Environment]::SetEnvironmentVariable($key, $oldValues[$key], "Process")
        }
        if ($null -eq $oldAppDir) {
            Remove-Item Env:\APP_DIR -ErrorAction SilentlyContinue
        } else {
            $env:APP_DIR = $oldAppDir
        }
    }
}

function Assert-SmokeCase {
    param(
        [string]$Name,
        [string[]]$Lines,
        [int]$ExpectedExitCode,
        [string[]]$ExpectedPatterns,
        [hashtable]$Environment = @{}
    )

    $fixture = New-RuntimeLogFixture -Lines $Lines
    try {
        $result = Invoke-RuntimeLogSmoke -AppDir $fixture -Environment $Environment
        if ($result.ExitCode -ne $ExpectedExitCode) {
            throw "$Name expected exit $ExpectedExitCode but got $($result.ExitCode):`n$($result.Text)"
        }
        foreach ($pattern in $ExpectedPatterns) {
            if ($result.Text -notmatch $pattern) {
                throw "$Name output missing pattern '$pattern':`n$($result.Text)"
            }
        }
    } finally {
        Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Assert-SmokeCase `
    -Name "clean runtime log passes" `
    -Lines @(
        "2026-06-18T00:00:00.000Z  INFO 1 --- [agora-trading-api] Started TradingApplication",
        "2026-06-18T00:00:01.000Z  INFO 1 --- [agora-trading-api] Scheduling disabled for local-smoke profile"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime ERROR count is 0",
        "runtime WARN lines match known baseline",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "telegram scheduler errors fail live readiness log smoke" `
    -Lines @(
        "2026-06-18T11:15:10.507Z ERROR 184643 --- [agora-trading-api] [trading-sched-1] c.a.service.impl.TelegramServiceImpl     : Failed to send Telegram keyboard message to channel -1003885932854: Unable to executesendmessagemethod",
        "2026-06-18T11:15:10.515Z ERROR 184643 --- [agora-trading-api] [trading-sched-1] c.a.s.trading.ExecutionEventScheduler    : [ExecutionEvent] scheduled scan failed: Failed to send Telegram keyboard message: Unable to executesendmessagemethod"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "TelegramServiceImpl",
        "ExecutionEventScheduler",
        "ERROR category telegram_service=1 execution_event_scheduler=1 unknown=0",
        "ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH",
        "runtime ERROR lines present: count=2"
    )

Assert-SmokeCase `
    -Name "unknown runtime errors fail live readiness log smoke" `
    -Lines @(
        "2026-06-18T11:16:10.507Z ERROR 184643 --- [agora-trading-api] [main] c.a.UnknownRuntimePath     : unexpected database/runtime failure before live review"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "UnknownRuntimePath",
        "ERROR category telegram_service=0 execution_event_scheduler=0 unknown=1",
        "runtime ERROR lines present: count=1"
    )

Assert-SmokeCase `
    -Name "operation-like log fails live readiness log smoke" `
    -Lines @(
        "2026-06-18T00:00:00.000Z  INFO 1 --- [agora-trading-api] Started TradingApplication",
        "2026-06-18T00:00:01.000Z  INFO 1 --- [agora-trading-api] order placed successfully for BTCUSDT"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "high-risk operation-like log lines present",
        "order placed"
    )

Assert-SmokeCase `
    -Name "okx auto-trade enabled config echo is not an operation" `
    -Lines @(
        "2026-06-26T01:58:16.105Z  INFO 2299189 --- [agora-trading-api] [main] c.a.service.impl.OkxTradingService      : [OKX] Auto-trade enabled   : true",
        "2026-06-26T01:58:17.000Z  INFO 2299189 --- [agora-trading-api] [main] c.a.TradingApplication                  : Started TradingApplication"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "OKX auto-trade enabled startup config echo present: count=1",
        "no high-risk trading/OCO/grid/Earn/fund operation-like lines",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "runtime error allow flag is diagnostic only" `
    -Lines @(
        "2026-06-18T11:15:10.507Z ERROR 184643 --- [agora-trading-api] [trading-sched-1] c.a.service.impl.TelegramServiceImpl     : Failed to send Telegram keyboard message to channel -1003885932854: Unable to executesendmessagemethod"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "ERROR category telegram_service=1 execution_event_scheduler=0 unknown=0",
        "ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH",
        "runtime ERROR lines present but allowed: count=1",
        "runtime log smoke complete"
    ) `
    -Environment @{ ALLOW_RUNTIME_ERROR = "1" }

Assert-SmokeCase `
    -Name "unknown warn allow flag is diagnostic only" `
    -Lines @(
        "2026-06-18T00:00:00.000Z  WARN 1 --- [agora-trading-api] UnexpectedWarningSource : unexpected warning before live review"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "unknown WARN lines present but allowed: count=1",
        "runtime log smoke complete"
    ) `
    -Environment @{ ALLOW_UNKNOWN_WARN = "1" }

Assert-SmokeCase `
    -Name "scorebuy ml schema mismatch is classified warn baseline" `
    -Lines @(
        "2026-06-26T00:37:28.907Z  WARN 2299189 --- [agora-trading-api] [        async-1] c.a.service.backtest.ScoreBuyV2Strategy  : [ScoreBuyV2] predict failed v21: ML_PREDICT_ROW failed: PreparedStatementCallback; uncategorized SQLException for SQL [SELECT sys.ML_PREDICT_ROW(CAST(? AS JSON), ?, NULL)]; SQL state [HY000]; error code [3877]; `"ML003011: Columns of provided data need to match those used for training. Provided - ['adx14'] vs Trained - ['adx14', 'bb_width']`""
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime WARN lines match known baseline",
        "scorebuy_ml_schema_mismatch=1",
        "unknown=0",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "pyth transient timeout is classified warn baseline" `
    -Lines @(
        "2026-06-26T23:01:23.441Z  WARN 2516045 --- [agora-trading-api] [   indicator-io] c.a.service.market.PythNetworkService    : [Pyth] feed=e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43 error: timeout"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime WARN lines match known baseline",
        "pyth_network_transient=1",
        "unknown=0",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "pyth transient timeout threshold is fail closed" `
    -Lines @(
        "2026-06-26T23:01:23.441Z  WARN 2516045 --- [agora-trading-api] [   indicator-io] c.a.service.market.PythNetworkService    : [Pyth] feed=e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43 error: timeout"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "Pyth network warnings exceeded threshold: count=1 max=0"
    ) `
    -Environment @{ MAX_PYTH_NETWORK_WARN = "0" }

Assert-SmokeCase `
    -Name "etherscan token supply transient is classified warn baseline" `
    -Lines @(
        "2026-07-01T09:01:09.642Z  WARN 3651509 --- [agora-trading-api] [   indicator-io] c.agora.service.market.EtherscanService  : [Etherscan] tokenSupply chainid=137 error for 0xc2132D05D31c914a87C6611C10748AEb04B58e8F: Error retrieving value"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime WARN lines match known baseline",
        "etherscan_token_supply=1",
        "unknown=0",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "etherscan token supply threshold is fail closed" `
    -Lines @(
        "2026-07-01T09:01:09.642Z  WARN 3651509 --- [agora-trading-api] [   indicator-io] c.agora.service.market.EtherscanService  : [Etherscan] tokenSupply chainid=137 error for 0xc2132D05D31c914a87C6611C10748AEb04B58e8F: Error retrieving value"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "Etherscan tokenSupply warnings exceeded threshold: count=1 max=0"
    ) `
    -Environment @{ MAX_ETHERSCAN_TOKEN_SUPPLY_WARN = "0" }

Assert-SmokeCase `
    -Name "okx ws transient null is classified warn baseline" `
    -Lines @(
        "2026-06-27T09:09:16.576Z  WARN 2516045 --- [agora-trading-api] [kx.com:8443/...] c.a.service.market.OkxWsKlineService     : [OkxWS] WS failure BTCUSDT@1m: null"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime WARN lines match known baseline",
        "okx_ws_transient=1",
        "unknown=0",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "okx ws transient threshold is fail closed" `
    -Lines @(
        "2026-06-27T09:09:16.576Z  WARN 2516045 --- [agora-trading-api] [kx.com:8443/...] c.a.service.market.OkxWsKlineService     : [OkxWS] WS failure BTCUSDT@1m: null"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "OKX WS transient warnings exceeded threshold: count=1 max=0"
    ) `
    -Environment @{ MAX_OKX_WS_TRANSIENT_WARN = "0" }

Assert-SmokeCase `
    -Name "mcp auth denied is classified warn baseline" `
    -Lines @(
        "2026-06-30T04:18:43.818Z  WARN 3396029 --- [agora-trading-api] [mcat-handler-99] com.agora.mcp.auth.McpApiKeyFilter       : [McpAuth] DENIED MCP method=tools/list ip=127.0.0.1 reason=metadata key missing"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "runtime WARN lines match known baseline",
        "mcp_auth_denied=1",
        "unknown=0",
        "runtime log smoke complete"
    )

Assert-SmokeCase `
    -Name "mcp auth denied threshold is fail closed" `
    -Lines @(
        "2026-06-30T04:18:43.818Z  WARN 3396029 --- [agora-trading-api] [mcat-handler-99] com.agora.mcp.auth.McpApiKeyFilter       : [McpAuth] DENIED MCP method=tools/list ip=127.0.0.1 reason=metadata key missing"
    ) `
    -ExpectedExitCode 1 `
    -ExpectedPatterns @(
        "MCP auth denied warnings exceeded threshold: count=1 max=0"
    ) `
    -Environment @{ MAX_MCP_AUTH_DENIED_WARN = "0" }

Assert-SmokeCase `
    -Name "high risk allow flag is diagnostic only" `
    -Lines @(
        "2026-06-18T00:00:00.000Z  INFO 1 --- [agora-trading-api] Started TradingApplication",
        "2026-06-18T00:00:01.000Z  INFO 1 --- [agora-trading-api] order placed successfully for BTCUSDT"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
        "high-risk operation-like log lines present but allowed: count=1",
        "runtime log smoke complete"
    ) `
    -Environment @{ ALLOW_HIGH_RISK_LOG = "1" }

Write-Host "[runtime-log-smoke-classification-test] OK"
