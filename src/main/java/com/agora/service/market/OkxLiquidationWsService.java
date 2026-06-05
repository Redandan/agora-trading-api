package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OKX Public WebSocket — 即時爆倉 + 現貨成交數據
 *
 * 訂閱：
 *   liquidation-orders (instType=SWAP) — 合約空頭爆倉（side="sell"）
 *   trades (instId=BTC-USDT)           — 現貨成交，維護最新 BTC 價格
 *
 * ⚠️ side 字段：
 *   "sell" = 空頭被爆（原持空頭，被強制買入平倉）← 軋空用這個
 *   "buy"  = 多頭被爆（原持多頭，被強制賣出平倉）← 不是軋空
 *
 * ⚠️ 爆倉 USD：sz × currentBtcPrice（不用 bkPx，破產價格有誤差）
 *
 * ⚠️ OKX 公開 WS 爆倉有限流/採樣，非全量，用相對變化幅度而非絕對值。
 */
@Slf4j
@Service
public class OkxLiquidationWsService implements DisposableBean {

    private static final String WS_URL      = "wss://ws.okx.com:8443/ws/v5/public";
    private static final long   PING_SEC    = 25;
    private static final long   WINDOW_MS   = 30 * 60 * 1000L;     // 保留 30 分鐘事件
    private static final long[] BACKOFF_MS  = {5_000, 15_000, 30_000, 60_000, 60_000};
    private static final int    MAX_RETRY   = 5;

    private static final String INST_SPOT  = "BTC-USDT";
    private static final String INST_SWAP  = "BTC-USDT-SWAP";

    // ── 滾動窗口 ──
    private final ConcurrentLinkedDeque<LiqEvent>   shortLiqWindow = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<TradeEvent> tradeWindow    = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Double>           btcPrice       = new AtomicReference<>(0.0);
    private final AtomicLong lastMessageTs = new AtomicLong(0);

    // ── 連線狀態 ──
    private volatile WebSocket webSocket;
    private volatile String    status = "INIT";
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    @Getter private volatile boolean degraded = false;

