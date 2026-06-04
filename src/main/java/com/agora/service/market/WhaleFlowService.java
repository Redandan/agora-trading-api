package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 從 OKX taker-volume 端點計算近期 taker buy/sell 比例，作為市場主動買賣方向的代理指標。
 *
 * <p>OKX 端點 {@code /api/v5/rubik/stat/taker-volume} 直接給每 5 分鐘的 taker 買賣總量
 * （不需用大單門檻篩選），相較於 Binance.us aggTrades 的低流動性能提供更穩定的訊號。
 *
 * <p>聚合最近 {@code AGG_PERIODS} 個 5 分鐘 bucket（預設 6 = 30 分鐘）以平滑短期雜訊。
 *
 * <p>API 失敗時回傳中性值 0.5，不影響訊號評估。快取 5 分鐘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhaleFlowService {

    private static final String OKX_BASE = "https://www.okx.com";
    private static final int AGG_PERIODS = 6;  // 6 × 5m = 30 分鐘聚合
    private static final double DEFAULT_NEUTRAL_RATIO = 0.5;

    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    /**
     * 取得指定交易對近 30 分鐘的 taker buy 比例（0.0~1.0）。
     * 快取 5 分鐘；API 失敗或資料不足時回傳中性值 0.5。
     */
    @Cacheable(value = "whaleBuyRatio", key = "#symbol")
    public double getBuyRatio(String symbol) {
        String ccy = symbol.replace("USDT", "").replace("BUSD", "");
        String url = OKX_BASE + "/api/v5/rubik/stat/taker-volume?ccy=" + ccy
                + "&instType=SPOT&period=5m";
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[WhaleFlow] OKX HTTP {} for {}", response.code(), symbol);
                    return DEFAULT_NEUTRAL_RATIO;
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                if (!"0".equals(root.path("code").asText())) {
                    log.warn("[WhaleFlow] OKX error for {}: {}", symbol, root.path("msg").asText());
                    return DEFAULT_NEUTRAL_RATIO;
                }
                JsonNode data = root.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    log.warn("[WhaleFlow] OKX empty data for {}", symbol);
                    return DEFAULT_NEUTRAL_RATIO;
                }

                // 格式：[[ts, sellVol, buyVol], ...]，第 0 筆為最新
                double buyVol = 0.0;
                double sellVol = 0.0;
                int periods = Math.min(AGG_PERIODS, data.size());
                for (int i = 0; i < periods; i++) {
                    JsonNode row = data.get(i);
                    sellVol += row.get(1).asDouble(0);
                    buyVol  += row.get(2).asDouble(0);
                }

                double total = buyVol + sellVol;
                if (total == 0) {
                    log.info("[WhaleFlow] symbol={} zero volume in {} periods, returning neutral 0.5",
                            symbol, periods);
                    return DEFAULT_NEUTRAL_RATIO;
                }
                double ratio = buyVol / total;
                log.info("[WhaleFlow] symbol={} buyRatio={} (n={} periods, buy={} sell={} units)",
                        symbol, String.format("%.2f", ratio), periods,
                        String.format("%.2f", buyVol), String.format("%.2f", sellVol));
                return ratio;
            }
        } catch (Exception e) {
            log.warn("[WhaleFlow] Failed to fetch {}, using neutral 0.5: {}", symbol, e.getMessage());
            return DEFAULT_NEUTRAL_RATIO;
        }
    }
}
