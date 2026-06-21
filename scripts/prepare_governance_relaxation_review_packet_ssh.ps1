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
        throw "$Name contains unsupported characters for governance relaxation packet arguments."
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
        throw "Unable to find powershell or pwsh for governance relaxation review packet."
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
$signalPolicyClear = Get-RegexValue -Text $text -Pattern "signalPolicyClear=([^\r\n]+)" -Default "N/A"
$governanceMode = Get-RegexValue -Text $text -Pattern "7d Governance Drift:\s*[\r\n]+\s*governanceMode=([^\r\n]+)" -Default "N/A"
$missedStatus = Get-RegexValue -Text $text -Pattern "Missed Opportunity Regression:\s*[\r\n]+\s*overallStatus=([A-Z_]+)" -Default "N/A"
$suspiciousNoBuyCount = Get-RegexValue -Text $text -Pattern "suspiciousNoBuyCount=([0-9]+)" -Default "0"
$falseBlockRiskCount = Get-RegexValue -Text $text -Pattern "falseBlockRiskCount=([0-9]+)" -Default "0"
$highForwardReturnNoBuyCount = Get-RegexValue -Text $text -Pattern "highForwardReturnNoBuyCount=([0-9]+)" -Default "0"
$missingSignalFields = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "missing_signal_policy_fields=(\[[^\r\n]*\])" -Default "[]")
$reviewPlan = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $text -Pattern "signal_policy_review_plan=(\[[^\r\n]*\])" -Default "[]")
$classifications = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=([^\r\n]+)" -Default "N/A"
$blockerFamilies = Get-RegexValue -Text $text -Pattern "No-Buy Row Classification:\s*[\r\n]+\s*classifications=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$highReturnStrategies = Get-RegexValue -Text $text -Pattern "High-Return No-Buy Breakdown:\s*[\r\n]+\s*strategies=([^\r\n]+)" -Default "N/A"
$highReturnBlockerFamilies = Get-RegexValue -Text $text -Pattern "High-Return No-Buy Breakdown:\s*[\r\n]+\s*strategies=[^\r\n]+[\r\n]+\s*blockerFamilies=([^\r\n]+)" -Default "N/A"
$dataFreshnessCurrentClean = (($text -match "staleNowKeys=0") -and ($text -match "noDataNowKeys=0") -and ($text -match "queryFailedNowKeys=0"))

