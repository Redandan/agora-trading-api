param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$ExpectedControlReleaseId = $env:AGORA_RESEARCH_EXPECTED_CONTROL_RELEASE_ID,
    [string]$ExpectedDataReleaseId = $env:AGORA_RESEARCH_EXPECTED_DATA_RELEASE_ID,
    [string]$ExpectMicrostructureSource = $env:AGORA_RESEARCH_EXPECT_MICROSTRUCTURE_SOURCE,
    [string]$ExpectCarrySource = $env:AGORA_RESEARCH_EXPECT_CARRY_SOURCE,
    [ValidateSet("disabled", "active")]
    [string]$ExpectTimer = "disabled",
    [switch]$RunHeartbeat,
    [switch]$RunSourceProbe
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($ExpectCarrySource -notin @("absent", "inactive")) {
    throw "ExpectCarrySource is required and must be exactly absent or inactive."
}
if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
    throw "A safe SshHost is required."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}
if ([string]::IsNullOrWhiteSpace($ExpectedControlReleaseId) -or $ExpectedControlReleaseId -notmatch "^[A-Za-z0-9._-]+$") {
    throw "ExpectedControlReleaseId is required and must use only A-Z, a-z, 0-9, dot, underscore, or hyphen."
}
if ([string]::IsNullOrWhiteSpace($ExpectedDataReleaseId) -or $ExpectedDataReleaseId -notmatch "^[A-Za-z0-9._-]+$") {
    throw "ExpectedDataReleaseId is required and must use only A-Z, a-z, 0-9, dot, underscore, or hyphen."
}
if ($ExpectMicrostructureSource -notin @("disabled", "active")) {
    throw "ExpectMicrostructureSource is required and must be exactly disabled or active."
}

$localVerifier = Join-Path $PSScriptRoot "research-worker\verify-worker.sh"
if (-not (Test-Path -LiteralPath $localVerifier -PathType Leaf)) {
    throw "Worker verifier is missing: $localVerifier"
}
$body = Get-Content -Raw -LiteralPath $localVerifier
$runFlag = if ($RunHeartbeat) { "1" } else { "0" }
$probeFlag = if ($RunSourceProbe) { "1" } else { "0" }
$intakePreflightFlag = if ($ExpectMicrostructureSource -eq "active") { "1" } else { "0" }
$remote = "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | EXPECTED_CONTROL_RELEASE_ID='$ExpectedControlReleaseId' EXPECTED_DATA_RELEASE_ID='$ExpectedDataReleaseId' EXPECT_MICROSTRUCTURE_SOURCE='$ExpectMicrostructureSource' EXPECT_CARRY_SOURCE='$ExpectCarrySource' MICROSTRUCTURE_INTAKE_PREFLIGHT='$intakePreflightFlag' EXPECT_TIMER='$ExpectTimer' RUN_HEARTBEAT='$runFlag' RUN_SOURCE_PROBE='$probeFlag' bash -s"
$body | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost $remote
if ($LASTEXITCODE -ne 0) {
    throw "Research Worker verification failed with exit code $LASTEXITCODE"
}
