Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Write-Host "[verify] mvn test"
    mvn test

    Write-Host "[verify] checking source boundary markers"
    $forbidden = rg "FlutterDeployment|FlutterAppDeployment|AppVersion|flutter/deployment|SearchLog|UserSearchLog|user_search_log|CustomerIssue|CreateIssueParam|IssueSearchParam|ReplyIssueParam|IssueTypeEnum|IssueStatusEnum|AdminImageAuditService|BrokenImage|AiProductClassificationSuggestion|imageaudit|UserAddress|PostalArea|TaiwanPostalArea|DeliveryCountryPolicy|OAuth2Service|OAuth2AuthorizationService|OAuth2UsageService|OAuth2Authorize|OAuth2Token|GoogleOAuthUserInfo|/login/oauth2|WalletConnectSession|WalletConnectNonce|WalletConnectSignatureVerifier|WalletConnectNonceResponse|Web3LoginRequest|Web3NonceRequest|oauth2-client|AuthService\\.java|AuthCode|TwoFactorAuthService|TwoFactor(Manage|Setup|Status|Verify)|GoogleAuthenticator|googleauth|RegisterResult|RegisterParam|RegisterRequestParam|LoginParam|PasswordReset(Param|WithCodeParam|CodeValidate)|EmailLogin(Request|SendCode)|BindEmail|BindOAuth|LoginBindings|LoginMethod|AdminResetPassword|AdminCreateUser|UserProfileUpdate|UpdateUsername|web-push|webpush|WebPush|ProductReport|ProductTypeEnum|ProductStatusEnum|ProductCategoryEnum|ValidProductByType|CartSummaryDTO|OrderStatusChangedEvent|OrderFulfilledEvent|EventPublisherService|DeliveryProofStatusEnum|DeliveryProofTypeEnum|DeliveryReportTypeEnum|DeliveryStatusEnum|OrderStatusEnum|OrderSortTypeEnum|OrderSearchDateTypeEnum|ColdWalletStatusEnum|WalletStatusEnum|NotificationTypeEnum|NotificationStatusEnum|DeliveryFeeProperties|DigitalOrderProperties|GeoUtils|LogisticsCalculator|CreateOrderTool|OrderTool|OrderStatusTool|SmartOrderTool|RecommendProductsTool|SmartSearchTool|LoginTool|McpLoginTool" src/main/java src/main/resources/application.yml pom.xml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden Flutter/AppVersion residue found:`n$forbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg failed with exit code $LASTEXITCODE"
    }

    Write-Host "[verify] OK"
} finally {
    Pop-Location
}
