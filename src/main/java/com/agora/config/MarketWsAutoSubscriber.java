package com.agora.config;

import com.agora.service.ServerStartupService;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.backtest.LiveSignalEvaluator;
import com.agora.service.market.KlineStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 應用啟動後自動建立 Binance WS 訂閱，並從 DB 補跑最新評估以暖機快取。
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
    private final LiveSignalEvaluator liveSignalEvaluator;
    private final ServerStartupService serverStartupService;
    private final WsSubscriptionResolver subscriptionResolver;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void subscribeOnStartup() {
        if (!properties.isEnabled()) {
            log.info("[MarketWS] Auto subscribe disabled");
            return;
        }

        // V14 — derive item list from enabled strategies + grids (yaml fallback).
        // Order of precedence:
        //   1. bt_strategy.enabled=1 × bt_grid.enabled=1 (DB source of truth)
        //   2. yaml items (legacy fallback, resolver handles empty-DB gracefully)
        List<MarketWsAutoSubscribeProperties.Item> itemsToSubscribe = subscriptionResolver.resolve();
        if (itemsToSubscribe.isEmpty()) {
            log.warn("[MarketWS] No items to subscribe (DB empty + yaml empty)");
            return;
        }
        List<KlineStreamService> activeServices = activeStreamServices();
        if (activeServices.isEmpty()) {
            log.warn("[MarketWS] No enabled providers for auto-subscribe; configured providers={}",
                    properties.normalizedProviders().isEmpty() ? "all" : properties.normalizedProviders());
            return;
        }

        long startupLogId = serverStartupService.recordStarted();
        List<MarketWsAutoSubscribeProperties.Item> subscribed = new ArrayList<>();

        for (MarketWsAutoSubscribeProperties.Item item : itemsToSubscribe) {
            try {
                if (item.getSymbol() == null || item.getSymbol().trim().isEmpty()
                        || item.getIntervalCode() == null || item.getIntervalCode().trim().isEmpty()) {
                    log.warn("[MarketWS] Skip invalid auto-subscribe item: symbol={} interval={}",
                            item.getSymbol(), item.getIntervalCode());
                    continue;
                }
                // 每個允許的 provider 各開一條訂閱，資料以 source 欄位區分。
                for (KlineStreamService svc : activeServices) {
                    svc.subscribe(item.getSymbol(), item.getIntervalCode(), item.getMarketType());
                    log.info("[MarketWS] Auto subscribed via {} → {} {}@{}",
                            svc.providerName(), item.getMarketType(), item.getSymbol(), item.getIntervalCode());
                }
                subscribed.add(item);
            } catch (Exception e) {
                String message = String.format(
                        "[MarketWS] 啟動自動訂閱失敗\nmarketType=%s\nsymbol=%s\ninterval=%s\nreason=%s",
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
        waitForWsRunning(subscribed, activeServices);
        serverStartupService.recordWsReady(startupLogId);

        // 暖機快取：從 DB 讀取最新 K 線補跑評估，避免重啟後等待下一根 K 線才有數據
        if (!properties.isWarmUpEnabled()) {
            log.info("[MarketWS] Cache warm-up disabled");
        } else if (!subscribed.isEmpty()) {
            log.info("[MarketWS] Warming up MarketSignalCache for {} pairs...", subscribed.size());
            for (MarketWsAutoSubscribeProperties.Item item : subscribed) {
                // 1m 訂閱只為即時價格資料，不做策略評估
                if ("1m".equalsIgnoreCase(item.getIntervalCode())) continue;
                try {
                    liveSignalEvaluator.evaluate(item.getSymbol(), item.getIntervalCode());
                    log.info("[MarketWS] Cache warmed up: {}@{}", item.getSymbol(), item.getIntervalCode());
                } catch (Exception e) {
                    log.warn("[MarketWS] Cache warm-up failed for {}@{}: {}",
                            item.getSymbol(), item.getIntervalCode(), e.getMessage());
                }
            }
            log.info("[MarketWS] MarketSignalCache warm-up complete");
        }

        serverStartupService.recordFirstEval(startupLogId);
    }

    /**
     * 輪詢直到所有已訂閱的 WS 均為 RUNNING 狀態，或超時（30 秒）。
     * 避免在 TCP 握手完成前就記錄 ws_ready_at。
     */
    private void waitForWsRunning(List<MarketWsAutoSubscribeProperties.Item> subscribed,
                                  List<KlineStreamService> activeServices) {
        if (subscribed.isEmpty()) return;

        Set<String> expectedKeys = subscribed.stream()
                .map(i -> i.getMarketType().toUpperCase() + ":"
                        + i.getSymbol().toUpperCase() + ":"
                        + i.getIntervalCode())
                .collect(Collectors.toSet());

        int maxWaitMs = 30_000;
        int intervalMs = 200;
        int elapsed = 0;

        // 每個 item 會在每個允許的 provider 各開 1 條連線，total = items × providers
        int expectedTotal = expectedKeys.size() * activeServices.size();
        while (elapsed < maxWaitMs) {
            long runningCount = activeServices.stream()
                    .flatMap(svc -> svc.listSubscriptions().stream())
                    .filter(s -> "RUNNING".equals(s.getStatus()))
                    .filter(s -> expectedKeys.contains(
                            s.getMarketType().toUpperCase() + ":"
                            + s.getSymbol().toUpperCase() + ":"
                            + s.getIntervalCode()))
                    .count();

            if (runningCount >= expectedTotal) {
                log.info("[MarketWS] All {} WS connections RUNNING across {} provider(s) (+{}ms)",
                        expectedTotal, activeServices.size(), elapsed);
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
}
