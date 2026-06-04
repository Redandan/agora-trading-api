package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Data
@Schema(description = "策略與回測結果查詢請求")
public class StrategyBacktestDetailQueryRequest {

    @Schema(description = "策略 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "strategyId 不可為空")
    @Positive(message = "strategyId 必須大於 0")
    private Long strategyId;

    @Schema(description = "指定回測結果 ID（優先級最高，指定後僅回傳該筆）", example = "1001", nullable = true)
    @Positive(message = "resultId 必須大於 0")
    private Long resultId;

    @Schema(description = "是否只查最新回測結果（預設 true；當 resultId 有值時此參數會被忽略）", example = "true")
    private Boolean latest = true;

    @Schema(description = "回傳筆數上限（僅 latest=false 且未指定 resultId 時生效）", example = "20", minimum = "1", nullable = true)
    @Positive(message = "limit 必須大於 0")
    private Integer limit;
}