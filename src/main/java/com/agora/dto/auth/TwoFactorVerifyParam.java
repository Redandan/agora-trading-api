package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "雙因素認證驗證參數")
public class TwoFactorVerifyParam {
    @Schema(description = "驗證碼")
    @NotNull
    private String code;
} 