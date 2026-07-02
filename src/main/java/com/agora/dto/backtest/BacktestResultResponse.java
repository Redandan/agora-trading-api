package com.agora.dto.backtest;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "回測結果回應")
public class BacktestResultResponse {

    @Schema(description = "回測結果 ID", example = "1001")
    private Long id;

    @Schema(description = "策略 ID", example = "1")
    private Long strategyId;

    @Schema(description = "策略名稱", example = "SOP BTCUSDT 1H")
    private String strategyName;

    @Schema(description = "交易對", example = "BTCUSDT")
    private String symbol;

    @Schema(description = "回測週期", example = "1h")
    private String intervalCode;

    @Schema(description = "回測開始時間（ISO-8601）", example = "2025-01-01T00:00:00")
    private LocalDateTime startTime;

    @Schema(description = "回測結束時間（ISO-8601）", example = "2025-03-01T00:00:00")
    private LocalDateTime endTime;

    @Schema(description = "初始資金", example = "10000.00000000")
    private BigDecimal initialCapital;

    @Schema(description = "最終資金", example = "11234.56780000")
    private BigDecimal finalCapital;

    @Schema(description = "總報酬率", example = "0.123456")
    private BigDecimal totalReturn;

    @Schema(description = "最大回撤", example = "0.089000")
    private BigDecimal maxDrawdown;

    @Schema(description = "勝率", example = "0.580000")
    private BigDecimal winRate;

    @Schema(description = "簡化夏普比率（mean(returnPct) / sampleStdDev(returnPct)）", example = "0.452000", nullable = true)
    private BigDecimal sharpeRatio;

    @Schema(description = "交易筆數", example = "42")
    private Integer tradeCount;

    @Schema(description = "手續費率", example = "0.001000")
    private BigDecimal feeRate;

    @Schema(description = "結果建立時間（ISO-8601）", example = "2026-03-18T13:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "策略參數快照 JSON", nullable = true)
    private String configSnapshotJson;

    @Schema(description = "多頭交易筆數", example = "28", nullable = true)
    private Integer longTradeCount;

    @Schema(description = "空頭交易筆數", example = "14", nullable = true)
    private Integer shortTradeCount;

    @Schema(description = "applyFilters=true 時被歷史過濾器略過的進場次數", example = "3", nullable = true)
    private Integer filteredEntryCount;

    @Schema(description = "多頭勝率", example = "0.607143", nullable = true)
    private BigDecimal longWinRate;

    @Schema(description = "空頭勝率", example = "0.500000", nullable = true)
    private BigDecimal shortWinRate;

    @Schema(description = "行情開盤價（回測期首根 K）", example = "42000.00000000", nullable = true)
    private BigDecimal marketOpenPrice;

    @Schema(description = "行情收盤價（回測期末根 K）", example = "48000.00000000", nullable = true)
    private BigDecimal marketClosePrice;

    @Schema(description = "行情最高價（回測期間）", example = "52000.00000000", nullable = true)
    private BigDecimal marketHighPrice;

    @Schema(description = "行情最低價（回測期間）", example = "38000.00000000", nullable = true)
    private BigDecimal marketLowPrice;

    @Schema(description = "行情波動幅度 % = (high - low) / low", example = "0.368421", nullable = true)
    private BigDecimal marketVolatilityPct;

    @Schema(description = "行情漲跌幅 % = (close - open) / open", example = "0.142857", nullable = true)
    private BigDecimal marketPriceChangePct;

    @Schema(description = "行情走勢分類", example = "BULLISH", allowableValues = {"BULLISH", "BEARISH", "SIDEWAYS"}, nullable = true)
    private String marketTrend;

    @Schema(description = "買持報酬率（Buy & Hold），與 totalReturn 對比使用", example = "0.142857", nullable = true)
    private BigDecimal benchmarkReturn;

    @Schema(description = "交易明細", nullable = true)
    private List<TradeRecordDto> trades;

        @Schema(
            description = "未觸發交易時的診斷日誌（結構化）",
            example = "[{\"code\":\"ADX_BELOW_THRESHOLD\",\"count\":12,\"firstOccurredAt\":\"2025-01-03T08:00:00\",\"lastOccurredAt\":\"2025-01-04T11:00:00\",\"sampleDetail\":\"adx=18.2, threshold=20.0\"}]",
            nullable = true
        )
        private List<DiagnosticLogDto> diagnosticLogs;

    @Data
    @Schema(description = "單筆交易紀錄")
    public static class TradeRecordDto {
        @Schema(description = "進場時間（ISO-8601）", example = "2025-01-03T08:00:00")
        private LocalDateTime entryTime;

        @Schema(description = "出場時間（ISO-8601）", example = "2025-01-03T12:00:00")
        private LocalDateTime exitTime;

        @Schema(description = "進場價格", example = "43000.12340000")
        private BigDecimal entryPrice;

        @Schema(description = "出場價格", example = "43888.88880000")
        private BigDecimal exitPrice;

        @Schema(description = "交易數量", example = "0.12345678")
        private BigDecimal quantity;

        @Schema(description = "毛損益", example = "120.55000000")
        private BigDecimal grossPnl;

        @Schema(description = "淨損益", example = "118.33000000")
        private BigDecimal netPnl;

        @Schema(description = "報酬率", example = "0.020000")
        private BigDecimal returnPct;

        @Schema(description = "出場原因", example = "TP2", nullable = true)
        private String exitReason;

        @Schema(description = "交易方向", example = "LONG", allowableValues = {"LONG", "SHORT"}, nullable = true)
        private String side;

        @Schema(description = "借貨利息（僅做空時有值，幣安小時計息模型）", example = "1.23450000", nullable = true)
        private BigDecimal borrowingCost;

        @Schema(description = "進場原因／TradingView 訂單類型", example = "TRADINGVIEW_RELATIVE_LOW", nullable = true)
        private String entryReason;

        @Schema(description = "進場標籤", example = "相对低点买入", nullable = true)
        private String entryLabel;

        @Schema(description = "TradingView 原始 qty 參數（僅作訊號對照，不等於本地實際下單數量）", example = "1000.0", nullable = true)
        private BigDecimal entryRequestedQuantity;

        @Schema(description = "同一根 K 觸發的 TradingView order intent 數量", example = "2", nullable = true)
        private Integer entryOrderCount;

        @Schema(description = "同一根 K 觸發的 TradingView order intent 清單", example = "TRADINGVIEW_AI_BUY_SIGNAL,TRADINGVIEW_RELATIVE_LOW", nullable = true)
        private String entryOrderReasons;
    }

    @Data
    @Schema(description = "未觸發交易的診斷項目")
    public static class DiagnosticLogDto {
        @Schema(description = "診斷代碼", example = "ADX_BELOW_THRESHOLD")
        private String code;

        @Schema(description = "命中次數", example = "12")
        private Integer count;

        @Schema(description = "首次發生時間（ISO-8601）", example = "2025-01-03T08:00:00", nullable = true)
        private LocalDateTime firstOccurredAt;

        @Schema(description = "最後發生時間（ISO-8601）", example = "2025-01-04T11:00:00", nullable = true)
        private LocalDateTime lastOccurredAt;

        @Schema(description = "診斷樣本內容", example = "adx=18.2, threshold=20.0", nullable = true)
        private String sampleDetail;
    }
}
