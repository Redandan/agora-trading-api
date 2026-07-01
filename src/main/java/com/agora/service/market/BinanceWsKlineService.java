package com.agora.service.market;

import com.agora.config.BinanceMarketDataProperties;
import com.agora.dto.market.KlineSubscriptionInfo;
import com.agora.event.KlineClosedEvent;
import com.agora.event.WsReconnectedEvent;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;

/**
 * 透過 Binance WebSocket 即時訂閱已收盤 K 線並自動存入 md_kline 表。
 * - 每個 symbol+interval 組合使用獨立的 WS 連線。
 * - 發生連線錯誤時會發送 Telegram 告警並停止該訂閱（不重連）。
 * - Spring context 關閉時優雅釋放所有連線資源。
 */
@Slf4j
@Service
public class BinanceWsKlineService implements DisposableBean, KlineStreamService {

    @Override
    public String providerName() { return "binance"; }


    private static final String WS_BASE_SPOT = "wss://stream.binance.com:9443/ws/";
    private static final String WS_BASE_FUTURES = "wss://fstream.binance.com/ws/";
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long[] BACKOFF_DELAYS_MS = {5_000, 15_000, 30_000, 60_000, 60_000};

    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient wsClient;
    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final String spotWsBaseUrl;
    private final String futuresWsBaseUrl;
    /** Optional — Spring-wired in production, null in legacy test ctors. */
    private MdKlineInsertHelper insertHelper;

    private final ConcurrentHashMap<String, WsSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public BinanceWsKlineService(MdKlineRepository klineRepository,
                                 ObjectMapper objectMapper,
                                 NotificationPort notificationPort,
                                 ApplicationEventPublisher eventPublisher,
                                 BinanceMarketDataProperties properties) {
        this(klineRepository, objectMapper,
                new OkHttpClient.Builder()
                        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(), notificationPort, eventPublisher,
            resolveSpotWsBaseUrl(properties),
            resolveFuturesWsBaseUrl(properties));
    }

    /** 測試用建構子，允許注入自訂 OkHttpClient */
    BinanceWsKlineService(MdKlineRepository klineRepository, ObjectMapper objectMapper,
                          OkHttpClient wsClient) {
        this(klineRepository, objectMapper, wsClient, null, null, WS_BASE_SPOT, WS_BASE_FUTURES);
    }

    /** 測試用建構子，允許注入自訂 OkHttpClient 與 NotificationPort */
    BinanceWsKlineService(MdKlineRepository klineRepository, ObjectMapper objectMapper,
                          OkHttpClient wsClient, NotificationPort notificationPort) {
        this(klineRepository, objectMapper, wsClient, notificationPort, null, WS_BASE_SPOT, WS_BASE_FUTURES);
    }

    /** 測試用建構子，允許注入完整端點配置 */
    BinanceWsKlineService(MdKlineRepository klineRepository, ObjectMapper objectMapper,
                          OkHttpClient wsClient, NotificationPort notificationPort,
                          ApplicationEventPublisher eventPublisher,
                          String spotWsBaseUrl, String futuresWsBaseUrl) {
        this.klineRepository = klineRepository;
        this.objectMapper = objectMapper;
        this.wsClient = wsClient;
        this.notificationPort = notificationPort;
        this.eventPublisher = eventPublisher;
        this.spotWsBaseUrl = normalizeWsBaseUrl(spotWsBaseUrl, WS_BASE_SPOT);
        this.futuresWsBaseUrl = normalizeWsBaseUrl(futuresWsBaseUrl, WS_BASE_FUTURES);
    }

    /**
     * Setter injection for {@link MdKlineInsertHelper}. Field is left
     * package-private so legacy test ctors can stay without the helper while
     * production wiring is satisfied.
     */
    @Autowired(required = false)
    public void setInsertHelper(MdKlineInsertHelper insertHelper) {
        this.insertHelper = insertHelper;
    }

    @PostConstruct
    void logEndpointConfig() {
        log.info("[BinanceWS] Endpoint config loaded | spotWs={} futuresWs={}", spotWsBaseUrl, futuresWsBaseUrl);
    }

    // ── 公開 API ─────────────────────────────────────────────────────────────

    /**
     * 訂閱指定 symbol + intervalCode 的即時 K 線。
     * 若已訂閱則直接回傳現有狀態（冪等）。
     */
    public KlineSubscriptionInfo subscribe(String symbol, String intervalCode) {
        return subscribe(symbol, intervalCode, "SPOT");
    }

    public KlineSubscriptionInfo subscribe(String symbol, String intervalCode, String marketType) {
        String normalizedMarketType = normalizeMarketType(marketType);
        String key = buildKey(symbol, intervalCode, normalizedMarketType);
        WsSubscription existing = subscriptions.get(key);
        if (existing != null) {
            log.info("[BinanceWS] Already subscribed: {}", key);
            return toInfo(existing);
        }
        WsSubscription sub = new WsSubscription(symbol.toUpperCase(), intervalCode, normalizedMarketType);
        subscriptions.put(key, sub);
        connect(sub);
        return toInfo(sub);
    }

