package com.agora.service.backtest;

import com.agora.event.KlineClosedEvent;
import com.agora.model.MdKline;
import com.agora.service.strategy.RuntimeStrategy;
import com.agora.service.strategy.RuntimeStrategyRegistry;
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

    private final RuntimeStrategyRegistry runtimeStrategyRegistry;

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
        for (RuntimeStrategy strategy : runtimeStrategyRegistry.evaluationOrder()) {
            try {
                strategy.onClosedBar(kline);
            } catch (Exception e) {
                log.error("[KlineClosedEventListener] strategy lane failed key={} {}@{} "
                                + "openTime={}: {}",
                        strategy.key(),
                        kline.getSymbol(),
                        intervalCode,
                        kline.getOpenTime(),
                        e.getMessage(),
                        e);
            }
        }
    }
}
