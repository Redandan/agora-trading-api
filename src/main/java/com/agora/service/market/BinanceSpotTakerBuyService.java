package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Fetches BTCUSDT spot taker buy volume via OKX public market trades API.
 * side=buy means the taker is a buyer (active buy order).
 * Sums px*sz for all buy-side trades in the past 15 minutes.
 * OKX public API requires no key and is accessible from the server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceSpotTakerBuyService {

    private final ObjectMapper objectMapper;

    @Value("${trading.okx.base-url:https://www.okx.com}")
    private String okxBaseUrl;

    public static final String INDICATOR = "spot_taker_buy_usd_15m";
    private static final int WINDOW_MINUTES = 15;
    private static final int LIMIT = 500;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public double fetchTakerBuyUsd(String symbol) throws Exception {
        // OKX uses BTC-USDT format (not BTCUSDT)
        String instId = symbol.replace("USDT", "-USDT");
        long cutoffMs = System.currentTimeMillis() - (long) WINDOW_MINUTES * 60 * 1000;

        String url = okxBaseUrl + "/api/v5/market/trades?instId=" + instId + "&limit=" + LIMIT;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("OKX market/trades HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) {
                throw new RuntimeException("OKX unexpected response: " + root.path("msg").asText());
            }

            double takerBuyUsd = 0;
            for (JsonNode trade : data) {
                long ts = trade.path("ts").asLong();
                if (ts < cutoffMs) continue; // older than 15 min window
                if ("buy".equals(trade.path("side").asText())) {
                    double px = trade.path("px").asDouble();
                    double sz = trade.path("sz").asDouble();
                    takerBuyUsd += px * sz;
                }
            }
            return takerBuyUsd;
        }
    }
}
