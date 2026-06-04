package com.agora.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs slow Spring bean creation during startup without changing business flow.
 * This is intentionally coarse: it highlights which bean owns otherwise silent gaps.
 */
@Slf4j
@Component
public class StartupBeanTimingProbe implements InstantiationAwareBeanPostProcessor, PriorityOrdered {

    private final boolean enabled;
    private final long thresholdMs;
    private final Map<String, Long> startedAtNanos = new ConcurrentHashMap<>();

    public StartupBeanTimingProbe() {
        this.enabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault(
                "STARTUP_BEAN_TIMING_ENABLED", "true"));
        this.thresholdMs = parseLong(System.getenv("STARTUP_BEAN_TIMING_THRESHOLD_MS"), 2_000L);
    }

    @Override
    public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws BeansException {
        if (enabled) {
            startedAtNanos.put(beanName, System.nanoTime());
        }
        return null;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!enabled) {
            return bean;
        }
        Long started = startedAtNanos.remove(beanName);
        if (started == null) {
            return bean;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        if (elapsedMs >= thresholdMs) {
            log.warn("[StartupBeanTiming] bean={} type={} took={}ms threshold={}ms",
                    beanName, bean.getClass().getName(), elapsedMs, thresholdMs);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
