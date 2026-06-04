package com.agora.service.market;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One-time backfill of BTC open-interest history from OKX public API.
 *
 * <p>Uses {@code GET /api/v5/rubik/stat/contracts/open-interest-volume?ccy=BTC&period=1H}
 * which provides ~30 days of hourly OI data (no auth, no geo-restriction).
 *
 * <p>OI values are in USDT. We only write {@code oi_change_pct_1h} (percentage
 * change between consecutive rows) — unit-agnostic and what the strategy needs.
 * Idempotent: skips timestamps that already exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OiBackfillService {

    private static final String OKX_URL =
            "https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-volume"
            + "?ccy=BTC&period=1H&limit=720";
    private static final String SYMBOL    = "BTCUSDT";
    private static final String IND_DELTA = "oi_change_pct_1h";
    private static final long   ONE_HOUR_MS = 3_600_000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final MarketIndicatorHistoryRepository historyRepo;
    private final ObjectMapper objectMapper;

    public String backfill() {
        log.info("[OiBackfill] Fetching BTC OI history from OKX...");

        List<long[]> snapshots = fetchOkxOi();
        if (snapshots.isEmpty()) {
            return "ERROR: OKX returned no OI data";
        }

        snapshots.sort(Comparator.comparingLong(a -> a[0]));
        log.info("[OiBackfill] Fetched {} OI rows, from {} to {}",
                snapshots.size(),
                toUtc(snapshots.get(0)[0]),
                toUtc(snapshots.get(snapshots.size() - 1)[0]));

        int written = writeDeltaRows(snapshots);
        String summary = String.format(
                "OI backfill complete: fetched=%d rows, oi_change_pct_1h written=%d",
                snapshots.size(), written);
        log.info("[OiBackfill] {}", summary);
        return summary;
    }

    // ── Fetch from OKX ────────────────────────────────────────────────────────

    /** Returns list of [timestampMs, oiUsdt] pairs. */
    private List<long[]> fetchOkxOi() {
        try (Response resp = HTTP.newCall(
                new Request.Builder().url(OKX_URL).get().build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[OiBackfill] OKX HTTP {}", resp.code());
                return List.of();
            }
            String body = resp.body().string();
            JsonNode root = objectMapper.readTree(body);
            if (!"0".equals(root.path("code").asText())) {
                log.warn("[OiBackfill] OKX error: {}", root.path("msg").asText());
                return List.of();
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) return List.of();

            List<long[]> result = new ArrayList<>();
            for (JsonNode row : data) {
                // row = [ts_ms, oi_usdt, volume_usdt]
                if (row.isArray() && row.size() >= 2) {
                    long ts  = row.get(0).asLong();
                    long oi  = (long) Double.parseDouble(row.get(1).asText());
                    result.add(new long[]{ts, oi});
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[OiBackfill] OKX fetch error: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Write delta rows ──────────────────────────────────────────────────────

    private int writeDeltaRows(List<long[]> snapshots) {
        int written = 0;
        for (int i = 1; i < snapshots.size(); i++) {
            long[] prev = snapshots.get(i - 1);
            long[] curr = snapshots.get(i);

            if (curr[0] - prev[0] > 2 * ONE_HOUR_MS) continue;
            if (prev[1] <= 0) continue;

            double changePct = (double)(curr[1] - prev[1]) / prev[1] * 100.0;
            LocalDateTime capturedAt = toUtc(curr[0]);

            if (historyRepo.existsBySymbolAndIndicatorAndCapturedAt(
                    SYMBOL, IND_DELTA, capturedAt)) continue;

            MarketIndicatorHistory row = new MarketIndicatorHistory();
            row.setCapturedAt(capturedAt);
            row.setSymbol(SYMBOL);
            row.setIndicator(IND_DELTA);
            row.setValue(BigDecimal.valueOf(changePct));
            historyRepo.save(row);
            written++;
        }
        return written;
    }

    private static LocalDateTime toUtc(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
