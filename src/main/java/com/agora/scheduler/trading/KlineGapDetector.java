package com.agora.scheduler.trading;

import com.agora.config.MarketWsAutoSubscribeProperties;
import com.agora.config.WsSubscriptionResolver;
import com.agora.model.MdKline;
import com.agora.event.KlineClosedEvent;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.market.MdKlineInsertHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 定時偵測 md_kline 的時間缺口並從 OKX 回填。
 *
 * <p>背景：Binance WS 偶發斷線會導致 1-2 根 K 線遺失（2026-04-14 發生過）。
 * 遺失的 bar 會讓策略信號評估跳過，但系統無自動偵測，需手動排查。
 *
 * <p>運作：每小時掃描 DB-derived auto-subscribe 清單中每個 (symbol, interval)
 * 過去 25 小時的 K 線，對比預期 bar 時間，若有缺口則呼叫 OKX candles API 補齊。
 *
 * <p>資料源選擇 OKX 而非 Binance：過往經驗顯示 Binance.us 在美國受限場景下偶有斷連
 * 或 API 失效，OKX 穩定度較佳。SPOT 收盤價差異通常 < 0.05%，對信號評估無實質影響。
 */
@Slf4j
@Component
public class KlineGapDetector {

    private static final String OKX_BASE = "https://www.okx.com";
    private static final int OKX_DAILY_OPEN_HOUR_UTC = 16;

    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;
    private final MarketWsAutoSubscribeProperties wsProps;
    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final MdKlineInsertHelper insertHelper;
    private final WsSubscriptionResolver subscriptionResolver;
    private final String okxBaseUrl;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    @Autowired
    public KlineGapDetector(MdKlineRepository klineRepository,
                            ObjectMapper objectMapper,
                            MarketWsAutoSubscribeProperties wsProps,
                            NotificationPort notificationPort,
                            ApplicationEventPublisher eventPublisher,
                            MdKlineInsertHelper insertHelper,
                            WsSubscriptionResolver subscriptionResolver) {
        this(klineRepository, objectMapper, wsProps, notificationPort, eventPublisher,
                insertHelper, subscriptionResolver, OKX_BASE);
    }

    KlineGapDetector(MdKlineRepository klineRepository,
                     ObjectMapper objectMapper,
                     MarketWsAutoSubscribeProperties wsProps,
                     NotificationPort notificationPort,
                     ApplicationEventPublisher eventPublisher,
                     MdKlineInsertHelper insertHelper,
                     WsSubscriptionResolver subscriptionResolver,
                     String okxBaseUrl) {
        this.klineRepository = klineRepository;
        this.objectMapper = objectMapper;
        this.wsProps = wsProps;
        this.notificationPort = notificationPort;
        this.eventPublisher = eventPublisher;
        this.insertHelper = insertHelper;
        this.subscriptionResolver = subscriptionResolver;
        this.okxBaseUrl = okxBaseUrl;
    }

