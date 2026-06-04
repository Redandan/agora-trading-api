package com.agora.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "商品檢舉統計儀表板")
public class ProductReportStatsDto {

    @Schema(description = "查詢區間天數", example = "7")
    private int periodDays;

    @Schema(description = "區間內新進檢舉數")
    private long newReportsInPeriod;

    @Schema(description = "目前 PENDING 數")
    private long pendingCount;

    @Schema(description = "目前 REVIEWING 數")
    private long reviewingCount;

    @Schema(description = "目前 RESOLVED 數")
    private long resolvedCount;

    @Schema(description = "目前 DISMISSED 數")
    private long dismissedCount;

    @Schema(description = "SLA 違規:超過 slaHours 仍未結案的數量")
    private long slaBreachCount;

    @Schema(description = "SLA 閾值(小時)", example = "48")
    private int slaThresholdHours;

    @Schema(description = "區間內各 reason_category 的數量(key=enum name)")
    private Map<String, Long> reasonBreakdown;
}
