package com.agora.scheduler.trading;

import com.agora.config.MarketWsAutoSubscribeProperties;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.market.KlineDivergenceAlerter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨源 K 線偏差掃描 — manual scan 入口（給 MCP {@code runKlineDivergenceScan} 工具用）。
 *
 * <p>原本用 cron 每小時掃描，現已改為事件驅動
 * （見 {@link com.agora.listener.KlineDivergenceListener}），此類別只保留手動掃描功能。
 *
 * <p><b>實測基準</b>（180 天 BTC/ETH 1h，2026-04-14 資料）：
 * 平均偏差 0.08%、97% bar < 0.3%、最大 1.14%。
 * 閾值：0.5% WARN，1.0% CRITICAL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineDivergenceMonitor {

    private final MdKlineRepository klineRepository;
    private final MarketWsAutoSubscribeProperties wsProps;
    private final KlineDivergenceAlerter alerter;
    private final com.agora.config.properties.KlineDivergenceProperties props;

    /** Manual scan：由 MCP {@code runKlineDivergenceScan} 工具觸發。回傳 summary。 */
    public String runManual() {
        if (!props.enabled()) {
            return "[KlineDivergence] disabled by trading.kline-divergence.enabled=false";
        }
        if (wsProps.getItems() == null || wsProps.getItems().isEmpty()) {
            return "No subscribed pairs.";
        }

        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusHours(24);

        int totalBarsChecked = 0;
        int warnCount = 0;
        int criticalCount = 0;
        int thinSourceCount = 0;
        List<String> criticalSamples = new ArrayList<>();
        List<String> warnSamples = new ArrayList<>();
        List<String> thinSourceSamples = new ArrayList<>();

        for (MarketWsAutoSubscribeProperties.Item item : wsProps.getItems()) {
            String symbol = item.getSymbol().toUpperCase();
            String intervalCode = item.getIntervalCode().toLowerCase();

            List<Object[]> pairs = klineRepository.findDualSourcePairs(
                    symbol, intervalCode, start, end);

            int checked = 0;
            for (Object[] row : pairs) {
                if (checked++ >= props.recentBars()) break;
                LocalDateTime openTime = toLocalDateTime(row[0]);
                BigDecimal binClose = (BigDecimal) row[1];
                BigDecimal okxClose = (BigDecimal) row[2];
                BigDecimal binVol = (BigDecimal) row[3];
                BigDecimal okxVol = (BigDecimal) row[4];

                double diffPct = KlineDivergenceAlerter.diffPct(binClose, okxClose);
                totalBarsChecked++;

                KlineDivergenceAlerter.DivergenceLevel level = alerter.classify(diffPct, binVol, okxVol);
                String sample = alerter.formatSample(symbol, intervalCode, openTime,
                        binClose, okxClose, diffPct, binVol, okxVol);
                if (level == KlineDivergenceAlerter.DivergenceLevel.CRITICAL) {
                    criticalCount++;
                    criticalSamples.add(sample);
                } else if (level == KlineDivergenceAlerter.DivergenceLevel.WARN) {
                    warnCount++;
                    warnSamples.add(sample);
                } else if (level == KlineDivergenceAlerter.DivergenceLevel.THIN_SOURCE) {
                    thinSourceCount++;
                    thinSourceSamples.add(sample);
                }
            }
        }

        String summary = String.format(
                "[KlineDivergence] manual scan: checked=%d critical(>=%.1f%%)=%d warn(>=%.1f%%)=%d thinSource=%d",
                totalBarsChecked, alerter.getCriticalPct(), criticalCount,
                alerter.getWarnPct(), warnCount, thinSourceCount);
        log.info(summary);

        if (criticalCount > 0) {
            alerter.sendBatchAlert("CRITICAL", criticalSamples, warnCount, warnSamples);
        } else if (warnCount > 0) {
            alerter.sendBatchAlert("WARN", warnSamples, 0, List.of());
        }

        return summary + (criticalSamples.isEmpty() ? ""
                : "\n\nCRITICAL samples:\n" + String.join("\n", criticalSamples))
                + (thinSourceSamples.isEmpty() ? ""
                : "\n\nTHIN_SOURCE downgraded samples:\n" + String.join("\n", thinSourceSamples));
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("Unsupported open_time type: "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
