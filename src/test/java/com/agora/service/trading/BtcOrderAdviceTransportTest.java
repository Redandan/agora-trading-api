package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceTransportTest {
    @Test void adviceProviderAdapterUsesOnlyFixedGetEndpointsAndNoCachedBalanceFallback() throws Exception {
        List<String> paths = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            boolean repeatBalance = paths.stream().filter(s -> s.contains("/account/balance")).count() > 1;
            String body = repeatBalance ? "{\"code\":\"50000\",\"msg\":\"test failure\",\"data\":[]}"
                    : exchange.getRequestURI().getPath().endsWith("/instruments")
                    ? "{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDT\",\"state\":\"live\"}]}"
                    : "{\"code\":\"0\",\"data\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var props = new OkxTradingProperties(); props.setEnabled(false);
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.setApiKey("local-test-only"); props.setSecretKey("local-test-only"); props.setPassphrase("local-test-only");
            var okx = new OkxTradingService(props, new ObjectMapper()); okx.init();
            okx.getBtcAdvicePendingOrders(null);
            for (String type : List.of("oco", "conditional", "trigger", "move_order_stop")) okx.getBtcAdvicePendingOrders(type);
            okx.getBtcAdviceInstrument(); okx.getBtcAdviceBalances();
            assertThrows(RuntimeException.class, okx::getBtcAdviceBalances);
            assertThrows(IllegalArgumentException.class, () -> okx.getBtcAdvicePendingOrders("oco&unsafe=true"));
            assertEquals(8, paths.size()); assertTrue(paths.stream().allMatch(p -> p.startsWith("GET ")));
            assertEquals("GET /api/v5/trade/orders-pending?instType=SPOT&instId=BTC-USDT&limit=100", paths.getFirst());
        } finally { server.stop(0); }
    }
}
