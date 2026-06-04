package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "使用郵件驗證碼提交新密碼")
public class PasswordResetWithCodeParam {

    @Email
    @NotBlank
    @Schema(description = "用戶郵箱", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank
    @Schema(description = "郵件驗證碼", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank
    @Size(min = 8, max = 128, message = "密碼長度必須在8-128個字符之間")
    @Schema(description = "新密碼", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;

    @NotBlank
    @Schema(description = "確認新密碼", requiredMode = Schema.RequiredMode.REQUIRED)
    private String confirmNewPassword;
}
