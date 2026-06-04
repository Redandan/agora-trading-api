package com.agora.service.meta;

import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.dto.meta.AttributionSummary;
import com.agora.enums.trading.AttributionStatusEnum;
import com.agora.enums.trading.OverrideTypeEnum;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MetaControlAttribution;
import com.agora.model.StrategyOverride;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MetaControlAttributionRepository;
import com.agora.service.BacktestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Meta-Control override 的 counterfactual attribution 服務。
 *
 * <h3>核心思路</h3>
 * <p>每當 Claude 透過 MCP 呼叫 {@code pauseStrategy} 暫停策略,系統會寫一筆
 * strategy_override 列並在 PAUSE 期間短路該策略評估。但「這次 pause 究竟是救了
 * profit 還是錯過了機會」一直沒人量化。本服務在 override 結束後,對相同 symbol、
 * interval、time window 跑一次 backtest(當作沒 pause 會怎樣),把結果與 PAUSE
 * 期間實際 P&L 相減 —— 這個 delta 就是 Claude 介入的 alpha 貢獻。
 *
 * <pre>
 *   alpha_contribution = actual_pnl - counterfactual_pnl
 *     正 → override 加分(避開虧損 / 保住 profit)
 *     負 → override 扣分(錯過 profit)
 *     零 → counterfactual 也沒交易,無事後判斷
 * </pre>
 *
 * <h3>冪等性</h3>
 * {@code meta_control_attribution.uk_attr_override(override_type, override_id)}
 * 保證同一 override 不重複計算,scheduler 重跑同一窗口安全。
 *
 * <h3>不使用 @Transactional on computePauseAttribution</h3>
 * 因為 BacktestService.runForExploration 本身 @Transactional,若外層包 tx,
 * backtest 拋 RuntimeException 會把外層 tx 標記 rollback,導致後續寫
 * BACKTEST_FAILED row 的 save() 失敗。改用 auto-commit per save。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaControlAttributionService {

    private final MetaControlAttributionRepository attributionRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final BacktestService backtestService;
    private final com.agora.config.properties.AttributionProperties props;

    /**
     * 計算單個 PAUSE override 的 attribution。冪等:已算過則直接 return 既有列。
     * 不 @Transactional —— 見 class javadoc。
     */
    public MetaControlAttribution computePauseAttribution(StrategyOverride ov) {
        // 1. 冪等 check
        Optional<MetaControlAttribution> existing = attributionRepository
                .findByOverrideTypeAndOverrideId(OverrideTypeEnum.STRATEGY_PAUSE, ov.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2. 只處理 PAUSE(TWEAK / 其他 action 不在本 service 責任範圍)
        if (!"PAUSE".equalsIgnoreCase(ov.getAction())) {
            throw new IllegalArgumentException(
                    "computePauseAttribution 只處理 action=PAUSE,但收到: " + ov.getAction());
        }

        // 3. 計算 window
        LocalDateTime start = ov.getCreatedAt();
        LocalDateTime end = ov.getRevokedAt() != null ? ov.getRevokedAt() : ov.getExpiresAt();

        // 4. 建基礎 entity
        MetaControlAttribution a = new MetaControlAttribution();
        a.setOverrideType(OverrideTypeEnum.STRATEGY_PAUSE);
        a.setOverrideId(ov.getId());
        a.setStrategyId(ov.getStrategyId());
        a.setSymbol(ov.getSymbol() != null ? ov.getSymbol() : "*");
        a.setIntervalCode(ov.getIntervalCode() != null ? ov.getIntervalCode() : "*");
        a.setWindowStart(start);
        a.setWindowEnd(end);
        a.setComputedAt(LocalDateTime.now(ZoneOffset.UTC));

        // 5. Scope 檢查:v1 不處理 symbol / interval 為 null(跨所有 symbol)的 override,
        //    會涉及跨幣種 backtest 成本過高,留待 Phase 2
        if (ov.getSymbol() == null || ov.getIntervalCode() == null) {
            a.setComputationStatus(AttributionStatusEnum.SCOPE_TOO_BROAD);
            a.setAlphaContribution(BigDecimal.ZERO);
            return attributionRepository.save(a);
        }

        // 6. Window 長度檢查:短於 1 根 K 線沒意義
        Duration minBar = intervalToDuration(ov.getIntervalCode());
        Duration windowLen = Duration.between(start, end);
        if (windowLen.compareTo(minBar) < 0) {
            a.setComputationStatus(AttributionStatusEnum.INSUFFICIENT_DATA);
            a.setAlphaContribution(BigDecimal.ZERO);
            return attributionRepository.save(a);
        }

        // 7. actual_pnl:PAUSE 期間實際發生的自動交易 P&L。
        //    PAUSE 通常會短路評估,所以此查詢通常空,但保留以利 Phase 2
        //    HINT override(不阻擋交易)也能用同套流程。
        List<BtLiveSignal> actualSignals = liveSignalRepository
                .findByStrategyIdAndSymbolAndAutoTradedIsTrueAndBarOpenTimeBetween(
                        ov.getStrategyId(), ov.getSymbol(), start, end);
        BigDecimal actualPnl = BigDecimal.ZERO;
        for (BtLiveSignal ls : actualSignals) {
            if (ls.getRealizedPnl() != null) {
                actualPnl = actualPnl.add(ls.getRealizedPnl());
            }
        }
        a.setActualPnl(actualPnl);
        a.setActualTradeCount(actualSignals.size());

        // 8. counterfactual_pnl:對相同 window 跑 backtest,假設 override 未發生
        try {
            BacktestRunRequest req = new BacktestRunRequest();
            req.setStrategyId(ov.getStrategyId());
            req.setSymbol(ov.getSymbol());
            req.setIntervalCode(ov.getIntervalCode());
            req.setStartTime(start);
            req.setEndTime(end);
            req.setInitialCapital(props.initialCapital());
            req.setFeeRate(props.feeRate());
            req.setApplyFilters(true);
            req.setSource(props.klineSource());
            req.setSkipPersist(true);  // 關鍵:不汙染 bt_backtest_result「最新」狀態

            BacktestResultResponse result = backtestService.runForExploration(req);

            BigDecimal cfPnl = BigDecimal.ZERO;
            int cfCount = 0;
            if (result.getTrades() != null) {
                for (BacktestResultResponse.TradeRecordDto t : result.getTrades()) {
                    if (t.getNetPnl() != null) {
                        cfPnl = cfPnl.add(t.getNetPnl());
                    }
                    cfCount++;
                }
            }
            a.setCounterfactualPnl(cfPnl);
            a.setCounterfactualTradeCount(cfCount);
            a.setAlphaContribution(actualPnl.subtract(cfPnl));
            a.setComputationStatus(AttributionStatusEnum.SUCCESS);
        } catch (Throwable t) {
            log.warn("[Attribution] backtest failed for override {}: {}", ov.getId(), t.getMessage());
            a.setComputationStatus(AttributionStatusEnum.BACKTEST_FAILED);
            a.setErrorMessage(truncate(t.getMessage() != null
                    ? t.getMessage() : t.getClass().getName(), 500));
            a.setAlphaContribution(BigDecimal.ZERO);
        }

        return attributionRepository.save(a);
    }

    /**
     * 近 N 天 SUCCESS 狀態 attribution 的聚合摘要。per-strategy 依累積 alpha 降冪。
     */
    @Transactional(readOnly = true)
    public AttributionSummary summarizeRecent(int days) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        List<MetaControlAttribution> successes = attributionRepository
                .findRecentByStatus(since, AttributionStatusEnum.SUCCESS);

        AttributionSummary summary = new AttributionSummary();
        summary.setDays(days);
        summary.setTotalOverrides(successes.size());

        BigDecimal total = BigDecimal.ZERO;
        int pos = 0, neg = 0, neu = 0;
        Map<Long, List<MetaControlAttribution>> byStrategy = new LinkedHashMap<>();
        for (MetaControlAttribution a : successes) {
            total = total.add(a.getAlphaContribution());
            int cmp = a.getAlphaContribution().compareTo(BigDecimal.ZERO);
            if (cmp > 0) pos++;
            else if (cmp < 0) neg++;
            else neu++;
            byStrategy.computeIfAbsent(a.getStrategyId(), k -> new ArrayList<>()).add(a);
        }
        summary.setTotalAlpha(total);
        summary.setPositiveCount(pos);
        summary.setNegativeCount(neg);
        summary.setNeutralCount(neu);

        List<AttributionSummary.StrategyBreakdown> perStrategy = new ArrayList<>();
        for (Map.Entry<Long, List<MetaControlAttribution>> e : byStrategy.entrySet()) {
            AttributionSummary.StrategyBreakdown sb = new AttributionSummary.StrategyBreakdown();
            sb.setStrategyId(e.getKey());
            Optional<BtStrategy> strategy = strategyRepository.findById(e.getKey());
            sb.setStrategyName(strategy.map(BtStrategy::getName).orElse("strategy-" + e.getKey()));
            sb.setOverrideCount(e.getValue().size());

            BigDecimal cum = BigDecimal.ZERO;
            BigDecimal maxPos = BigDecimal.ZERO;
            BigDecimal maxNeg = BigDecimal.ZERO;
            for (MetaControlAttribution a : e.getValue()) {
                BigDecimal alpha = a.getAlphaContribution();
                cum = cum.add(alpha);
                if (alpha.compareTo(maxPos) > 0) maxPos = alpha;
                if (alpha.compareTo(maxNeg) < 0) maxNeg = alpha;
            }
            sb.setCumulativeAlpha(cum);
            sb.setMaxPositive(maxPos);
            sb.setMaxNegative(maxNeg);
            perStrategy.add(sb);
        }
        perStrategy.sort((x, y) -> y.getCumulativeAlpha().compareTo(x.getCumulativeAlpha()));
        summary.setPerStrategy(perStrategy);

        return summary;
    }

    private Duration intervalToDuration(String intervalCode) {
        if (intervalCode == null) return Duration.ofHours(1);
        return switch (intervalCode.toLowerCase()) {
            case "1m" -> Duration.ofMinutes(1);
            case "5m" -> Duration.ofMinutes(5);
            case "15m" -> Duration.ofMinutes(15);
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "2h" -> Duration.ofHours(2);
            case "4h" -> Duration.ofHours(4);
            case "6h" -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d" -> Duration.ofDays(1);
            default -> Duration.ofHours(1);
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
