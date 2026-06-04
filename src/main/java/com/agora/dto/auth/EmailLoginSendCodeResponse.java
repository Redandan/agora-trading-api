package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * #379 — Java 21 record. Used only via static factory methods on the
 * record itself; AuthController calls success() / error() / errorWithWaitTime()
 * — never constructs directly with raw setters.
 */
@Schema(description = "發送郵箱登入驗證碼響應")
public record EmailLoginSendCodeResponse(
        @Schema(description = "操作是否成功", example = "true")
        boolean success,

        @Schema(description = "操作結果消息", example = "驗證碼已發送到您的郵箱")
        String message,

        @Schema(description = "錯誤代碼（當操作失敗時）", example = "EMAIL_NOT_FOUND")
        String errorCode,

        @Schema(description = "剩餘等待時間（秒，當操作失敗且需要等待時）", example = "300")
        Long remainingSeconds
) {
    /** No-arg success factory; renamed from {@code success()} to avoid clash
     *  with the record's auto-generated {@code success()} accessor. */
    public static EmailLoginSendCodeResponse ok() {
        return new EmailLoginSendCodeResponse(true, "驗證碼已發送到您的郵箱", null, null);
    }

    public static EmailLoginSendCodeResponse success(String message) {
        return new EmailLoginSendCodeResponse(true, message, null, null);
    }

    public static EmailLoginSendCodeResponse error(String message) {
        return new EmailLoginSendCodeResponse(false, message, null, null);
    }

    public static EmailLoginSendCodeResponse error(String message, String errorCode) {
        return new EmailLoginSendCodeResponse(false, message, errorCode, null);
    }

    public static EmailLoginSendCodeResponse errorWithWaitTime(String message, long remainingSeconds) {
        return new EmailLoginSendCodeResponse(false, message, null, remainingSeconds);
    }
}