    /**
     * 掃描過去 25 小時的 K 線缺口並從 OKX 補齊。
     * @Scheduled 已移至 HourlyOrchestrator（UTC :00 串行執行，step 2）
     */
    public void detectAndBackfill() {
        if (!wsProps.isEnabled()) return;

        List<MarketWsAutoSubscribeProperties.Item> itemsToCheck = resolveItemsToCheck();
        if (itemsToCheck.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 從 25 小時前 → 1 小時前（避開正在進行的當前 bar）
        LocalDateTime end = alignToInterval(now, "1h").minusHours(1);
        LocalDateTime start = end.minusHours(25);

        int totalFilled = 0;
        List<String> alerts = new ArrayList<>();
        for (MarketWsAutoSubscribeProperties.Item item : itemsToCheck) {
            int filled = checkGaps(item.getSymbol(), item.getIntervalCode(), start, end);
            if (filled > 0) {
                totalFilled += filled;
                alerts.add(String.format("%s@%s: +%d bars", item.getSymbol(), item.getIntervalCode(), filled));
            }
        }
        if (totalFilled > 0) {
            log.info("[KlineGap] Scan complete, backfilled {} bars total: {}",
                    totalFilled, String.join(", ", alerts));
        }
        // 2026-04-18: TG 門檻從 >0 提高到 >=3。1-2 bar 是每小時 WS 跟 OKX REST 的
        // 例行延遲(幾乎每次 :05 掃描都會補到 ETHUSDT@1h +1),純噪音。
        // 真的連續掉 3 bars 以上才表示 WS 或上游有異常,值得警示人類。
        if (totalFilled >= 3) {
            try {
                notificationPort.broadcast(
                        "🔧 <b>Kline 缺口自動補齊</b> (⚠️ 缺口偏大)\n"
                                + "totalFilled=" + totalFilled + "\n"
                                + String.join("\n", alerts), true);
            } catch (Exception ignored) {}
        }
    }

    private List<MarketWsAutoSubscribeProperties.Item> resolveItemsToCheck() {
        try {
            List<MarketWsAutoSubscribeProperties.Item> resolved = subscriptionResolver.resolve();
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        } catch (Exception e) {
            log.warn("[KlineGap] subscription resolver failed, falling back to yaml items: {}", e.getMessage());
        }
        return wsProps.getItems() != null ? wsProps.getItems() : List.of();
    }

    private int checkGaps(String symbol, String intervalCode, LocalDateTime start, LocalDateTime end) {
        // 只檢查 source='okx' 的缺口（Binance 缺口由 Binance WS 自己處理；此 detector 專補 OKX 源）
        List<LocalDateTime> existing = klineRepository.findOpenTimesBetweenBySource(
                symbol, intervalCode, "okx", start, end);
        Set<LocalDateTime> existingSet = new HashSet<>(existing);

        List<LocalDateTime> expected = generateExpectedBars(intervalCode, start, end);
        List<LocalDateTime> missing = new ArrayList<>();
        for (LocalDateTime t : expected) {
            if (!existingSet.contains(t)) missing.add(t);
        }
        if (missing.isEmpty()) return 0;

        log.warn("[KlineGap] {}@{} missing {} bars (expected={} existing={}): {}",
                symbol, intervalCode, missing.size(), expected.size(), existing.size(), missing);

        // 從 OKX 撈整個範圍（不是一根根撈，節省 API 呼叫）
        List<MdKline> fetched = fetchFromOkx(symbol, intervalCode, start, end);
        if (fetched.isEmpty()) {
            log.warn("[KlineGap] OKX fetch returned 0 bars for {}@{}, cannot backfill", symbol, intervalCode);
            return 0;
        }

        // 只存缺口內的 bars（避免意外覆寫）
        Set<LocalDateTime> missingSet = new HashSet<>(missing);
        List<MdKline> toSave = fetched.stream()
                .filter(k -> missingSet.contains(k.getOpenTime()))
                .toList();

        if (toSave.isEmpty()) {
            log.warn("[KlineGap] OKX data does not cover missing bars for {}@{}", symbol, intervalCode);
            return 0;
        }

        List<MdKline> inserted = insertBackfillKlines(toSave);
        log.info("[KlineGap] {}@{} backfilled {} bars from OKX (candidates={} duplicatesOrFailed={})",
                symbol, intervalCode, inserted.size(), toSave.size(), toSave.size() - inserted.size());

        // 2026-04-18: 補齊的 bar 也必須發布 KlineClosedEvent。是否補跑 legacy
        // LiveSignalEvaluator 由 signal-source policy 決定；TradingView-primary 模式只保留資料事件。
        // OkxWsKlineService.persistIfClosed 亦是 save 後 publishEvent，此處保持語義一致。
        for (MdKline k : inserted) {
            try {
                eventPublisher.publishEvent(new KlineClosedEvent(this, k));
            } catch (Exception e) {
                log.warn("[KlineGap] publishEvent failed for {}@{}: {}", k.getSymbol(), k.getIntervalCode(), e.getMessage());
            }
        }

        return inserted.size();
    }

    List<MdKline> insertBackfillKlines(List<MdKline> candidates) {
        List<MdKline> inserted = new ArrayList<>();
        for (MdKline k : candidates) {
            if (insertHelper.insertIgnore(k)) {
                inserted.add(k);
            }
        }
        return inserted;
    }

    /** 對齊到 interval 的起點（例如 12:37 → 1h 對齊為 12:00）。 */
    LocalDateTime alignToInterval(LocalDateTime t, String intervalCode) {
        Duration d = intervalDuration(intervalCode);
        long minutes = d.toMinutes();
        int minute = t.getMinute();
        int hour = t.getHour();

        if (minutes < 60) {
            int aligned = (minute / (int) minutes) * (int) minutes;
            return t.withMinute(aligned).withSecond(0).withNano(0);
        } else if (minutes == 60) {
            return t.withMinute(0).withSecond(0).withNano(0);
        } else if (minutes % (24 * 60) == 0) {
            // OKX candle1D is UTC+8 anchored, so the UTC open time is 16:00.
            // Using 00:00 here creates false "missing 1d bar" gaps.
            int daysInInterval = (int) (minutes / (24 * 60));
            LocalDateTime aligned = t.withHour(OKX_DAILY_OPEN_HOUR_UTC).withMinute(0).withSecond(0).withNano(0);
            while (aligned.isAfter(t)) {
                aligned = aligned.minusDays(daysInInterval);
            }
            while (!aligned.plusDays(daysInInterval).isAfter(t)) {
                aligned = aligned.plusDays(daysInInterval);
            }
            return aligned;
        } else {
            // e.g. 4h → hour aligned to multiples of 4
            int hoursInInterval = (int) (minutes / 60);
            int alignedHour = (hour / hoursInInterval) * hoursInInterval;
            return t.withHour(alignedHour).withMinute(0).withSecond(0).withNano(0);
        }
    }

    private List<LocalDateTime> generateExpectedBars(String intervalCode, LocalDateTime start, LocalDateTime end) {
        Duration step = intervalDuration(intervalCode);
        // 排除當前進行中的 bar：closeTime 必須在 now 之前才算已收盤
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cursor = alignToInterval(start, intervalCode);
        if (cursor.isBefore(start)) cursor = cursor.plus(step);
        List<LocalDateTime> result = new ArrayList<>();
        while (!cursor.isAfter(end)) {
            LocalDateTime closeTime = cursor.plus(step);
            if (closeTime.isAfter(nowUtc)) break;  // 該 bar 尚未收盤，跳過
            result.add(cursor);
            cursor = cursor.plus(step);
        }
        return result;
    }

    private Duration intervalDuration(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("m")) return Duration.ofMinutes(Long.parseLong(code.substring(0, code.length() - 1)));
        if (code.endsWith("h")) return Duration.ofHours(Long.parseLong(code.substring(0, code.length() - 1)));
        if (code.endsWith("d")) return Duration.ofDays(Long.parseLong(code.substring(0, code.length() - 1)));
        throw new IllegalArgumentException("Unsupported interval: " + intervalCode);
    }

