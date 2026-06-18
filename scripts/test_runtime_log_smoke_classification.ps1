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
    param([string]$AppDir)

    $repoRoot = Split-Path -Parent $PSScriptRoot
    $scriptPath = Join-Path $repoRoot "scripts\check_server_runtime_log.sh"
    $bash = Resolve-BashCommand
    $oldAppDir = $env:APP_DIR
    try {
        $env:APP_DIR = $AppDir
        $output = & $bash $scriptPath 2>&1
        [PSCustomObject]@{
            ExitCode = $LASTEXITCODE
            Text = ($output | Out-String)
        }
    } finally {
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
        [string[]]$ExpectedPatterns
    )

    $fixture = New-RuntimeLogFixture -Lines $Lines
    try {
        $result = Invoke-RuntimeLogSmoke -AppDir $fixture
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

Write-Host "[runtime-log-smoke-classification-test] OK"
