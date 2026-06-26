Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-ScriptFailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern,
        [string]$Description
    )

    $scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_gate_preflight_ssh.ps1"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "$Description unexpectedly succeeded"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "$Description did not fail with expected pattern '$ExpectedPattern'. Output: $text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_gate_preflight_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath
$verifyLocalText = Get-Content -Raw -LiteralPath $verifyLocalPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs and server-local read-only MCP calls only",
        "getEventRiskControlStatus",
        "getExpectedValueGateStats",
        "bt_decision_audit",
        "bt_live_signal",
        "ExpectedValueGate",
        "EventRiskControl",
        "DuplicateBar",
        "daily learning cap",
        "max loss",
        "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET",
        "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE",
        "PARTIAL_RUNTIME_PASS_CANDIDATE_SNAPSHOT_MISSING",
        "CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW",
        "BLOCKED_CANDIDATE_CAP_OR_LOSS_ROWS_PRESENT",
        "PARTIAL_GLOBAL_CAP_OR_LOSS_ROWS_NOT_CANDIDATE_BLOCKER",
        "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED",
        "BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY",
        "MISSING_CANDIDATE_RUNTIME_EVIDENCE_SNAPSHOTS",
        "PARTIAL_RUNTIME_EVIDENCE_CANDIDATE_COVERAGE",
        "CLEARED_CANDIDATE_RUNTIME_EVIDENCE_COVERAGE",
        "bt_runtime_decision_evidence",
        "JSON_EXTRACT",
        "$.entryPlan.status",
        "$.ocoPlan.status",
        "runtime_evidence_coverage_status",
        "candidate_runtime_ev_evaluated_rows",
        "candidate_runtime_oco_plan_rows",
        "entry_dedup_semantics_gate_preflight_status",
        "order_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "EntryDedup semantics gate preflight smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention EntryDedup gate preflight smoke" -Text $doc -Pattern "smoke_entry_dedup_semantics_gate_preflight_ssh\.ps1"
    Assert-Contains -Name "operator docs mention EntryDedup gate preflight read-only" -Text $doc -Pattern "read-only"
}

Assert-Contains `
    -Name "verify_local includes EntryDedup gate preflight smoke test" `
    -Text $verifyLocalText `
    -Pattern "test_entry_dedup_semantics_gate_preflight_smoke\.ps1"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "EntryDedup gate preflight SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-McpDays", "0") `
    -ExpectedPattern "McpDays must be between 1 and 90" `
    -Description "EntryDedup gate preflight MCP days input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "300") `
    -ExpectedPattern "Limit must be between 1 and 200" `
    -Description "EntryDedup gate preflight limit input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "EntryDedup gate preflight interval input guard"

Write-Host "[entry-dedup-semantics-gate-preflight-smoke-test] OK"
