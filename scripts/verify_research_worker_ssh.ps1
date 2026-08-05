param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [ValidateSet("disabled", "active")]
    [string]$ExpectTimer = "active",
    [switch]$RunHeartbeat,
    [switch]$RunSourceProbe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
    throw "A safe SshHost is required."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}

$localVerifier = Join-Path $PSScriptRoot "research-worker\verify-worker.sh"
if (-not (Test-Path -LiteralPath $localVerifier -PathType Leaf)) {
    throw "Worker verifier is missing: $localVerifier"
}
$body = Get-Content -Raw -LiteralPath $localVerifier
$runFlag = if ($RunHeartbeat) { "1" } else { "0" }
$probeFlag = if ($RunSourceProbe) { "1" } else { "0" }
$remote = "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | EXPECT_TIMER='$ExpectTimer' RUN_HEARTBEAT='$runFlag' RUN_SOURCE_PROBE='$probeFlag' bash -s"
$body | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost $remote
if ($LASTEXITCODE -ne 0) {
    throw "Research Worker verification failed with exit code $LASTEXITCODE"
}
