package com.agora.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录方式信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "登录方式信息")
public class LoginMethod {
    
    @Schema(description = "登录方式类型", example = "GOOGLE_OAUTH2", allowableValues = {"GOOGLE_OAUTH2", "TELEGRAM_BOT", "WALLET_CONNECT"})
    private String type;
    
    @Schema(description = "显示名称", example = "Google")
    private String name;
    
    @Schema(description = "是否可用", example = "true")
    private Boolean available;
    
    @Schema(description = "Telegram Bot 信息（仅当 type 为 TELEGRAM_BOT 时返回）")
    private TelegramBotInfo telegramBot;
    
    @Schema(description = "WalletConnect 信息（仅当 type 为 WALLET_CONNECT 时返回）")
    private WalletConnectInfo walletConnect;
    
    /**
     * Telegram Bot 信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Telegram Bot 登录信息")
    public static class TelegramBotInfo {
        
        @Schema(description = "Bot Username（用于生成 deep link，不包含 @ 符号）", example = "AgoraMarketBot")
        private String botUsername;
        
        @Schema(description = "生成登录 token 的 API 端点", example = "/auth/telegram-bot/generate-login-token")
        private String generateTokenEndpoint;
        
        @Schema(description = "验证 JWT 的 API 端点", example = "/auth/telegram-bot/verify-jwt")
        private String verifyJwtEndpoint;
        
        @Schema(description = "Deep Link 格式说明", example = "https://t.me/{botUsername}?start={loginToken}")
        private String deepLinkFormat;
    }
    
    /**
     * WalletConnect 信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "WalletConnect 登录信息")
    public static class WalletConnectInfo {
        
        @Schema(description = "获取 nonce 的 API 端点", example = "/auth/wallet-connect/nonce")
        private String nonceEndpoint;
        
        @Schema(description = "验证签名并登录的 API 端点", example = "/auth/wallet-connect/login")
        private String loginEndpoint;
        
        @Schema(description = "支持的链 ID 列表", example = "[1, 137, 56]")
        private List<Integer> supportedChains;
        
        @Schema(description = "文档链接", example = "https://docs.walletconnect.com/")
        private String documentationUrl;
    }
}

