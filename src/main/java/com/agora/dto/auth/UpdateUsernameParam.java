package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@Schema(description = "修改用戶名參數")
public class UpdateUsernameParam {
    
    @NotBlank(message = "用戶名不能為空")
    @Size(min = 3, max = 20, message = "用戶名長度必須在3-20個字符之間")
    @Schema(description = "新用戶名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newUsername;
}

