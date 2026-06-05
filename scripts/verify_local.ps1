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

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Write-Host "[verify] mvn test"
    mvn test

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

    Assert-RgNoMatch -Pattern "service\\.auth\\.model|bot\\.conversation|com\\.agora\\.entity|telemetry/game" -Paths @("src/main/java/com/agora/model/README.md") -Description "stale model package guidance"
    Assert-RgNoMatch -Pattern "system/auth/frontend remnants|Cleanup Queue" -Paths @("SPLIT_PROGRESS.md", "SERVICE_BOUNDARY.md") -Description "stale split progress/boundary wording"
    Assert-RgNoMatch -Pattern "archunit|ArchitectureTest|arch-boundaries-violations|arch-refactor-plan" -Paths @("pom.xml") -Description "stale ArchUnit boundary-test residue"
    Assert-RgNoMatch -Pattern "db_migration_history|db/migrations|matchIfMissing = true|Has V040" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "stale migration drift checker defaults"
    Assert-RgMatch -Pattern "flyway_schema_history" -Paths @("src/main/java/com/agora/config/MigrationDriftChecker.java") -Description "migration drift checker uses Flyway history table"
    Assert-RgMatch -Pattern "temporary bootstrap-only schema mode" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "ddl-auto update is documented as temporary bootstrap-only"
    Assert-RgMatch -Pattern "Flyway baseline" -Paths @("docs/deploy-runbook.md", "SPLIT_PROGRESS.md") -Description "migration baseline prerequisite is documented"
    Assert-RgMatch -Pattern "schema_baseline_inventory.ps1" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline starts with read-only source inventory"
    Assert-RgMatch -Pattern "read-only source inventory complete" -Paths @("scripts/schema_baseline_inventory.ps1") -Description "schema inventory script is read-only and explicit"
    Assert-RgMatch -Pattern "schema_baseline_compare_server.sh" -Paths @("docs/deploy-runbook.md", "docs/schema-baseline.md", "SPLIT_PROGRESS.md") -Description "schema baseline has read-only server compare step"
    Assert-RgMatch -Pattern "information_schema.tables" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare queries database metadata only"
    Assert-RgMatch -Pattern "read-only compare complete" -Paths @("scripts/schema_baseline_compare_server.sh") -Description "schema compare documents read-only behavior"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED:false" -Paths @("src/main/resources/application.yml") -Description "Flyway is disabled by default until baseline exists"
    Assert-RgMatch -Pattern "SPRING_FLYWAY_ENABLED=false" -Paths @("scripts/bootstrap_server.sh", "docs/deploy-runbook.md") -Description "server template keeps Flyway disabled before baseline"
    Assert-RgNoMatch -Pattern "baseline-on-migrate=true|baseline-on-migrate" -Paths @("pom.xml") -Description "pom does not claim Flyway baseline is already enabled"
    Assert-RgNoMatch -Pattern "db/migrations|db_migration_history|V0[0-9]+__" -Paths @("src/main/java") -Description "stale migration path comments"
    Assert-RgNoMatch -Pattern "GET /internal/exchange-rates" -Paths @("SERVICE_BOUNDARY.md") -Description "service boundary uses externally callable internal API path"
    Assert-RgNoMatch -Pattern "(GET|POST) /internal/users" -Paths @("SERVICE_BOUNDARY.md") -Description "deferred identity candidates use externally callable internal API path"
    Assert-RgMatch -Pattern "GET /api/internal/exchange-rates/usdt" -Paths @("SERVICE_BOUNDARY.md", "SPLIT_PROGRESS.md", "INTERNAL_API_TODO.md") -Description "exchange-rate internal API path is consistent"
    Assert-RgMatch -Pattern "Status: implemented in trading" -Paths @("INTERNAL_API_TODO.md") -Description "exchange-rate internal API TODO reflects current SDK implementation"
    Assert-RgMatch -Pattern "Split Guardrails Covered By Verification" -Paths @("docs/split-audit.md") -Description "split audit documents local deploy/schema/contract guards"
    Assert-RgMatch -Pattern "Split deploy guardrails stay documented" -Paths @("docs/deploy-runbook.md") -Description "deploy runbook documents local split deploy/schema/contract guards"
    Assert-RgMatch -Pattern '@Profile\("!local-smoke"\)' -Paths @("src/main/java/com/agora/config/TradingSchedulingConfig.java") -Description "local-smoke does not register scheduled tasks"
    Assert-RgMatch -Pattern "Scheduling disabled for local-smoke profile" -Paths @("src/main/java/com/agora/config/LocalSmokeSchedulingConfig.java", "scripts/smoke_local_health.ps1") -Description "local-smoke smoke logs prove scheduling is disabled"
    Assert-RgMatch -Pattern "localSmokeDoesNotRegisterScheduledTasks" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test proves local-smoke scheduling is disabled"
    Assert-RgMatch -Pattern "does not register scheduled tasks" -Paths @("docs/deploy-runbook.md", "docs/split-audit.md") -Description "local-smoke scheduler exclusion is documented"

    Assert-RgNoMatch -Pattern "sed[^\r\n]*(8084|8085|/api/trading/|127\\.0\\.0\\.1)" -Paths @("deploy.sh") -Description "unsafe deploy nginx sed swap"
    Assert-RgMatch -Pattern "required env key missing or empty" -Paths @("deploy.sh") -Description "deploy fails fast on missing or empty required env key"
    Assert-RgMatch -Pattern "cleanup_new_instance" -Paths @("deploy.sh") -Description "deploy cleans new process and pid file on failure after startup"
    Assert-RgMatch -Pattern 'rm -f "app.pid.\$NEW_PORT"' -Paths @("deploy.sh") -Description "deploy removes new-port pid file on failed startup"
    Assert-RgMatch -Pattern "invalid app.port value" -Paths @("deploy.sh") -Description "deploy rejects unknown active port state"
    Assert-RgMatch -Pattern "unknown active port" -Paths @("scripts/verify_server.sh") -Description "server verify rejects unknown active port state"
    Assert-RgMatch -Pattern "invalid TRADING_PORT" -Paths @("scripts/install_nginx_path.sh") -Description "nginx path installer rejects unknown trading port"
    Assert-RgMatch -Pattern "internal-client pom missing" -Paths @("deploy.sh") -Description "deploy fails fast when AgoraMarket internal-client is missing"
    Assert-RgMatch -Pattern 'mvn -f "\$INTERNAL_CLIENT_POM" install' -Paths @("deploy.sh") -Description "deploy installs AgoraMarket internal-client before building trading"
    Assert-RgMatch -Pattern 'missing or empty.*in \$ENV_FILE' -Paths @("scripts/preflight_server.sh", "scripts/verify_server.sh") -Description "server preflight/verify require non-empty env keys"

    Assert-RgMatch -Pattern '@ActiveProfiles\("local-smoke"\)' -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "context test uses local-smoke profile"

    Assert-RgNoMatch -Pattern "@SpringBootTest\(properties|spring\.datasource\.url|market\.liquidation-ws\.enabled|trading\.tiny-live\.auto-execution\.enabled" -Paths @("src/test/java/com/agora/trading/TradingApiApplicationTests.java") -Description "inline local-smoke duplicate properties in context test"

    foreach ($pattern in @("Assert-LogContains", "Assert-LogNotContains", "AGORA_MARKET_INTERNAL_API_KEY", "TRADING_OKX_API_KEY", "TRADING_BINANCE_API_KEY", "TELEGRAM_BOT_TOKEN", "Scheduling disabled for local-smoke profile", "Auto-trade enabled", "API Key configured", "Trading disabled.*private WS skipped", "order placed", "send telegram")) {
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
