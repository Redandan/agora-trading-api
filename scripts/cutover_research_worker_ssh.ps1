param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$StateDir = (Join-Path $PSScriptRoot "..\.research-state"),
    [switch]$OldCodexHeartbeatDisabled
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $OldCodexHeartbeatDisabled) {
    throw "Cutover requires confirmed disablement of the old Codex heartbeat."
}
if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
    throw "A safe SshHost is required."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}

$resolvedState = [System.IO.Path]::GetFullPath($StateDir)
if (-not (Test-Path -LiteralPath $resolvedState -PathType Container)) {
    throw "StateDir does not exist: $resolvedState"
}

$authority = [ordered]@{
    schema_version = "1"
    mode = "REMOTE_READ_ONLY_REPLICA"
    canonical_state = "ssh://$SshHost/var/lib/agora-research/state"
}
$authorityPath = Join-Path $resolvedState "authority.json"
$temporary = "$authorityPath.$PID.tmp"
$json = $authority | ConvertTo-Json
[System.IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::Move($temporary, $authorityPath, $true)

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
Push-Location $repoRoot
try {
    $output = & python -m research_pipeline heartbeat 2>&1
    $localExit = $LASTEXITCODE
}
finally {
    Pop-Location
}
if ($localExit -ne 2 -or ($output -join "`n") -notmatch "read-only replica") {
    throw "Local replica write guard did not reject the heartbeat as expected."
}
Write-Host "LOCAL_REPLICA_GUARD=OK"

$remoteCutover = @'
set -euo pipefail
sudo systemctl start agora-research-heartbeat.service
sudo python3 - /var/lib/agora-research/inbox/latest.json <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
if value.get("status") != "HEARTBEAT_OK":
    raise SystemExit(f"manual heartbeat failed: {value.get('status')}")
print(f"HEARTBEAT_STATUS={value.get('status')}")
print(f"RESEARCH_STATUS={value.get('research_status')}")
print(f"NEXT_DUE={value.get('next_due')}")
PY
sudo systemctl enable --now agora-research-heartbeat.timer
systemctl is-enabled --quiet agora-research-heartbeat.timer
systemctl is-active --quiet agora-research-heartbeat.timer
systemctl show agora-research-heartbeat.timer --property=NextElapseUSecRealtime
'@
$remoteCutover | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Remote Research Worker cutover failed with exit code $LASTEXITCODE"
}
