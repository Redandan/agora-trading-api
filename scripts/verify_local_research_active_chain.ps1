[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$python = Get-Command python -ErrorAction Stop

$activeTasks = @(
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3-evidence-diagnostic.v1.json"
        task_id = "local-node-microstructure-v3-evidence-diagnostic-v1"
        sha256 = "d50e41e5fe98e76c1ff9930baeb89ba357040dd70b2cfdd51656edbc8c03ad86"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3-handoff-transfer.v3.json"
        task_id = "local-node-microstructure-v3-handoff-transfer-v3"
        sha256 = "1147fa58e09eb74e4ed58a2c88c9a3c5bc58a76e30083635f2fdd94a9b30a2a2"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3-interpretation-runner.v2.json"
        task_id = "local-node-microstructure-v3-interpretation-runner-v2"
        sha256 = "0607f48c3542dbbb2f662f401998904c483f6d60e453c7ba6fea9a9eebf9155f"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3-hypothesis-design-runner.v3.json"
        task_id = "local-node-microstructure-v3-hypothesis-design-runner-v3"
        sha256 = "0dee2f121f549b2cecddc0342c07ff7ed368791362af9db665e0811cf3fe2725"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-positive-route-design-runner.v4.json"
        task_id = "local-node-microstructure-positive-route-design-runner-v4"
        sha256 = "b9ec0f752b412473942b4f1dae06cae61770087d4d5f131c6ccded5291c9779b"
    }
)

$diagnosticDispatch = [ordered]@{
    path = "research_pipeline/examples/local-research-dispatch.microstructure-v3-evidence-diagnostic.v1.json"
    dispatch_id = "manager-microstructure-v3-evidence-diagnostic-v1"
    sha256 = "faf705ca7ca4c61b183d2b5533c80c344ae551bed4197de7fd7a6327a8e32a5f"
    task_path = $activeTasks[0].path
}

function Invoke-JsonValidation {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $output = & $python.Source @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed: $($output -join [Environment]::NewLine)"
    }
    try {
        return (($output -join [Environment]::NewLine) | ConvertFrom-Json -Depth 100)
    }
    catch {
        throw "$Description returned non-JSON output: $($output -join [Environment]::NewLine)"
    }
}

Push-Location -LiteralPath $repoRoot
try {
    $validatedTasks = @()
    foreach ($expected in $activeTasks) {
        $validated = Invoke-JsonValidation -Description $expected.task_id -Arguments @(
            "-m",
            "research_pipeline",
            "validate-local-research-task",
            $expected.path
        )
        if (
            $validated.status -ne "VALID" -or
            $validated.task_id -ne $expected.task_id -or
            $validated.task_sha256 -ne $expected.sha256
        ) {
            throw "Active task identity drift: $($expected.task_id)"
        }
        $validatedTasks += [ordered]@{
            task_id = $validated.task_id
            task_sha256 = $validated.task_sha256
            status = $validated.status
        }
    }

    $validatedDispatch = Invoke-JsonValidation -Description $diagnosticDispatch.dispatch_id -Arguments @(
        "-m",
        "research_pipeline",
        "validate-local-research-dispatch",
        $diagnosticDispatch.path,
        "--task",
        $diagnosticDispatch.task_path
    )
    if (
        $validatedDispatch.status -ne "VALID" -or
        $validatedDispatch.dispatch_id -ne $diagnosticDispatch.dispatch_id -or
        $validatedDispatch.dispatch_sha256 -ne $diagnosticDispatch.sha256 -or
        $validatedDispatch.task_id -ne $activeTasks[0].task_id -or
        $validatedDispatch.task_sha256 -ne $activeTasks[0].sha256
    ) {
        throw "Active diagnostic dispatch identity drift"
    }

    $bindingCode = @'
import json
from research_pipeline.microstructure_handoff_runner import DIAGNOSTIC_TASK_ID, DIAGNOSTIC_TASK_SHA256
from research_pipeline.microstructure_handoff_receive import TRANSFER_TASK_ID, TRANSFER_TASK_SHA256
from research_pipeline.microstructure_interpretation_runner import RUNNER_TASK_ID as INTERPRETATION_TASK_ID, RUNNER_TASK_SHA256 as INTERPRETATION_TASK_SHA256
from research_pipeline.microstructure_hypothesis_design_runner import RUNNER_TASK_ID as HYPOTHESIS_TASK_ID, RUNNER_TASK_SHA256 as HYPOTHESIS_TASK_SHA256
from research_pipeline.microstructure_positive_route_hypothesis_design_runner import RUNNER_TASK_ID as POSITIVE_TASK_ID, RUNNER_TASK_SHA256 as POSITIVE_TASK_SHA256
print(json.dumps([
    {"task_id": DIAGNOSTIC_TASK_ID, "task_sha256": DIAGNOSTIC_TASK_SHA256},
    {"task_id": TRANSFER_TASK_ID, "task_sha256": TRANSFER_TASK_SHA256},
    {"task_id": INTERPRETATION_TASK_ID, "task_sha256": INTERPRETATION_TASK_SHA256},
    {"task_id": HYPOTHESIS_TASK_ID, "task_sha256": HYPOTHESIS_TASK_SHA256},
    {"task_id": POSITIVE_TASK_ID, "task_sha256": POSITIVE_TASK_SHA256},
], separators=(",", ":"), sort_keys=True))
'@
    $bindingOutput = & $python.Source -c $bindingCode 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Active runner binding import failed: $($bindingOutput -join [Environment]::NewLine)"
    }
    try {
        $runnerBindings = @(($bindingOutput -join [Environment]::NewLine) | ConvertFrom-Json -Depth 100)
    }
    catch {
        throw "Active runner binding import returned non-JSON output: $($bindingOutput -join [Environment]::NewLine)"
    }
    if ($runnerBindings.Count -ne $activeTasks.Count) {
        throw "Active runner binding count drift"
    }
    for ($index = 0; $index -lt $activeTasks.Count; $index++) {
        if (
            $runnerBindings[$index].task_id -ne $activeTasks[$index].task_id -or
            $runnerBindings[$index].task_sha256 -ne $activeTasks[$index].sha256
        ) {
            throw "Active runner binding drift: $($activeTasks[$index].task_id)"
        }
    }

    [ordered]@{
        schema_version = "1"
        status = "VALID"
        classification = "ACTIVE_EXECUTION_CHAIN_ONLY"
        historical_task_snapshots_scanned = $false
        state_authority = "SERVER_CANONICAL"
        timer_authority = "CODEX_CLOUD_OPS_ONLY"
        active_task_count = $validatedTasks.Count
        active_tasks = $validatedTasks
        diagnostic_dispatch = [ordered]@{
            dispatch_id = $validatedDispatch.dispatch_id
            dispatch_sha256 = $validatedDispatch.dispatch_sha256
            task_id = $validatedDispatch.task_id
            task_sha256 = $validatedDispatch.task_sha256
            status = $validatedDispatch.status
        }
        active_runner_binding_count = $runnerBindings.Count
        active_runner_bindings = "VALID"
        focused_tests = "SEPARATE_RELEASE_GATE_NOT_RUN"
        server_or_canonical_write = $false
        schedule_change = $false
        trading_action = $false
        oos_access = $false
        paid_api_use = $false
    } | ConvertTo-Json -Depth 10 -Compress
}
finally {
    Pop-Location
}
