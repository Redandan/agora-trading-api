package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Data
@Schema(description = "綁定郵箱參數")
public class BindEmailParam {
    
    @Email(message = "郵箱格式不正確")
    @NotBlank(message = "郵箱不能為空")
    @Schema(description = "用戶郵箱", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    
    @NotBlank(message = "驗證碼不能為空")
    @Schema(description = "驗證碼", requiredMode = Schema.RequiredMode.REQUIRED)
    private String verificationCode;
}

