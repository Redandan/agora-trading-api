package com.agora.service.backtest;

import com.agora.service.BtStrategyService;
import java.util.ArrayList;
import java.util.List;

public class BacktestQualityValidator {

    /**
     * #306 按策略類型決定最低交易筆數門檻：
     * - CMI_MIH_THRESHOLD（低頻精準型）：3 筆（月均 < 1，設 5 不合理）
     * - 其他：5 筆（預設）
     */
    public static int minTradeCount(String strategyType) {
        if ("CMI_MIH_THRESHOLD".equalsIgnoreCase(strategyType)) return 3;
        return BtStrategyService.QUALITY_MIN_TRADE_COUNT;
    }

    public static boolean passes(int tc, double ret, double dd) {
        return passes(tc, ret, dd, null);
    }

    public static boolean passes(int tc, double ret, double dd, String strategyType) {
        return tc >= minTradeCount(strategyType)
                && ret > 0
                && dd <= BtStrategyService.QUALITY_MAX_DRAWDOWN;
    }

    public static List<String> failureReasons(int tc, double ret, double dd) {
        return failureReasons(tc, ret, dd, null);
    }

    public static List<String> failureReasons(int tc, double ret, double dd, String strategyType) {
        int minTc = minTradeCount(strategyType);
        List<String> failures = new ArrayList<>();
        if (tc < minTc)
            failures.add(String.format("  • 交易筆數: %d（需 ≥ %d）", tc, minTc));
        if (ret <= 0)
            failures.add(String.format("  • 總報酬: %+.2f%%（需 > 0%%）", ret * 100));
        if (dd > BtStrategyService.QUALITY_MAX_DRAWDOWN)
            failures.add(String.format("  • 最大回撤: %.1f%%（需 ≤ %.0f%%）",
                    dd * 100, BtStrategyService.QUALITY_MAX_DRAWDOWN * 100));
        return failures;
    }

    public static String failedThresholdLine() {
        return "\n❌ 未達啟用品質門檻（tradeCount≥" + BtStrategyService.QUALITY_MIN_TRADE_COUNT
                + ", totalReturn>0%, maxDrawdown≤" + (int)(BtStrategyService.QUALITY_MAX_DRAWDOWN * 100) + "%）\n";
    }

    public static String thresholdsDescription() {
        return "tradeCount≥" + BtStrategyService.QUALITY_MIN_TRADE_COUNT
                + " / totalReturn>0% / maxDrawdown≤" + (int)(BtStrategyService.QUALITY_MAX_DRAWDOWN * 100) + "%";
    }
}
