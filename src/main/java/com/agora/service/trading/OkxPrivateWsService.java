package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.scheduler.trading.OcoPositionPollerScheduler;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.WsConnectionDiagnostics;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OKX Private WebSocket 服務：訂閱 algo-orders 頻道，即時偵測 OCO 止盈/止損成交。
 *
 * <p>相比每 10 分鐘的 REST polling，WS 推送可將成交偵測延遲從最高 10 分鐘縮短至秒級。
 * polling 保留為 fallback，確保 WS 斷線期間也不會遺漏成交事件。</p>
 *
 * <p>連線流程：connect → onOpen: login → 收到 event=login,code=0 → subscribe
 * orders-algo(ANY) on business endpoint → 持續收推送 → 斷線時 exponential backoff 重連。</p>
 */
@Slf4j
@Service
public class OkxPrivateWsService implements DisposableBean {

    // algo-order fills (OCO / TP-SL) are pushed on the "business" endpoint
    private static final String WS_URL = "wss://ws.okx.com:8443/ws/v5/business";
    private static final long   PING_INTERVAL_S = 25;
    private static final long[] BACKOFF_DELAYS_S = {5, 15, 30, 60, 120, 120};

    private final OkxTradingProperties      tradingProperties;
    private final OcoPositionPollerScheduler ocoPollerScheduler;
    private final ObjectMapper               objectMapper;
    private final NotificationPort           notificationPort;
    private final OkHttpClient               wsClient;

