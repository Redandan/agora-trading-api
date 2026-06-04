package com.agora.dto.meta;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Meta-Control attribution 統計摘要(供 getAttributionSummary / getSessionBrief 使用)。
 *
 * <p>只包含 SUCCESS 狀態的 attribution 列。INSUFFICIENT_DATA / SCOPE_TOO_BROAD /
 * BACKTEST_FAILED 不會進入聚合。
 */
@Data
public class AttributionSummary {

    /** 回溯天數(請求參數) */
    private int days;

    /** SUCCESS 的 attribution 總筆數 */
    private int totalOverrides;

    /** alpha > 0 的筆數(override 加分) */
    private int positiveCount;

    /** alpha < 0 的筆數(override 扣分) */
    private int negativeCount;

    /** alpha == 0 的筆數(通常 counterfactual 也無交易) */
    private int neutralCount;

    /** 全部 alpha 加總 */
    private BigDecimal totalAlpha = BigDecimal.ZERO;

    /** 依累積 alpha 降冪排序 */
    private List<StrategyBreakdown> perStrategy = new ArrayList<>();

    @Data
    public static class StrategyBreakdown {
        private Long strategyId;
        private String strategyName;
        private int overrideCount;
        private BigDecimal cumulativeAlpha = BigDecimal.ZERO;
        /** 單筆最大正向 alpha(若都沒正向則 0) */
        private BigDecimal maxPositive = BigDecimal.ZERO;
        /** 單筆最大負向 alpha(若都沒負向則 0;存為負值) */
        private BigDecimal maxNegative = BigDecimal.ZERO;
    }
}
