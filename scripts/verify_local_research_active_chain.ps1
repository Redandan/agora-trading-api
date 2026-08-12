[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$python = Get-Command python -ErrorAction Stop

$activeTasks = @(
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json"
        task_id = "local-node-microstructure-v3r1-evidence-diagnostic-v1"
        sha256 = "7c18f791996ddd1b55ba43ee0a2e194284574155d4b4e536e857e56a83a8596b"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3r1-handoff-transfer.v1.json"
        task_id = "local-node-microstructure-v3r1-handoff-transfer-v1"
        sha256 = "81affa9f98b436820209d15eed334663c441efd64de73a65abd5caa2975ed2b0"
    },
    [ordered]@{
        path = "research_pipeline/examples/local-research-task.microstructure-v3r1-interpretation-runner.v1.json"
        task_id = "local-node-microstructure-v3r1-interpretation-runner-v1"
        sha256 = "bf022e30e429c3859c80aead880fc71f6e84a3c3598421ddf3aa289127334a77"
    }
)

$diagnosticDispatch = [ordered]@{
    path = "research_pipeline/examples/local-research-dispatch.microstructure-v3r1-evidence-diagnostic.v1.json"
    dispatch_id = "manager-microstructure-v3r1-evidence-diagnostic-v1"
    sha256 = "d7ae36ffdce69f169cc88a12340ed9fbbcc62e663a5cb0a0f81070fbec0a58f0"
    task_path = $activeTasks[0].path
}

$expectedAuthority = [ordered]@{
    exporter_task_path = "/etc/agora-research/local-tasks/microstructure-v3r1-evidence-diagnostic.v1.json"
    exporter_final_root = "/var/lib/agora-research/microstructure-v3r1-handoff-export"
    manifest_type = "MICROSTRUCTURE_DISCOVERY_V3R1_CREATE_ONLY_HANDOFF_MANIFEST"
    manifest_schema_sha256 = "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617"
    diagnostic_task_id = $activeTasks[0].task_id
    diagnostic_task_sha256 = $activeTasks[0].sha256
    transfer_task_id = $activeTasks[1].task_id
    transfer_task_sha256 = $activeTasks[1].sha256
    interpretation_task_id = $activeTasks[2].task_id
    interpretation_task_sha256 = $activeTasks[2].sha256
    pull_script_sha256 = "42e0e60a7510c39186d675757662e75290919bdb86066e3b510062f2b8a22f63"
}

function Invoke-JsonValidation {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $output = & $python.Source @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed: $($output -join [Environment]::NewLine)"
    }
    try {
        return (($output -join [Environment]::NewLine) | ConvertFrom-Json)
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
            "-m", "research_pipeline", "validate-local-research-task", $expected.path
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
        "-m", "research_pipeline", "validate-local-research-dispatch",
        $diagnosticDispatch.path, "--task", $diagnosticDispatch.task_path
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
import hashlib
import json
from pathlib import Path
from research_pipeline.microstructure_handoff_export import (
    EXPORT_FINAL_ROOT,
    HANDOFF_SCHEMA_SHA256 as EXPORT_SCHEMA_SHA256,
    LOCAL_DIAGNOSTIC_TASK,
    MANIFEST_TYPE as EXPORT_MANIFEST_TYPE,
)
from research_pipeline.microstructure_handoff_v3r1 import (
    MANIFEST_SCHEMA_SHA256 as LOCAL_SCHEMA_SHA256,
    MANIFEST_TYPE as LOCAL_MANIFEST_TYPE,
)
from research_pipeline.microstructure_handoff_runner_v3r1 import (
    DIAGNOSTIC_TASK_ID,
    DIAGNOSTIC_TASK_SHA256,
    PRODUCTION_PATHS as DIAGNOSTIC_PATHS,
    _validate_fixed_task,
)
from research_pipeline.microstructure_handoff_receive_v3r1 import (
    DIAGNOSTIC_TASK_ID as RECEIVE_DIAGNOSTIC_TASK_ID,
    PRODUCTION_PATHS as RECEIVE_PATHS,
    TRANSFER_TASK_ID,
    TRANSFER_TASK_SHA256,
    _validate_transfer_task,
)
from research_pipeline.microstructure_interpretation_runner_v3r1 import (
    DIAGNOSTIC_TASK_ID as INTERPRETATION_DIAGNOSTIC_TASK_ID,
    PRODUCTION_PATHS as INTERPRETATION_PATHS,
    RUNNER_TASK_ID as INTERPRETATION_TASK_ID,
    RUNNER_TASK_SHA256 as INTERPRETATION_TASK_SHA256,
    _validate_runner_task,
)

_validate_fixed_task(DIAGNOSTIC_PATHS)
_validate_transfer_task(RECEIVE_PATHS)
_validate_runner_task(INTERPRETATION_PATHS)
if EXPORT_MANIFEST_TYPE != LOCAL_MANIFEST_TYPE or EXPORT_SCHEMA_SHA256 != LOCAL_SCHEMA_SHA256:
    raise ValueError("exporter authority drift")
if not (DIAGNOSTIC_TASK_ID == RECEIVE_DIAGNOSTIC_TASK_ID == INTERPRETATION_DIAGNOSTIC_TASK_ID):
    raise ValueError("Local execution authority drift")
pull = Path("scripts/pull_microstructure_v3r1_handoff_ssh.ps1")
value = {
    "exporter_task_path": LOCAL_DIAGNOSTIC_TASK.as_posix(),
    "exporter_final_root": EXPORT_FINAL_ROOT.as_posix(),
    "manifest_type": EXPORT_MANIFEST_TYPE,
    "manifest_schema_sha256": EXPORT_SCHEMA_SHA256,
    "diagnostic_task_id": DIAGNOSTIC_TASK_ID,
    "diagnostic_task_sha256": DIAGNOSTIC_TASK_SHA256,
    "transfer_task_id": TRANSFER_TASK_ID,
    "transfer_task_sha256": TRANSFER_TASK_SHA256,
    "interpretation_task_id": INTERPRETATION_TASK_ID,
    "interpretation_task_sha256": INTERPRETATION_TASK_SHA256,
    "pull_script_sha256": hashlib.sha256(pull.read_bytes()).hexdigest(),
}
print(json.dumps(value, separators=(",", ":"), sort_keys=True))
'@
    $bindingOutput = $bindingCode | & $python.Source - 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Active Local execution authority validation failed: $($bindingOutput -join [Environment]::NewLine)"
    }
    try {
        $actualAuthority = (($bindingOutput -join [Environment]::NewLine) | ConvertFrom-Json)
    }
    catch {
        throw "Active Local execution authority returned non-JSON output"
    }
    foreach ($name in $expectedAuthority.Keys) {
        if ($actualAuthority.$name -ne $expectedAuthority[$name]) {
            if ($name.StartsWith("exporter_") -or $name.StartsWith("manifest_")) {
                throw "exporter authority drift: $name"
            }
            throw "Local execution authority drift: $name"
        }
    }

    [ordered]@{
        schema_version = "2"
        status = "VALID"
        classification = "V3R1_ACTIVE_TERMINAL_EXECUTION_CHAIN_ONLY"
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
        exporter_to_local_authority = "VALID"
        manifest_type = $actualAuthority.manifest_type
        manifest_schema_sha256 = $actualAuthority.manifest_schema_sha256
        exporter_final_root = $actualAuthority.exporter_final_root
        terminal_stage = $actualAuthority.interpretation_task_id
        positive_only_downstream_active = $false
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
