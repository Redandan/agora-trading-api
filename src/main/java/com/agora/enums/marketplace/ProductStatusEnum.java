package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商品狀態")
public enum ProductStatusEnum {
    @Schema(description = "上架")
    ON_SALE,

    @Schema(description = "下架")
    OFF_SALE,

    @Schema(description = "待審核")
    PENDING_REVIEW,

    @Schema(description = "已刪除")
    DELETED
} 