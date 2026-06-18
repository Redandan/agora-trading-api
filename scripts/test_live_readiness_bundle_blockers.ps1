Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LiveReadinessBundleBlockers {
    param(
        [string]$Audit = "",
        [string]$Background = "",
        [string]$RuntimeEvidence = "",
        [string]$TinyLive = "",
        [string]$Signal = "",
        [string]$McpParity = "",
        [string]$DeploymentMetadata = ""
    )

    $blockers = [System.Collections.Generic.List[string]]::new()
    if ($Audit -match "verdict=NOT_READY") {
        $blockers.Add("LIVE_READINESS_NOT_READY")
    }
    if ($Background -match "HIGH_RISK_BACKGROUND_AUTOMATION_TRUE" -or $Background -match "NOT_READY_BACKGROUND_AUTOMATION_REVIEW") {
        $blockers.Add("BACKGROUND_AUTOMATION_REVIEW")
    }
    if ($RuntimeEvidence -match "diagnosis=CONFIG_DISABLED") {
        $blockers.Add("RUNTIME_EVIDENCE_CONFIG_DISABLED")
    }
    if ($RuntimeEvidence -match "shadowIntentCount=0") {
        $blockers.Add("RUNTIME_EVIDENCE_NO_SHADOW_INTENT")
    }
    if ($TinyLive -match "hardStopDetected=true" -or $TinyLive -match "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES") {
        $blockers.Add("TINY_LIVE_LOSS_HARD_STOP")
    }
    if ($Signal -match "REVIEW_POLICY_GAPS") {
        $blockers.Add("SIGNAL_POLICY_REVIEW_GAPS")
    }
    if ($McpParity -notmatch "\[mcp-parity-ssh\] OK") {
        $blockers.Add("MCP_PARITY_NOT_PROVEN")
    }
    if ($DeploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)") {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=(WORKTREE_NOT_ORIGIN_MAIN|UNKNOWN_ORIGIN_MAIN)") {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }

    @($blockers | Select-Object -Unique)
}

function Assert-BlockerCase {
    param(
        [string]$Name,
        [hashtable]$Inputs,
        [string[]]$ExpectedBlockers
    )

    $actual = @(Get-LiveReadinessBundleBlockers @Inputs | Sort-Object)
    $expected = @($ExpectedBlockers | Sort-Object)
    $actualText = $actual -join ","
    $expectedText = $expected -join ","
    if ($actualText -ne $expectedText) {
        throw "$Name expected blockers [$expectedText] but got [$actualText]"
    }
}

function Merge-Inputs {
    param(
        [hashtable]$Base,
        [hashtable]$Override
    )

    $merged = @{}
    foreach ($key in $Base.Keys) {
        $merged[$key] = $Base[$key]
    }
    foreach ($key in $Override.Keys) {
        $merged[$key] = $Override[$key]
    }
    $merged
}

