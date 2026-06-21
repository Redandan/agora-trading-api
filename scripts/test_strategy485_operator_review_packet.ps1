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

function Assert-FailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern
    )

    $script = Join-Path $PSScriptRoot "prepare_strategy485_operator_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy485 operator packet test"
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
        throw "strategy485 operator packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "strategy485 operator packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy485 operator packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy485_operator_review_packet_ssh.ps1"
$gatePath = Join-Path $PSScriptRoot "prepare_strategy485_position_review_gate_ssh.ps1"
$planPath = Join-Path $repoRoot "docs/strategy485-aged-position-review-plan.md"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$gateText = Get-Content -Raw -LiteralPath $gatePath
$planText = Get-Content -Raw -LiteralPath $planPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[strategy485-operator-review-packet] read-only packet preflight",
        "scope=READ_ONLY",
        "prepare_strategy485_position_review_gate_ssh.ps1",
        "docs/strategy485-aged-position-review-plan.md",
        "origin_delta_status",
        "strategy485_position_risk_recommendation",
        "strategy485_position_review_decision",
        "deploy_required_before_strategy485_packet",
        "operator_review_packet_allowed",
        "position_or_oco_mutation_allowed=false",
        "strategy485_operator_packet_missing_requirements",
        "strategy485_operator_review_packet",
        "strategy485_operator_packet_status",
        "gateMissingIsBlocking",
        "READY_FOR_OPERATOR_PACKET_NOT_MUTATION",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "BLOCKED_OCO_PROTECTION_FIRST",
        "NO_EVIDENCE",
        "RequireReady",
        "canDraftOperatorReviewPacket",
        "positionOrOcoMutationAllowed",
        "strategy485 decision missing field",
        "strategy485 decision must block position/OCO mutation",
        "notAuthorization=read-only packet preflight only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "strategy485 operator packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "operator_review_packet_allowed",
        "position_or_oco_mutation_allowed=false",
        "strategy485_position_review_decision",
        "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION"
    )) {
    Assert-Contains -Name "strategy485 gate supports packet" -Text $gateText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "Required Packet Inputs",
        "positionOrOcoMutationAllowed=false",
        "Any actual close, OCO modification, new order",
        'stale production runtime or `origin_delta_status=RUNTIME_DRIFT`'
    )) {
    Assert-Contains -Name "strategy485 plan supports packet" -Text $planText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy485_operator_review_packet_ssh.ps1",
        "strategy 485 operator review packet",
        "strategy485_operator_packet_status",
        "READY_FOR_OPERATOR_PACKET_NOT_MUTATION",
        "position_or_oco_mutation_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention strategy485 packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-PositionAgeWarnDays", "0") `
    -ExpectedPattern "PositionAgeWarnDays must be between 1 and 90"

Write-Host "[strategy485-operator-review-packet-test] OK"
