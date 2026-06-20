Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$expectedBackgroundFlags = @(
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_DAILY_TG_REPORT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED"
)

$expectedHighRiskFlags = @(
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED"
)

function Assert-SameSet {
    param(
        [string]$Name,
        [string[]]$Actual,
        [string[]]$Expected
    )

    $actualText = @($Actual | Sort-Object -Unique) -join ","
    $expectedText = @($Expected | Sort-Object -Unique) -join ","
    if ($actualText -ne $expectedText) {
        throw "$Name [$actualText] differs from expected [$expectedText]"
    }
}

function Get-PythonStringList {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Name
    )

    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        throw "Could not find $Name list."
    }
    @(
        [regex]::Matches($match.Groups[1].Value, '"([^"]+)"') |
            ForEach-Object { $_.Groups[1].Value }
    )
}

$smokePath = Join-Path $PSScriptRoot "smoke_live_background_automation_ssh.ps1"
$auditPath = Join-Path $PSScriptRoot "audit_live_readiness_ssh.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$proposalPath = Join-Path $repoRoot "docs/live-background-automation-env-diff-proposal.md"

$smokeText = Get-Content -Raw -LiteralPath $smokePath
$auditText = Get-Content -Raw -LiteralPath $auditPath
$proposalText = Get-Content -Raw -LiteralPath $proposalPath

$smokeBackground = Get-PythonStringList -Text $smokeText -Pattern 'background_flags\s*=\s*\[(.*?)\]' -Name "smoke background_flags"
$smokeHighRisk = Get-PythonStringList -Text $smokeText -Pattern 'high_risk_flags\s*=\s*\[(.*?)\]' -Name "smoke high_risk_flags"
$auditBackground = Get-PythonStringList -Text $auditText -Pattern 'background_flags\s*=\s*\[(.*?)\]' -Name "audit background_flags"

Assert-SameSet -Name "smoke background flags" -Actual $smokeBackground -Expected $expectedBackgroundFlags
Assert-SameSet -Name "smoke high-risk background flags" -Actual $smokeHighRisk -Expected $expectedHighRiskFlags
Assert-SameSet -Name "audit background flags" -Actual $auditBackground -Expected $expectedBackgroundFlags

foreach ($flag in $expectedBackgroundFlags) {
    if ($proposalText -notmatch [regex]::Escape($flag)) {
        throw "Background automation proposal missing current flag $flag"
    }
    if ($proposalText -notmatch ([regex]::Escape("$flag=false"))) {
        throw "Background automation proposal missing proposed false diff for $flag"
    }
}

foreach ($flag in $expectedHighRiskFlags) {
    $occurrences = [regex]::Matches($proposalText, [regex]::Escape($flag)).Count
    if ($occurrences -lt 3) {
        throw "Background automation proposal does not clearly list high-risk flag $flag"
    }
}

foreach ($pattern in @(
        "background_automation_false",
        "background_automation_review_plan",
        "missing_background_automation_flags",
        "background_automation_blockers=[]",
        "backgroundAutomationClear=true",
        "riskCategory",
        "requiredReview",
        "requiredEvidence",
        "nextAction",
        "notAuthorization",
        "smoke_live_background_automation_ssh.ps1 -RequireClear",
        "exits non-zero",
        "fail closed",
        "lists all nine reviewed background flags",
        "missing background automation flag",
        "does not list every reviewed background flag",
        "OK_BACKGROUND_AUTOMATION_DISABLED",
        "BACKGROUND_AUTOMATION_REVIEW`` no longer appears in ``bundle_blockers``",
        "order_capable_flags`` remain false",
        "full-bundle ``bundle_blockers=[]``",
        "``live_review_packet_allowed=true``",
        "``deploy_required_before_live_review=false``",
        "``bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED``",
        "not live approval"
    )) {
    if ($proposalText -notmatch [regex]::Escape($pattern)) {
        throw "Background automation proposal missing verification/rollback marker $pattern"
    }
}

foreach ($pattern in @(
        "background_automation_true=",
        "high_risk_background_automation_true=",
        "missing_background_automation_flags=",
        "background_automation_review_plan=",
        "background_automation_blockers=",
        "backgroundAutomationClear=",
        "flag_reviews = {",
        "riskCategory",
        "requiredReview",
        "requiredEvidence",
        "nextAction",
        "notAuthorization",
        "review_plan.append",
        "background_clear",
        "if not background_clear:",
        "if high_risk_true:",
        "if missing_flags:",
        "blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE",
        "blocker=MISSING_BACKGROUND_AUTOMATION_FLAG",
        "verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW",
        "[switch]`$RequireClear",
        "REQUIRE_CLEAR",
        "require_clear",
        "raise SystemExit(2)"
    )) {
    if ($smokeText -notmatch [regex]::Escape($pattern)) {
        throw "Background automation smoke missing fail-closed marker $pattern"
    }
}

foreach ($pattern in @(
        "background_flags = [",
        "background_missing = []",
        "missing_background_automation_flags=",
        "BACKGROUND_AUTOMATION_MISSING_FLAG_REVIEW_BEFORE_LIVE",
        "MISSING:{key}",
        "BACKGROUND_AUTOMATION_MISSING_FLAG"
    )) {
    if ($auditText -notmatch [regex]::Escape($pattern)) {
        throw "Live readiness audit missing background missing-flag marker $pattern"
    }
}

foreach ($pattern in @(
        "background_automation_false=",
        "missing_background_automation_flags=",
        "background_automation_review_plan=",
        "background_automation_blockers=",
        "backgroundAutomationClear=",
        "strip(chr(34)).strip(chr(39))",
        "sed '1s/^\xEF\xBB\xBF//'",
        "tr -d '\r' | bash -s",
        "blocker=MISSING_BACKGROUND_AUTOMATION_FLAG",
        "classification=BACKGROUND_AUTOMATION_CLEARED",
        "verdict=OK_BACKGROUND_AUTOMATION_DISABLED",
        "classification=BACKGROUND_AUTOMATION_REVIEW_BEFORE_LIVE",
        "recommendation=KEEP_LIVE_DISABLED_UNTIL_FLAGS_ARE_REVIEWED_OR_SEPARATELY_AUTHORIZED"
    )) {
    if ($smokeText -notmatch [regex]::Escape($pattern)) {
        throw "Background automation smoke missing output marker $pattern"
    }
}

Write-Host "[live-background-automation-flag-test] OK"
