package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "交付證明類型")
public enum DeliveryProofTypeEnum {
    @Schema(description = "截圖")
    SCREENSHOT,

    @Schema(description = "收據/發票")
    RECEIPT,

    @Schema(description = "兌換碼/序號")
    REDEMPTION_CODE,

    @Schema(description = "轉寄 email")
    EMAIL_FORWARD,

    @Schema(description = "其他")
    OTHER
}
