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
