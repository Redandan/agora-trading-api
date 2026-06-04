package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "策略多條件查詢請求")
public class StrategyQueryRequest {

    @Schema(description = "策略 ID", example = "1", nullable = true)
    private Long id;

    @Schema(description = "策略名稱（模糊比對）", example = "SOP BTC", nullable = true)
    private String name;

    @Schema(description = "策略類型（精準比對，不分大小寫）", example = "SOP_MTF_ADX", nullable = true)
    private String strategyType;

    @Schema(description = "是否啟用", example = "true", nullable = true)
    private Boolean enabled;

    @Schema(description = "建立時間起點（含）", example = "2026-03-01T00:00:00", nullable = true)
    private LocalDateTime createdAtFrom;

    @Schema(description = "建立時間終點（含）", example = "2026-03-31T23:59:59", nullable = true)
    private LocalDateTime createdAtTo;
}