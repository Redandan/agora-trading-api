Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

$expectedObservedAt = "2026-06-19T12:15+08:00"
$expectedMetadataObservedAt = "2026-06-20T09:53+08:00"
$expectedCurrentMetadataObservedAt = "2026-06-20T13:34+08:00"
$expectedServerCommit = "224f550478b20a329775f503b3eaa70ba6a2f6a8"
$expectedOriginCommit = "0eef3ce5c3964e2520c1c5aa16a57e87f0ba26a0"
$expectedMetadataOriginCommit = "4ee52d860fb18f79bd989801c471cd71be5c63d1"
$expectedCurrentOriginCommit = "873b219171755401c40f3a676fb3c7c9477471ec"
$expectedCurrentObservedAt = "2026-06-20T20:28+08:00"
$expectedCurrentCommit = "ef6253a4ecff7c27a2e709f226e166389700a82d"
$expectedLatestSplitObservedAt = "2026-06-20T20:28+08:00"
$expectedLatestSplitCommit = "ef6253a4ecff7c27a2e709f226e166389700a82d"
$expectedLatestDiagnosticObservedAt = "2026-06-20T22:31+08:00"
$expectedLatestDiagnosticOriginCommit = "d0e0f4f20a1892b81d1147c631caf04d20e8400a"
$expectedLatestOriginDeltaObservedAt = "2026-06-20T22:31+08:00"
$expectedLatestOriginDeltaCommit = "d0e0f4f20a1892b81d1147c631caf04d20e8400a"
$expectedLatestRcaObservedAt = "2026-06-20T22:31+08:00"
$expectedBlockers = @(
    "LIVE_READINESS_NOT_READY",
    "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN",
    "EXECUTION_ELIGIBILITY_NOT_READY",
    "BACKGROUND_AUTOMATION_REVIEW",
    "RUNTIME_EVIDENCE_CONFIG_DISABLED",
    "RUNTIME_EVIDENCE_NO_SHADOW_INTENT",
    "TINY_LIVE_LOSS_HARD_STOP",
    "TINY_LIVE_ROLLOUT_NOT_READY",
    "SIGNAL_POLICY_REVIEW_GAPS",
    "DEPLOYED_RUNTIME_NOT_CURRENT"
)
$expectedCurrentBlockers = @(
    "LIVE_READINESS_NOT_READY",
    "EXECUTION_ELIGIBILITY_NOT_READY",
    "BACKGROUND_AUTOMATION_REVIEW",
    "RUNTIME_EVIDENCE_CONFIG_DISABLED",
    "RUNTIME_EVIDENCE_NO_SHADOW_INTENT",
    "TINY_LIVE_LOSS_HARD_STOP",
    "TINY_LIVE_ROLLOUT_NOT_READY",
    "SIGNAL_POLICY_REVIEW_GAPS"
)

function Read-RepoText {
    param([string]$RelativePath)
    Get-Content -Raw -LiteralPath (Join-Path $repoRoot $RelativePath)
}

function Assert-ContainsLiteral {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Name missing literal: $Needle"
    }
}

