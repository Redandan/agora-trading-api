package com.agora.config;

import com.agora.service.ServerStartupService;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.KlineStreamService;
import com.agora.service.market.MarketDataTelegramAlertFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 應用啟動後自動建立市場資料 WS 訂閱。
 *
 * <p>策略評估只由 closed K-line event 經 Strategy Runtime Catalog 分派；
 * 啟動流程不得從資料庫 enabled flag 直接補跑舊策略。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "market.ws.auto-subscribe", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class MarketWsAutoSubscriber {

    /** Spring 會注入所有 KlineStreamService 實作；provider allowlist 決定自動訂閱哪些。 */
    private final List<KlineStreamService> wsKlineServices;
    private final NotificationPort notificationPort;
    private final MarketWsAutoSubscribeProperties properties;
    private final ServerStartupService serverStartupService;
    private final WsSubscriptionResolver subscriptionResolver;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeOnStartup() {
        if (!properties.isEnabled()) {
            log.info("[MarketWS] Auto subscribe disabled");
            return;
        }

        // The runtime catalog owns exact provider/symbol/interval requirements.
        List<MarketWsAutoSubscribeProperties.Item> itemsToSubscribe = subscriptionResolver.resolve();
        if (itemsToSubscribe.isEmpty()) {
            log.warn("[MarketWS] No catalog market-data requirements to subscribe");
            return;
        }
        List<KlineStreamService> activeServices = activeStreamServices();
        if (activeServices.isEmpty()) {
            log.warn("[MarketWS] No enabled providers for auto-subscribe; configured providers={}",
                    properties.normalizedProviders().isEmpty() ? "all" : properties.normalizedProviders());
            return;
        }

        long startupLogId = serverStartupService.recordStarted();
        Set<String> expectedSubscriptionKeys = new LinkedHashSet<>();

        for (MarketWsAutoSubscribeProperties.Item item : itemsToSubscribe) {
            try {
                if (item.getSymbol() == null || item.getSymbol().trim().isEmpty()
                        || item.getIntervalCode() == null || item.getIntervalCode().trim().isEmpty()) {
                    log.warn("[MarketWS] Skip invalid auto-subscribe item: symbol={} interval={}",
                            item.getSymbol(), item.getIntervalCode());
                    continue;
                }
                List<KlineStreamService> itemServices = servicesFor(item, activeServices);
                if (itemServices.isEmpty()) {
                    log.warn("[MarketWS] No enabled provider matches catalog requirement provider={} {} {}@{}",
                            item.getProvider(), item.getMarketType(), item.getSymbol(), item.getIntervalCode());
                    continue;
                }
                for (KlineStreamService svc : itemServices) {
                    svc.subscribe(item.getSymbol(), item.getIntervalCode(), item.getMarketType());
                    expectedSubscriptionKeys.add(subscriptionKey(
                            svc.providerName(),
                            item.getMarketType(),
                            item.getSymbol(),
                            item.getIntervalCode()));
                    log.info("[MarketWS] Catalog subscribed via {} → {} {}@{}",
                            svc.providerName(), item.getMarketType(), item.getSymbol(), item.getIntervalCode());
                }
            } catch (Exception e) {
                String message = MarketDataTelegramAlertFormatter.wsStartupFailure(
                        item.getMarketType(),
                        item.getSymbol(),
                        item.getIntervalCode(),
                        e.getMessage());
                log.error(message, e);
                try {
                    notificationPort.broadcast(message);
                } catch (Exception notifyError) {
                    log.error("[MarketWS] Failed to send startup error Telegram alert", notifyError);
                }
            }
        }

        // 等待所有 WS 真正連線（onOpen 後 status 變 RUNNING），最多 30 秒
        waitForWsRunning(expectedSubscriptionKeys, activeServices);
        serverStartupService.recordWsReady(startupLogId);

        log.info("[MarketWS] Strategy warm-up skipped; closed K-line events own catalog dispatch");
        serverStartupService.recordFirstEval(startupLogId);
    }

    /**
     * 輪詢直到所有已訂閱的 WS 均為 RUNNING 狀態，或超時（30 秒）。
     * 避免在 TCP 握手完成前就記錄 ws_ready_at。
     */
    private void waitForWsRunning(Set<String> expectedSubscriptionKeys,
                                  List<KlineStreamService> activeServices) {
        if (expectedSubscriptionKeys.isEmpty()) return;

        int maxWaitMs = 30_000;
        int intervalMs = 200;
        int elapsed = 0;

        int expectedTotal = expectedSubscriptionKeys.size();
        while (elapsed < maxWaitMs) {
            long runningCount = activeServices.stream()
                    .flatMap(svc -> svc.listSubscriptions().stream()
                            .filter(s -> "RUNNING".equals(s.getStatus()))
                            .filter(s -> expectedSubscriptionKeys.contains(subscriptionKey(
                                    svc.providerName(),
                                    s.getMarketType(),
                                    s.getSymbol(),
                                    s.getIntervalCode()))))
                    .count();

            if (runningCount >= expectedTotal) {
                log.info("[MarketWS] All {} catalog WS connections RUNNING (+{}ms)",
                        expectedTotal, elapsed);
                return;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            elapsed += intervalMs;
        }
        log.warn("[MarketWS] WS connection wait timed out after {}ms", maxWaitMs);
    }

    private List<KlineStreamService> activeStreamServices() {
        return wsKlineServices.stream()
                .filter(svc -> properties.isProviderEnabled(svc.providerName()))
                .toList();
    }

    private List<KlineStreamService> servicesFor(
            MarketWsAutoSubscribeProperties.Item item,
            List<KlineStreamService> activeServices) {
        String requiredProvider = normalize(item.getProvider());
        if (requiredProvider.isEmpty()) {
            return activeServices;
        }
        return activeServices.stream()
                .filter(svc -> requiredProvider.equals(normalize(svc.providerName())))
                .toList();
    }

    private static String subscriptionKey(
            String provider,
            String marketType,
            String symbol,
            String interval) {
        return normalize(provider) + ":"
                + normalizeUpper(marketType) + ":"
                + normalizeUpper(symbol) + ":"
                + normalize(interval);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
