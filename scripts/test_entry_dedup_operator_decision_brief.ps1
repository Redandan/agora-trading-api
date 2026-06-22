Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsBeforeSsh {
    param([string[]]$Arguments, [string]$ExpectedPattern)

    $script = Join-Path $PSScriptRoot "prepare_entry_dedup_operator_decision_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for EntryDedup decision brief test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "EntryDedup decision brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "EntryDedup decision brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "EntryDedup decision brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_operator_decision_brief_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-operator-decision-brief] read-only brief",
        "scope=READ_ONLY",
        "prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1",
        "ENTRY_DEDUP_OPERATOR_DECISION_BRIEF",
        "entry_dedup_operator_primary_recommendation",
        "entry_dedup_operator_decision_lanes",
        "entry_dedup_operator_decision_checklist",
        "decisionLanes",
        "decisionChecklist",
        "linkedActionProposalIds",
        "entry-dedup-semantics-shadow-operator-review",
        "entry-dedup-shadow-experiment",
        "entry-filter-datafreshness-policy",
        "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_EXPERIMENT",
        "NOT_APPROVED_BY_ENTRY_DEDUP_SHADOW_BRIEF",
        "SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW_NOT_LIVE",
        "POLICY_BLOCKED_OUTSIDE_ENTRY_DEDUP_SHADOW_REVIEW",
        "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE",
        "PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW",
        "entry_dedup_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "ExpectedValueGate pass-like evidence",
        "EventRiskControl clear or separately approved",
        "duplicate-hash and same-candidate replay protection",
        "daily cap and max-loss budget evidence",
        "OCO feasibility with exact route and lower-timeframe or exchange-side proof",
        "entry_dedup_operator_decision_brief_packet",
        "entry_dedup_operator_decision_brief_status",
        "notAuthorization=read-only EntryDedup operator decision brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireDecisionReady"
    )) {
    Assert-Contains -Name "EntryDedup decision brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "EntryDedup decision brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_entry_dedup_operator_decision_brief_ssh.ps1",
        "EntryDedup operator decision brief",
        "entry_dedup_operator_decision_brief_status",
        "entry_dedup_operator_decision_checklist",
        "READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE",
        "PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW",
        "entry-dedup-semantics-shadow-operator-review",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention EntryDedup decision brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewNotionalCapUsdt", "0") `
    -ExpectedPattern "ReviewNotionalCapUsdt must be between 1 and 100"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-StrategyId", "0") `
    -ExpectedPattern "StrategyId must be between 1 and 1000000"

Write-Host "[entry-dedup-operator-decision-brief-test] OK"
