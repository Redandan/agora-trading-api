package com.agora.service.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * #450 — 唯讀 position view 給 {@link Strategy#adjustExit} 用。
 *
 * <p>把內部的 {@code BacktestEngine.Position} / live {@code BtLiveSignal} 攤平成
 * 通用 record,讓 strategy 不需要 import 內部 class 即可讀 hold 中 position 狀態。
 *
 * <p>{@code entryIndicatorSnapshot} 帶開倉時的各項指標值(adx/rsi/atr/MIH 等),
 * 讓 adjustExit 可比對「現在 vs 進場時」(例如 ETF Pressure 是否衰減)。
 */
public record OpenPositionView(
        String side,                                // "LONG" / "SHORT"
        BigDecimal entryPrice,
        LocalDateTime entryTime,
        BigDecimal currentTp,
        BigDecimal currentSl,
        BigDecimal entryNotional,                   // entry 時 USDT 額(含 fee 之前)
        BigDecimal currentPrice,                    // 最新 close
        BigDecimal unrealizedPnl,                   // (current-entry) × qty signed by side
        BigDecimal unrealizedPnlPct,                // ratio,與 side 一致(LONG 漲 = 正)
        long ageHours,                              // 持倉小時數
        Map<String, Double> entryIndicatorSnapshot  // 進場時各指標值快照(可能為 empty)
) {

    /**
     * 持倉天數(整數,捨棄小數)。Convenience 給 time-decay rule 用。
     */
    public long ageDays() {
        return ageHours / 24;
    }

    /**
     * Position 達 TP 目標的百分比進度(0~1+)。
     * 例:entry=100, TP=+5%, current=+3% → progress 0.6
     *
     * @param targetTpPct 例如 0.05 代表 +5% TP target(不含 sign)
     * @return progress ratio, 0 if targetTpPct invalid
     */
    public double tpProgressPct(double targetTpPct) {
        if (targetTpPct <= 0 || unrealizedPnlPct == null) return 0.0;
        return unrealizedPnlPct.divide(BigDecimal.valueOf(targetTpPct), 4, RoundingMode.HALF_UP).doubleValue();
    }

    /** True 若浮盈 > 0(LONG 漲 / SHORT 跌)。 */
    public boolean inProfit() {
        return unrealizedPnlPct != null && unrealizedPnlPct.signum() > 0;
    }

    /** True 若浮虧。 */
    public boolean inLoss() {
        return unrealizedPnlPct != null && unrealizedPnlPct.signum() < 0;
    }

    /** Convenience indicator getter(handles missing key)。 */
    public Double entryIndicator(String name) {
        return entryIndicatorSnapshot == null ? null : entryIndicatorSnapshot.get(name);
    }
}
