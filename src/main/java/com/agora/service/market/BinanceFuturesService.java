package com.agora.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Futures open-interest service.
 *
 * <p>Tries Binance Futures first; falls back to OKX public SWAP API when Binance
 * returns a geo-block (HTTP 451) or any other non-2xx. OKX public endpoints have
 * no IP geo-restriction and require no API key.
 *
 * <h2>Provided indicators</h2>
 * <ul>
 *   <li>{@link #getOpenInterest(String)} — perpetual-swap open interest in base
 *       currency units (e.g. BTC for BTCUSDT). Written to
 *       {@code market_indicator_history} as {@code btc_open_interest} once per hour
 *       by {@link com.agora.scheduler.trading.MarketIndicatorHistoryCollector}.</li>
 * </ul>
 *
 * <h2>Symbol mapping (Binance → OKX)</h2>
 * <pre>
 *   BTCUSDT → BTC-USDT-SWAP
 *   ETHUSDT → ETH-USDT-SWAP
 * </pre>
 *
 * <h2>Why OI matters for ML</h2>
 * Rising OI in a downtrend = new shorts opening (conviction bear). Falling OI =
 * shorts covering (potential reversal). Combined with {@code oi_change_pct_1h} the
 * model can distinguish "quiet consolidation" from "leveraged positioning".
 */
@Slf4j
@Service
public class BinanceFuturesService {

    private static final String BINANCE_URL = "https://fapi.binance.com/fapi/v1";
    private static final String OKX_URL     = "https://www.okx.com/api/v5/public/open-interest";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper;

    public BinanceFuturesService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Return the current perpetual-swap open interest for {@code symbol} in base
     * currency units (e.g. BTC for BTCUSDT). Returns {@code null} if both providers fail.
     *
     * <p>Provider chain:
     * <ol>
     *   <li>Binance Futures — {@code GET /fapi/v1/openInterest?symbol=BTCUSDT}
     *       → {@code {"openInterest":"76542.679",...}}</li>
     *   <li>OKX SWAP (fallback) — {@code GET /api/v5/public/open-interest?instType=SWAP&instId=BTC-USDT-SWAP}
     *       → {@code {"data":[{"oiCcy":"76000",...}]}}</li>
     * </ol>
     */
    public Double getOpenInterest(String symbol) {
        OpenInterestObservation observation = getOpenInterestObservation(symbol);
        return observation == null ? null : observation.value();
    }

    /** Same provider chain as {@link #getOpenInterest(String)}, with auditable source metadata. */
    public OpenInterestObservation getOpenInterestObservation(String symbol) {
        // ── 1. Try Binance ──────────────────────────────────────────────────
        String binanceUrl = BINANCE_URL + "/openInterest?symbol=" + symbol;
        try (Response resp = HTTP.newCall(new Request.Builder().url(binanceUrl).get().build()).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode oi = root.get("openInterest");
                if (oi != null && !oi.isNull()) {
                    double value = Double.parseDouble(oi.asText());
                    log.debug("[BinanceFutures] OI {} = {} (Binance)", symbol, value);
                    long sourceTimestampMs = root.path("time").asLong(0L);
                    return new OpenInterestObservation(value, "BINANCE_FUTURES",
                            LocalDateTime.now(ZoneOffset.UTC),
                            sourceTimestampMs > 0 ? sourceTimestampMs : null);
                }
            } else {
                log.debug("[BinanceFutures] Binance OI HTTP {} for {} — trying OKX fallback",
                        resp.code(), symbol);
            }
        } catch (Exception e) {
            log.debug("[BinanceFutures] Binance OI error for {}: {} — trying OKX fallback",
                    symbol, e.getMessage());
        }

        // ── 2. OKX fallback ─────────────────────────────────────────────────
        return getOpenInterestFromOkx(symbol);
    }

    /**
     * OKX public SWAP open-interest endpoint (no auth, no geo-restriction).
     * {@code oiCcy} field = OI in base currency (BTC), equivalent to Binance's
     * {@code openInterest} field.
     */
    private OpenInterestObservation getOpenInterestFromOkx(String symbol) {
        String instId = toOkxInstId(symbol);
        if (instId == null) {
            log.warn("[BinanceFutures] unknown symbol {} — cannot map to OKX instId", symbol);
            return null;
        }
        String okxUrl = OKX_URL + "?instType=SWAP&instId=" + instId;
        try (Response resp = HTTP.newCall(new Request.Builder().url(okxUrl).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[BinanceFutures] OKX OI HTTP {} for {}", resp.code(), symbol);
                return null;
            }
            String body = resp.body().string();
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("[BinanceFutures] OKX OI empty data for {}: {}", symbol, body);
                return null;
            }
            // oiCcy = OI in base currency (BTC / ETH)
            JsonNode oiCcy = data.get(0).get("oiCcy");
            if (oiCcy == null || oiCcy.isNull()) {
                log.warn("[BinanceFutures] OKX OI oiCcy missing for {}", symbol);
                return null;
            }
            double value = Double.parseDouble(oiCcy.asText());
            log.debug("[BinanceFutures] OI {} = {} (OKX fallback)", symbol, value);
            long sourceTimestampMs = data.get(0).path("ts").asLong(0L);
            return new OpenInterestObservation(value, "OKX_PUBLIC_SWAP_FALLBACK",
                    LocalDateTime.now(ZoneOffset.UTC),
                    sourceTimestampMs > 0 ? sourceTimestampMs : null);
        } catch (Exception e) {
            log.warn("[BinanceFutures] OKX OI error for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch historical open-interest snapshots from Binance Futures
     * {@code /fapi/v1/openInterestHist}. Returns up to {@code limit} rows
     * (max 500) at 1-hour granularity between {@code startMs} and {@code endMs}.
     * Returns an empty list on any error.
     *
     * <p>Response item: {@code {"sumOpenInterest":"76542.679", "timestamp":1620000000000}}
     */
    public List<OiSnapshot> getOpenInterestHistory(String symbol, long startMs, long endMs, int limit) {
        String url = BINANCE_URL + "/openInterestHist?symbol=" + symbol
                + "&period=1h&limit=" + Math.min(limit, 500)
                + "&startTime=" + startMs + "&endTime=" + endMs;
        try (Response resp = HTTP.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[BinanceFutures] OI history HTTP {} for {}", resp.code(), symbol);
                return List.of();
            }
            String body = resp.body().string();
            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray()) {
                log.warn("[BinanceFutures] OI history unexpected response: {}", body.substring(0, Math.min(200, body.length())));
                return List.of();
            }
            List<OiSnapshot> result = new java.util.ArrayList<>();
            for (JsonNode item : arr) {
                JsonNode oi  = item.get("sumOpenInterest");
                JsonNode ts  = item.get("timestamp");
                if (oi == null || ts == null) continue;
                result.add(new OiSnapshot(ts.asLong(), Double.parseDouble(oi.asText())));
            }
            return result;
        } catch (Exception e) {
            log.warn("[BinanceFutures] OI history error for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    public record OiSnapshot(long timestampMs, double openInterest) {}

    public record OpenInterestObservation(double value,
                                          String provider,
                                          LocalDateTime observedAt,
                                          Long sourceTimestampMs) {
        public LocalDateTime effectiveCapturedAt() {
            if (sourceTimestampMs != null && sourceTimestampMs > 0) {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(sourceTimestampMs), ZoneOffset.UTC);
            }
            return observedAt;
        }
    }

    /**
     * Map a Binance-style symbol to OKX's perpetual-swap instId.
     * Returns null for unknown symbols.
     */
    private static String toOkxInstId(String symbol) {
        if (symbol == null) return null;
        return switch (symbol.toUpperCase()) {
            case "BTCUSDT" -> "BTC-USDT-SWAP";
            case "ETHUSDT" -> "ETH-USDT-SWAP";
            case "SOLUSDT" -> "SOL-USDT-SWAP";
            case "BNBUSDT" -> "BNB-USDT-SWAP";
            default -> null;
        };
    }
}
