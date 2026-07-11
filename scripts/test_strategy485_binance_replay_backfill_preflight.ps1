Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsBeforeSsh {
    param([string[]]$Arguments, [string]$ExpectedPattern)

    $script = Join-Path $PSScriptRoot "prepare_strategy485_binance_replay_backfill_preflight_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy 485 Binance replay preflight test"
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
        throw "Strategy 485 Binance replay preflight accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "Strategy 485 Binance replay preflight did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|SSH_CONNECT_FAILED|SSH_AUTH_FAILED") {
        throw "Strategy 485 Binance replay preflight reached SSH before local input guard:`n$text"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_strategy485_binance_replay_backfill_preflight_ssh.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "[strategy485-binance-replay-backfill-preflight] read-only evidence",
        "READ_ONLY_PRODUCTION_DB_AND_BINANCE_VISION",
        "SELECT DATE_FORMAT(open_time",
        "idx_md_kline_sym_int_src_open",
        "https://data-api.binance.vision/api/v3/klines",
        "expectedBars",
        "visionSha256",
        "productionDbSha256",
        "overlapDbSha256",
        "overlapVisionSha256",
        "overlapMatchCount",
        "overlapMismatchCount",
        "overlapMismatchFirstBarUtc",
        "overlapMismatchLastBarUtc",
        "overlapMismatchFieldCounts",
        "https://api.binance.us/api/v3/klines",
        "legacySourceDispositionStatus",
        "legacyBinanceUsMatchCount",
        "sourceColumnMaxLength",
        "sourceRelabelTarget",
        "sourceRelabelExistingTargetRows",
        "sourceRelabelEvidenceOk",
        "sourceRelabelPlan",
        "READY_FOR_SEPARATE_SOURCE_RELABEL_AND_BACKFILL_AUTHORIZATION_NOT_MUTATION",
        "postRelabelMissingBars",
        "postRelabelPlannedCalls",
        "sourceRelabelMutationAllowed",
        "externalBackfillImportAllowed",
        "source_relabel_mutation_allowed=false",
        "missingBars",
        "INSERT_MISSING_ONLY",
        "replaceExisting",
        "MAX_RANGE_DAYS = 730",
        "plannedCallCount",
        "plannedCalls",
        "backfillBinanceKlinesRange",
        "READY_FOR_SEPARATE_EXTERNAL_BACKFILL_AUTHORIZATION",
        "BLOCKED_BINANCE_VISION_COVERAGE",
        "BLOCKED_EXISTING_BINANCE_DATA_MISMATCH",
        "BLOCKED_RUNTIME_SAFETY_FLAGS",
        "REPLAY_HISTORY_ALREADY_COMPLETE",
        "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
        "BTC_BASE_DRY_RUN",
        "liveOrderEnabled",
        "productionMutationPerformed",
        "external_backfill_import_allowed=false",
        "authorizationRequired",
        "requiredAuthorizationText",
        "notAuthorization",
        "RequireReady",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "read-only check complete"
    )) {
    Assert-Contains -Name "strategy 485 Binance replay preflight" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match '(?im)^\s*(INSERT|UPDATE|DELETE|REPLACE|CREATE|ALTER|DROP|TRUNCATE)\s+') {
    throw "Strategy 485 Binance replay preflight contains a SQL mutation statement"
}
if ($scriptText -match '"method"\s*:\s*"tools/call"') {
    throw "Strategy 485 Binance replay preflight must not call any MCP write surface"
}
foreach ($forbidden in @("systemctl", "git pull", "git reset", "kubectl", "docker compose")) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "Strategy 485 Binance replay preflight contains forbidden mutation command: $forbidden"
    }
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h") `
    -ExpectedPattern "requires IntervalCode=1d"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayStartUtc", "2026-01-01T00:00:00Z", "-EndExclusiveUtc", "2026-02-01T00:00:00Z") `
    -ExpectedPattern "Replay range must be between 365 and 5000 days"

Write-Host "[strategy485-binance-replay-backfill-preflight-test] OK"
