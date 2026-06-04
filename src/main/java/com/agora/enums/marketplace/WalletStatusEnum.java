package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 錢包狀態枚舉
 * 定義了錢包可能的所有狀態
 */
@Schema(description = "錢包狀態")
public enum WalletStatusEnum {
    @Schema(description = "待處理")
    PENDING,

    @Schema(description = "活躍")
    ACTIVE,

    @Schema(description = "非活躍")
    INACTIVE,

    @Schema(description = "已暫停")
    SUSPENDED,

    @Schema(description = "已封禁")
    BANNED,

    @Schema(description = "已凍結")
    FROZEN
} 