    private final ObjectMapper objectMapper;
    private final OkHttpClient wsClient;
    private final boolean enabled;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "okx-liq-ws-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public OkxLiquidationWsService(
            ObjectMapper objectMapper,
            @Value("${market.liquidation-ws.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.wsClient = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            status = "DISABLED";
            log.info("[OkxLiqWS] disabled by market.liquidation-ws.enabled=false");
            return;
        }
        log.info("[OkxLiqWS] Starting liquidation + trades WebSocket");
        connect();
    }

    // ── 公開 API ──────────────────────────────────────────────────────────────

    /** 過去 N 分鐘空頭爆倉 USD 總額 */
    public double getShortLiqUsd(int minutes) {
        long cutoff = System.currentTimeMillis() - (long) minutes * 60 * 1000;
        return shortLiqWindow.stream()
                .filter(e -> e.ts >= cutoff)
                .mapToDouble(e -> e.usd)
                .sum();
    }

    /** 過去 N 分鐘現貨主動買單 USD 總額 */
    public double getTakerBuyUsd(int minutes) {
        long cutoff = System.currentTimeMillis() - (long) minutes * 60 * 1000;
        return tradeWindow.stream()
                .filter(e -> e.ts >= cutoff && e.isBuy)
                .mapToDouble(e -> e.usd)
                .sum();
    }

    public double getCurrentBtcPrice() { return btcPrice.get(); }
    public boolean isConnected()       { return "RUNNING".equals(status); }

    /** 健康檢查：距上次收到任何訊息超過 90 秒視為失聯 */
    public boolean isStale() {
        long last = lastMessageTs.get();
        return last > 0 && System.currentTimeMillis() - last > 90_000;
    }

    // ── 定時維護 ──────────────────────────────────────────────────────────────

    /** 每 30 秒清除過期事件，防記憶體洩漏 */
    @Scheduled(fixedDelay = 30_000)
    public void evictExpiredEvents() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        shortLiqWindow.removeIf(e -> e.ts < cutoff);
        tradeWindow.removeIf(e -> e.ts < cutoff);
    }

    /** 每分鐘檢查連線健康 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void healthCheck() {
        if (!enabled) return;
        if (isStale()) {
            log.warn("[OkxLiqWS] No message for 90s, reconnecting...");
            degraded = true;
            reconnect();
        } else {
            degraded = false;
        }
    }

    // ── 連線邏輯 ──────────────────────────────────────────────────────────────

    private void connect() {
        if (!enabled) return;
        status = "CONNECTING";
        Request request = new Request.Builder().url(WS_URL).build();
        webSocket = wsClient.newWebSocket(request, new LiqWsListener());
    }

    private void reconnect() {
        int attempt = reconnectAttempts.getAndIncrement();
        if (attempt >= MAX_RETRY) {
            status = "STOPPED";
            log.error("[OkxLiqWS] Max reconnect attempts reached, giving up");
            return;
        }
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        status = "RECONNECTING";
        reconnectScheduler.schedule(() -> {
            log.info("[OkxLiqWS] Reconnecting (attempt {})", attempt + 1);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sendSubscriptions() {
        // 訂閱合約爆倉
        send("{\"op\":\"subscribe\",\"args\":[{\"channel\":\"liquidation-orders\",\"instType\":\"SWAP\"}]}");
        // 訂閱現貨成交（維護 BTC 現價）
        send("{\"op\":\"subscribe\",\"args\":[{\"channel\":\"trades\",\"instId\":\"" + INST_SPOT + "\"}]}");
    }

    private void schedulePing() {
        reconnectScheduler.schedule(() -> {
            if (webSocket != null && "RUNNING".equals(status)) {
                send("ping");
                schedulePing();
            }
        }, PING_SEC, TimeUnit.SECONDS);
    }

    private void send(String msg) {
        try {
            if (webSocket != null) webSocket.send(msg);
        } catch (Exception e) {
            log.debug("[OkxLiqWS] send failed: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        status = "STOPPED";
        if (webSocket != null) webSocket.close(1000, "Shutdown");
        reconnectScheduler.shutdownNow();
        wsClient.dispatcher().executorService().shutdown();
    }

    // ── WebSocket Listener ─────────────────────────────────────────────────────

    private class LiqWsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            status = "RUNNING";
            degraded = false;
            reconnectAttempts.set(0);
            lastMessageTs.set(System.currentTimeMillis());
            log.info("[OkxLiqWS] Connected");
            sendSubscriptions();
            schedulePing();
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            if ("pong".equals(text)) { lastMessageTs.set(System.currentTimeMillis()); return; }
            lastMessageTs.set(System.currentTimeMillis());
            try {
                JsonNode root = objectMapper.readTree(text);
                if (root.has("event")) return; // subscribe ack, ignore

                String channel = root.path("arg").path("channel").asText();
                JsonNode data  = root.path("data");
                if (!data.isArray()) return;

                if ("liquidation-orders".equals(channel)) {
                    handleLiquidation(data);
                } else if ("trades".equals(channel)) {
                    handleTrades(data);
                }
            } catch (Exception e) {
                log.debug("[OkxLiqWS] parse error: {}", e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.warn("[OkxLiqWS] WS failure: {}", t.getMessage());
            if (!"STOPPED".equals(status)) reconnect();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("[OkxLiqWS] Closed: code={} reason={}", code, reason);
            if (!"STOPPED".equals(status)) reconnect();
        }
    }

    // ── 事件處理 ──────────────────────────────────────────────────────────────

    private void handleLiquidation(JsonNode data) {
        double price = btcPrice.get();
        if (price <= 0) return;

        for (JsonNode item : data) {
            // ⚠️ side="sell" = 空頭被爆（做空者被強制平倉）← 這才是軋空
            // ⚠️ side="buy"  = 多頭被爆 ← 不統計
            if (!"sell".equals(item.path("side").asText())) continue;
            if (!item.path("instId").asText().contains("BTC")) continue;

            double sz  = item.path("sz").asDouble();
            long   ts  = item.path("ts").asLong();
            if (ts <= 0) ts = System.currentTimeMillis();

            double usd = sz * price;  // sz × 當前 BTC 市價（不用 bkPx）
            shortLiqWindow.addLast(new LiqEvent(usd, ts));
            log.debug("[OkxLiqWS] Short liq: sz={} usd={} ts={}", sz, usd, ts);
        }
    }

    private void handleTrades(JsonNode data) {
        for (JsonNode trade : data) {
            double px  = trade.path("px").asDouble();
            double sz  = trade.path("sz").asDouble();
            boolean buy = "buy".equals(trade.path("side").asText());
            long ts    = trade.path("ts").asLong();
            if (ts <= 0) ts = System.currentTimeMillis();

            // 維護最新 BTC 現價
            if (px > 0) btcPrice.set(px);

            tradeWindow.addLast(new TradeEvent(px * sz, buy, ts));
        }
    }

    // ── 事件 DTO ──────────────────────────────────────────────────────────────

    record LiqEvent(double usd, long ts) {}
    record TradeEvent(double usd, boolean isBuy, long ts) {}
}
