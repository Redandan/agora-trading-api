[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$SshHost = $env:AGORA_SSH_HOST
$SshKey = $env:AGORA_SSH_KEY
$taskId = "local-node-microstructure-v3r1-evidence-diagnostic-v1"
$remoteExportRoot = "/var/lib/agora-research/microstructure-v3r1-handoff-export"
$localNodeRoot = "C:\Users\Redan\.codex\local-research-node"
$transportParent = Join-Path $localNodeRoot "transport"
$stagingRoot = Join-Path (Join-Path $localNodeRoot "staging") $taskId
$finalRoot = Join-Path (Join-Path $localNodeRoot "inbox") $taskId
$archivePath = Join-Path $transportParent "$taskId.tar"
$remoteCommand = @(
    "sudo -n systemctl start agora-research-microstructure-handoff-export.service",
    "&&",
    "sudo -n tar --format=ustar --numeric-owner",
    "-C '$remoteExportRoot'",
    "-cf - '$taskId'"
) -join " "
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

function Assert-RegularNonLinkFile {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Leaf)) {
        throw "$Label must be an existing regular file."
    }
    $item = Get-Item -LiteralPath $LiteralPath -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label must not be a link or reparse point."
    }
}

function Assert-RegularNonLinkDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Container)) {
        throw "$Label must be a pre-provisioned directory."
    }
    $item = Get-Item -LiteralPath $LiteralPath -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Label must not be a link or reparse point."
    }
}

function Invoke-FixedReceiver {
    Push-Location -LiteralPath $repositoryRoot
    try {
        & python -m research_pipeline.microstructure_handoff_receive_v3r1
        if ($LASTEXITCODE -ne 0) {
            throw "Local V3R1 handoff receiver failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

if (
    [string]::IsNullOrWhiteSpace($SshHost) -or
    $SshHost.StartsWith("-") -or
    $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$"
) {
    throw "A safe AGORA_SSH_HOST is required."
}
Assert-RegularNonLinkFile -LiteralPath $SshKey -Label "AGORA_SSH_KEY"
Assert-RegularNonLinkDirectory -LiteralPath $transportParent -Label "Transport parent"
Assert-RegularNonLinkDirectory `
    -LiteralPath (Split-Path -Parent $stagingRoot) `
    -Label "Staging parent"
Assert-RegularNonLinkDirectory `
    -LiteralPath (Split-Path -Parent $finalRoot) `
    -Label "Inbox parent"

if (Test-Path -LiteralPath $stagingRoot) {
    throw "The fixed V3R1 staging root already exists; preserve it for fail-closed review."
}
if ((Test-Path -LiteralPath $finalRoot) -or (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    Invoke-FixedReceiver
    exit 0
}
if (Test-Path -LiteralPath $archivePath) {
    throw "The fixed V3R1 archive path exists with the wrong filesystem type."
}

$partialName = ".$taskId.partial." + [System.Guid]::NewGuid().ToString("N")
$partialPath = Join-Path $transportParent $partialName
$partialOwned = $false
$partialStream = $null
try {
    $partialStream = [System.IO.File]::Open(
        $partialPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    $partialOwned = $true

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "ssh"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    [void]$startInfo.ArgumentList.Add("-i")
    [void]$startInfo.ArgumentList.Add([System.IO.Path]::GetFullPath($SshKey))
    [void]$startInfo.ArgumentList.Add("-o")
    [void]$startInfo.ArgumentList.Add("BatchMode=yes")
    [void]$startInfo.ArgumentList.Add("-o")
    [void]$startInfo.ArgumentList.Add("ConnectTimeout=10")
    [void]$startInfo.ArgumentList.Add($SshHost)
    [void]$startInfo.ArgumentList.Add($remoteCommand)

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "SSH V3R1 handoff process did not start."
    }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.StandardOutput.BaseStream.CopyTo($partialStream)
    $partialStream.Flush($true)
    $partialLength = $partialStream.Length
    $partialStream.Dispose()
    $partialStream = $null
    $process.WaitForExit()
    $stderrText = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "SSH V3R1 handoff pull failed with exit code $($process.ExitCode): $stderrText"
    }
    if ($partialLength -le 0) {
        throw "SSH V3R1 handoff pull returned an empty archive."
    }
    [System.IO.File]::Move($partialPath, $archivePath)
    $partialOwned = $false
    Invoke-FixedReceiver
}
finally {
    if ($null -ne $partialStream) {
        $partialStream.Dispose()
    }
    if ($partialOwned -and (Test-Path -LiteralPath $partialPath -PathType Leaf)) {
        Remove-Item -LiteralPath $partialPath -Force
    }
}
