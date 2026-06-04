package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** #379 — Java 21 record. User hot-path entity domain (auth/2FA). */
@Schema(description = "雙因素認證狀態響應")
public record TwoFactorStatusResponse(
        @Schema(description = "是否已啟用雙因素認證")
        Boolean enabled,

        @Schema(description = "是否已設置")
        Boolean configured
) {
}
