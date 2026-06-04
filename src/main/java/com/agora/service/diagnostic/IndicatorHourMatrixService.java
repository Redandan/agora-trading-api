package com.agora.service.diagnostic;

import com.agora.service.diagnostic.event.Event;
import com.agora.service.diagnostic.event.EventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * #339 indicatorAccuracyHourMatrix — 對單一 source/filter 把 events 依 hour-of-day 分 24 組，
 * 各自算事後正確率，揭露「BTC 漲跌是否有時段偏好」。
 *
 * <p>用途範例：
 * <ul>
 *   <li>SqiIndicator 警報在亞洲時段 vs 歐美時段 hit% 差異</li>
 *   <li>funding_rate 深負在 8/16/24h funding settlement 時段周圍是否更準</li>
 *   <li>確認 #338 leaderboard 的 alpha 是否集中某些時段（防 narrow window inflation）</li>
 * </ul>
 *
 * <p>V2 — 加 minSampleN 可調 + Session breakdown (Asia/EU/US)。
 *
 * <p>實作：先建 PriceLookup.Cache，fetch events 一次，按 event.ts().getHour() 分桶，
 * 對每桶獨立 aggregate 出 hour_hit_rate / hour_avg_return / hour_n。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorHourMatrixService {

    private final IndicatorOutcomeService outcomeService;

    /**
     * 主入口（V2 — 加 minSampleN + Session breakdown）。
     *
     * @return 24-row 表 + Session 區塊 (Asia/EU/US) + 摘要
     */
    public String matrix(String sourceName, String filter, Integer horizonHours,
                         Double hitThresholdPct, Integer days, String symbol,
                         Integer minSampleN, Boolean showSessionBreakdown) {
        int horizon = (horizonHours != null && horizonHours > 0) ? horizonHours : 24;
        double hitTh = (hitThresholdPct != null) ? Math.abs(hitThresholdPct) : 0.5;
        double hitFraction = hitTh / 100.0;
        int d = (days != null && days > 0) ? Math.min(days, 180) : 30;
        String sym = (symbol != null && !symbol.isBlank()) ? symbol.toUpperCase() : "BTCUSDT";
        int minN = (minSampleN != null && minSampleN >= 1) ? minSampleN : 3;
        boolean showSession = showSessionBreakdown == null || showSessionBreakdown;

        if (sourceName == null || sourceName.isBlank() || filter == null || filter.isBlank()) {
            return "❌ source 與 filter 必填";
        }

        EventSource source = outcomeService.allSources().stream()
                .filter(s -> s.name().equalsIgnoreCase(sourceName))
                .findFirst().orElse(null);
        if (source == null) {
            String supported = outcomeService.allSources().stream()
                    .map(EventSource::name).collect(Collectors.joining(", "));
            return "❌ unknown source: " + sourceName + " | 目前支援：" + supported;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = now.minusDays(d);
        LocalDateTime to = now.minusHours(horizon);

        List<Event> events;
        try {
            events = source.fetch(filter, from, to);
        } catch (Exception e) {
            log.warn("[hourMatrix] fetch failed source={} filter={}", sourceName, filter, e);
            return "❌ event 抽取失敗: " + e.getMessage();
        }
        if (events.isEmpty()) {
            return String.format("⚠️ 無 event：source=%s filter=%s 過去 %dd", sourceName, filter, d);
        }

        PriceLookup.Cache priceCache = outcomeService.priceLookup().buildCache(sym, from, now);
        if (priceCache.isEmpty()) {
            return "❌ price cache 為空（無 " + sym + " 價格資料）";
        }

        // 24 個 bucket（hour-of-day UTC）
        HourBucket[] buckets = new HourBucket[24];
        for (int i = 0; i < 24; i++) buckets[i] = new HourBucket();

        int skipped = 0;
        for (Event ev : events) {
            Optional<Double> ret = priceCache.returnOver(ev.ts(), horizon);
            if (ret.isEmpty()) { skipped++; continue; }
            int hour = ev.ts().getHour();
            buckets[hour].add(ev.direction(), ret.get(), hitFraction);
        }

        return format(sourceName, filter, sym, d, horizon, hitTh, buckets, events.size(), skipped, minN, showSession);
    }

    /** Backwards-compatible 6-param overload (V1 call sites). */
    public String matrix(String sourceName, String filter, Integer horizonHours,
                         Double hitThresholdPct, Integer days, String symbol) {
        return matrix(sourceName, filter, horizonHours, hitThresholdPct, days, symbol, null, null);
    }

    private static class HourBucket {
        int n = 0;
        int hits = 0;
        double sumRet = 0;
        String dirHint = "?";

        void add(String direction, double ret, double hitFraction) {
            n++;
            sumRet += ret;
            if (n == 1) dirHint = direction;
            boolean hit = switch (direction) {
                case Event.LONG  -> ret >  hitFraction;
                case Event.SHORT -> ret < -hitFraction;
                default          -> false;
            };
            if (hit) hits++;
        }

        double hitRate() { return n == 0 ? 0 : (double) hits / n; }
        double avgRet()  { return n == 0 ? 0 : sumRet / n; }
    }

    private String format(String sourceName, String filter, String symbol, int days,
                          int horizon, double hitThPct, HourBucket[] buckets,
                          int totalEvents, int skipped, int minN, boolean showSession) {
        int totalN = 0, totalHits = 0;
        double totalSum = 0;
        int activeBuckets = 0;
        int peakHour = -1;
        double peakHit = -1;
        int worstHour = -1;
        double worstHit = 2.0;

        for (int h = 0; h < 24; h++) {
            HourBucket b = buckets[h];
            if (b.n == 0) continue;
            activeBuckets++;
            totalN += b.n;
            totalHits += b.hits;
            totalSum += b.sumRet;
            if (b.n >= minN) {  // V2: configurable minSampleN
                if (b.hitRate() > peakHit) { peakHit = b.hitRate(); peakHour = h; }
                if (b.hitRate() < worstHit) { worstHit = b.hitRate(); worstHour = h; }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Indicator Hour Matrix (UTC hour-of-day) ===\n");
        sb.append("Source     : ").append(sourceName)
                .append(" [").append(filter).append("]\n");
        sb.append("Symbol     : ").append(symbol).append("\n");
        sb.append("Window     : ").append(days).append("d  Horizon: ").append(horizon).append("h  ")
                .append("HitTh: ±").append(hitThPct).append("%  minN: ").append(minN).append("\n");
        sb.append("Sample     : ").append(totalN).append(" outcomes")
                .append("  (events=").append(totalEvents).append(", skipped=").append(skipped).append(")")
                .append("  active hours=").append(activeBuckets).append("/24\n\n");

        sb.append("hour(UTC)   n   hit%    avg_ret   bar\n");
        sb.append("--------- --- ------- --------- -----------------------\n");
        for (int h = 0; h < 24; h++) {
            HourBucket b = buckets[h];
            if (b.n == 0) {
                sb.append(String.format("  %02d:00     -     -        -      %s%n", h, "·"));
                continue;
            }
            int barLen = (int) Math.round(b.hitRate() * 20);
            String bar = "█".repeat(Math.max(0, Math.min(barLen, 20)));
            sb.append(String.format("  %02d:00   %3d  %5.1f%%  %+7.2f%%  %s%n",
                    h, b.n, b.hitRate() * 100, b.avgRet() * 100, bar));
        }

        sb.append("\n");
        if (totalN > 0) {
            double overallHit = (double) totalHits / totalN;
            double overallAvg = totalSum / totalN;
            sb.append(String.format("📊 Overall: hit=%.1f%% (%d/%d), avg_ret=%+.2f%%%n",
                    overallHit * 100, totalHits, totalN, overallAvg * 100));
        }
        if (peakHour >= 0 && peakHit >= 0) {
            sb.append(String.format("🏆 Best hour: %02d:00 UTC (n=%d, hit=%.1f%%)%n",
                    peakHour, buckets[peakHour].n, peakHit * 100));
        }
        if (worstHour >= 0 && worstHit < 1.5) {
            sb.append(String.format("⚠️  Worst hour: %02d:00 UTC (n=%d, hit=%.1f%%)%n",
                    worstHour, buckets[worstHour].n, worstHit * 100));
        }
        if (peakHour >= 0 && worstHour >= 0 && peakHit - worstHit >= 0.30) {
            sb.append(String.format("💡 hit%% spread = %.0fpp → 強烈時段偏好（建議只在 %02d:00 附近用此訊號）%n",
                    (peakHit - worstHit) * 100, peakHour));
        }

        // V2 — Session breakdown (Asia 00-08 / EU 08-16 / US 16-24 UTC)
        if (showSession) {
            sb.append("\n━━━━━━━━━━━━ By UTC Session ━━━━━━━━━━━━\n");
            SessionAgg asia = aggregate(buckets,  0,  8);
            SessionAgg eu   = aggregate(buckets,  8, 16);
            SessionAgg us   = aggregate(buckets, 16, 24);
            sb.append(String.format("ASIA 00-08 UTC: %s%n", formatSession(asia, minN)));
            sb.append(String.format("EU   08-16 UTC: %s%n", formatSession(eu,   minN)));
            sb.append(String.format("US   16-24 UTC: %s%n", formatSession(us,   minN)));

            SessionAgg[] all = {asia, eu, us};
            String[] names = {"ASIA", "EU", "US"};
            int bestI = -1, worstI = -1;
            double bestHit = -1, worstSessHit = 2;
            for (int i = 0; i < 3; i++) {
                if (all[i].n < minN) continue;
                if (all[i].hitRate() > bestHit) { bestHit = all[i].hitRate(); bestI = i; }
                if (all[i].hitRate() < worstSessHit) { worstSessHit = all[i].hitRate(); worstI = i; }
            }
            if (bestI >= 0 && worstI >= 0 && bestI != worstI && bestHit - worstSessHit >= 0.20) {
                sb.append(String.format("💡 Session spread = %.0fpp → %s session 最強，%s 最弱%n",
                        (bestHit - worstSessHit) * 100, names[bestI], names[worstI]));
            }
        }

        return sb.toString();
    }

    private SessionAgg aggregate(HourBucket[] buckets, int hourFrom, int hourTo) {
        SessionAgg s = new SessionAgg();
        for (int h = hourFrom; h < hourTo; h++) {
            s.n += buckets[h].n;
            s.hits += buckets[h].hits;
            s.sumRet += buckets[h].sumRet;
        }
        return s;
    }

    private String formatSession(SessionAgg s, int minN) {
        if (s.n < minN) {
            return String.format("n=%d (insuf., need ≥%d)", s.n, minN);
        }
        return String.format("hit=%.1f%% (%d/%d)  avg_ret=%+.2f%%",
                s.hitRate() * 100, s.hits, s.n, s.avgRet() * 100);
    }

    private static class SessionAgg {
        int n = 0;
        int hits = 0;
        double sumRet = 0;
        double hitRate() { return n == 0 ? 0 : (double) hits / n; }
        double avgRet()  { return n == 0 ? 0 : sumRet / n; }
    }
}
