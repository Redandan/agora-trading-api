param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$Limit = 200,
    [string]$OutputDir = "target/profit-review",
    [string]$ExpectedCommit = $env:AGORA_EXPECTED_COMMIT,
    [switch]$RequireBlocked,
    [switch]$PlanOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for issue #7 post-deploy bundle invocation."
    }
}

function Assert-OutputPathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Invoke-ReadOnlyChild {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments,
        [string]$LogName
    )

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only child script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for issue #7 post-deploy bundle."
    }

    Write-Host ""
    Write-Host "===== BEGIN $Name ====="
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    $text = ($output | Out-String -Width 4096).Trim()
    $logPath = Join-Path $resolvedOutputDir $LogName
    $text | Set-Content -LiteralPath $logPath -Encoding UTF8

    if ($text.Length -gt 5000) {
        Write-Host ($text.Substring(0, 5000) + "`n...[truncated]")
    } else {
        Write-Host $text
    }
    Write-Host "===== END $Name exitCode=$exitCode log=$logPath ====="

    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode"
    }

    return $text
}

if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}
if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
}
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16
Assert-OutputPathSafe -Name "OutputDir" -Value $OutputDir

if ([string]::IsNullOrWhiteSpace($ExpectedCommit) -and (Get-Command git -ErrorAction SilentlyContinue)) {
    $ExpectedCommit = (git rev-parse HEAD).Trim()
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedCommit) -and $ExpectedCommit -notmatch "^[0-9a-fA-F]{7,40}$") {
    throw "ExpectedCommit must be a git hex commit prefix/full SHA."
}

$steps = @(
    [ordered]@{
        Name = "split-acceptance"
        Script = "verify_split_acceptance_ssh.ps1"
        Log = "issue7-post-deploy-split-acceptance.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-TradingAppDir", $AppDir, "-EnvFile", $EnvFile)
    },
    [ordered]@{
        Name = "filter-block-source-refresh"
        Script = "smoke_filter_block_false_kill_issue7_ssh.ps1"
        Log = "filter-block-false-kill-issue7-latest.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-IntervalCode", $IntervalCode, "-ReviewDays", "$ReviewDays", "-Limit", "$Limit")
    },
    [ordered]@{
        Name = "replay-candidate-id"
        Script = "smoke_data_freshness_replay_candidate_id_ssh.ps1"
        Log = "issue7-df-replay-candidate-id-latest.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-ReviewDays", "$ReplayIdDays", "-Limit", "$Limit", "-ExpectedCommit", $ExpectedCommit)
    },
    [ordered]@{
        Name = "replay-observation-bundle"
        Script = "smoke_data_freshness_replay_observation_bundle_ssh.ps1"
        Log = "issue7-df-replay-observation-latest.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-ReviewDays", "$ReviewDays", "-ReplayIdDays", "$ReplayIdDays", "-Limit", "$Limit")
    },
    [ordered]@{
        Name = "replay-evidence-readiness"
        Script = "prepare_data_freshness_replay_evidence_readiness_ssh.ps1"
        Log = "data-freshness-replay-evidence-readiness-refresh.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-ReviewDays", "$ReviewDays", "-ReplayIdDays", "$ReplayIdDays", "-Limit", "$Limit")
    },
    [ordered]@{
        Name = "runtime-evidence-only-env"
        Script = "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1"
        Log = "issue7-runtime-evidence-only-env-current.log"
        Arguments = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile)
    },
    [ordered]@{
        Name = "issue7-post-activation-status"
        Script = "prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1"
        Log = "issue7-collector-post-activation-status-refresh.log"
        Arguments = @(
            "-SourceLog", (Join-Path $OutputDir "filter-block-false-kill-issue7-latest.log"),
            "-ObservationLog", (Join-Path $OutputDir "issue7-df-replay-observation-latest.log"),
            "-ReadinessLogPath", (Join-Path $OutputDir "data-freshness-replay-evidence-readiness-refresh.log"),
            "-RuntimeEvidenceLog", (Join-Path $OutputDir "issue7-runtime-evidence-only-env-current.log")
        )
    }
)
if ($RequireBlocked) {
    $steps[$steps.Count - 1].Arguments += "-RequireBlocked"
}

Write-Host "[issue7-post-deploy-read-only-bundle] read-only bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local scripts only; no push, deploy, restart, nginx reload, production env, DB write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host "symbol=$Symbol interval=$IntervalCode reviewDays=$ReviewDays replayIdDays=$ReplayIdDays limit=$Limit expectedCommit=$ExpectedCommit"
Write-Host ("issue7_post_deploy_read_only_bundle_plan=" + ($steps | ConvertTo-Json -Compress -Depth 5))

if ($PlanOnly) {
    Write-Host "issue7_post_deploy_read_only_bundle_status=PLAN_READY_NOT_EXECUTED"
    Write-Host "issue7_close_allowed=false"
    Write-Host "issue7_live_relaxation_allowed=false"
    Write-Host "deploy_or_env_change_allowed=false"
    Write-Host "notAuthorization=read-only issue #7 post-deploy verification bundle plan only; does not push, deploy, restart, reload nginx, change production env, close issue #7, relax DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable scheduler, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
    return
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
Assert-SshHostSafe -Name "SshHost" -Value $SshHost

$resolvedOutputDir = Resolve-RepoPath $OutputDir
New-Item -ItemType Directory -Force -Path $resolvedOutputDir | Out-Null

$texts = @{}
foreach ($step in $steps) {
    $texts[$step.Name] = Invoke-ReadOnlyChild -Name $step.Name -ScriptName $step.Script -Arguments @($step.Arguments) -LogName $step.Log
}

$postActivationText = $texts["issue7-post-activation-status"]
$status = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_collector_post_activation_status="
$remainingBlocker = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_remaining_blocker="
$closeAllowed = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_close_allowed="
$liveRelaxationAllowed = Get-LastPrefixedValue -Text $postActivationText -Prefix "issue7_live_relaxation_allowed="

$bundleStatus = if ($status -eq "READY_TO_CLOSE_NOT_LIVE_RELAXATION") {
    "READY_TO_CLOSE_NOT_LIVE_RELAXATION"
} elseif (-not [string]::IsNullOrWhiteSpace($status)) {
    "BLOCKED_NOT_CLOSEABLE"
} else {
    "EVIDENCE_INCOMPLETE"
}

Write-Host ""
Write-Host "Issue #7 Post-Deploy Read-Only Bundle Summary:"
Write-Host "  issue7_post_deploy_read_only_bundle_status=$bundleStatus"
Write-Host "  issue7_collector_post_activation_status=$status"
Write-Host "  issue7_remaining_blocker=$remainingBlocker"
Write-Host "  issue7_close_allowed=$closeAllowed"
Write-Host "  issue7_live_relaxation_allowed=$liveRelaxationAllowed"
Write-Host "  deploy_or_env_change_allowed=false"
Write-Host "  output_dir=$resolvedOutputDir"
Write-Host "  notAuthorization=read-only issue #7 post-deploy verification bundle only; does not push, deploy, restart, reload nginx, change production env, close issue #7, relax DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable scheduler, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