$relaxationRegex = [regex]::new("^\s*-\s+blocker=(?<blocker>\S+)(?<detail>.*)$", [System.Text.RegularExpressions.RegexOptions]::Multiline)
$relaxationCandidates = [System.Collections.Generic.List[object]]::new()
foreach ($match in $relaxationRegex.Matches($text)) {
    $relaxationCandidates.Add([pscustomobject]@{
        blocker = $match.Groups["blocker"].Value.Trim()
        detail = $match.Groups["detail"].Value.Trim()
        requiredEvidence = @(
            "candidate remains present in fresh governance relaxation scan",
            "missed-opportunity regression is PASS",
            "row-level no-buy evidence reviewed",
            "hard gates and write-path preflight remain unchanged"
        )
        notAuthorization = "read-only governance relaxation evidence only; does not authorize policy relaxation or live mutation"
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
if (@($missingSignalFields).Count -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "missing_signal_policy_fields=[]"
}
if (@($reviewPlan).Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "signal_policy_review_plan present"
}
if (-not $dataFreshnessCurrentClean) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness current snapshot clean"
}
if ($relaxationCandidates.Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "governance relaxation candidates present"
}
if ($signalPolicyClear -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "signalPolicyClear=true before governance relaxation review can be marked ready"
}
if ($governanceMode -eq "TOO_STRICT" -or $governanceMode -eq "TOO_LOOSE" -or $governanceMode -eq "INSUFFICIENT_DATA" -or $governanceMode -eq "N/A") {
    Add-MissingRequirement -List $missingRequirements -Value "governance drift resolved"
}
if ($missedStatus -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "missed-opportunity regression PASS"
}

$packetStatus = "NO_EVIDENCE"
$shadowReviewAllowed = $false
$nextAction = "Fix read-only governance relaxation evidence collection before drafting a review packet."
if ($smoke.ExitCode -eq 0 -and $relaxationCandidates.Count -gt 0) {
    if ($missingRequirements.Count -eq 0) {
        $packetStatus = "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE"
        $shadowReviewAllowed = $true
        $nextAction = "Attach this packet to a separate shadow-only governance relaxation review; this is not live policy approval."
    } else {
        $packetStatus = "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
        $nextAction = "Review governance relaxation candidates with missed-opportunity/no-buy evidence; keep all live policy and hard gates unchanged."
    }
}

$packet = [pscustomobject]@{
    packetType = "GOVERNANCE_RELAXATION_REVIEW"
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
    dataFreshnessCurrentClean = $dataFreshnessCurrentClean
    noBuyClassifications = $classifications
    noBuyBlockerFamilies = $blockerFamilies
    highReturnStrategies = $highReturnStrategies
    highReturnBlockerFamilies = $highReturnBlockerFamilies
    relaxationCandidateCount = $relaxationCandidates.Count
    relaxationCandidates = @($relaxationCandidates)
    signalPolicyReviewPlan = @($reviewPlan)
    shadowGovernanceReviewAllowed = $shadowReviewAllowed
    livePolicyChangeAllowed = $false
    tinyLiveOrderAllowed = $false
    missingRequirements = @($missingRequirements)
    requiredOperatorChecks = @(
        "confirm relaxation candidates are not hard-safety bypasses",
        "confirm missed-opportunity/no-buy rows are reviewed",
        "confirm EntryDedup/DataFreshness/live policy remains unchanged",
        "confirm any follow-up is shadow-only until separate live approval"
    )
    nextAction = $nextAction
    notAuthorization = "read-only governance relaxation review packet only; does not authorize live trading, tiny-live order execution, scheduler enablement, EntryDedup/DataFreshness/live policy relaxation, order/OCO/grid/fund/Earn/Telegram/exchange mutation, DB changes, deploy, restart, production env mutation, external backfill/import, or strategy/filter changes"
}

Write-Host "[governance-relaxation-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_signal_correctness_ssh.ps1"
Write-Host "source_governance_tool=findGovernanceRelaxationCandidates"
Write-Host "source_smoke_exit_code=$($smoke.ExitCode)"
Write-Host "executionMachineStatus=$executionStatus"
Write-Host "missingEvalOrOrderBug=$missingEvalOrOrderBug"
Write-Host "signalPolicyClear=$signalPolicyClear"
Write-Host "governanceMode=$governanceMode"
Write-Host "missedOpportunityStatus=$missedStatus"
Write-Host "suspiciousNoBuyCount=$suspiciousNoBuyCount"
Write-Host "falseBlockRiskCount=$falseBlockRiskCount"
Write-Host "highForwardReturnNoBuyCount=$highForwardReturnNoBuyCount"
Write-Host "dataFreshnessCurrentClean=$($dataFreshnessCurrentClean.ToString().ToLowerInvariant())"
Write-Host "noBuyClassifications=$classifications"
Write-Host "noBuyBlockerFamilies=$blockerFamilies"
Write-Host "highReturnStrategies=$highReturnStrategies"
Write-Host "highReturnBlockerFamilies=$highReturnBlockerFamilies"
Write-Host "relaxationCandidateCount=$($relaxationCandidates.Count)"
Write-Host "shadow_governance_review_allowed=$($shadowReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host ("governance_relaxation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("governance_relaxation_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "governance_relaxation_review_packet_status=$packetStatus"
Write-Host "governance_relaxation_review_next_action=$nextAction"
Write-Host "notAuthorization=read-only governance relaxation review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, execute tiny-live orders, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[governance-relaxation-review-packet] read-only check complete"

if ($RequireReview -and $packetStatus -eq "NO_EVIDENCE") {
    throw "Governance relaxation review packet has no evidence: missing=$(@($missingRequirements) -join '; ')"
}
