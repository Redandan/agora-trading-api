package com.agora.scheduler.trading;

import com.agora.config.properties.ShortSqueezeAlertProperties;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.market.BinanceSpotTakerBuyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects 15-minute spot taker buy volume from Binance every minute.
 * Stores as mih_indicator = spot_taker_buy_usd_15m.
 * Used by ShortSqueezeAlertScheduler as the "trigger" condition.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.short-squeeze-alert.taker-buy-collector-enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class BinanceSpotTakerBuyCollector {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final BinanceSpotTakerBuyService takerBuyService;
    private final ShortSqueezeAlertProperties props;

    private static final String SYMBOL = "BTCUSDT";
    private final AtomicBoolean collectRunning = new AtomicBoolean(false);

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void collect() {
        if (!props.takerBuyCollectorEnabled()) return;
        if (!collectRunning.compareAndSet(false, true)) {
            log.warn("[SpotTakerBuy] previous collection still running; skipping this tick");
            return;
        }
        try {
            double takerBuyUsd = takerBuyService.fetchTakerBuyUsd(SYMBOL);
            LocalDateTime capturedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
            int inserted = historyRepo.insertIgnore(
                    SYMBOL,
                    BinanceSpotTakerBuyService.INDICATOR,
                    capturedAt,
                    BigDecimal.valueOf(takerBuyUsd));

            log.debug("[SpotTakerBuy] {} 15m taker buy = ${}", SYMBOL,
                    String.format("%.0f", takerBuyUsd));
            if (inserted == 0) {
                log.debug("[SpotTakerBuy] duplicate snapshot ignored capturedAt={}", capturedAt);
            }
        } catch (Exception e) {
            log.warn("[SpotTakerBuy] collect failed: {}", e.getMessage());
        } finally {
            collectRunning.set(false);
        }
    }
}
