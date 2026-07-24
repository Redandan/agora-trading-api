package com.agora.service.indicator;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CMI Framework 複合指標 Interface。
 *
 * <p>實作此 interface 的 @Component 會被 Spring 自動發現。各實作的可執行入口
 * 需要由目前 runtime dependency closure 獨立確認。
 *
 * <h3>實作規範（上線前強制）</h3>
 * <ul>
 *   <li>子維度：2-5 個，weight 之和 = 1.0</li>
 *   <li>閾值：Alert < Warning < Critical（建議 30/40/75）</li>
 *   <li>子維度 mhiKey 必須唯一，格式：{name}_{dimension}</li>
 *   <li>上線前完成 30+ 天回測，Precision > 50% 或說明原因</li>
 *   <li>寫入 KB + docs/ 設計文檔</li>
 * </ul>
 */
public interface CompositeIndicator {

    // ── 元數據 ────────────────────────────────────────────────────────────────

    /** mih indicator 主 key，例如 "sqi"、"short_build_index" */
    String getName();

    /** 人類可讀名稱，例如 "SQI 擠倉指數" */
    String getDisplayName();

    /** 交易對，例如 "BTCUSDT" */
    String getSymbol();

    /** 子維度聲明（決定哪些欄位寫入 mih，以及權重） */
    List<SubDimension> getDimensions();

    // ── 計算 ──────────────────────────────────────────────────────────────────

    /** 計算當前複合指標值（每分鐘由 Scheduler 呼叫）。 */
    CompositeResult calculate(LocalDateTime now);

    // ── 分級閾值 ──────────────────────────────────────────────────────────────

    /** 關注閾值（以上 LOG only，不發 TG），預設 30。 */
    default int getAlertThreshold() { return 30; }

    /** 警告閾值（以上發 TG WARN），預設 40。 */
    default int getWarningThreshold() { return 40; }

    /** 緊急閾值（以上發 TG CRITICAL，豁免所有過濾），預設 75。 */
    default int getCriticalThreshold() { return 75; }

    /** 根據分數返回分級。 */
    default IndicatorLevel getLevel(int score) {
        if (score >= getCriticalThreshold()) return IndicatorLevel.CRITICAL;
        if (score >= getWarningThreshold())  return IndicatorLevel.WARNING;
        if (score >= getAlertThreshold())    return IndicatorLevel.ALERT;
        return IndicatorLevel.NORMAL;
    }

    // ── 告警設定 ──────────────────────────────────────────────────────────────

    /**
     * #404 — TG 告警冷卻分鐘數（已被 HysteresisAlertGuard 取代）。
     *
     * <p><b>Deprecated</b>: 自 #404 起，alert 抑制改由 hysteresis state machine
     * 驅動，使用 {@link #getElevatedExitScore()} + {@link #getReminderHours()}
     * 配置。本欄位保留只為向後相容，scheduler 已不讀。
     */
    @Deprecated
    default int getCooldownMinutes() { return 60; }

    /**
     * #404 — Hysteresis 退出 elevated state 的下緣 score。
     *
     * <p>進入 elevated 用 {@link #getWarningThreshold()}（從 NORMAL 跨上去）。
     * 退出 elevated 必須跌到 {@code getElevatedExitScore()} 以下，避免
     * "warningThreshold ± 1" 邊界震盪。預設等於 {@link #getAlertThreshold()}
     * （30 分），即從 WARNING 跌出整個 ALERT 區段才回 NORMAL。
     */
    default int getElevatedExitScore() { return getAlertThreshold(); }

    /**
     * #404 — Elevated state 持續期間的 reminder 間隔（小時）。
     *
     * <p>進入 elevated 後，若 score 持續處於警戒區，每 N 小時發一次提醒
     * 「✕✕ 持續警戒 12h」訊息。預設 12 小時。
     */
    default long getReminderHours() { return 12L; }

    /** WARNING 層是否需要連續信號（前 2h 曾有 score >= alertThreshold），預設 true。 */
    default boolean isSustainedRequired() { return true; }

    /** WARNING 層是否需要價格方向過濾（1h 價格不跌 > 0.3%），預設 true。 */
    default boolean isDirectionalFilterEnabled() { return true; }

    /** 格式化 TG 告警訊息（WARNING/CRITICAL 層使用）。 */
    String formatAlertMessage(CompositeResult result);

    // ── Backfill 設定 ─────────────────────────────────────────────────────────

    /** Backfill 天數，預設 58（對應 Coinalyze 數據範圍）。 */
    default int getBackfillDays() { return 58; }

    /**
     * 執行一個歷史時間點的計算（Backfill 用）。
     * 預設直接呼叫 calculate(at)，若歷史計算需要不同邏輯可覆寫。
     */
    default CompositeResult calculateHistorical(LocalDateTime at) {
        return calculate(at);
    }
}
