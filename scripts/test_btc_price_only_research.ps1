Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Write-FixturePages {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][datetime]$StartUtc,
        [Parameter(Mandatory = $true)][int]$BarCount
    )
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $rows = [System.Collections.Generic.List[object]]::new()
    for ($i = $BarCount - 1; $i -ge 0; $i--) {
        $time = [datetime]::SpecifyKind($StartUtc.AddHours($i), [DateTimeKind]::Utc)
        $timestamp = [DateTimeOffset]::new($time).ToUnixTimeMilliseconds().ToString()
        $open = 10000.0 + ($i * 2.0)
        $close = $open + 1.0
        $rows.Add(@(
                $timestamp,
                $open.ToString("0.0", [System.Globalization.CultureInfo]::InvariantCulture),
                ($open + 10.0).ToString("0.0", [System.Globalization.CultureInfo]::InvariantCulture),
                ($open - 10.0).ToString("0.0", [System.Globalization.CultureInfo]::InvariantCulture),
                $close.ToString("0.0", [System.Globalization.CultureInfo]::InvariantCulture),
                "1.0",
                "10000.0",
                "10000.0",
                "1"))
    }
    $pageIndex = 1
    for ($offset = 0; $offset -lt $rows.Count; $offset += 300) {
        $length = [Math]::Min(300, $rows.Count - $offset)
        $pageRows = @()
        for ($i = 0; $i -lt $length; $i++) { $pageRows += ,$rows[$offset + $i] }
        $response = [ordered]@{ code = "0"; msg = ""; data = $pageRows }
        $path = Join-Path $Directory ("page-{0:D4}.json" -f $pageIndex)
        [System.IO.File]::WriteAllText(
            $path,
            ($response | ConvertTo-Json -Depth 6 -Compress),
            [System.Text.UTF8Encoding]::new($false))
        $pageIndex++
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target/btc-price-only-research-test"))
$repoTarget = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target")).TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar
Assert-True ($targetRoot.StartsWith($repoTarget, [System.StringComparison]::OrdinalIgnoreCase)) `
    "Test target must remain under repository target directory"
if (Test-Path -LiteralPath $targetRoot) { Remove-Item -LiteralPath $targetRoot -Recurse -Force }

try {
    $fixtureDirectory = Join-Path $targetRoot "fixture-pages"
    $datasetRoot = Join-Path $targetRoot "datasets"
    $start = [datetime]::Parse("2020-01-01T00:00:00Z").ToUniversalTime()
    $barCount = 800
    $end = $start.AddHours($barCount)
    Write-FixturePages -Directory $fixtureDirectory -StartUtc $start -BarCount $barCount

    $downloadOutput = @(& (Join-Path $PSScriptRoot "download_okx_btc_research_dataset.ps1") `
            -StartUtc $start -EndUtc $end -OutputRoot $datasetRoot `
            -SourcePageDirectory $fixtureDirectory -InterPageDelayMs 0)
    $download = $downloadOutput[-1] | ConvertFrom-Json
    Assert-True ([string]$download.status -eq "FIXTURE_DATASET_READY_NOT_EXTERNAL_EVIDENCE") `
        "Fixture dataset status mismatch"
    Assert-True ([int]$download.rowCount -eq $barCount) "Fixture row count mismatch"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$download.datasetBuilderSha256)) `
        "Dataset builder hash missing"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$download.analyzerSha256AtBuild)) `
        "Analyzer-at-build hash missing"

    $analysisOutput = @(& (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
            -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset)
    $analysis = $analysisOutput[-1] | ConvertFrom-Json
    Assert-True ([string]$analysis.status -eq "RESEARCH_COMPLETE") "Research did not complete"
    Assert-True ([string]$analysis.verdict -eq "REJECTED_NO_PRICE_ONLY_EDGE_NO_LIVE") `
        "Short fixture must not pass long-history gates"
    $report = Get-Content -Raw -LiteralPath ([string]$analysis.reportPath) | ConvertFrom-Json
    Assert-True (@($report.results).Count -eq 3) "Expected all three frozen candidates"
    $donchian = @($report.results | Where-Object { $_.candidateId -eq "BTC_DONCHIAN_20D_10D_V1" })[0]
    $volManaged = @($report.results | Where-Object { $_.candidateId -eq "BTC_VOL_MANAGED_LONG_V1" })[0]
    Assert-True ([string]$donchian.normal.status -eq "SIMULATED") "Donchian fixture simulation missing"
    Assert-True ([string]$volManaged.stress.status -eq "SIMULATED") "Vol-managed stress simulation missing"
    Assert-True (@($donchian.normal.signalLedger).Count -gt 0) "Donchian signal ledger missing"
    Assert-True (@($donchian.normal.orderLedger).Count -eq [int]$donchian.normal.orders) `
        "Donchian order ledger count mismatch"
    Assert-True ([bool]$donchian.normal.ledgerIntegrityPassed) "Donchian normal ledger integrity failed"
    Assert-True ([bool]$donchian.stress.ledgerIntegrityPassed) "Donchian stress ledger integrity failed"
    Assert-True ([string]$donchian.normal.foldEvaluationMode -eq
        "ISOLATED_FIXED_PARAMETER_FORWARD_FOLDS") "Fold isolation mode mismatch"
    Assert-True (@($donchian.normal.folds).Count -eq 5) "Expected five isolated folds"
    Assert-True (@($donchian.normal.folds | Where-Object {
                [string]$_.mode -ne "ISOLATED_FIXED_PARAMETER_FORWARD_FOLD"
            }).Count -eq 0) "A fold was not independently simulated"
    foreach ($integrityCheck in $donchian.normal.ledgerIntegrityChecks.PSObject.Properties) {
        Assert-True ([bool]$integrityCheck.Value) "Ledger integrity sub-check failed: $($integrityCheck.Name)"
    }
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$donchian.normal.signalLedgerSha256)) `
        "Donchian signal ledger hash missing"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$donchian.normal.orderLedgerSha256)) `
        "Donchian order ledger hash missing"
    foreach ($signal in @($donchian.normal.signalLedger)) {
        $availableAt = [DateTimeOffset]::Parse([string]$signal.signalAvailableAtUtc)
        $scheduledAt = [DateTimeOffset]::Parse([string]$signal.scheduledExecutionTimeUtc)
        Assert-True ($scheduledAt -ge $availableAt) "Signal scheduled before it was available"
    }
    $scheduledOrders = @($donchian.normal.orderLedger | Where-Object {
            [string]$_.reason -notin @("ATR_STOP", "FINAL_LIQUIDATION")
        })
    foreach ($order in $scheduledOrders) {
        $signal = @($donchian.normal.signalLedger | Where-Object {
                [string]$_.signalId -eq [string]$order.signalId
            })[0]
        Assert-True ([string]$order.executionTimeUtc -eq [string]$signal.scheduledExecutionTimeUtc) `
            "Scheduled order did not execute at its ledger time"
    }
    Assert-True (-not [bool]$donchian.gateChecks.minimumCompletedRoundTrips) `
        "Short fixture must fail the 30 completed round-trip gate"
    Assert-True (-not [bool]$report.safety.livePromotionAllowed) "Research must never authorize live"
    Assert-True ([string]$report.strategy508Baseline.status -eq
        "NOT_COMPARABLE_NO_CANONICAL_CAUSAL_SIGNAL_LEDGER") "508 baseline boundary missing"
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$report.deterministicResultSha256)) `
        "Deterministic result hash missing"
    Assert-True ([string]$report.dataset.analyzerSha256AtDatasetBuild -eq [string]$report.analyzerSha256) `
        "Fixture must bind the analyzer version used for the report"
    Assert-True ([bool]$report.dataset.analyzerVersionMatchesDatasetBuild) `
        "Analyzer version match flag missing"
    Assert-True ([bool]$report.dataset.manifestContractValidated) "Manifest contract was not validated"
    Assert-True ([bool]$report.dataset.rawCanonicalReconstructionMatched) `
        "Raw/canonical reconstruction evidence missing"

    $verificationOutput = @(& (Join-Path $PSScriptRoot "verify_btc_price_only_research_report.ps1") `
            -ReportPath ([string]$analysis.reportPath))
    $verification = $verificationOutput[-1] | ConvertFrom-Json
    Assert-True ([string]$verification.status -eq "REPORT_VERIFIED_READ_ONLY") `
        "Independent report verification failed"

    $repeatOutput = @(& (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
            -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
            -OutputPath (Join-Path $targetRoot "repeat-report.json"))
    $repeat = $repeatOutput[-1] | ConvertFrom-Json
    Assert-True ([string]$repeat.deterministicResultSha256 -eq [string]$analysis.deterministicResultSha256) `
        "Identical inputs must produce the same deterministic result hash"

    $tamperedReportPath = Join-Path $targetRoot "tampered-ledger-report.json"
    $tamperedReport = Get-Content -Raw -LiteralPath ([string]$analysis.reportPath) | ConvertFrom-Json
    $tamperedOrder = @($tamperedReport.results[1].normal.orderLedger)[0]
    $tamperedOrder.feeEquityUnits = [double]$tamperedOrder.feeEquityUnits + 1.0
    [System.IO.File]::WriteAllText(
        $tamperedReportPath,
        ($tamperedReport | ConvertTo-Json -Depth 30),
        [System.Text.UTF8Encoding]::new($false))
    $tamperedReportFailure = $null
    try {
        & (Join-Path $PSScriptRoot "verify_btc_price_only_research_report.ps1") `
            -ReportPath $tamperedReportPath | Out-Null
    }
    catch {
        $tamperedReportFailure = $_.Exception.Message
    }
    Assert-True ($tamperedReportFailure -match "ledger hash mismatch|Per-order fee") `
        "Independent verifier must reject a modified fee ledger"

    $alteredPolicyPath = Join-Path $targetRoot "altered-policy.json"
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "btc_price_only_research_policy.json") `
        -Destination $alteredPolicyPath
    Add-Content -LiteralPath $alteredPolicyPath -Value " "
    $policyHashFailure = $null
    try {
        & (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
            -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
            -ResearchPolicyPath $alteredPolicyPath `
            -OutputPath (Join-Path $targetRoot "altered-policy-report.json") | Out-Null
    }
    catch {
        $policyHashFailure = $_.Exception.Message
    }
    Assert-True ($policyHashFailure -match "Research policy hash mismatch") `
        "Altered research policy must fail hash verification"

    $manifest = Get-Content -Raw -LiteralPath ([string]$download.manifestPath) | ConvertFrom-Json
    $firstRawPagePath = Join-Path ([string]$download.datasetDirectory) ([string]@($manifest.rawPages)[0].file)
    $firstRawPageBytes = [System.IO.File]::ReadAllBytes($firstRawPagePath)
    try {
        $tamperedRawBytes = New-Object byte[] ($firstRawPageBytes.Length + 1)
        [Array]::Copy($firstRawPageBytes, $tamperedRawBytes, $firstRawPageBytes.Length)
        $tamperedRawBytes[-1] = 10
        [System.IO.File]::WriteAllBytes($firstRawPagePath, $tamperedRawBytes)
        $rawHashFailure = $null
        try {
            & (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
                -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
                -OutputPath (Join-Path $targetRoot "tampered-raw-report.json") | Out-Null
        }
        catch {
            $rawHashFailure = $_.Exception.Message
        }
    Assert-True ($rawHashFailure -match "Raw page hash mismatch") `
        "Tampered raw page must fail hash verification"
    }
    finally {
        [System.IO.File]::WriteAllBytes($firstRawPagePath, $firstRawPageBytes)
    }

    $manifestBytes = [System.IO.File]::ReadAllBytes([string]$download.manifestPath)
    try {
        $manifestObject = Get-Content -Raw -LiteralPath ([string]$download.manifestPath) | ConvertFrom-Json
        $manifestObject.request.instrument = "ETH-USDT"
        [System.IO.File]::WriteAllText(
            [string]$download.manifestPath,
            ($manifestObject | ConvertTo-Json -Depth 20),
            [System.Text.UTF8Encoding]::new($false))
        $manifestContractFailure = $null
        try {
            & (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
                -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
                -OutputPath (Join-Path $targetRoot "altered-manifest-report.json") | Out-Null
        }
        catch {
            $manifestContractFailure = $_.Exception.Message
        }
        Assert-True ($manifestContractFailure -match "Dataset manifest contract violation") `
            "Wrong manifest instrument must fail the dataset contract"
    }
    finally {
        [System.IO.File]::WriteAllBytes([string]$download.manifestPath, $manifestBytes)
    }

    $canonicalBytes = [System.IO.File]::ReadAllBytes([string]$download.canonicalCsvPath)
    $manifestBytes = [System.IO.File]::ReadAllBytes([string]$download.manifestPath)
    try {
        $canonicalText = [System.Text.Encoding]::UTF8.GetString($canonicalBytes)
        $tamperedCanonicalText = [regex]::Replace(
            $canonicalText,
            ',"1\.0","1","okx_spot"',
            ',"2.0","1","okx_spot"',
            1)
        Assert-True ($tamperedCanonicalText -ne $canonicalText) "Fixture canonical mutation pattern missing"
        [System.IO.File]::WriteAllText(
            [string]$download.canonicalCsvPath,
            $tamperedCanonicalText,
            [System.Text.UTF8Encoding]::new($false))
        $manifestObject = Get-Content -Raw -LiteralPath ([string]$download.manifestPath) | ConvertFrom-Json
        $manifestObject.canonical.sha256 = (Get-FileHash -Algorithm SHA256 `
                -LiteralPath ([string]$download.canonicalCsvPath)).Hash.ToLowerInvariant()
        [System.IO.File]::WriteAllText(
            [string]$download.manifestPath,
            ($manifestObject | ConvertTo-Json -Depth 20),
            [System.Text.UTF8Encoding]::new($false))
        $reconstructionFailure = $null
        try {
            & (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
                -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
                -OutputPath (Join-Path $targetRoot "raw-canonical-mismatch-report.json") | Out-Null
        }
        catch {
            $reconstructionFailure = $_.Exception.Message
        }
        Assert-True ($reconstructionFailure -match "Raw/canonical reconstruction mismatch") `
            "Raw reconstruction must catch a canonical edit even when manifest hash is updated"
    }
    finally {
        [System.IO.File]::WriteAllBytes([string]$download.canonicalCsvPath, $canonicalBytes)
        [System.IO.File]::WriteAllBytes([string]$download.manifestPath, $manifestBytes)
    }

    Add-Content -LiteralPath ([string]$download.canonicalCsvPath) -Value "tampered"
    $hashFailure = $null
    try {
        & (Join-Path $PSScriptRoot "analyze_btc_price_only_candidates.ps1") `
            -DatasetDirectory ([string]$download.datasetDirectory) -AllowFixtureDataset `
            -OutputPath (Join-Path $targetRoot "tampered-report.json") | Out-Null
    }
    catch {
        $hashFailure = $_.Exception.Message
    }
    Assert-True ($hashFailure -match "Canonical CSV hash mismatch") `
        "Tampered canonical CSV must fail hash verification"

    Write-Host "[btc-price-only-research-test] OK"
}
finally {
    if (Test-Path -LiteralPath $targetRoot) {
        $resolved = [System.IO.Path]::GetFullPath($targetRoot)
        Assert-True ($resolved.StartsWith($repoTarget, [System.StringComparison]::OrdinalIgnoreCase)) `
            "Refusing to remove test data outside repository target"
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
