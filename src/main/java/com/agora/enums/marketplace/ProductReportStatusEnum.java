package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商品檢舉處理狀態")
public enum ProductReportStatusEnum {
    @Schema(description = "待處理")
    PENDING,

    @Schema(description = "處理中")
    REVIEWING,

    @Schema(description = "已結案")
    RESOLVED,

    @Schema(description = "已駁回(檢舉不成立)")
    DISMISSED
}
