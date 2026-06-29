Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsWith {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [string]$Pattern
    )

    $failed = $false
    try {
        & $Action
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Name failed with unexpected message: $($_.Exception.Message)"
        }
    }

    if (-not $failed) {
        throw "$Name did not fail"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_complete_operator_packet_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$statusPath = Join-Path $repoRoot "docs/split-acceptance-status.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "GRID_OPEN_COMPLETE_OPERATOR_PACKET",
        "READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION",
        "AWAIT_SEPARATE_OPERATOR_AUTHORIZATIONS_AND_DEPLOY_CURRENTNESS",
        "prepare_grid_split_acceptance_deploy_handoff_ssh.ps1",
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "prepare_grid_post_env_verification_plan_ssh.ps1",
        "SplitHandoffLog",
        "OperatorAuthorizationRequestLog",
        "PostEnvPlanLog",
        "currentnessAuthorizationLine",
        "requiredPostDeployReadOnlyVerification",
        "requiredPostEnvCommands",
        "completeOperatorPacketReady",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "gridOpenAllowed = `$false",
        "gridMutationAllowed = `$false",
        "schedulerEnablementAllowed = `$false",
        "orderAllowed = `$false",
        "ocoMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "read-only grid open complete operator packet only",
        "RequireCompletePacketReady",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "grid complete operator packet script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git push",
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl restart",
        "systemctl reload",
        "nginx -s reload",
        "tools/call",
        "createGrid(",
        "pauseGrid(",
        "resumeGrid(",
        "closeGrid(",
        "enableGridAutoRebalance("
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "grid complete operator packet must not contain mutation marker: $forbidden"
    }
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $statusPath
) -join "`n"
foreach ($marker in @(
        "prepare_grid_open_complete_operator_packet_ssh.ps1",
        "GRID_OPEN_COMPLETE_OPERATOR_PACKET",
        "grid_open_complete_operator_packet_status",
        "READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION",
        "read-only"
    )) {
    Assert-Contains -Name "grid complete operator packet docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "grid complete operator packet verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_grid_open_complete_operator_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("grid-complete-operator-packet-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $splitLog = Join-Path $tempDir "split.log"
    $requestLog = Join-Path $tempDir "request.log"
    $planLog = Join-Path $tempDir "plan.log"

    $splitPacket = [ordered]@{
        packetType = "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET"
        status = "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION"
        decision = "REQUEST_SEPARATE_DEPLOY_CURRENT_MAIN_AND_READ_ONLY_GRID_VERIFICATION"
        symbol = "BTCUSDT"
        reviewedGridCandidateParameters = [ordered]@{ symbol = "BTCUSDT"; lookbackHours = 72; candidateLookbackHours = 168; gridCount = 2; perLevelUsdt = 5; stopOutPct = 5; candidateHalfWidthPct = 10 }
        gridOpenableNow = $false
        gridOpenReadinessScorePct = "66.67"
        gridOpenReadinessPassedGates = "8/12"
        gridOpenRankedBlockers = @(
            [ordered]@{ rank = 1; family = "deployment/split-acceptance"; priority = "P0"; blocker = "SPLIT_ACCEPTANCE_NOT_PASSING"; evidence = "server behind"; nextAction = "deploy"; authorizationRequired = "separate deploy/restart authorization" },
            [ordered]@{ rank = 2; family = "production-env"; priority = "P0"; blocker = "GRID_ENV_DIFF_NOT_APPLIED"; evidence = "TRADING_OKX_ENABLED=false; TRADING_GRID_ENABLED=true"; nextAction = "env diff"; authorizationRequired = "separate production env diff authorization" }
        )
        expectedPostDeployNextBlockers = @(
            [ordered]@{ rank = 2; family = "production-env"; priority = "P0"; blocker = "GRID_ENV_DIFF_NOT_APPLIED" }
        )
        deploymentMetadataStatus = "CURRENT"
        originDeltaStatus = "DOCS_TOOLING_ONLY_DRIFT"
        originRuntimeDeltaFiles = "0"
        serverWorktreeCommit = "3937f5d"
        originMainCommitFromMetadata = "1054bc8"
        runtimeCurrentForGridOpen = $true
        splitAcceptanceBlockedByToolingOnlyCurrentness = $true
        gridOpenRankedRuntimeBlockers = @(
            [ordered]@{ rank = 2; family = "production-env"; priority = "P0"; blocker = "GRID_ENV_DIFF_NOT_APPLIED" },
            [ordered]@{ rank = 5; family = "capital"; priority = "P1"; blocker = "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP" }
        )
        requiredPostDeployReadOnlyVerification = @(
            ".\scripts\verify_split_acceptance_ssh.ps1",
            ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -Symbol BTCUSDT -GridCount 2"
        )
        missingRequirements = @()
    }
    "grid_split_acceptance_deploy_handoff_packet=$($splitPacket | ConvertTo-Json -Depth 20 -Compress)" | Set-Content -LiteralPath $splitLog -Encoding UTF8

    $requestPacket = [ordered]@{
        packetType = "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET"
        status = "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION"
        authorizationRequestReady = $true
        trendGate = "CLEAR_TREND_REGIME"
        trendGateClearanceAccepted = $true
        authorizationRequestLines = @(
            "Fresh trend gate clearance accepted for BTCUSDT (trendGate=CLEAR_TREND_REGIME); separate trend-regime override is not required unless the gate becomes blocked before env/createGrid review.",
            "I authorize a separate production env diff for BTCUSDT grid review only: TRADING_OKX_ENABLED=true; TRADING_GRID_ENABLED=true."
        )
        remainingExecutionBlockers = @("OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED")
        reviewedCreateGridInputs = [ordered]@{ symbol = "BTCUSDT"; priceLower = 54266.49; priceUpper = 66325.71; gridCount = 2; perLevelUsdt = 5; candidateCapitalUsdt = 10; stopLow = 51553.17; stopHigh = 69642.0; stopOutPct = 5; replayScore = 80 }
        capitalOverrideRequest = [ordered]@{ candidateCapitalUsdt = 10; effectiveReviewCapitalCapUsdt = 5; requiredCapRaiseUsdt = 5; requiredCapMultiplier = 2 }
        proposedSeparateEnvDiff = @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false")
        requestBlockers = @()
        coveredCreateReviewBlockers = @("CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP")
        uncoveredCreateReviewBlockers = @()
        missingEvidence = @()
    }
    "grid_open_operator_authorization_request_packet=$($requestPacket | ConvertTo-Json -Depth 20 -Compress)" | Set-Content -LiteralPath $requestLog -Encoding UTF8

    $planPacket = [ordered]@{
        packetType = "GRID_POST_ENV_VERIFICATION_PLAN_PACKET"
        status = "READY_FOR_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION"
        postEnvVerificationPlanReady = $true
        requiredPostEnvCommands = @(
            ".\scripts\verify_split_acceptance_ssh.ps1",
            ".\scripts\prepare_grid_env_diff_preflight_packet_ssh.ps1 -Symbol BTCUSDT -GridCount 2 -AcceptAlreadyAppliedEnvDiff"
        )
        planBlockers = @()
        missingEvidence = @()
    }
    "grid_post_env_verification_plan_packet=$($planPacket | ConvertTo-Json -Depth 20 -Compress)" | Set-Content -LiteralPath $planLog -Encoding UTF8

    $output = & $scriptPath -SplitHandoffLog $splitLog -OperatorAuthorizationRequestLog $requestLog -PostEnvPlanLog $planLog -RequireCompletePacketReady *>&1
    $text = $output -join "`n"
    Assert-Contains -Name "grid complete packet output" -Text $text -Pattern "grid_open_complete_operator_packet="
    $packetJson = (($text -split "`r?`n") | Where-Object { $_.StartsWith("grid_open_complete_operator_packet=") } | Select-Object -Last 1).Substring("grid_open_complete_operator_packet=".Length)
    $packet = $packetJson | ConvertFrom-Json
    if ($packet.coveredCreateReviewBlockers -is [string]) {
        throw "coveredCreateReviewBlockers must stay a JSON array, not a string"
    }
    if ($packet.uncoveredCreateReviewBlockers -is [pscustomobject]) {
        throw "uncoveredCreateReviewBlockers must stay a JSON array, not an object"
    }
    if (@($packet.remainingExecutionBlockers) -contains "SPLIT_ACCEPTANCE_NOT_PASSING") {
        throw "SPLIT_ACCEPTANCE_NOT_PASSING must be omitted when split runtime currentness is proven by zero runtime delta"
    }
    if (@($packet.remainingExecutionBlockers) -contains "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP") {
        throw "covered CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP must not be duplicated as a remaining execution blocker"
    }
    if (@($packet.rawExecutionBlockers) -notcontains "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP") {
        throw "rawExecutionBlockers must preserve the covered capital review blocker as evidence"
    }
    Assert-Contains -Name "grid complete packet status" -Text $text -Pattern "grid_open_complete_operator_packet_status=READY_FOR_GRID_OPEN_COMPLETE_OPERATOR_PACKET_NOT_MUTATION"
    Assert-Contains -Name "grid complete packet ready" -Text $text -Pattern "grid_open_complete_operator_packet_ready=true"
    Assert-Contains -Name "grid complete packet currentness line" -Text $text -Pattern "origin/main 1054bc8"
    Assert-Contains -Name "grid complete packet runtime currentness" -Text $text -Pattern "zero runtime delta"
    Assert-Contains -Name "grid complete packet env diff" -Text $text -Pattern "TRADING_OKX_ENABLED=true"
    Assert-Contains -Name "grid complete packet covered execution blockers" -Text $text -Pattern "grid_open_complete_operator_packet_covered_execution_review_blockers="
    Assert-Contains -Name "grid complete packet trend clearance sequence" -Text $text -Pattern "separate trend-regime override not required"
    Assert-Contains -Name "grid complete packet post env command" -Text $text -Pattern "AcceptAlreadyAppliedEnvDiff"
    Assert-Contains -Name "grid complete packet deploy blocked" -Text $text -Pattern "deploy_allowed=false"
    Assert-Contains -Name "grid complete packet grid blocked" -Text $text -Pattern "grid_open_allowed=false"
    Assert-Contains -Name "grid complete packet not authorization" -Text $text -Pattern "notAuthorization=read-only grid open complete operator packet only"

    Assert-FailsWith -Name "partial replay logs" -Pattern "must be provided together" -Action {
        & $scriptPath -SplitHandoffLog $splitLog
    }

    $tempKey = Join-Path $tempDir "dummy.key"
    Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[grid-open-complete-operator-packet-test] OK"
