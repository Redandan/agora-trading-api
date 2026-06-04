package com.agora.service.meta;

import com.agora.model.MarketFlipEvent;
import com.agora.repository.trading.MarketFlipEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 資料品質異常偵測 — 在 {@link MarketIndicatorFlipDetector} 寫入 event 前跑,
 * 把「看起來像資料問題」的 flip 標為 anomalous,讓下游 AI 共識分析納入考量。
 *
 * <h3>動機</h3>
 * 2026-04-16 MarketFlip Phase 2A/2B 上線首日遇到 {@code whale_buy_ratio} 在 2 小時內
 * 從 22% → 42% → 76% 的劇烈震盪,對 AI (Groq + Gemini) 而言門檻跨越是事實,
 * 但可能只是 WhaleFlowService 聚合視窗噪音 / 偶發大單進入視窗。AI 無法辨識資料品質,
 * 需要一層 rule-based 前置偵測。
 *
 * <h3>三條規則 (任一觸發即 flag anomalous)</h3>
 * <ol>
 *   <li><b>單小時變化過大</b> — whale > 40pp, fg > 30 pts, funding > 0.05%, orderbook > 0.8</li>
 *   <li><b>震盪</b> — 同 (symbol, indicator) 最近 3 個 event 都在 3 小時內 (系統自己跟自己打)</li>
 *   <li><b>超出合理範圍</b> — whale ∉ [0, 1], fg ∉ [0, 100], |funding_rate| > 1.0</li>
 * </ol>
 *
 * <h3>失敗容忍</h3>
 * 本 monitor 出任何例外視為 <b>non-anomalous</b> (fail-open),絕不阻塞主 flip 偵測流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityMonitor {

    public static final String INDICATOR_FG      = "fear_greed";
    public static final String INDICATOR_WHALE   = "whale_buy_ratio";
    public static final String INDICATOR_FUNDING = "funding_rate";
    public static final String INDICATOR_OB      = "orderbook_imbalance";

    private final MarketFlipEventRepository eventRepo;
    private final com.agora.config.properties.DataQualityProperties props;

    /**
     * 檢查當前 flip 是否為可疑資料。不存取 monitor 是 fail-open(non-anomalous)。
     *
     * @param symbol    e.g. BTCUSDT
     * @param indicator fear_greed / whale_buy_ratio / funding_rate / orderbook_imbalance
     * @param prev      前一次觀測值
     * @param current   目前觀測值 (引發本次 flip 的值)
     * @return AnomalyResult;若 anomalous=true,reasons 列出所有觸發的規則 tag
     */
    public AnomalyResult check(String symbol, String indicator, double prev, double current) {
        if (!props.enabled()) return AnomalyResult.clean();

        List<String> reasons = new ArrayList<>();
        try {
            // Rule 1 — 單次變化過大 (indicator-specific)
            String rule1 = rule1LargeDelta(indicator, prev, current);
            if (rule1 != null) reasons.add(rule1);

            // Rule 2 — 連續震盪 (需查 DB,包在獨立 try 避免誤判為 fatal)
            try {
                String rule2 = rule2Oscillation(symbol, indicator);
                if (rule2 != null) reasons.add(rule2);
            } catch (Exception e) {
                log.debug("[DataQuality] {} {} oscillation check failed: {}", symbol, indicator, e.getMessage());
            }

            // Rule 3 — 值超出合理範圍 (防 upstream 資料壞掉)
            String rule3 = rule3OutOfRange(indicator, current);
            if (rule3 != null) reasons.add(rule3);

        } catch (Throwable t) {
            // fail-open: monitor 壞了不該阻塞主流程
            log.warn("[DataQuality] {} {} check failed (non-fatal, fail-open): {}",
                    symbol, indicator, t.getMessage());
            return AnomalyResult.clean();
        }

        if (reasons.isEmpty()) return AnomalyResult.clean();

        log.info("[DataQuality] {} {} flagged anomalous: {} (prev={}, current={})",
                symbol, indicator, reasons, prev, current);
        return new AnomalyResult(true, reasons);
    }

    private String rule1LargeDelta(String indicator, double prev, double current) {
        double delta = Math.abs(current - prev);
        return switch (indicator) {
            case INDICATOR_FG -> delta > props.fgDeltaThreshold()
                    ? String.format("fg_delta_%.1fpts_exceeds_%.1f", delta, props.fgDeltaThreshold())
                    : null;
            case INDICATOR_WHALE -> delta > props.whaleDeltaThreshold()
                    ? String.format("whale_delta_%.1fpp_exceeds_%.1fpp",
                            delta * 100, props.whaleDeltaThreshold() * 100)
                    : null;
            case INDICATOR_FUNDING -> delta > props.fundingDeltaThreshold()
                    ? String.format("funding_delta_%.4f_exceeds_%.4f", delta, props.fundingDeltaThreshold())
                    : null;
            case INDICATOR_OB -> delta > props.orderbookDeltaThreshold()
                    ? String.format("orderbook_delta_%.2f_exceeds_%.2f", delta, props.orderbookDeltaThreshold())
                    : null;
            default -> null;
        };
    }

    /**
     * 查同 (symbol, indicator) 最近 (threshold-1) 個 event (不含正要寫入的當前事件),
     * 若全部都在 window 內 → 連同當前事件共 threshold 個在 window 內 → 震盪。
     *
     * <p>範例 (threshold=3, window=3h):
     * <ul>
     *   <li>event #1 at T=0 — 0 prior events → 不 flag</li>
     *   <li>event #2 at T=1h — 1 prior event within 3h → 不 flag (need 2 prior)</li>
     *   <li>event #3 at T=2h — 2 prior events within 3h → flag (3 events in 3h)</li>
     * </ul>
     */
    private String rule2Oscillation(String symbol, String indicator) {
        int priorNeeded = props.oscillationCountThreshold() - 1;
        if (priorNeeded <= 0) return null;

        List<MarketFlipEvent> recent = eventRepo.findLatestBySymbolAndIndicator(
                symbol, indicator, PageRequest.of(0, priorNeeded));
        if (recent.size() < priorNeeded) return null;

        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(props.oscillationWindowHours());
        long withinWindow = recent.stream()
                .filter(e -> e.getDetectedAt() != null && e.getDetectedAt().isAfter(cutoff))
                .count();

        if (withinWindow >= priorNeeded) {
            return String.format("oscillation_%d_flips_in_%dh",
                    props.oscillationCountThreshold(), props.oscillationWindowHours());
        }
        return null;
    }

    private String rule3OutOfRange(String indicator, double current) {
        return switch (indicator) {
            case INDICATOR_FG -> (current < 0 || current > 100)
                    ? String.format("fg_out_of_range_%.1f_not_in_[0,100]", current) : null;
            case INDICATOR_WHALE -> (current < 0 || current > 1)
                    ? String.format("whale_out_of_range_%.3f_not_in_[0,1]", current) : null;
            case INDICATOR_FUNDING -> Math.abs(current) > 1.0
                    ? String.format("funding_out_of_range_|%.4f|_gt_1", current) : null;
            case INDICATOR_OB -> Math.abs(current) > 1.0
                    ? String.format("orderbook_out_of_range_|%.3f|_gt_1", current) : null;
            default -> null;
        };
    }

    /** Monitor 回傳值。 */
    public record AnomalyResult(boolean anomalous, List<String> reasons) {
        public static AnomalyResult clean() { return new AnomalyResult(false, List.of()); }
    }
}
