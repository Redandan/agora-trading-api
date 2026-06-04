package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "註冊參數")
public class RegisterParam {
    @Schema(description = "用戶名")
    @NotBlank(message = "用戶名不能為空")
    @Size(min = 3, max = 20, message = "用戶名長度必須在3-20個字符之間")
    private String username;

    @Schema(description = "密碼")
    @NotBlank(message = "密碼不能為空")
    @Size(min = 8, max = 128, message = "密碼長度必須在8-128個字符之間")
    private String password;

    @Schema(description = "確認密碼")
    private String confirmPassword;

    @Schema(description = "電子郵件")
    @Email(message = "郵箱格式不正確")
    private String email;

    @Schema(description = "推廣碼")
    private String promoCode;

    @Schema(description = "Cloudflare Turnstile 驗證 Token（可選）")
    private String turnstileToken;

    @Schema(description = "來源頁面 URL（可選，由前端傳入 document.referrer），用於流量分析", nullable = true)
    @Size(max = 500)
    private String referrer;
} 