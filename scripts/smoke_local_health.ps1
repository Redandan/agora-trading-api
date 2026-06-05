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
Push-Location $repo
try {
    Write-Host "[smoke] starting local-smoke profile on port $Port"
    $args = @(
        "spring-boot:run",
        "-Dspring-boot.run.profiles=local-smoke",
        "-Dspring-boot.run.useTestClasspath=true",
        "-Dspring-boot.run.arguments=--server.port=$Port"
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
    Assert-LogContains -Path $stdout -Pattern "jdbc:h2:mem:trading-local-smoke" -Description "local-smoke uses in-memory H2 database"
    Assert-LogContains -Path $stdout -Pattern "Auto-trade enabled\s*:\s*false" -Description "OKX auto-trade is disabled"
    Assert-LogContains -Path $stdout -Pattern "Trading disabled.*private WS skipped" -Description "OKX private WebSocket is skipped"
    Assert-LogContains -Path $stdout -Pattern "AGORA_MARKET_INTERNAL_API_KEY not configured; using static fallback rates" -Description "exchange-rate client uses static fallback"
    Assert-LogContains -Path $stdout -Pattern "startup check disabled" -Description "ML materialized refresh startup check is disabled"
    Assert-LogNotContains -Path $stdout -Pattern "(?i)(order placed|placing order|submitted order|send telegram|sent telegram|connected to private|private ws connected|auto-execution enabled|auto-trade enabled\s*:\s*true)" -Description "local-smoke must not place orders, send notifications, connect private trading WS, or enable auto execution"

    Write-Host "[smoke] OK $healthUrl"
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-ProcessTree -RootPid $process.Id
    }
    Pop-Location
}
