package com.agora.metrics;

import com.agora.event.KlineClosedEvent;
import com.agora.model.MdKline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits {@code market.kline.stream.lag.seconds} gauge per (symbol, interval_code).
 *
 * <p>Value = {@code now - last_closed_bar_openTime} in seconds. A healthy
 * 1h stream sits around [3600, 3700] (bar close arrives shortly after openTime + interval).
 * Sudden climb to > 2× interval means the WS feed has stalled — alert.</p>
 *
 * <p>Registers a gauge lazily per key the first time a bar closes for that pair.
 * The registry keeps a weak reference to the AtomicLong supplier, so subsequent
 * updates just set the long value in place.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineLagMeter {

    private final MeterRegistry registry;

    /** key = symbol + ":" + intervalCode → last closed bar openTime (epoch seconds). */
    private final ConcurrentHashMap<String, AtomicLong> lastCloseEpoch = new ConcurrentHashMap<>();

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        MdKline k = event.getKline();
        if (k == null || k.getSymbol() == null || k.getIntervalCode() == null
                || k.getOpenTime() == null) return;

        String key = k.getSymbol() + ":" + k.getIntervalCode();
        long openEpoch = k.getOpenTime().toEpochSecond(ZoneOffset.UTC);

        AtomicLong slot = lastCloseEpoch.computeIfAbsent(key, this::registerGauge);
        slot.set(openEpoch);
    }

    private AtomicLong registerGauge(String key) {
        AtomicLong slot = new AtomicLong(Instant.now().getEpochSecond());
        String[] parts = key.split(":", 2);
        String symbol = parts.length > 0 ? parts[0] : "unknown";
        String intervalCode = parts.length > 1 ? parts[1] : "unknown";

        Gauge.builder(TradingMetrics.KLINE_STREAM_LAG, slot,
                        s -> Math.max(0, Instant.now().getEpochSecond() - s.get()))
                .description("Seconds since the last closed-bar event for (symbol, interval). " +
                        "> 2× interval = WS feed stalled.")
                .tag("symbol", symbol)
                .tag("interval", intervalCode)
                .baseUnit("seconds")
                .register(registry);

        log.info("[KlineLagMeter] registered gauge symbol={} interval={}", symbol, intervalCode);
        return slot;
    }
}
