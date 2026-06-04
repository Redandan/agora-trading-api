package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "交付證明狀態")
public enum DeliveryProofStatusEnum {
    @Schema(description = "已提交")
    SUBMITTED,

    @Schema(description = "買家已確認")
    BUYER_CONFIRMED,

    @Schema(description = "買家已拒絕")
    BUYER_REJECTED,

    @Schema(description = "已逾期")
    EXPIRED
}