function Assert-ContainsPattern {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-BlockersPresent {
    param(
        [string]$Name,
        [string]$Text
    )
    foreach ($blocker in $expectedBlockers) {
        Assert-ContainsLiteral -Name $Name -Text $Text -Needle $blocker
    }
}

function Assert-BlockerJsonExact {
    param(
        [string]$Name,
        [string]$Text
    )
    $json = 'bundle_blockers=["' + ($expectedBlockers -join '","') + '"]'
    Assert-ContainsLiteral -Name $Name -Text $Text -Needle $json
}

$splitStatus = Read-RepoText "docs/split-acceptance-status.md"
$splitProgress = Read-RepoText "SPLIT_PROGRESS.md"
$remediation = Read-RepoText "docs/live-readiness-blocker-remediation.md"
$productionProposal = Read-RepoText "docs/live-production-env-review-proposal.md"

foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle $expectedLatestSplitObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle $expectedLatestSplitCommit
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "active port"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "8084"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "origin_metadata_status=CURRENT_ORIGIN_MAIN"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "deployment_metadata_status=CURRENT"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "metadata_blockers=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "deploy_required_before_live_review=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "reported runtime log"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle '`PASS` with ERROR count 0'
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "WARN baseline total 13"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "required_tools=[...]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "missing_required_tools=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "toolCount=305 required=35"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "missing_readiness_detail_fields=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "autonomousOpportunity.eligible=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "backgroundAutomationClear=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle 'background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "BACKGROUND_AUTOMATION_TRUE"]'
    Assert-ContainsLiteral -Name "$($doc.Name) docs-only snapshot boundary" -Text $doc.Text -Needle "Do not chase docs-only deploy commits"
    Assert-ContainsLiteral -Name "$($doc.Name) docs-only snapshot boundary" -Text $doc.Text -Needle "currentness source of truth"
    Assert-ContainsLiteral -Name "$($doc.Name) docs-only snapshot boundary" -Text $doc.Text -Needle "freshly rerun deployment metadata smoke plus the full live-readiness bundle"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "bundle_blocker_summary"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "MCP_AUDIT_TOOL_ERROR"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "are no longer current blockers"
    Assert-ContainsLiteral -Name "$($doc.Name) latest current bundle" -Text $doc.Text -Needle "bundle_verdict=NOT_READY"
    foreach ($blocker in $expectedCurrentBlockers) {
        Assert-ContainsLiteral -Name "$($doc.Name) latest current blocker" -Text $doc.Text -Needle $blocker
    }
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedObservedAt
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedServerCommit
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedOriginCommit
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "originMainCommit"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "observed origin"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "rerun"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "smoke_live_deployment_metadata_ssh.ps1"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "metadata-only"
    Assert-BlockersPresent -Name $doc.Name -Text $doc.Text
    Assert-ContainsLiteral -Name "$($doc.Name) current metadata refresh" -Text $doc.Text -Needle $expectedCurrentMetadataObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) current metadata refresh" -Text $doc.Text -Needle $expectedCurrentOriginCommit
    Assert-ContainsLiteral -Name "$($doc.Name) current metadata refresh" -Text $doc.Text -Needle 'bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]'
    Assert-ContainsLiteral -Name "$($doc.Name) current metadata refresh" -Text $doc.Text -Needle "bundle_verdict=NO_EVIDENCE"
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle $expectedLatestDiagnosticObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle $expectedLatestDiagnosticOriginCommit
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle "origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN"
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle 'metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]'
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle "WARN baseline total 16"
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle "signalPolicyClear=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle "TOO_STRICT"
    Assert-ContainsLiteral -Name "$($doc.Name) latest diagnostic refresh" -Text $doc.Text -Needle 'missed-opportunity regression was `WARN`'
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle $expectedLatestOriginDeltaObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle $expectedLatestOriginDeltaCommit
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_delta_local_evidence=true"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_delta_status=DOCS_TOOLING_ONLY_DRIFT"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_delta_files=20"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_docs_tooling_delta_files=20"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_runtime_delta_files=0"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "origin_runtime_delta_paths=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "live_review_packet_allowed=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest origin delta classifier" -Text $doc.Text -Needle "routing evidence only"
}

Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle $expectedCurrentObservedAt
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle $expectedCurrentCommit
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "snapshotType=ATTACHED_READ_ONLY_EVIDENCE"
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "not a currentness"
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "rerun"
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "smoke_live_deployment_metadata_ssh.ps1"
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "smoke_live_readiness_bundle_ssh.ps1"
Assert-ContainsLiteral -Name "live production env review proposal attached snapshot" -Text $productionProposal -Needle "fresh output"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "origin_metadata_status=CURRENT_ORIGIN_MAIN"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "deployment_metadata_status=CURRENT"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "runtimeLog=PASS"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "runtimeLogErrors=0"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "runtimeLogWarnBaselineTotal=13"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "missing_readiness_detail_fields=[]"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "backgroundAutomationClear=false"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle 'backgroundAutomationBlockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE","BACKGROUND_AUTOMATION_TRUE"]'
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "bundle_blocker_summary=present"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "metadata_blockers=[]"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "deploy_required_before_live_review=false"
Assert-ContainsLiteral -Name "live production env review proposal current snapshot" -Text $productionProposal -Needle "whether there is a deploy/currentness blocker"
foreach ($blocker in $expectedCurrentBlockers) {
    Assert-ContainsLiteral -Name "live production env review proposal current blocker" -Text $productionProposal -Needle $blocker
}

foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) historical metadata refresh" -Text $doc.Text -Needle $expectedMetadataObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) historical metadata refresh" -Text $doc.Text -Needle $expectedMetadataOriginCommit
}

Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle $expectedCurrentObservedAt
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle $expectedCurrentCommit
Assert-ContainsLiteral -Name "live readiness remediation attached snapshot" -Text $remediation -Needle "Latest Attached Expected Blockers"
Assert-ContainsLiteral -Name "live readiness remediation attached snapshot" -Text $remediation -Needle "not as currentness evidence after later commits"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "deployment_metadata_status=CURRENT"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "origin_metadata_status=CURRENT_ORIGIN_MAIN"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "runtime_log_status=PASS"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "missing_readiness_detail_fields=[]"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "backgroundAutomationClear=false"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle 'background_automation_blockers=["HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "BACKGROUND_AUTOMATION_TRUE"]'
Assert-ContainsLiteral -Name "live readiness remediation docs-only snapshot boundary" -Text $remediation -Needle "Do not chase docs-only deploy commits"
Assert-ContainsLiteral -Name "live readiness remediation docs-only snapshot boundary" -Text $remediation -Needle "currentness must come from a freshly rerun"
Assert-ContainsLiteral -Name "live readiness remediation docs-only snapshot boundary" -Text $remediation -Needle "not from the SHA embedded"
Assert-ContainsLiteral -Name "live readiness remediation current snapshot" -Text $remediation -Needle "deploy_required_before_live_review=false"
foreach ($blocker in $expectedCurrentBlockers) {
    Assert-ContainsLiteral -Name "live readiness remediation current blocker" -Text $remediation -Needle $blocker
}
Assert-ContainsLiteral -Name "live readiness remediation historical metadata" -Text $remediation -Needle $expectedServerCommit
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "originMainCommit"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "observed origin"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "smoke_live_deployment_metadata_ssh.ps1"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "metadata-only"
Assert-BlockersPresent -Name "live readiness remediation" -Text $remediation
Assert-ContainsLiteral -Name "live readiness remediation current metadata refresh" -Text $remediation -Needle $expectedCurrentMetadataObservedAt
Assert-ContainsLiteral -Name "live readiness remediation current metadata refresh" -Text $remediation -Needle $expectedCurrentOriginCommit
Assert-ContainsLiteral -Name "live readiness remediation current metadata refresh" -Text $remediation -Needle 'bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]'
Assert-ContainsLiteral -Name "live readiness remediation current metadata refresh" -Text $remediation -Needle "bundle_verdict=NO_EVIDENCE"
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle $expectedLatestDiagnosticObservedAt
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle $expectedLatestDiagnosticOriginCommit
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle "origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN"
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle 'metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]'
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle "WARN baseline total 16"
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle "signalPolicyClear=false"
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle "governanceMode=TOO_STRICT"
Assert-ContainsLiteral -Name "live readiness remediation latest diagnostic refresh" -Text $remediation -Needle "overallStatus=WARN"
foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress },
        @{ Name = "live readiness remediation"; Text = $remediation }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle $expectedLatestRcaObservedAt
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "runtime_log_status=PASS"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "WARN baseline total 16"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "order_capable_flags_true=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "riskLevel=R0"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "diagnosis=CONFIG_DISABLED"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "runtimeEvidenceStatus=NOT_READY_ENABLED_FALSE"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "shadowIntentCount=0"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "orderSentEvidence=0"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "hardStopDetected=true"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "autoApprovalMode=BLOCKED"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "canEnableProduction=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "signalPolicyClear=false"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "governanceMode=TOO_STRICT"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "overallStatus=WARN"
    Assert-ContainsLiteral -Name "$($doc.Name) latest RCA refresh" -Text $doc.Text -Needle "read-only RCA evidence only"
}

Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle 'bundle_blockers=["LIVE_READINESS_NOT_READY","EXECUTION_ELIGIBILITY_NOT_READY","BACKGROUND_AUTOMATION_REVIEW","RUNTIME_EVIDENCE_CONFIG_DISABLED","RUNTIME_EVIDENCE_NO_SHADOW_INTENT","TINY_LIVE_LOSS_HARD_STOP","TINY_LIVE_ROLLOUT_NOT_READY","SIGNAL_POLICY_REVIEW_GAPS"]'
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "attached snapshot superseded earlier stale"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "server worktree, or deployed"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "deploy_required_before_live_review=false"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "strict read-only"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "strict read-only"
Assert-ContainsLiteral -Name "live production env review proposal refreshed metadata" -Text $productionProposal -Needle "refreshType=DEPLOYMENT_METADATA_ONLY"
Assert-ContainsLiteral -Name "live production env review proposal refreshed metadata" -Text $productionProposal -Needle "bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY"
foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) read-only runtime sanity" -Text $doc.Text -Needle "2026-06-20T10:04+08:00"
    Assert-ContainsLiteral -Name "$($doc.Name) read-only runtime sanity" -Text $doc.Text -Needle "verify_server_ssh.ps1 -SkipGitCurrent"
    Assert-ContainsLiteral -Name "$($doc.Name) read-only runtime sanity" -Text $doc.Text -Needle "service-health evidence only"
    Assert-ContainsLiteral -Name "$($doc.Name) read-only runtime sanity" -Text $doc.Text -Needle "not live-readiness evidence"
    Assert-ContainsLiteral -Name "$($doc.Name) read-only runtime sanity" -Text $doc.Text -Needle "not a substitute"
}
foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "2026-06-20T10:11+08:00"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "smoke_mcp_parity_ssh.ps1"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "required_tools=[...]"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "missing_required_tools=[]"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "toolCount=305"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "required=35"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "DEPLOYED_RUNTIME_NOT_CURRENT"
    Assert-ContainsLiteral -Name "$($doc.Name) stale mcp parity sanity" -Text $doc.Text -Needle "not live-readiness evidence"
}
foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "2026-06-20T10:16+08:00"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "app-20260618T070102Z-port8084.log"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "runtime ERROR lines present: count=2"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "TelegramServiceImpl"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "ExecutionEventScheduler"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "ALLOW_RUNTIME_ERROR=1"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "not live-readiness evidence"
}

