package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "AI 策略自動探勘請求")
public class AiStrategyDiscoveryRequest {

    @Schema(description = "交易對", example = "BTCUSDT")
    @NotBlank(message = "symbol 不可為空")
    private String symbol = "BTCUSDT";

    @Schema(description = "回測 K 線週期", example = "1h")
    @NotBlank(message = "intervalCode 不可為空")
    private String intervalCode = "1h";

    @Schema(description = "回測開始時間（ISO-8601）", example = "2025-01-01T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "startTime 不可為空")
    private LocalDateTime startTime;

    @Schema(description = "回測結束時間（ISO-8601）", example = "2025-03-31T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "endTime 不可為空")
    private LocalDateTime endTime;

    @Schema(description = "回測初始資金", example = "10000")
    @NotNull(message = "initialCapital 不可為空")
    @DecimalMin(value = "0.0001", message = "initialCapital 必須大於 0")
    private BigDecimal initialCapital = new BigDecimal("10000");

    @Schema(description = "AI 生成的候選策略數量（1 ~ 10）", example = "3", minimum = "1", maximum = "10")
    @Min(value = 1, message = "candidateCount 最小為 1")
    @Max(value = 10, message = "candidateCount 最大為 10")
    private int candidateCount = 3;

    @Schema(description = "回測手續費率", example = "0.001")
    @DecimalMin(value = "0", message = "feeRate 不可為負")
    private BigDecimal feeRate = new BigDecimal("0.001");

    @Schema(description = "評分有效交易次數下限（低於此值 score=0）", example = "5", minimum = "1", maximum = "50")
    @Min(value = 1, message = "minTradeCount 最小為 1")
    @Max(value = 50, message = "minTradeCount 最大為 50")
    private int minTradeCount = 5;

    @Schema(description = "K 線資料源（binance / okx）；null 時走 BacktestService 預設 binance",
            example = "okx")
    private String source;
}
