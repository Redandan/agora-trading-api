package com.agora.config;

import com.agora.service.market.KlineStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the running WS subscriptions aligned with {@link WsSubscriptionResolver}'s
 * current desired set. Runs on:
 * <ul>
 *   <li>{@link StrategyEnabledEvent} — immediate resync (200ms debounce so a
 *       batch enable/disable still fires once).</li>
 *   <li>A 5-minute scheduled tick — safety net for missed events / manual DB
 *       edits.</li>
 *   <li>Manual MCP trigger via {@code reloadWsSubscriptions}.</li>
 * </ul>
 *
 * <h3>Separation from {@code MarketWsAutoSubscriber}</h3>
 * {@code MarketWsAutoSubscriber} handles the <em>initial</em> boot-time
 * subscription + cache warm-up. This class handles <em>ongoing</em> drift
 * resolution. Both are controlled by {@code market.ws.auto-subscribe.enabled}
 * so local smoke/test profiles can keep every WS subscription path inert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSubscriptionSyncer {

    private final MarketWsAutoSubscribeProperties properties;
    private final WsSubscriptionResolver resolver;
    private final List<KlineStreamService> streamServices;

    /** Prevents overlapping resyncs from event + scheduler firing simultaneously. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Async
    @EventListener(StrategyEnabledEvent.class)
    public void onStrategyEnabled(StrategyEnabledEvent event) {
        log.info("[WsSubSyncer] Strategy {} enabled={} reason={}",
                event.strategyId(), event.enabled(), event.reason());
        resync("strategy-event");
    }

    /**
     * Reconcile every 15 minutes. Idempotent — subscribes only missing pairs,
     * unsubscribes orphans. No-op if already in sync.
     * 5 min → 15 min: StrategyEnabledEvent already handles immediate resync on changes;
     * this poll is just a safety net and does not need to run this frequently.
     */
    @Scheduled(fixedDelay = 900_000L, initialDelay = 120_000L)
    public void periodicResync() {
        resync("periodic");
    }

    /** Manual MCP-triggered resync; returns human-readable diff summary. */
    public String manualResync() {
        return resync("manual");
    }

    private String resync(String trigger) {
        if (!properties.isEnabled()) {
            String msg = "[WsSubSyncer] Resync disabled by market.ws.auto-subscribe.enabled=false trigger=" + trigger;
            log.debug(msg);
            return msg;
        }
        if (!running.compareAndSet(false, true)) {
            String msg = "[WsSubSyncer] Resync skipped (another in progress) trigger=" + trigger;
            log.info(msg);
            return msg;
        }
        try {
            List<MarketWsAutoSubscribeProperties.Item> desired = resolver.resolve();
            Set<String> desiredKeys = new LinkedHashSet<>();
            for (var item : desired) desiredKeys.add(WsSubscriptionResolver.keyOf(item));

            Set<String> currentKeys = new HashSet<>();
            for (KlineStreamService svc : streamServices) {
                for (var sub : svc.listSubscriptions()) {
                    currentKeys.add(sub.getMarketType().toUpperCase() + ":"
                            + sub.getSymbol().toUpperCase() + ":" + sub.getIntervalCode());
                }
            }

            Set<String> toAdd = new LinkedHashSet<>(desiredKeys);
            toAdd.removeAll(currentKeys);
            Set<String> toRemove = new LinkedHashSet<>(currentKeys);
            toRemove.removeAll(desiredKeys);

            int added = 0;
            int removed = 0;
            for (var item : desired) {
                String key = WsSubscriptionResolver.keyOf(item);
                if (!toAdd.contains(key)) continue;
                for (KlineStreamService svc : streamServices) {
                    try {
                        svc.subscribe(item.getSymbol(), item.getIntervalCode(), item.getMarketType());
                        added++;
                    } catch (Exception e) {
                        log.warn("[WsSubSyncer] subscribe failed provider={} sym={} iv={}: {}",
                                svc.providerName(), item.getSymbol(), item.getIntervalCode(), e.getMessage());
                    }
                }
            }
            for (String key : toRemove) {
                String[] parts = key.split(":");
                if (parts.length != 3) continue;
                for (KlineStreamService svc : streamServices) {
                    try {
                        boolean ok = svc.unsubscribe(parts[1], parts[2], parts[0]);
                        if (ok) removed++;
                    } catch (Exception e) {
                        log.warn("[WsSubSyncer] unsubscribe failed provider={} key={}: {}",
                                svc.providerName(), key, e.getMessage());
                    }
                }
            }

            String summary = String.format(
                    "[WsSubSyncer] trigger=%s desired=%d current=%d added=%d removed=%d",
                    trigger, desiredKeys.size(), currentKeys.size(), added, removed);
            if (added > 0 || removed > 0) {
                log.info(summary);
            } else {
                log.debug(summary);
            }
            return summary;
        } finally {
            running.set(false);
        }
    }
}
