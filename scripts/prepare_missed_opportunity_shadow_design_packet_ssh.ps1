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
        throw "$Name contains unsupported characters for missed-opportunity shadow packet arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
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

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
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
        throw "Unable to find powershell or pwsh for missed-opportunity shadow design packet."
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

$reviewArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
)
$review = Invoke-ReadOnlyScript -ScriptName "prepare_no_buy_row_review_packet_ssh.ps1" -Arguments $reviewArgs
$text = $review.Text

$reviewPacketJson = Get-LastPrefixedValue -Text $text -Prefix "no_buy_row_review_packet="
$reviewPacketStatus = Get-LastPrefixedValue -Text $text -Prefix "no_buy_row_review_packet_status="
$reviewMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $text -Prefix "no_buy_row_review_packet_missing_requirements=")
$reviewPacket = Convert-JsonObjectOrNull -Value $reviewPacketJson

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($item in @($reviewMissing)) {
    Add-MissingRequirement -List $missingRequirements -Value ([string]$item)
}
if ($review.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "no-buy row review packet completed"
}
if ([string]::IsNullOrWhiteSpace($reviewPacketJson)) {
    Add-MissingRequirement -List $missingRequirements -Value "no_buy_row_review_packet present"
}
if ($null -eq $reviewPacket) {
    Add-MissingRequirement -List $missingRequirements -Value "no_buy_row_review_packet valid JSON"
}
if ([string]::IsNullOrWhiteSpace($reviewPacketStatus)) {
    Add-MissingRequirement -List $missingRequirements -Value "no_buy_row_review_packet_status present"
}

$missedRows = @()
$waitRows = @()
$hardSafetyRows = @()
if ($null -ne $reviewPacket) {
    foreach ($row in @($reviewPacket.rowActions)) {
        if ($null -eq $row) {
            continue
        }
        if ($row.actionFamily -eq "MISSED_OPPORTUNITY_REVIEW") {
            $missedRows += $row
        } elseif ($row.actionFamily -eq "WAIT_FOR_SIGNAL_CONFIRMATION") {
            $waitRows += $row
        } elseif ($row.actionFamily -eq "KEEP_HARD_SAFETY") {
            $hardSafetyRows += $row
        }
    }
}

if ($missedRows.Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "at least one MISSED_OPPORTUNITY_REVIEW row"
}
if ($hardSafetyRows.Count -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "no hard-safety row in missed-opportunity shadow candidate set"
}
if ($null -ne $reviewPacket -and [string]$reviewPacket.dataFreshnessCurrentClean -ne "True" -and [string]$reviewPacket.dataFreshnessCurrentClean -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness current snapshot clean"
}
if ($null -ne $reviewPacket -and [string]$reviewPacket.missingEvalOrOrderBug -ne "no") {
    Add-MissingRequirement -List $missingRequirements -Value "no missed evaluation/order bug"
}
if ($null -ne $reviewPacket -and [string]$reviewPacket.entryDedupWouldAllowStagedAddGroups -ne "0") {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup staged-add relaxation not mixed into missed-opportunity shadow design"
}

$shadowDesignAllowed = $false
$packetStatus = "NO_EVIDENCE"
$nextAction = "Fix no-buy row review evidence before drafting any missed-opportunity shadow design."
if ($review.ExitCode -eq 0 -and $null -ne $reviewPacket -and $missedRows.Count -gt 0) {
    if ($reviewPacketStatus -eq "READY_FOR_SHADOW_DESIGN_NOT_LIVE" -and $missingRequirements.Count -eq 0) {
        $packetStatus = "READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE"
        $shadowDesignAllowed = $true
        $nextAction = "Draft a separate shadow-only experiment using the MISSED_OPPORTUNITY_REVIEW rows; keep tiny-live/live execution disabled."
    } else {
        $packetStatus = "BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED"
        $nextAction = "Use the row evidence for operator review only; resolve governance drift and missed-opportunity regression before shadow or tiny-live experiment design."
    }
}

$candidateRows = @()
foreach ($row in $missedRows) {
    $candidateRows += [pscustomobject]@{
        path = $row.path
        classification = $row.classification
        topBlocker = $row.topBlocker
        actionFamily = $row.actionFamily
        proposedShadowOnlyQuestion = "Would a shadow-only pass of this path have survived all write-path gates and improved forward outcome without weakening hard safety?"
        requiredEvidence = @(
            "row remains MISSED_OPPORTUNITY_REVIEW in fresh no-buy packet",
            "signalPolicyClear=true before any experiment design can be marked ready",
            "governanceMode not TOO_STRICT/TOO_LOOSE/INSUFFICIENT_DATA",
            "missedOpportunityStatus=PASS",
            "live execution, tiny-live order, EntryDedup/DataFreshness policy unchanged"
        )
        nextAction = $row.nextAction
        warnings = $row.warnings
    }
}

