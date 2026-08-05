param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [switch]$All
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
    throw "A safe SshHost is required."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}

$includeAll = if ($All) { "1" } else { "0" }
$remote = @"
set -euo pipefail
sudo -n python3 - '$includeAll' <<'PY'
import glob
import json
import os
import sys

include_all = sys.argv[1] == "1"
records = []
for path in sorted(glob.glob("/var/lib/agora-research/inbox/heartbeat-*.json")):
    with open(path, encoding="utf-8") as stream:
        payload = json.load(stream)
    if include_all or payload.get("should_notify_coach") or payload.get("status") != "HEARTBEAT_OK":
        records.append({"file": os.path.basename(path), "payload": payload})
print(json.dumps({"records": records}, ensure_ascii=False, indent=2))
PY
"@
$remote | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Research Worker inbox read failed with exit code $LASTEXITCODE"
}
