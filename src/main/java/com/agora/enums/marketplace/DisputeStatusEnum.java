package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "申訴狀態")
public enum DisputeStatusEnum {

    @Schema(description = "待處理")
    PENDING("待處理"),

    @Schema(description = "已完成")
    COMPLETED("已完成"),

    @Schema(description = "已駁回")
    REJECTED("已駁回");

    private final String description;

    DisputeStatusEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
