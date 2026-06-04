package com.agora.scheduler.trading;

import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.util.AtrCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * #434 — BTC price-move indicator collector. Six new indicators written to
 * {@code market_indicator_history}:
 *
 * <ul>
 *   <li>{@code btc_change_pct_1h}  — close vs 1h ago, %</li>
 *   <li>{@code btc_change_pct_4h}  — close vs 4h ago, %</li>
 *   <li>{@code btc_change_pct_24h} — close vs 24h ago, %</li>
 *   <li>{@code btc_atr_units_1h}   — |Δ| / Wilder ATR(14) on 1h klines</li>
 *   <li>{@code btc_atr_units_4h}   — |Δ| / Wilder ATR(14) on 4h klines</li>
 *   <li>{@code btc_atr_units_24h}  — |Δ| / Wilder ATR(14) on 1d klines</li>
 * </ul>
 *
 * <p><b>Cadence</b>: 1h/4h indicators every 5 min; 24h every 15 min. Values
 * change only at kline-close boundaries (every hour / every 4h / every day),
 * so the higher-frequency cadence is for alert latency, not data freshness.
 *
 * <p><b>Why not in MarketIndicatorHistoryCollector</b>: that collector runs
 * hourly via HourlyOrchestrator. Price-move alerts need sub-hour reaction
 * time, so a dedicated scheduler is justified.
 *
 * <p><b>Why ATR-units</b>: pure-% thresholds either fire constantly in
 * volatile regimes or never in calm regimes. ATR-normalized thresholds
 * adapt to current volatility automatically (see {@link AtrCalculator}).
 *
 * <p>Each indicator write is wrapped in try/catch — a single computation
 * failure (insufficient kline history, DB hiccup) only loses that one row,
 * never blocks the other five or future ticks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BtcPriceMoveIndicatorCollector {

    private static final String SYMBOL = "BTCUSDT";

    private final MdKlineRepository klineRepo;
    private final MarketIndicatorHistoryRepository historyRepo;

    /** 1h + 4h indicators every 5 minutes — captures intra-hour spikes for alerts. */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void collectShortWindows() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        compute("1h",  "btc_change_pct_1h",  "btc_atr_units_1h",  now);
        compute("4h",  "btc_change_pct_4h",  "btc_atr_units_4h",  now);
    }

    /** 24h indicator every 15 minutes — daily window doesn't need sub-15min reaction. */
    @Scheduled(cron = "0 */15 * * * *", zone = "UTC")
    public void collectLongWindow() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        compute("1d", "btc_change_pct_24h", "btc_atr_units_24h", now);
    }

    private void compute(String intervalCode, String changeIndicator, String atrIndicator,
                         LocalDateTime now) {
        try {
            List<MdKline> recent = klineRepo.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    SYMBOL, intervalCode, PageRequest.of(0, 16));
            if (recent.size() < 16) {
                log.debug("[BtcPriceMove] {} klines insufficient ({} < 16), skip",
                        intervalCode, recent.size());
                return;
            }
            // findBy...Desc returns newest first; ATR helper expects ascending.
            Collections.reverse(recent);

            // recent now ordered ascending: oldest @ [0], newest @ [15]
            double closeNow = recent.get(15).getClosePrice().doubleValue();
            double closePrev = recent.get(14).getClosePrice().doubleValue();
            if (closePrev <= 0) {
                log.warn("[BtcPriceMove] {} prev close non-positive, skip", intervalCode);
                return;
            }

            double changePct = (closeNow - closePrev) / closePrev * 100.0;
            double atr14 = AtrCalculator.wildersAtr14(recent);
            double atrUnits = atr14 > 0 ? Math.abs(closeNow - closePrev) / atr14 : 0.0;

            persist(changeIndicator, changePct, now);
            persist(atrIndicator, atrUnits, now);

            log.debug("[BtcPriceMove] {} change={:.3f}% atr_units={:.3f} (atr14={:.2f})",
                    intervalCode, changePct, atrUnits, atr14);
        } catch (Throwable t) {
            log.warn("[BtcPriceMove] {} compute failed: {}", intervalCode, t.getMessage());
        }
    }

    private void persist(String indicator, double value, LocalDateTime capturedAt) {
        try {
            BigDecimal scaled = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
            historyRepo.insertIgnore(SYMBOL, indicator, capturedAt, scaled);
        } catch (Throwable t) {
            log.warn("[BtcPriceMove] persist {} failed: {}", indicator, t.getMessage());
        }
    }
}
