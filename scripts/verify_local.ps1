Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Write-Host "[verify] mvn test"
    mvn test

    Write-Host "[verify] checking source boundary markers"
    $forbidden = rg "FlutterDeployment|FlutterAppDeployment|AppVersion|flutter/deployment|SearchLog|UserSearchLog|user_search_log|CustomerIssue|CreateIssueParam|IssueSearchParam|ReplyIssueParam|IssueTypeEnum|IssueStatusEnum|AdminImageAuditService|BrokenImage|AiProductClassificationSuggestion|imageaudit|UserAddress|PostalArea|TaiwanPostalArea|DeliveryCountryPolicy|OAuth2Service|OAuth2AuthorizationService|OAuth2UsageService|OAuth2Authorize|OAuth2Token|GoogleOAuthUserInfo|/login/oauth2|WalletConnectSession|WalletConnectNonce|WalletConnectSignatureVerifier|WalletConnectNonceResponse|Web3LoginRequest|Web3NonceRequest|oauth2-client|AuthService\\.java|AuthCode|TwoFactorAuthService|TwoFactor(Manage|Setup|Status|Verify)|GoogleAuthenticator|googleauth|RegisterResult|RegisterParam|LoginParam|PasswordReset(Param|WithCodeParam|CodeValidate)|EmailLogin(Request|SendCode)|BindEmail|BindOAuth|LoginBindings|LoginMethod|AdminResetPassword|AdminCreateUser|UserProfileUpdate|UpdateUsername|TrackReferrerRequest|TelegramWebAppAuthService|TelegramBotLoginService|LoginResult|UserOAuthBinding|OAuthProvider|UserServiceImpl|MemberSearchParam|MemberUpdateParam|DefaultHomePageEnum|RegistrationMethodEnum|PostService|PostCreateParam|PostSearchParam|PostResponse|PostUpdateParam|PostStatusEnum|web-push|webpush|WebPush|ProductReport|ProductTypeEnum|ProductStatusEnum|ProductCategoryEnum|ValidProductByType|CartSummaryDTO|OrderStatusChangedEvent|OrderFulfilledEvent|EventPublisherService|DeliveryProofStatusEnum|DeliveryProofTypeEnum|DeliveryReportTypeEnum|DeliveryStatusEnum|OrderStatusEnum|OrderSortTypeEnum|OrderSearchDateTypeEnum|ShippingCompanyEnum|PickupServiceTypeEnum|CompanyCategory|EnumTranslationUtil|ColdWalletStatusEnum|WalletStatusEnum|NotificationTypeEnum|NotificationStatusEnum|DeliveryFeeProperties|DigitalOrderProperties|GeoUtils|LogisticsCalculator|CreateOrderTool|OrderTool|OrderStatusTool|SmartOrderTool|RecommendProductsTool|SmartSearchTool|LoginTool|McpLoginTool|BetStatusEnum|MarketOptionStatusEnum|MarketStatusEnum|MarketTypeEnum|PromoCodeStatusEnum|RechargeStatusEnum|\\bServiceTypeEnum\\b|WithdrawStatusEnum|/products/|/products\\*|/pwa-logs|/slot/|PwaLog|TrafficAnalytics|Slot(Symbol|Traffic|Overview|Hourly|Daily)|RegistrationOverview|DailyRegistrationStats|HourlyRegistrationStats|MethodRegistrationStats|PromoCodeRegistrationStats|slotPaytableConfig|slotRtp|ApplyStakingParam|ChatMessage(DTO|QueryParam|UpdateDTO)|ChatSessionQueryParam|InterestRecord(DTO|SearchParam)|ManualAdjustBalanceParam|NextInterestEstimateDTO|Staking(ConfigDTO|ConfigUpdateParam|SearchParam|StatisticsDTO)|Transaction(ListParam|SearchParam|TypeEnum)|Dispute(Outcome|StatusEnum)|ReturnReasonEnum|Tg(Game|Handicap)Type" src/main/java src/main/resources/application.yml pom.xml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden Flutter/AppVersion residue found:`n$forbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg failed with exit code $LASTEXITCODE"
    }

    $marketplaceRealtimeForbiddenFiles = rg --files src/main/java | rg "Chat(Session|Message)(Repository)?\.java|ChatMessageBuilder\.java|WebRTC.*\.java|SSE(Service|Config|Event(Request|Response)|ExceptionHandler)\.java|SseProperties\.java|NotifyEventTypeEnum\.java|ClientId(Validator|FormatException)\.java|UnauthorizedException\.java|ReturnRejectedEvent\.java|PromoCodeView\.java|StakingStatusEnum\.java|WalletException\.java|BuyerInfoSchemaValidator\.java|CreateColdWalletParam\.java|FounderAffiliatedSellerRegistry\.java|WithdrawRiskService\.java|UserWithdrawRiskState(Repository)?\.java|SanctionBlacklist(Service|Address|AddressRepository)\.java|AiGroupConversion(Daily|Event|Service).*\.java|AiAnalyticsService\.java|GroupConversionStatsDTO\.java|AutoReply(Config|ConfigRepository|ConfigService|ConfigSearchRequest|ResetStatsResponse|DeleteResponse)\.java|FileAssociation(ErrorResponse|Exception)\.java|BusinessIdGenerator\.java|TronSignatureVerifier\.java|TestDataService\.java|EncryptedStringConverter\.java|LlmContextRedactor\.java|SchedulerJobTypeEnum\.java|dto[\\/]scheduler[\\/].*\.java"
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden marketplace realtime/frontend files found:`n$marketplaceRealtimeForbiddenFiles"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg marketplace realtime file check failed with exit code $LASTEXITCODE"
    }

    $authForbidden = rg "JwtAuthenticationFilter|CurrentUserMethodArgumentResolver|\bCurrentUser\b|UserPrincipal|CustomUserDetailsServiceImpl|DeviceFingerprintUtil|SecurityUtils|/auth/\*\*" src/main/java src/main/resources/application.yml pom.xml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden web auth residue found:`n$authForbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg auth check failed with exit code $LASTEXITCODE"
    }

    $loginForbidden = rg "JwtUtil|JwtConfig|jjwt|McpAuthLevel\.MEMBER|\bMEMBER\b|User JWT|isValidJwt|/auth/\*\*|TelegramLoginBotConfig|TelegramWebhookConfig|login-bot|USER_LOGIN|USER_LOGOUT|LOGIN_ANOMALY|TWO_FACTOR_AUTH_REQUIRED|SUSPICIOUS_LOGIN_ATTEMPT|ACCOUNT_LOCKED|AppMarketProperties|agora_login_bot" src/main/java src/main/resources/application.yml pom.xml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden login/JWT residue found:`n$loginForbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg login/JWT check failed with exit code $LASTEXITCODE"
    }

    $userBoundaryForbidden = rg "UserRepository|AutoReplyService|AutoReplyServiceImpl|WebRTCSignalingService|UserStatusEnum|@Table\(name = `"users`"" src/main/java src/main/resources/application.yml pom.xml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden marketplace user boundary residue found:`n$userBoundaryForbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg user boundary check failed with exit code $LASTEXITCODE"
    }

    $unsafeDeployNginxSwap = rg "sed[^\r\n]*(8084|8085|/api/trading/|127\\.0\\.0\\.1)" deploy.sh
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Unsafe deploy nginx sed swap found; keep deploy.sh scoped to the /api/trading/ block:`n$unsafeDeployNginxSwap"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg deploy nginx swap check failed with exit code $LASTEXITCODE"
    }

    Write-Host "[verify] OK"
} finally {
    Pop-Location
}
