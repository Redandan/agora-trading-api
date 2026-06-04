package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "申訴處理結果")
public enum DisputeOutcome {

    @Schema(description = "全部退款")
    FULL_REFUND("全部退款"),

    @Schema(description = "部分退款")
    PARTIAL_REFUND("部分退款"),

    @Schema(description = "駁回")
    REJECTED("駁回");

    private final String description;

    DisputeOutcome(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
