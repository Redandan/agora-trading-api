package com.agora.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** #379 — Java 21 record. WalletConnect 获取 Nonce 响应。 */
@Schema(description = "WalletConnect 获取 Nonce 响应")
public record WalletConnectNonceResponse(
        @Schema(description = "Nonce 值", example = "550e8400-e29b-41d4-a716-446655440000")
        String nonce,

        @Schema(description = "需要签名的消息", example = "Please sign this message to authenticate...")
        String message,

        @Schema(description = "时间戳", example = "1705123456")
        Long timestamp
) {
}
