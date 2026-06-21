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
        throw "$Name contains unsupported characters for no-buy row review packet arguments."
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

function Get-ActionFamily {
    param([string]$Classification, [string]$TopBlocker, [string]$Action)
    $text = (($Classification, $TopBlocker, $Action) -join " ").ToUpperInvariant()
    if ($text -match "DATA_FRESHNESS|STALE|NO_DATA|QUERY_FAILED") {
        return "DATA_QUALITY_REVIEW"
    }
    if ($text -match "DEDUP|DUPLICATE|STAGED") {
        return "ENTRY_DEDUP_REVIEW"
    }
    if ($text -match "MISSED_OPPORTUNITY|FALSE_BLOCK|HIGH_RETURN") {
        return "MISSED_OPPORTUNITY_REVIEW"
    }
    if ($text -match "WATCH_SIGNAL_NEAR_BUY|SIGNAL_NOT_READY|NO_CURRENT_BUY|HOLD|FORMING|SCORE_BUY") {
        return "WAIT_FOR_SIGNAL_CONFIRMATION"
    }
    if ($text -match "BUDGET|NOTIONAL|CAPACITY|DAILY|CAP") {
        return "BUDGET_CAPACITY_REVIEW"
    }
    if ($text -match "HARD_SAFETY|OCO|RISK|MAX_LOSS|SL|STOP") {
        return "KEEP_HARD_SAFETY"
    }
    return "MANUAL_REVIEW"
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
        throw "Unable to find powershell or pwsh for no-buy row review packet."
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
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
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
$text = $smoke.Text

$executionStatus = Get-RegexValue -Text $text -Pattern "executionMachineStatus=([^\r\n]+)" -Default "N/A"
$missingEvalOrOrderBug = Get-RegexValue -Text $text -Pattern "missingEvalOrOrderBug=([^\r\n]+)" -Default "N/A"
$governanceMode = Get-RegexValue -Text $text -Pattern "7d Governance Drift:\s*[\r\n]+\s*governanceMode=([^\r\n]+)" -Default "N/A"
$missedStatus = Get-RegexValue -Text $text -Pattern "Missed Opportunity Regression:\s*[\r\n]+\s*overallStatus=([A-Z_]+)" -Default "N/A"
$signalPolicyClear = Get-RegexValue -Text $text -Pattern "signalPolicyClear=([^\r\n]+)" -Default "N/A"
$suspiciousNoBuyCount = Get-RegexValue -Text $text -Pattern "suspiciousNoBuyCount=([0-9]+)" -Default "0"
$falseBlockRiskCount = Get-RegexValue -Text $text -Pattern "falseBlockRiskCount=([0-9]+)" -Default "0"
$highForwardReturnNoBuyCount = Get-RegexValue -Text $text -Pattern "highForwardReturnNoBuyCount=([0-9]+)" -Default "0"
$missingSignalFields = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "missing_signal_policy_fields=(\[[^\r\n]*\])" -Default "[]")
$reviewPlan = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "signal_policy_review_plan=(\[[^\r\n]*\])" -Default "[]")
$classifications = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=([^\r\n]+)" -Default "N/A"
$blockerFamilies = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$highReturnStrategies = Get-RegexValue -Text $text -Pattern "High-Return No-Buy Breakdown:\s*[\r\n]+\s*strategies=([^\r\n]+)" -Default "N/A"
$highReturnBlockerFamilies = Get-RegexValue -Text $text -Pattern "High-Return No-Buy Breakdown:\s*[\r\n]+\s*strategies=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$truthClassifications = Get-RegexValue -Text $text -Pattern "No-Buy Reason Truth Table:\s*[\r\n]+\s*classifications=([^\r\n]+)" -Default "N/A"
$truthBlockerFamilies = Get-RegexValue -Text $text -Pattern "No-Buy Reason Truth Table:\s*[\r\n]+\s*classifications=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$dataFreshnessCurrentStatus = Get-RegexValue -Text $text -Pattern "dataFreshnessCurrentStatus=([A-Z_]+)" -Default "N/A"
$dataFreshnessCurrentClean = ($dataFreshnessCurrentStatus -eq "CLEAN")
$entryDedupWouldAllow = Get-RegexValue -Text $text -Pattern "wouldAllowStagedAddGroups=([0-9]+)" -Default "0"
$dedupTooCoarseSuspects = Get-RegexValue -Text $text -Pattern "dedupTooCoarseSuspects=([0-9]+)" -Default "0"

