Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$expectedWrappers = @(
    "deploy_ssh.ps1",
    "verify_server_ssh.ps1",
    "verify_split_acceptance_ssh.ps1",
    "smoke_mcp_parity_ssh.ps1",
    "smoke_guardrail_acceptance_ssh.ps1",
    "smoke_signal_correctness_ssh.ps1",
    "smoke_trailing_stop_pnl_replay_ssh.ps1",
    "audit_live_readiness_ssh.ps1",
    "smoke_live_background_automation_ssh.ps1",
    "smoke_runtime_evidence_rca_ssh.ps1",
    "smoke_tiny_live_loss_rca_ssh.ps1",
    "smoke_live_deployment_metadata_ssh.ps1",
    "smoke_live_readiness_bundle_ssh.ps1"
)

$missing = [System.Collections.Generic.List[string]]::new()
$root = $PSScriptRoot

foreach ($scriptName in $expectedWrappers) {
    $path = Join-Path $root $scriptName
    if (-not (Test-Path -LiteralPath $path)) {
        $missing.Add($scriptName)
        continue
    }

    $text = Get-Content -Raw -LiteralPath $path
    if ($text -notmatch "bash -s") {
        throw "$scriptName no longer contains a remote bash -s invocation; update the BOM guard test."
    }

    $lines = Get-Content -LiteralPath $path
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match "bash -s" -and $line -notmatch "sed '1s/\^\\xEF\\xBB\\xBF//'") {
            throw "$scriptName line $($i + 1) invokes bash -s without stripping UTF-8 BOM first."
        }
    }
}

if ($missing.Count -gt 0) {
    throw "Missing expected SSH wrapper(s): $($missing -join ', ')"
}

Write-Host "[ssh-wrapper-bom-guard-test] OK"
