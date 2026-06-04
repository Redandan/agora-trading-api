package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "修改/設置密碼參數")
public class ChangePasswordParam {
    @Schema(description = "舊密碼（如果用戶已有密碼則必填，如果沒有密碼則可選）")
    private String oldPassword;

    @Schema(description = "新密碼", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新密碼不能為空")
    @Size(min = 8, max = 128, message = "密碼長度必須在8-128個字符之間")
    @NotNull
    private String newPassword;

    @Schema(description = "確認新密碼", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "確認新密碼不能為空")
    @NotNull
    private String confirmNewPassword;
} 