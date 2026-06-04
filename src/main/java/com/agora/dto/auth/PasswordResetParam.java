package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "密碼重置參數")
public class PasswordResetParam {
    @Schema(description = "舊密碼")
    @NotNull
    private String oldPassword;

    @Schema(description = "新密碼")
    @NotNull
    private String newPassword;

    @Schema(description = "確認新密碼")
    @NotNull
    private String confirmNewPassword;
}