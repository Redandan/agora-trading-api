package com.agora.dto.market;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** #379 — Java 21 record. Built via constructor in KlineMarketController only. */
@Schema(description = "K 線 OHLCV 資料（用於前端圖表展示）")
public record KlineBarDTO(
        @Schema(description = "開盤時間 (UTC)", example = "2025-01-01T00:00:00")
        LocalDateTime openTime,

        @Schema(description = "收盤時間 (UTC)", example = "2025-01-01T00:14:59")
        LocalDateTime closeTime,

        @Schema(description = "開盤價", example = "100000.00000000")
        BigDecimal open,

        @Schema(description = "最高價", example = "101500.00000000")
        BigDecimal high,

        @Schema(description = "最低價", example = "99800.00000000")
        BigDecimal low,

        @Schema(description = "收盤價", example = "101200.00000000")
        BigDecimal close,

        @Schema(description = "成交量", example = "23.4500000000")
        BigDecimal volume
) {
}