    /**
     * 停止並移除指定訂閱。
     *
     * @return true 若成功移除，false 若該訂閱不存在
     */
    public boolean unsubscribe(String symbol, String intervalCode) {
        return unsubscribe(symbol, intervalCode, "SPOT");
    }

    public boolean unsubscribe(String symbol, String intervalCode, String marketType) {
        WsSubscription sub = subscriptions.remove(buildKey(symbol, intervalCode, normalizeMarketType(marketType)));
        if (sub != null) {
            sub.close();
            log.info("[BinanceWS] Unsubscribed: {} {}@{}", sub.marketType, symbol, intervalCode);
            return true;
        }
        return false;
    }

    /** 回傳所有訂閱的即時狀態快照 */
    public List<KlineSubscriptionInfo> listSubscriptions() {
        return subscriptions.values().stream().map(this::toInfo).collect(Collectors.toList());
    }

    // ── Spring 生命週期 ───────────────────────────────────────────────────────

    @Override
    public void destroy() {
        log.info("[BinanceWS] Shutting down {} subscriptions", subscriptions.size());
        subscriptions.values().forEach(WsSubscription::close);
        subscriptions.clear();
        reconnectExecutor.shutdownNow();
        wsClient.dispatcher().executorService().shutdown();
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────

    private void connect(WsSubscription sub) {
        String wsBase = "FUTURES".equals(sub.marketType) ? futuresWsBaseUrl : spotWsBaseUrl;
        String stream = sub.symbol.toLowerCase() + "@kline_" + sub.intervalCode;
        Request request = new Request.Builder().url(wsBase + stream).build();
        sub.status = "CONNECTING";
        sub.webSocket = wsClient.newWebSocket(request, new KlineWsListener(sub));
        log.debug("[BinanceWS] Connecting to: {}", wsBase + stream);
    }

    private static String buildKey(String symbol, String intervalCode, String marketType) {
        return marketType.toUpperCase() + ":" + symbol.toUpperCase() + ":" + intervalCode;
    }

    private KlineSubscriptionInfo toInfo(WsSubscription sub) {
        KlineSubscriptionInfo info = new KlineSubscriptionInfo();
        info.setSymbol(sub.symbol);
        info.setIntervalCode(sub.intervalCode);
        info.setMarketType(sub.marketType);
        info.setStatus(sub.status);
        info.setConnectedAt(sub.connectedAt);
        info.setReceivedCount(sub.receivedCount);
        info.setSource(providerName());
        return info;
    }

    private void stopWithAlert(WsSubscription sub, String reason) {
        if (sub.stoppedByError) {
            return;
        }
        sub.stoppedByError = true;
        sub.status = "STOPPED";
        if (sub.webSocket != null) {
            try {
                sub.webSocket.close(1011, "Stopped due to error");
            } catch (Exception ignored) {
                // Ignore close exceptions in failure path.
            }
        }

        String message = MarketDataTelegramAlertFormatter.wsStopped(
                providerName(),
                sub.marketType,
                sub.symbol,
                sub.intervalCode,
                reason == null ? "unknown" : reason);

        log.error("{}", message);
        if (notificationPort != null) {
            try {
                notificationPort.broadcast(message);
            } catch (Exception e) {
                log.error("[BinanceWS] Failed to send Telegram alert: {}", e.getMessage(), e);
            }
        }
    }

    // ── WebSocket 監聽器 ──────────────────────────────────────────────────────

    private class KlineWsListener extends WebSocketListener {

        private final WsSubscription sub;

        KlineWsListener(WsSubscription sub) {
            this.sub = sub;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            boolean isReconnect = sub.reconnectAttempts > 0;
            sub.status = "RUNNING";
            sub.connectedAt = LocalDateTime.now();
            sub.stoppedByError = false;
            sub.reconnectAttempts = 0;
            log.info("[BinanceWS] Connected{}: {} {}@kline_{}",
                    isReconnect ? "(reconnect)" : "", sub.marketType, sub.symbol, sub.intervalCode);
            // 重連後補發事件，讓 evaluator 補跑最新 bar（避免斷線期間漏掉訊號）
            if (isReconnect && eventPublisher != null) {
                eventPublisher.publishEvent(new WsReconnectedEvent(this, sub.symbol, sub.intervalCode));
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JsonNode root = objectMapper.readTree(text);
                JsonNode k = root.get("k");
                // k.x == true 表示此根 K 線已收盤，才存入 DB
                if (k == null || !k.get("x").asBoolean()) return;

                MdKline kline = parseWsKline(sub.symbol, sub.intervalCode, k);
                kline.setSource("binance");
                if (!klineRepository.existsBySymbolAndIntervalCodeAndOpenTimeAndSource(
                        kline.getSymbol(), kline.getIntervalCode(), kline.getOpenTime(), "binance")) {
                    boolean inserted;
                    if (insertHelper != null) {
                        // Production path: INSERT IGNORE — silent on dup, no Hibernate ERROR log
                        inserted = insertHelper.insertIgnore(kline);
                    } else {
                        // Legacy path (tests only): JPA save with try/catch dup
                        try {
                            klineRepository.save(kline);
                            inserted = true;
                        } catch (DataIntegrityViolationException dup) {
                            inserted = false;
                        }
                    }
                    if (inserted) {
                        sub.receivedCount++;
                        log.debug("[BinanceWS] Saved {} kline {}@{} openTime={}",
                                sub.marketType, sub.symbol, sub.intervalCode, kline.getOpenTime());
                        if (eventPublisher != null) {
                            eventPublisher.publishEvent(new KlineClosedEvent(this, kline));
                        }
                    } else {
                        // TOCTOU race: another writer (gap detector or parallel WS) won.
                        // INSERT IGNORE handled it silently; we just log debug.
                        log.debug("[BinanceWS] Race lost (row already persisted) for {} {}@{} openTime={}",
                                sub.marketType, sub.symbol, sub.intervalCode, kline.getOpenTime());
                    }
                }
            } catch (Exception e) {
                log.error("[BinanceWS] Failed to process message: {}", e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            BinanceWsKlineService.this.scheduleReconnect(sub, t == null ? "unknown error" : t.getMessage());
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            if (!"STOPPED".equals(sub.status) && !sub.stoppedByError) {
                BinanceWsKlineService.this.scheduleReconnect(sub,
                        String.format("closed code=%d reason=%s", code, reason));
            }
        }
    }

    private MdKline parseWsKline(String symbol, String intervalCode, JsonNode k) {
        MdKline kline = new MdKline();
        kline.setSymbol(symbol);
        kline.setIntervalCode(intervalCode);
        kline.setOpenTime(Instant.ofEpochMilli(k.get("t").asLong()).atZone(UTC).toLocalDateTime());
        kline.setCloseTime(Instant.ofEpochMilli(k.get("T").asLong()).atZone(UTC).toLocalDateTime());
        kline.setOpenPrice(new BigDecimal(k.get("o").asText()));
        kline.setHighPrice(new BigDecimal(k.get("h").asText()));
        kline.setLowPrice(new BigDecimal(k.get("l").asText()));
        kline.setClosePrice(new BigDecimal(k.get("c").asText()));
        kline.setVolume(new BigDecimal(k.get("v").asText()));
        return kline;
    }

    /**
     * 排程重連，超過上限才呼叫 stopWithAlert 發告警。
     * backoff: 5s → 15s → 30s → 60s → 60s（最多 MAX_RECONNECT_ATTEMPTS 次）
     */
    private void scheduleReconnect(WsSubscription sub, String reason) {
        if (sub.stoppedByError) return;

        if (sub.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            stopWithAlert(sub, reason);
            return;
        }

        long delayMs = BACKOFF_DELAYS_MS[Math.min(sub.reconnectAttempts, BACKOFF_DELAYS_MS.length - 1)];
        sub.reconnectAttempts++;
        sub.status = "RECONNECTING";
        log.warn("[BinanceWS] Reconnecting ({}/{}) in {}s: {} {}@kline_{} reason={}",
                sub.reconnectAttempts, MAX_RECONNECT_ATTEMPTS, delayMs / 1000,
                sub.marketType, sub.symbol, sub.intervalCode, reason);

        reconnectExecutor.schedule(() -> {
            if (!subscriptions.containsKey(buildKey(sub.symbol, sub.intervalCode, sub.marketType))) {
                return; // 訂閱已被手動移除，不重連
            }
            connect(sub);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ── 訂閱狀態物件 ──────────────────────────────────────────────────────────

    static class WsSubscription {
        final String symbol;
        final String intervalCode;
        final String marketType;
        volatile String status = "INIT";
        volatile LocalDateTime connectedAt;
        volatile long receivedCount;
        volatile WebSocket webSocket;
        volatile boolean stoppedByError;
        volatile int reconnectAttempts = 0;

        WsSubscription(String symbol, String intervalCode, String marketType) {
            this.symbol = symbol;
            this.intervalCode = intervalCode;
            this.marketType = marketType;
        }

        void close() {
            stoppedByError = false;
            status = "STOPPED";
            if (webSocket != null) {
                webSocket.close(1000, "User requested");
            }
        }
    }

    private String normalizeMarketType(String marketType) {
        if (marketType == null || marketType.trim().isEmpty()) {
            return "SPOT";
        }
        String value = marketType.trim().toUpperCase();
        return "FUTURES".equals(value) ? "FUTURES" : "SPOT";
    }

    private static String resolveSpotWsBaseUrl(BinanceMarketDataProperties properties) {
        if (properties == null || properties.getSpotWsBaseUrl() == null || properties.getSpotWsBaseUrl().trim().isEmpty()) {
            return WS_BASE_SPOT;
        }
        return properties.getSpotWsBaseUrl().trim();
    }

    private static String resolveFuturesWsBaseUrl(BinanceMarketDataProperties properties) {
        if (properties == null || properties.getFuturesWsBaseUrl() == null || properties.getFuturesWsBaseUrl().trim().isEmpty()) {
            return WS_BASE_FUTURES;
        }
        return properties.getFuturesWsBaseUrl().trim();
    }

    private String normalizeWsBaseUrl(String value, String defaultValue) {
        String base = (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
        return base.endsWith("/") ? base : (base + "/");
    }
}
