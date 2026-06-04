package com.agora.dto.backtest;

import com.agora.service.backtest.DiagnosticCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

@Data
@Schema(description = "SOP_MTF_ADX 策略參數配置")
public class SopMtfAdxConfig {

    @Schema(description = "是否啟用多時框過濾（1D 趨勢 + MTF K 線）", example = "true")
    private Boolean enableMtf;

    @Schema(description = "1H 打分訊號最低門檻（A/C/D/E 共 4 項中至少需達到幾項；Signal B 已升級為必要前提條件）", example = "3", minimum = "1", maximum = "4")
    @Min(value = 1, message = "minSignals 最小為 1")
    @Max(value = 4, message = "minSignals 最大為 4")
    private Integer minSignals;

    @Schema(description = "ADX 進場強度門檻，低於此值不進場", example = "25.0", minimum = "0")
    @Positive(message = "adxEntryThreshold 必須大於 0")
    private Double adxEntryThreshold;

    @Schema(description = "價格與 EMA20 最大距離百分比（超過不進場）", example = "0.015", minimum = "0", maximum = "1")
    @DecimalMin(value = "0.0", inclusive = false, message = "maxDistanceFromEma 必須大於 0")
    @DecimalMax(value = "1.0", message = "maxDistanceFromEma 不可超過 1.0")
    private Double maxDistanceFromEma;

    @Schema(description = "固定止損百分比", example = "0.012", minimum = "0", maximum = "1")
    @DecimalMin(value = "0.0", inclusive = false, message = "fixedStopLossPct 必須大於 0")
    @DecimalMax(value = "1.0", message = "fixedStopLossPct 不可超過 1.0")
    private Double fixedStopLossPct;

    @Schema(description = "固定止盈百分比", example = "0.03", minimum = "0", maximum = "1")
    @DecimalMin(value = "0.0", inclusive = false, message = "fixedTakeProfitPct 必須大於 0")
    @DecimalMax(value = "1.0", message = "fixedTakeProfitPct 不可超過 1.0")
    private Double fixedTakeProfitPct;

    @Schema(description = "最大持倉小時數（0 表示不限制）", example = "24", minimum = "0")
    @PositiveOrZero(message = "maxHoldingHours 不可為負數")
    private Integer maxHoldingHours;

    @Schema(description = "TP1 觸發後止損移至成本價", example = "true")
    private Boolean moveSlToBreakeven;

    @Schema(description = "RSI 回調門檻，低於此值進入 PULLBACK 狀態", example = "40.0", minimum = "0", maximum = "100")
    @DecimalMin(value = "0.0", message = "rsiPullbackThreshold 不可小於 0")
    @DecimalMax(value = "100.0", message = "rsiPullbackThreshold 不可超過 100")
    private Double rsiPullbackThreshold;

    @Schema(description = "RSI Rebound 確認門檻，RSI 由 pullback 回升超過此值才視為 REBOUND_READY 成立", example = "50.0", minimum = "0", maximum = "100")
    @DecimalMin(value = "0.0", message = "rsiReboundConfirm 不可小於 0")
    @DecimalMax(value = "100.0", message = "rsiReboundConfirm 不可超過 100")
    private Double rsiReboundConfirm;

    @Schema(description = "是否要求價格突破前一根 K 線高點才允許進場（ENTRY_TRIGGER 動能確認）", example = "true")
    private Boolean requireCandleBreak;

    @Schema(description = "RSI Rebound 最小上升幅度，防止 V 型假反彈（rsiNow - rsiPrev >= minRsiDelta）", example = "3.0", minimum = "0")
    @DecimalMin(value = "0.0", inclusive = true, message = "minRsiDelta 不可為負數")
    private Double minRsiDelta;

    @Schema(description = "Pullback→Rebound 狀態機的最大回溯 K 線數，超過此範圍找不到序列則不進場", example = "10", minimum = "1")
    @Min(value = 1, message = "reboundLookbackBars 最小為 1")
    private Integer reboundLookbackBars;

