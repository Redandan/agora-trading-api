package com.agora.dto.auth;

import com.agora.enums.system.OAuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "綁定OAuth參數")
public class BindOAuthParam {
    
    @NotNull(message = "OAuth提供商不能為空")
    @Schema(description = "OAuth提供商", requiredMode = Schema.RequiredMode.REQUIRED)
    private OAuthProvider provider;
    
    // Google OAuth2 參數
    @Schema(description = "臨時Token ID（推薦，用於Google OAuth2，從 /auth/oauth2/token/by-token-id 獲取）")
    private String tokenId;
    
    @Schema(description = "授權碼（用於Google OAuth2，不推薦，建議使用tokenId）")
    private String code;
    
    @Schema(description = "狀態參數（用於Google OAuth2，當使用code時必填）")
    private String state;
    
    @Schema(description = "重定向URI（用於Google OAuth2）")
    private String redirectUri;
    
    // Telegram Bot 參數
    @Schema(description = "登入Token（用於Telegram Bot）")
    private String loginToken;
    
    @Schema(description = "驗證碼（用於Telegram Bot）")
    private String verificationCode;
    
    // Telegram WebApp 參數
    @Schema(description = "初始化數據（用於Telegram WebApp，包含簽名驗證）")
    private String initData;
    
    // WalletConnect / Tron 參數
    @Schema(description = "錢包地址（用於WalletConnect或Tron）")
    private String walletAddress;
    
    @Schema(description = "簽名（用於WalletConnect或Tron）")
    private String signature;
    
    @Schema(description = "簽名消息（用於WalletConnect或Tron）")
    private String message;
}

