package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用戶註冊方式")
public enum RegistrationMethodEnum {

    @Schema(description = "傳統表單註冊（用戶名+密碼）")
    FORM,

    @Schema(description = "郵箱驗證碼自動註冊")
    EMAIL_CODE,

    @Schema(description = "Google OAuth2 首次登入自動建立帳號")
    GOOGLE,

    @Schema(description = "Telegram Bot 首次登入自動建立帳號")
    TELEGRAM_BOT,

    @Schema(description = "Telegram WebApp 首次登入自動建立帳號")
    TELEGRAM_WEBAPP,

    @Schema(description = "WalletConnect (以太坊) 首次登入自動建立帳號")
    WALLET_CONNECT,

    @Schema(description = "Tron 錢包首次登入自動建立帳號")
    TRON,

    @Schema(description = "管理員後台創建")
    ADMIN
}
