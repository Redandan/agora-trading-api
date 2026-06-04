package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "退貨原因")
public enum ReturnReasonEnum {

    @Schema(description = "商品與描述不符")
    NOT_AS_DESCRIBED("商品與描述不符"),

    @Schema(description = "商品有損壞/瑕疵")
    DAMAGED_OR_DEFECTIVE("商品有損壞/瑕疵"),

    @Schema(description = "少寄 / 寄錯商品")
    WRONG_OR_MISSING_ITEM("少寄 / 寄錯商品"),

    @Schema(description = "商品未送達 / 無法取件")
    NOT_DELIVERED_OR_UNCLAIMABLE("商品未送達 / 無法取件"),

    @Schema(description = "其他（手動填寫）")
    OTHER("其他");

    private final String description;

    ReturnReasonEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
