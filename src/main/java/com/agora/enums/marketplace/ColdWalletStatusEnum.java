package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "冷錢包狀態")
public enum ColdWalletStatusEnum {
    @Schema(description = "可用")
    AVAILABLE("可用"),

    @Schema(description = "使用中")
    IN_USE("使用中"),

    @Schema(description = "已凍結")
    FROZEN("已凍結");

    private final String description;

    ColdWalletStatusEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
} 