    /**
     * 從 OKX candles API 撈指定範圍的 K 線。
     * OKX bar 格式：1m/3m/5m/15m/30m/1H/2H/4H/6H/12H/1D。
     */
    List<MdKline> fetchFromOkx(String symbol, String intervalCode, LocalDateTime start, LocalDateTime end) {
        // P0 2026-05-21: gap detection repairs the most recent 25h window. OKX
        // history-candles can lag around newly closed bars, so use the recent
        // candles endpoint first. Fallback keeps older/manual repair behavior.
        List<MdKline> recent = fetchFromOkxEndpoint(symbol, intervalCode, start, end, "candles");
        if (!recent.isEmpty()) {
            return recent;
        }
        return fetchFromOkxEndpoint(symbol, intervalCode, start, end, "history-candles");
    }

    private List<MdKline> fetchFromOkxEndpoint(String symbol, String intervalCode,
                                               LocalDateTime start, LocalDateTime end,
                                               String endpoint) {
        String instId = toOkxSpotInstId(symbol);
        String bar = toOkxBar(intervalCode);
        // limit=300 涵蓋最近 300 根（1h=12.5天、4h=50天），遠大於 25h 掃描窗口，不需 after/before 分頁
        String url = okxBaseUrl + "/api/v5/market/" + endpoint + "?instId=" + instId
                + "&bar=" + bar + "&limit=300";

        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[KlineGap] OKX HTTP {} for {}@{}", resp.code(), symbol, intervalCode);
                    return List.of();
                }
                JsonNode root = objectMapper.readTree(resp.body().string());
                if (!"0".equals(root.path("code").asText())) {
                    log.warn("[KlineGap] OKX error for {}@{}: {}", symbol, intervalCode, root.path("msg").asText());
                    return List.of();
                }
                JsonNode data = root.path("data");
                List<MdKline> list = new ArrayList<>();
                for (JsonNode row : data) {
                    // OKX 只回傳 confirm=1 的已收盤 bar
                    if ("0".equals(row.get(8).asText())) continue;
                    long ts = row.get(0).asLong();
                    LocalDateTime openTime = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDateTime();
                    if (openTime.isBefore(start) || openTime.isAfter(end)) {
                        continue;
                    }
                    MdKline k = new MdKline();
                    k.setSymbol(symbol);
                    k.setIntervalCode(intervalCode);
                    k.setOpenTime(openTime);
                    k.setOpenPrice(new BigDecimal(row.get(1).asText()));
                    k.setHighPrice(new BigDecimal(row.get(2).asText()));
                    k.setLowPrice(new BigDecimal(row.get(3).asText()));
                    k.setClosePrice(new BigDecimal(row.get(4).asText()));
                    k.setVolume(new BigDecimal(row.get(5).asText()));
                    k.setSource("okx");
                    // closeTime = openTime + interval
                    k.setCloseTime(k.getOpenTime().plus(intervalDuration(intervalCode)));
                    list.add(k);
                }
                return list;
            }
        } catch (Exception e) {
            log.warn("[KlineGap] OKX {} fetch failed for {}@{}: {}", endpoint, symbol, intervalCode, e.getMessage());
            return List.of();
        }
    }

    /** BTCUSDT → BTC-USDT */
    private String toOkxSpotInstId(String symbol) {
        if (symbol.endsWith("USDT")) return symbol.substring(0, symbol.length() - 4) + "-USDT";
        if (symbol.endsWith("BUSD")) return symbol.substring(0, symbol.length() - 4) + "-BUSD";
        return symbol;
    }

    /** 1h → 1H；1d → 1D（OKX 大寫）；1m/15m 保持小寫。 */
    private String toOkxBar(String intervalCode) {
        String code = intervalCode.toLowerCase(Locale.ROOT);
        if (code.endsWith("h")) return code.toUpperCase(Locale.ROOT);
        if (code.endsWith("d")) return code.toUpperCase(Locale.ROOT);
        return code;  // m 保持小寫
    }
}