$packet = [pscustomobject]@{
    packetType = "MISSED_OPPORTUNITY_SHADOW_DESIGN_PREFLIGHT"
    status = $packetStatus
    symbol = $Symbol
    sourcePacket = "prepare_no_buy_row_review_packet_ssh.ps1"
    sourcePacketStatus = $reviewPacketStatus
    executionMachineStatus = if ($null -ne $reviewPacket) { $reviewPacket.executionMachineStatus } else { "N/A" }
    signalPolicyClear = if ($null -ne $reviewPacket) { $reviewPacket.signalPolicyClear } else { "N/A" }
    governanceMode = if ($null -ne $reviewPacket) { $reviewPacket.governanceMode } else { "N/A" }
    missedOpportunityStatus = if ($null -ne $reviewPacket) { $reviewPacket.missedOpportunityStatus } else { "N/A" }
    suspiciousNoBuyCount = if ($null -ne $reviewPacket) { $reviewPacket.suspiciousNoBuyCount } else { "N/A" }
    falseBlockRiskCount = if ($null -ne $reviewPacket) { $reviewPacket.falseBlockRiskCount } else { "N/A" }
    highForwardReturnNoBuyCount = if ($null -ne $reviewPacket) { $reviewPacket.highForwardReturnNoBuyCount } else { "N/A" }
    candidateMissedOpportunityRows = $candidateRows
    waitForSignalConfirmationRows = @($waitRows)
    hardSafetyRows = @($hardSafetyRows)
    shadowDesignReviewAllowed = $shadowDesignAllowed
    tinyLiveOrderAllowed = $false
    livePolicyChangeAllowed = $false
    missingRequirements = @($missingRequirements)
    requiredOperatorChecks = @(
        "confirm MISSED_OPPORTUNITY_REVIEW row is not a hard-safety block",
        "confirm WAIT_FOR_SIGNAL_CONFIRMATION rows are observation only",
        "confirm no EntryDedup/DataFreshness/live policy relaxation is bundled",
        "confirm any follow-up is shadow-only until separate live approval"
    )
    nextAction = $nextAction
    notAuthorization = "read-only missed-opportunity shadow design preflight only; does not authorize live trading, tiny-live order execution, scheduler enablement, EntryDedup/DataFreshness/live policy relaxation, order/OCO/grid/fund/Earn/Telegram/exchange mutation, DB changes, deploy, restart, production env mutation, external backfill/import, or strategy/filter changes"
}

Write-Host "[missed-opportunity-shadow-design-packet] read-only preflight"
Write-Host "scope=READ_ONLY; runs prepare_no_buy_row_review_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_no_buy_row_review_packet_ssh.ps1"
Write-Host "source_packet_exit_code=$($review.ExitCode)"
Write-Host "source_packet_status=$reviewPacketStatus"
Write-Host "symbol=$Symbol"
Write-Host "signalPolicyClear=$($packet.signalPolicyClear)"
Write-Host "governanceMode=$($packet.governanceMode)"
Write-Host "missedOpportunityStatus=$($packet.missedOpportunityStatus)"
Write-Host "suspiciousNoBuyCount=$($packet.suspiciousNoBuyCount)"
Write-Host "falseBlockRiskCount=$($packet.falseBlockRiskCount)"
Write-Host "highForwardReturnNoBuyCount=$($packet.highForwardReturnNoBuyCount)"
Write-Host "candidateMissedOpportunityRowCount=$($missedRows.Count)"
Write-Host "waitForSignalConfirmationRowCount=$($waitRows.Count)"
Write-Host "hardSafetyRowCount=$($hardSafetyRows.Count)"
Write-Host "shadow_design_review_allowed=$($shadowDesignAllowed.ToString().ToLowerInvariant())"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host ("missed_opportunity_shadow_design_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("missed_opportunity_shadow_design_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "missed_opportunity_shadow_design_packet_status=$packetStatus"
Write-Host "missed_opportunity_shadow_design_next_action=$nextAction"
Write-Host "notAuthorization=read-only missed-opportunity shadow design preflight only; does not deploy, restart, reload nginx, change production env, enable live trading, execute tiny-live orders, relax EntryDedup/DataFreshness/live policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[missed-opportunity-shadow-design-packet] read-only check complete"

if ($RequireReview -and $packetStatus -eq "NO_EVIDENCE") {
    throw "Missed-opportunity shadow design packet has no evidence: missing=$(@($missingRequirements) -join '; ')"
}
