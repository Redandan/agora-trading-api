package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;

@Data
@Schema(description = "登入參數")
public class LoginParam {
    @Schema(description = "用戶名（用於傳統登入）")
    private String username;
    
    @Schema(description = "密碼（用於傳統登入）")
    private String password;
    
    @Schema(description = "郵箱（用於郵箱驗證碼登入）")
    @Email(message = "郵箱格式不正確")
    private String email;
    
    @Schema(description = "驗證碼（用於郵箱驗證碼登入）")
    private String verificationCode;
    
    @Schema(description = "記住我")
    private boolean rememberMe;
    
    @Schema(description = "雙因素認證碼")
    private String twoFactorCode;
    
    @Schema(description = "Cloudflare Turnstile 驗證 Token（可選）")
    private String turnstileToken;
} 