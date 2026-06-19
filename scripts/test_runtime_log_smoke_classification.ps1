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
    try {
        $env:APP_DIR = $AppDir
        foreach ($key in $Environment.Keys) {
            $oldValues[$key] = [System.Environment]::GetEnvironmentVariable($key, "Process")
            [System.Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], "Process")
        }
        $output = & $bash $scriptPath 2>&1
        [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Text = ($output | Out-String)
        }
    } finally {
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
        "runtime ERROR lines present: count=2"
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
    -Name "runtime error allow flag is diagnostic only" `
    -Lines @(
        "2026-06-18T11:15:10.507Z ERROR 184643 --- [agora-trading-api] [trading-sched-1] c.a.service.impl.TelegramServiceImpl     : Failed to send Telegram keyboard message to channel -1003885932854: Unable to executesendmessagemethod"
    ) `
    -ExpectedExitCode 0 `
    -ExpectedPatterns @(
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
