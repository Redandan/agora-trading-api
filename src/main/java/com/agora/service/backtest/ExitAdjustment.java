package com.agora.service.backtest;

import java.math.BigDecimal;

/**
 * #450 — Strategy 對 hold 中 position 的 exit 調整指令。
 *
 * <p>由 {@link Strategy#adjustExit} return,讓 strategy 根據 hold 中的市場狀態
 * 動態調整 TP/SL,而不只用 entry 時 static fixedTakeProfitPct/fixedStopLossPct。
 *
 * <h3>常見 use case</h3>
 * <ul>
 *   <li>Alpha source weakened: ETF Pressure / SQI 跌破閾值 → tightenTp lock 浮盈</li>
 *   <li>Time decay: 持倉超過 backtest avg hold → tighten / forceClose</li>
 *   <li>Trail SL: profit > 50% of TP → trail SL 上移到 BE</li>
 *   <li>Trend reversal: EMA bearish cross → forceClose</li>
 *   <li>Anti-thesis hit: regime flip → forceClose</li>
 * </ul>
 *
 * <h3>合約</h3>
 * <ul>
 *   <li>{@code newTp == null && newSl == null && !forceClose} 視為 no-op(等同 Optional.empty)</li>
 *   <li>{@code forceClose} 優先於 newTp/newSl(立即 market exit)</li>
 *   <li>{@code reason} 寫入 audit log + TG;{@code tag} 是分類 enum 用 categorize</li>
 * </ul>
 */
public record ExitAdjustment(
        BigDecimal newTp,        // null = 不動 TP
        BigDecimal newSl,        // null = 不動 SL
        String reason,            // human-readable,寫 audit/TG
        boolean forceClose,       // true = 立即 market exit at current price
        String tag                // category: ALPHA_WEAKENED / TIME_DECAY / TRAIL_SL / TREND_REVERSAL / ...
) {

    /**
     * 立即 market exit。Strategy 偵測到 anti-thesis hit / alpha source gone 時用。
     */
    public static ExitAdjustment forceClose(String reason, String tag) {
        return new ExitAdjustment(null, null, reason, true, tag);
    }

    /**
     * 只調 TP(SL 不動)。常見:lock 浮盈 → TP 拉到 current+0.5%。
     */
    public static ExitAdjustment tightenTp(BigDecimal newTp, String reason, String tag) {
        return new ExitAdjustment(newTp, null, reason, false, tag);
    }

    /**
     * 只調 SL(TP 不動)。常見:trail SL 上移鎖利。
     */
    public static ExitAdjustment trailingSl(BigDecimal newSl, String reason, String tag) {
        return new ExitAdjustment(null, newSl, reason, false, tag);
    }

    /**
     * 同時調 TP + SL。常見:time decay 收斂兩端。
     */
    public static ExitAdjustment tighten(BigDecimal newTp, BigDecimal newSl, String reason, String tag) {
        return new ExitAdjustment(newTp, newSl, reason, false, tag);
    }

    /** True 若此 adjustment 沒有實際效果(用於 caller 略過)。 */
    public boolean isNoop() {
        return !forceClose && newTp == null && newSl == null;
    }
}
