package com.agora.service.indicator;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 複合指標計算結果。
 *
 * @param score      0-100 綜合分
 * @param level      分級（NORMAL/ALERT/WARNING/CRITICAL）
 * @param dimValues  各子維度分數 map，key = SubDimension.mhiKey
 * @param context    診斷用額外資訊（如爆倉 USD、p95 值等），可為 empty
 * @param computedAt 計算時間（UTC）
 * @param symbol     交易對
 */
public record CompositeResult(
        int score,
        IndicatorLevel level,
        Map<String, Double> dimValues,
        Map<String, Object> context,
        LocalDateTime computedAt,
        String symbol
) {
    /** 格式化子維度貢獻，供 TG 訊息使用。 */
    public String formatDecomposed(java.util.List<SubDimension> dimensions) {
        StringBuilder sb = new StringBuilder();
        for (SubDimension dim : dimensions) {
            double val = dimValues.getOrDefault(dim.mhiKey(), 0.0);
            sb.append(String.format("%s：%.0f/%.0f  ",
                    dim.label(), val * dim.weight(), dim.weight() * 100));
        }
        return sb.toString().trim();
    }
}
