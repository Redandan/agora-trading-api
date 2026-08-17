[CmdletBinding()]
param(
    [switch]$Execute,
    [string]$ExpectedCanonicalResearchStatus = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$env:PYTHONDONTWRITEBYTECODE = "1"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$python = Get-Command python -ErrorAction Stop
$git = Get-Command git -ErrorAction Stop
$windowsPowerShell = "C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe"
$powerShell7 = "C:\Program Files\PowerShell\7\pwsh.exe"
$activeGate = Join-Path $PSScriptRoot "verify_local_research_active_chain.ps1"
$pullScript = Join-Path $PSScriptRoot "pull_microstructure_v3r1_handoff_ssh.ps1"

function Invoke-JsonProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $output = & $FilePath @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed: $($output -join [Environment]::NewLine)"
    }
    $text = ($output -join [Environment]::NewLine).Trim()
    try {
        $value = $text | ConvertFrom-Json
    }
    catch {
        throw "$Label returned non-JSON output: $text"
    }
    return [pscustomobject]@{
        Text = $text
        Value = $value
    }
}

function Invoke-GitText {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $output = & $git.Source @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed: $($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
    throw "Windows PowerShell 5.1 is required."
}
if (-not (Test-Path -LiteralPath $powerShell7 -PathType Leaf)) {
    throw "PowerShell 7 is required."
}

Push-Location -LiteralPath $repoRoot
try {
    $windowsGate = Invoke-JsonProcess `
        -FilePath $windowsPowerShell `
        -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $activeGate) `
        -Label "Windows PowerShell V3R1 active-chain gate"
    $powerShell7Gate = Invoke-JsonProcess `
        -FilePath $powerShell7 `
        -Arguments @("-NoProfile", "-File", $activeGate) `
        -Label "PowerShell 7 V3R1 active-chain gate"
    if ($windowsGate.Text -cne $powerShell7Gate.Text) {
        throw "The dual-shell V3R1 active-chain outputs are not byte-equivalent."
    }
    if (
        $windowsGate.Value.status -ne "VALID" -or
        $windowsGate.Value.classification -ne "V3R1_ACTIVE_TERMINAL_EXECUTION_CHAIN_ONLY" -or
        $windowsGate.Value.timer_authority -ne "CODEX_CLOUD_OPS_ONLY" -or
        $windowsGate.Value.state_authority -ne "SERVER_CANONICAL"
    ) {
        throw "The V3R1 active-chain gate did not prove the frozen authority boundary."
    }

    $head = Invoke-GitText -Arguments @("rev-parse", "HEAD") -Label "Git HEAD lookup"
    $upstream = Invoke-GitText -Arguments @("rev-parse", "@{upstream}") -Label "Git upstream lookup"
    $dirtyText = Invoke-GitText `
        -Arguments @("status", "--porcelain=v1", "--untracked-files=normal") `
        -Label "Git worktree lookup"
    $worktreeClean = [string]::IsNullOrEmpty($dirtyText)
    $sourceSynchronized = $head -ceq $upstream

    if (-not $Execute) {
        [ordered]@{
            schema_version = "1"
            status = "VALID"
            classification = "MANAGER_OPERATED_LOCAL_DETERMINISTIC_EXECUTOR_READY"
            execution_mode = "VALIDATE_ONLY"
            execution_authority = "MANAGER_OPERATED_LOCAL_DETERMINISTIC_ADAPTER"
            state_authority = "SERVER_CANONICAL"
            timer_authority = "CODEX_CLOUD_OPS_ONLY"
            source_git_commit = $head
            source_matches_upstream = $sourceSynchronized
            worktree_clean = $worktreeClean
            dual_shell_active_chain = "BYTE_EQUIVALENT_VALID"
            python_executable = $python.Source
            server_export_invoked = $false
            local_artifact_written = $false
            canonical_state_write = $false
            second_timer_or_writer = $false
            trading_action = $false
            oos_access = $false
            paid_api_use = $false
        } | ConvertTo-Json -Depth 6 -Compress
        exit 0
    }

    if ($ExpectedCanonicalResearchStatus -cne "DIAGNOSTIC_READY") {
        throw "Execution requires an exact fresh DIAGNOSTIC_READY Manager observation."
    }
    if (-not $worktreeClean -or -not $sourceSynchronized) {
        throw "Execution requires a clean HEAD equal to its configured upstream."
    }

    $receiver = Invoke-JsonProcess `
        -FilePath $windowsPowerShell `
        -Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $pullScript) `
        -Label "V3R1 create-once handoff pull"
    if ($receiver.Value.status -notin @("RECEIVED", "IDEMPOTENT_IDENTICAL")) {
        throw "The V3R1 receiver returned an unexpected status."
    }

    $diagnostic = Invoke-JsonProcess `
        -FilePath $python.Source `
        -Arguments @("-B", "-m", "research_pipeline.microstructure_handoff_runner_v3r1") `
        -Label "V3R1 deterministic diagnostic"
    if ($diagnostic.Value.status -notin @("CREATED", "IDEMPOTENT_IDENTICAL")) {
        throw "The V3R1 diagnostic returned an unexpected status."
    }

    $interpretation = Invoke-JsonProcess `
        -FilePath $python.Source `
        -Arguments @("-B", "-m", "research_pipeline.microstructure_interpretation_runner_v3r1") `
        -Label "V3R1 deterministic interpretation"
    if ($interpretation.Value.status -notin @("CREATED", "IDEMPOTENT_IDENTICAL")) {
        throw "The V3R1 interpretation returned an unexpected status."
    }

    [ordered]@{
        schema_version = "1"
        status = "COMPLETED"
        classification = "MANAGER_OPERATED_LOCAL_V3R1_TERMINAL_CHAIN_COMPLETED"
        execution_mode = "EXECUTE_ONCE"
        execution_authority = "MANAGER_OPERATED_LOCAL_DETERMINISTIC_ADAPTER"
        expected_canonical_research_status = $ExpectedCanonicalResearchStatus
        state_authority = "SERVER_CANONICAL"
        timer_authority = "CODEX_CLOUD_OPS_ONLY"
        source_git_commit = $head
        dual_shell_active_chain = "BYTE_EQUIVALENT_VALID"
        receiver = $receiver.Value
        diagnostic = $diagnostic.Value
        interpretation = $interpretation.Value
        server_export_invoked = $true
        local_artifact_written = $true
        canonical_state_write = $false
        second_timer_or_writer = $false
        trading_action = $false
        oos_access = $false
        paid_api_use = $false
    } | ConvertTo-Json -Depth 10 -Compress
}
finally {
    Pop-Location
}
