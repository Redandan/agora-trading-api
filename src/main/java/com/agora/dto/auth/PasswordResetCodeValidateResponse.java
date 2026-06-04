package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "密碼重置驗證碼校驗結果")
public record PasswordResetCodeValidateResponse(
        @Schema(description = "驗證碼是否有效") boolean valid,
        @Schema(description = "穩定狀態碼", example = "VALID") String status,
        @Schema(description = "前端可顯示訊息") String message
) {

    public static PasswordResetCodeValidateResponse success() {
        return new PasswordResetCodeValidateResponse(true, "VALID", "驗證碼有效");
    }

    public static PasswordResetCodeValidateResponse invalid(String message) {
        return new PasswordResetCodeValidateResponse(false, "INVALID_OR_EXPIRED", message);
    }
}
