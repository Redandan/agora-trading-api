package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "回測執行請求")
public class BacktestRunRequest {

    @Schema(description = "策略 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "strategyId 不可為空")
    private Long strategyId;

    @Schema(description = "交易對", example = "BTCUSDT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "symbol 不可為空")
    private String symbol;

    @Schema(description = "回測週期", example = "1h", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "intervalCode 不可為空")
    private String intervalCode;

    @Schema(description = "回測開始時間（ISO-8601）", example = "2025-01-01T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "startTime 不可為空")
    private LocalDateTime startTime;

    @Schema(description = "回測結束時間（ISO-8601）", example = "2025-03-01T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "endTime 不可為空")
    private LocalDateTime endTime;

    @Schema(description = "初始資金", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "initialCapital 不可為空")
    @DecimalMin(value = "0.0001", message = "initialCapital 必須大於 0")
    private BigDecimal initialCapital;

    @Schema(description = "手續費率", example = "0.001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "feeRate 不可為空")
    @DecimalMin(value = "0.0", message = "feeRate 不可為負數")
    private BigDecimal feeRate = new BigDecimal("0.001");

    @Schema(description = "套用歷史過濾器（F&G、事件日曆、資金費率）進行回測。預設 false。",
            example = "false")
    private Boolean applyFilters = false;

    @Schema(description = "K 線資料源覆寫：binance 或 okx。留白時使用 strategy.klineSource（V041 起的 source of truth），" +
            "後者若也為 null 則退回 market.signal.source（預設 okx）。僅 MCP 研究工具（如 validateRobustness、" +
            "MetaControlAttribution）會明確指定；一般 Controller 呼叫應留白讓策略自決。",
            example = "okx", nullable = true)
    private String source;

    /**
     * 跳過 bt_backtest_result 寫入(回傳結果但不持久化)。
     * 用於 Walk-Forward 驗證等不應汙染「最新回測」狀態的 ad-hoc 評估。
     * 預設 false 維持向下相容。
     */
    @Schema(description = "skipPersist=true 時不寫入 bt_backtest_result(WF 驗證等用)。預設 false。",
            example = "false", nullable = true)
    private Boolean skipPersist = false;

    /**
     * 臨時覆蓋策略 config 參數（不寫 DB，僅用於本次回測）。
     * 例如 {"buyThreshold": 25, "requireFundingImprovingBars": 48}。
     * 優先級高於 DB config，低於 runIntervalCode / applyFilters 等請求層覆寫。
     * 用於 MCP runBacktest configOverrideJson 和 runBacktestSweep。
     */
    @Schema(description = "臨時 config 覆蓋（不改 DB），供參數探索使用。", nullable = true)
    private java.util.Map<String, Object> configOverride;
}
