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

function Assert-ToolCall {
    param(
        [string]$ScriptText,
        [string]$ToolName
    )

    Assert-Contains -Name "signal policy smoke tool calls" -Text $ScriptText -Pattern ([regex]::Escape("call_tool(`"$ToolName`""))
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_signal_correctness_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"
$dryRunPath = Join-Path $repoRoot "docs/live-dry-run-evidence-plan.md"
$proposalPath = Join-Path $repoRoot "docs/live-production-env-review-proposal.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$bundleText = Get-Content -Raw -LiteralPath $bundlePath
$remediationText = Get-Content -Raw -LiteralPath $remediationPath
$dryRunText = Get-Content -Raw -LiteralPath $dryRunPath
$proposalText = Get-Content -Raw -LiteralPath $proposalPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "verifyStrategyExecution",
        "analyzeBlockedSignalOutcomes",
        "getSignalAccuracyReport",
        "diagnoseDataFreshnessGuardBlocks",
        "getSignalCorrectnessDashboard",
        "getGovernanceDriftDashboard",
        "findGovernanceRelaxationCandidates",
        "findGovernanceTighteningCandidates",
        "getEntryDedupGovernanceDashboard",
        "getMissedOpportunityRegressionReport",
        "getNoBuyReasonTruthTable"
    )) {
    Assert-ToolCall -ScriptText $scriptText -ToolName $toolName
}

foreach ($marker in @(
        "DataFreshnessGuard Current Snapshot",
        "staleNowKeys",
        "noDataNowKeys",
        "queryFailedNowKeys",
        "7d Governance Drift",
        "governanceMode",
        "EntryDedup Live-Readiness Cross-Check",
        "Missed Opportunity Regression",
        "No-Buy Row Classification",
        "High-Return No-Buy Breakdown",
        "No-Buy Reason Truth Table",
        "DO NOT RELAX ENTRY DEDUP LIVE",
        "REVIEW ENTRY DEDUP",
        "read-only check complete"
    )) {
    Assert-Contains -Name "signal policy smoke" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "signal policy smoke execution summary" -Text $scriptText -Pattern 'missingEvalOrOrderBug=\{''no'' if execution_ok else ''unknown_or_present''\}'

foreach ($marker in @(
        "no-buy reason truth table read-only boundary",
        "no-buy reason truth table no order send marker",
        "missed opportunity regression no runtime evidence writes marker",
        "EntryDedup governance no runtime evidence writes marker"
    )) {
    Assert-Contains -Name "signal policy smoke read-only hard fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern '\$signal -match "REVIEW_POLICY_GAPS"'
Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern '7d Governance Drift:\\s\*`r\?`n\\s\*governanceMode=\(TOO_STRICT\|TOO_LOOSE\|INSUFFICIENT_DATA\)'
Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern 'overallStatus=\(FAIL\|WARN\)'
Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern '7d Governance Drift:\\s\*`r\?`n\\s\*governanceMode='
Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern 'Missed Opportunity Regression:\\s\*`r\?`n\\s\*overallStatus=PASS'
Assert-Contains -Name "bundle signal blocker mapping" -Text $bundleText -Pattern '\$blockers\.Add\("SIGNAL_POLICY_REVIEW_GAPS"\)'

Assert-Contains -Name "remediation matrix" -Text $remediationText -Pattern '\| `SIGNAL_POLICY_REVIEW_GAPS` \| `\.\\scripts\\smoke_signal_correctness_ssh\.ps1` \|'
foreach ($pattern in @(
        'No `REVIEW_POLICY_GAPS`',
        'explicit 7d `governanceMode` is present',
        'governanceMode=TOO_STRICT',
        'governanceMode=TOO_LOOSE',
        'governanceMode=INSUFFICIENT_DATA',
        'overallStatus=PASS',
        'missing or `N/A` governance/missed-opportunity evidence stays blocked',
        'shadow/tiny-live caps only',
        'signal correctness and governance drift summary'
    )) {
    Assert-Contains -Name "remediation matrix signal policy row" -Text $remediationText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'smoke_signal_correctness_ssh.ps1',
        'REVIEW_POLICY_GAPS',
        'governance drift',
        'missed-opportunity',
        'No-Buy Reason Truth Table'
    )) {
    Assert-Contains -Name "deploy runbook signal policy section" -Text $runbookText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'smoke_signal_correctness_ssh.ps1',
        'signal correctness',
        'governance drift',
        'REVIEW_POLICY_GAPS',
        'not live approval'
    )) {
    Assert-Contains -Name "dry-run evidence plan signal gate" -Text $dryRunText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'smoke_signal_correctness_ssh.ps1',
        'signal correctness',
        'governance drift',
        'REVIEW_POLICY_GAPS',
        'market-signal state',
        'not authorization'
    )) {
    Assert-Contains -Name "production env review signal gate" -Text $proposalText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'smoke_signal_correctness_ssh.ps1',
        'server-local `/api/mcp`',
        'DataFreshnessGuard current status',
        'governance drift',
        'EntryDedup governance',
        'missed-opportunity regression',
        'REVIEW_POLICY_GAPS',
        'live blockers'
    )) {
    Assert-Contains -Name "README signal policy handoff" -Text $readmeText -Pattern ([regex]::Escape($pattern))
}
Assert-Contains -Name "README signal policy handoff" -Text $readmeText -Pattern 'no-buy\s+reason\s+truth\s+table'

foreach ($pattern in @(
        'getNoBuyReasonTruthTable',
        'scripts/test_signal_policy_review_plan.ps1',
        'SIGNAL_POLICY_REVIEW_GAPS',
        'governance drift documentation',
        'missed-opportunity evidence'
    )) {
    Assert-Contains -Name "split progress signal policy handoff" -Text $progressText -Pattern ([regex]::Escape($pattern))
}
Assert-Contains -Name "split progress signal policy handoff" -Text $progressText -Pattern 'signal-policy\s+review contract drift'

Write-Host "[signal-policy-review-plan-test] OK"