$rowRegex = [regex]::new("^\s*-\s+path=(?<path>\S+)\s+classification=(?<classification>\S+)\s+topBlocker=(?<topBlocker>.*?)\s+action=(?<action>.*?)(?:\s+warnings=(?<warnings>.*))?$", [System.Text.RegularExpressions.RegexOptions]::Multiline)
$rows = [System.Collections.Generic.List[object]]::new()
$actionFamilyCounts = @{}
foreach ($match in $rowRegex.Matches($text)) {
    $classification = $match.Groups["classification"].Value.Trim()
    $topBlocker = $match.Groups["topBlocker"].Value.Trim()
    $action = $match.Groups["action"].Value.Trim()
    $warnings = $match.Groups["warnings"].Value.Trim()
    $family = Get-ActionFamily -Classification $classification -TopBlocker $topBlocker -Action $action
    if (-not $actionFamilyCounts.ContainsKey($family)) {
        $actionFamilyCounts[$family] = 0
    }
    $actionFamilyCounts[$family] = [int]$actionFamilyCounts[$family] + 1
    $rows.Add([pscustomobject]@{
        path = $match.Groups["path"].Value.Trim()
        classification = $classification
        topBlocker = $topBlocker
        actionFamily = $family
        nextAction = $action
        warnings = $warnings
    })
}

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
if ($rows.Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "rowActions present"
}
if ($signalPolicyClear -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "signalPolicyClear=true before any no-buy experiment"
}
if ($governanceMode -eq "TOO_STRICT" -or $governanceMode -eq "TOO_LOOSE" -or $governanceMode -eq "INSUFFICIENT_DATA" -or $governanceMode -eq "N/A") {
    Add-MissingRequirement -List $missingRequirements -Value "governance drift reviewed"
}
if ($missedStatus -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "missed-opportunity regression PASS"
}

$packetStatus = "NO_EVIDENCE"
$nextAction = "Fix read-only signal/no-buy row evidence collection before drafting a no-buy row review packet."
if ($smoke.ExitCode -eq 0 -and $rows.Count -gt 0) {
    if ($signalPolicyClear -eq "true" -and $missingRequirements.Count -eq 0) {
        $packetStatus = "READY_FOR_SHADOW_DESIGN_NOT_LIVE"
        $nextAction = "Use row-level families to draft a bounded shadow experiment; this is not live execution approval."
    } else {
        $packetStatus = "REVIEW_REQUIRED_NOT_EXPERIMENT"
        $nextAction = "Classify no-buy row families and resolve governance/missed-opportunity blockers before any shadow or tiny-live experiment."
    }
}

$orderedFamilies = @()
foreach ($key in ($actionFamilyCounts.Keys | Sort-Object)) {
    $orderedFamilies += [pscustomobject]@{
        family = $key
        count = [int]$actionFamilyCounts[$key]
    }
}

$packet = [pscustomobject]@{
    packetType = "NO_BUY_ROW_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    sourceSmoke = "smoke_signal_correctness_ssh.ps1"
    executionMachineStatus = $executionStatus
    missingEvalOrOrderBug = $missingEvalOrOrderBug
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
    highReturnStrategies = $highReturnStrategies
    highReturnBlockerFamilies = $highReturnBlockerFamilies
    truthTableClassifications = $truthClassifications
    truthTableBlockerFamilies = $truthBlockerFamilies
    entryDedupWouldAllowStagedAddGroups = $entryDedupWouldAllow
    dedupTooCoarseSuspects = $dedupTooCoarseSuspects
    rowActionFamilyCounts = $orderedFamilies
    rowActions = @($rows)
    signalPolicyReviewPlan = @($reviewPlan)
    missingRequirements = @($missingRequirements)
    requiredOperatorChecks = @(
        "confirm each MISSED_OPPORTUNITY_REVIEW row is not a hard-safety block",
        "confirm WAIT_FOR_SIGNAL_CONFIRMATION rows are not threshold-relaxation approval",
        "confirm BUDGET_CAPACITY_REVIEW rows have bounded sizing evidence before any experiment",
        "confirm EntryDedup/DataFreshness/live policy remains unchanged unless separately approved"
    )
    nextAction = $nextAction
    notAuthorization = "read-only no-buy row review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[no-buy-row-review-packet] read-only packet"
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
Write-Host "highReturnStrategies=$highReturnStrategies"
Write-Host "highReturnBlockerFamilies=$highReturnBlockerFamilies"
Write-Host "truthTableClassifications=$truthClassifications"
Write-Host "truthTableBlockerFamilies=$truthBlockerFamilies"
Write-Host "entryDedupWouldAllowStagedAddGroups=$entryDedupWouldAllow"
Write-Host "dedupTooCoarseSuspects=$dedupTooCoarseSuspects"
Write-Host ("no_buy_row_action_family_counts=" + (ConvertTo-Json -Compress -Depth 5 $orderedFamilies))
Write-Host ("no_buy_row_review_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("no_buy_row_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "no_buy_row_review_packet_status=$packetStatus"
Write-Host "no_buy_row_review_packet_next_action=$nextAction"
Write-Host "notAuthorization=read-only no-buy row review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[no-buy-row-review-packet] read-only check complete"

if ($RequireReview -and $packetStatus -eq "NO_EVIDENCE") {
    throw "No-buy row review packet has no evidence: missing=$(@($missingRequirements) -join '; ')"
}
