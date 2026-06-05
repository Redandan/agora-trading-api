Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Rg {
    param(
        [string]$Pattern,
        [string[]]$Paths
    )

    $output = & rg $Pattern @Paths
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
    Assert-RgNoMatch -Pattern '"/(public|test|images|telegram/webhook|backtests|admin/market|admin/oco|market)/(.*)?"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "legacy public HTTP route allowlist residue"
    Assert-RgMatch -Pattern '"/mcp"' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "MCP endpoint remains the only trading tool HTTP surface"

    Assert-RgNoMatch -Pattern "service\\.auth\\.model|bot\\.conversation|com\\.agora\\.entity|telemetry/game" -Paths @("src/main/java/com/agora/model/README.md") -Description "stale model package guidance"
    Assert-RgNoMatch -Pattern "system/auth/frontend remnants|Cleanup Queue" -Paths @("SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale split progress/boundary wording"
    Assert-RgNoMatch -Pattern "archunit|ArchitectureTest|arch-boundaries-violations|arch-refactor-plan" -Paths @("pom.xml") -Description "stale ArchUnit boundary-test residue"
    Assert-RgMatch -Pattern "smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180" -Paths @("README.md") -Description "README documents full local smoke command"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL=http://127\.0\.0\.1:8082" -Paths @("README.md") -Description "README documents AgoraMarket local dependency base URL"
    Assert-RgMatch -Pattern "/api/trading/mcp" -Paths @("README.md") -Description "README documents trading MCP context path"
    Assert-RgMatch -Pattern "production currentness" -Paths @("README.md") -Description "README warns local verification does not prove production currentness"
    Assert-RgNoMatch -Pattern "db_migration_history|db/migrations|matchIfMissing = true|Has V040" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "stale migration drift checker defaults"
    Assert-RgMatch -Pattern "flyway_schema_history" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "migration drift checker uses Flyway history table"
    Assert-RgMatch -Pattern "temporary bootstrap-only schema mode" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "ddl-auto update is documented as temporary bootstrap-only"
    Assert-RgMatch -Pattern "Flyway baseline" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "migration baseline prerequisite is documented"
    Assert-RgMatch -Pattern "schema_baseline_inventory.ps1" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline starts with read-only source inventory"
    Assert-RgMatch -Pattern "read-only source inventory complete" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory script is read-only and explicit"
    Assert-RgMatch -Pattern "without explicit @Table" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory rejects implicit table names"
    Assert-RgMatch -Pattern "forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_inventory.ps1", "docs/schema-baseline.md") -Description "schema inventory rejects obvious marketplace-owned table names"
    Assert-RgMatch -Pattern "WriteAllLines" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory clears stale output files when result lists are empty"
    Assert-RgMatch -Pattern "schema_baseline_inventory.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs schema inventory"
    Assert-RgMatch -Pattern "validate_pom_boundary.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs pom dependency boundary"
    Assert-RgMatch -Pattern "validate_package_boundary.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs package boundary"
    Assert-RgMatch -Pattern "validate_env_template.ps1" -Paths @("scripts/verify_split_boundaries.ps1") -Description "split boundary verifier runs env template boundary"
    Assert-RgMatch -Pattern "schema_baseline_compare_server.sh" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline has read-only server compare step"
    Assert-RgMatch -Pattern "RUN_SCHEMA_BASELINE_COMPARE" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline compare is exposed through server verification"
    Assert-RgMatch -Pattern "VERIFY_GIT_CURRENT" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "server verification checks deployed git currentness by default"
    Assert-RgMatch -Pattern 'does not match origin/\$BRANCH' -Paths @("scripts/verify_server.sh") -Description "server verification fails when deployed commit differs from origin branch"
    Assert-RgMatch -Pattern "app.commit" -Paths @("deploy.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "deploy records and server verify checks deployed commit metadata"
    Assert-RgMatch -Pattern "deployed app.commit.*does not match worktree HEAD" -Paths @("scripts/verify_server.sh") -Description "server verification fails when deployed commit metadata is stale"
    Assert-RgMatch -Pattern "schema baseline database comparison skipped" -Paths @("scripts/verify_server.sh") -Description "schema compare is opt-in for normal server verification"
    Assert-RgMatch -Pattern "EXPECTED_AGORA_MARKET_BASE_URL" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts guard AgoraMarket local dependency base URL"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL must point at local AgoraMarketAPI dependency" -Paths @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server scripts fail on stale AgoraMarket base URL"
    Assert-RgMatch -Pattern "AGORA_MARKET_HEALTH_URL=.*http://127\.0\.0\.1:8082/api/actuator/health" -Paths @("scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server scripts check local AgoraMarket dependency health by default"
    Assert-RgNoMatch -Pattern "agoramarketapi\.purrtechllc\.com/api/actuator/health" -Paths @("scripts/bootstrap_server.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server dependency health checks must not use public AgoraMarket health URL"
    Assert-RgMatch -Pattern "information_schema.tables" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare queries database metadata only"
    Assert-RgMatch -Pattern "server-implicit-entities.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects implicit entity table names"
    Assert-RgMatch -Pattern "server-forbidden-marketplace-tables.txt" -Paths @("scripts/schema_baseline_compare_server.sh", "docs/schema-baseline.md") -Description "server schema compare rejects obvious marketplace-owned table names"
    Assert-RgMatch -Pattern "read-only compare complete" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare documents read-only behavior"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED:false" -Paths @("src/main/resources/application.yml") -Description "Flyway is disabled by default until baseline exists"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED=false" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "server template keeps Flyway disabled before baseline"
    Assert-RgNoMatch -Pattern "baseline-on-migrate=true|baseline-on-migrate" -Paths @("pom.xml") -Description "pom does not claim Flyway baseline is already enabled"
    Assert-RgNoMatch -Pattern "db/migrations|db_migration_history|V0[0-9]+__" -Paths @("src/main/java") -Description "stale migration path comments"
    Assert-RgNoMatch -Pattern "GET /internal/exchange-rates" -Paths @("SERVICE_BOUNDARY.md") -Description "service boundary uses externally callable internal API path"
    Assert-RgNoMatch -Pattern "(GET|POST) /internal/users|/api/internal/users" -Paths @("SERVICE_BOUNDARY.md", "INTERNAL_API_TODO.md", "SPLIT_PROGRESS.md") -Description "identity internal API stays out of the first trading split"
    Assert-RgMatch -Pattern "GET /api/internal/exchange-rates/usdt" -Paths @("SERVICE_BOUNDARY.md", "SPLIT_PROGRESS.md", "INTERNAL_API_TODO.md") -Description "exchange-rate internal API path is consistent"
    Assert-RgMatch -Pattern "Status: implemented in trading" -Paths @("INTERNAL_API_TODO.md") -Description "exchange-rate internal API TODO reflects current SDK implementation"
    Assert-RgMatch -Pattern "AGORA_MARKET_BASE_URL:http://127\.0\.0\.1:8082" -Paths @("src/main/resources/application.yml", "INTERNAL_API_TODO.md") -Description "AgoraMarket internal API default points at local server dependency port"
    Assert-RgMatch -Pattern 'baseUrl = "http://127\.0\.0\.1:8082"' -Paths @("src/main/java/com/agora/config/AgoraMarketExchangeRateProperties.java") -Description "AgoraMarket exchange-rate Java fallback matches server dependency port"
    Assert-RgNoMatch -Pattern "localhost:8080|127\.0\.0\.1:8080" -Paths @("INTERNAL_API_TODO.md") -Description "internal API TODO has no stale pre-split AgoraMarket base URL"
    Assert-RgMatch -Pattern 'api-key: \$\{MCP_API_KEY:\$\{TRADING_MCP_KEY:\}\}' -Paths @("src/main/resources/application.yml") -Description "TRADING_MCP_KEY maps to MCP dev auth key"
    Assert-RgMatch -Pattern 'ops-key: \$\{MCP_OPS_KEY:\$\{TRADING_MCP_KEY:\}\}' -Paths @("src/main/resources/application.yml") -Description "TRADING_MCP_KEY maps to MCP ops auth key"
    Assert-RgMatch -Pattern "localSmokeMcpAuthKeysAreConfigured" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves local-smoke MCP auth keys are configured"
    Write-Host "[verify] checking split boundary validators"
    & "$PSScriptRoot\verify_split_boundaries.ps1"
    Assert-RgMatch -Pattern "Split Guardrails Covered By Verification" -Paths @("docs/split-audit.md") -Description "split audit documents local deploy/schema/contract guards"
    Assert-RgMatch -Pattern "Split deploy guardrails stay documented" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents local split deploy/schema/contract guards"
    Assert-OnlyAllowedEnabledTrueFallbacks
    Assert-RgMatch -Pattern "Remaining ``enabled:true`` fallbacks are deliberately limited" -Paths @("docs/split-audit.md", "docs/deploy-runbook.md") -Description "remaining enabled:true fallback classification is documented"
    Assert-RgMatch -Pattern '@Profile\("!local-smoke"\)' -Paths @("src/main/java/com/agora/config/TradingSchedulingConfig.java") -Description "local-smoke does not register scheduled tasks"
    Assert-RgMatch -Pattern "Scheduling disabled for local-smoke profile" -Paths @("src/main/java/com/agora/config/LocalSmokeSchedulingConfig.java", "scripts/smoke_local_health.ps1") -Description "local-smoke smoke logs prove scheduling is disabled"
    Assert-RgMatch -Pattern "localSmokeDoesNotRegisterScheduledTasks" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves local-smoke scheduling is disabled"
    Assert-RgMatch -Pattern "does not register scheduled tasks" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md") -Description "local-smoke scheduler exclusion is documented"
    Assert-RgMatch -Pattern "position-exit manager is disabled" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md") -Description "local-smoke position-exit manager guard is documented"

    Assert-RgNoMatch -Pattern "sed[^\r\n]*(8084|8085|/api/trading/|127\\.0\\.0\\.1)" -Paths @("deploy.sh") -Description "unsafe deploy nginx sed swap"
    Assert-RgMatch -Pattern "required env key missing or empty" -Paths @("deploy.sh") -Description "deploy fails fast on missing or empty required env key"
    Assert-RgMatch -Pattern "has unstaged changes; refusing to overwrite during deploy" -Paths @("deploy.sh") -Description "deploy refuses to overwrite unstaged server changes before reset"
    Assert-RgMatch -Pattern "has staged changes; refusing to overwrite during deploy" -Paths @("deploy.sh") -Description "deploy refuses to overwrite staged server changes before reset"
    Assert-RgMatch -Pattern "cleanup_new_instance" -Paths @("deploy.sh") -Description "deploy cleans new process and pid file on failure after startup"
    Assert-RgMatch -Pattern 'rm -f "app.pid.\$NEW_PORT"' -Paths @("deploy.sh") -Description "deploy removes new-port pid file on failed startup"
    Assert-RgMatch -Pattern "RUN_POST_DEPLOY_VERIFY" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy runs server verification after switching active metadata"
    Assert-RgMatch -Pattern 'RUN_PREFLIGHT=0 bash "\$VERIFY_SCRIPT"' -Paths @("deploy.sh") -Description "deploy post-verify reuses server verifier without repeating preflight"
    Assert-RgMatch -Pattern "post-deploy verification failed; rolling back active metadata" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy rolls back active metadata when post-deploy verification fails"
    Assert-RgMatch -Pattern "post-deploy verifier missing" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy treats missing post-deploy verifier as rollback-worthy failure"
    Assert-RgMatch -Pattern "restoring nginx trading upstream after failed verification" -Paths @("deploy.sh", "docs/deploy-runbook.md") -Description "deploy restores nginx backup when post-deploy verification fails"
    Assert-RgMatch -Pattern "draining old instance after verification" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "deploy drains previous instance only after post-deploy verification"
    Assert-RgMatch -Pattern "DEFAULT_PUBLIC_TRADING_HEALTH_URL" -Paths @("deploy.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "nginx deploy verifies public trading health by default"
    Assert-RgMatch -Pattern "PUBLIC_TRADING_HEALTH_URL=.*DEFAULT_PUBLIC_TRADING_HEALTH_URL" -Paths @("deploy.sh") -Description "post-deploy verify sets public trading health URL when nginx is updated"
    Assert-RgMatch -Pattern "invalid app.port value" -Paths @("deploy.sh") -Description "deploy rejects unknown active port state"
    Assert-RgMatch -Pattern "unknown active port" -Paths @("scripts/verify_server.sh") -Description "server verify rejects unknown active port state"
    Assert-RgMatch -Pattern "deployed app.pid.*is not running" -Paths @("scripts/verify_server.sh") -Description "server verify fails when deployed pid metadata is stale"
    Assert-RgMatch -Pattern "is not listening on active port" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md") -Description "server verify fails when deployed pid does not own active port"
    Assert-RgMatch -Pattern "local MCP getMcpRegistryVersion passed" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server verify proves local MCP endpoint with MCP key"
    Assert-RgMatch -Pattern "REQUIRE_NGINX_TRADING_PATH" -Paths @("scripts/verify_server.sh", "docs/deploy-runbook.md", "docs/split-audit.md") -Description "server verify requires nginx trading path by default"
    Assert-RgMatch -Pattern "nginx /api/trading/ location not found" -Paths @("scripts/verify_server.sh") -Description "server verify fails when nginx trading path is missing"
    Assert-RgMatch -Pattern "invalid TRADING_PORT" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer rejects unknown trading port"
    Assert-RgMatch -Pattern "require_cmd bash" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when bash is unavailable"
    Assert-RgMatch -Pattern "require_cmd lsof" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when lsof is unavailable"
    Assert-RgMatch -Pattern "require_cmd ps" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when ps is unavailable"
    Assert-RgMatch -Pattern "require_cmd lsof" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when lsof is unavailable"
    Assert-RgMatch -Pattern "require_cmd sudo" -Paths @("deploy.sh", "scripts/preflight_server.sh") -Description "deploy/preflight fail fast when sudo is unavailable for nginx swap"
    Assert-RgMatch -Pattern "require_cmd sudo" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer fails fast when sudo is unavailable"
    Assert-RgMatch -Pattern "require_cmd awk" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer fails fast when awk is unavailable"
    Assert-RgMatch -Pattern "internal-client pom missing" -Paths @("deploy.sh") -Description "deploy fails fast when AgoraMarket internal-client is missing"
    Assert-RgMatch -Pattern "internal-client pom missing" -Paths @("scripts/verify_server.sh") -Description "server verify fails fast when AgoraMarket internal-client is missing even when preflight is skipped"
    Assert-RgMatch -Pattern 'mvn -f "\$INTERNAL_CLIENT_POM" install' -Paths @("deploy.sh") -Description "deploy installs AgoraMarket internal-client before building trading"
    Assert-RgMatch -Pattern 'missing or empty.*in \$ENV_FILE' -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server preflight/verify require non-empty env keys"
    Assert-RgMatch -Pattern "env template available" -Paths @("scripts/bootstrap_server.sh") -Description "bootstrap uses tracked env template"
    Assert-RgMatch -Pattern "Optional runtime safety toggles" -Paths @(".env.trading.secrets.example") -Description "server env template documents optional safety toggles"
    Assert-RgMatch -Pattern "optional safety key" -Paths @("scripts/validate_env_template.ps1") -Description "env template validator checks optional safety toggles"
    Assert-RgMatch -Pattern "normalizedContextPath" -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java") -Description "LocalMcpClient normalizes configured context path"
    Assert-RgMatch -Pattern 'normalizedContextPath\(\) \+ "/mcp"' -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java") -Description "LocalMcpClient calls MCP under configured context path"
    Assert-RgMatch -Pattern "POST /api/trading/mcp" -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "MCP docs use trading context path"
    Assert-RgNoMatch -Pattern 'POST /api/mcp|localhost:" \+ serverPort \+ "/api/mcp' -Paths @("src/main/java/com/agora/service/ai/LocalMcpClient.java", "src/main/java/com/agora/mcp/McpStreamableHttpController.java") -Description "stale pre-split MCP path"
    Assert-RgMatch -Pattern '"\/mcp",' -Paths @("src/main/java/com/agora/config/SecurityPaths.java") -Description "Spring Security permits bare MCP endpoint for MCP filter auth"

    Write-Host "[verify] checking shell script syntax"
    $bash = Resolve-BashCommand
    $shellScripts = git ls-files -- deploy.sh scripts/*.sh
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

    foreach ($pattern in @("Assert-LogContains", "Assert-LogNotContains", "AGORA_MARKET_INTERNAL_API_KEY", "MCP_API_KEY", "MCP_OPS_KEY", "TRADING_OKX_API_KEY", "TRADING_BINANCE_API_KEY", "TELEGRAM_BOT_TOKEN", "GEMINI_API_KEY", "GROQ_API_KEY", "ANTHROPIC_API_KEY", "JINA_API_KEY", "EXTERNAL_COINALYZE_API_KEY", "EXCHANGE_RATE_COINMARKETCAP_API_KEY", "META_CONTROL_STARTUP_BACKFILL_COINALYZE_ENABLED", "META_CONTROL_STARTUP_BACKFILL_COMPOSITE_INDICATOR_ENABLED", "META_CONTROL_STARTUP_BACKFILL_DEX_FLOW_ENABLED", "META_CONTROL_STARTUP_BACKFILL_HYPERLIQUID_FUNDING_ENABLED", "MARKET_WS_AUTO_SUBSCRIBE_ENABLED", "MARKET_LIQUIDATION_WS_ENABLED", "OKX_EARN_TOPUP_ENABLED", "POLYMARKET_MONITOR_ENABLED", "TRAILING_STOP_ENABLED", "POSITION_EXIT_MANAGER_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_ENABLED", "TRADING_SHORT_SQUEEZE_ALERT_TAKER_BUY_COLLECTOR_ENABLED", "api/trading/mcp", "getMcpRegistryVersion", "Authorization = `"Bearer local-smoke-mcp`"", "spring-boot.run.arguments", "agora-market.base-url=http://127.0.0.1:0", "mcp.api-key=local-smoke-mcp", "mcp.ops-key=local-smoke-mcp", "gemini.api.key=", "groq.api.key=", "trading.okx.api-key=", "trading.oco-poller.enabled=false", "telegram.bot.token=", "meta-control.startup-backfill.coinalyze.enabled=false", "meta-control.startup-backfill.composite-indicator.enabled=false", "meta-control.startup-backfill.dex-flow.enabled=false", "meta-control.startup-backfill.hyperliquid-funding.enabled=false", "market.ws.auto-subscribe.enabled=false", "market.liquidation-ws.enabled=false", "okx.earn-topup.enabled=false", "polymarket.monitor.enabled=false", "position-exit-manager.enabled=false", "trailing-stop.enabled=false", "trading.short-squeeze-alert.enabled=false", "trading.short-squeeze-alert.taker-buy-collector-enabled=false", "Scheduling disabled for local-smoke profile", "Auto-trade enabled", "API Key configured", "OCO poller disabled.*private WS skipped", "AiTaskRouter.*initialized with 0 providers", "Jina embedding client initialised: enabled=false", "MarketWS.*auto-subscribe config: enabled=false", "OkxLiqWS.*disabled by market", "EarnTopUp.*config: enabled=false", "PolymarketMonitor.*config: enabled=false", "ExitMgr.*init: enabled=false", "TrailingStop.*config: enabled=false", "ShortSqueezeAlert.*config: enabled=false takerBuyCollectorEnabled=false", "DexFlowBackfill", "HLFundingBackfill", "CoinalyzeBackfill", "CMIBackfill", "Auto subscribed via", "Warming up MarketSignalCache", "Trading buffer topped from Earn", "Simple Earn", "modifyOco", "ShortSqueezeAlert.*FIRED", "SpotTakerBuy.*15m taker buy", "order placed", "send telegram")) {
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
