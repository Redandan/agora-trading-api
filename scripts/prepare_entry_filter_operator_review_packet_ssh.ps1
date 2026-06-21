param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
    [switch]$RequireReview
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for entry/filter packet arguments."
    }
}

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups[1].Value.Trim()
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for entry/filter operator review packet."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        Text = ($output | Out-String)
        ExitCode = $exitCode
    }
}

function Write-ChildFailureContext {
    param([string]$ScriptName, [pscustomobject]$Result)
    if ($Result.ExitCode -eq 0) {
        return
    }
    $text = [string]$Result.Text
    if ($text.Length -gt 4000) {
        $text = $text.Substring(0, 4000) + "`n...[truncated]"
    }
    Write-Host "[entry-filter-operator-review-packet] child_failure script=$ScriptName exitCode=$($Result.ExitCode)"
    Write-Host $text
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($ExecutionDays -lt 1 -or $ExecutionDays -gt 90 -or $BlockedDays -lt 1 -or $BlockedDays -gt 90 -or $AccuracyDays -lt 1 -or $AccuracyDays -gt 90) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$smokeArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
)
$smoke = Invoke-ReadOnlyScript -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments $smokeArgs
Write-ChildFailureContext -ScriptName "smoke_signal_correctness_ssh.ps1" -Result $smoke
$text = $smoke.Text

$executionStatus = Get-RegexValue -Text $text -Pattern "executionMachineStatus=([^\r\n]+)" -Default "N/A"
$missingEvalOrOrderBug = Get-RegexValue -Text $text -Pattern "missingEvalOrOrderBug=([^\r\n]+)" -Default "N/A"
$accuracySummary = Get-RegexValue -Text $text -Pattern "passSummary=([^\r\n]+)" -Default "N/A"
$governanceMode = Get-RegexValue -Text $text -Pattern "7d Governance Drift:\s*[\r\n]+\s*governanceMode=([^\r\n]+)" -Default "N/A"
$missedStatus = Get-RegexValue -Text $text -Pattern "Missed Opportunity Regression:\s*[\r\n]+\s*overallStatus=([A-Z_]+)" -Default "N/A"
$suspiciousNoBuyCount = Get-RegexValue -Text $text -Pattern "suspiciousNoBuyCount=([0-9]+)" -Default "0"
$falseBlockRiskCount = Get-RegexValue -Text $text -Pattern "falseBlockRiskCount=([0-9]+)" -Default "0"
$highForwardReturnNoBuyCount = Get-RegexValue -Text $text -Pattern "highForwardReturnNoBuyCount=([0-9]+)" -Default "0"
$signalPolicyClear = Get-RegexValue -Text $text -Pattern "signalPolicyClear=([^\r\n]+)" -Default "N/A"
$missingSignalFields = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "missing_signal_policy_fields=(\[[^\r\n]*\])" -Default "[]")
$reviewPlan = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "signal_policy_review_plan=(\[[^\r\n]*\])" -Default "[]")
$classifications = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=([^\r\n]+)" -Default "N/A"
$blockerFamilies = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$dataFreshnessCurrentStatus = Get-RegexValue -Text $text -Pattern "dataFreshnessCurrentStatus=([A-Z_]+)" -Default "N/A"
$dataFreshnessCurrentClean = ($dataFreshnessCurrentStatus -eq "CLEAN")
$entryDedupWouldAllow = Get-RegexValue -Text $text -Pattern "wouldAllowStagedAddGroups=([0-9]+)" -Default "0"
$dedupTooCoarseSuspects = Get-RegexValue -Text $text -Pattern "dedupTooCoarseSuspects=([0-9]+)" -Default "0"

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($smoke.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "signal correctness smoke completed"
}
if ($text -notmatch "read-only production MCP check") {
    Add-MissingRequirement -List $missingRequirements -Value "read-only production MCP marker"
}
if ($missingEvalOrOrderBug -ne "no") {
    Add-MissingRequirement -List $missingRequirements -Value "no missed evaluation/order bug"
}
if (-not $dataFreshnessCurrentClean) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness current snapshot clean"
}
if (@($missingSignalFields).Count -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "missing_signal_policy_fields=[]"
}
if (@($reviewPlan).Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "signal_policy_review_plan present"
}
if ($signalPolicyClear -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "signalPolicyClear=true before any policy relaxation"
}
if ($governanceMode -eq "TOO_STRICT" -or $governanceMode -eq "TOO_LOOSE" -or $governanceMode -eq "INSUFFICIENT_DATA" -or $governanceMode -eq "N/A") {
    Add-MissingRequirement -List $missingRequirements -Value "governance drift reviewed"
}
if ($missedStatus -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "missed-opportunity regression PASS"
}
if ($entryDedupWouldAllow -ne "0" -or $dedupTooCoarseSuspects -ne "0") {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup staged-add relaxation evidence reviewed"
}

