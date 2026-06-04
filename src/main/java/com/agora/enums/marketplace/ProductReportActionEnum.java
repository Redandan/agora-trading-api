package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "檢舉處理後採取的行動")
public enum ProductReportActionEnum {
    @Schema(description = "未採取行動")
    NONE,

    @Schema(description = "已警告賣家")
    WARNED_SELLER,

    @Schema(description = "商品已隱藏")
    PRODUCT_HIDDEN,

    @Schema(description = "商品已移除")
    PRODUCT_REMOVED,

    @Schema(description = "賣家已停權")
    SELLER_SUSPENDED
}