    @Schema(description = "最小風險報酬比（reward / risk）", example = "2.0", minimum = "0")
    @Positive(message = "minRR 必須大於 0")
    private Double minRR;

    @Schema(description = "關鍵位計算的回望 K 線數", example = "20", minimum = "5")
    @Min(value = 5, message = "keyLevelLookbackBars 最小為 5")
    private Integer keyLevelLookbackBars;

    @Schema(description = "日線趨勢判斷的 MA 週期", example = "50", minimum = "20")
    @Min(value = 20, message = "dailyMaPeriod 最小為 20")
    private Integer dailyMaPeriod;

    @Schema(description = "RSI 做空門檻，高於此值視為空頭回調訊號成立", example = "60.0", minimum = "0", maximum = "100")
    @DecimalMin(value = "0.0", message = "rsiSellThreshold 不可小於 0")
    @DecimalMax(value = "100.0", message = "rsiSellThreshold 不可超過 100")
    private Double rsiSellThreshold;

    @Schema(description = "是否允許做空（借貨高賣低買還）", example = "false")
    private Boolean allowShort;

    @Schema(description = "純空頭模式：只執行空頭訊號，完全跳過多頭進場邏輯（自動啟用 allowShort）", example = "false")
    private Boolean shortOnly;

    @Schema(
            description = "借貨日利率（參考幣安方式，每小時扣 dailyBorrowingRate÷24，最少計 1 小時，僅 allowShort=true 時有效）",
            example = "0.0003",
            nullable = true
    )
    @DecimalMin(value = "0.0", inclusive = true, message = "dailyBorrowingRate 不可為負數")
    @DecimalMax(value = "1.0", message = "dailyBorrowingRate 不可超過 1.0")
    private Double dailyBorrowingRate;

    @Schema(description = "是否啟用 ATR 追蹤止損（替代固定止損追隨價格移動）", example = "false")
    private Boolean atrTrailingStopEnabled;

    @Schema(description = "ATR 追蹤止損的 ATR 週期，僅 atrTrailingStopEnabled=true 時有效", example = "14", minimum = "1", nullable = true)
    @Min(value = 1, message = "atrPeriod 最小為 1")
    private Integer atrPeriod;

    @Schema(description = "ATR 追蹤止損的 ATR 倍數，僅 atrTrailingStopEnabled=true 時有效", example = "2.0", minimum = "0", nullable = true)
    @Positive(message = "atrMultiplier 必須大於 0")
    private Double atrMultiplier;

    @Schema(
            description = "要求的日線趨勢方向（BULLISH/BEARISH/ANY）。不符合時策略靜默跳過實盤評估，不影響回測。",
            example = "BEARISH",
            nullable = true,
            allowableValues = {"BULLISH", "BEARISH", "ANY"}
    )
    private String requiredDailyTrend;

    @Schema(description = "ATR 動態初始止損倍數（SL = ATR% × multiplier，優先於 fixedStopLossPct）", example = "2.5", nullable = true)
    @Positive(message = "atrSlMultiplier 必須大於 0")
    private Double atrSlMultiplier;

    @Schema(description = "ATR 動態初始止盈倍數（TP = ATR% × multiplier，優先於 fixedTakeProfitPct）", example = "7.0", nullable = true)
    @Positive(message = "atrTpMultiplier 必須大於 0")
    private Double atrTpMultiplier;

    @Schema(description = "用更大時框的 ATR 計算 SL（防止 stop hunt）。例如 '4h' 在 1h 策略上使用 4h ATR 計算 SL，更寬的止損可避免整數位被掃。TP 仍用原時框 ATR。",
            example = "4h", nullable = true)
    private String higherTfForSl;

    @Schema(description = "防整數位止損獵殺：在 SL 加入 0.03%–0.08% 隨機 offset，使 SL 落在非整數位置（預設 true）", example = "true", nullable = true)
    private Boolean antiStopHuntOffset;

