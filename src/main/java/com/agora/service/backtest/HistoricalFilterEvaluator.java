package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.market.EventCalendarService;
import com.agora.service.market.FearGreedService;
import com.agora.service.trading.OkxTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歷史重播版 Long/ShortAiFilter：給定 (symbol, bar 時間, RSI, MACD 等指標)，判斷該筆進場
 * 若在當時是否會被過濾。供 BacktestEngine 在 {@code applyFilters=true} 時啟用。
 *
 * <p><b>涵蓋規則（v1）</b>：
 * <ul>
 *   <li>事件日曆（FOMC / CPI 窗口）— 硬編碼日期，直接適用於歷史</li>
 *   <li>Fear &amp; Greed（基於 bar 日期的歷史 F&amp;G）</li>
 *   <li>OKX 資金費率（歷史資料）</li>
 *   <li>OKX 多空帳戶比（歷史資料）</li>
 * </ul>
 *
 * <p><b>跳過的規則</b>（因缺少歷史資料）：
 * <ul>
 *   <li>Polymarket — 無歷史市場快照</li>
 *   <li>Whale taker-volume — 歷史需額外實作</li>
 *   <li>4h 趨勢 / RSI — 已是策略信號的一部分，不重複過濾</li>
 * </ul>
 *
 * <p><b>使用方式</b>：在 {@link BacktestEngine} 處理到 BUY/SELL 訊號的每根 bar 時呼叫
 * {@link #wouldBlock(String, String, LocalDateTime, Map)}，回傳非 null 字串表示被封鎖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalFilterEvaluator {

    private final FearGreedService fearGreedService;
    private final EventCalendarService eventCalendarService;
    private final OkxTradingService okxTradingService;
    private final MdKlineRepository klineRepository;

    /**
     * @param side         "LONG" 或 "SHORT"
     * @param symbol       交易對（如 BTCUSDT）
     * @param barTime      進場 bar 的 openTime（UTC）
     * @param ctx          額外可選參數（未使用；保留擴充）
     * @return 非 null 表示被封鎖（內容為原因），null 表示通過
     */
    public String wouldBlock(String side, String symbol, LocalDateTime barTime,
                              Map<String, Object> ctx) {
        boolean isShort = "SHORT".equalsIgnoreCase(side);

        // 1. 事件日曆
        EventCalendarService.Event evt = findActiveEvent(barTime);
        if (evt != null) {
            return String.format("Event %s (%s)", evt.name(),
                    barTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }

        // 2. F&G（結合 4h 趨勢判斷：順勢 SHORT/LONG 不攔）
        LocalDate d = barTime.toLocalDate();
        Integer fg = fgByDateCache().get(d);
        if (fg != null) {
            String trend4h = get4hTrendAt(symbol, barTime);
            if (isShort && fg < 25) {
                // 恐慌 + 4h 非延續空頭 → 可能為底部反彈，攔掉
                if (!"BEARISH".equals(trend4h)) {
                    return String.format("F&G=%d (<25) 且 4h=%s 非延續空頭（恐慌反彈風險）", fg, trend4h);
                }
                // 4h BEARISH → 允許順勢 SHORT
            }
            if (!isShort && fg > 75) {
                if (!"BULLISH".equals(trend4h)) {
                    return String.format("F&G=%d (>75) 且 4h=%s 非延續多頭（頂部回調風險）", fg, trend4h);
                }
            }
        }

        // 3. OKX 資金費率歷史（結合 4h 趨勢：延續性趨勢下不攔）
        String trend4hForExtremes = get4hTrendAt(symbol, barTime);
        Double fr = getFundingRateAt(symbol, barTime);
        if (fr != null) {
            boolean bearishCont = "BEARISH".equals(trend4hForExtremes);
            boolean bullishCont = "BULLISH".equals(trend4hForExtremes);
            if (isShort && fr < -0.0003 && !bearishCont)
                return String.format("Funding=%.4f%% 且 4h=%s 非延續空頭（擠壓風險）", fr * 100, trend4hForExtremes);
            if (!isShort && fr > 0.0005 && !bullishCont)
                return String.format("Funding=%.4f%% 且 4h=%s 非延續多頭（擠壓風險）", fr * 100, trend4hForExtremes);
        }

        // 4. OKX 多空比歷史（同邏輯）
        Double ls = getLongShortAt(symbol, barTime);
        if (ls != null) {
            boolean bearishCont = "BEARISH".equals(trend4hForExtremes);
            boolean bullishCont = "BULLISH".equals(trend4hForExtremes);
            if (isShort && ls < 0.75 && !bearishCont)
                return String.format("L/S=%.2f 且 4h=%s 非延續空頭（擠壓風險）", ls, trend4hForExtremes);
            if (!isShort && ls > 1.5 && !bullishCont)
                return String.format("L/S=%.2f 且 4h=%s 非延續多頭（擠壓風險）", ls, trend4hForExtremes);
        }

        // 5. 鯨魚 taker buy ratio 歷史（無趨勢 gate：瞬時資金流本身就是獨立信號）
        Double whale = getWhaleBuyRatioAt(symbol, barTime);
        if (whale != null) {
            if (isShort && whale > 0.65)
                return String.format("Whale buyRatio=%.0f%% (>65%% 大戶持續買入)", whale * 100);
            if (!isShort && whale < 0.35)
                return String.format("Whale buyRatio=%.0f%% (<35%% 大戶持續賣出)", whale * 100);
        }

        return null;
    }

    // ─── 內部快取（單次回測期間 reuse）────────────────────────────────────────────

    private final ThreadLocal<Map<LocalDate, Integer>> fgCache = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Boolean> fgLoaded = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Map<String, Map<LocalDate, Double>>> frCache = ThreadLocal.withInitial(HashMap::new);
    // L/S 比率與鯨魚 taker buy ratio 歷史：OKX rubik period=1H，直接以 hour 為 key
    private final ThreadLocal<Map<String, Map<LocalDateTime, Double>>> lsHourCache    = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Map<String, Map<LocalDateTime, Double>>> whaleHourCache = ThreadLocal.withInitial(HashMap::new);
    // 4h 趨勢快取：key = symbol + ":" + yyyy-MM-ddTHH（每小時一次 DB 查詢）
    private final ThreadLocal<Map<String, String>> trendCache = ThreadLocal.withInitial(HashMap::new);

    /** 開始新回測前清快取（BacktestEngine 呼叫）。 */
    public void reset() {
        fgCache.remove();
        fgLoaded.remove();
        frCache.remove();
        lsHourCache.remove();
        whaleHourCache.remove();
        trendCache.remove();
    }

    /**
     * 查詢 barTime 當下的 4h 趨勢（BULLISH / BEARISH / UNKNOWN）。
     * 從 DB 取 barTime 前 10 天的 4h 棒，簡化：close vs SMA20 做判斷（與
     * AiStrategyDiscoveryService.buildMarketSnapshot 一致）。
     * 快取粒度為「每小時一次查詢」。
     */
    private String get4hTrendAt(String symbol, LocalDateTime barTime) {
        String key = symbol + ":" + barTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Map<String, String> cache = trendCache.get();
        String cached = cache.get(key);
        if (cached != null) return cached;

        String trend = computeTrend(symbol, barTime);
        cache.put(key, trend);
        return trend;
    }

    private String computeTrend(String symbol, LocalDateTime barTime) {
        LocalDateTime start = barTime.minusDays(10);
        try {
            List<MdKline> klines = klineRepository
                    .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                            symbol, "4h", start, barTime);
            if (klines.size() < 20) return "UNKNOWN";
            int n = klines.size();
            double sma = 0;
            int smaPeriod = Math.min(20, n);
            for (int i = n - smaPeriod; i < n; i++) {
                sma += klines.get(i).getClosePrice().doubleValue();
            }
            sma /= smaPeriod;
            double last = klines.get(n - 1).getClosePrice().doubleValue();
            if (last > sma * 1.005) return "BULLISH";   // > 0.5% 高於 SMA20
            if (last < sma * 0.995) return "BEARISH";   // < 0.5% 低於 SMA20
            return "FLAT";
        } catch (Exception e) {
            log.warn("[HistFilter] 4h trend compute failed for {} at {}: {}",
                    symbol, barTime, e.getMessage());
            return "UNKNOWN";
        }
    }

    private Map<LocalDate, Integer> fgByDateCache() {
        if (!fgLoaded.get()) {
            try {
                List<FearGreedService.FearGreedEntry> history = fearGreedService.getHistoricalFearGreed(365);
                Map<LocalDate, Integer> m = fgCache.get();
                for (FearGreedService.FearGreedEntry e : history) {
                    LocalDate d = java.time.Instant.ofEpochSecond(e.timestamp())
                            .atZone(ZoneOffset.UTC).toLocalDate();
                    m.put(d, e.value());
                }
                fgLoaded.set(true);
            } catch (Exception e) {
                log.warn("[HistFilter] F&G history load failed: {}", e.getMessage());
                fgLoaded.set(true);  // 避免反覆重試
            }
        }
        return fgCache.get();
    }

    private EventCalendarService.Event findActiveEvent(LocalDateTime barTime) {
        // 事件窗口：前 2h + 後 4h（與 EventCalendarService 配置一致）
        List<EventCalendarService.Event> all = eventCalendarService.listUpcoming(365);
        for (EventCalendarService.Event e : all) {
            Duration dt = Duration.between(barTime, e.time());
            long h = dt.toHours();
            if (h <= 2 && h >= -4) return e;
        }
        return null;
    }

    private Double getFundingRateAt(String symbol, LocalDateTime barTime) {
        LocalDate d = barTime.toLocalDate();
        Map<LocalDate, Double> byDate = frCache.get().computeIfAbsent(symbol, k -> loadFundingRateHistory(k));
        return byDate.get(d);
    }

    private Map<LocalDate, Double> loadFundingRateHistory(String symbol) {
        Map<LocalDate, Double> out = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode data =
                    okxTradingService.getFundingRateHistory(symbol, 100);
            for (com.fasterxml.jackson.databind.JsonNode row : data) {
                long ts = row.path("fundingTime").asLong();
                double rate = row.path("fundingRate").asDouble(0);
                LocalDate d = java.time.Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate();
                // 同日多筆取平均（OKX 每 8h 發布 → 每日 3 筆）
                out.merge(d, rate, (a, b) -> (a + b) / 2.0);
            }
        } catch (Exception e) {
            log.warn("[HistFilter] Funding rate history failed for {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    private Double getLongShortAt(String symbol, LocalDateTime barTime) {
        // 以小時為 key（OKX 端點 period=1H）
        LocalDateTime hour = barTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Map<LocalDateTime, Double> byHour = lsHourCache.get()
                .computeIfAbsent(symbol, k -> loadLongShortHistory(k));
        return byHour.get(hour);
    }

    private Map<LocalDateTime, Double> loadLongShortHistory(String symbol) {
        Map<LocalDateTime, Double> out = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode data =
                    okxTradingService.getLongShortRatioHistory(symbol, 1440);  // 60 天小時資料
            for (com.fasterxml.jackson.databind.JsonNode row : data) {
                long ts = row.get(0).asLong();
                double ratio = row.get(1).asDouble(-1);
                LocalDateTime hour = java.time.Instant.ofEpochMilli(ts)
                        .atZone(ZoneOffset.UTC).toLocalDateTime()
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
                if (ratio > 0) out.put(hour, ratio);
            }
        } catch (Exception e) {
            log.warn("[HistFilter] L/S ratio history failed for {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    /** 鯨魚 taker buy ratio（OKX rubik taker-volume）歷史，按小時 index。 */
    public Double getWhaleBuyRatioAt(String symbol, LocalDateTime barTime) {
        LocalDateTime hour = barTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Map<LocalDateTime, Double> byHour = whaleHourCache.get()
                .computeIfAbsent(symbol, k -> loadWhaleHistory(k));
        return byHour.get(hour);
    }

    private Map<LocalDateTime, Double> loadWhaleHistory(String symbol) {
        Map<LocalDateTime, Double> out = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode data =
                    okxTradingService.getTakerVolumeHistory(symbol, 1440);
            for (com.fasterxml.jackson.databind.JsonNode row : data) {
                long ts = row.get(0).asLong();
                double sellVol = row.get(1).asDouble(0);
                double buyVol  = row.get(2).asDouble(0);
                double total = buyVol + sellVol;
                if (total <= 0) continue;
                double ratio = buyVol / total;
                LocalDateTime hour = java.time.Instant.ofEpochMilli(ts)
                        .atZone(ZoneOffset.UTC).toLocalDateTime()
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
                out.put(hour, ratio);
            }
        } catch (Exception e) {
            log.warn("[HistFilter] Whale taker-volume history failed for {}: {}", symbol, e.getMessage());
        }
        return out;
    }
}
