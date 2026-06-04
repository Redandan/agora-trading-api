package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** #379 — Java 21 record. Strategy hot-path entity domain. */
@Schema(description = "策略與回測結果查詢回應")
public record StrategyBacktestDetailResponse(
        @Schema(description = "策略資訊", requiredMode = Schema.RequiredMode.REQUIRED)
        StrategyResponse strategy,

        @Schema(description = "回測結果列表", requiredMode = Schema.RequiredMode.REQUIRED)
        List<BacktestResultResponse> results
) {
}