    @Schema(description = "#221 per-strategy RegimeFilter bypass: only crash-bottom strategies " +
            "(e.g. SCORE_BUY_V2) should set true. Trend strategies keep false — RSI<20 in bear market " +
            "is a short signal, not a long entry.", example = "false", nullable = true)
    private Boolean allowRsiBypassRegime;

    @Schema(description = "#249 ML gate threshold override for SIDEWAYS/VOLATILE regime. " +
            "Default 0 = auto-compute as buyThreshold*0.85. " +
            "Set explicitly to relax or tighten ML gate in sideways markets.", example = "0.28", nullable = true)
    private Double mlGateSidewaysThreshold;

    @Schema(
            description = "診斷碼開關設定（false 表示停用該診斷碼，預設全部啟用，null 表示全部啟用）",
            example = "{\"ADX_BELOW_THRESHOLD\": false, \"LONG_SIGNALS_NOT_ENOUGH\": false}",
            nullable = true
    )
    private Map<DiagnosticCode, Boolean> diagnostics;

    // ---- SCORE_BUY 策略專屬參數 ----

    @Schema(description = "[SCORE_BUY] 回看年高的 K 線數（1d 用 252，1h 用 8760）", example = "252", minimum = "1")
    @Min(value = 1, message = "yearLookbackBars 最小為 1")
    private Integer yearLookbackBars;

    @Schema(description = "[SCORE_BUY] 中期相對低點回看 K 線數（1d 用 63，1h 用 504）", example = "63", minimum = "1")
    @Min(value = 1, message = "medLookbackBars 最小為 1")
    private Integer medLookbackBars;

    @Schema(description = "[SCORE_BUY] 短期相對低點回看 K 線數（1d 用 10，1h 用 48）", example = "10", minimum = "1")
    @Min(value = 1, message = "shortLookbackBars 最小為 1")
    private Integer shortLookbackBars;

    @Schema(description = "[SCORE_BUY] RSI 超賣門檻，低於此值視為超賣", example = "40.0", minimum = "0", maximum = "100")
    @DecimalMin(value = "0.0", message = "rsiOversold 不可小於 0")
    @DecimalMax(value = "100.0", message = "rsiOversold 不可超過 100")
    private Double rsiOversold;

    @Schema(description = "[SCORE_BUY] RSI 超漲門檻，高於此值觸發賣出訊號", example = "70.0", minimum = "0", maximum = "100")
    @DecimalMin(value = "0.0", message = "rsiOverbought 不可小於 0")
    @DecimalMax(value = "100.0", message = "rsiOverbought 不可超過 100")
    private Double rsiOverbought;

    @Schema(description = "[SCORE_BUY] nnOutput 買入門檻（sigmoid 輸出）", example = "0.8", minimum = "0", maximum = "1")
    @DecimalMin(value = "0.0", inclusive = false, message = "buyThreshold 必須大於 0")
    @DecimalMax(value = "1.0", message = "buyThreshold 不可超過 1.0")
    private Double buyThreshold;

    @Schema(description = "[SCORE_BUY] 量能放大倍數門檻", example = "1.5", minimum = "0")
    @DecimalMin(value = "0.0", inclusive = false, message = "volumeBreakoutMultiplier 必須大於 0")
    private Double volumeBreakoutMultiplier;

    @Schema(description = "[SCORE_BUY] sigmoid 輸入縮放係數（score × scoreScale - scoreShift）", example = "8.0")
    private Double scoreScale;

    @Schema(description = "[SCORE_BUY] sigmoid 輸入平移量（score × scoreScale - scoreShift）", example = "4.0")
    private Double scoreShift;

    @Schema(description = "[SCORE_BUY] 記錄診斷 log 的 score 最低門檻（低於此值不記錄）", example = "0.4", minimum = "0")
    @DecimalMin(value = "0.0", inclusive = true, message = "diagScoreFloor 不可為負數")
    private Double diagScoreFloor;
}
