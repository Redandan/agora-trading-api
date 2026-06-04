package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商品檢舉原因")
public enum ProductReportReasonEnum {
    @Schema(description = "違法內容")
    ILLEGAL_CONTENT,

    @Schema(description = "疑似詐騙")
    SCAM,

    @Schema(description = "違反平台 ToS")
    TOS_VIOLATION,

    @Schema(description = "假貨/仿冒")
    COUNTERFEIT,

    @Schema(description = "商品資訊不實")
    FALSE_INFO,

    @Schema(description = "禁止販售項目")
    PROHIBITED_ITEM,

    @Schema(description = "其他")
    OTHER
}
