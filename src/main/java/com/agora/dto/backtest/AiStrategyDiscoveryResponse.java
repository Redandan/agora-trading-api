package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "AI 策略自動探勘結果")
public class AiStrategyDiscoveryResponse {

    @Schema(description = "本次探勘批次 ID", example = "20260320-143021")
    private String discoveryBatch;

    @Schema(description = "交易對", example = "BTCUSDT")
    private String symbol;

    @Schema(description = "K 線週期", example = "1h")
    private String intervalCode;

    @Schema(description = "回測開始時間")
    private LocalDateTime startTime;

    @Schema(description = "回測結束時間")
    private LocalDateTime endTime;

    @Schema(description = "所有候選策略的回測結果（由評分高到低排序）")
    private List<CandidateResult> candidates;

    @Schema(description = "評分最高的候選策略（若所有候選均無效交易則為 null）", nullable = true)
    private CandidateResult bestStrategy;

    @Schema(description = "實際執行的候選總數", example = "3")
    private int totalCandidates;

    @Schema(description = "評分 > 0 的有效候選數", example = "2")
    private int validCount;

    @Schema(description = "建立或回測失敗的候選數", example = "1")
    private int failedCount;

    @Data
    @Schema(description = "單一候選策略的回測評分結果")
    public static class CandidateResult {

        @Schema(description = "策略 ID（DB 中已儲存）", example = "42")
        private Long strategyId;

        @Schema(description = "策略名稱", example = "AI-20260320-143021-1")
        private String strategyName;

        @Schema(description = "總報酬率", example = "0.152300")
        private BigDecimal totalReturn;

        @Schema(description = "最大回撤", example = "0.045000")
        private BigDecimal maxDrawdown;

        @Schema(description = "勝率", example = "0.630000")
        private BigDecimal winRate;

        @Schema(description = "夏普比率", example = "0.452000", nullable = true)
        private BigDecimal sharpeRatio;

        @Schema(description = "交易筆數", example = "18")
        private Integer tradeCount;

        @Schema(description = "綜合評分（越高越好）", example = "0.096031")
        private double score;

        @Schema(description = "AI 建議的策略參數（JSON 物件）", nullable = true)
        private Object config;

        @Schema(description = "AI 建議說明", nullable = true)
        private String aiRationale;

        @Schema(description = "Walk-Forward 30 天驗證結果（通過品質門檻後自動執行）", nullable = true)
        private String walkForwardNote;

        @Schema(description = "失敗原因（成功時為 null）", nullable = true)
        private String errorMessage;
    }
}
