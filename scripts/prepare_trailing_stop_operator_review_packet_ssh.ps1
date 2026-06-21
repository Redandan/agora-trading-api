param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [string]$ReplayIntervalCode = "1m",
    [int]$Days = 30,
    [int]$Limit = 500,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for trailing operator packet arguments."
    }
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups[1].Value.Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop operator review packet."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($Days -lt 1 -or $Days -gt 90) {
    throw "Days must be between 1 and 90."
}
if ($Limit -lt 1 -or $Limit -gt 500) {
    throw "Limit must be between 1 and 500."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-SmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode

$smokeArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-IntervalCode", $IntervalCode,
    "-ReplayIntervalCode", $ReplayIntervalCode,
    "-Days", [string]$Days,
    "-Limit", [string]$Limit,
    "-RequireAcceptance"
)
$smoke = Invoke-ReadOnlyScript -ScriptName "smoke_trailing_stop_pnl_replay_ssh.ps1" -Arguments $smokeArgs
$text = $smoke.Text

$sampleStatus = Get-RegexValue -Text $text -Pattern "sampleStatus=([A-Z_]+)" -Default "N/A"
$tradesFound = Get-RegexValue -Text $text -Pattern "tradesFound=([0-9]+)" -Default "0"
$replayed = Get-RegexValue -Text $text -Pattern "replayed=([0-9]+)" -Default "0"
$acceptanceRows = Get-RegexValue -Text $text -Pattern "acceptanceRows=([0-9]+)" -Default "0"
$trailingExited = Get-RegexValue -Text $text -Pattern "trailingExited=([0-9]+)" -Default "0"
$improved = Get-RegexValue -Text $text -Pattern "improved=([0-9]+)" -Default "0"
$worsened = Get-RegexValue -Text $text -Pattern "worsened=([0-9]+)" -Default "0"
$ambiguousSameBar = Get-RegexValue -Text $text -Pattern "ambiguousSameBar=([0-9]+)" -Default "0"
$originalNetPnl = Get-RegexValue -Text $text -Pattern "acceptanceOriginalNetPnl=([-+0-9.]+)" -Default "N/A"
$trailingNetPnl = Get-RegexValue -Text $text -Pattern "acceptanceTrailingNetPnl=([-+0-9.]+)" -Default "N/A"
$deltaPnl = Get-RegexValue -Text $text -Pattern "acceptanceDeltaPnl=([-+0-9.]+)" -Default "N/A"
$improvementPct = Get-RegexValue -Text $text -Pattern "improvementPct=([-+0-9.]+%)" -Default "N/A"
$acceptance = Get-RegexValue -Text $text -Pattern "acceptance=([A-Z_]+)" -Default "N/A"
$acceptanceBlocker = Get-RegexValue -Text $text -Pattern "acceptanceBlocker=([A-Z_]+)" -Default "N/A"
$operatorAction = Get-RegexValue -Text $text -Pattern "operatorAction:\s*([^\r\n]+)" -Default "N/A"

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($smoke.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop PnL replay smoke completed"
}
if ($text -notmatch "boundary:\s*READ_ONLY") {
    Add-MissingRequirement -List $missingRequirements -Value "read-only boundary marker"
}
if ($text -notmatch "acceptanceTarget: total trailing PnL improvement >= 5%") {
    Add-MissingRequirement -List $missingRequirements -Value "acceptance target marker"
}
if ($text -notmatch "acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals") {
    Add-MissingRequirement -List $missingRequirements -Value "ambiguous same-bar exclusion marker"
}
if ($sampleStatus -ne "REPLAYED") {
    Add-MissingRequirement -List $missingRequirements -Value "sampleStatus=REPLAYED"
}
if ($acceptance -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "acceptance=PASS"
}
if ($acceptanceBlocker -ne "NONE") {
    Add-MissingRequirement -List $missingRequirements -Value "acceptanceBlocker=NONE"
}

$packetStatus = "NOT_READY"
$nextAction = "Collect stronger trailing-stop replay evidence before any exit-side operator review."
if ($smoke.ExitCode -ne 0) {
    $packetStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only trailing-stop replay collection before drafting any operator packet."
} elseif ($missingRequirements.Count -eq 0) {
    $packetStatus = "READY_FOR_OPERATOR_PACKET_NOT_LIVE"
    $nextAction = "Attach this packet to a separate exit-side operator review; this is not live trailing/OCO approval."
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_OPERATOR_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    intervalCode = $IntervalCode
    replayIntervalCode = $ReplayIntervalCode
    lookbackDays = $Days
    sampleLimit = $Limit
    sourceSmoke = "smoke_trailing_stop_pnl_replay_ssh.ps1"
    sampleStatus = $sampleStatus
    tradesFound = $tradesFound
    replayed = $replayed
    acceptanceRows = $acceptanceRows
    trailingExited = $trailingExited
    improved = $improved
    worsened = $worsened
    ambiguousSameBar = $ambiguousSameBar
    acceptanceOriginalNetPnl = $originalNetPnl
    acceptanceTrailingNetPnl = $trailingNetPnl
    acceptanceDeltaPnl = $deltaPnl
    improvementPct = $improvementPct
    acceptance = $acceptance
    acceptanceBlocker = $acceptanceBlocker
    operatorAction = $operatorAction
    requiredOperatorChecks = @(
        "confirm trailing-stop global scheduler stays disabled or dry-run until separately approved",
        "confirm strategy opt-in scope before any future trailing-stop enablement",
        "confirm OCO modification path remains disabled unless separately approved",
        "confirm ambiguous same-bar rows are excluded from acceptance"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only trailing-stop operator review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
}

Write-Host "[trailing-stop-operator-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs smoke_trailing_stop_pnl_replay_ssh.ps1 -RequireAcceptance only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_trailing_stop_pnl_replay_ssh.ps1"
Write-Host "source_smoke_exit_code=$($smoke.ExitCode)"
Write-Host "sampleStatus=$sampleStatus"
Write-Host "tradesFound=$tradesFound"
Write-Host "replayed=$replayed"
Write-Host "acceptanceRows=$acceptanceRows"
Write-Host "trailingExited=$trailingExited"
Write-Host "improved=$improved"
Write-Host "worsened=$worsened"
Write-Host "ambiguousSameBar=$ambiguousSameBar"
Write-Host "acceptanceOriginalNetPnl=$originalNetPnl"
Write-Host "acceptanceTrailingNetPnl=$trailingNetPnl"
Write-Host "acceptanceDeltaPnl=$deltaPnl"
Write-Host "improvementPct=$improvementPct"
Write-Host "acceptance=$acceptance"
Write-Host "acceptanceBlocker=$acceptanceBlocker"
Write-Host "operatorAction=$operatorAction"
Write-Host ("trailing_stop_operator_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("trailing_stop_operator_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet))
Write-Host "trailing_stop_operator_packet_status=$packetStatus"
Write-Host "trailing_stop_operator_packet_next_action=$nextAction"
Write-Host "notAuthorization=read-only trailing-stop operator review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, enable trailing scheduler, change strategy opt-in, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize exit policy changes"
Write-Host "[trailing-stop-operator-review-packet] read-only check complete"

if ($RequireReady -and $packetStatus -ne "READY_FOR_OPERATOR_PACKET_NOT_LIVE") {
    throw "Trailing-stop operator packet is not ready: $packetStatus; missing=$(@($missingRequirements) -join '; ')"
}