    @Value("${trading.oco-poller.enabled:false}")
    private boolean ocoPollerEnabled;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "okx-private-ws");
                t.setDaemon(true);
                return t;
            });

    private volatile WebSocket      activeWs     = null;
    private final AtomicBoolean     loggedIn     = new AtomicBoolean(false);
    private final AtomicBoolean     destroyed    = new AtomicBoolean(false);
    private final AtomicInteger     reconnectIdx = new AtomicInteger(0);
    private volatile ScheduledFuture<?> pingTask = null;
    private ScheduledFuture<?> reconnectTask;
    private boolean outageAlerted;
    private final WsConnectionDiagnostics diagnostics = new WsConnectionDiagnostics();

    public OkxPrivateWsService(OkxTradingProperties tradingProperties,
                                OcoPositionPollerScheduler ocoPollerScheduler,
                                ObjectMapper objectMapper,
                                NotificationPort notificationPort) {
        this.tradingProperties  = tradingProperties;
        this.ocoPollerScheduler = ocoPollerScheduler;
        this.objectMapper       = objectMapper;
        this.notificationPort   = notificationPort;
        this.wsClient = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)   // 手動發 ping
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @PostConstruct
    public void init() {
        if (!ocoPollerEnabled) {
            log.info("[OkxPrivateWs] OCO poller disabled — private WS skipped");
            return;
        }
        if (!tradingProperties.isEnabled()) {
            log.info("[OkxPrivateWs] Trading disabled — private WS skipped");
            return;
        }
        String key = tradingProperties.getApiKey();
        if (key == null || key.isBlank()) {
            log.warn("[OkxPrivateWs] No API key — private WS skipped");
            return;
        }
        connect();
    }

    // ──────────────────────────────────────────────
    //  連線 / 認證 / 訂閱
    // ──────────────────────────────────────────────

    private synchronized void connect() {
        if (destroyed.get()) return;
        log.info("[OkxPrivateWs] Connecting to {}", WS_URL);
        Request req = new Request.Builder().url(WS_URL).build();
        activeWs = wsClient.newWebSocket(req, new OkxWsListener());
    }

    private void sendLogin(WebSocket ws) {
        try {
            long   ts      = Instant.now().getEpochSecond();
            String preSign = ts + "GET" + "/users/self/verify";
            String sign    = hmacSha256(tradingProperties.getSecretKey(), preSign);
            String msg = String.format(
                    "{\"op\":\"login\",\"args\":[{\"apiKey\":\"%s\",\"passphrase\":\"%s\"," +
                    "\"timestamp\":\"%d\",\"sign\":\"%s\"}]}",
                    tradingProperties.getApiKey(), tradingProperties.getPassphrase(), ts, sign);
            ws.send(msg);
            log.debug("[OkxPrivateWs] Login sent (ts={})", ts);
        } catch (Exception e) {
            log.error("[OkxPrivateWs] Failed to build login message: {}", e.getMessage());
        }
    }

    private void sendSubscribe(WebSocket ws) {
        // orders-algo covers all TP-SL / OCO algo orders; instType=ANY catches SPOT + SWAP
        String msg = "{\"op\":\"subscribe\",\"args\":[" +
                "{\"channel\":\"orders-algo\",\"instType\":\"ANY\"}" +
                "]}";
        ws.send(msg);
        log.info("[OkxPrivateWs] Subscribed orders-algo (instType=ANY)");
    }

    private void schedulePing(WebSocket ws) {
        cancelPing();
        pingTask = executor.scheduleAtFixedRate(() -> {
            if (activeWs == ws && !destroyed.get()) {
                ws.send("ping");
                log.trace("[OkxPrivateWs] ping →");
            }
        }, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void cancelPing() {
        ScheduledFuture<?> t = pingTask;
        if (t != null) t.cancel(false);
        pingTask = null;
    }

    // ──────────────────────────────────────────────
    //  重連
    // ──────────────────────────────────────────────

    private void scheduleReconnect() {
        if (destroyed.get() || (reconnectTask != null && !reconnectTask.isDone())) return;
        int idx       = reconnectIdx.getAndIncrement();
        long delaySec = BACKOFF_DELAYS_S[Math.min(idx, BACKOFF_DELAYS_S.length - 1)];
        log.info("[OkxPrivateWs] Reconnecting in {}s (attempt {})", delaySec, idx + 1);

        // When we've exhausted the backoff table, alert via TG and reset index to
        // keep retrying at the max interval (120 s) indefinitely.
        if (idx >= BACKOFF_DELAYS_S.length && !outageAlerted) {
            outageAlerted = true;
            reconnectIdx.set(BACKOFF_DELAYS_S.length - 1); // keep using last slot
            try {
                notificationPort.broadcast(
                        "⚠️ <b>OKX Private WS 無法重連</b>\n" +
                        "已嘗試 " + idx + " 次仍失敗，將持續每 120 秒重試。\n" +
                        "請檢查 API Key 或網路狀態。", true);
            } catch (Exception e) {
                log.warn("[OkxPrivateWs] Failed to send TG alert: {}", e.getMessage());
            }
        }

        reconnectTask = executor.schedule(() -> {
            synchronized (OkxPrivateWsService.this) {
                reconnectTask = null;
                loggedIn.set(false);
                connect();
            }
        }, delaySec, TimeUnit.SECONDS);
    }

    // ──────────────────────────────────────────────
    //  工具
    // ──────────────────────────────────────────────

    private String hmacSha256(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public synchronized void destroy() {
        if (reconnectTask != null) reconnectTask.cancel(false);
        reconnectTask = null;
        destroyed.set(true);
        cancelPing();
        WebSocket ws = activeWs;
        if (ws != null) ws.close(1000, "shutdown");
        executor.shutdownNow();
        log.info("[OkxPrivateWs] Destroyed");
    }

    // ──────────────────────────────────────────────
    //  WebSocket 監聽器
    // ──────────────────────────────────────────────

    private class OkxWsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            synchronized (OkxPrivateWsService.this) {
                if (activeWs != ws || destroyed.get()) { ws.cancel(); return; }
                log.info("[OkxPrivateWs] Connected — sending login");
                sendLogin(ws);
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            if (activeWs != ws || destroyed.get()) return;
            diagnostics.received(Instant.now());
            if ("pong".equals(text)) {
                log.trace("[OkxPrivateWs] ← pong");
                return;
            }
            try {
                JsonNode node = objectMapper.readTree(text);

                // ── 事件訊息（login / subscribe / error）─────────────────
                String event = node.path("event").asText("");
                if (!event.isEmpty()) {
                    handleEvent(ws, event, node);
                    return;
                }

                // ── 資料推送 ──────────────────────────────────────────────
                String channel = node.path("arg").path("channel").asText("");
                if ("orders-algo".equals(channel)) {
                    handleAlgoOrders(node.path("data"));
                }

            } catch (Exception e) {
                log.error("[OkxPrivateWs] Parse error: {} | raw={}", e.getMessage(), text);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(code, reason);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            synchronized (OkxPrivateWsService.this) {
                if (activeWs != ws || destroyed.get()) return;
                diagnostics.failed(t, Instant.now());
                log.warn("[OkxPrivateWs] Connection failure: {}",
                        diagnostics.failureContext(response == null ? null : response.code()));
                activeWs = null;
                loggedIn.set(false);
                cancelPing();
                scheduleReconnect();
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            synchronized (OkxPrivateWsService.this) {
                if (activeWs != ws || destroyed.get()) return;
                diagnostics.disconnected(Instant.now());
                log.info("[OkxPrivateWs] Closed: code={}", code);
                activeWs = null;
                loggedIn.set(false);
                cancelPing();
                scheduleReconnect();
            }
        }
    }

    // ──────────────────────────────────────────────
    //  事件處理
    // ──────────────────────────────────────────────

    private synchronized void handleEvent(WebSocket ws, String event, JsonNode node) {
        if (activeWs != ws || destroyed.get()) return;
        switch (event) {
            case "login" -> {
                String code = node.path("code").asText("");
                if ("0".equals(code)) {
                    log.info("[OkxPrivateWs] Login OK — subscribing");
                    loggedIn.set(true);
                    sendSubscribe(ws);
                    schedulePing(ws);
                } else {
                    log.error("[OkxPrivateWs] Login FAILED: {}", node);
                }
            }
            case "subscribe" -> {
                reconnectIdx.set(0);
                outageAlerted = false;
                log.info("[OkxPrivateWs] Subscription confirmed: {} downtimeMs={}",
                        node.path("arg"), diagnostics.ready(Instant.now()));
            }
            case "error" ->
                log.error("[OkxPrivateWs] Server error: {}", node);
            default ->
                log.debug("[OkxPrivateWs] Unknown event: {}", event);
        }
    }

    // ──────────────────────────────────────────────
    //  algo-orders 推送
    // ──────────────────────────────────────────────

    private void handleAlgoOrders(JsonNode dataArr) {
        for (JsonNode item : dataArr) {
            String state   = item.path("state").asText("");
            String algoStr = item.path("algoId").asText("");
            String instId  = item.path("instId").asText("");

            if (!"filled".equals(state) && !"canceled".equals(state)) continue;
            if (algoStr.isBlank()) continue;

            try {
                Long algoId = Long.parseLong(algoStr);
                log.info("[OkxPrivateWs] algo-orders push: instId={} algoId={} state={}",
                        instId, algoId, state);
                ocoPollerScheduler.handleAlgoFillPush(algoId);
            } catch (NumberFormatException e) {
                log.warn("[OkxPrivateWs] Cannot parse algoId={}", algoStr);
            }
        }
    }
}
