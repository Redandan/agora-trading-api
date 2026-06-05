param(
    [int]$Port = 18084,
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Stop-ProcessTree {
    param([int]$RootPid)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootPid" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -RootPid $child.ProcessId
    }

    $process = Get-Process -Id $RootPid -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $RootPid -Force -ErrorAction SilentlyContinue
    }
}

function Assert-LogContains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Description
    )

    if (-not (Select-String -Path $Path -Pattern $Pattern -Quiet)) {
        throw "Local smoke log missing expected evidence: $Description. pattern=$Pattern stdout=$Path"
    }
}

function Assert-LogNotContains {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Description
    )

    $match = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if ($match) {
        throw "Local smoke log contains forbidden evidence: $Description. match=$($match.Line) stdout=$Path"
    }
}

$repo = Resolve-Path "$PSScriptRoot\.."
$healthUrl = "http://127.0.0.1:$Port/api/trading/actuator/health"
$logDir = Join-Path $repo "logs\local-smoke"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format "yyyyMMddTHHmmss"
$stdout = Join-Path $logDir "smoke-$stamp.out.log"
$stderr = Join-Path $logDir "smoke-$stamp.err.log"

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    throw "Port $Port is already listening. Choose another port with -Port."
}

$mvn = (Get-Command mvn.cmd -ErrorAction SilentlyContinue)
if (-not $mvn) {
    $mvn = Get-Command mvn -ErrorAction Stop
}

