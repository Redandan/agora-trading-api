param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$Branch = "main",
    [int]$PollSeconds = 10,
    [int]$TimeoutSeconds = 900
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

if ($PollSeconds -lt 2) {
    throw "PollSeconds must be at least 2."
}

if ($PollSeconds -gt 60) {
    throw "PollSeconds must be at most 60."
}

if ($TimeoutSeconds -lt 60 -or $TimeoutSeconds -gt 3600) {
    throw "TimeoutSeconds must be between 60 and 3600."
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

function Assert-RemoteRelativePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._/-]+$" -or $Value.StartsWith("/") -or $Value.Contains("..")) {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-GitBranchSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 128 -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._/-]*$" -or $Value.Contains("..") -or $Value.EndsWith("/") -or $Value.EndsWith(".")) {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-GitBranchSafe -Name "Branch" -Value $Branch

function Invoke-RemoteScript {
    param([string]$Script)

    $Script | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "remote command failed with exit code $LASTEXITCODE"
    }
}

$startScript = @"
set -euo pipefail
cd '$AppDir'
git fetch origin '$Branch'
git checkout '$Branch'
git reset --hard 'origin/$Branch'
mkdir -p logs/deploy
stamp=`$(date -u +%Y%m%dT%H%M%SZ)
log="logs/deploy/deploy-`$stamp.log"
exitfile="logs/deploy/deploy-`$stamp.exit"
pidfile="logs/deploy/deploy-`$stamp.pid"
nohup bash -c "cd '$AppDir' && bash deploy.sh > '`$log' 2>&1; code=\`$?; echo \`$code > '`$exitfile'" >/dev/null 2>&1 &
pid=`$!
echo "`$pid" > "`$pidfile"
printf 'DEPLOY_PID=%s\nDEPLOY_LOG=%s\nDEPLOY_EXIT=%s\n' "`$pid" "`$log" "`$exitfile"
"@

$startOutput = @($startScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s")
if ($LASTEXITCODE -ne 0) {
    throw "failed to start remote deploy with exit code $LASTEXITCODE"
}

$deployLog = $null
$deployExit = $null
foreach ($line in $startOutput) {
    Write-Host $line
    if ($line -like "DEPLOY_LOG=*") {
        $deployLog = $line.Substring("DEPLOY_LOG=".Length)
    } elseif ($line -like "DEPLOY_EXIT=*") {
        $deployExit = $line.Substring("DEPLOY_EXIT=".Length)
    }
}

if ([string]::IsNullOrWhiteSpace($deployLog) -or [string]::IsNullOrWhiteSpace($deployExit)) {
    throw "remote deploy did not return log/exit paths"
}

Assert-RemoteRelativePathSafe -Name "deployLog" -Value $deployLog
Assert-RemoteRelativePathSafe -Name "deployExit" -Value $deployExit

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$lastLineCount = 0
while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $pollScript = @"
set -euo pipefail
cd '$AppDir'
if [ -f '$deployLog' ]; then
  total=`$(wc -l < '$deployLog')
  if [ "`$total" -gt "$lastLineCount" ]; then
    tail -n +"$($lastLineCount + 1)" '$deployLog'
  fi
  echo "__DEPLOY_LINE_COUNT__=`$total"
fi
if [ -f '$deployExit' ]; then
  echo "__DEPLOY_EXIT__=`$(cat '$deployExit')"
fi
"@

    $pollOutput = @($pollScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s")
    if ($LASTEXITCODE -ne 0) {
        throw "failed to poll remote deploy with exit code $LASTEXITCODE"
    }

    $exitCode = $null
    foreach ($line in $pollOutput) {
        if ($line -like "__DEPLOY_LINE_COUNT__=*") {
            $lastLineCount = [int]$line.Substring("__DEPLOY_LINE_COUNT__=".Length)
        } elseif ($line -like "__DEPLOY_EXIT__=*") {
            $exitCode = [int]$line.Substring("__DEPLOY_EXIT__=".Length)
        } else {
            Write-Host $line
        }
    }

    if ($null -ne $exitCode) {
        if ($exitCode -ne 0) {
            throw "remote deploy failed with exit code $exitCode; log=$deployLog"
        }
        Write-Host "[deploy-ssh] OK: remote deploy completed; log=$deployLog"
        return
    }

    Start-Sleep -Seconds $PollSeconds
}

throw "remote deploy did not finish within $TimeoutSeconds seconds; log=$deployLog exit=$deployExit"
