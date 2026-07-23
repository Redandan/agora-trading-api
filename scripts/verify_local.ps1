Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:VerifyLocalFileCache = @{}

function Invoke-Rg {
    param(
        [string]$Pattern,
        [string[]]$Paths
    )

    $allLeafPaths = $true
    foreach ($path in $Paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $allLeafPaths = $false
            break
        }
    }

    if ($allLeafPaths) {
        $matchLines = @()
        foreach ($path in $Paths) {
            $resolvedPath = (Resolve-Path -LiteralPath $path).Path
            if (-not $script:VerifyLocalFileCache.ContainsKey($resolvedPath)) {
                $script:VerifyLocalFileCache[$resolvedPath] = Get-Content -LiteralPath $resolvedPath -Raw
            }

            $content = $script:VerifyLocalFileCache[$resolvedPath]
            if ($content -match $Pattern) {
                $lineNumber = 0
                $fileLineMatched = $false
                foreach ($line in ($content -split "`r?`n")) {
                    $lineNumber++
                    if ($line -match $Pattern) {
                        $fileLineMatched = $true
                        $matchLines += "$($path):$($lineNumber):$line"
                    }
                }
                if (-not $fileLineMatched) {
                    $matchLines += "$($path):<multi-line match>"
                }
            }
        }

        return [PSCustomObject]@{
            Output = $matchLines
            Found = ($matchLines.Count -gt 0)
        }
    }

    # Windows PowerShell 5.1 can split native-command args when the regex contains
    # embedded literal quotes. Escape them before handing the pattern to rg.
    $nativePattern = $Pattern -replace '"', '\"'
    $output = & rg -- $nativePattern @Paths
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) {
        throw "rg failed with exit code $exitCode for pattern: $Pattern"
    }

    [PSCustomObject]@{
        Output = $output
        Found = ($exitCode -eq 0)
    }
}

function Assert-RgMatch {
    param(
        [string]$Pattern,
        [string[]]$Paths,
        [string]$Description
    )

    $result = Invoke-Rg -Pattern $Pattern -Paths $Paths
    if (-not $result.Found) {
        Write-Error "Missing required marker: $Description. pattern=$Pattern"
    }
}

function Assert-RgNoMatch {
    param(
        [string]$Pattern,
        [string[]]$Paths,
        [string]$Description
    )

    $result = Invoke-Rg -Pattern $Pattern -Paths $Paths
    if ($result.Found) {
        Write-Error "Forbidden marker found: $Description`n$($result.Output)"
    }
}

function Invoke-VerifyPowerShellTest {
    param([string]$ScriptName)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    & pwsh -NoProfile -ExecutionPolicy Bypass -File $scriptPath
    if ($LASTEXITCODE -ne 0) {
        throw "$ScriptName failed with exit code $LASTEXITCODE"
    }
}

function Resolve-BashCommand {
    $fromPath = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) {
        return $fromPath.Source
    }

    foreach ($candidate in @(
        "C:\Program Files\Git\bin\bash.exe",
        "C:\Program Files\Git\usr\bin\bash.exe",
        "C:\Program Files (x86)\Git\bin\bash.exe"
    )) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "bash is required for local shell syntax verification; install Git Bash or put bash on PATH"
}

function Assert-PostDeployIssueAcceptanceFlagGuard {
    $script = Join-Path $PWD "scripts\verify_post_deploy_issue_acceptance_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for post-deploy issue acceptance flag-guard verification"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script -SkipSplitAcceptance -RequireTrailingAcceptance 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        Write-Error "post-deploy issue acceptance wrapper accepted incompatible -SkipSplitAcceptance and -RequireTrailingAcceptance flags"
    }
    if ($text -notmatch "cannot be combined") {
        Write-Error "post-deploy issue acceptance wrapper did not fail with the expected incompatible-flag message:`n$text"
    }
    if ($text -match "SshHost is required|SshKey is required|SSH key not found") {
        Write-Error "post-deploy issue acceptance wrapper validates SSH inputs before rejecting incompatible acceptance flags:`n$text"
    }
}

function Assert-LiveReadinessBundleNoEvidenceGuard {
    $script = Join-Path $PWD "scripts\smoke_live_readiness_bundle_ssh.ps1"
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for live-readiness no-evidence verification"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File $script `
            -SshHost "127.0.0.1" `
            -SshKey ".\README.md" `
            -AppDir "/home/ubuntu/agora-trading-api" `
            -EnvFile "/home/ubuntu/.env.trading.secrets" 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)

    if ($exitCode -eq 0) {
        Write-Error "live-readiness bundle accepted an unusable local SSH key during no-evidence guard test"
    }
    foreach ($pattern in @(
            "read_only_bundle_error=",
            "read_only_bundle_error_detail=.*before full live-readiness evidence could be collected",
            'bundle_blockers=\["LIVE_READINESS_EVIDENCE_UNAVAILABLE"\]',
            "bundle_blocker_summary=",
            "live_review_packet_allowed=false",
            "deploy_required_before_live_review=unknown",
            "bundle_verdict=NO_EVIDENCE")) {
        if ($text -notmatch $pattern) {
            Write-Error "live-readiness bundle no-evidence guard output missing expected marker '$pattern':`n$text"
        }
    }
    if ($text -match "===== BEGIN live-readiness-audit =====|===== BEGIN live-background-automation =====|===== BEGIN runtime-evidence-rca =====|===== BEGIN tiny-live-loss-rca =====|===== BEGIN signal-correctness =====|===== BEGIN mcp-parity =====") {
        Write-Error "live-readiness bundle continued into child smokes after SSH evidence collection failed:`n$text"
    }
}

function Assert-PowerShellScriptFailsBeforeSsh {
    param(
        [string]$ScriptRelativePath,
        [string[]]$Arguments,
        [string]$ExpectedPattern,
        [string]$Description
    )

    $script = Join-Path $PWD $ScriptRelativePath
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for SSH wrapper input-guard verification"
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
        Write-Error "$Description accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        Write-Error "$Description did not fail with the expected guard message. pattern=$ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|remote command failed|failed to start remote deploy|server verification failed|runtime log smoke failed") {
        Write-Error "$Description reached SSH/remote execution before local input guard:`n$text"
    }
}

function Resolve-MavenProperty {
    param([string]$Name)

    $output = & mvn help:evaluate "-Dexpression=$Name" -q "-DforceStdout"
    if ($LASTEXITCODE -ne 0) {
        throw "mvn help:evaluate failed for $Name with exit code $LASTEXITCODE"
    }

    $value = @($output | Where-Object { $_ -and -not $_.StartsWith("[") } | Select-Object -Last 1)[0]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match "null object|invalid expression") {
        throw "Unable to resolve Maven property: $Name"
    }
    return $value.Trim()
}

function Resolve-MockitoJavaAgentArgLine {
    $localRepository = Resolve-MavenProperty -Name "settings.localRepository"
    $mockitoVersion = Resolve-MavenProperty -Name "mockito.version"
    $mockitoJar = Join-Path $localRepository "org\mockito\mockito-core\$mockitoVersion\mockito-core-$mockitoVersion.jar"
    return "-javaagent:`"$mockitoJar`""
}

function Assert-OnlyAllowedEnabledTrueFallbacks {
    $allowedFragments = @(
        "McpApiKeyFilter.java|mcp.master-approval.probe-wait-enabled:true",
        "TelegramServiceImpl.java|telegram.noise-reduction.enabled:true",
        "EnabledStrategyDataValidator.java|backtest.enabled-strategy-validator.enabled:true",
        "LiveSignalEvaluator.java|trade-decision-engine.regime-filter.enabled:true"
    )

    $matches = @(rg -n "enabled:true" src/main/java/com/agora)
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) {
        throw "rg failed with exit code $exitCode for enabled:true fallback scan"
    }

    foreach ($line in $matches) {
        $isAllowed = $false
        foreach ($fragment in $allowedFragments) {
            $parts = $fragment.Split("|")
            if ($line.Contains($parts[0]) -and $line.Contains($parts[1])) {
                $isAllowed = $true
                break
            }
        }
        if (-not $isAllowed) {
            Write-Error "Unexpected enabled:true fallback found. New default-on behavior must be classified as protective/internal or changed to explicit opt-in:`n$line"
        }
    }

    foreach ($fragment in $allowedFragments) {
        $found = $false
        foreach ($line in $matches) {
            $parts = $fragment.Split("|")
            if ($line.Contains($parts[0]) -and $line.Contains($parts[1])) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Error "Expected documented enabled:true fallback missing or moved without updating split verification allowlist: $fragment"
        }
    }
}

function Assert-OnlyAllowedDefaultValueTrueProperties {
    $allowedFragments = @(
        'DataQualityProperties.java|@DefaultValue("true") boolean enabled',
        'DbSlowQueryMonitorProperties.java|@DefaultValue("true") boolean enabled',
        'EventCalendarProperties.java|@DefaultValue("true") boolean enabled',
        'EventScanNotificationProperties.java|@DefaultValue("true") boolean dryRun',
        'EventRiskControlProperties.java|@DefaultValue("true") boolean enabled',
        'EventRiskControlProperties.java|@DefaultValue("true") boolean blockNewEntries',
        'ExecutionEventProperties.java|@DefaultValue("true") boolean notificationEnabled',
        'ExecutionEventProperties.java|@DefaultValue("true") boolean notificationDryRun',
        'GeminiAdvisorProperties.java|@DefaultValue("true") boolean tgSummary',
        'GeminiAdvisorProperties.java|@DefaultValue("true") boolean skipStuckEnabled',
        'GeminiAdvisorProperties.java|@DefaultValue("true") boolean priorHintContextEnabled',
        'KlineDivergenceProperties.java|@DefaultValue("true") boolean thinSourceDowngradeEnabled',
        'LongAiFilterProperties.java|@DefaultValue("true") boolean spotMode',
        'MarketSignalRiskCardProperties.java|@DefaultValue("true") boolean dryRun',
        'MarketSignalRiskCardProperties.java|@DefaultValue("true") boolean sendOnStatusChangeOnly',
        'MemoryMonitorProperties.java|@DefaultValue("true") boolean enabled',
        'StartupWatcherProperties.java|@DefaultValue("true") boolean enabled',
        'TradingGridProperties.java|@DefaultValue("true") boolean recycleClosedLevels',
        'TradingViewLocalSignalProperties.java|@DefaultValue("true") boolean executionDryRun',
        'TradingViewWebhookProperties.java|@DefaultValue("true") boolean dryRun'
    )

    $defaultTrueLines = @()
    Get-ChildItem -LiteralPath "src/main/java/com/agora/config/properties" -Filter "*.java" | ForEach-Object {
        $file = $_
        Select-String -LiteralPath $file.FullName -Pattern '@DefaultValue("true")' -SimpleMatch | ForEach-Object {
            $lineText = $_.Line.Trim().TrimEnd(",")
            $defaultTrueLines += "$($file.Name)|$lineText"
        }
    }

    foreach ($line in $defaultTrueLines) {
        $isAllowed = $false
        foreach ($fragment in $allowedFragments) {
            if ($line -eq $fragment) {
                $isAllowed = $true
                break
            }
        }
        if (-not $isAllowed) {
            Write-Error "Unexpected @DefaultValue(`"true`") fallback found. New default-on property must be classified as protective/internal/dry-run or changed to explicit opt-in:`n$line"
        }
    }

    foreach ($fragment in $allowedFragments) {
        $found = $false
        foreach ($line in $defaultTrueLines) {
            if ($line -eq $fragment) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Error "Expected documented @DefaultValue(`"true`") fallback missing or moved without updating split verification allowlist: $fragment"
        }
    }
}

function Assert-OnlyAllowedValueAnnotationTrueFallbacks {
    $allowedFragments = @(
        "McpApiKeyFilter.java|mcp.master-approval.probe-wait-enabled",
        "PositionMcpTools.java|trailing-stop.dry-run",
        "EnabledStrategyDataValidator.java|backtest.enabled-strategy-validator.enabled",
        "TelegramServiceImpl.java|telegram.noise-reduction.enabled",
        "ScoreBuyPrePositionAutoExecutionScheduler.java|trading.score-buy.pre-position.execution.dry-run",
        "ScoreBuyPostScoutAutoAddExecutionScheduler.java|trading.score-buy.post-scout-add.execution.dry-run",
        "PositionExitManagerScheduler.java|position-exit-manager.dry-run",
        "ScoreBuyConfirmedDeployAutoExecutionScheduler.java|trading.score-buy.confirmed-deploy.execution.dry-run",
        "TinyLiveAutoExecutionScheduler.java|trading.tiny-live.auto-execution.dry-run",
        "TrailingStopScheduler.java|trailing-stop.dry-run"
    )

    $valueTrueLines = @()
    Get-ChildItem -LiteralPath "src/main/java/com/agora" -Filter "*.java" -Recurse | ForEach-Object {
        $file = $_
        $text = Get-Content -LiteralPath $file.FullName -Raw
        [regex]::Matches($text, '@Value\("\$\{(?<key>[^}:]+):true\}"\)') | ForEach-Object {
            $valueTrueLines += "$($file.Name)|$($_.Groups["key"].Value)"
        }
    }

    foreach ($line in $valueTrueLines) {
        $isAllowed = $false
        foreach ($fragment in $allowedFragments) {
            if ($line -eq $fragment) {
                $isAllowed = $true
                break
            }
        }
        if (-not $isAllowed) {
            Write-Error "Unexpected @Value property fallback ':true' found. New default-on property must be classified as protective/internal/dry-run or changed to explicit opt-in:`n$line"
        }
    }

    foreach ($fragment in $allowedFragments) {
        $found = $false
        foreach ($line in $valueTrueLines) {
            if ($line -eq $fragment) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Error "Expected documented @Value ':true' fallback missing or moved without updating split verification allowlist: $fragment"
        }
    }
}

function Assert-OnlyAllowedEnvironmentPropertyDefaultTrueFallbacks {
    $allowedFragments = @(
        "McpSessionMasterApproval.java|mcp.master-approval.enabled",
        "ScoreBuyConfirmedDeployAutoExecutionService.java|trading.score-buy.confirmed-deploy.execution.dry-run",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.adaptive-extra-orders-enabled",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.adaptive-extra-requires-confirmation",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.budget-residual-extra-orders-enabled",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.budget-residual-extra-requires-confirmation",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.dry-run",
        "ScoreBuyPostScoutAutoAddExecutionService.java|trading.score-buy.post-scout-add.execution.missed-alpha-micro-slot-enabled",
        "ScoreBuyPrePositionAutoExecutionService.java|trading.score-buy.pre-position.execution.dry-run",
        "TinyLiveExecutionService.java|trading.tiny-live.auto-execution.dry-run"
    )

    $defaultTrueLines = @()
    Get-ChildItem -LiteralPath "src/main/java/com/agora" -Filter "*.java" -Recurse | ForEach-Object {
        $file = $_
        $text = Get-Content -LiteralPath $file.FullName -Raw
        [regex]::Matches($text, '\.getProperty\(\s*"(?<key>[^"]+)"\s*,\s*"true"\s*\)') | ForEach-Object {
            $defaultTrueLines += "$($file.Name)|$($_.Groups["key"].Value)"
        }
    }

    foreach ($line in $defaultTrueLines) {
        $isAllowed = $false
        foreach ($fragment in $allowedFragments) {
            if ($line -eq $fragment) {
                $isAllowed = $true
                break
            }
        }
        if (-not $isAllowed) {
            Write-Error "Unexpected Environment.getProperty(..., `"true`") fallback found. New default-on property must be classified as protective/internal/dry-run or changed to explicit opt-in:`n$line"
        }
    }

    foreach ($fragment in $allowedFragments) {
        $found = $false
        foreach ($line in $defaultTrueLines) {
            if ($line -eq $fragment) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Error "Expected documented Environment.getProperty default-true fallback missing or moved without updating split verification allowlist: $fragment"
        }
    }
}

function Assert-OnlyAllowedSystemEnvDefaultTrueFallbacks {
    $allowedFragments = @(
        "StartupBeanTimingProbe.java|STARTUP_BEAN_TIMING_ENABLED"
    )

    $defaultTrueLines = @()
    Get-ChildItem -LiteralPath "src/main/java/com/agora" -Filter "*.java" -Recurse | ForEach-Object {
        $file = $_
        $text = Get-Content -LiteralPath $file.FullName -Raw
        [regex]::Matches($text, 'System\.getenv\(\)\.getOrDefault\(\s*"(?<key>[^"]+)"\s*,\s*"true"\s*\)') | ForEach-Object {
            $defaultTrueLines += "$($file.Name)|$($_.Groups["key"].Value)"
        }
    }

    foreach ($line in $defaultTrueLines) {
        $isAllowed = $false
        foreach ($fragment in $allowedFragments) {
            if ($line -eq $fragment) {
                $isAllowed = $true
                break
            }
        }
        if (-not $isAllowed) {
            Write-Error "Unexpected System.getenv().getOrDefault(..., `"true`") fallback found. New default-on env behavior must be classified as internal diagnostic/protective or changed to explicit opt-in:`n$line"
        }
    }

    foreach ($fragment in $allowedFragments) {
        $found = $false
        foreach ($line in $defaultTrueLines) {
            if ($line -eq $fragment) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Write-Error "Expected documented System.getenv default-true fallback missing or moved without updating split verification allowlist: $fragment"
        }
    }
}

function Assert-SecurityPathsAllowedListExact {
    $allowedPaths = @(
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/v3/api-docs**",
        "/swagger-ui.html",
        "/mcp",
        "/mcp/**",
        "/trading/internal/reports/**",
        "/tradingview/webhook",
        "/ratelimit",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/actuator/prometheus",
        "/actuator/metrics",
        "/actuator/metrics/**",
        "/favicon.ico"
    )

    $text = Get-Content -LiteralPath "src/main/java/com/agora/config/SecurityPaths.java" -Raw
    $actualPaths = @()
    [regex]::Matches($text, '"(?<path>/[^"]*)"') | ForEach-Object {
        $actualPaths += $_.Groups["path"].Value
    }

    foreach ($path in $actualPaths) {
        if ($allowedPaths -notcontains $path) {
            Write-Error "Unexpected public HTTP path in SecurityPaths.ALLOWED_PATHS. New public route must be classified as retained trading surface or removed:`n$path"
        }
    }

    foreach ($path in $allowedPaths) {
        if ($actualPaths -notcontains $path) {
            Write-Error "Expected public HTTP path missing from SecurityPaths.ALLOWED_PATHS: $path"
        }
    }

    if ($actualPaths.Count -ne $allowedPaths.Count) {
        Write-Error "SecurityPaths.ALLOWED_PATHS has duplicate or missing entries. expected=$($allowedPaths.Count) actual=$($actualPaths.Count)"
    }
}

function Assert-StartupRunnersAreSplitSafe {
    $runnerFiles = @(rg -l "implements (ApplicationRunner|CommandLineRunner)" src/main/java)
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) {
        throw "rg failed with exit code $exitCode for startup runner scan"
    }

    foreach ($file in $runnerFiles) {
        $text = Get-Content -LiteralPath $file -Raw
        foreach ($required in @(
            "@AsyncStartup",
            "@ConditionalOnProperty",
            'havingValue = "true"',
            "matchIfMissing = false"
        )) {
            if (-not $text.Contains($required)) {
                Write-Error "Startup runner must be async and explicit opt-in for split-safe deploys. Missing '$required' in $file"
            }
        }
    }
}

function Get-McpParityRequiredTools {
    $script = Get-Content -LiteralPath "scripts/smoke_mcp_parity.ps1" -Raw
    $match = [regex]::Match($script, '\$requiredTools\s*=\s*@\((?<body>.*?)\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        throw "Unable to find required MCP parity tool list in scripts/smoke_mcp_parity.ps1"
    }
    $tools = @()
    [regex]::Matches($match.Groups["body"].Value, '"(?<tool>[A-Za-z0-9_]+)"') | ForEach-Object {
        $tools += $_.Groups["tool"].Value
    }
    if ($tools.Count -eq 0) {
        throw "Required MCP parity tool list is empty"
    }
    return @($tools | Sort-Object -Unique)
}

function Get-LocalSmokeRequiredMcpTools {
    $script = Get-Content -LiteralPath "scripts/smoke_local_health.ps1" -Raw
    $match = [regex]::Match($script, 'Assert-McpToolsPresent\s+-Url\s+\$mcpUrl\s+-RequiredTools\s+@\((?<body>.*?)\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        throw "Unable to find required MCP parity tool list in scripts/smoke_local_health.ps1"
    }
    $tools = @()
    [regex]::Matches($match.Groups["body"].Value, '"(?<tool>[A-Za-z0-9_]+)"') | ForEach-Object {
        $tools += $_.Groups["tool"].Value
    }
    if ($tools.Count -eq 0) {
        throw "Local smoke required MCP tool list is empty"
    }
    return @($tools | Sort-Object -Unique)
}

function Get-SshMcpParityRequiredTools {
    $script = Get-Content -LiteralPath "scripts/smoke_mcp_parity_ssh.ps1" -Raw
    $match = [regex]::Match($script, 'required_tools\s*=\s*\[(?<body>.*?)\]', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        throw "Unable to find required MCP parity tool list in scripts/smoke_mcp_parity_ssh.ps1"
    }
    $tools = @()
    [regex]::Matches($match.Groups["body"].Value, '"(?<tool>[A-Za-z0-9_]+)"') | ForEach-Object {
        $tools += $_.Groups["tool"].Value
    }
    if ($tools.Count -eq 0) {
        throw "SSH MCP parity required tool list is empty"
    }
    return @($tools | Sort-Object -Unique)
}

function Get-McpToolMetadataRows {
    $rows = @()
    $mcpToolFiles = Get-ChildItem -LiteralPath "src/main/java/com/agora/mcp" -Filter "*McpTools.java" -Recurse
    foreach ($file in $mcpToolFiles) {
        $lines = Get-Content -LiteralPath $file.FullName
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -notmatch '^\s*public\s+.+?\s+(?<method>[A-Za-z0-9_]+)\s*\(') {
                continue
            }
            $method = $Matches["method"]
            $start = $i - 1
            while ($start -ge 0 -and -not [string]::IsNullOrWhiteSpace($lines[$start])) {
                $start--
            }
            if ($start + 1 -gt $i) {
                continue
            }
            $annotationBlock = $lines[($start + 1)..$i] -join "`n"
            if ($annotationBlock -notmatch '@Tool\b') {
                continue
            }
            $toolName = $method
            $toolNameMatch = [regex]::Match($annotationBlock, '@Tool\s*\((?<body>.*?)\)\s*(?:@|\s*public)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
            if ($toolNameMatch.Success) {
                $explicitName = [regex]::Match($toolNameMatch.Groups["body"].Value, 'name\s*=\s*"(?<name>[^"]+)"')
                if ($explicitName.Success) {
                    $toolName = $explicitName.Groups["name"].Value
                }
            }
            $rows += [PSCustomObject]@{
                File = $file.Name
                Line = $i + 1
                Method = $method
                Tool = $toolName
                HasAuth = ($annotationBlock -match '@(?:[\w.]+\.)?McpAuth\b')
                HasCategory = ($annotationBlock -match '@(?:[\w.]+\.)?McpCategory\b')
                AuthLevel = if ($annotationBlock -match 'McpAuthLevel\.(?<level>[A-Z_]+)') { $Matches["level"] } else { "" }
                Categories = @([regex]::Matches($annotationBlock, 'Category\.(?<category>[A-Z_]+)') | ForEach-Object { $_.Groups["category"].Value })
            }
        }
    }
    return @($rows)
}

