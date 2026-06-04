package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "服務類型")
public enum ServiceTypeEnum {

    @Schema(description = "一般服務")
    REGULAR,

    @Schema(description = "快速服務")
    EXPRESS,

    @Schema(description = "包裹服務")
    PACKAGE,

    @Schema(description = "宅配服務")
    HOME_DELIVERY,

    @Schema(description = "店取服務")
    STORE_PICKUP,

    @Schema(description = "冷鏈服務")
    COLD_CHAIN,

    @Schema(description = "標準服務")
    STANDARD,

    @Schema(description = "經濟服務")
    ECONOMY,

    @Schema(description = "特急服務")
    URGENT;
} 