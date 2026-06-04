package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "充值狀態")
public enum RechargeStatusEnum {
    @Schema(description = "待付款")
    PENDING,

    @Schema(description = "已完成")
    COMPLETED,

    @Schema(description = "已超時")
    EXPIRED,

    @Schema(description = "已失敗")
    FAILED
}