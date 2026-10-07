package com.agora.service.advice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A default-off advice clock; cannot enable or evaluate a Trading strategy. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.btc-order-advice.monitor-enabled", havingValue = "true")
public class BtcOrderAdviceMonitor {
    private final BtcOrderAdviceService advice;

    @Scheduled(initialDelay = 60000, fixedDelay = 300000)
    public void refresh() {
        try { advice.monitorOnce(); }
        catch (RuntimeException e) { log.warn("[BtcOrderAdvice] refresh unavailable ({})", e.getClass().getSimpleName()); }
    }
}
