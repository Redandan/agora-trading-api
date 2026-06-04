package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "OAuth第三方登录提供商")
public enum OAuthProvider {
    @Schema(description = "Google")
    GOOGLE,
    
    @Schema(description = "Facebook")
    FACEBOOK,
    
    @Schema(description = "Telegram Bot")
    TELEGRAM_BOT,
    
    @Schema(description = "WalletConnect (Web3 Wallet)")
    WALLET_CONNECT,
    
    @Schema(description = "Tron (TRC20 Wallet)")
    TRON
}

