package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "發送郵箱登入驗證碼請求")
public class EmailLoginSendCodeRequest {
    @Schema(description = "用戶郵箱", example = "user@example.com")
    @NotBlank(message = "郵箱不能為空")
    @Email(message = "郵箱格式不正確")
    @NotNull(message = "郵箱不能為空")
    private String email;
}

