package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商品類型")
public enum ProductTypeEnum {

    @Schema(description = "實體商品")
    PHYSICAL("實體商品"),

    @Schema(description = "數位服務代購")
    DIGITAL_SERVICE("數位服務代購");

    private final String displayName;

    ProductTypeEnum(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
