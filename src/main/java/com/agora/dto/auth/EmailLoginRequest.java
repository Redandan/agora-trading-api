package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "郵箱登入請求")
public class EmailLoginRequest {
    @Schema(description = "用戶郵箱", example = "user@example.com")
    @NotBlank(message = "郵箱不能為空")
    @Email(message = "郵箱格式不正確")
    @NotNull(message = "郵箱不能為空")
    private String email;

    @Schema(description = "驗證碼", example = "123456")
    @NotBlank(message = "驗證碼不能為空")
    @NotNull(message = "驗證碼不能為空")
    private String verificationCode;
}

