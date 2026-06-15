package com.agora.service.backtest;

import com.agora.event.KlineClosedEvent;
import com.agora.model.MdKline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Bridges closed K-line events from market-data streams into live signal evaluation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineClosedEventListener {

    private final LiveSignalEvaluator liveSignalEvaluator;

    @Async
    @EventListener
    @CacheEvict(value = "liveSignalKlines", allEntries = true)
    public void onKlineClosed(KlineClosedEvent event) {
        MdKline kline = event.getKline();
        if (kline == null || kline.getSymbol() == null || kline.getIntervalCode() == null) {
            return;
        }

        String intervalCode = kline.getIntervalCode();
        if ("1m".equalsIgnoreCase(intervalCode)) {
            return;
        }

        try {
            log.info("[KlineClosedEventListener] evaluate {}@{} openTime={} source={}",
                    kline.getSymbol(), intervalCode, kline.getOpenTime(), kline.getSource());
            liveSignalEvaluator.evaluate(kline.getSymbol(), intervalCode);
        } catch (Exception e) {
            log.error("[KlineClosedEventListener] evaluate failed {}@{} openTime={}: {}",
                    kline.getSymbol(), intervalCode, kline.getOpenTime(), e.getMessage(), e);
        }
    }
}