foreach ($doc in @(
        @{ Name = "live readiness remediation"; Text = $remediation }
    )) {
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "2026-06-20T10:16+08:00"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "app-20260618T070102Z-port8084.log"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "runtime ERROR lines present: count=2"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "TelegramServiceImpl"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "ExecutionEventScheduler"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "ALLOW_RUNTIME_ERROR=1"
    Assert-ContainsLiteral -Name "$($doc.Name) strict runtime log blocker" -Text $doc.Text -Needle "diagnostic-only"
}

Assert-ContainsLiteral -Name "live production env review proposal stale runtime log RCA" -Text $productionProposal -Needle "2026-06-20T10:16+08:00"
Assert-ContainsLiteral -Name "live production env review proposal stale runtime log RCA" -Text $productionProposal -Needle "app-20260618T070102Z-port8084.log"
Assert-ContainsLiteral -Name "live production env review proposal stale runtime log RCA" -Text $productionProposal -Needle "TelegramServiceImpl"
Assert-ContainsLiteral -Name "live production env review proposal stale runtime log RCA" -Text $productionProposal -Needle "ExecutionEventScheduler"
Assert-ContainsLiteral -Name "live production env review proposal stale runtime log RCA" -Text $productionProposal -Needle "no longer the current blocker"

foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress },
        @{ Name = "live readiness remediation"; Text = $remediation }
    )) {
    Assert-ContainsPattern -Name $doc.Name -Text $doc.Text -Pattern "stale\s+live-review evidence"
    Assert-ContainsPattern -Name $doc.Name -Text $doc.Text -Pattern "separately\s+authorized"
}

Write-Host "[live-readiness-snapshot-consistency-test] OK"
