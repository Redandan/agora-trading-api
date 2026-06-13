Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Rg {
    param(
        [string]$Pattern,
        [string[]]$Paths
    )

    # Windows PowerShell 5.1 can split native-command args when the regex contains
    # embedded literal quotes. Escape them before handing the pattern to rg.
    $nativePattern = $Pattern -replace '"', '\"'
    $output = & rg $nativePattern @Paths
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
        'TradingGridProperties.java|@DefaultValue("true") boolean recycleClosedLevels'
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

function Assert-McpParityToolCoverage {
    $requiredTools = Get-McpParityRequiredTools
    $mcpSource = Get-ChildItem -LiteralPath "src/main/java/com/agora/mcp" -Filter "*.java" -Recurse
    foreach ($tool in $requiredTools) {
        $foundInSource = $false
        foreach ($file in $mcpSource) {
            if (Select-String -LiteralPath $file.FullName -Pattern "\b$tool\s*\(" -Quiet) {
                $foundInSource = $true
                break
            }
        }
        if (-not $foundInSource) {
            Write-Error "MCP parity smoke requires tool '$tool' but no matching MCP Java method exists"
        }

        if (-not (Select-String -LiteralPath "scripts/smoke_local_health.ps1" -Pattern "`"$tool`"" -Quiet)) {
            Write-Error "Local smoke must require the same MCP parity tool as smoke_mcp_parity.ps1: $tool"
        }
    }

    foreach ($marker in @("tools/list", "getMcpRegistryVersion", "api/trading/mcp")) {
        Assert-RgMatch -Pattern $marker -Paths @("scripts/smoke_mcp_parity.ps1", "scripts/smoke_local_health.ps1") -Description "MCP parity smoke marker $marker"
    }
}

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Write-Host "[verify] mvn test"
    mvn test
    if ($LASTEXITCODE -ne 0) {
        throw "mvn test failed with exit code $LASTEXITCODE"
    }

    Write-Host "[verify] checking source boundary markers"
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
    Assert-RgMatch -Pattern '"/mcp"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "MCP endpoint remains the only trading tool HTTP surface"
    Assert-RgMatch -Pattern "exact public HTTP allowlist is enforced by ``scripts/verify_local.ps1``" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "exact public HTTP allowlist verification is documented"

    Assert-RgNoMatch -Pattern "service\\.auth\\.model|bot\\.conversation|com\\.agora\\.entity|telemetry/game" -Paths @("src/main/java/com/agora/model/README.md") -Description "stale model package guidance"
    Assert-RgNoMatch -Pattern "system/auth/frontend remnants|Cleanup Queue" -Paths @("SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale split progress/boundary wording"
    Assert-RgNoMatch -Pattern "archunit|ArchitectureTest|arch-boundaries-violations|arch-refactor-plan" -Paths @("pom.xml") -Description "stale ArchUnit boundary-test residue"
    Assert-RgMatch -Pattern "smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180" -Paths @("README.md") -Description "README documents full local smoke command"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL=http://127\.0\.0\.1:8080" -Paths @("README.md") -Description "README documents AgoraMarket local dependency base URL"
    Assert-RgMatch -Pattern "AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000" -Paths @("README.md") -Description "README documents bounded AgoraMarket internal API timeout"
    Assert-RgMatch -Pattern "/api/trading/mcp" -Paths @("README.md") -Description "README documents trading MCP context path"
    Assert-RgMatch -Pattern "production currentness" -Paths @("README.md") -Description "README warns local verification does not prove production currentness"
    Assert-RgNoMatch -Pattern "db_migration_history|db/migrations|matchIfMissing = true|Has V040" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "stale migration drift checker defaults"
    Assert-RgMatch -Pattern "flyway_schema_history" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "migration drift checker uses Flyway history table"
    Assert-RgMatch -Pattern "temporary bootstrap-only schema mode" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "ddl-auto update is documented as temporary bootstrap-only"
    Assert-RgMatch -Pattern "Flyway baseline" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "migration baseline prerequisite is documented"
    Assert-RgMatch -Pattern "AgoraMarketAPI Trading Cutover Plan" -Paths @("docs/deploy-runbook.md") -Description "legacy AgoraMarketAPI trading cutover plan is documented"
    Assert-RgMatch -Pattern "split-acceptance-status.md" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "current split acceptance handoff is linked"
    Assert-RgMatch -Pattern "Shared-DB schema compare and Trading deployment acceptance have passed" -Paths @("docs/split-acceptance-status.md") -Description "acceptance handoff records post-cutover trading deployment acceptance"
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
    Assert-RgMatch -Pattern "V1__baseline.sql" -Paths @("scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md", "docs/deploy-runbook.md") -Description "baseline generator writes reviewable Flyway baseline path"
    Assert-RgMatch -Pattern "shared marketplace tables are intentionally excluded|Shared marketplace tables are intentionally excluded" -Paths @("scripts/schema_baseline_generate_server.sh", "docs/schema-baseline.md") -Description "baseline generator excludes shared marketplace tables"
    Assert-RgNoMatch -Pattern "SPRING_FLYWAY_ENABLED=true|SPRING_JPA_HIBERNATE_DDL_AUTO=validate|flyway enabled|DROP TABLE|schema_extra_tables_cleanup" -Paths @("scripts/schema_baseline_generate_server.sh") -Description "baseline generator must not enable Flyway, switch ddl-auto, or run cleanup"
    Assert-RgMatch -Pattern "schema_extra_tables_cleanup_plan_server.sh" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "standalone-only schema extra-table cleanup planning is documented"
    Assert-RgNoMatch -Pattern "^[[:space:]]*DROP TABLE" -Paths @("scripts/schema_extra_tables_cleanup_plan_server.sh") -Description "schema extra-table cleanup planner must not execute drop statements"
    Assert-RgMatch -Pattern "disabled in shared DB mode" -Paths @("scripts/schema_extra_tables_cleanup_apply_server.sh", "scripts/schema_extra_tables_cleanup_plan_server.sh", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema extra-table cleanup is disabled in shared DB mode"
    Assert-RgMatch -Pattern "APPLY_SCHEMA_EXTRA_TABLE_CLEANUP" -Paths @("scripts/schema_extra_tables_cleanup_apply_server.sh", "docs/schema-baseline.md") -Description "standalone schema extra-table cleanup apply path requires explicit apply flag"
    Assert-RgMatch -Pattern "mysqldump" -Paths @("scripts/schema_extra_tables_cleanup_apply_server.sh") -Description "schema extra-table cleanup apply path creates a backup before destructive cleanup"
    Assert-RgMatch -Pattern "dry-run complete" -Paths @("scripts/schema_extra_tables_cleanup_apply_server.sh") -Description "standalone schema extra-table cleanup apply path defaults to dry-run"
    Assert-RgMatch -Pattern "schema_baseline_compare_server.sh" -Paths @("scripts/schema_extra_tables_cleanup_apply_server.sh", "docs/schema-baseline.md") -Description "schema extra-table cleanup apply path can regenerate read-only compare outputs"
    Assert-RgMatch -Pattern "RUN_SCHEMA_BASELINE_COMPARE" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline compare is exposed through server verification"
    Assert-RgMatch -Pattern "VERIFY_GIT_CURRENT" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "server verification checks deployed git currentness by default"
    Assert-RgMatch -Pattern "REQUIRE_DEPLOY_METADATA=.*REQUIRE_DEPLOY_METADATA:-1" -Paths @("scripts/verify_server.sh") -Description "server verification requires deploy metadata by default"
    Assert-RgMatch -Pattern "REQUIRE_DEPLOY_METADATA=0.*diagnostics" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "deploy metadata bypass is documented as diagnostic only"
    Assert-RgMatch -Pattern "deploy commit file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.commit metadata is missing by default"
    Assert-RgMatch -Pattern "deploy port file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.port metadata is missing by default"
    Assert-RgMatch -Pattern "deploy pid file missing" -Paths @("scripts/verify_server.sh") -Description "server verification fails when app.pid metadata is missing by default"
    Assert-RgMatch -Pattern 'does not match origin/\$BRANCH' -Paths @("scripts/verify_server.sh") -Description "server verification fails when deployed commit differs from origin branch"
    Assert-RgMatch -Pattern "app.commit" -Paths @("deploy.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "deploy records and server verify checks deployed commit metadata"
    Assert-RgMatch -Pattern "deployed app.commit.*does not match worktree HEAD" -Paths @("scripts/verify_server.sh") -Description "server verification fails when deployed commit metadata is stale"
    Assert-RgNoMatch -Pattern "deployment completed from ``origin/main`` commit|trading was deployed from ``origin/main`` commit" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md") -Description "stale observed deployment commit must not be phrased as current production"
    Assert-RgMatch -Pattern "historical evidence, not a current-deployment claim" -Paths @("SPLIT_PROGRESS.md", "docs/deploy-runbook.md") -Description "observed deployment commit is clearly marked historical"
    Assert-RgMatch -Pattern "schema baseline database comparison skipped" -Paths @("scripts/verify_server.sh") -Description "schema compare is opt-in for normal server verification"
    Assert-RgMatch -Pattern "EXPECTED_AGORA_MARKET_BASE_URL" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts guard AgoraMarket local dependency base URL"
    Assert-RgMatch -Pattern 'EXPECTED_AGORA_MARKET_BASE_URL="\$EXPECTED_AGORA_MARKET_BASE_URL"' -Paths @("scripts/verify_server.sh") -Description "server verification passes AgoraMarket expected base URL into preflight"
    Assert-RgMatch -Pattern "EXPECTED_TRADING_DATABASE" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts guard shared trading database target"
    Assert-RgMatch -Pattern 'EXPECTED_TRADING_DATABASE="\$EXPECTED_TRADING_DATABASE"' -Paths @("deploy.sh", "scripts/verify_server.sh") -Description "deploy/server verification propagate expected trading database target"
    Assert-RgMatch -Pattern "EXPECTED_TRADING_DATABASE" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare guards shared trading database target even when run directly"
    Assert-RgMatch -Pattern 'EXPECTED_TRADING_DATABASE="\$EXPECTED_TRADING_DATABASE"' -Paths @("scripts/verify_server.sh") -Description "server verification propagates expected trading database target into schema compare"
    Assert-RgMatch -Pattern "SPRING_DATASOURCE_URL must point at expected shared database" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md", "docs/schema-baseline.md") -Description "server scripts require shared datasource target"
    Assert-RgMatch -Pattern "SCHEMA_COMPARE_MODE" -Paths @("scripts/schema_baseline_compare_server.sh", "scripts/verify_server.sh", "docs/schema-baseline.md") -Description "schema compare supports explicit shared or standalone mode"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL must point at local AgoraMarketAPI dependency" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server scripts fail on stale AgoraMarket base URL"
    Assert-RgMatch -Pattern "SPRING_DATASOURCE_URL should point at the shared agora_market database" -Paths @("scripts/validate_env_template.ps1") -Description "env template validator requires shared datasource target"
    Assert-RgMatch -Pattern "AGORA_MARKET_HEALTH_URL=.*http://127\.0\.0\.1:8080/api/actuator/health" -Paths @("deploy.sh", "scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts check local AgoraMarket dependency health by default"
    Assert-RgMatch -Pattern "AgoraMarket exchange-rate dependency health failed" -Paths @("deploy.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy/verify fail on AgoraMarket health failure"
    Assert-RgMatch -Pattern "before starting the blue-green switch" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "deploy pre-switch AgoraMarket health gate is documented"
    Assert-RgMatch -Pattern "REQUIRE_AGORA_MARKET_HEALTH=.*REQUIRE_AGORA_MARKET_HEALTH:-1" -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server preflight/verify require AgoraMarket health by default"
    Assert-RgMatch -Pattern "REQUIRE_AGORA_MARKET_HEALTH=0.*diagnostic" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md", "SPLIT_PROGRESS.md") -Description "AgoraMarket health bypass is documented as diagnostic only"
    Assert-RgMatch -Pattern "AgoraMarket exchange-rate dependency local health failed" -Paths @("scripts/preflight_server.sh") -Description "server preflight fails on AgoraMarket health failure by default"
    Assert-RgMatch -Pattern 'REQUIRE_AGORA_MARKET_HEALTH=\$REQUIRE_AGORA_MARKET_HEALTH' -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "AgoraMarket health bypass stays diagnostic in preflight and server verification"
    Assert-RgNoMatch -Pattern "agoramarketapi\.purrtechllc\.com/api/actuator/health" -Paths @("scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server dependency health checks must not use public AgoraMarket health URL"
    Assert-RgMatch -Pattern "information_schema.tables" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare queries database metadata only"
    Assert-RgMatch -Pattern "missing or empty.*ENV_FILE" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare rejects empty datasource env keys when run directly"
    Assert-RgMatch -Pattern "server-implicit-entities.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects implicit entity table names"
    Assert-RgMatch -Pattern "server-forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects obvious marketplace-owned table names"
    Assert-RgMatch -Pattern "server-db-forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare reports marketplace-owned database tables"
    Assert-RgMatch -Pattern "SCHEMA_COMPARE_MODE.*standalone" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare fails on marketplace-owned database tables only in standalone mode"
    Assert-RgMatch -Pattern "MARKETPLACE_TABLE_PATTERN" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare shares marketplace table pattern across source and database checks"
    Assert-RgMatch -Pattern "KNOWN_SYSTEM_TABLE_PATTERN" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare centralizes known system table pattern"
    Assert-RgMatch -Pattern "server-db-known-system-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare classifies known database system tables"
    Assert-RgMatch -Pattern "flyway_schema_history" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "schema compare classifies Flyway history table separately"
    Assert-RgMatch -Pattern "read-only compare complete" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare documents read-only behavior"
    foreach ($commandName in @("comm", "find", "grep", "mkdir", "mysql", "perl", "sort", "tail", "tr", "wc", "xargs")) {
        Assert-RgMatch -Pattern "require_cmd $commandName" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "server schema compare fails fast when $commandName is unavailable"
    }
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED:false" -Paths @("src/main/resources/application.yml") -Description "Flyway is disabled by default until baseline exists"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED=false" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "server template keeps Flyway disabled before baseline"
    Assert-RgMatch -Pattern "require_env_value SPRING_JPA_HIBERNATE_DDL_AUTO update" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification keeps ddl-auto update until Flyway baseline exists"
    Assert-RgMatch -Pattern "require_env_value SPRING_FLYWAY_ENABLED false" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification keeps Flyway disabled until baseline exists"
    Assert-RgMatch -Pattern "require_env_value AGORA_MARKET_INTERNAL_TIMEOUT_MS 3000" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server deploy/verification keeps AgoraMarket internal API timeout bounded"
    Assert-RgNoMatch -Pattern 'matches expected temporary bootstrap value|must be \$expected until the Flyway baseline exists' -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "fixed-value env guard messages must stay generic because they also cover AgoraMarket timeout"
    Assert-RgMatch -Pattern "temporary schema bootstrap env values" -Paths @("SPLIT_PROGRESS.md") -Description "split progress documents schema bootstrap env guard"
    Assert-RgMatch -Pattern "Deploy, server preflight, and verification fail if" -Paths @("docs/split-audit.md", "docs/deploy-runbook.md") -Description "schema bootstrap env guard is documented"
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
    Assert-RgMatch -Pattern "/api/trading/mcp" -Paths @("docs/legacy-trading-parity-inventory.md", "README.md", "scripts/smoke_mcp_parity.ps1") -Description "legacy parity docs and smoke use standalone MCP path"
    Assert-RgMatch -Pattern "Covered through .*McpTools|MCP-first" -Paths @("docs/legacy-trading-parity-inventory.md") -Description "legacy HTTP parity inventory records MCP-first replacement boundary"
    Assert-RgMatch -Pattern "Do not remove AgoraMarketAPI marketplace HTTP or internal exchange-rate APIs" -Paths @("docs/legacy-trading-parity-inventory.md") -Description "legacy parity inventory preserves marketplace/internal API boundary"
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
    foreach ($tool in @("backfillOkxKlines", "backfillDexFlow", "backfillFundingRateHistory", "backfillLongShortRatioHistory", "backfillFredMacro", "backfillHyperliquidFunding", "backfillOpenInterest", "importPolymarketHistory", "backfillCoinalyzeLiquidation")) {
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
    Assert-RgMatch -Pattern "POST /api/trading/mcp" -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "MCP docs use trading context path"
    Assert-RgNoMatch -Pattern 'POST /api/mcp|localhost:" \+ serverPort \+ "/api/mcp' -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "stale pre-split MCP path"
    Assert-RgMatch -Pattern '"\/mcp",' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "Spring Security permits bare MCP endpoint for MCP filter auth"
    Assert-RgMatch -Pattern '@ConditionalOnProperty\(name = "trading\.tiny-live\.auto-execution\.enabled", havingValue = "true", matchIfMissing = false\)' -Paths @("src/main/java/com/agora/scheduler/trading/TinyLiveAutoExecutionScheduler.java") -Description "TinyLive auto-execution scheduler bean is explicit opt-in"
    Assert-RgMatch -Pattern 'trading\.tiny-live\.auto-execution\.enabled:false' -Paths @("src/main/java/com/agora/scheduler/trading/TinyLiveAutoExecutionScheduler.java") -Description "TinyLive auto-execution method guard defaults off"

    Write-Host "[verify] checking shell script syntax"
    $bash = Resolve-BashCommand
    $shellScripts = @("deploy.sh") + @(Get-ChildItem -LiteralPath "scripts" -Filter "*.sh" | ForEach-Object { "scripts/$($_.Name)" })
    & $bash -n @shellScripts

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
        "TRADING_GRID_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED",
        "TRADING_RUNTIME_EVIDENCE_ENABLED",
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
        "trading.discovery.ai-suggestions.enabled=false",
        "trading.kline-divergence.enabled=false",
        "market.ws.auto-subscribe.warm-up-enabled=false",
        "position-exit-manager.dry-run=true",
        "trailing-stop.dry-run=true"
    )) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_health.ps1") -Description "local-smoke clears high-risk split runtime key $pattern"
    }

    foreach ($pattern in @("Assert-LogContains", "Assert-LogNotContains", "Assert-McpContentContains", "Invoke-McpTool", "Stop-ProcessTree", "Get-CimInstance Win32_Process", "Stop-ProcessTree -RootPid", "SetEnvironmentVariable", "AGORA_MARKET_INTERNAL_API_KEY", "MCP_API_KEY", "MCP_OPS_KEY", "TRADING_OKX_API_KEY", "TRADING_BINANCE_API_KEY", "TELEGRAM_BOT_TOKEN", "GEMINI_API_KEY", "GROQ_API_KEY", "ANTHROPIC_API_KEY", "JINA_API_KEY", "TRADING_MARKET_DATA_COINALYZE_API_KEY", "EXCHANGE_RATE_COINMARKETCAP_API_KEY", "META_CONTROL_ATTENTION_WEEKLY_DIGEST_ENABLED", "META_CONTROL_SCORECARD_DIGEST_ENABLED", "META_CONTROL_STARTUP_BACKFILL_COINALYZE_ENABLED", "META_CONTROL_STARTUP_BACKFILL_COMPOSITE_INDICATOR_ENABLED", "META_CONTROL_STARTUP_BACKFILL_DEX_FLOW_ENABLED", "META_CONTROL_STARTUP_BACKFILL_HYPERLIQUID_FUNDING_ENABLED", "META_CONTROL_ATTRIBUTION_ENABLED", "META_CONTROL_ML_MATERIALIZED_REFRESH_STARTUP_CHECK_ENABLED", "META_CONTROL_HOURLY_ORCHESTRATOR_ENABLED", "META_CONTROL_COMPOSITE_INDICATOR_SCHEDULER_ENABLED", "META_CONTROL_MARKET_INDICATOR_ATTENTION_ENABLED", "META_CONTROL_MARKET_FLIP_DETECTOR_ENABLED", "META_CONTROL_MARKET_FLIP_ANALYSIS_ENABLED", "META_CONTROL_MARKET_FLIP_AUTO_ESCALATE_ENABLED", "META_CONTROL_ML_PROTECTION_AUTO_KILL_SECONDARY_LOAD", "META_CONTROL_ML_SHADOW_ENABLED", "MARKET_WS_AUTO_SUBSCRIBE_ENABLED", "MARKET_LIQUIDATION_WS_ENABLED", "OKX_EARN_TOPUP_ENABLED", "POLYMARKET_MONITOR_ENABLED", "TRADING_EXPLORATION_MONITOR_ENABLED", "TRADING_EXPLORATION_MONITOR_TELEGRAM_ENABLED", "TRADING_EXPLORATION_LOOP_ENABLED", "TRADING_EXPLORATION_LOOP_TELEGRAM_ENABLED", "TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED", "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED", "TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION", "TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE", "TRADING_AUTONOMOUS_DIGEST_ENABLED", "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED", "TRADING_AUTONOMOUS_DIGEST_SEVERE_SCAN_ENABLED", "TRADING_AUTONOMOUS_DIGEST_SNAPSHOT_REFRESH_ENABLED", "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_ENABLED", "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED", "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED", "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED", "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN", "TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_ENABLED", "TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED", "EVENT_SCAN_NOTIFICATION_ENABLED", "EVENT_SCAN_NOTIFICATION_DRY_RUN", "EXECUTION_EVENT_ENABLED", "EXECUTION_EVENT_NOTIFICATION_DRY_RUN", "WICK_CAPTURE_SHADOW_ENABLED", "WICK_CAPTURE_SHADOW_BOOTSTRAP_ENABLED", "SHADOW_CLEANUP_ENABLED", "GRID_RECOVERY_ENABLED", "TRADING_DAILY_TG_REPORT_ENABLED", "TRADING_BTC_PRICE_MOVE_ALERT_ENABLED", "MARKET_SIGNAL_RISK_CARD_ENABLED", "MARKET_SIGNAL_RISK_CARD_DRY_RUN", "TRADING_WAI_ENABLED", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED", "TRADING_FUNDING_ARB_ENABLED", "TRAILING_STOP_ENABLED", "POSITION_EXIT_MANAGER_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_TAKER_BUY_COLLECTOR_ENABLED", "SIGNAL_VERIFICATION_SCHEDULER_ENABLED", "AGORA_ALPHA_TRACKER_ENABLED", "AI_STRATEGY_DISCOVERY_ENABLED", "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED", "TRADING_EVENT_CALENDAR_FRESHNESS_NOTIFICATION_ENABLED", "api/trading/mcp", "getMcpRegistryVersion", "getMarketSentiment", "getSystemHealth", "backfillOkxKlines", "TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true", "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true", "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true", "Authorization = `"Bearer local-smoke-mcp`"", "spring-boot.run.arguments", "agora-market.base-url=http://127.0.0.1:0", "mcp.api-key=local-smoke-mcp", "mcp.ops-key=local-smoke-mcp", "gemini.api.key=", "groq.api.key=", "trading.market-data.coinalyze.api-key=", "trading.okx.api-key=", "trading.oco-poller.enabled=false", "telegram.bot.token=", "meta-control.attention-weekly-digest.enabled=false", "meta-control.scorecard-digest.enabled=false", "meta-control.startup-backfill.coinalyze.enabled=false", "meta-control.startup-backfill.composite-indicator.enabled=false", "meta-control.startup-backfill.dex-flow.enabled=false", "meta-control.startup-backfill.hyperliquid-funding.enabled=false", "meta-control.attribution.enabled=false", "meta-control.ml-materialized-refresh.startup-check-enabled=false", "meta-control.hourly-orchestrator.enabled=false", "meta-control.composite-indicator.scheduler-enabled=false", "meta-control.market-indicator-attention.enabled=false", "meta-control.market-flip-detector.enabled=false", "meta-control.market-flip.analysis-enabled=false", "meta-control.market-flip.auto-escalate-enabled=false", "meta-control.ml-protection.auto-kill-secondary-load=false", "meta-control.ml-shadow.enabled=false", "market.ws.auto-subscribe.enabled=false", "market.liquidation-ws.enabled=false", "okx.earn-topup.enabled=false", "polymarket.monitor.enabled=false", "trading.exploration.monitor.enabled=false", "trading.exploration.monitor.telegram.enabled=false", "trading.exploration.loop.enabled=false", "trading.exploration.loop.telegram.enabled=false", "trading.exploration.loop.production.enabled=false", "trading.exploration.rollout.auto-enabled=false", "trading.exploration.rollout.allow-production-promotion=false", "trading.exploration.rollout.allow-cap-increase=false", "trading.autonomous.digest.enabled=false", "trading.autonomous.digest.telegram-enabled=false", "trading.autonomous.digest.severe-scan-enabled=false", "trading.autonomous.digest.snapshot-refresh-enabled=false", "trading.score-buy.forming-day.notification.enabled=false", "trading.score-buy.forming-day.notification.telegram-enabled=false", "trading.score-buy.pre-position.execution.enabled=false", "trading.score-buy.pre-position.execution.dry-run=true", "trading.score-buy.confirmed-deploy.execution.enabled=false", "trading.score-buy.confirmed-deploy.execution.dry-run=true", "trading.score-buy.post-scout-add.execution.enabled=false", "trading.score-buy.post-scout-add.execution.dry-run=true", "trading.score-buy.post-scout-add.notification.enabled=false", "trading.score-buy.post-scout-add.notification.telegram-enabled=false", "event-scan.notification.enabled=false", "event-scan.notification.dry-run=true", "execution-event.enabled=false", "execution-event.notification-dry-run=true", "wick-capture.shadow.enabled=false", "wick-capture.shadow.bootstrap-enabled=false", "shadow-cleanup.enabled=false", "grid.recovery.enabled=false", "trading.daily-tg-report.enabled=false", "trading.btc-price-move-alert.enabled=false", "market-signal.risk-card.enabled=false", "market-signal.risk-card.dry-run=true", "trading.wai.enabled=false", "trading.grid.auto-rebalance-scheduler.enabled=false", "trading.funding-arb.enabled=false", "position-exit-manager.enabled=false", "trailing-stop.enabled=false", "trading.short-squeeze-alert.enabled=false", "trading.short-squeeze-alert.taker-buy-collector-enabled=false", "signal-verification.scheduler.enabled=false", "agora.alpha-tracker.enabled=false", "ai.strategy.discovery.enabled=false", "trading.live-signal.retry-notification.enabled=false", "trading.event-calendar.freshness-notification-enabled=false", "Scheduling disabled for local-smoke profile", "Auto-trade enabled", "API Key configured", "OCO poller disabled.*private WS skipped", "AiTaskRouter.*initialized with 0 providers", "Jina embedding client initialised: enabled=false", "MarketWS.*auto-subscribe config: enabled=false", "OkxLiqWS.*disabled by market", "PolymarketMonitor.*(fatal|digest|snapshot)", "Attribution/startup", "MlMatRefresh.*start refresh", "MlMatRefresh.*kicking off initial refresh", "DexFlowBackfill", "HLFundingBackfill", "CoinalyzeBackfill", "CMIBackfill", "Auto subscribed via", "Warming up MarketSignalCache", "Trading buffer topped from Earn", "Simple Earn", "modifyOco", "ShortSqueezeAlert.*FIRED", "SpotTakerBuy.*15m taker buy", "order placed", "send telegram")) {
        Assert-RgMatch -Pattern $pattern -Paths @("scripts/smoke_local_health.ps1") -Description "local-smoke log guard pattern $pattern"
    }

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