$packetStatus = "NO_EVIDENCE"
$nextAction = "Fix read-only signal-policy evidence collection before drafting any entry/filter operator packet."
if ($smoke.ExitCode -eq 0) {
    if ($signalPolicyClear -eq "true" -and $missingRequirements.Count -eq 0) {
        $packetStatus = "READY_FOR_OPERATOR_PACKET_NOT_LIVE"
        $nextAction = "Attach this packet to a separate entry/filter operator review; this is not live policy approval."
    } else {
        $packetStatus = "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
        $nextAction = "Review governance drift, missed-opportunity rows, and no-buy classifications; keep EntryDedup/DataFreshness/live policy unchanged."
    }
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_FILTER_OPERATOR_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    sourceSmoke = "smoke_signal_correctness_ssh.ps1"
    executionMachineStatus = $executionStatus
    missingEvalOrOrderBug = $missingEvalOrOrderBug
    accuracySummary = $accuracySummary
    signalPolicyClear = $signalPolicyClear
    governanceMode = $governanceMode
    missedOpportunityStatus = $missedStatus
    suspiciousNoBuyCount = $suspiciousNoBuyCount
    falseBlockRiskCount = $falseBlockRiskCount
    highForwardReturnNoBuyCount = $highForwardReturnNoBuyCount
    dataFreshnessCurrentStatus = $dataFreshnessCurrentStatus
    dataFreshnessCurrentClean = $dataFreshnessCurrentClean
    noBuyClassifications = $classifications
    noBuyBlockerFamilies = $blockerFamilies
    entryDedupWouldAllowStagedAddGroups = $entryDedupWouldAllow
    dedupTooCoarseSuspects = $dedupTooCoarseSuspects
    signalPolicyReviewPlan = @($reviewPlan)
    requiredOperatorChecks = @(
        "row-level no-buy classifications reviewed",
        "governance drift reviewed without bypassing hard gates",
        "missed-opportunity rows classified before any bounded shadow/tiny-live experiment",
        "EntryDedup/DataFreshness/live policy unchanged unless separately approved"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only entry/filter operator review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[entry-filter-operator-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_signal_correctness_ssh.ps1"
Write-Host "source_smoke_exit_code=$($smoke.ExitCode)"
Write-Host "executionMachineStatus=$executionStatus"
Write-Host "missingEvalOrOrderBug=$missingEvalOrOrderBug"
Write-Host "signalPolicyClear=$signalPolicyClear"
Write-Host "governanceMode=$governanceMode"
Write-Host "missedOpportunityStatus=$missedStatus"
Write-Host "suspiciousNoBuyCount=$suspiciousNoBuyCount"
Write-Host "falseBlockRiskCount=$falseBlockRiskCount"
Write-Host "highForwardReturnNoBuyCount=$highForwardReturnNoBuyCount"
Write-Host "dataFreshnessCurrentStatus=$dataFreshnessCurrentStatus"
Write-Host "dataFreshnessCurrentClean=$($dataFreshnessCurrentClean.ToString().ToLowerInvariant())"
Write-Host "noBuyClassifications=$classifications"
Write-Host "noBuyBlockerFamilies=$blockerFamilies"
Write-Host "entryDedupWouldAllowStagedAddGroups=$entryDedupWouldAllow"
Write-Host "dedupTooCoarseSuspects=$dedupTooCoarseSuspects"
Write-Host ("entry_filter_operator_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_filter_operator_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "entry_filter_operator_packet_status=$packetStatus"
Write-Host "entry_filter_operator_packet_next_action=$nextAction"
Write-Host "notAuthorization=read-only entry/filter operator review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[entry-filter-operator-review-packet] read-only check complete"

if ($RequireReview -and $packetStatus -eq "NO_EVIDENCE") {
    throw "Entry/filter operator packet has no evidence: missing=$(@($missingRequirements) -join '; ')"
}