$process = $null
$previousEnv = @{}
$envOverrides = @{
    AGORA_MARKET_INTERNAL_API_KEY = ""
    AGORA_MARKET_BASE_URL = "http://127.0.0.1:0"
    TELEGRAM_BOT_TOKEN = ""
    TRADING_OKX_ENABLED = "false"
    TRADING_OKX_API_KEY = ""
    TRADING_OKX_SECRET_KEY = ""
    TRADING_OKX_PASSPHRASE = ""
    TRADING_BINANCE_ENABLED = "false"
    TRADING_BINANCE_API_KEY = ""
    TRADING_BINANCE_SECRET_KEY = ""
    GEMINI_API_KEY = ""
    GROQ_API_KEY = ""
    ANTHROPIC_API_KEY = ""
    JINA_API_KEY = ""
    EXTERNAL_COINALYZE_API_KEY = ""
    EXTERNAL_COINGECKO_DEMO_API_KEY = ""
    EXTERNAL_FRED_API_KEY = ""
    EXTERNAL_ETHERSCAN_API_KEY = ""
    EXTERNAL_ALCHEMY_API_KEY = ""
    EXTERNAL_THEGRAPH_API_KEY = ""
    EXCHANGE_RATE_COINMARKETCAP_API_KEY = ""
    MARKET_WS_AUTO_SUBSCRIBE_ENABLED = "false"
    MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED = "false"
    OKX_EARN_TOPUP_ENABLED = "false"
    POLYMARKET_MONITOR_ENABLED = "false"
    TRAILING_STOP_ENABLED = "false"
    TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED = "false"
    TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN = "true"
}
Push-Location $repo
try {
    Write-Host "[smoke] starting local-smoke profile on port $Port"
    foreach ($name in $envOverrides.Keys) {
        $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $envOverrides[$name], "Process")
    }
    $bootArguments = @(
        "--server.port=$Port",
        "--agora-market.internal-api-key=",
        "--telegram.bot.token=",
        "--trading.okx.api-key=",
        "--trading.okx.secret-key=",
        "--trading.okx.passphrase=",
        "--trading.binance.api-key=",
        "--trading.binance.secret-key=",
        "--gemini.api.key=",
        "--groq.api.key=",
        "--anthropic.api.key=",
        "--jina.api.key=",
        "--external.coinalyze.api-key=",
        "--external.coingecko.demo-api-key=",
        "--external.fred.api-key=",
        "--external.etherscan.api-key=",
        "--external.alchemy.api-key=",
        "--external.thegraph.api-key=",
        "--exchange-rate.coinmarketcap.api-key=",
        "--market.ws.auto-subscribe.enabled=false",
        "--market.ws.auto-subscribe.warm-up-enabled=false",
        "--okx.earn-topup.enabled=false",
        "--polymarket.monitor.enabled=false",
        "--trailing-stop.enabled=false"
    )
    $args = @(
        "spring-boot:run",
        "-Dspring-boot.run.profiles=local-smoke",
        "-Dspring-boot.run.useTestClasspath=true",
        "-Dspring-boot.run.arguments=`"$($bootArguments -join ' ')`""
    )
    $process = Start-Process `
        -FilePath $mvn.Source `
        -ArgumentList $args `
        -WorkingDirectory $repo `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    $ready = $false
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "Local smoke process exited before health passed. stdout=$stdout stderr=$stderr"
        }

        try {
            $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }

    if (-not $ready) {
        throw "Timed out waiting for $healthUrl. stdout=$stdout stderr=$stderr"
    }

    Start-Sleep -Milliseconds 500
    Assert-LogContains -Path $stdout -Pattern 'profile is active: "local-smoke"' -Description "local-smoke profile is active"
    Assert-LogContains -Path $stdout -Pattern "Scheduling disabled for local-smoke profile" -Description "local-smoke scheduling is disabled"
    Assert-LogContains -Path $stdout -Pattern "jdbc:h2:mem:trading-local-smoke" -Description "local-smoke uses in-memory H2 database"
    Assert-LogContains -Path $stdout -Pattern "Auto-trade enabled\s*:\s*false" -Description "OKX auto-trade is disabled"
    Assert-LogContains -Path $stdout -Pattern "API Key configured\s*:\s*false" -Description "OKX API key is cleared for local-smoke"
    Assert-LogContains -Path $stdout -Pattern "Trading disabled.*private WS skipped" -Description "OKX private WebSocket is skipped"
    Assert-LogContains -Path $stdout -Pattern "AGORA_MARKET_INTERNAL_API_KEY not configured; using static fallback rates" -Description "exchange-rate client uses static fallback"
    Assert-LogContains -Path $stdout -Pattern "startup check disabled" -Description "ML materialized refresh startup check is disabled"
    Assert-LogContains -Path $stdout -Pattern "AiTaskRouter.*initialized with 0 providers" -Description "local-smoke does not initialize external AI providers"
    Assert-LogContains -Path $stdout -Pattern "Jina embedding client initialised: enabled=false" -Description "local-smoke does not enable external Jina embeddings"
    Assert-LogContains -Path $stdout -Pattern "MarketWS.*auto-subscribe config: enabled=false" -Description "local-smoke does not enable public market WS auto-subscribe"
    Assert-LogContains -Path $stdout -Pattern "EarnTopUp.*config: enabled=false" -Description "local-smoke does not enable OKX Earn top-up"
    Assert-LogContains -Path $stdout -Pattern "PolymarketMonitor.*config: enabled=false" -Description "local-smoke does not enable Polymarket monitor"
    Assert-LogContains -Path $stdout -Pattern "TrailingStop.*config: enabled=false" -Description "local-smoke does not enable trailing-stop OCO updates"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(Auto subscribed via|Warming up MarketSignalCache)" -Description "local-smoke must not auto-subscribe public market WebSockets or warm market cache"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(Trading buffer topped from Earn|Simple Earn)" -Description "local-smoke must not top up from OKX Earn"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(modifyOco|state .*->|state .*→|sl .*->|sl .*→)" -Description "local-smoke must not modify trailing-stop OCO state"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(order placed|placing order|submitted order|send telegram|sent telegram|connected to private|private ws connected|auto-execution enabled|auto-trade enabled\s*:\s*true)" -Description "local-smoke must not place orders, send notifications, connect private trading WS, or enable auto execution"

    Write-Host "[smoke] OK $healthUrl"
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-ProcessTree -RootPid $process.Id
    }
    foreach ($name in $envOverrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
    Pop-Location
}