function Assert-BundleScriptBlockersCovered {
    param([string[]]$ExpectedBlockers)

    $bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
    $bundleText = Get-Content -Raw -LiteralPath $bundlePath
    $actualBlockers = @(
        [regex]::Matches($bundleText, '\$blockers\.Add\("([^"]+)"\)') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
    $expected = @($ExpectedBlockers | Sort-Object -Unique)
    $actualText = $actualBlockers -join ","
    $expectedText = $expected -join ","
    if ($actualText -ne $expectedText) {
        throw "bundle script blockers [$actualText] differ from test coverage [$expectedText]"
    }
}

function Assert-RemediationDocBlockersCovered {
    param([string[]]$ExpectedBlockers)

    $repoRoot = Split-Path -Parent $PSScriptRoot
    $docPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"
    $docText = Get-Content -Raw -LiteralPath $docPath
    $docBlockers = @(
        [regex]::Matches($docText, '^\| `([A-Z0-9_]+)` \|', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
    $expected = @($ExpectedBlockers | Sort-Object -Unique)
    $docTextValue = $docBlockers -join ","
    $expectedText = $expected -join ","
    if ($docTextValue -ne $expectedText) {
        throw "remediation doc blockers [$docTextValue] differ from bundle blockers [$expectedText]"
    }
}

function Get-CurrentExpectedRemediationBlockers {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $docPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"
    $docText = Get-Content -Raw -LiteralPath $docPath
    $match = [regex]::Match(
        $docText,
        '## Current Expected Blockers[\s\S]*?```text\s*(?<blockers>[\s\S]*?)```',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $match.Success) {
        throw "remediation doc is missing Current Expected Blockers code block"
    }

    @(
        $match.Groups["blockers"].Value -split "`r?`n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match '^[A-Z0-9_]+$' } |
            Sort-Object -Unique
    )
}

function Get-LatestProposalSnapshotBlockers {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $docPath = Join-Path $repoRoot "docs/live-production-env-review-proposal.md"
    $docText = Get-Content -Raw -LiteralPath $docPath
    $match = [regex]::Match($docText, 'bundle_blockers=\[(?<blockers>[^\]]*)\]')
    if (-not $match.Success) {
        throw "production env review proposal is missing latest bundle_blockers snapshot"
    }

    @(
        [regex]::Matches($match.Groups["blockers"].Value, '"([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
}

function Assert-CurrentExpectedBlockersMatchLatestSnapshot {
    $currentExpected = @(Get-CurrentExpectedRemediationBlockers)
    $latestSnapshot = @(Get-LatestProposalSnapshotBlockers)
    $currentText = $currentExpected -join ","
    $snapshotText = $latestSnapshot -join ","
    if ($currentText -ne $snapshotText) {
        throw "remediation current expected blockers [$currentText] differ from latest proposal snapshot blockers [$snapshotText]"
    }
    if ($currentExpected -notcontains "DEPLOYED_RUNTIME_NOT_CURRENT") {
        throw "current expected blockers must include DEPLOYED_RUNTIME_NOT_CURRENT while latest recorded snapshot is stale"
    }
}

function Assert-BundleEvidenceWindowsCovered {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
    $readmePath = Join-Path $repoRoot "README.md"
    $runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
    $bundleText = Get-Content -Raw -LiteralPath $bundlePath
    $docsText = @(
        Get-Content -Raw -LiteralPath $readmePath
        Get-Content -Raw -LiteralPath $runbookPath
    ) -join "`n"

    foreach ($pattern in @(
            '\[int\]\$RuntimeEvidenceMinutes = 43200',
            '\[int\]\$TinyLiveDays = 30',
            '\[int\]\$SignalExecutionDays = 5',
            '\[int\]\$SignalBlockedDays = 7',
            '\[int\]\$SignalAccuracyDays = 14',
            '\$RuntimeEvidenceMinutes -lt 60 -or \$RuntimeEvidenceMinutes -gt 43200',
            '\$TinyLiveDays -lt 1 -or \$TinyLiveDays -gt 90',
            '\$SignalExecutionDays -lt 1 -or \$SignalExecutionDays -gt 90',
            '\$SignalBlockedDays -lt 1 -or \$SignalBlockedDays -gt 90',
            '\$SignalAccuracyDays -lt 1 -or \$SignalAccuracyDays -gt 90',
            'Minutes = \$RuntimeEvidenceMinutes',
            'Days = \$TinyLiveDays',
            'ExecutionDays = \$SignalExecutionDays',
            'BlockedDays = \$SignalBlockedDays',
            'AccuracyDays = \$SignalAccuracyDays'
        )) {
        if ($bundleText -notmatch $pattern) {
            throw "live readiness bundle missing bounded evidence-window marker: $pattern"
        }
    }

    foreach ($pattern in @(
            'runtime evidence defaults to\s+43,200 minutes',
            'tiny-live RCA defaults to\s+30 days',
            'signal execution defaults to\s+5 days',
            'blocked-signal/governance review\s+defaults to\s+7 days',
            'signal accuracy defaults to\s+14 days',
            'RuntimeEvidenceMinutes=43200',
            'TinyLiveDays=30',
            'SignalExecutionDays=5',
            'SignalBlockedDays=7',
            'SignalAccuracyDays=14',
            'Override them only for a documented\s+read-only diagnostic'
        )) {
        if ($docsText -notmatch $pattern) {
            throw "live readiness bundle docs missing evidence-window marker: $pattern"
        }
    }
}

$allExpectedBlockers = @(
    "BACKGROUND_AUTOMATION_REVIEW",
    "DEPLOYED_RUNTIME_NOT_CURRENT",
    "LIVE_READINESS_NOT_READY",
    "MCP_PARITY_NOT_PROVEN",
    "RUNTIME_EVIDENCE_CONFIG_DISABLED",
    "RUNTIME_EVIDENCE_NO_SHADOW_INTENT",
    "SIGNAL_POLICY_REVIEW_GAPS",
    "TINY_LIVE_LOSS_HARD_STOP"
)

Assert-BundleScriptBlockersCovered -ExpectedBlockers $allExpectedBlockers
Assert-RemediationDocBlockersCovered -ExpectedBlockers $allExpectedBlockers
Assert-CurrentExpectedBlockersMatchLatestSnapshot
Assert-BundleEvidenceWindowsCovered

$cleanInputs = @{
    Audit = "verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    Background = "verdict=OK_BACKGROUND_AUTOMATION_DISABLED"
    RuntimeEvidence = "diagnosis=CANONICAL_SHADOW_READY`nshadowIntentCount=3"
    TinyLive = "hardStopDetected=false"
    Signal = "no review gaps"
    McpParity = "[mcp-parity-ssh] OK toolCount=305 required=35"
    DeploymentMetadata = "liveBundleDeployStatus=CURRENT`nliveBundleOriginStatus=CURRENT_ORIGIN_MAIN"
}

Assert-BlockerCase -Name "clean ready-for-review mapping" -Inputs $cleanInputs -ExpectedBlockers @()

Assert-BlockerCase -Name "audit not ready" -Inputs (Merge-Inputs $cleanInputs @{ Audit = "verdict=NOT_READY" }) -ExpectedBlockers @("LIVE_READINESS_NOT_READY")
Assert-BlockerCase -Name "background high risk" -Inputs (Merge-Inputs $cleanInputs @{ Background = "blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE" }) -ExpectedBlockers @("BACKGROUND_AUTOMATION_REVIEW")
Assert-BlockerCase -Name "background not ready verdict" -Inputs (Merge-Inputs $cleanInputs @{ Background = "verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW" }) -ExpectedBlockers @("BACKGROUND_AUTOMATION_REVIEW")
Assert-BlockerCase -Name "runtime config disabled" -Inputs (Merge-Inputs $cleanInputs @{ RuntimeEvidence = "diagnosis=CONFIG_DISABLED`nshadowIntentCount=3" }) -ExpectedBlockers @("RUNTIME_EVIDENCE_CONFIG_DISABLED")
Assert-BlockerCase -Name "runtime no shadow intent" -Inputs (Merge-Inputs $cleanInputs @{ RuntimeEvidence = "diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT`nshadowIntentCount=0" }) -ExpectedBlockers @("RUNTIME_EVIDENCE_NO_SHADOW_INTENT")
Assert-BlockerCase -Name "tiny live hard stop" -Inputs (Merge-Inputs $cleanInputs @{ TinyLive = "hardStopDetected=true" }) -ExpectedBlockers @("TINY_LIVE_LOSS_HARD_STOP")
Assert-BlockerCase -Name "tiny live consecutive loss text" -Inputs (Merge-Inputs $cleanInputs @{ TinyLive = "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES" }) -ExpectedBlockers @("TINY_LIVE_LOSS_HARD_STOP")
Assert-BlockerCase -Name "signal policy review gaps" -Inputs (Merge-Inputs $cleanInputs @{ Signal = "Operator action: REVIEW_POLICY_GAPS" }) -ExpectedBlockers @("SIGNAL_POLICY_REVIEW_GAPS")
Assert-BlockerCase -Name "missing mcp parity ok marker" -Inputs (Merge-Inputs $cleanInputs @{ McpParity = "toolCount=304 required=35" }) -ExpectedBlockers @("MCP_PARITY_NOT_PROVEN")
Assert-BlockerCase -Name "runtime drift metadata" -Inputs (Merge-Inputs $cleanInputs @{ DeploymentMetadata = "liveBundleDeployStatus=RUNTIME_DRIFT`nliveBundleOriginStatus=CURRENT_ORIGIN_MAIN" }) -ExpectedBlockers @("DEPLOYED_RUNTIME_NOT_CURRENT")
Assert-BlockerCase -Name "origin drift metadata" -Inputs (Merge-Inputs $cleanInputs @{ DeploymentMetadata = "liveBundleDeployStatus=CURRENT`nliveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN" }) -ExpectedBlockers @("DEPLOYED_RUNTIME_NOT_CURRENT")

Assert-BlockerCase `
    -Name "current observed blocker mix" `
    -Inputs @{
        Audit = "verdict=NOT_READY"
        Background = "blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE"
        RuntimeEvidence = "diagnosis=CONFIG_DISABLED`nshadowIntentCount=0"
        TinyLive = "hardStopDetected=true`nAUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES"
        Signal = "[signal-correctness] OK read-only check complete"
        McpParity = "[mcp-parity-ssh] OK toolCount=305 required=35"
        DeploymentMetadata = "liveBundleDeployStatus=CURRENT`nliveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN"
    } `
    -ExpectedBlockers @(
        "LIVE_READINESS_NOT_READY",
        "BACKGROUND_AUTOMATION_REVIEW",
        "RUNTIME_EVIDENCE_CONFIG_DISABLED",
        "RUNTIME_EVIDENCE_NO_SHADOW_INTENT",
        "TINY_LIVE_LOSS_HARD_STOP",
        "DEPLOYED_RUNTIME_NOT_CURRENT"
    )

Write-Host "[live-bundle-blocker-test] OK"
