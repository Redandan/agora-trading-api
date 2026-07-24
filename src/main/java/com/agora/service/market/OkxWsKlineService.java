package com.agora.service.market;

import com.agora.dto.market.KlineSubscriptionInfo;
import com.agora.event.KlineClosedEvent;
import com.agora.event.WsReconnectedEvent;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * OKX v5 WebSocket 即時 K 線訂閱（Business channel）。與交易所執行面一致的價源。
 *
 * <p>Spring loads this provider alongside other kline providers. The strategy
 * runtime catalog owns subscription requirements; the provider configuration
 * is only a mechanical allowlist.
 *
 * <p>OKX 協定摘要：
 * <ul>
 *   <li>URL：{@code wss://ws.okx.com:8443/ws/v5/business}</li>
 *   <li>Subscribe：{@code {"op":"subscribe","args":[{"channel":"candle1H","instId":"BTC-USDT"}]}}</li>
 *   <li>資料行：{@code [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]}</li>
 *   <li>{@code confirm="1"} 表示 bar 已收盤；我們只在收盤時存 DB</li>
 *   <li>心跳：每 25 秒送文字 {@code ping}，server 回 {@code pong}</li>
 * </ul>
 */
@Slf4j
@Service
public class OkxWsKlineService implements DisposableBean, KlineStreamService {

    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/business";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long[] BACKOFF_DELAYS_MS = {5_000, 15_000, 30_000, 60_000, 60_000};
    private static final long PING_INTERVAL_SEC = 25;

    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient wsClient;
    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher eventPublisher;
    /** Optional — Spring-wired in production. */
    private MdKlineInsertHelper insertHelper;

    @Autowired(required = false)
    public void setInsertHelper(MdKlineInsertHelper insertHelper) {
        this.insertHelper = insertHelper;
    }

    private final ConcurrentHashMap<String, WsSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "okx-ws-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public OkxWsKlineService(MdKlineRepository klineRepository,
                              ObjectMapper objectMapper,
                              NotificationPort notificationPort,
                              ApplicationEventPublisher eventPublisher) {
        this.klineRepository = klineRepository;
        this.objectMapper = objectMapper;
        this.notificationPort = notificationPort;
        this.eventPublisher = eventPublisher;
        this.wsClient = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)  // 不用 WebSocket ping（OKX 要求文字 "ping"）
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @PostConstruct
    void logProvider() {
        log.info("[OkxWS] Loaded OKX WebSocket kline provider (v5 business channel)");
    }

    @Override
    public String providerName() { return "okx"; }

    // ── 公開 API ──────────────────────────────────────────────────────────────

    @Override
    public KlineSubscriptionInfo subscribe(String symbol, String intervalCode) {
        return subscribe(symbol, intervalCode, "SPOT");
    }

    @Override
    public KlineSubscriptionInfo subscribe(String symbol, String intervalCode, String marketType) {
        String normalizedMarketType = normalizeMarketType(marketType);
        String key = buildKey(symbol, intervalCode, normalizedMarketType);
        WsSubscription existing = subscriptions.get(key);
        if (existing != null) {
            log.info("[OkxWS] Already subscribed: {}", key);
            return toInfo(existing);
        }
        WsSubscription sub = new WsSubscription(symbol.toUpperCase(), intervalCode, normalizedMarketType);
        subscriptions.put(key, sub);
        connect(sub);
        return toInfo(sub);
    }

    @Override
    public boolean unsubscribe(String symbol, String intervalCode) {
        return unsubscribe(symbol, intervalCode, "SPOT");
    }

    @Override
    public boolean unsubscribe(String symbol, String intervalCode, String marketType) {
        WsSubscription sub = subscriptions.remove(
                buildKey(symbol, intervalCode, normalizeMarketType(marketType)));
        if (sub != null) {
            sub.close();
            log.info("[OkxWS] Unsubscribed: {} {}@{}", sub.marketType, symbol, intervalCode);
            return true;
        }
        return false;
    }

    @Override
    public List<KlineSubscriptionInfo> listSubscriptions() {
        return subscriptions.values().stream().map(this::toInfo).collect(Collectors.toList());
    }

    @Override
    public void destroy() {
        log.info("[OkxWS] Shutting down {} subscriptions", subscriptions.size());
        subscriptions.values().forEach(WsSubscription::close);
        subscriptions.clear();
        scheduler.shutdownNow();
        wsClient.dispatcher().executorService().shutdown();
    }

    // ── 私有 ──────────────────────────────────────────────────────────────────

    private void connect(WsSubscription sub) {
        Request request = new Request.Builder().url(WS_URL).build();
        sub.status = "CONNECTING";
        sub.webSocket = wsClient.newWebSocket(request, new OkxKlineWsListener(sub));
        log.debug("[OkxWS] Connecting for {}@{}", sub.symbol, sub.intervalCode);
    }

    private void sendSubscribe(WsSubscription sub) {
        String instId = toOkxInstId(sub.symbol, sub.marketType);
        String channel = "candle" + toOkxBar(sub.intervalCode);
        String msg = String.format(
                "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"%s\",\"instId\":\"%s\"}]}",
                channel, instId);
        if (sub.webSocket != null) {
            sub.webSocket.send(msg);
            log.debug("[OkxWS] Sent subscribe: {}", msg);
        }
    }

    private void schedulePing(WsSubscription sub) {
        scheduler.schedule(() -> {
            if (sub.webSocket != null && !"STOPPED".equals(sub.status)) {
                try {
                    sub.webSocket.send("ping");
                } catch (Exception e) {
                    log.debug("[OkxWS] ping send failed for {}: {}", sub.symbol, e.getMessage());
                }
                schedulePing(sub);
            }
        }, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void persistIfClosed(WsSubscription sub, JsonNode dataRow) {
        if (dataRow == null || dataRow.size() < 9) return;
        if (!"1".equals(dataRow.get(8).asText())) return;  // 未收盤，略過

        try {
            long ts = dataRow.get(0).asLong();
            MdKline k = new MdKline();
            k.setSymbol(sub.symbol);
            k.setIntervalCode(sub.intervalCode);
            k.setOpenTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), UTC));
            k.setOpenPrice(new BigDecimal(dataRow.get(1).asText()));
            k.setHighPrice(new BigDecimal(dataRow.get(2).asText()));
            k.setLowPrice(new BigDecimal(dataRow.get(3).asText()));
            k.setClosePrice(new BigDecimal(dataRow.get(4).asText()));
            k.setVolume(new BigDecimal(dataRow.get(5).asText()));
            k.setCloseTime(k.getOpenTime().plus(intervalDurationMinutes(sub.intervalCode), java.time.temporal.ChronoUnit.MINUTES));
            k.setSource("okx");

            if (!klineRepository.existsBySymbolAndIntervalCodeAndOpenTimeAndSource(
                    k.getSymbol(), k.getIntervalCode(), k.getOpenTime(), "okx")) {
                boolean inserted;
                if (insertHelper != null) {
                    // Production path: INSERT IGNORE — silent on dup, no Hibernate ERROR log
                    inserted = insertHelper.insertIgnore(k);
                } else {
                    // Legacy path (tests only): JPA save with try/catch dup
                    try {
                        klineRepository.save(k);
                        inserted = true;
                    } catch (DataIntegrityViolationException dup) {
                        inserted = false;
                    }
                }
                if (inserted) {
                    sub.receivedCount++;
                    log.info("[OkxWS] Persisted {} {}@{} close={}",
                            sub.symbol, sub.intervalCode, k.getOpenTime(), k.getClosePrice());
                    if (eventPublisher != null) {
                        eventPublisher.publishEvent(new KlineClosedEvent(this, k));
                    }
                } else {
                    // TOCTOU race: another writer (gap detector or parallel WS) won.
                    // INSERT IGNORE handled silently.
                    log.debug("[OkxWS] Race lost (row already persisted) for {}@{} openTime={}",
                            sub.symbol, sub.intervalCode, k.getOpenTime());
                }
            }
        } catch (Exception e) {
            log.warn("[OkxWS] Persist failed for {}@{}: {}", sub.symbol, sub.intervalCode, e.getMessage());
        }
    }

    private void scheduleReconnect(WsSubscription sub) {
        if (sub.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            stopWithAlert(sub, "max reconnect attempts reached");
            return;
        }
        long delay = BACKOFF_DELAYS_MS[Math.min(sub.reconnectAttempts, BACKOFF_DELAYS_MS.length - 1)];
        sub.reconnectAttempts++;
        sub.status = "RECONNECTING";
        scheduler.schedule(() -> {
            log.info("[OkxWS] Reconnecting {}@{} (attempt {})",
                    sub.symbol, sub.intervalCode, sub.reconnectAttempts);
            connect(sub);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void stopWithAlert(WsSubscription sub, String reason) {
        sub.stoppedByError = true;
        sub.status = "STOPPED";
        log.error("[OkxWS] Stopping {}@{}: {}", sub.symbol, sub.intervalCode, reason);
        if (notificationPort != null) {
            try {
                notificationPort.broadcast(MarketDataTelegramAlertFormatter.wsStopped(
                        providerName(), sub.marketType, sub.symbol, sub.intervalCode, reason), true);
            } catch (Exception ignored) {}
        }
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

    private String buildKey(String symbol, String intervalCode, String marketType) {
        return symbol.toUpperCase() + ":" + intervalCode + ":" + marketType;
    }

    private String normalizeMarketType(String marketType) {
        if (marketType == null) return "SPOT";
        return "FUTURES".equalsIgnoreCase(marketType) ? "FUTURES" : "SPOT";
    }

    /** BTCUSDT / SPOT → BTC-USDT；BTCUSDT / FUTURES → BTC-USDT-SWAP */
    private String toOkxInstId(String symbol, String marketType) {
        String base;
        if (symbol.endsWith("USDT")) base = symbol.substring(0, symbol.length() - 4) + "-USDT";
        else if (symbol.endsWith("BUSD")) base = symbol.substring(0, symbol.length() - 4) + "-BUSD";
        else base = symbol;
        return "FUTURES".equals(marketType) ? base + "-SWAP" : base;
    }

    /** 1h → 1H, 4h → 4H, 1d → 1D; 1m/15m 保持小寫 */
    private String toOkxBar(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("h") || code.endsWith("d")) return code.toUpperCase(Locale.ROOT);
        return code;
    }

    private long intervalDurationMinutes(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("m")) return Long.parseLong(code.substring(0, code.length() - 1));
        if (code.endsWith("h")) return Long.parseLong(code.substring(0, code.length() - 1)) * 60;
        if (code.endsWith("d")) return Long.parseLong(code.substring(0, code.length() - 1)) * 60 * 24;
        return 60;
    }

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
            if (webSocket != null) webSocket.close(1000, "User requested");
        }
    }

    private class OkxKlineWsListener extends WebSocketListener {
        private final WsSubscription sub;

        OkxKlineWsListener(WsSubscription sub) { this.sub = sub; }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            sub.status = "RUNNING";
            sub.connectedAt = LocalDateTime.now(UTC);
            sub.reconnectAttempts = 0;
            log.info("[OkxWS] Connected: {} {}@{}", sub.marketType, sub.symbol, sub.intervalCode);
            sendSubscribe(sub);
            schedulePing(sub);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new WsReconnectedEvent(OkxWsKlineService.this, sub.symbol, sub.intervalCode));
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            if ("pong".equals(text)) return;  // 心跳回應
            try {
                JsonNode root = objectMapper.readTree(text);
                if (root.has("event")) {
                    String event = root.path("event").asText();
                    if ("subscribe".equals(event)) {
                        log.debug("[OkxWS] Subscribed: {}@{}", sub.symbol, sub.intervalCode);
                    } else if ("error".equals(event)) {
                        log.warn("[OkxWS] Server error: {}", text);
                    }
                    return;
                }
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    for (JsonNode row : data) persistIfClosed(sub, row);
                }
            } catch (Exception e) {
                log.warn("[OkxWS] Parse failed: {} -- {}", e.getMessage(),
                        text.length() > 200 ? text.substring(0, 200) + "..." : text);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.warn("[OkxWS] WS failure {}@{}: {}", sub.symbol, sub.intervalCode, t.getMessage());
            if (!"STOPPED".equals(sub.status)) scheduleReconnect(sub);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("[OkxWS] Closed {}@{}: code={} reason={}", sub.symbol, sub.intervalCode, code, reason);
            if (!"STOPPED".equals(sub.status)) scheduleReconnect(sub);
        }
    }
}
