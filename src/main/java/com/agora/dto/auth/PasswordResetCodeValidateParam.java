package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "密碼重置驗證碼校驗請求")
public class PasswordResetCodeValidateParam {

    @Email
    @NotBlank
    @Schema(description = "用戶郵箱", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank
    @Schema(description = "郵件驗證碼", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
