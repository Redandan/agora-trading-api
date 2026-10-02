package com.agora.service.market;

import com.agora.config.OkxTradingProperties;
import com.agora.service.trading.OkxPrivateWsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.ByteString;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real listeners and executors, fake sockets. No init, HTTP, DB or notifications. */
class WsLifecycleTest {
    @Test
    void okxCancelRemovesReconnectAndLateCallbacksCannotReviveSubscription() throws Exception {
        try (var f = market(true)) { checkCancelled(f); }
    }

    @Test
    void binanceCancelRemovesReconnectAndLateCallbacksCannotReviveSubscription() throws Exception {
        try (var f = market(false)) { checkCancelled(f); }
    }

    @Test
    void oldMarketSocketsCannotReplaceNewSocketOrCreateAnotherReconnect() throws Exception {
        for (boolean okx : new boolean[]{true, false}) {
            try (var f = market(okx)) {
                var newer = new FakeSocket();
                set(f.sub, "webSocket", newer);
                set(f.sub, "status", "RUNNING");
                f.listener.onFailure(f.socket, new java.io.EOFException(), null);
                f.listener.onClosed(f.socket, 1000, "old");
                f.listener.onOpen(f.socket, null);
                assertSame(newer, get(f.sub, "webSocket"));
                assertEquals("RUNNING", get(f.sub, "status"));
                assertNull(get(f.sub, "reconnectTask"));
                assertTrue(f.socket.cancelled);
            }
        }
    }

    @Test
    void privateSocketStaleCallbacksCannotClearReplacementOrScheduleDuplicateReconnects() throws Exception {
        var service = new OkxPrivateWsService(new OkxTradingProperties(), null, new ObjectMapper(), null);
        try {
            var listener = listener(service, "OkxWsListener");
            var old = new FakeSocket();
            var current = new FakeSocket();
            set(service, "activeWs", current);
            ((AtomicBoolean) get(service, "loggedIn")).set(true);
            listener.onFailure(old, new java.io.EOFException(), null);
            listener.onClosed(old, 1000, "old");
            assertSame(current, get(service, "activeWs"));
            assertTrue(((AtomicBoolean) get(service, "loggedIn")).get());
            assertNull(get(service, "reconnectTask"));
            listener.onFailure(current, new java.io.EOFException(), null);
            var retry = (ScheduledFuture<?>) get(service, "reconnectTask");
            listener.onClosed(current, 1000, "duplicate");
            assertSame(retry, get(service, "reconnectTask"));
            service.destroy();
            assertTrue(retry.isCancelled());
            listener.onOpen(current, null);
            assertTrue(current.cancelled);
        } finally { service.destroy(); }
    }

    private void checkCancelled(Fixture f) throws Exception {
        f.listener.onFailure(f.socket, new java.io.EOFException(), null);
        var retry = (ScheduledFuture<?>) get(f.sub, "reconnectTask");
        assertNotNull(retry);
        f.listener.onClosed(f.socket, 1000, "duplicate");
        assertSame(retry, get(f.sub, "reconnectTask"));
        assertTrue(f.service.unsubscribe("BTCUSDT", f.interval, "SPOT"));
        assertTrue(retry.isCancelled());
        f.listener.onOpen(f.socket, null);
        assertEquals("STOPPED", get(f.sub, "status"));
        assertTrue(f.socket.cancelled);
        // Even an already dequeued reconnect must re-check the exact subscription.
        var connect = f.service.getClass().getDeclaredMethod("connect", f.sub.getClass());
        connect.setAccessible(true);
        connect.invoke(f.service, f.sub);
        assertEquals("STOPPED", get(f.sub, "status"));
    }

    @SuppressWarnings("unchecked")
    private Fixture market(boolean okx) throws Exception {
        var mapper = new ObjectMapper();
        KlineStreamService service = okx ? new OkxWsKlineService(null, mapper, null, null)
                : new BinanceWsKlineService(null, mapper, new OkHttpClient());
        String interval = okx ? "1h" : "1d";
        Object sub = okx ? new OkxWsKlineService.WsSubscription("BTCUSDT", interval, "SPOT",
                new WsHeartbeat((ScheduledExecutorService) get(service, "scheduler"), 86400))
                : new BinanceWsKlineService.WsSubscription("BTCUSDT", interval, "SPOT");
        var socket = new FakeSocket();
        set(sub, "webSocket", socket);
        set(sub, "status", "RUNNING");
        ((Map<String, Object>) get(service, "subscriptions")).put(
                okx ? "BTCUSDT:1h:SPOT" : "SPOT:BTCUSDT:1d", sub);
        return new Fixture(service, sub, socket, listener(service,
                okx ? "OkxKlineWsListener" : "KlineWsListener", sub), interval);
    }

    private record Fixture(KlineStreamService service, Object sub, FakeSocket socket,
                           WebSocketListener listener, String interval) implements AutoCloseable {
        public void close() throws Exception { ((org.springframework.beans.factory.DisposableBean) service).destroy(); }
    }

    private static WebSocketListener listener(Object service, String name, Object... args) throws Exception {
        for (var cls : service.getClass().getDeclaredClasses()) {
            if (!cls.getSimpleName().equals(name)) continue;
            var ctor = cls.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] all = new Object[args.length + 1];
            all[0] = service;
            System.arraycopy(args, 0, all, 1, args.length);
            return (WebSocketListener) ctor.newInstance(all);
        }
        throw new AssertionError(name);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static class FakeSocket implements WebSocket {
        boolean cancelled;
        public Request request() { return new Request.Builder().url("https://fixture.invalid").build(); }
        public long queueSize() { return 0; }
        public boolean send(String text) { return true; }
        public boolean send(ByteString bytes) { return true; }
        public boolean close(int code, String reason) { return true; }
        public void cancel() { cancelled = true; }
    }
}