function Assert-AllMcpToolsHaveExplicitMetadata {
    $rows = Get-McpToolMetadataRows
    $missingAuth = @($rows | Where-Object { -not $_.HasAuth })
    $missingCategory = @($rows | Where-Object { -not $_.HasCategory })
    if ($missingAuth.Count -gt 0) {
        $details = ($missingAuth | ForEach-Object { "$($_.File):$($_.Line) $($_.Tool)" }) -join "`n"
        Write-Error "Every MCP @Tool in *McpTools.java must declare explicit @McpAuth. Missing:`n$details"
    }
    if ($missingCategory.Count -gt 0) {
        $details = ($missingCategory | ForEach-Object { "$($_.File):$($_.Line) $($_.Tool)" }) -join "`n"
        Write-Error "Every MCP @Tool in *McpTools.java must declare explicit @McpCategory. Missing:`n$details"
    }
}

function Assert-McpParityToolCoverage {
    Assert-AllMcpToolsHaveExplicitMetadata

    $requiredTools = Get-McpParityRequiredTools
    $localSmokeTools = Get-LocalSmokeRequiredMcpTools
    $sshParityTools = Get-SshMcpParityRequiredTools
    $missingFromLocalSmoke = @($requiredTools | Where-Object { $localSmokeTools -notcontains $_ })
    if ($missingFromLocalSmoke.Count -gt 0) {
        Write-Error "Local smoke MCP parity list is missing tool(s) from smoke_mcp_parity.ps1: $($missingFromLocalSmoke -join ', ')"
    }
    $missingFromReusableSmoke = @($localSmokeTools | Where-Object { $requiredTools -notcontains $_ })
    if ($missingFromReusableSmoke.Count -gt 0) {
        Write-Error "smoke_mcp_parity.ps1 is missing tool(s) required by local smoke MCP parity list: $($missingFromReusableSmoke -join ', ')"
    }
    $missingFromSshSmoke = @($requiredTools | Where-Object { $sshParityTools -notcontains $_ })
    if ($missingFromSshSmoke.Count -gt 0) {
        Write-Error "SSH MCP parity smoke list is missing tool(s) from smoke_mcp_parity.ps1: $($missingFromSshSmoke -join ', ')"
    }
    $extraInSshSmoke = @($sshParityTools | Where-Object { $requiredTools -notcontains $_ })
    if ($extraInSshSmoke.Count -gt 0) {
        Write-Error "smoke_mcp_parity.ps1 is missing tool(s) required by SSH MCP parity smoke list: $($extraInSshSmoke -join ', ')"
    }

    $mcpToolRows = Get-McpToolMetadataRows
    foreach ($tool in $requiredTools) {
        $row = @($mcpToolRows | Where-Object { $_.Tool -eq $tool -or $_.Method -eq $tool } | Select-Object -First 1)
        if ($row.Count -eq 0) {
            Write-Error "MCP parity smoke requires tool '$tool' but no matching MCP Java method exists"
        }
        if ($row.Count -gt 0 -and -not $row[0].HasAuth) {
            Write-Error "MCP parity smoke requires tool '$tool' but its Java method is missing explicit @McpAuth"
        }
        if ($row.Count -gt 0 -and -not $row[0].HasCategory) {
            Write-Error "MCP parity smoke requires tool '$tool' but its Java method is missing explicit @McpCategory"
        }
        if ($row.Count -gt 0 -and $row[0].AuthLevel -eq "DEV") {
            Write-Error "MCP parity smoke must stay read-only/ops-callable; required tool '$tool' is DEV-only"
        }
        if ($row.Count -gt 0 -and $row[0].Categories -contains "WRITE_TRADING") {
            Write-Error "MCP parity smoke must not require WRITE_TRADING tool '$tool'"
        }

        if (-not (Select-String -LiteralPath "scripts/smoke_local_health.ps1" -Pattern "`"$tool`"" -Quiet)) {
            Write-Error "Local smoke must require the same MCP parity tool as smoke_mcp_parity.ps1: $tool"
        }
        if (-not (Select-String -LiteralPath "scripts/smoke_mcp_parity_ssh.ps1" -Pattern "`"$tool`"" -Quiet)) {
            Write-Error "SSH MCP parity smoke must require the same MCP parity tool as smoke_mcp_parity.ps1: $tool"
        }
    }

    foreach ($marker in @("tools/list", "getMcpRegistryVersion", "api/mcp", "required_tools=", "missing_required_tools=")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_mcp_parity.ps1", "scripts/smoke_local_health.ps1") -Description "MCP parity smoke marker $marker"
    }
    foreach ($marker in @("Invoke-McpTool", "getEventRiskControlStatus", "analyzeSpotAntiWickPolicyCoverage", "analyzeTrailingStopPnlReplay", "getEntryDedupGovernanceDashboard", "getMissedOpportunityRegressionReport", "analyzeStrategy508HoldCounterfactual", "analyzeBtcDonchianShadowGoldenParity", "getBtcDonchianShadowReadiness", "orderSent", "ocoModified", "writesRuntimeEvidence", "operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION", "ULTRA_LOW_DISASTER", "acceptanceTarget: total trailing PnL improvement >= 5%", "ambiguousSameBar rows are excluded", "acceptanceBlocker=", "acceptanceBlockerDetail=")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_mcp_parity.ps1") -Description "reusable MCP parity smoke calls read-only acceptance surface marker $marker"
    }
    foreach ($marker in @("getGovernanceDriftDashboard", "findGovernanceRelaxationCandidates", "findGovernanceTighteningCandidates", "Governance Drift Dashboard", "Governance Relaxation Candidates", "Governance Tightening Candidates")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_mcp_parity_ssh.ps1", "scripts/smoke_signal_correctness_ssh.ps1") -Description "MySQL-backed governance parity smoke is executable on server-local SSH marker $marker"
    }
    Assert-RgNoMatch -Pattern "MCP parity smoke must not require WRITE_TRADING|MCP parity smoke must stay read-only/ops-callable" -Paths @("README.md", "docs", "SPLIT_PROGRESS.md") -Description "MCP parity write-tool guard should live in verifier, not operator docs"
    Assert-RgMatch -Pattern "requires 47 representative tools|47 required|required=47" -Paths @("docs/legacy-trading-parity-inventory.md", "docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "MCP parity required-tool count is documented as 47"
    Assert-RgNoMatch -Pattern "26 required|requires 26|30 required|requires 30 representative tools|required=30|32 required|requires 32 representative tools|required=32|41 required|requires 41 representative tools|required=41" -Paths @("README.md", "SPLIT_PROGRESS.md", "docs") -Description "stale MCP parity required-tool count"
    foreach ($marker in @("smoke_mcp_parity.ps1", "-BaseUrl", "-McpKey", "Reusable MCP parity smoke failed")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_local_health.ps1") -Description "local smoke invokes reusable MCP parity smoke marker $marker"
    }
    foreach ($marker in @("analyzeSpotAntiWickPolicyCoverage", "ULTRA_LOW_DISASTER", "Anti-wick policy coverage stays read-only")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_local_health.ps1") -Description "local smoke calls anti-wick MCP coverage marker $marker"
    }
}

function Assert-StrategyExecutionVerifierReadOnly {
    $diagnosticTool = "src/main/java/com/agora/mcp/DiagnosticMcpTools.java"

    Assert-RgNoMatch -Pattern "BinanceKlineImportService|klineImportService\.importHistorical" `
        -Paths @($diagnosticTool) `
        -Description "verifyStrategyExecution must not call external Binance import/backfill"

    foreach ($marker in @(
        "no external import/backfill",
        "MACHINE_STATUS no missing evaluation; no missed order",
        "MACHINE_STATUS missing evaluation or missed order suspected",
        "signal_source_policy_primary",
        "POLICY_SUPPRESSED_NOT_MISSED_EVALUATION",
        "TRADINGVIEW primary expects external TradingView alerts",
        "disables legacy LiveSignalEvaluator",
        "loadKlineReadinessLine",
        "resolveEffectiveKlineSource",
        "LOCAL_TRADINGVIEW_ALLOWED_SOURCE_OVERRIDE",
        "validationWarmupDays",
        "tradingViewParityMode",
        "LOCAL_TRADINGVIEW_PARITY/",
        "req\.setSource\(klineSource\)",
        "req\.setSkipPersist\(true\)"
    )) {
        Assert-RgMatch -Pattern $marker `
            -Paths @($diagnosticTool) `
            -Description "verifyStrategyExecution read-only diagnostic marker $marker"
    }
}

function Assert-DataFreshnessRcaSnapshotSummary {
    $diagnosticTool = "src/main/java/com/agora/mcp/DiagnosticMcpTools.java"

    foreach ($marker in @(
        "Current snapshot summary",
        "READY_NOW",
        "STALE_NOW",
        "NO_DATA_NOW",
        "QUERY_FAILED_NOW",
        "staleNowKeys",
        "KlineFreshnessSnapshot"
    )) {
        Assert-RgMatch -Pattern $marker `
            -Paths @($diagnosticTool) `
            -Description "DataFreshnessGuard RCA current snapshot marker $marker"
    }
}

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Invoke-VerifyPowerShellTest -ScriptName "check_java_directory_classpath.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_schema_baseline_entity_table_parser.ps1"

    $mockitoArgLine = Resolve-MockitoJavaAgentArgLine
    Write-Host "[verify] mvn test"
    mvn test "-DargLine=$mockitoArgLine"
    if ($LASTEXITCODE -ne 0) {
        throw "mvn test failed with exit code $LASTEXITCODE"
    }

    Write-Host "[verify] checking source boundary markers"
    Assert-StrategyExecutionVerifierReadOnly
    Assert-DataFreshnessRcaSnapshotSummary

    Assert-RgNoMatch -Pattern "FlutterDeployment|FlutterAppDeployment|AppVersion|flutter/deployment|SearchLog|UserSearchLog|user_search_log|CustomerIssue|CreateIssueParam|IssueSearchParam|ReplyIssueParam|IssueTypeEnum|IssueStatusEnum|AdminImageAuditService|BrokenImage|AiProductClassificationSuggestion|imageaudit|UserAddress|PostalArea|TaiwanPostalArea|DeliveryCountryPolicy|OAuth2Service|OAuth2AuthorizationService|OAuth2UsageService|OAuth2Authorize|OAuth2Token|GoogleOAuthUserInfo|/login/oauth2|WalletConnectSession|WalletConnectNonce|WalletConnectSignatureVerifier|WalletConnectNonceResponse|Web3LoginRequest|Web3NonceRequest|oauth2-client|AuthService\\.java|AuthCode|TwoFactorAuthService|TwoFactor(Manage|Setup|Status|Verify)|GoogleAuthenticator|googleauth|RegisterResult|RegisterParam|LoginParam|PasswordReset(Param|WithCodeParam|CodeValidate)|EmailLogin(Request|SendCode)|BindEmail|BindOAuth|LoginBindings|LoginMethod|AdminResetPassword|AdminCreateUser|UserProfileUpdate|UpdateUsername|TrackReferrerRequest|TelegramWebAppAuthService|TelegramBotLoginService|LoginResult|UserOAuthBinding|OAuthProvider|UserServiceImpl|MemberSearchParam|MemberUpdateParam|DefaultHomePageEnum|RegistrationMethodEnum|PostService|PostCreateParam|PostSearchParam|PostResponse|PostUpdateParam|PostStatusEnum|web-push|webpush|WebPush|ProductReport|ProductTypeEnum|ProductStatusEnum|ProductCategoryEnum|ValidProductByType|CartSummaryDTO|OrderStatusChangedEvent|OrderFulfilledEvent|EventPublisherService|DeliveryProofStatusEnum|DeliveryProofTypeEnum|DeliveryReportTypeEnum|DeliveryStatusEnum|OrderStatusEnum|OrderSortTypeEnum|OrderSearchDateTypeEnum|ShippingCompanyEnum|PickupServiceTypeEnum|CompanyCategory|EnumTranslationUtil|ColdWalletStatusEnum|WalletStatusEnum|NotificationTypeEnum|NotificationStatusEnum|DeliveryFeeProperties|DigitalOrderProperties|GeoUtils|LogisticsCalculator|CreateOrderTool|OrderTool|OrderStatusTool|SmartOrderTool|RecommendProductsTool|SmartSearchTool|LoginTool|McpLoginTool|BetStatusEnum|MarketOptionStatusEnum|MarketStatusEnum|MarketTypeEnum|PromoCodeStatusEnum|RechargeStatusEnum|\\bServiceTypeEnum\\b|WithdrawStatusEnum|/products/|/products\\*|/pwa-logs|/slot/|PwaLog|TrafficAnalytics|Slot(Symbol|Traffic|Overview|Hourly|Daily)|RegistrationOverview|DailyRegistrationStats|HourlyRegistrationStats|MethodRegistrationStats|PromoCodeRegistrationStats|slotPaytableConfig|slotRtp|ApplyStakingParam|ChatMessage(DTO|QueryParam|UpdateDTO)|ChatSessionQueryParam|InterestRecord(DTO|SearchParam)|ManualAdjustBalanceParam|NextInterestEstimateDTO|Staking(ConfigDTO|ConfigUpdateParam|SearchParam|StatisticsDTO)|Transaction(ListParam|SearchParam|TypeEnum)|Dispute(Outcome|StatusEnum)|ReturnReasonEnum|Tg(Game|Handicap)Type" -Paths @("src/main/java", "src/main/resources/application.yml", "pom.xml") -Description "marketplace/frontend residue"

    $marketplaceRealtimeForbiddenFiles = rg --files src/main/java | rg "Chat(Session|Message)(Repository)?\.java|ChatMessageBuilder\.java|WebRTC.*\.java|SSE(Service|Config|Event(Request|Response)|ExceptionHandler)\.java|SseProperties\.java|NotifyEventTypeEnum\.java|ClientId(Validator|FormatException)\.java|UnauthorizedException\.java|ReturnRejectedEvent\.java|PromoCodeView\.java|StakingStatusEnum\.java|WalletException\.java|BuyerInfoSchemaValidator\.java|CreateColdWalletParam\.java|FounderAffiliatedSellerRegistry\.java|WithdrawRiskService\.java|UserWithdrawRiskState(Repository)?\.java|SanctionBlacklist(Service|Address|AddressRepository)\.java|AiGroupConversion(Daily|Event|Service).*\.java|AiAnalyticsService\.java|GroupConversionStatsDTO\.java|AutoReply(Config|ConfigRepository|ConfigService|ConfigSearchRequest|ResetStatsResponse|DeleteResponse)\.java|FileAssociation(ErrorResponse|Exception)\.java|BusinessIdGenerator\.java|TronSignatureVerifier\.java|TestDataService\.java|EncryptedStringConverter\.java|LlmContextRedactor\.java|SchedulerJobTypeEnum\.java|dto[\\/]scheduler[\\/].*\.java"
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden marketplace realtime/frontend files found:`n$marketplaceRealtimeForbiddenFiles"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg marketplace realtime file check failed with exit code $LASTEXITCODE"
    }

    Assert-RgNoMatch -Pattern "JwtAuthenticationFilter|CurrentUserMethodArgumentResolver|\bCurrentUser\b|UserPrincipal|CustomUserDetailsServiceImpl|DeviceFingerprintUtil|SecurityUtils|/auth/\*\*" -Paths @("src/main/java", "src/main/resources/application.yml", "pom.xml") -Description "web auth residue"

    Assert-RgNoMatch -Pattern "JwtUtil|JwtConfig|jjwt|McpAuthLevel\.MEMBER|\bMEMBER\b|User JWT|isValidJwt|/auth/\*\*|TelegramLoginBotConfig|TelegramWebhookConfig|login-bot|USER_LOGIN|USER_LOGOUT|LOGIN_ANOMALY|TWO_FACTOR_AUTH_REQUIRED|SUSPICIOUS_LOGIN_ATTEMPT|ACCOUNT_LOCKED|AppMarketProperties|agora_login_bot" -Paths @("src/main/java", "src/main/resources/application.yml", "pom.xml") -Description "login/JWT residue"

    Assert-RgNoMatch -Pattern "UserRepository|AutoReplyService|AutoReplyServiceImpl|WebRTCSignalingService|UserStatusEnum|@Table\(name = `"users`"" -Paths @("src/main/java", "src/main/resources/application.yml", "pom.xml") -Description "marketplace user boundary residue"
    Assert-RgNoMatch -Pattern "ROLE_ADMIN|hasAuthority|\.authenticated\(" -Paths @("src/main/java/com/agora/config/SecurityConfig.java") -Description "role/login HTTP security fallback residue"
    Assert-RgMatch -Pattern "\.anyRequest\(\)\.denyAll\(\)" -Paths @("src/main/java/com/agora/config/SecurityConfig.java") -Description "non-public HTTP routes default deny without login fallback"
    Assert-RgMatch -Pattern "Non-public HTTP routes default to deny-all" -Paths @("SERVICE_BOUNDARY.md") -Description "service boundary documents deny-all HTTP default"
    Assert-RgNoMatch -Pattern "TRADING_ADMIN_KEY|trading\.admin|local-smoke-admin" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "scripts/validate_env_template.ps1", ".env.trading.secrets.example", "src/main/resources/application.yml", "src/main/resources/application-local-smoke.yml", "docs/deploy-runbook.md") -Description "unused admin HTTP secret residue"
    Assert-RgNoMatch -Pattern '"/(public|test|images|telegram/webhook|backtests|admin/market|admin/oco|market)/(.*)?"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "legacy public HTTP route allowlist residue"
    Assert-SecurityPathsAllowedListExact
    Assert-RgMatch -Pattern '"/mcp"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "MCP endpoint remains a retained trading HTTP surface"
    Assert-RgMatch -Pattern '"/trading/internal/reports/\*\*"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "internal report gateway remains API-key guarded retained HTTP surface"
    Assert-RgMatch -Pattern "X-Internal-Api-Key" -Paths @("src/main/java/com/agora/infra/bot/InternalTradingReportController.java") -Description "internal report gateway enforces service API key header"
    Assert-RgMatch -Pattern "TRADING_INTERNAL_API_KEY" -Paths @(".env.trading.secrets.example", "README.md", "src/main/resources/application.yml", "scripts/validate_env_template.ps1") -Description "internal report gateway secret is documented and validated"
    Assert-RgMatch -Pattern "exact public HTTP allowlist is enforced by ``scripts/verify_local.ps1``" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "exact public HTTP allowlist verification is documented"

    Assert-RgNoMatch -Pattern "service\\.auth\\.model|bot\\.conversation|com\\.agora\\.entity|telemetry/game" -Paths @("src/main/java/com/agora/model/README.md") -Description "stale model package guidance"
    Assert-RgNoMatch -Pattern "system/auth/frontend remnants|Cleanup Queue" -Paths @("SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale split progress/boundary wording"
    Assert-RgNoMatch -Pattern "archunit|ArchitectureTest|arch-boundaries-violations|arch-refactor-plan" -Paths @("pom.xml") -Description "stale ArchUnit boundary-test residue"
    Assert-RgMatch -Pattern "smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180" -Paths @("README.md") -Description "README documents full local smoke command"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL=https://agoramarketapi\.purrtechllc\.com" -Paths @("README.md") -Description "README documents AgoraMarket stable dependency base URL"
    Assert-RgMatch -Pattern "AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000" -Paths @("README.md") -Description "README documents bounded AgoraMarket internal API timeout"
    Assert-RgMatch -Pattern "/api/mcp" -Paths @("README.md") -Description "README documents trading MCP context path"
    Assert-RgMatch -Pattern "production currentness" -Paths @("README.md") -Description "README warns local verification does not prove production currentness"
    Assert-RgNoMatch -Pattern "db_migration_history|db/migrations|matchIfMissing = true|Has V040" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "stale migration drift checker defaults"
    Assert-RgMatch -Pattern "trading_flyway_schema_history" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "migration drift checker uses Trading-owned Flyway history table"
    Assert-RgMatch -Pattern "meta-control\.migration-drift-check\.table:trading_flyway_schema_history" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java", "src/main/java/com/agora/mcp/DiagnosticMcpTools.java") -Description "migration diagnostics default to Trading-owned Flyway history table"
    Assert-RgNoMatch -Pattern "FROM flyway_schema_history" -Paths @("src/main/java/com/agora/mcp/DiagnosticMcpTools.java") -Description "MCP migration diagnostics must not hard-read AgoraMarketAPI Flyway history table"
    Assert-RgMatch -Pattern "getAppliedMigrations.*MigrationDriftChecker" -Paths @("docs/schema-baseline.md") -Description "schema baseline docs mention MCP migration diagnostics and drift checker together"
    Assert-RgMatch -Pattern "Trading-owned" -Paths @("docs/schema-baseline.md") -Description "schema baseline docs identify Trading-owned Flyway history table"
    Assert-RgMatch -Pattern "do not read.*flyway_schema_history" -Paths @("docs/schema-baseline.md") -Description "schema baseline docs keep MCP migration diagnostics off AgoraMarketAPI Flyway history table"
    Assert-RgMatch -Pattern "Hardened schema mode" -Paths @(".env.trading.secrets.example", "docs/deploy-runbook.md") -Description "ddl-auto validate is documented as hardened schema mode"
    Assert-RgMatch -Pattern "Flyway baseline" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "migration baseline prerequisite is documented"
    Assert-RgNoMatch -Pattern "pending trading Flyway baseline" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state pending trading Flyway baseline"
    Assert-RgNoMatch -Pattern "future trading migration baseline" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state future trading migration baseline"
    Assert-RgNoMatch -Pattern "V10[0-9] migration" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state V10[0-9] migration"
    Assert-RgNoMatch -Pattern "follow-up Flyway migration" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state follow-up Flyway migration"
    Assert-RgNoMatch -Pattern "Generate and review the Flyway baseline" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state Generate and review the Flyway baseline"
    Assert-RgNoMatch -Pattern "until a baseline migration is ready" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state until a baseline migration is ready"
    Assert-RgNoMatch -Pattern "Before replacing Hibernate schema update with Flyway validation" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state Before replacing Hibernate schema update with Flyway validation"
    Assert-RgNoMatch -Pattern "before generating Flyway baseline" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state before generating Flyway baseline"
    Assert-RgNoMatch -Pattern "before any Flyway baseline is generated" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state before any Flyway baseline is generated"
    Assert-RgNoMatch -Pattern "once Flyway is enabled" -Paths @("pom.xml", "src/main/java", "src/test/java", "README.md", "docs", "SPLIT_PROGRESS.md") -Description "schema comments and docs must not imply pre-baseline or legacy V10x migration state once Flyway is enabled"
    Assert-RgMatch -Pattern "reviewed V1 baseline" -Paths @("src/main/java/com/agora/model/BtLiveSignal.java") -Description "trailing columns document shared-DB V1 baseline ownership"
    Assert-RgNoMatch -Pattern "V108" -Paths @("src/main/java/com/agora/model/BtLiveSignal.java") -Description "trailing schema comments must not imply standalone V108 migrations"
    Assert-RgNoMatch -Pattern "V116" -Paths @("src/main/java/com/agora/model/BtLiveSignal.java") -Description "trailing schema comments must not imply standalone V116 migrations"
    Assert-RgMatch -Pattern "AgoraMarketAPI Trading Cutover Plan" -Paths @("docs/deploy-runbook.md") -Description "legacy AgoraMarketAPI trading cutover plan is documented"
    Assert-RgMatch -Pattern "split-acceptance-status.md" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "current split acceptance handoff is linked"
    Assert-RgMatch -Pattern "Shared-DB schema compare and Trading deployment acceptance have passed" -Paths @("docs/split-acceptance-status.md") -Description "acceptance handoff records post-cutover trading deployment acceptance"
    Assert-RgMatch -Pattern "current local handoff branch tip" -Paths @("docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "local handoff docs avoid self-stale exact commit evidence"
    Assert-RgMatch -Pattern "GitHub issues #1/#2/#3 record the exact latest evidence commit" -Paths @("docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "local handoff docs point exact issue evidence to GitHub issues"
    Assert-RgNoMatch -Pattern "through commit ``?[0-9a-f]{7,40}``?" -Paths @("docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "local handoff docs must not hard-code a self-stale evidence commit SHA"
    Assert-RgMatch -Pattern "check-live-mcp-split-ownership\.ps1" -Paths @("README.md", "SPLIT_PROGRESS.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md") -Description "cross-service live MCP ownership smoke is documented"
    Assert-RgMatch -Pattern "representative legacy Trading tools are absent" -Paths @("README.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md") -Description "cross-service ownership smoke documents AgoraMarketAPI legacy Trading absence"
    Assert-RgMatch -Pattern "schema_baseline_inventory.ps1" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline starts with read-only source inventory"
    Assert-RgMatch -Pattern "Runtime side effects that could surprise a split deployment now default off" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents default-off runtime side-effect cleanup"
    Assert-RgMatch -Pattern "high-risk host env values" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents local-smoke high-risk host env clearing"
    Assert-RgMatch -Pattern "Deploy/nginx scripts fail fast when ``systemctl`` is unavailable" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents nginx reload dependency guard"
    Assert-RgMatch -Pattern "high-risk trading/deletion/AI automation toggles" -Paths @("docs/split-audit.md") -Description "split audit documents local-smoke high-risk runtime env clearing"
    Assert-RgMatch -Pattern "attribution/ML startup work is not scheduled" -Paths @("docs/split-audit.md") -Description "split audit documents startup attribution and ML refresh smoke guards"
    Assert-RgMatch -Pattern "Remaining ``enabled:true`` fallbacks are enforced by ``scripts/verify_local.ps1``" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents remaining enabled:true allowlist"
    Assert-RgMatch -Pattern "production deploy advanced .*agora-trading-api.* to" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents current production deployment"
    Assert-RgMatch -Pattern "read-only source inventory complete" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory script is read-only and explicit"
    Assert-RgMatch -Pattern "without explicit @Table" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory rejects implicit table names"
    Assert-RgMatch -Pattern "forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_inventory.ps1", "docs/schema-baseline.md") -Description "schema inventory rejects obvious marketplace-owned table names"
    Assert-RgMatch -Pattern "unsafe-table-names.txt" -Paths @("scripts/schema_baseline_inventory.ps1", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema inventory rejects unsafe table names"
    Assert-RgMatch -Pattern "unsafe table name" -Paths @("scripts/schema_baseline_inventory.ps1", "docs/schema-baseline.md") -Description "schema inventory documents unsafe table name rejection"
    Assert-RgMatch -Pattern "WriteAllLines" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory clears stale output files when result lists are empty"
    Assert-RgMatch -Pattern "schema_baseline_inventory.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs schema inventory"
    Assert-RgMatch -Pattern "validate_pom_boundary.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs pom dependency boundary"
    Assert-RgMatch -Pattern "validate_package_boundary.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs package boundary"
    foreach ($marketplacePackageSegment in @("member", "pwa", "transaction", "walletconnect", "web3", "withdraw")) {
        Assert-RgMatch -Pattern "`"$marketplacePackageSegment`"" -Paths @("scripts/validate_package_boundary.ps1") -Description "package boundary rejects residual marketplace package segment: $marketplacePackageSegment"
    }
    Assert-RgMatch -Pattern "validate_env_template.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs env template boundary"
    Assert-RgMatch -Pattern "require_env_.*key.*value" -Paths @("scripts/validate_env_template.ps1") -Description "env template validator tracks required key and fixed-value env guards"
    Assert-RgMatch -Pattern "schema_baseline_compare_server.sh" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline has read-only server compare step"
    Assert-RgMatch -Pattern "schema_baseline_generate_server.sh" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "schema baseline has guarded server baseline generation step"
    Assert-RgMatch -Pattern "SCHEMA_COMPARE_MODE=.*shared" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator defaults to shared DB mode"
    Assert-RgMatch -Pattern "schema_baseline_compare_server.sh" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator re-runs read-only compare first"
    Assert-RgMatch -Pattern "mysqldump" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator dumps DDL without mutating schema"
    Assert-RgMatch -Pattern "--no-data" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator dumps schema only"
    Assert-RgMatch -Pattern "--skip-add-drop-table" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator must not include DROP TABLE statements"
    Assert-RgMatch -Pattern 'dump_tables=\(\)' -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator passes table names to mysqldump through a guarded array"
    Assert-RgMatch -Pattern "unsafe source table name before baseline dump" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator rejects unsafe table names before mysqldump"
    Assert-RgMatch -Pattern "V1__baseline.sql" -Paths @("scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md", "docs/deploy-runbook.md") -Description "baseline generator writes reviewable Flyway baseline path"
    Assert-RgMatch -Pattern "Reviewed shared-DB baseline" -Paths @("src/main/resources/db/migration/V1__baseline.sql", "scripts/schema_baseline_generate_server.sh") -Description "baseline migration header reflects reviewed shared-DB status"
    Assert-RgMatch -Pattern "Future Trading schema changes should be V2__" -Paths @("src/main/resources/db/migration/V1__baseline.sql", "scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md") -Description "baseline migration points future schema changes to V2 migrations"
    Assert-RgNoMatch -Pattern "until this file is reviewed|review baseline before enabling Flyway|Generate and review the Flyway baseline" -Paths @("src/main/resources/db/migration/V1__baseline.sql", "scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md") -Description "baseline migration/docs/generator must not imply pre-review baseline state"
    Assert-RgNoMatch -Pattern "(?i)^[[:space:]]*drop[[:space:]]+table" -Paths @("src/main/resources/db/migration/V1__baseline.sql") -Description "reviewed baseline must not drop tables, truncate tables, or create Flyway history tables drop table"
    Assert-RgNoMatch -Pattern "(?i)^[[:space:]]*drop[[:space:]]+database" -Paths @("src/main/resources/db/migration/V1__baseline.sql") -Description "reviewed baseline must not drop tables, truncate tables, or create Flyway history tables drop database"
    Assert-RgNoMatch -Pattern "(?i)^[[:space:]]*truncate[[:space:]]+table" -Paths @("src/main/resources/db/migration/V1__baseline.sql") -Description "reviewed baseline must not drop tables, truncate tables, or create Flyway history tables truncate table"
    Assert-RgNoMatch -Pattern "(?i)create[[:space:]]+table[[:space:]]+.*flyway_schema_history" -Paths @("src/main/resources/db/migration/V1__baseline.sql") -Description "reviewed baseline must not create AgoraMarketAPI Flyway history table"
    Assert-RgNoMatch -Pattern "(?i)create[[:space:]]+table[[:space:]]+.*trading_flyway_schema_history" -Paths @("src/main/resources/db/migration/V1__baseline.sql") -Description "reviewed baseline must not create Trading Flyway history table"
    Assert-RgMatch -Pattern "shared marketplace tables are intentionally excluded|Shared marketplace tables are intentionally excluded" -Paths @("scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md") -Description "baseline generator excludes shared marketplace tables"
    Assert-RgNoMatch -Pattern "SPRING_FLYWAY_ENABLED=true|SPRING_JPA_HIBERNATE_DDL_AUTO=validate|flyway enabled|DROP TABLE|schema_extra_tables_cleanup" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator must not enable Flyway, switch ddl-auto, or run cleanup"
    Assert-RgNoMatch -Pattern "flyway:(migrate|baseline)|spring-boot:run|mvn .*flyway|SPRING_FLYWAY_ENABLED=|SPRING_FLYWAY_TABLE=|APPLY_SCHEMA_EXTRA_TABLE_CLEANUP|schema_extra_tables_cleanup_(plan|apply)_server\.sh|mysql[[:space:]].*-e" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator must not run Flyway, Spring Boot, cleanup, or MySQL mutation/query commands"
    Assert-RgMatch -Pattern "RUN_SCHEMA_BASELINE_COMPARE" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline compare is exposed through server verification"
    Assert-RgMatch -Pattern "VERIFY_GIT_CURRENT" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "server verification checks deployed git currentness by default"
    Assert-RgMatch -Pattern "REQUIRE_DEPLOY_METADATA=.*REQUIRE_DEPLOY_METADATA:-1" -Paths @("scripts/verify_server.sh") -Description "server verification requires deploy metadata by default"
    Assert-RgMatch -Pattern "REQUIRE_DEPLOY_METADATA=0.*diagnostics" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "deploy metadata bypass is documented as diagnostic only"
    Assert-RgMatch -Pattern "deploy commit file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.commit metadata is missing by default"
    Assert-RgMatch -Pattern "deploy port file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.port metadata is missing by default"
    Assert-RgMatch -Pattern "deploy pid file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.pid metadata is missing by default"
    Assert-RgMatch -Pattern 'runtime files differ from origin/\$BRANCH' -Paths @("scripts/verify_server.sh") -Description "server verification fails when worktree differs from origin branch by runtime files"
    Assert-RgMatch -Pattern 'worktree commit differs from origin/\$BRANCH only by docs/tooling files' -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "server verification permits docs/tooling-only origin drift"
    Assert-RgMatch -Pattern "app.commit" -Paths @("deploy.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "deploy records and server verify checks deployed commit metadata"
    Assert-RgMatch -Pattern "classify_deployed_delta_path" -Paths @("scripts/verify_server.sh") -Description "server verification classifies deployed commit drift by file type"
    Assert-RgMatch -Pattern "runtime files differ from deployed app.commit" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server verification fails when runtime files differ from deployed app.commit"
    Assert-RgMatch -Pattern "deployed app.commit differs from worktree HEAD only by docs/tooling files" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server verification permits docs/tooling-only deployed commit drift"
    Assert-RgMatch -Pattern "scripts/\*\.ps1" -Paths @("scripts/verify_server.sh") -Description "PowerShell operator scripts are docs/tooling-only deploy drift"
    Assert-RgMatch -Pattern "Local server verifier not found" -Paths @("scripts/verify_server_ssh.ps1") -Description "server SSH verifier streams the local verifier script"
    Assert-RgMatch -Pattern "Runtime log checker not found" -Paths @("scripts/verify_split_acceptance_ssh.ps1") -Description "split acceptance streams the local runtime-log checker"
    Assert-RgMatch -Pattern "PUBLIC_TRADING_MCP_URL" -Paths @("deploy.sh", "scripts/verify_server.sh", "scripts/verify_server_ssh.ps1", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "deploy/server verification checks public dedicated Trading MCP"
    Assert-RgMatch -Pattern "PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL" -Paths @("deploy.sh", "scripts/verify_server.sh", "scripts/verify_server_ssh.ps1", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "deploy/server verification requires public shared-host Trading MCP to be blocked"
    Assert-RgMatch -Pattern 'proxy_pass http://127[.]0[.]0[.]1:" port "/api/mcp' -Paths @("scripts/rewrite_nginx_trading_routes.awk") -Description "nginx deploy/install exposes dedicated public Trading MCP through canonical path"
    Assert-RgMatch -Pattern "verify_nginx_mcp_routes" -Paths @("scripts/verify_server.sh") -Description "server verification checks nginx public MCP routes"
    Assert-RgMatch -Pattern "dedicated /api/mcp route must proxy_pass" -Paths @("scripts/verify_server.sh") -Description "server verification requires dedicated public MCP proxy_pass"
    Assert-RgMatch -Pattern 'MCP_URL="http://127\.0\.0\.1:\$\{ACTIVE_PORT\}/api/mcp"' -Paths @("scripts/verify_server.sh") -Description "server verification uses canonical server-local Trading MCP path"
    Assert-RgMatch -Pattern '\$mcpUrl = "http://127\.0\.0\.1:\$Port/api/mcp"' -Paths @("scripts/smoke_local_health.ps1") -Description "local smoke uses canonical server-local Trading MCP path"
    Assert-RgMatch -Pattern "not a standalone MCP endpoint" -Paths @("SPLIT_PROGRESS.md", "docs/split-acceptance-status.md") -Description "current docs forbid /api/trading/mcp as a standalone MCP endpoint"
    Assert-RgNoMatch -Pattern "server-local /api/trading/mcp|server-local ``/api/trading/mcp``|server-local MCP ``getMcpRegistryVersion`` passed at ``/api/trading/mcp``|local MCP ``getMcpRegistryVersion`` passed at ``/api/trading/mcp``|``/api/trading/mcp`` ``getMcpRegistryVersion`` passed" -Paths @("SPLIT_PROGRESS.md", "docs/split-acceptance-status.md", "docs/deploy-runbook.md", "README.md") -Description "current handoff docs must not describe /api/trading/mcp as the active server-local MCP path"
    Assert-RgMatch -Pattern "DEFAULT_PUBLIC_TRADING_MCP_URL|PublicTradingMcpUrl" -Paths @("deploy.sh", "scripts/verify_server_ssh.ps1", "scripts/verify_split_acceptance_ssh.ps1") -Description "public dedicated Trading MCP is verified as an authenticated public registry"
    Assert-RgNoMatch -Pattern "deployment completed from ``origin/main`` commit|trading was deployed from ``origin/main`` commit" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md") -Description "stale observed deployment commit must not be phrased as current production"
    Assert-RgMatch -Pattern "historical evidence, not a current-deployment claim" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md") -Description "observed deployment commit is clearly marked historical"
    Assert-RgMatch -Pattern "schema baseline database comparison skipped" -Paths @("scripts/verify_server.sh") -Description "schema compare is opt-in for normal server verification"
    Assert-RgMatch -Pattern "EXPECTED_AGORA_MARKET_BASE_URL" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts guard AgoraMarket stable dependency base URL"
    Assert-RgMatch -Pattern 'EXPECTED_AGORA_MARKET_BASE_URL="\$EXPECTED_AGORA_MARKET_BASE_URL"' -Paths @("scripts/verify_server.sh") -Description "server verification passes AgoraMarket expected base URL into preflight"
    Assert-RgMatch -Pattern "EXPECTED_TRADING_DATABASE" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts guard shared trading database target"
    Assert-RgMatch -Pattern 'EXPECTED_TRADING_DATABASE="\$EXPECTED_TRADING_DATABASE"' -Paths @("deploy.sh", "scripts/verify_server.sh") -Description "deploy/server verification propagate expected trading database target"
    Assert-RgMatch -Pattern "EXPECTED_TRADING_DATABASE" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare guards shared trading database target even when run directly"
    Assert-RgMatch -Pattern 'EXPECTED_TRADING_DATABASE="\$EXPECTED_TRADING_DATABASE"' -Paths @("scripts/verify_server.sh") -Description "server verification propagates expected trading database target into schema compare"
    Assert-RgMatch -Pattern "SPRING_DATASOURCE_URL must point at expected shared database" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md", "docs/schema-baseline.md") -Description "server scripts require shared datasource target"
    Assert-RgMatch -Pattern "SCHEMA_COMPARE_MODE" -Paths @("scripts/schema_baseline_compare_server.sh", "scripts/verify_server.sh", "docs/schema-baseline.md") -Description "schema compare supports explicit shared or standalone mode"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL must point at stable AgoraMarketAPI dependency|AGORA_MARKET_BASE_URL.*stable AgoraMarketAPI nginx vhost dependency" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server scripts fail on stale AgoraMarket base URL"
    Assert-RgMatch -Pattern "SPRING_DATASOURCE_URL should point at the shared agora_market database" -Paths @("scripts/validate_env_template.ps1") -Description "env template validator requires shared datasource target"
    Assert-RgMatch -Pattern "AGORA_MARKET_HEALTH_URL=.*https://agoramarketapi\.purrtechllc\.com/api/actuator/health" -Paths @("deploy.sh", "scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts check stable AgoraMarket dependency health by default"
    Assert-RgMatch -Pattern "AgoraMarket exchange-rate dependency health failed" -Paths @("deploy.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy/verify fail on AgoraMarket health failure"
    Assert-RgMatch -Pattern "before starting the blue-green switch" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "deploy pre-switch AgoraMarket health gate is documented"
    Assert-RgMatch -Pattern "REQUIRE_AGORA_MARKET_HEALTH=.*REQUIRE_AGORA_MARKET_HEALTH:-1" -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server preflight/verify require AgoraMarket health by default"
    Assert-RgMatch -Pattern "REQUIRE_AGORA_MARKET_HEALTH=0.*diagnostic" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "AgoraMarket health bypass is documented as diagnostic only"
    Assert-RgMatch -Pattern "AgoraMarket exchange-rate dependency health failed" -Paths @("scripts/preflight_server.sh") -Description "server preflight fails on AgoraMarket health failure by default"
    Assert-RgMatch -Pattern 'REQUIRE_AGORA_MARKET_HEALTH=\$REQUIRE_AGORA_MARKET_HEALTH' -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "AgoraMarket health bypass stays diagnostic in preflight and server verification"
    Assert-RgMatch -Pattern "agoramarketapi\.purrtechllc\.com/api/actuator/health" -Paths @("scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server dependency health checks use stable AgoraMarket nginx vhost"
    Assert-RgMatch -Pattern "information_schema.tables" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare queries database metadata only"
    Assert-RgMatch -Pattern "missing or empty.*ENV_FILE" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare rejects empty datasource env keys when run directly"
    Assert-RgMatch -Pattern "server-implicit-entities.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects implicit entity table names"
    Assert-RgMatch -Pattern "server-forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects obvious marketplace-owned table names"
    Assert-RgMatch -Pattern "server-unsafe-source-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects unsafe source table names"
    Assert-RgMatch -Pattern "unsafe source table names" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "schema baseline docs and compare reject unsafe source table names"
    Assert-RgMatch -Pattern "server-db-forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare reports marketplace-owned database tables"
    Assert-RgMatch -Pattern "SCHEMA_COMPARE_MODE.*standalone" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare fails on marketplace-owned database tables only in standalone mode"
    Assert-RgMatch -Pattern "MARKETPLACE_TABLE_PATTERN" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare shares marketplace table pattern across source and database checks"
    Assert-RgMatch -Pattern "KNOWN_SYSTEM_TABLE_PATTERN" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare centralizes known system table pattern"
    Assert-RgMatch -Pattern "server-db-known-system-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare classifies known database system tables"
    Assert-RgMatch -Pattern "trading_flyway_schema_history" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md", "docs/deploy-runbook.md") -Description "schema compare classifies Trading Flyway history table separately"
    Assert-RgMatch -Pattern "read-only compare complete" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare documents read-only behavior"
    foreach ($commandName in @("comm", "find", "grep", "mkdir", "mysql", "perl", "sort", "tail", "tr", "wc", "xargs")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare fails fast when $commandName is unavailable"
    }
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED:false" -Paths @("src/main/resources/application.yml") -Description "Flyway is disabled by default until baseline exists"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED=true" -Paths @(".env.trading.secrets.example", "docs/deploy-runbook.md") -Description "server template enables Flyway after baseline"
    Assert-RgMatch -Pattern "require_env_value SPRING_JPA_HIBERNATE_DDL_AUTO validate" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification requires schema validation after Flyway baseline"
    Assert-RgMatch -Pattern "require_env_value SPRING_FLYWAY_ENABLED true" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification requires Flyway after baseline"
    Assert-RgMatch -Pattern "require_env_value SPRING_FLYWAY_TABLE trading_flyway_schema_history" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification requires Trading-owned Flyway history table"
    Assert-RgMatch -Pattern "require_env_value AGORA_MARKET_INTERNAL_TIMEOUT_MS 3000" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification keeps AgoraMarket internal API timeout bounded"
    Assert-RgNoMatch -Pattern 'matches expected temporary bootstrap value|must be \$expected until the Flyway baseline exists' -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "fixed-value env guard messages must stay generic because they also cover AgoraMarket timeout"
    Assert-RgMatch -Pattern "hardened schema env values" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents hardened schema env guard"
    Assert-RgMatch -Pattern "Deploy, server preflight, and verification require" -Paths @("docs/split-audit.md", "docs/deploy-runbook.md") -Description "hardened schema env guard is documented"
    Assert-RgNoMatch -Pattern "baseline-on-migrate=true|baseline-on-migrate" -Paths @("pom.xml") -Description "pom does not claim Flyway baseline is already enabled"
    Assert-RgNoMatch -Pattern "db/migrations|db_migration_history|V0[0-9]+__" -Paths @("src/main/java") -Description "stale migration path comments"
    Assert-RgNoMatch -Pattern "GET /internal/exchange-rates" -Paths @("SERVICE_BOUNDARY.md") -Description "service boundary uses externally callable internal API path"
    Assert-RgNoMatch -Pattern "(GET|POST) /internal/users|/api/internal/users" -Paths @("SERVICE_BOUNDARY.md", "INTERNAL_API_TODO.md", "SPLIT_PROGRESS.md") -Description "identity internal API stays out of the first trading split"
    Assert-RgMatch -Pattern "GET /api/internal/exchange-rates/usdt" -Paths @("SERVICE_BOUNDARY.md", "SPLIT_PROGRESS.md", "INTERNAL_API_TODO.md") -Description "exchange-rate internal API path is consistent"
    Assert-RgMatch -Pattern "Status: implemented in trading" -Paths @("INTERNAL_API_TODO.md") -Description "exchange-rate internal API TODO reflects current SDK implementation"
    Assert-RgNoMatch -Pattern "ExchangeRateProvider|CoinGeckoExchangeRateProvider|BinanceExchangeRateProvider|CoinMarketCapExchangeRateProvider|ExchangeRateServiceImpl\.scheduledRefreshRates|CoinGecko → Binance → CoinMarketCap" -Paths @("src/main/java", "docs", "README.md", "SPLIT_PROGRESS.md", "INTERNAL_API_TODO.md", "SERVICE_BOUNDARY.md") -Description "stale exchange-rate provider chain residue"
    Assert-RgMatch -Pattern "exchange-rate split contract" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "exchange-rate MCP description reflects internal SDK split contract"
    Assert-RgMatch -Pattern "old public exchange-rate provider chain was removed" -Paths @("docs/split-audit.md") -Description "split audit documents removed public exchange-rate provider chain"
    Assert-RgMatch -Pattern "internal SDK or static fallback only" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents exchange-rate runtime ownership"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL:http://127\.0\.0\.1:8080" -Paths @("src/main/resources/application.yml", "INTERNAL_API_TODO.md") -Description "AgoraMarket internal API default points at local server dependency port"
    Assert-RgMatch -Pattern "baseUrl = .*127\.0\.0\.1:8080" -Paths @("src/main/java/com/agora/config/AgoraMarketExchangeRateProperties.java") -Description "AgoraMarket exchange-rate Java fallback matches server dependency port"
    Assert-RgNoMatch -Pattern "agora_market\." -Paths @("src/main/java", "src/main/resources/application.yml") -Description "hardcoded legacy AgoraMarket database schema"
    Assert-RgMatch -Pattern "META_CONTROL_ML_SQL_SCHEMA=agora_market" -Paths @(".env.trading.secrets.example", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ML SQL schema is explicit in shared split deploy docs"
    Assert-RgMatch -Pattern "meta-control\.ml\.sql" -Paths @("src/main/java/com/agora/config/properties/MlSqlProperties.java", "src/main/resources/application.yml", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ML SQL schema/table names are configuration-backed"
    Assert-RgNoMatch -Pattern "localhost:8082|127\.0\.0\.1:8082" -Paths @("INTERNAL_API_TODO.md") -Description "internal API TODO has no stale AgoraMarket dependency port"
    Assert-RgMatch -Pattern 'api-key: \$\{MCP_API_KEY:\$\{TRADING_MCP_KEY:\}\}' -Paths @("src/main/resources/application.yml") -Description "TRADING_MCP_KEY maps to MCP dev auth key"
    Assert-RgMatch -Pattern 'ops-key: \$\{MCP_OPS_KEY:\$\{TRADING_MCP_KEY:\}\}' -Paths @("src/main/resources/application.yml") -Description "TRADING_MCP_KEY maps to MCP ops auth key"
    Assert-RgMatch -Pattern "localSmokeMcpAuthKeysAreConfigured" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves local-smoke MCP auth keys are configured"
    Assert-RgMatch -Pattern "actuatorMetricsRequireLocalhostOrMcpKey" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves actuator metrics filter requires localhost or MCP key"
    Assert-RgMatch -Pattern "setRemoteAddr\(`"203\.0\.113\.10`"\)" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "actuator metrics auth test uses non-local remote address"
    Write-Host "[verify] checking split boundary validators"
    & "$PSScriptRoot\verify_split_boundaries.ps1"
    Assert-RgMatch -Pattern "Split Guardrails Covered By Verification" -Paths @("docs/split-audit.md") -Description "split audit documents local deploy/schema/contract guards"
    Assert-RgMatch -Pattern "Split deploy guardrails stay documented" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents local split deploy/schema/contract guards"
    Assert-OnlyAllowedEnabledTrueFallbacks
    Assert-OnlyAllowedDefaultValueTrueProperties
    Assert-OnlyAllowedValueAnnotationTrueFallbacks
    Assert-OnlyAllowedEnvironmentPropertyDefaultTrueFallbacks
    Assert-OnlyAllowedSystemEnvDefaultTrueFallbacks
    Assert-RgMatch -Pattern "Remaining ``enabled:true`` fallbacks are deliberately limited" -Paths @("docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining enabled:true fallback classification is documented"
    Assert-RgMatch -Pattern "Remaining ``@DefaultValue\(`"true`"\)`` properties are deliberately limited" -Paths @("SPLIT_PROGRESS.md", "docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining @DefaultValue true fallback classification is documented"
    Assert-RgMatch -Pattern "Remaining ``@Value`` ``:true`` fallbacks are deliberately limited" -Paths @("SPLIT_PROGRESS.md", "docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining @Value true fallback classification is documented"
    Assert-RgMatch -Pattern "Remaining ``Environment.getProperty`` default-``true`` fallbacks are deliberately limited" -Paths @("SPLIT_PROGRESS.md", "docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining Environment.getProperty true fallback classification is documented"
    Assert-RgMatch -Pattern "STARTUP_BEAN_TIMING_ENABLED" -Paths @("SPLIT_PROGRESS.md", "docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining System.getenv default-true fallback classification is documented"
    Assert-RgMatch -Pattern '@Profile\("!local-smoke"\)' -Paths @("src/main/java/com/agora/config/TradingSchedulingConfig.java") -Description "local-smoke does not register scheduled tasks"
    Assert-RgMatch -Pattern "Scheduling disabled for local-smoke profile" -Paths @("src/main/java/com/agora/config/LocalSmokeSchedulingConfig.java", "scripts/smoke_local_health.ps1") -Description "local-smoke smoke logs prove scheduling is disabled"
    Assert-RgMatch -Pattern "localSmokeDoesNotRegisterScheduledTasks" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves local-smoke scheduling is disabled"
    Assert-RgMatch -Pattern "does not register scheduled tasks" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md") -Description "local-smoke scheduler exclusion is documented"
    Assert-RgMatch -Pattern "OCO modification" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md") -Description "local-smoke OCO scheduler side-effect guard is documented"

    Assert-RgNoMatch -Pattern "sed[^\r\n]*(8084|8085|/api/trading/|127\\.0\\.0\\.1)" -Paths @("deploy.sh") -Description "unsafe deploy nginx sed swap"
    Assert-RgMatch -Pattern "required env key missing or empty" -Paths @("deploy.sh") -Description "deploy fails fast on missing or empty required env key"
    Assert-RgMatch -Pattern "has unstaged changes; refusing to overwrite during deploy" -Paths @("deploy.sh") -Description "deploy refuses to overwrite unstaged server changes before reset"
    Assert-RgMatch -Pattern "has staged changes; refusing to overwrite during deploy" -Paths @("deploy.sh") -Description "deploy refuses to overwrite staged server changes before reset"
    Assert-RgMatch -Pattern "cleanup_new_instance" -Paths @("deploy.sh") -Description "deploy cleans new process and pid file on failure after startup"
    Assert-RgMatch -Pattern 'rm -f "app.pid.\$NEW_PORT"' -Paths @("deploy.sh") -Description "deploy removes new-port pid file on failed startup"
    Assert-RgMatch -Pattern "RUN_POST_DEPLOY_VERIFY" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy runs server verification after switching active metadata"
    Assert-RgMatch -Pattern "POST_DEPLOY_VERIFIED=1" -Paths @("deploy.sh") -Description "deploy records successful post-deploy verification before cleanup"
    Assert-RgMatch -Pattern "keeping old instance because post-deploy verification was not proven" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "deploy keeps previous instance when post-deploy verification is skipped"
    Assert-RgMatch -Pattern "keeping nginx backup because post-deploy verification was not proven" -Paths @("deploy.sh") -Description "deploy keeps nginx backup when post-deploy verification is skipped"
    Assert-RgMatch -Pattern 'VERIFY_ENV=\(' -Paths @("deploy.sh") -Description "deploy passes actual context into post-deploy server verifier"
    foreach ($pattern in @(
        'APP_DIR="\$APP_DIR"',
        'ENV_FILE="\$ENV_FILE"',
        'PORT_A="\$PORT_A"',
        'PORT_B="\$PORT_B"',
        'INTERNAL_CLIENT_POM="\$INTERNAL_CLIENT_POM"',
        'AGORA_MARKET_HEALTH_URL="\$AGORA_MARKET_HEALTH_URL"',
        'EXPECTED_AGORA_MARKET_BASE_URL="\$EXPECTED_AGORA_MARKET_BASE_URL"',
        'EXPECTED_TRADING_DATABASE="\$EXPECTED_TRADING_DATABASE"',
        'NGINX_CONF_GLOB="\$NGINX_CONF"',
        'RUN_SCHEMA_BASELINE_COMPARE="\$\{RUN_SCHEMA_BASELINE_COMPARE:-0\}"',
        'RUN_PREFLIGHT=0',
        'env "\$\{VERIFY_ENV\[@\]\}" bash "\$VERIFY_SCRIPT"'
    )) {
        Assert-RgMatch -Pattern $pattern -Paths @("deploy.sh") -Description "deploy post-verifier context marker $pattern"
    }
    Assert-RgMatch -Pattern "post-deploy verification failed; rolling back active metadata" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy rolls back active metadata when post-deploy verification fails"
    Assert-RgMatch -Pattern "post-deploy verifier missing" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy treats missing post-deploy verifier as rollback-worthy failure"
    Assert-RgMatch -Pattern "restoring nginx trading upstream after failed verification" -Paths @("deploy.sh", "docs/deploy-runbook.md") -Description "deploy restores nginx backup when post-deploy verification fails"
    Assert-RgMatch -Pattern "draining old instance after verification" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy drains previous instance only after post-deploy verification"
    Assert-RgMatch -Pattern "DEFAULT_PUBLIC_TRADING_HEALTH_URL" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "nginx deploy verifies public trading health by default"
    Assert-RgMatch -Pattern "PUBLIC_TRADING_HEALTH_URL=.*DEFAULT_PUBLIC_TRADING_HEALTH_URL" -Paths @("deploy.sh") -Description "post-deploy verify sets public trading health URL when nginx is updated"
    Assert-RgMatch -Pattern "invalid app.port value" -Paths @("deploy.sh") -Description "deploy rejects unknown active port state"
    Assert-RgMatch -Pattern "unknown active port" -Paths @("scripts/verify_server.sh") -Description "server verify rejects unknown active port state"
    Assert-RgMatch -Pattern "active per-port pid metadata missing" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "server verify requires active blue-green per-port pid metadata by default"
    Assert-RgMatch -Pattern "does not match app.pid" -Paths @("scripts/verify_server.sh") -Description "server verify rejects mismatched active per-port pid metadata"
    Assert-RgMatch -Pattern "deployed app.pid.*is not running" -Paths @("scripts/verify_server.sh") -Description "server verify fails when deployed pid metadata is stale"
    Assert-RgMatch -Pattern "is not listening on active port" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server verify fails when deployed pid does not own active port"
    Assert-RgMatch -Pattern "local MCP getMcpRegistryVersion passed" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server verify proves local MCP endpoint with MCP key"
    Assert-RgMatch -Pattern "REQUIRE_NGINX_TRADING_PATH" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server verify requires nginx trading path by default"
    Assert-RgMatch -Pattern 'REQUIRE_NGINX_SERVICE="\$\{REQUIRE_NGINX_SERVICE:-1\}"' -Paths @("scripts/verify_server.sh") -Description "server verify requires nginx service active by default"
    Assert-RgMatch -Pattern "REQUIRE_NGINX_SERVICE=0.*non-nginx" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "nginx service bypass is documented as non-nginx diagnostic only"
    Assert-RgMatch -Pattern "fail `"nginx service is not active" -Paths @("scripts/verify_server.sh") -Description "server verify fails when nginx service is not active by default"
    Assert-RgMatch -Pattern "nginx /api/trading/ location not found" -Paths @("scripts/verify_server.sh") -Description "server verify fails when nginx trading path is missing"
    Assert-RgMatch -Pattern "invalid TRADING_PORT" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer rejects unknown trading port"
    Assert-RgMatch -Pattern "require_cmd bash" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when bash is unavailable"
    Assert-RgMatch -Pattern "require_cmd grep" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when grep is unavailable"
    Assert-RgMatch -Pattern "require_cmd lsof" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when lsof is unavailable"
    Assert-RgMatch -Pattern "require_cmd ls" -Paths @("scripts/verify_server.sh", "scripts/preflight_server.sh") -Description "server preflight/verify fail fast when ls is unavailable for nginx glob checks"
    Assert-RgMatch -Pattern "require_cmd ps" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when ps is unavailable"
    Assert-RgMatch -Pattern "require_cmd tail" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when tail is unavailable"
    Assert-RgMatch -Pattern "require_cmd tr" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when tr is unavailable"
    foreach ($commandName in @("bash", "date", "env", "grep", "nohup", "sleep")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when $commandName is unavailable"
    }
    foreach ($commandName in @("cat", "kill", "mkdir", "rm")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("deploy.sh") -Description "deploy fails fast when $commandName is unavailable"
    }
    Assert-RgMatch -Pattern "require_cmd lsof" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when lsof is unavailable"
    Assert-RgMatch -Pattern "require_cmd seq" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when seq is unavailable for readiness loops"
    Assert-RgMatch -Pattern "require_cmd tail" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when tail is unavailable for failure diagnostics"
    Assert-RgMatch -Pattern "require_cmd sudo" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when sudo is unavailable for nginx swap"
    Assert-RgMatch -Pattern "require_cmd sudo" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer fails fast when sudo is unavailable"
    Assert-RgMatch -Pattern "require_cmd awk" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer fails fast when awk is unavailable"
    foreach ($commandName in @("cp", "mv", "nginx")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("deploy.sh") -Description "deploy fails fast when $commandName is unavailable for nginx swap"
    }
    foreach ($commandName in @("cp", "grep", "mv", "nginx", "rm")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer fails fast when $commandName is unavailable"
    }
    Assert-RgMatch -Pattern "require_cmd systemctl" -Paths @("deploy.sh", "scripts/install_nginx_path.sh", "scripts/verify_server.sh") -Description "nginx deploy/install/verify fail fast when systemctl is unavailable"
    Assert-RgMatch -Pattern "blue-green process launch, active metadata checks, health probes, nginx swaps, and post-verify parsing" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents deploy/preflight/server verify tool guard scope"
    Assert-RgMatch -Pattern "grep.*ls.*cp.*mv.*nginx.*rm" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents bootstrap/nginx file-update tool guard list"
    Assert-RgMatch -Pattern "internal-client pom missing" -Paths @("deploy.sh") -Description "deploy fails fast when AgoraMarket internal-client is missing"
    Assert-RgMatch -Pattern "internal-client pom missing" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when AgoraMarket internal-client is missing even when preflight is skipped"
    Assert-RgMatch -Pattern 'mvn -f "\$INTERNAL_CLIENT_POM" install' -Paths @("deploy.sh") -Description "deploy installs AgoraMarket internal-client before building trading"
    Assert-RgMatch -Pattern 'missing or empty.*in \$ENV_FILE' -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server preflight/verify require non-empty env keys"
    Assert-RgMatch -Pattern "env template available" -Paths @("scripts/bootstrap_server.sh") -Description "bootstrap uses tracked env template"
    foreach ($commandName in @("grep", "ls")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("scripts/bootstrap_server.sh") -Description "bootstrap fails fast when $commandName is unavailable"
    }
    Assert-RgMatch -Pattern "Bootstrap and nginx path installation fail fast" -Paths @("docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "bootstrap/nginx install tool guards are documented"
    Assert-RgMatch -Pattern "bootstrap and nginx path installation fail fast" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents bootstrap/nginx install tool guards"
    Assert-RgMatch -Pattern "schema baseline database comparison fails fast" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents schema compare tool guards"
    Assert-RgMatch -Pattern "Server verification and schema baseline comparison fail fast" -Paths @("docs/split-audit.md") -Description "split audit documents server verify/schema compare tool guards"
    Assert-RgMatch -Pattern "Legacy AgoraMarketAPI trading HTTP/MCP/scheduler parity inventory" -Paths @("docs/legacy-trading-parity-inventory.md", "SPLIT_PROGRESS.md") -Description "legacy trading parity inventory is documented"
    Assert-RgMatch -Pattern "/api/mcp" -Paths @("docs/legacy-trading-parity-inventory.md", "README.md", "scripts/smoke_mcp_parity.ps1") -Description "legacy parity docs and smoke use standalone MCP path"
    Assert-RgMatch -Pattern "Covered through .*McpTools|MCP-first" -Paths @("docs/legacy-trading-parity-inventory.md") -Description "legacy HTTP parity inventory records MCP-first replacement boundary"
    Assert-RgMatch -Pattern "Do not remove AgoraMarketAPI marketplace HTTP or internal exchange-rate APIs" -Paths @("docs/legacy-trading-parity-inventory.md") -Description "legacy parity inventory preserves marketplace/internal API boundary"
    foreach ($pattern in @(
        "36-tool",
        "smoke_mcp_parity_ssh\.ps1",
        "anti-wick guardrail coverage",
        "event-risk status",
        "signal-correctness diagnostics",
        "trailing-stop replay",
        "verify_post_deploy_issue_acceptance_ssh\.ps1 -RequireTrailingAcceptance",
        "SkipSplitAcceptance.*diagnostic-only",
        "must not be used as #1/#2/#3 closure evidence",
        "CLOSURE_READY OK",
        "output is not closure evidence"
    )) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/legacy-trading-parity-inventory.md") -Description "legacy parity inventory keeps current server-local issue acceptance handoff marker $pattern"
    }
    Assert-McpParityToolCoverage
    Assert-RgMatch -Pattern "Optional runtime safety toggles" -Paths @(".env.trading.secrets.example") -Description "server env template documents optional safety toggles"
    Assert-RgMatch -Pattern "optional safety key" -Paths @("scripts/validate_env_template.ps1") -Description "env template validator checks optional safety toggles"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.btc-price-move-indicator\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/BtcPriceMoveIndicatorCollector.java") -Description "BTC price-move indicator collector bean is explicit opt-in"
    Assert-RgMatch -Pattern "meta-control\.btc-price-move-indicator\.enabled:false" -Paths @("src/main/java/com/agora/scheduler/trading/BtcPriceMoveIndicatorCollector.java") -Description "BTC price-move indicator collector is explicit opt-in"
    Assert-RgMatch -Pattern "meta-control\.etf-pressure\.refresh-enabled:false" -Paths @("src/main/java/com/agora/service/indicator/impl/EtfPressureIndicator.java") -Description "ETF pressure scheduled refresh is explicit opt-in"
    Assert-RgMatch -Pattern "snap == null && refreshEnabled" -Paths @("src/main/java/com/agora/service/indicator/impl/EtfPressureIndicator.java") -Description "ETF pressure calculation fetch is explicit opt-in"
    Assert-RgMatch -Pattern "ETF pressure calculation no longer fetches Yahoo Finance data unless" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents ETF calculation fetch opt-in"
    Assert-RgMatch -Pattern "META_CONTROL_INDICATOR_HISTORY_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "market indicator history opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_BTC_PRICE_MOVE_INDICATOR_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "BTC price-move indicator opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_ETF_PRESSURE_REFRESH_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "ETF pressure refresh opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.hourly-orchestrator\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/HourlyOrchestrator.java") -Description "hourly orchestrator scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "meta-control\.hourly-orchestrator\.enabled:false" -Paths @("src/main/java/com/agora/scheduler/trading/HourlyOrchestrator.java") -Description "hourly orchestrator defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.composite-indicator\.scheduler-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/CompositeIndicatorScheduler.java") -Description "composite indicator scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "meta-control\.composite-indicator\.scheduler-enabled:false" -Paths @("src/main/java/com/agora/scheduler/trading/CompositeIndicatorScheduler.java") -Description "composite indicator scheduler defaults off"
    Assert-RgMatch -Pattern "META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "hourly orchestrator opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "composite indicator scheduler opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.market-indicator-attention\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MarketIndicatorAttentionScheduler.java") -Description "market-indicator attention scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "meta-control\.market-indicator-attention\.enabled:false" -Paths @("src/main/java/com/agora/scheduler/trading/MarketIndicatorAttentionScheduler.java") -Description "market-indicator attention scheduler defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean analysisEnabled' -Paths @("src/main/java/com/agora/config/properties/MarketFlipProperties.java") -Description "market-flip analysis defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean autoEscalateEnabled' -Paths @("src/main/java/com/agora/config/properties/MarketFlipProperties.java") -Description "market-flip auto-escalation defaults off"
    Assert-RgMatch -Pattern "META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-indicator attention opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/MarketFlipDetectorProperties.java") -Description "market-flip detector defaults off"
    Assert-RgMatch -Pattern "META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-flip detector opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/MlShadowProperties.java") -Description "ML shadow inference logging defaults off"
    Assert-RgMatch -Pattern "META_CONTROL_ML_SHADOW_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ML shadow opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.ml-protection\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MlSecondaryLoadGuardScheduler.java") -Description "ML secondary-load guard scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.ml-edge-watcher\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MlEdgeStalenessWatcher.java") -Description "ML edge staleness watcher scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean autoKillSecondaryLoad' -Paths @("src/main/java/com/agora/config/properties/MlProtectionProperties.java") -Description "ML protection connection auto-kill defaults off"
    Assert-RgMatch -Pattern "META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ML protection auto-kill opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.daily-ml-digest\.enabled",' -Paths @("src/main/java/com/agora/scheduler/trading/DailyMlPipelineDigest.java") -Description "daily ML digest scheduler bean uses the explicit opt-in key"
    Assert-RgMatch -Pattern 'havingValue = "true", matchIfMissing = false' -Paths @("src/main/java/com/agora/scheduler/trading/DailyMlPipelineDigest.java") -Description "daily ML digest scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "okx\.earn-topup\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/EarnTradingBufferTopUpScheduler.java") -Description "OKX Earn top-up scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'okx\.earn-topup\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/EarnTradingBufferTopUpScheduler.java") -Description "OKX Earn top-up method guard defaults off"
    Assert-RgMatch -Pattern 'okx\.earn-topup\.enabled:false' -Paths @("src/main/java/com/agora/service/trading/OkxEarnService.java") -Description "OKX Earn top-up service guard defaults off"
    Assert-RgMatch -Pattern 'if \(!topupEnabled\)' -Paths @("src/main/java/com/agora/service/trading/OkxEarnService.java") -Description "OKX Earn top-up service method hard-stops when disabled"
    Assert-RgMatch -Pattern "OKX_EARN_TOPUP_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "OKX Earn top-up opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "polymarket\.monitor\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/PolymarketMonitorScheduler.java") -Description "Polymarket monitor scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'polymarket\.monitor\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/PolymarketMonitorScheduler.java") -Description "Polymarket monitor method guards default off"
    Assert-RgMatch -Pattern "POLYMARKET_MONITOR_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "Polymarket monitor opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "position-exit-manager\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/PositionExitManagerScheduler.java") -Description "position-exit manager scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trailing-stop\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/TrailingStopScheduler.java") -Description "trailing-stop scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'position-exit-manager\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/PositionExitManagerScheduler.java") -Description "position-exit manager method guard defaults off"
    Assert-RgMatch -Pattern 'trailing-stop\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/TrailingStopScheduler.java") -Description "trailing-stop method guard defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/KlineDivergenceProperties.java") -Description "kline divergence alerting defaults off"
    Assert-RgMatch -Pattern "TRADING_KLINE_DIVERGENCE_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "kline divergence opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "if \(!props\.enabled\(\)\)" -Paths @("src/main/java/com/agora/scheduler/trading/KlineDivergenceMonitor.java", "src/main/java/com/agora/service/market/KlineDivergenceAlerter.java") -Description "kline divergence manual and alert paths have method-level opt-in guards"
    Assert-RgMatch -Pattern "META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "market-flip analysis opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "market-flip auto-escalation opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.market-flip\.analysis-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MarketFlipAnalysisScheduler.java") -Description "market-flip analysis scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.market-flip\.auto-escalate-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MarketFlipAutoEscalateScheduler.java") -Description "market-flip auto-escalation scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/AuditCleanupProperties.java", "src/main/java/com/agora/config/properties/KlinePruningProperties.java", "src/main/java/com/agora/config/properties/EphemeralCleanupProperties.java") -Description "cleanup jobs default off"
    Assert-RgMatch -Pattern "META_CONTROL_AUDIT_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "decision audit cleanup opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "KLINE_PRUNING_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "kline pruning opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_EPHEMERAL_CLEANUP_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "ephemeral cleanup opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgNoMatch -Pattern "scheduler\.system|com\.agora\.scheduler\.system" -Paths @("src", "docs", ".env.trading.secrets.example") -Description "stale scheduler.system package reference"
    Assert-RgNoMatch -Pattern "NightlyCleanupOrchestrator|scheduler\.system\.NightlyCleanupOrchestrator" -Paths @("src", "docs", ".env.trading.secrets.example") -Description "stale nightly cleanup orchestrator reference"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean flipDetectorEnabled' -Paths @("src/main/java/com/agora/config/properties/GeminiAdvisorProperties.java") -Description "Gemini hint flip detector defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean stalenessDetectorEnabled' -Paths @("src/main/java/com/agora/config/properties/GeminiAdvisorProperties.java") -Description "Gemini hint staleness detector defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/LongAiFilterProperties.java") -Description "LongAiFilter external market reads default off"
    Assert-RgMatch -Pattern "TRADING_LONG_AI_FILTER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "LongAiFilter opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/ShortAiFilterProperties.java") -Description "ShortAiFilter AI/MCP layer defaults off"
    Assert-RgMatch -Pattern "TRADING_SHORT_AI_FILTER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ShortAiFilter opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern 'trading\.ensemble-preview\.live-market-reads-enabled:false' -Paths @("src/main/java/com/agora/mcp/EnsembleMcpTools.java") -Description "ensemble preview live market reads default off"
    Assert-RgMatch -Pattern "if \(liveMarketReadsEnabled\)" -Paths @("src/main/java/com/agora/mcp/EnsembleMcpTools.java") -Description "ensemble preview live market reads are guarded"
    Assert-RgMatch -Pattern "TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ensemble preview live market read opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern 'trading\.market-data-mcp\.live-sentiment-enabled:false' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP live sentiment reads default off"
    Assert-RgMatch -Pattern "if \(!liveSentimentEnabled\)" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP live sentiment reads are guarded"
    Assert-RgMatch -Pattern 'disabledLiveSentimentMessage\("getPolymarketRisk"' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP Polymarket risk reads are guarded"
    Assert-RgMatch -Pattern 'disabledLiveSentimentMessage\("getFearGreedHistory"' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP Fear&Greed history reads are guarded"
    Assert-RgMatch -Pattern 'disabledLiveSentimentMessage\("backfillFearGreed"' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP Fear&Greed backfill reads are guarded"
    Assert-RgMatch -Pattern 'disabledLiveSentimentMessage\("analyzeStrategyTrades"' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP F&G trade analysis reads are guarded"
    Assert-RgMatch -Pattern "TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-data MCP live sentiment opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern 'trading\.market-data-mcp\.external-health-probes-enabled:false' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP external health probes default off"
    Assert-RgMatch -Pattern "if \(externalHealthProbesEnabled\)" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP external health probes are guarded"
    Assert-RgMatch -Pattern "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-data MCP external health probe opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern 'trading\.market-data-mcp\.external-backfills-enabled:false' -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP external backfills default off"
    Assert-RgMatch -Pattern "if \(!externalBackfillsEnabled\)" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP external backfills are guarded"
    foreach ($tool in @("backfillOkxKlines", "backfillOkxKlinesRange", "backfillDexFlow", "backfillFundingRateHistory", "backfillLongShortRatioHistory", "backfillFredMacro", "backfillHyperliquidFunding", "backfillOpenInterest", "importPolymarketHistory", "backfillCoinalyzeLiquidation")) {
        Assert-RgMatch -Pattern "disabledExternalBackfillMessage\(`"$tool`"" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "market-data MCP external backfill tool $tool is guarded"
    }
    Assert-RgMatch -Pattern "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-data MCP external backfill opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean statusNotifyEnabled' -Paths @("src/main/java/com/agora/config/properties/EventRiskControlProperties.java") -Description "EventRiskControl state-change notifications default off"
    Assert-RgMatch -Pattern "if \(!properties\.statusNotifyEnabled\(\)\) return" -Paths @("src/main/java/com/agora/service/trading/EventRiskActionOrchestrator.java") -Description "EventRiskControl state-change notification has method-level opt-in guard"
    Assert-RgMatch -Pattern "EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "EventRiskControl notification opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.gemini-advisor\.flip-detector-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/GeminiHintFlipDetector.java") -Description "Gemini hint flip scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.gemini-advisor\.staleness-detector-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/GeminiHintStalenessDetector.java") -Description "Gemini hint staleness scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "if \(!props\.flipDetectorEnabled\(\)\) return" -Paths @("src/main/java/com/agora/scheduler/trading/GeminiHintFlipDetector.java") -Description "Gemini hint flip scheduler retains method-level opt-in guard"
    Assert-RgMatch -Pattern "if \(!props\.stalenessDetectorEnabled\(\)\) return" -Paths @("src/main/java/com/agora/scheduler/trading/GeminiHintStalenessDetector.java") -Description "Gemini hint staleness scheduler retains method-level opt-in guard"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/AiStrategyDiscoveryProperties.java") -Description "AI strategy discovery scheduler defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "ai\.strategy\.discovery\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/AiStrategyDiscoveryScheduler.java") -Description "AI strategy discovery scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "AI_STRATEGY_DISCOVERY_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "AI strategy discovery opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern 'trading\.exploration\.monitor\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/AutonomousExplorationMonitorScheduler.java") -Description "autonomous exploration monitor defaults off"
    Assert-RgMatch -Pattern 'trading\.exploration\.loop\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/AutonomousExplorationLoopScheduler.java") -Description "autonomous exploration loop defaults off"
    Assert-RgMatch -Pattern 'trading\.exploration\.rollout\.auto-enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/AutoExplorationRolloutScheduler.java") -Description "autonomous exploration rollout defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.exploration\.monitor\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/AutonomousExplorationMonitorScheduler.java") -Description "autonomous exploration monitor scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.exploration\.rollout\.auto-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/AutoExplorationRolloutScheduler.java") -Description "autonomous exploration rollout scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern "TRADING_EXPLORATION_MONITOR_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "autonomous exploration monitor opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_EXPLORATION_LOOP_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "autonomous exploration loop opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "autonomous exploration rollout opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.autonomous\.digest\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/DailyAutonomousTradingDigestScheduler.java") -Description "daily autonomous digest scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.autonomous\.digest\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/DailyAutonomousTradingDigestScheduler.java") -Description "daily autonomous digest defaults off"
    Assert-RgMatch -Pattern 'trading\.autonomous\.digest\.telegram-enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/DailyAutonomousTradingDigestScheduler.java") -Description "daily autonomous digest Telegram defaults off"
    Assert-RgMatch -Pattern "TRADING_AUTONOMOUS_DIGEST_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "daily autonomous digest opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "daily autonomous digest Telegram opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.score-buy\.forming-day\.notification\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyFormingDayNotificationScheduler.java") -Description "ScoreBuy forming-day notification scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.score-buy\.forming-day\.notification\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyFormingDayNotificationScheduler.java") -Description "ScoreBuy forming-day notification defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.score-buy\.pre-position\.execution\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyPrePositionAutoExecutionScheduler.java") -Description "ScoreBuy pre-position execution scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.score-buy\.pre-position\.execution\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyPrePositionAutoExecutionScheduler.java") -Description "ScoreBuy pre-position execution defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.score-buy\.confirmed-deploy\.execution\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyConfirmedDeployAutoExecutionScheduler.java") -Description "ScoreBuy confirmed-deploy execution scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.score-buy\.confirmed-deploy\.execution\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyConfirmedDeployAutoExecutionScheduler.java") -Description "ScoreBuy confirmed-deploy execution defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.score-buy\.post-scout-add\.execution\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyPostScoutAutoAddExecutionScheduler.java") -Description "ScoreBuy post-scout add execution scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.score-buy\.post-scout-add\.execution\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/ScoreBuyPostScoutAutoAddExecutionScheduler.java") -Description "ScoreBuy post-scout add execution defaults off"
    Assert-RgMatch -Pattern "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ScoreBuy forming-day notification opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ScoreBuy pre-position execution opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ScoreBuy confirmed-deploy execution opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "ScoreBuy post-scout add execution opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/EventScanNotificationProperties.java", "src/main/java/com/agora/config/properties/ExecutionEventProperties.java", "src/main/java/com/agora/config/properties/GridRecoveryProperties.java") -Description "event-scan, execution-event, and grid recovery schedulers default off"
    Assert-RgMatch -Pattern '@DefaultValue\("true"\) boolean dryRun' -Paths @("src/main/java/com/agora/config/properties/EventScanNotificationProperties.java") -Description "event-scan notification defaults dry-run"
    Assert-RgMatch -Pattern '@DefaultValue\("true"\) boolean notificationDryRun' -Paths @("src/main/java/com/agora/config/properties/ExecutionEventProperties.java") -Description "execution-event notification defaults dry-run"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "event-scan\.notification\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/EventScanNotificationScheduler.java") -Description "event-scan notification scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "execution-event\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ExecutionEventScheduler.java") -Description "execution-event scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "wick-capture\.shadow\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/WickCaptureShadowObserverScheduler.java") -Description "wick-capture shadow observer scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'wick-capture\.shadow\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/WickCaptureShadowObserverScheduler.java") -Description "wick-capture shadow observer defaults off"
    Assert-RgMatch -Pattern 'wick-capture\.shadow\.bootstrap-enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/WickCaptureShadowObserverScheduler.java") -Description "wick-capture historical bootstrap defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "shadow-cleanup\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ShadowSignalCleanupScheduler.java") -Description "shadow signal cleanup scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'shadow-cleanup\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/ShadowSignalCleanupScheduler.java") -Description "shadow signal cleanup defaults off"
    Assert-RgMatch -Pattern "EVENT_SCAN_NOTIFICATION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "event-scan notification opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "EXECUTION_EVENT_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "execution-event scheduler opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "WICK_CAPTURE_SHADOW_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "wick-capture shadow opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "wick-capture bootstrap opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "SHADOW_CLEANUP_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "shadow cleanup opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "GRID_RECOVERY_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "grid recovery opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.daily-tg-report\.enabled", havingValue = "true"\)' -Paths @("src/main/java/com/agora/scheduler/trading/DailyTgReportOrchestrator.java") -Description "daily TG report scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.btc-price-move-alert\.enabled", havingValue = "true"\)' -Paths @("src/main/java/com/agora/scheduler/trading/BtcPriceMoveAlertScheduler.java") -Description "BTC price-move alert scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.grid\.auto-rebalance-scheduler\.enabled", havingValue = "true"\)' -Paths @("src/main/java/com/agora/scheduler/trading/GridAutoRebalanceScheduler.java") -Description "grid auto-rebalance scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "grid\.recovery\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/GridOrphanRecoveryScanner.java") -Description "grid orphan recovery scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(prefix = "trading\.funding-arb", name = "enabled", havingValue = "true"\)' -Paths @("src/main/java/com/agora/scheduler/trading/FundingArbScheduler.java") -Description "funding-arb scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "market-signal\.risk-card\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/MarketSignalRiskCardScheduler.java") -Description "market-signal risk-card scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/MarketSignalRiskCardProperties.java") -Description "market-signal risk-card scheduler defaults off"
    Assert-RgMatch -Pattern '@DefaultValue\("true"\) boolean dryRun' -Paths @("src/main/java/com/agora/config/properties/MarketSignalRiskCardProperties.java") -Description "market-signal risk-card defaults dry-run"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.wai\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/WashoutAccumulationIndexScheduler.java") -Description "WAI scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.wai\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/WashoutAccumulationIndexScheduler.java") -Description "WAI scheduler defaults off"
    Assert-RgMatch -Pattern "TRADING_DAILY_TG_REPORT_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "daily TG report opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_BTC_PRICE_MOVE_ALERT_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "BTC price-move alert opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "MARKET_SIGNAL_RISK_CARD_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-signal risk-card opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "MARKET_SIGNAL_RISK_CARD_DRY_RUN" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "market-signal risk-card dry-run key is documented, validated, and forced in local smoke"
    Assert-RgMatch -Pattern "TRADING_WAI_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "WAI scheduler opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "grid auto-rebalance scheduler opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_FUNDING_ARB_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "funding-arb scheduler opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/ShortSqueezeAlertProperties.java") -Description "short-squeeze alert defaults off in code"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean takerBuyCollectorEnabled' -Paths @("src/main/java/com/agora/config/properties/ShortSqueezeAlertProperties.java") -Description "Binance taker-buy collector defaults off in code"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.short-squeeze-alert\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/ShortSqueezeAlertScheduler.java") -Description "short-squeeze alert scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.short-squeeze-alert\.taker-buy-collector-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/BinanceSpotTakerBuyCollector.java") -Description "Binance taker-buy collector bean is explicit opt-in"
    Assert-RgMatch -Pattern "if \(!props\.takerBuyCollectorEnabled\(\)\) return" -Paths @("src/main/java/com/agora/scheduler/trading/BinanceSpotTakerBuyCollector.java") -Description "Binance taker-buy collector scheduled method has method-level opt-in guard"
    Assert-RgMatch -Pattern 'meta-control\.attention-weekly-digest\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/AttentionRuleWeeklyDigest.java") -Description "attention weekly digest method guard defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "meta-control\.scorecard-digest\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/WeeklyScorecardDigest.java") -Description "weekly scorecard digest scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'meta-control\.scorecard-digest\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/WeeklyScorecardDigest.java") -Description "weekly scorecard digest method guard defaults off"
    Assert-RgMatch -Pattern "if \(!enabled\) return" -Paths @("src/main/java/com/agora/scheduler/trading/AttentionRuleWeeklyDigest.java", "src/main/java/com/agora/scheduler/trading/WeeklyScorecardDigest.java") -Description "weekly trading digest schedulers have method-level opt-in guards"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/AttributionProperties.java") -Description "meta-control attribution defaults off"
    Assert-RgMatch -Pattern "if \(!props\.enabled\(\)\) return" -Paths @("src/main/java/com/agora/scheduler/trading/MetaControlAttributionScheduler.java") -Description "attribution startup backfill has method-level opt-in guard"
    Assert-RgMatch -Pattern 'meta-control\.ml-materialized-refresh\.startup-check-enabled:false' -Paths @("src/main/java/com/agora/service/ml/SignalTrainingMaterializedRefreshService.java") -Description "ML materialized startup refresh defaults off"
    Assert-RgMatch -Pattern "if \(!startupCheckEnabled\)" -Paths @("src/main/java/com/agora/service/ml/SignalTrainingMaterializedRefreshService.java") -Description "ML materialized startup refresh has method-level opt-in guard"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(' -Paths @("src/main/java/com/agora/config/MarketWsAutoSubscriber.java", "src/main/java/com/agora/config/CoinalyzeBackfillRunner.java", "src/main/java/com/agora/config/CompositeIndicatorBackfillRunner.java", "src/main/java/com/agora/config/DexFlowBackfillRunner.java", "src/main/java/com/agora/config/HyperliquidFundingBackfillRunner.java") -Description "startup WS/backfill beans are property-gated"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(prefix = "market\.ws\.auto-subscribe", name = "enabled", havingValue = "true"\)' -Paths @("src/main/java/com/agora/config/MarketWsAutoSubscriber.java") -Description "market WS auto-subscriber bean is gated by the split-safe auto-subscribe key"
    Assert-StartupRunnersAreSplitSafe
    Assert-RgMatch -Pattern "if \(!properties\.isEnabled\(\)\)" -Paths @("src/main/java/com/agora/config/WsSubscriptionSyncer.java") -Description "WS subscription syncer has method-level opt-in guard"
    Assert-RgMatch -Pattern "META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "attention weekly digest opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_SCORECARD_DIGEST_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "scorecard digest opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_ATTRIBUTION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "attribution startup/hourly opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md") -Description "ML materialized startup refresh opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.live-signal\.retry-notification\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/LiveSignalRetryScheduler.java") -Description "live-signal retry notification scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.live-signal\.retry-notification\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/LiveSignalRetryScheduler.java") -Description "live-signal retry notification method guard defaults off"
    Assert-RgMatch -Pattern "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "live-signal retry notification opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.event-calendar\.freshness-notification-enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/EventCalendarFreshnessScheduler.java") -Description "event-calendar freshness scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.event-calendar\.freshness-notification-enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/EventCalendarFreshnessScheduler.java") -Description "event-calendar freshness notification method guard defaults off"
    Assert-RgMatch -Pattern "TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "event-calendar freshness notification opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern '@DefaultValue\("false"\) boolean enabled' -Paths @("src/main/java/com/agora/config/properties/TradingGridProperties.java") -Description "grid manager defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.grid\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/GridManagerScheduler.java") -Description "grid manager scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@Scheduled\(fixedDelayString = "\$\{trading\.grid\.check-interval-ms:300000\}"\)' -Paths @("src/main/java/com/agora/scheduler/trading/GridManagerScheduler.java") -Description "grid manager scheduler owns fixed-delay trigger"
    Assert-RgNoMatch -Pattern "@Scheduled" -Paths @("src/main/java/com/agora/service/trading/GridManagerService.java") -Description "grid manager service must not self-register scheduled work"
    Assert-RgMatch -Pattern "if \(!props\.enabled\(\)\) return" -Paths @("src/main/java/com/agora/service/trading/GridManagerService.java") -Description "grid manager callable entry point keeps method-level opt-in guard"
    Assert-RgMatch -Pattern 'market\.liquidation-ws\.enabled:false' -Paths @("src/main/java/com/agora/service/market/OkxLiquidationWsService.java") -Description "OKX liquidation WebSocket defaults off"
    Assert-RgMatch -Pattern "if \(!enabled\) return" -Paths @("src/main/java/com/agora/service/market/OkxLiquidationWsService.java") -Description "OKX liquidation WebSocket scheduled maintenance has method-level opt-in guard"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "signal-verification\.scheduler\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/SignalOutcomeVerifierScheduler.java") -Description "signal outcome verifier scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'signal-verification\.scheduler\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/SignalOutcomeVerifierScheduler.java") -Description "signal outcome verifier method guard defaults off"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "agora\.alpha-tracker\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/AlphaPromotionTrackerScheduler.java") -Description "alpha promotion scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'agora\.alpha-tracker\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/AlphaPromotionTrackerScheduler.java") -Description "alpha promotion scheduled tracker defaults off"
    Assert-RgNoMatch -Pattern "@Scheduled" -Paths @("src/main/java/com/agora/service/diagnostic/AlphaPromotionTracker.java") -Description "alpha promotion tracker service must not self-register scheduled work"
    Assert-RgMatch -Pattern "SIGNAL_VERIFICATION_SCHEDULER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "signal verifier opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "AGORA_ALPHA_TRACKER_ENABLED" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "alpha tracker opt-in key is documented, validated, and cleared in local smoke"
    Assert-RgMatch -Pattern "TRADING_MARKET_DATA_COINALYZE_API_KEY" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1") -Description "Coinalyze API key uses Spring-bound trading.market-data env key"
    Assert-RgNoMatch -Pattern "EXTERNAL_COINALYZE_API_KEY|external\.coinalyze\.api-key" -Paths @(".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "stale Coinalyze env key"
    Assert-RgNoMatch -Pattern "KbDailyExportProperties|kb\.daily-export|KB_DAILY_EXPORT" -Paths @("src/main/java", ".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale KB daily-export scheduler config"
    Assert-RgNoMatch -Pattern "KbPostDeployAuditProperties|kb\.post-deploy-audit|KB_POST_DEPLOY_AUDIT" -Paths @("src/main/java", ".env.trading.secrets.example", "scripts/validate_env_template.ps1", "scripts/smoke_local_health.ps1", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale KB post-deploy audit listener config"
    Assert-RgNoMatch -Pattern "OiBackfillService" -Paths @("src/main/java", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale one-shot OKX OI backfill service"
    Assert-RgNoMatch -Pattern "SqiBackfillRunner" -Paths @("src/main/java", "docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale disabled SQI startup backfill runner"
    Assert-RgNoMatch -Pattern "KILL QUERY|killSafeExpectedReportQueries|SafeKillResult|safeKill" -Paths @("src/main/java", ".env.trading.secrets.example") -Description "DB slow-query monitor must remain read-only with no safe-kill side effect"
    Assert-RgMatch -Pattern "DB slow-query monitoring is read-only" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "DB slow-query read-only split boundary is documented"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.gemini-advisor\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/GeminiMarketAdvisorScheduler.java") -Description "Gemini advisor scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern '@Scheduled\(cron = "\$\{trading\.gemini-advisor\.cron:0 5 \*/8 \* \* \*\}", zone = "UTC"\)' -Paths @("src/main/java/com/agora/scheduler/trading/GeminiMarketAdvisorScheduler.java") -Description "Gemini advisor scheduler owns cron trigger"
    Assert-RgNoMatch -Pattern "@Scheduled" -Paths @("src/main/java/com/agora/service/ai/GeminiMarketAdvisor.java") -Description "Gemini advisor service must not self-register scheduled work"
    Assert-RgMatch -Pattern "normalizedContextPath" -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java") -Description "LocalMcpClient normalizes configured context path"
    Assert-RgMatch -Pattern 'normalizedContextPath\(\) \+ "/mcp"' -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java") -Description "LocalMcpClient calls MCP under configured context path"
    Assert-RgMatch -Pattern "POST /api/mcp" -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "MCP docs use canonical trading MCP context path"
    Assert-RgNoMatch -Pattern 'POST /api/trading/mcp|localhost:" \+ serverPort \+ "/api/trading/mcp' -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "stale /api/trading/mcp path"
    Assert-RgMatch -Pattern '"\/mcp",' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "Spring Security permits bare MCP endpoint for MCP filter auth"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.tiny-live\.auto-execution\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/TinyLiveAutoExecutionScheduler.java") -Description "TinyLive auto-execution scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.tiny-live\.auto-execution\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/TinyLiveAutoExecutionScheduler.java") -Description "TinyLive auto-execution method guard defaults off"

    Write-Host "[verify] checking shell script syntax"
    $bash = Resolve-BashCommand
    $shellScripts = @("deploy.sh") + @(Get-ChildItem -LiteralPath "scripts" -Filter "*.sh" | ForEach-Object { "scripts/$($_.Name)" })
    & $bash -n @shellScripts

    Write-Host "[verify] checking PowerShell script syntax"
    $powerShellScripts = Get-ChildItem -LiteralPath "scripts" -Filter "*.ps1"
    foreach ($script in $powerShellScripts) {
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$parseErrors) | Out-Null
        if ($parseErrors.Count -gt 0) {
            Write-Error "PowerShell syntax check failed: $($script.FullName)"
            $parseErrors | ForEach-Object { Write-Error $_ }
            throw "PowerShell syntax check failed"
        }
    }

    Write-Host "[verify] checking nginx route rewrite regression"
    & powershell -NoProfile -ExecutionPolicy Bypass -File "scripts/test_nginx_route_rewrite.ps1"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx route rewrite regression test failed with exit code $LASTEXITCODE"
    }

    Assert-RgMatch -Pattern '@ActiveProfiles\("local-smoke"\)' -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test uses local-smoke profile"

    Assert-RgNoMatch -Pattern "@SpringBootTest\(properties|spring\.datasource\.url|market\.liquidation-ws\.enabled|trading\.tiny-live\.auto-execution\.enabled" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "inline local-smoke duplicate properties in context test"

    foreach ($providerFile in @(
        "src/main/java/com/agora/service/ai/router/GeminiFlashProvider.java",
        "src/main/java/com/agora/service/ai/router/GroqLlamaProvider.java",
        "src/main/java/com/agora/service/ai/router/ClaudeSonnetProvider.java"
    )) {
        Assert-RgMatch -Pattern '@Profile\("!local-smoke"\)' -Paths @($providerFile) -Description "external AI provider excluded from local-smoke $providerFile"
    }

    foreach ($pattern in @(
        "META_CONTROL_ML_PROTECTION_ENABLED",
        "META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD",
        "META_CONTROL_ML_SHADOW_ENABLED",
        "META_CONTROL_ML_EDGE_WATCHER_ENABLED",
        "META_CONTROL_ML_AUTORETRAIN_ENABLED",
        "META_CONTROL_DAILY_ML_DIGEST_ENABLED",
        "TRADING_GEMINI_ADVISOR_ENABLED",
        "TRADING_GEMINI_ADVISOR_FLIP_DETECTOR_ENABLED",
        "TRADING_GEMINI_ADVISOR_STALENESS_DETECTOR_ENABLED",
        "TRADING_LONG_AI_FILTER_ENABLED",
        "TRADING_SHORT_AI_FILTER_ENABLED",
        "TRADING_ENSEMBLE_PREVIEW_LIVE_MARKET_READS_ENABLED",
        "TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED",
        "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED",
        "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
        "TRADING_MCP_KEY",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_JPA_HIBERNATE_DDL_AUTO",
        "SPRING_FLYWAY_ENABLED",
        "TRADING_SCHEDULER_POOL_SIZE",
        "TRADING_CORS_ALLOWED_ORIGINS",
        "META_CONTROL_ML_SQL_SCHEMA",
        "META_CONTROL_ML_SQL_SIGNAL_SCORER_TRAINING_TABLE",
        "META_CONTROL_ML_SQL_WEEKLY_RETRAIN_TRAINING_VIEW",
        "EVENT_RISK_CONTROL_STATUS_NOTIFY_ENABLED",
        "EXTERNAL_ALCHEMY_API_KEY",
        "EXTERNAL_COINGECKO_DEMO_API_KEY",
        "EXTERNAL_ETHERSCAN_API_KEY",
        "EXTERNAL_FRED_API_KEY",
        "EXTERNAL_THEGRAPH_API_KEY",
        "TRADING_OKX_ENABLED",
        "TRADING_OCO_POLLER_ENABLED",
        "TRADING_OKX_SECRET_KEY",
        "TRADING_OKX_PASSPHRASE",
        "TRADING_BINANCE_ENABLED",
        "TRADING_BINANCE_API_KEY",
        "TRADING_BINANCE_SECRET_KEY",
        "MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED",
        "MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS",
        "TRADING_GRID_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED",
        "TRADING_RUNTIME_EVIDENCE_ENABLED",
        "TRADING_BTC_DONCHIAN_SHADOW_MODE",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED",
        "TRADING_DISCOVERY_AI_SUGGESTIONS_ENABLED",
        "TRADING_KLINE_DIVERGENCE_ENABLED",
        "POSITION_EXIT_MANAGER_DRY_RUN",
        "TRAILING_STOP_DRY_RUN",
        "server.port=",
        "agora-market.internal-api-key=",
        "agora-market.timeout-ms=3000",
        "spring.datasource.url=jdbc:h2:mem:trading-local-smoke",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.task.scheduling.pool.size=1",
        "app.cors.allowed-origins=http://localhost:",
        "meta-control.ml.sql.schema=agora_market",
        "meta-control.ml.sql.signal-scorer-training-table=bt_signal_training_v8_mat",
        "meta-control.ml.sql.weekly-retrain-training-view=vw_signal_training_v2",
        "anthropic.api.key=",
        "jina.api.key=",
        "exchange-rate.coinmarketcap.api-key=",
        "trading.binance.api-key=",
        "meta-control.indicator-history.enabled=false",
        "meta-control.btc-price-move-indicator.enabled=false",
        "meta-control.etf-pressure.refresh-enabled=false",
        "meta-control.audit.enabled=false",
        "kline-pruning.enabled=false",
        "trading.ephemeral-cleanup.enabled=false",
        "meta-control.ml-protection.enabled=false",
        "meta-control.ml-protection.auto-kill-secondary-load=false",
        "meta-control.ml-shadow.enabled=false",
        "meta-control.ml-edge-watcher.enabled=false",
        "meta-control.ml-autoretrain.enabled=false",
        "meta-control.daily-ml-digest.enabled=false",
        "trading.gemini-advisor.enabled=false",
        "trading.gemini-advisor.flip-detector-enabled=false",
        "trading.gemini-advisor.staleness-detector-enabled=false",
        "trading.long-ai-filter.enabled=false",
        "trading.short-ai-filter.enabled=false",
        "trading.ensemble-preview.live-market-reads-enabled=false",
        "external.alchemy.api-key=",
        "external.coingecko.demo-api-key=",
        "external.etherscan.api-key=",
        "external.fred.api-key=",
        "external.thegraph.api-key=",
        "trading.okx.secret-key=",
        "trading.okx.passphrase=",
        "trading.binance.secret-key=",
        "trading.market-data-mcp.live-sentiment-enabled=false",
        "trading.market-data-mcp.external-health-probes-enabled=false",
        "trading.market-data-mcp.external-backfills-enabled=false",
        "event-risk-control.status-notify-enabled=false",
        "trading.grid.enabled=false",
        "trading.tiny-live.auto-execution.enabled=false",
        "trading.tiny-live.auto-execution.dry-run=true",
        "mcp.guardian-live-actions-enabled=false",
        "trading.runtime-evidence.enabled=false",
        "trading.btc-donchian-shadow.mode=OFF",
        "trading.discovery.ai-suggestions.enabled=false",
        "trading.kline-divergence.enabled=false",
        "market.ws.auto-subscribe.warm-up-enabled=false",
        "market.ws.auto-subscribe.providers=okx",
        "position-exit-manager.dry-run=true",
        "trailing-stop.dry-run=true"
    )) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_health.ps1") -Description "local-smoke clears high-risk split runtime key $pattern"
    }

    foreach ($pattern in @("Assert-LogContains", "Assert-LogNotContains", "Assert-McpContentContains", "Invoke-McpTool", "Stop-ProcessTree", "Get-CimInstance Win32_Process", "Stop-ProcessTree -RootPid", "SetEnvironmentVariable", "AGORA_MARKET_INTERNAL_API_KEY", "MCP_API_KEY", "MCP_OPS_KEY", "TRADING_OKX_API_KEY", "TRADING_BINANCE_API_KEY", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHANNEL_ID", "TELEGRAM_BOT_CHANNEL_ID", "GEMINI_API_KEY", "GROQ_API_KEY", "ANTHROPIC_API_KEY", "JINA_API_KEY", "TRADING_MARKET_DATA_COINALYZE_API_KEY", "EXCHANGE_RATE_COINMARKETCAP_API_KEY", "META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED", "META_CONTROL_SCORECARD_DIGEST_ENABLED", "META_CONTROL_STARTUP_BACKFILL_COINALYZE_ENABLED", "META_CONTROL_STARTUP_BACKFILL_COMPOSITE_INDICATOR_ENABLED", "META_CONTROL_STARTUP_BACKFILL_DEX_FLOW_ENABLED", "META_CONTROL_STARTUP_BACKFILL_HYPERLIQUID_FUNDING_ENABLED", "META_CONTROL_ATTRIBUTION_ENABLED", "META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED", "META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED", "META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED", "META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED", "META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED", "META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED", "META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED", "META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD", "META_CONTROL_ML_SHADOW_ENABLED", "MARKET_WS_AUTO_SUBSCRIBE_ENABLED", "MARKET_LIQUIDATION_WS_ENABLED", "OKX_EARN_TOPUP_ENABLED", "POLYMARKET_MONITOR_ENABLED", "TRADING_EXPLORATION_MONITOR_ENABLED", "TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED", "TRADING_EXPLORATION_LOOP_ENABLED", "TRADING_EXPLORATION_LOOP_TELEGRAM_ENABLED", "TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED", "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED", "TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION", "TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE", "TRADING_AUTONOMOUS_DIGEST_ENABLED", "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED", "TRADING_AUTONOMOUS_DIGEST_SEVERE_SCAN_ENABLED", "TRADING_AUTONOMOUS_DIGEST_SNAPSHOT_REFRESH_ENABLED", "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED", "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED", "TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED", "EVENT_SCAN_NOTIFICATION_ENABLED", "EVENT_SCAN_NOTIFICATION_DRY_RUN", "EXECUTION_EVENT_ENABLED", "EXECUTION_EVENT_NOTIFICATION_DRY_RUN", "WICK_CAPTURE_SHADOW_ENABLED", "WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED", "SHADOW_CLEANUP_ENABLED", "GRID_RECOVERY_ENABLED", "TRADING_DAILY_TG_REPORT_ENABLED", "TRADING_BTC_PRICE_MOVE_ALERT_ENABLED", "MARKET_SIGNAL_RISK_CARD_ENABLED", "MARKET_SIGNAL_RISK_CARD_DRY_RUN", "TRADING_WAI_ENABLED", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED", "TRADING_FUNDING_ARB_ENABLED", "TRAILING_STOP_ENABLED", "POSITION_EXIT_MANAGER_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_TAKER_BUY_COLLECTOR_ENABLED", "SIGNAL_VERIFICATION_SCHEDULER_ENABLED", "AGORA_ALPHA_TRACKER_ENABLED", "AI_STRATEGY_DISCOVERY_ENABLED", "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED", "TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED", "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED", "api/mcp", "getMcpRegistryVersion", "getMarketSentiment", "getSystemHealth", "backfillOkxKlines", "TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true", "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true", "Authorization = `"Bearer local-smoke-mcp`"", "spring-boot.run.arguments", "agora-market.base-url=http://127.0.0.1:0", "mcp.api-key=local-smoke-mcp", "mcp.ops-key=local-smoke-mcp", "gemini.api.key=", "groq.api.key=", "trading.market-data.coinalyze.api-key=", "trading.okx.api-key=", "trading.oco-poller.enabled=false", "telegram.bot.token=", "telegram.bot.channel-id=", "meta-control.attention-weekly-digest.enabled=false", "meta-control.scorecard-digest.enabled=false", "meta-control.startup-backfill.coinalyze.enabled=false", "meta-control.startup-backfill.composite-indicator.enabled=false", "meta-control.startup-backfill.dex-flow.enabled=false", "meta-control.startup-backfill.hyperliquid-funding.enabled=false", "meta-control.attribution.enabled=false", "meta-control.ml-materialized-refresh.startup-check-enabled=false", "meta-control.hourly-orchestrator.enabled=false", "meta-control.composite-indicator.scheduler-enabled=false", "meta-control.market-indicator-attention.enabled=false", "meta-control.market-flip-detector.enabled=false", "meta-control.market-flip.analysis-enabled=false", "meta-control.market-flip.auto-escalate-enabled=false", "meta-control.ml-protection.auto-kill-secondary-load=false", "meta-control.ml-shadow.enabled=false", "market.ws.auto-subscribe.enabled=false", "market.liquidation-ws.enabled=false", "okx.earn-topup.enabled=false", "polymarket.monitor.enabled=false", "trading.exploration.monitor.enabled=false", "trading.exploration.monitor.telegram.enabled=false", "trading.exploration.loop.enabled=false", "trading.exploration.loop.telegram.enabled=false", "trading.exploration.loop.production.enabled=false", "trading.exploration.rollout.auto-enabled=false", "trading.exploration.rollout.allow-production-promotion=false", "trading.exploration.rollout.allow-cap-increase=false", "trading.autonomous.digest.enabled=false", "trading.autonomous.digest.telegram-enabled=false", "trading.autonomous.digest.severe-scan-enabled=false", "trading.autonomous.digest.snapshot-refresh-enabled=false", "trading.score-buy.forming-day.notification.enabled=false", "trading.score-buy.forming-day.notification.telegram-enabled=false", "trading.score-buy.pre-position.execution.enabled=false", "trading.score-buy.pre-position.execution.dry-run=true", "trading.score-buy.confirmed-deploy.execution.enabled=false", "trading.score-buy.confirmed-deploy.execution.dry-run=true", "trading.score-buy.post-scout-add.execution.enabled=false", "trading.score-buy.post-scout-add.execution.dry-run=true", "trading.score-buy.post-scout-add.notification.enabled=false", "trading.score-buy.post-scout-add.notification.telegram-enabled=false", "event-scan.notification.enabled=false", "event-scan.notification.dry-run=true", "execution-event.enabled=false", "execution-event.notification-dry-run=true", "wick-capture.shadow.enabled=false", "wick-capture.shadow.bootstrap-enabled=false", "shadow-cleanup.enabled=false", "grid.recovery.enabled=false", "trading.daily-tg-report.enabled=false", "trading.btc-price-move-alert.enabled=false", "market-signal.risk-card.enabled=false", "market-signal.risk-card.dry-run=true", "trading.wai.enabled=false", "trading.grid.auto-rebalance-scheduler.enabled=false", "trading.funding-arb.enabled=false", "position-exit-manager.enabled=false", "trailing-stop.enabled=false", "trading.short-squeeze-alert.enabled=false", "trading.short-squeeze-alert.taker-buy-collector-enabled=false", "signal-verification.scheduler.enabled=false", "agora.alpha-tracker.enabled=false", "ai.strategy.discovery.enabled=false", "trading.live-signal.retry-notification.enabled=false", "trading.event-calendar.freshness-notification-enabled=false", "trading.data-freshness.shadow-replay.collector.enabled=false", "Scheduling disabled for local-smoke profile", "Auto-trade enabled", "API Key configured", "OCO poller disabled.*private WS skipped", "AiTaskRouter.*initialized with 0 providers", "Jina embedding client initialised: enabled=false", "MarketWS.*auto-subscribe config: enabled=false", "OkxLiqWS.*disabled by market", "PolymarketMonitor.*(fatal|digest|snapshot)", "Attribution/startup", "MlMatRefresh.*start refresh", "MlMatRefresh.*kicking off initial refresh", "DexFlowBackfill", "HLFundingBackfill", "CoinalyzeBackfill", "CMIBackfill", "Auto subscribed via", "Warming up MarketSignalCache", "Trading buffer topped from Earn", "Simple Earn", "modifyOco", "ShortSqueezeAlert.*FIRED", "SpotTakerBuy.*15m taker buy", "order placed", "send telegram")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_health.ps1") -Description "local-smoke log guard pattern $pattern"
    }
    foreach ($pattern in @("Assert-HttpStatus", "api/trading/internal/reports/current")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_health.ps1") -Description "local-smoke internal report gateway guard pattern $pattern"
    }

    Write-Host "[verify] checking post-deploy issue acceptance guardrails"
    foreach ($pattern in @("smoke_mcp_parity_ssh.ps1", "smoke_signal_correctness_ssh.ps1", "reusable MCP parity smoke", "signal-correctness MCP smoke", "-RequireNoReviewGaps", "RequireTrailingAcceptance", "RequireAcceptance", "DIAGNOSTIC_ONLY", "DIAGNOSTIC_ONLY OK", "REACHABILITY_ONLY", "REACHABILITY_ONLY OK", "CLOSURE_READY OK", "full split acceptance, no-review-gaps guardrail smoke, signal-correctness smoke, and hard trailing replay acceptance passed", "must not be used as #1/#2/#3 closure evidence", "do not use this output as #1/#2/#3 closure evidence")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper keeps strict guardrail/trailing closure gate $pattern"
    }
    foreach ($pattern in @("tools/list", "required_tools=", "missing_required_tools=", "getEventRiskControlStatus", "analyzeSpotAntiWickPolicyCoverage", "analyzeTrailingStopPnlReplay", "acceptanceTarget: total trailing PnL improvement >= 5%", "acceptanceBlocker=", "acceptanceBlockerDetail=", "getEntryDedupGovernanceDashboard", "getMissedOpportunityRegressionReport", "analyzeStrategy508HoldCounterfactual", "analyzeBtcDonchianShadowGoldenParity", "getBtcDonchianShadowReadiness", "getGovernanceDriftDashboard", "findGovernanceRelaxationCandidates", "findGovernanceTighteningCandidates", "Governance Drift Dashboard", "Governance Relaxation Candidates", "Governance Tightening Candidates", "orderSent", "ocoModified", "writesRuntimeEvidence", "server-local MCP parity smoke failed", "/api/mcp")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_mcp_parity_ssh.ps1") -Description "server-local MCP parity SSH smoke keeps executable read-only surface marker $pattern"
    }
    foreach ($pattern in @("verifyStrategyExecution", "analyzeBlockedSignalOutcomes", "getSignalCorrectnessDashboard", "getEntryDedupGovernanceDashboard", "getMissedOpportunityRegressionReport", "getGovernanceDriftDashboard", "findGovernanceRelaxationCandidates", "findGovernanceTighteningCandidates", "read-only production MCP check", "verifyStrategyExecution machine status marker", "MACHINE_STATUS", "executionMachineStatus", "executionMachineStatusMarkerFound", "dataFreshnessCurrentStatus", "data-freshness-current-sample", "missing_signal_policy_fields", "signal_policy_review_plan", "notAuthorization", "requiredEvidence", "suspected missing evaluation or missed order", "missing MACHINE_STATUS marker", "sys.exit\(1\)", "OK read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_signal_correctness_ssh.ps1") -Description "signal correctness SSH smoke keeps executable read-only MCP marker $pattern"
    }
    foreach ($pattern in @("TelegramServiceImpl", "ExecutionEventScheduler", "unknown runtime errors fail live readiness log smoke", "ERROR category telegram_service=0 execution_event_scheduler=0 unknown=1", "runtime ERROR lines present: count=2", "high-risk operation-like log lines present", "okx auto-trade enabled config echo is not an operation", "OKX auto-trade enabled startup config echo present", "pyth transient timeout is classified warn baseline", "Pyth network warnings exceeded threshold", "etherscan token supply transient is classified warn baseline", "Etherscan tokenSupply warnings exceeded threshold", "okx ws transient null is classified warn baseline", "OKX WS transient warnings exceeded threshold", "mcp auth denied is classified warn baseline", "MCP auth denied warnings exceeded threshold", "http method not supported probe is classified warn baseline", "HTTP method-not-supported warnings exceeded threshold", "runtime error allow flag is diagnostic only", "unknown warn allow flag is diagnostic only", "high risk allow flag is diagnostic only", "ALLOW_RUNTIME_ERROR", "ALLOW_UNKNOWN_WARN", "ALLOW_HIGH_RISK_LOG", "MAX_PYTH_NETWORK_WARN", "MAX_ETHERSCAN_TOKEN_SUPPLY_WARN", "MAX_OKX_WS_TRANSIENT_WARN", "MAX_MCP_AUTH_DENIED_WARN", "MAX_HTTP_METHOD_NOT_SUPPORTED_WARN", "runtime-log-smoke-classification-test")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/test_runtime_log_smoke_classification.ps1") -Description "runtime log smoke classification test keeps live blocker marker $pattern"
    }
    foreach ($pattern in @("ALLOW_UNKNOWN_WARN.*=.*0", "ALLOW_RUNTIME_ERROR.*=.*0", "ALLOW_HIGH_RISK_LOG.*=.*0")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/audit_live_readiness_ssh.ps1", "scripts/verify_split_acceptance_ssh.ps1") -Description "live readiness runtime log smoke forces strict allow flag $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_runtime_log_smoke_classification.ps1"
    foreach ($pattern in @("read-only server audit", "order_capable_flags", "dry_run_flags", "secret_presence", "readiness_details", "missing_readiness_detail_fields", "READINESS_DETAILS_MISSING_FIELDS", "autonomousOpportunity", "primaryBlockers", "blockingInterpretation", "terminalBlockers", "runtimeEvidenceStatus", "validateAutonomousOpportunityReadiness", "blocker_classification", "next_actions", "market_condition_wait", "runtime_evidence_gap", "risk_hard_stop", "execution_disabled_guard", "capacity_not_primary", "background_automation_review", "background_missing", "missing_background_automation_flags", "BACKGROUND_AUTOMATION_MISSING_FLAG_REVIEW_BEFORE_LIVE", "security_or_secret_gap", "runtime_health_gap", "runtime_log_status", "riskLevel=", "EVENT_RISK_NOT_R0", "verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED", "verdict=NOT_READY", "getTinyLiveAutoExecutionTriggerStatus", "getScoreBuyPrePositionAutoExecutionStatus", "getScoreBuyConfirmedDeployAutoExecutionStatus", "getScoreBuyPostScoutAutoAddStatus", "getTrailingStopStatus", "getGuardianSnapshot")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/audit_live_readiness_ssh.ps1") -Description "live readiness audit keeps read-only blocker/verdict marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_readiness_audit_next_actions.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_tiny_live_post_trade_smoke.ps1"
    foreach ($pattern in @("tiny-live-loss-rca", "hardStopDetected", "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES", "hardStopClearCriteria", "maxConsecutiveTinyLiveLosses<2", "Rollout Gates", "completedTinyLiveSamples", "falsePositiveCount", "canEnableProduction", "canIncreaseDailyCap", "missing_tiny_live_hard_stop_fields", "missing_tiny_live_rollout_fields", "missing_tiny_live_fields", "listTinyLiveExecutionReadiness", "previewTinyLiveAutoApproval", "previewTinyLiveAutoExecution", "listTinyLiveExecutions", "getAutonomousExecutionAttribution", "getAutonomousExplorationMonitorStatus", "getExplorationRolloutStatus", "getMissedOpportunityRegressionReport", "getNoBuyReasonTruthTable", "KEEP_DISABLED", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_tiny_live_loss_rca_ssh.ps1") -Description "tiny-live loss RCA smoke keeps read-only hard-stop evidence marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_tiny_live_hard_stop_plan.ps1"
    foreach ($pattern in @("runtime-evidence-rca", "TRADING_RUNTIME_EVIDENCE_ENABLED", "getAutonomousReadinessDashboard", "listRuntimeDecisionEvidence", "previewTinyLiveMinimumOrder", "previewTinyLiveAutoExecution", "validateAutonomousOpportunityReadiness", "getNoBuyReasonTruthTable", "diagnosis=", "CONFIG_DISABLED", "NO_CANONICAL_ROWS", "NO_TARGET_STRATEGY_CANONICAL_ROWS", "targetStrategyEvidenceRows", "targetStrategyShadowLikeRows", "CANONICAL_ROWS_NO_SHADOW_INTENT", "CANONICAL_SHADOW_READY", "runtime_evidence_review_plan", "riskCategory", "requiredEvidence", "notAuthorization", "missing_runtime_evidence_fields", "SCOPE: this smoke is read-only", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_runtime_evidence_rca_ssh.ps1") -Description "runtime evidence RCA smoke keeps read-only evidence-gap marker $pattern"
    }
    foreach ($pattern in @("TRADING_RUNTIME_EVIDENCE_ENABLED=true", "TRADING_OKX_ENABLED=false", "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false", "TRAILING_STOP_ENABLED=false", "POSITION_EXIT_MANAGER_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false", "audit_live_readiness_ssh.ps1", "smoke_runtime_evidence_rca_ssh.ps1", "smoke_tiny_live_loss_rca_ssh.ps1", "not authorization", "must not place orders", "orderSentEvidence=0", "shadowIntentCount")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-dry-run-evidence-plan.md") -Description "live dry-run evidence plan keeps no-live/no-order marker $pattern"
    }
    foreach ($pattern in @("live-background-automation", "READ_ONLY", "background_automation_true", "high_risk_background_automation_true", "missing_background_automation_flags", "background_automation_review_plan", "riskCategory", "requiredReview", "requiredEvidence", "nextAction", "notAuthorization", "background_automation_blockers", "backgroundAutomationClear", "MISSING_BACKGROUND_AUTOMATION_FLAG", "BACKGROUND_AUTOMATION_REVIEW_BEFORE_LIVE", "NOT_READY_BACKGROUND_AUTOMATION_REVIEW", "OK_BACKGROUND_AUTOMATION_DISABLED", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED", "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED", "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED", "Assert-RemotePathSafe", "Assert-SshHostSafe", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_live_background_automation_ssh.ps1") -Description "live background automation smoke keeps read-only env marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_background_automation_flags.ps1"
    foreach ($pattern in @("not authorization", "TRADING_RUNTIME_EVIDENCE_ENABLED=true", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false", "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false", "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false", "TRADING_OKX_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "smoke_live_background_automation_ssh.ps1", "HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "orderSentEvidence=0", "shadowIntentCount", "Rollback Criteria", "not live approval")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-production-env-review-proposal.md") -Description "live production env review proposal keeps no-mutation/live gate marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_production_env_review_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_env_review_packet_preflight.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_readiness_snapshot_consistency.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_deployment_metadata_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_origin_delta_local.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_ssh_wrapper_bom_guard.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_review_packet_preflight.ps1"
    foreach ($pattern in @("live-readiness-bundle", "READ_ONLY", "audit_live_readiness_ssh.ps1", "smoke_live_background_automation_ssh.ps1", "smoke_runtime_evidence_rca_ssh.ps1", "smoke_tiny_live_loss_rca_ssh.ps1", "smoke_signal_correctness_ssh.ps1", "smoke_local_tradingview_candidate_ssh.ps1", "smoke_mcp_parity_ssh.ps1", "deployment-metadata", "liveBundleDeployStatus", "liveBundleOriginStatus", "originMainCommit", "origin delta local classifier", "origin_delta_status", "origin_runtime_delta_files", "origin_docs_tooling_delta_files", "DOCS_TOOLING_ONLY_DRIFT", "RUNTIME_DRIFT", "DEPLOYED_RUNTIME_NOT_CURRENT", "ContinueWhenRuntimeStale", "Assert-DeploymentMetadataCurrentOrStop", "deployment_metadata_status", "origin_metadata_status", "local_tradingview_current_candidate_status", "local_tradingview_dry_run_receipt_armed", "local_tradingview_live_micro_armed", "local_tradingview_execution_path_armed", "local_tradingview_oco_lifecycle_tracked", "runtime_order_sent_evidence", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE", "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_NOT_ARMED", "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED", "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED", "LOCAL_TRADINGVIEW_OCO_PREFLIGHT_FAILED", '\$partialBlockers = @\("LIVE_READINESS_EVIDENCE_UNAVAILABLE", "DEPLOYED_RUNTIME_NOT_CURRENT"\)', '\$partialBlockers = @\("LIVE_READINESS_EVIDENCE_UNAVAILABLE"\)', 'New-BlockerSummary -Blockers \$partialBlockers', "missing_readiness_detail_fields", "READINESS_DETAILS_MISSING_FIELDS", "missing_required_tools", "missing_tiny_live_hard_stop_fields", "missing_tiny_live_rollout_fields", "bundle_blockers", "bundle_blocker_summary", "New-BlockerSummary", "requiredEvidence", "evidenceMarkers", "nextAction", "background_automation_review_plan=", '"state"\\s\*:\\s\*"TRUE"', '"state"\\s\*:\\s\*"MISSING"', "runtime_evidence_review_plan=", '"state"\\s\*:\\s\*"BLOCKED"', '"state"\\s\*:\\s\*"HARD_BLOCKED"', "signalPolicyClear=true", "signal_policy_review_plan=", '"state"\\s\*:\\s\*"REVIEW"', "live_review_packet_allowed", "deploy_required_before_live_review", "LIVE_READINESS_EVIDENCE_UNAVAILABLE", "bundle_verdict=NO_EVIDENCE", "bundle_verdict=NOT_READY", "READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED", "RequireReady", "Assert-RemotePathSafe", "Assert-SshHostSafe", "SSH_AUTH_FAILED", "READ_ONLY_SMOKE_FAILED", "not complete live-readiness evidence", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_live_readiness_bundle_ssh.ps1") -Description "live readiness bundle keeps read-only orchestration marker $pattern"
    }
    foreach ($pattern in @("DEPLOYMENT_METADATA_ONLY", "metadata-only", "liveBundleDeployStatus", "liveBundleOriginStatus", "deployment_metadata_status", "origin_metadata_status", "DEPLOYED_RUNTIME_NOT_CURRENT", "live_review_packet_allowed=false", "bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY", "Assert-RemotePathSafe", "Assert-SshHostSafe", "Get-ReadOnlyMetadataFailureClassification", "read_only_metadata_error=", "LIVE_READINESS_EVIDENCE_UNAVAILABLE", "deploy_required_before_live_review=unknown", "SSH_AUTH_FAILED", "SSH_CONNECT_FAILED", "SSH_COMMAND_FAILED", "READ_ONLY_SMOKE_FAILED", "tr -d", "bash -s")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_live_deployment_metadata_ssh.ps1", "README.md", "docs/deploy-runbook.md", "docs/live-readiness-blocker-remediation.md") -Description "live deployment metadata smoke keeps read-only marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_readiness_bundle_blockers.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_live_readiness_bundle_metadata.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_candidate_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_normalize_tradingview_golden_truth.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_join_tradingview_nn_chart_export.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_tradingview_exact_parity.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_binance_replay_backfill_preflight.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_only_readiness_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_buy_candidate_watch.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_runtime_evidence_watch.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_post_close_evidence_watch.ps1"
    foreach ($pattern in @("local-tradingview-only-readiness", "legacy_tiny_scorebuy_runtime_evidence_not_evaluated=true", "smoke_live_deployment_metadata_ssh.ps1", "audit_live_readiness_ssh.ps1", "smoke_live_background_automation_ssh.ps1", "smoke_local_tradingview_candidate_ssh.ps1", "local_tradingview_only_status", "WAIT_BUY", "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED", "local_tradingview_only_blockers", "local_tradingview_only_health_warnings", "deployment_metadata_effective_status", "metadata_effective_current", "origin_delta_files", "DOCS_TOOLING_ONLY_DRIFT_NOT_DEPLOYED", "RUNTIME_LOG_NOT_CLEAN", "EVENT_RISK_NOT_BASELINE", "local_tradingview_pre_execution_evidence_status", "local_tradingview_pre_execution_readiness", "local_tradingview_pre_execution_blockers", "local_tradingview_okx_auto_trade_enabled", "local_tradingview_okx_private_credentials_configured", "local_tradingview_notional_accepted", "local_tradingview_daily_cap_available", "local_tradingview_open_position_cap_available", "local_tradingview_open_exact_position_exists", "local_tradingview_duplicate_bar_exists", "LOCAL_TRADINGVIEW_CANDIDATE_BLOCKERS_MISSING", "LOCAL_TRADINGVIEW_PRE_EXECUTION_BLOCKERS_MISSING", "LOCAL_TRADINGVIEW_PRE_EXECUTION_DB_EVIDENCE_UNAVAILABLE", "LOCAL_TRADINGVIEW_DAILY_CAP_REACHED", "LOCAL_TRADINGVIEW_OPEN_POSITION_CAP_REACHED", "LOCAL_TRADINGVIEW_OPEN_POSITION_EXISTS", "LOCAL_TRADINGVIEW_DUPLICATE_BAR", "LOCAL_TRADINGVIEW_SIGNAL_STALE", "local_tradingview_only_legacy_blockers_excluded=true", "notAuthorization=read-only LOCAL_TRADINGVIEW-only readiness evidence", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_tradingview_only_readiness_ssh.ps1", "README.md", "docs/deploy-runbook.md", "docs/live-readiness-blocker-remediation.md") -Description "local TradingView-only readiness smoke keeps focused read-only marker $pattern"
    }
    foreach ($pattern in @("local-tradingview-buy-candidate-watch", "watch_local_tradingview_buy_candidate_ssh.ps1", "local_tradingview_buy_candidate_watch_status", "local_tradingview_buy_candidate_watch_pre_execution_blockers", "local_tradingview_buy_candidate_watch_only_status", "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED", "BLOCKED_CURRENT_BUY_CANDIDATE", "WAIT_BUY", "RunFullReadinessEveryAttempt", "RequireCurrentCandidate", "RequireReady", "notAuthorization=read-only LOCAL_TRADINGVIEW BUY candidate watcher only", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/watch_local_tradingview_buy_candidate_ssh.ps1", "README.md", "docs/deploy-runbook.md", "docs/tradingview-webhook.md") -Description "local TradingView BUY candidate watcher keeps read-only marker $pattern"
    }
    foreach ($pattern in @("local-tradingview-runtime-evidence-watch", "watch_local_tradingview_runtime_evidence_ssh.ps1", "local_tradingview_runtime_evidence_watch_status", "local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows", "local_tradingview_runtime_evidence_watch_target_interval_persisted_count", "WAIT_1D_CLOSED_K_EVENT", "WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED", "BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED", "RunReadinessEveryAttempt", "RequireEvidence", "notAuthorization=read-only LOCAL_TRADINGVIEW runtime evidence watcher only", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/watch_local_tradingview_runtime_evidence_ssh.ps1", "docs/deploy-runbook.md", "docs/tradingview-webhook.md") -Description "local TradingView runtime evidence watcher keeps read-only marker $pattern"
    }
    foreach ($pattern in @("local-tradingview-post-close-evidence-watch", "watch_local_tradingview_post_close_evidence_ssh.ps1", "local_tradingview_post_close_evidence_watch_status", "local_tradingview_post_close_evidence_watch_target_strategy_evidence_rows", "local_tradingview_post_close_evidence_watch_target_interval_persisted_count", "CLOSED_K_OBSERVED_EVIDENCE_CONFIRMED", "WAIT_1D_CLOSED_K_EVENT_TIMEOUT", "AcceptExistingClosedK", "AllowMissingEvidenceAfterClosedK", "AllowWaitTimeout", "notAuthorization=read-only LOCAL_TRADINGVIEW post-close evidence watcher only", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/watch_local_tradingview_post_close_evidence_ssh.ps1", "docs/deploy-runbook.md", "docs/tradingview-webhook.md") -Description "local TradingView post-close evidence watcher keeps read-only marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_post_fix_strategy_monitoring_packet.ps1"
    foreach ($pattern in @("post-fix-strategy-monitoring", "prepare_post_fix_strategy_monitoring_packet_ssh.ps1", "watch_local_tradingview_buy_candidate_ssh.ps1", "smoke_strategy508_entry_dedup_exposure_ssh.ps1", "smoke_signal_correctness_ssh.ps1", "post_fix_strategy_monitoring_status", "post_fix_strategy_monitoring_local_tradingview_watch_status", "post_fix_strategy_monitoring_strategy508_recommendation", "post_fix_strategy_monitoring_verify_machine_status", "post_fix_strategy_monitoring_signal_source_policy_primary", "CURRENT_BUY_READY_MONITOR_ORDER_AND_OCO", "CURRENT_BUY_BLOCKED_REVIEW_REQUIRED", "MISSED_ORDER_REVIEW_REQUIRED", "WATCH_FALSE_BLOCK_RISK", "notAuthorization=read-only post-fix strategy monitoring packet only", "read-only check complete")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/prepare_post_fix_strategy_monitoring_packet_ssh.ps1", "README.md", "docs/deploy-runbook.md") -Description "post-fix strategy monitoring packet keeps read-only marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_dry_run_receipt_env_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_local_tradingview_oco_lifecycle_env_handoff.ps1"
    foreach ($pattern in @("LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET", "READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION", "REQUEST_EXACT_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_AUTHORIZATION", "exactOcoLifecycleAuthorizationText", "TRADING_OCO_POLLER_ENABLED=true", "POSITION_EXIT_MANAGER_ENABLED=false", "smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked", "smoke_strategy485_position_risk_ssh.ps1", "prepare_oco_sync_reconciliation_packet_ssh.ps1", "local_tradingview_oco_lifecycle_env_request_allowed=false", "production_env_change_allowed=false", "deploy_allowed=false", "order_allowed=false", "telegram_send_allowed=false", "notAuthorization")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/prepare_local_tradingview_oco_lifecycle_env_handoff.ps1", "scripts/test_local_tradingview_oco_lifecycle_env_handoff.ps1", "README.md", "docs/deploy-runbook.md", "docs/live-readiness-blocker-remediation.md", "docs/tradingview-webhook.md", "SPLIT_PROGRESS.md") -Description "local TradingView OCO lifecycle env handoff keeps read-only exact authorization marker $pattern"
    }
    foreach ($pattern in @("LIVE_ORDER_CAPABLE_SCOPE_REVIEW_PACKET", "READY_FOR_ORDER_CAPABLE_SCOPE_OPERATOR_REVIEW_NOT_MUTATION", "FIX_GRID_POST_ENV_REVIEW_OR_ROLLBACK_GRID_OKX_SCOPE", "ACCEPT_EXISTING_GRID_OKX_TRAILING_SCOPE_READ_ONLY_REVIEW", "exactAcceptScopeAuthorizationText", "exactRollbackScopeAuthorizationText", "riskAcceptanceConditions", "killSwitchPlan", "TRADING_OKX_ENABLED_SCOPE_EVIDENCE_MISSING", "TRADING_OKX_ENABLED_GRID_SCOPE_NOT_READY", "TRADING_GRID_ENABLED_SCOPE_EVIDENCE_MISSING", "GRID_SCOPE_REVIEW_NOT_READY", "GRID_SCOPE_READY_MARKER_NOT_TRUE", "TRAILING_STOP_ENABLED_SCOPE_EVIDENCE_MISSING", "TRAILING_SCOPE_REVIEW_NOT_READY", "RUNTIME_LOG_SMOKE_NOT_PASS", "AUDIT_USED_STALE_SERVER_CHECKER_LOCAL_CLASSIFIER_PASSED", "EXISTING_GRID_ORDER_PATH_ACTIVATION_RISK", "TRAILING_STOP_DRY_RUN_OBSERVATION_ONLY_NOT_OCO_MUTATION_APPROVAL", "TINY_LIVE_DRY_RUN_FALSE_WHILE_EXECUTION_DISABLED", "TRADING_OKX_ENABLED=false", "TRADING_GRID_ENABLED=false", "TRAILING_STOP_ENABLED=false", "order_allowed=false", "grid_mutation_allowed=false", "exchange_mutation_allowed=false", "notAuthorization")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/prepare_live_order_capable_scope_review_packet.ps1", "scripts/test_live_order_capable_scope_review_packet.ps1") -Description "live order-capable scope review packet keeps read-only aggressive review marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_order_capable_scope_review_packet.ps1"
    Assert-LiveReadinessBundleNoEvidenceGuard
    Invoke-VerifyPowerShellTest -ScriptName "test_signal_policy_review_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_signal_governance_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_signal_review_gate.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_tiny_live_governance_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_position_risk_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_oco_sync_reconciliation_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_closed_grid_residual_disposition_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_aged_position_review_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_position_review_gate.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_operator_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_auto_trading_review_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_auto_trading_review_gate.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_candidate_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_loss_review_gate.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_post_deploy_profit_validation.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_false_kill_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_executability_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_counterfactual_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_close_readiness.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_operator_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_collector_activation_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_collector_post_activation_status.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_push_deploy_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_runtime_evidence_only_env_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_filter_block_false_kill_issue7_post_deploy_read_only_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_candidate_id_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_observation_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_evidence_readiness.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_sample_gap_rca.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_attention_hit_progression_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_signal_eval_no_buy_generation_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_buy_like_candidate_progression_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_no_buy_attention_flow_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_near_threshold_decision_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_near_threshold_shadow_observation_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_entry_dedup_exposure_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_first_entry_readiness_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_hold_counterfactual_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_time_exit_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_btc_base_position_manager_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_btc_base_position_manager_shadow_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_btc_base_position_adoption_execution.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_btc_price_only_research.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_btc_donchian_shadow_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_min_notional_floor_activation_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy508_trade_plan_quality_gate_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_exposure_consistency_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_blocker_decomposition_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_coarse_semantics_shadow_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_shadow_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_feasibility_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_gate_preflight_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_synthetic_ev_oco_preview_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_exact_opportunity_staged_add_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_shadow_experiment_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_operator_decision_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_profit_blocker_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_shadow_replay_input_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_shadow_replay_collector_design.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_collector_activation_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_collector_activation_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_improvement_review_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_experiment_gate.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_shadow_experiment_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_runtime_deploy_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_blocker_ledger.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_readiness_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_filter_blocker_decision_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_signal_missed_blocker_decision_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_evidence_watch.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_next_execution_readiness_watch.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_remaining_open_issues_status.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_review_matrix.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_action_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_quick_status.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_compact_status.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_verified_recommendations.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_panic_bottom_context.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_panic_bottom_missed_rebound_rca_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_scorebuy_ml_gate_diagnostic.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_trend_adjustment_review_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_readiness_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_candidate_plan_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_trend_clearance_watch_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_mcp_tool_coverage_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_decision_snapshot.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_trend_override_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_env_diff_preflight_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_create_authorization_preflight_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_capital_override_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_authorization_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_operator_authorization_request.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_post_env_verification_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_complete_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_post_env_read_only_verification_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_blocker_priority_board.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_open_readiness_watch.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_candidate_parameter_sweep.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_split_acceptance_deploy_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_post_open_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_grid_resize_rebuild_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy574_tiny_live_governance_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_candidate_flow_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_verified_experiment_readiness.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_experiment_operator_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_consolidated_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_priority_decision_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_review_chain_blocked_packet_preservation.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_next_action_board.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_operator_authorization_request_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_aggressive_activation_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_grid10_order_path_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_grid10_activation_authorization_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_grid10_same_session_activation_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_grid10_activation_source_refresh.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_grid10_execution_preflight_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_high_risk_micro_live_probe_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_high_risk_micro_live_probe_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_high_risk_micro_live_probe_activation_source_refresh.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_evidence_only_accelerator_env_deploy_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_evidence_only_accelerator_post_env_read_only_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_live_blocker_audit_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_live_blocker_source_refresh.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_dry_run_operator_decision_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_dry_run_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_dry_run_activation_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_strategy_opt_in_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_post_opt_in_readiness_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_dry_run_env_deploy_handoff.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_strategy_opt_in_execution.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_dry_run_observation_status.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_profit_next_execution_blocker_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_risk_reduction_operator_decision_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_risk_reduction_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_strategy485_risk_escalation_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_direct_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_runtime_proof_gap_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_candidate_runtime_snapshot_collector_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_duplicate_hash_replay_protection_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_oco_route_proof_preflight_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_exact_ev_oco_snapshot_coverage_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_event_risk_control_evidence_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_historical_event_risk_row_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_review_only_shadow_bundle_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_mutation_blocker_handoff_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_open_exposure_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_open_exposure_semantic_resolution_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_runtime_snapshot_collector_activation_request_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_budget_snapshot_review_request_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_oco_route_dry_run_request_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_mutation_blocker_clearance_board_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_review_only_objective_traceability_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_post_semantic_blocker_priority_board.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_live_gate_semantics_diff_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_live_gate_default_off_change_request_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_open_exposure_operator_choice_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_open_exposure_scope_activation_authorization_bundle.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_operator_decision_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_dedup_semantics_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_blocker_decision_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_replay_blocker_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_operator_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_trailing_stop_parameter_sweep_smoke.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_profit_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_operator_decision_brief.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_operator_experiment_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_tp_sl_oco_feasibility_operator_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_tp_sl_oco_feasibility_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_exit_side_operator_review_plan.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_entry_filter_operator_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_no_buy_row_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_missed_opportunity_shadow_design_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_governance_relaxation_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_governance_relaxation_preflight_review_packet.ps1"
    Invoke-VerifyPowerShellTest -ScriptName "test_data_freshness_shadow_candidate_packet.ps1"
    foreach ($pattern in @("deployment_metadata_status", "origin_metadata_status", "DEPLOYED_RUNTIME_NOT_CURRENT", "origin/main", "bundle_blockers", "live_review_packet_allowed", "deploy_required_before_live_review", "bundle_verdict", "ERROR category", "ERROR rca=TELEGRAM_EXECUTION_EVENT_NOTIFICATION_PATH", "EVENT_SCAN_NOTIFICATION_ENABLED", "EXECUTION_EVENT_ENABLED", "Telegram send")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md") -Description "operator docs keep live readiness bundle deployment metadata marker $pattern"
    }
    foreach ($pattern in @("SSH_AUTH_FAILED", "SSH_CONNECT_FAILED", "SSH_COMMAND_FAILED", "READ_ONLY_SMOKE_FAILED", "live_review_packet_allowed=false", "complete evidence", "not live-readiness evidence")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "handoff docs classify live-readiness bundle SSH access failure $pattern"
    }
    foreach ($pattern in @("Live Readiness Blocker Remediation Matrix", "not authorization", "LIVE_READINESS_EVIDENCE_UNAVAILABLE", "LIVE_READINESS_NOT_READY", "ORDER_CAPABLE_FLAGS_REVIEW", "SECRET_PREREQUISITES_MISSING", "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN", "EVENT_RISK_NOT_BASELINE", "MCP_AUDIT_TOOL_ERROR", "EXECUTION_ELIGIBILITY_NOT_READY", "BACKGROUND_AUTOMATION_REVIEW", "RUNTIME_EVIDENCE_CONFIG_DISABLED", "RUNTIME_EVIDENCE_NO_CANONICAL_ROWS", "RUNTIME_EVIDENCE_NO_TARGET_STRATEGY_CANONICAL_ROWS", "RUNTIME_EVIDENCE_NO_SHADOW_INTENT", "RUNTIME_EVIDENCE_REVIEW_REQUIRED", "RUNTIME_EVIDENCE_ORDER_SENT", "TINY_LIVE_LOSS_HARD_STOP", "TINY_LIVE_ROLLOUT_NOT_READY", "SIGNAL_POLICY_REVIEW_GAPS", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE", "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_NOT_ARMED", "LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE", "LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK", "LOCAL_TRADINGVIEW_OCO_PREFLIGHT_FAILED", "LOCAL_TRADINGVIEW_ORDER_SENT_EVIDENCE", "signal_policy_review_plan", "riskCategory", "evidenceMarkers", "requiredEvidence", "notAuthorization", "MCP_PARITY_NOT_PROVEN", "DEPLOYED_RUNTIME_NOT_CURRENT", "Audit Classifications", "blocker_classification", "next_actions", "market_condition_wait", "runtime_evidence_gap", "risk_hard_stop", "execution_disabled_guard", "background_automation_review", "security_or_secret_gap", "runtime_health_gap", "capacity_not_primary", "secondary sizing review only", "must not be used to bypass primary blockers", "missing readiness verdicts stay blocked", "missing order-capable evidence stays blocked", "missing masked secret evidence stays blocked", "missing health/log evidence stays blocked", "missing event-risk evidence stays blocked", "parsed ``readiness_details`` JSON", "missing_readiness_detail_fields=\[\]", "Missing or non-empty readiness-detail field summaries stay", "missing MCP readiness-details evidence stays blocked", "missing execution eligibility evidence stays blocked", "missing OK verdict", "missing_required_tools=\[\]", "Missing or non-empty required-tool evidence", "background_automation_review_plan=\[\]", "missing review plan evidence", "state=TRUE", "state=MISSING", "missing high-risk background evidence stays blocked", "missing reviewed env keys", "background smoke, live-readiness audit, env diff proposal, and local", "missing metadata", "missing_runtime_evidence_fields=\[\]", "Missing runtime-evidence fields", "Missing or ``N/A`` target-strategy shadow-intent evidence stays blocked", "Missing or unrecognized runtime-evidence diagnosis stays blocked", "missing_tiny_live_hard_stop_fields=\[\]", "missing_tiny_live_rollout_fields=\[\]", "missing_tiny_live_fields=\[\]", "Missing or ``N/A`` hard-stop evidence stays blocked", "Missing or ``N/A`` rollout evidence stays blocked", "signalPolicyClear=true", "state=REVIEW", "missing_signal_policy_fields=\[\]", "Missing signal-policy fields", "missing or ``N/A`` governance/missed-opportunity evidence stays blocked", "ALLOW_RUNTIME_ERROR=1", "diagnostic-only", "force those values back to ``0``", "deployment_metadata_status=CURRENT", "origin_metadata_status=CURRENT_ORIGIN_MAIN", "live_review_packet_allowed=true", "live_review_packet_allowed=false", "deploy_required_before_live_review=false", "high_risk_background_automation_true=\[\]", "targetStrategyShadowLikeRows", "orderSentEvidenceBlockerCount=0", "\[mcp-parity-ssh\] OK", "TRADING_OKX_ENABLED=true", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=true", "Review Packet Minimum", "not live approval")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-readiness-blocker-remediation.md") -Description "live readiness blocker remediation keeps no-live evidence marker $pattern"
    }
    foreach ($pattern in @("LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED", "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-readiness-blocker-remediation.md") -Description "live readiness blocker remediation keeps LOCAL_TRADINGVIEW live-micro marker $pattern"
    }
    foreach ($pattern in @("Live Background Automation Env Diff Proposal", "not authorization", "BACKGROUND_AUTOMATION_REVIEW", "HIGH_RISK_BACKGROUND_AUTOMATION_TRUE", "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false", "MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false", "TRADING_DAILY_TG_REPORT_ENABLED=false", "TRADING_AUTONOMOUS_DIGEST_ENABLED=false", "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false", "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false", "background_automation_true=\[\]", "high_risk_background_automation_true=\[\]", "missing_background_automation_flags=\[\]", "background_automation_review_plan=\[\]", "riskCategory", "requiredReview", "requiredEvidence", "nextAction", "notAuthorization", "background_automation_blockers=\[\]", "backgroundAutomationClear=true", "background_automation_false", "lists all nine reviewed background flags", "Coverage drift guard", "smoke_live_background_automation_ssh.ps1", "audit_live_readiness_ssh.ps1", "test_live_background_automation_flags.ps1", "not equivalent to explicit ``false`` evidence", "not authorization to keep a flag true", "OK_BACKGROUND_AUTOMATION_DISABLED", "order_capable_flags", "Rollback Criteria", "not live approval")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-background-automation-env-diff-proposal.md") -Description "live background automation env diff proposal keeps no-mutation marker $pattern"
    }
    foreach ($pattern in @("Live Runtime Evidence Env Proposal", "not authorization", "RUNTIME_EVIDENCE_CONFIG_DISABLED", "RUNTIME_EVIDENCE_NO_SHADOW_INTENT", "TRADING_RUNTIME_EVIDENCE_ENABLED=true", "TRADING_OKX_ENABLED=false", "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false", "smoke_runtime_evidence_rca_ssh.ps1", "smoke_live_readiness_bundle_ssh.ps1", "diagnosis.*CONFIG_DISABLED", "orderSentEvidence=0", "shadowIntentCount", "missing_runtime_evidence_fields=\[\]", "runtime_evidence_review_plan", "state=BLOCKED", "state=HARD_BLOCKED", "full bundle fails closed", "riskCategory", "evidenceMarkers", "requiredEvidence", "notAuthorization", "bundle_blockers", "Rollback Criteria", "not live approval")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/live-runtime-evidence-env-proposal.md") -Description "live runtime evidence env proposal keeps evidence-only marker $pattern"
    }
    Invoke-VerifyPowerShellTest -ScriptName "test_live_runtime_evidence_env_plan.ps1"
    foreach ($script in @("scripts/smoke_mcp_parity_ssh.ps1", "scripts/smoke_guardrail_acceptance_ssh.ps1", "scripts/smoke_trailing_stop_pnl_replay_ssh.ps1", "scripts/smoke_trailing_stop_parameter_sweep_ssh.ps1", "scripts/smoke_signal_correctness_ssh.ps1", "scripts/audit_live_readiness_ssh.ps1", "scripts/smoke_tiny_live_loss_rca_ssh.ps1", "scripts/smoke_runtime_evidence_rca_ssh.ps1", "scripts/smoke_tiny_live_post_trade_ssh.ps1", "scripts/smoke_strategy574_signal_governance_ssh.ps1", "scripts/smoke_strategy485_position_risk_ssh.ps1", "scripts/smoke_profit_candidate_review_ssh.ps1", "scripts/smoke_strategy508_hold_counterfactual_ssh.ps1", "scripts/smoke_strategy508_time_exit_ssh.ps1", "scripts/smoke_btc_donchian_shadow_ssh.ps1", "scripts/smoke_panic_bottom_context_ssh.ps1", "scripts/smoke_grid_trend_adjustment_review_ssh.ps1", "scripts/smoke_data_freshness_false_kill_review_ssh.ps1", "scripts/smoke_data_freshness_executability_review_ssh.ps1", "scripts/prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1", "scripts/execute_trailing_stop_strategy_opt_in_ssh.ps1")) {
        Assert-RgMatch -Pattern "http://127\.0\.0\.1:\{os\.environ\['PORT'\]\}/api/mcp" -Paths @($script) -Description "$script uses server-local /api/mcp"
        Assert-RgMatch -Pattern "TRADING_MCP_KEY" -Paths @($script) -Description "$script reads the server-local MCP key"
        Assert-RgMatch -Pattern "Assert-RemotePathSafe" -Paths @($script) -Description "$script validates remote shell embedded paths"
        Assert-RgMatch -Pattern "Assert-McpSmokeTokenSafe" -Paths @($script) -Description "$script validates remote shell embedded smoke tokens"
        Assert-RgNoMatch -Pattern "https://agoratradingapi\.purrtechllc\.com/api/mcp|https://agoramarketapi\.purrtechllc\.com/api/trading/mcp|/api/trading/mcp" -Paths @($script) -Description "$script must not call public or legacy Trading MCP routes"
    }
    foreach ($pattern in @("Assert-RemotePathSafe", "Assert-McpSmokeTokenSafe")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper validates remote shell embedded inputs $pattern"
    }
    Assert-RgMatch -Pattern "ssh is not available on PATH" -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper fails fast when ssh is unavailable"
    Assert-RgMatch -Pattern 'splitAcceptance .* -EnvFile \$EnvFile' -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper passes the selected env file into split acceptance"
    foreach ($pattern in @(
            '& \$mcpParitySmoke .* -Symbol \$Symbol -IntervalCode \$IntervalCode',
            '& \$guardrailSmoke .* -Symbol \$Symbol -RequireNoReviewGaps',
            '& \$signalCorrectnessSmoke .* -Symbol \$Symbol -ExecutionDays \$SignalExecutionDays -BlockedDays \$SignalBlockedDays -AccuracyDays \$SignalAccuracyDays',
            'Symbol = \$Symbol',
            'IntervalCode = \$IntervalCode',
            'ReplayIntervalCode = \$ReplayIntervalCode',
            'Days = \$TrailingDays',
            'Limit = \$TrailingLimit',
            '\$trailingArgs\.RequireAcceptance = \$true')) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper propagates custom smoke parameters $pattern"
    }
    Assert-RgMatch -Pattern "Assert-RemotePathSafe" -Paths @("scripts/verify_split_acceptance_ssh.ps1") -Description "split acceptance verifier validates remote shell embedded paths"
    foreach ($pattern in @("EnvFile", "Assert-RemotePathSafe.*EnvFile", 'EnvFile = \$EnvFile')) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_split_acceptance_ssh.ps1") -Description "split acceptance verifier carries the selected env file into server verification $pattern"
    }
    foreach ($pattern in @("EnvFile", "Assert-RemotePathSafe.*EnvFile", 'ENV_FILE=''\$EnvFile''', "Assert-RemotePathSafe", "Assert-PublicHttpsUrlSafe", "agoratradingapi\.purrtechllc\.com", "agoramarketapi\.purrtechllc\.com")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_server_ssh.ps1") -Description "server SSH verifier validates remote shell embedded paths and public URLs $pattern"
    }
    Assert-RgMatch -Pattern "dedicated-host health plus public MCP blocked checks" -Paths @("docs/split-acceptance-status.md") -Description "split acceptance handoff describes public MCP as a blocked-route check"
    Assert-RgNoMatch -Pattern "dedicated-host health/MCP checks" -Paths @("docs/split-acceptance-status.md", "README.md", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "handoff docs must not imply successful public dedicated-host MCP checks"
    foreach ($pattern in @("Assert-RemotePathSafe", "Assert-RemoteRelativePathSafe", "Assert-GitBranchSafe", "PollSeconds must be at most 60", "TimeoutSeconds must be between 60 and 3600")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/deploy_ssh.ps1") -Description "deploy SSH wrapper validates remote shell embedded inputs and polling bounds $pattern"
    }
    foreach ($script in @("scripts/deploy_ssh.ps1", "scripts/verify_server_ssh.ps1", "scripts/verify_split_acceptance_ssh.ps1", "scripts/verify_post_deploy_issue_acceptance_ssh.ps1", "scripts/smoke_mcp_parity_ssh.ps1", "scripts/smoke_guardrail_acceptance_ssh.ps1", "scripts/smoke_signal_correctness_ssh.ps1", "scripts/prepare_entry_filter_operator_review_packet_ssh.ps1", "scripts/prepare_no_buy_row_review_packet_ssh.ps1", "scripts/prepare_missed_opportunity_shadow_design_packet_ssh.ps1", "scripts/prepare_governance_relaxation_review_packet_ssh.ps1", "scripts/prepare_data_freshness_shadow_candidate_packet_ssh.ps1", "scripts/prepare_data_freshness_profit_blocker_brief_ssh.ps1", "scripts/prepare_data_freshness_replay_evidence_readiness_ssh.ps1", "scripts/prepare_profit_candidate_flow_review_packet_ssh.ps1", "scripts/prepare_entry_dedup_semantics_shadow_experiment_packet_ssh.ps1", "scripts/prepare_entry_dedup_operator_decision_brief_ssh.ps1", "scripts/prepare_entry_filter_blocker_decision_brief_ssh.ps1", "scripts/prepare_signal_missed_blocker_decision_brief_ssh.ps1", "scripts/smoke_trailing_stop_pnl_replay_ssh.ps1", "scripts/smoke_trailing_stop_parameter_sweep_ssh.ps1", "scripts/prepare_trailing_stop_operator_review_packet_ssh.ps1", "scripts/prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1", "scripts/prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1", "scripts/prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1", "scripts/prepare_trailing_stop_dry_run_observation_status_ssh.ps1", "scripts/execute_trailing_stop_strategy_opt_in_ssh.ps1", "scripts/audit_live_readiness_ssh.ps1", "scripts/smoke_live_background_automation_ssh.ps1", "scripts/smoke_runtime_evidence_rca_ssh.ps1", "scripts/smoke_tiny_live_loss_rca_ssh.ps1", "scripts/smoke_strategy574_signal_governance_ssh.ps1", "scripts/prepare_strategy574_signal_review_gate_ssh.ps1", "scripts/smoke_strategy485_position_risk_ssh.ps1", "scripts/prepare_strategy485_position_review_gate_ssh.ps1", "scripts/prepare_strategy485_operator_review_packet_ssh.ps1", "scripts/smoke_auto_trading_review_bundle_ssh.ps1", "scripts/prepare_auto_trading_review_gate_ssh.ps1", "scripts/smoke_profit_candidate_review_ssh.ps1", "scripts/smoke_strategy508_hold_counterfactual_ssh.ps1", "scripts/smoke_strategy508_time_exit_ssh.ps1", "scripts/smoke_panic_bottom_context_ssh.ps1", "scripts/smoke_grid_trend_adjustment_review_ssh.ps1", "scripts/prepare_profit_loss_review_gate_ssh.ps1", "scripts/smoke_post_deploy_profit_validation_ssh.ps1", "scripts/smoke_data_freshness_false_kill_review_ssh.ps1", "scripts/smoke_data_freshness_executability_review_ssh.ps1", "scripts/smoke_data_freshness_counterfactual_review_ssh.ps1", "scripts/smoke_filter_block_false_kill_issue7_ssh.ps1", "scripts/smoke_data_freshness_sample_gap_rca_ssh.ps1", "scripts/smoke_attention_hit_progression_ssh.ps1", "scripts/smoke_signal_eval_no_buy_generation_ssh.ps1", "scripts/smoke_buy_like_candidate_progression_ssh.ps1", "scripts/smoke_strategy508_entry_dedup_exposure_ssh.ps1", "scripts/smoke_strategy508_first_entry_readiness_ssh.ps1", "scripts/smoke_profit_improvement_review_bundle_ssh.ps1", "scripts/prepare_profit_experiment_gate_ssh.ps1", "scripts/prepare_profit_shadow_experiment_packet_ssh.ps1", "scripts/prepare_profit_runtime_deploy_review_packet_ssh.ps1", "scripts/prepare_profit_blocker_ledger_ssh.ps1", "scripts/prepare_profit_readiness_brief_ssh.ps1", "scripts/prepare_profit_operator_action_brief_ssh.ps1", "scripts/prepare_exit_side_operator_decision_brief_ssh.ps1", "scripts/smoke_live_readiness_bundle_ssh.ps1", "scripts/prepare_live_review_packet_ssh.ps1", "scripts/prepare_grid_open_decision_snapshot_ssh.ps1", "scripts/prepare_grid_trend_override_review_packet_ssh.ps1", "scripts/prepare_grid_env_diff_preflight_packet_ssh.ps1", "scripts/prepare_grid_create_authorization_preflight_packet_ssh.ps1", "scripts/prepare_grid_capital_override_review_packet_ssh.ps1", "scripts/prepare_grid_open_authorization_bundle_ssh.ps1", "scripts/prepare_grid_open_operator_authorization_request_ssh.ps1", "scripts/prepare_grid_post_env_verification_plan_ssh.ps1", "scripts/prepare_grid_open_complete_operator_packet_ssh.ps1", "scripts/prepare_grid_post_env_read_only_verification_bundle_ssh.ps1", "scripts/prepare_grid_open_blocker_priority_board_ssh.ps1", "scripts/prepare_grid_resize_rebuild_operator_packet_ssh.ps1", "scripts/watch_local_tradingview_buy_candidate_ssh.ps1", "scripts/watch_local_tradingview_runtime_evidence_ssh.ps1", "scripts/watch_local_tradingview_post_close_evidence_ssh.ps1", "scripts/watch_profit_next_execution_readiness_ssh.ps1", "scripts/watch_grid_open_readiness_ssh.ps1", "scripts/prepare_grid_split_acceptance_deploy_handoff_ssh.ps1")) {
        Assert-RgMatch -Pattern "Assert-SshHostSafe" -Paths @($script) -Description "$script validates SSH target syntax"
        Assert-RgMatch -Pattern "unsupported characters for ssh target" -Paths @($script) -Description "$script rejects unsafe SSH targets"
    }
    foreach ($pattern in @("Assert-SshHostSafe", "unsupported characters for ssh target")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_btc_donchian_shadow_ssh.ps1") -Description "BTC Donchian SSH smoke validates target marker $pattern"
    }
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\verify_post_deploy_issue_acceptance_ssh.ps1" `
        -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
        -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
        -Description "post-deploy issue acceptance wrapper SSH target input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\deploy_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Branch", "main';echo bad") `
        -ExpectedPattern "Branch contains unsupported characters" `
        -Description "deploy SSH wrapper branch input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\verify_server_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-PublicTradingHealthUrl", "https://evil.example/api/actuator/health") `
        -ExpectedPattern "PublicTradingHealthUrl must be a safe purrtechllc HTTPS URL" `
        -Description "server SSH verifier public URL input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\verify_split_acceptance_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TradingAppDir", "/home/ubuntu/agora-trading-api';echo bad") `
        -ExpectedPattern "TradingAppDir contains unsupported characters" `
        -Description "split acceptance verifier remote path input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_signal_correctness_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ExecutionDays", "999") `
        -ExpectedPattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90" `
        -Description "signal correctness SSH smoke query-window input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_mcp_parity_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
        -ExpectedPattern "IntervalCode contains unsupported characters" `
        -Description "MCP parity SSH smoke interval input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_guardrail_acceptance_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
        -ExpectedPattern "Symbol contains unsupported characters" `
        -Description "guardrail acceptance SSH smoke symbol input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_trailing_stop_pnl_replay_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Days", "999") `
        -ExpectedPattern "Days must be between 1 and 90" `
        -Description "trailing replay SSH smoke query-window input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_trailing_stop_parameter_sweep_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TrailingTriggerMultiples", "0.5';echo bad") `
        -ExpectedPattern "TrailingTriggerMultiples contains unsupported characters" `
        -Description "trailing parameter sweep SSH smoke parameter-list input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\audit_live_readiness_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
        -ExpectedPattern "Symbol contains unsupported characters" `
        -Description "live readiness SSH audit symbol input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\verify_post_deploy_issue_acceptance_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TrailingLimit", "999") `
        -ExpectedPattern "TrailingLimit must be between 1 and 500" `
        -Description "post-deploy issue acceptance wrapper trailing-limit input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_live_background_automation_ssh.ps1" `
        -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
        -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
        -Description "live background automation SSH smoke target input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_runtime_evidence_rca_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Minutes", "1") `
        -ExpectedPattern "Minutes must be between 60 and 43200" `
        -Description "runtime evidence RCA SSH smoke query-window input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_tiny_live_loss_rca_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Days", "999") `
        -ExpectedPattern "Days must be between 1 and 90" `
        -Description "tiny-live loss RCA SSH smoke query-window input guard"
    Assert-PowerShellScriptFailsBeforeSsh `
        -ScriptRelativePath "scripts\smoke_live_readiness_bundle_ssh.ps1" `
        -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-RuntimeEvidenceMinutes", "1") `
        -ExpectedPattern "RuntimeEvidenceMinutes must be between 60 and 43200" `
        -Description "live readiness bundle SSH smoke query-window input guard"
    foreach ($pattern in @("TrailingDays must be between 1 and 90", "TrailingLimit must be between 1 and 500", "SignalExecutionDays, SignalBlockedDays, and SignalAccuracyDays must be between 1 and 90", "ReplayIntervalCode")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper bounds read-only production query window $pattern"
    }
    Assert-RgMatch -Pattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90" -Paths @("scripts/smoke_signal_correctness_ssh.ps1") -Description "signal-correctness SSH smoke bounds read-only production query window"
    foreach ($pattern in @("blocked signal outcomes read-only marker", "EntryDedup governance no order send marker", "missed opportunity regression no runtime evidence writes marker")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_signal_correctness_ssh.ps1") -Description "signal-correctness SSH smoke hard-fails when read-only evidence marker drifts $pattern"
    }
    Assert-RgMatch -Pattern "mode=READ_ONLY \| no signal/order/OCO/strategy/grid/fund/Earn/Telegram behavior changed" -Paths @("src/main/java/com/agora/mcp/MarketDataMcpTools.java") -Description "blocked signal outcomes MCP output carries read-only boundary marker"
    foreach ($pattern in @("RequireNoReviewGaps", "REVIEW_POLICY_GAPS", "review gaps are not acceptable for issue acceptance")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_guardrail_acceptance_ssh.ps1") -Description "guardrail acceptance smoke keeps no-review-gaps closure semantics $pattern"
    }
    foreach ($pattern in @("Limit = 500", "RequireAcceptance", "acceptance=PASS", "sampleStatus=NO_REPLAYABLE_TRADES", "sampleStatus=NO_REPLAYED_ROWS", "acceptanceTarget: total trailing PnL improvement >= 5%", "acceptanceBlocker=", "acceptanceBlockerDetail=", "replayIntervalCode", "backtestInterval:", "replayInterval:")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_trailing_stop_pnl_replay_ssh.ps1") -Description "trailing replay smoke keeps hard acceptance and insufficient-sample semantics $pattern"
    }
    foreach ($pattern in @("analyzeTrailingStopParameterSweep", "currentPolicy=breakevenAtr", "parameterGrid=breakevenAtr", "currentPolicySummary=policy=", "bestPolicySummary=policy=", "bestVsCurrentDeltaPnl=", "topCandidates:", "REVIEW_PARAMETER_CANDIDATE_NOT_LIVE", "NO_BETTER_PARAMETER_FOUND_IN_SWEEP", "RequireBetterCandidate")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_trailing_stop_parameter_sweep_ssh.ps1") -Description "trailing parameter sweep smoke keeps read-only comparison marker $pattern"
    }
    Assert-RgMatch -Pattern "TrailingLimit = 500" -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper defaults trailing closure sample to 30d/500"
    foreach ($pattern in @("REVIEW_POLICY_GAPS.*fails #1/#2 issue acceptance", "RequireTrailingAcceptance", "acceptance=PASS", "signal-correctness", "SkipSplitAcceptance.*diagnostic-only", "cannot be combined with.*RequireTrailingAcceptance", "DIAGNOSTIC_ONLY OK", "REACHABILITY_ONLY OK", "CLOSURE_READY OK", "reachability-only output")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md") -Description "operator docs keep current issue acceptance closure semantics $pattern"
    }
    Assert-RgMatch -Pattern "CLOSURE_READY OK" -Paths @("SPLIT_PROGRESS.md") -Description "split progress records the current full closure marker"
    Assert-RgMatch -Pattern "full closure mode only: split acceptance, no-review-gaps guardrail smoke" -Paths @("SPLIT_PROGRESS.md") -Description "split progress records the split and guardrail closure prerequisites"
    Assert-RgMatch -Pattern "signal-correctness smoke, and hard trailing replay acceptance must all pass" -Paths @("SPLIT_PROGRESS.md") -Description "split progress records the signal and trailing closure prerequisites"
    foreach ($pattern in @("If ``-EnvFile`` is overridden", "one consistent runtime configuration", "server verification, split acceptance, and every server-local MCP smoke", "audit_live_readiness_ssh.ps1")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md") -Description "operator docs explain EnvFile propagation across acceptance wrappers $pattern"
    }
    foreach ($pattern in @("Windows SSH wrappers validate .SshHost. locally", "unsupported SSH target syntax|option-like targets")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "operator docs explain SSH target guard $pattern"
    }
    foreach ($pattern in @("-EnvFile", "consistent runtime", "server-local MCP (acceptance )?smokes")) {
        Assert-RgMatch -Pattern $pattern -Paths @("docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "handoff docs record EnvFile-consistent local acceptance wrapper evidence $pattern"
    }
    foreach ($pattern in @("acceptanceTarget: total trailing PnL improvement >= 5%", "replayIntervalCode", "backtest interval selects normalized trades", "30d/500")) {
        Assert-RgMatch -Pattern $pattern -Paths @("README.md", "docs/deploy-runbook.md", "docs/split-acceptance-status.md", "SPLIT_PROGRESS.md") -Description "docs keep trailing replay acceptance target marker $pattern"
    }
    Assert-RgMatch -Pattern "RequireTrailingAcceptance.*cannot be combined with.*SkipSplitAcceptance" -Paths @("scripts/verify_post_deploy_issue_acceptance_ssh.ps1") -Description "post-deploy issue acceptance wrapper rejects hard trailing closure without split acceptance"
    Assert-PostDeployIssueAcceptanceFlagGuard
    Assert-RgMatch -Pattern "guardrail, signal-correctness, and trailing replay smokes" -Paths @("SPLIT_PROGRESS.md", "docs/split-acceptance-status.md") -Description "current handoff docs mention all post-deploy issue acceptance smokes"
    Assert-RgNoMatch -Pattern "Current local (handoff|parity) head.*``[0-9a-f]{7,40}``|local handoff head ``[0-9a-f]{7,40}``|Local .*passed through ``[0-9a-f]{7,40}``|Local verification .*through ``[0-9a-f]{7,40}``" -Paths @("docs/split-acceptance-status.md", "docs/legacy-trading-parity-inventory.md", "SPLIT_PROGRESS.md") -Description "handoff docs must not pin amend-prone local head SHAs"

    Write-Host "[verify] checking deploy script git attributes"
    $shellEol = git ls-files --eol -- deploy.sh scripts/*.sh
    foreach ($line in $shellEol) {
        if ($line -notmatch "w/lf" -or $line -notmatch "attr/text eol=lf") {
            Write-Error "Shell script must stay LF in the worktree and git attributes:`n$line"
        }
    }

    $shellModes = git ls-files -s -- deploy.sh scripts/*.sh
    foreach ($line in $shellModes) {
        if ($line -notmatch "^100755 ") {
            Write-Error "Server shell script must keep executable mode 100755:`n$line"
        }
    }

    Write-Host "[verify] OK"
} finally {
    Pop-Location
}
