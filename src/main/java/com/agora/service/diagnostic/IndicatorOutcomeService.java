package com.agora.service.diagnostic;

import com.agora.service.diagnostic.event.Event;
import com.agora.service.diagnostic.event.EventSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * #337 verifyIndicatorOutcome 核心 — 對指定 source + filter 抽 events，逐一查事後 horizon
 * 報酬，產出 hit_rate / 95% CI / 報酬分布 / 最近 5 筆事件詳情。
 *
 * <p>#338 scanIndicatorAccuracy 也共用這層 — 透過 {@link #analyzeOne} 拿 {@link OneResult}
 * 即可批次跑多 source。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorOutcomeService {

    private final List<EventSource> sources;
    private final PriceLookup priceLookup;

    /**
     * 主入口（#337）。
     */
    public String verify(String sourceName, String filter, Integer horizonHours,
                         Double hitThresholdPct, Integer days, String symbol) {
        // —— 1. 參數正規化 ——
        int horizon = (horizonHours != null && horizonHours > 0) ? horizonHours : 24;
        double hitTh = (hitThresholdPct != null) ? Math.abs(hitThresholdPct) : 0.5;
        int d = (days != null && days > 0) ? Math.min(days, 180) : 30;
        String sym = (symbol != null && !symbol.isBlank()) ? symbol.toUpperCase() : "BTCUSDT";
        if (sourceName == null || sourceName.isBlank()) {
            return "❌ source 必填，目前支援：" + supportedSourceNames();
        }
        EventSource source = findSource(sourceName);
        if (source == null) {
            return "❌ unknown source: " + sourceName + " | 目前支援：" + supportedSourceNames();
        }
        // —— 2. 抽 events ——
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = now.minusDays(d);
        LocalDateTime to = now.minusHours(horizon);
        List<Event> events;
        try {
            events = source.fetch(filter, from, to);
        } catch (Exception e) {
            log.warn("[verifyIndicatorOutcome] event extraction failed", e);
            return "❌ event 抽取失敗: " + e.getMessage();
        }
        if (events.isEmpty()) {
            int pending = 0;
            try {
                pending = source.fetch(filter, from, now).size();
            } catch (Exception ignore) {}
            if (pending > 0) {
                return String.format(
                        "⚠️ 無可分析 event：source=%s filter=%s 過去 %dd\n" +
                        "  找到 %d 筆但都在最近 %dh 內 — 尚未滿 horizon buffer，無法評估事後報酬。\n" +
                        "  解法：等 %dh 後重跑，或縮小 horizonHours 參數（例如 horizonHours=4）。",
                        sourceName, filter, d, pending, horizon, horizon);
            }
            return String.format("⚠️ 無 event：source=%s filter=%s 過去 %dd",
                    sourceName, filter, d);
        }
        // —— 3. 建 price cache ——
        PriceLookup.Cache priceCache = priceLookup.buildCache(sym, from, now);
        if (priceCache.isEmpty()) {
            return "❌ price cache 為空（無 " + sym + " 價格資料）";
        }
        // —— 4. 對每 event 算 return ——
        List<Outcome> outcomes = computeOutcomes(events, priceCache, horizon);
        int skipped = events.size() - outcomes.size();
        if (outcomes.isEmpty()) {
            return String.format("⚠️ 全部 %d 個 event 都缺 horizon=%dh 後價格資料", events.size(), horizon);
        }
        // —— 5. aggregate + format verbose ——
        OneResult result = aggregate(sourceName, filter, outcomes, skipped, hitTh / 100.0);
        return formatVerbose(result, sym, d, horizon, hitTh, outcomes);
    }

    /**
     * #338 用：批次模式。回傳 {@link OneResult}（純資料，無格式化）。
     *
     * @param sharedCache  非 null 則重複利用（scan 30+ source 共用一個 cache）；null 自建
     * @return Optional.empty() 若 source 不存在 / 無 event / 全部 skipped
     */
    public Optional<OneResult> analyzeOne(String sourceName, String filter,
                                          int days, int horizonHours,
                                          double hitFractionPct, String symbol,
                                          PriceLookup.Cache sharedCache) {
        if (sourceName == null || sourceName.isBlank()) return Optional.empty();
        EventSource source = findSource(sourceName);
        if (source == null) return Optional.empty();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = now.minusDays(Math.max(1, Math.min(days, 180)));
        LocalDateTime to = now.minusHours(Math.max(1, horizonHours));

        List<Event> events;
        try {
            events = source.fetch(filter, from, to);
        } catch (Exception e) {
            log.warn("[analyzeOne] fetch failed source={} filter={}", sourceName, filter, e);
            return Optional.empty();
        }
        if (events.isEmpty()) return Optional.empty();

        PriceLookup.Cache cache = sharedCache != null ? sharedCache
                : priceLookup.buildCache(symbol, from, now);
        if (cache.isEmpty()) return Optional.empty();

        List<Outcome> outcomes = computeOutcomes(events, cache, horizonHours);
        int skipped = events.size() - outcomes.size();
        if (outcomes.isEmpty()) return Optional.empty();

        return Optional.of(aggregate(sourceName, filter, outcomes, skipped, hitFractionPct / 100.0));
    }

    private List<Outcome> computeOutcomes(List<Event> events, PriceLookup.Cache cache, int horizonHours) {
        List<Outcome> outcomes = new ArrayList<>(events.size());
        for (Event e : events) {
            Optional<Double> ret = cache.returnOver(e.ts(), horizonHours);
            ret.ifPresent(value -> outcomes.add(new Outcome(e, value)));
        }
        return outcomes;
    }

    /** Aggregate 一個事件後的 (event, return) pair */
    private record Outcome(Event event, double returnPct) {}

    /** #338 leaderboard 用的純資料容器
     *  v2 (#351 Phase 2 partial)：加入 stdev / effectSize 防 isHit 對稱性偽陽性 */
    public record OneResult(
            String sourceName,
            String filter,
            int sampleN,
            int skipped,
            long hits,
            double hitRate,
            double avgReturn,
            double stdev,            // v2: return 標準差（給 effect_size 用）
            double effectSize,       // v2: avg_return / stdev，>0.3 強, >0.5 顯著
            long longCnt, long longHits, double avgLongRet,
            long shortCnt, long shortHits, double avgShortRet,
            long neutralCnt,
            String majorityDirection,
            String verdictTag,    // alpha / weak / marginal / noise / contra / lowN / neutral
            String verdictText
    ) {}

    private OneResult aggregate(String sourceName, String filter, List<Outcome> outcomes,
                                int skipped, double hitFraction) {
        int n = outcomes.size();
        long hits = 0, longHits = 0, shortHits = 0, neutralCnt = 0, longCnt = 0, shortCnt = 0;
        double sumReturn = 0, sumLongRet = 0, sumShortRet = 0;
        for (Outcome o : outcomes) {
            double r = o.returnPct;
            sumReturn += r;
            String dir = o.event.direction();
            boolean hit = isHit(dir, r, hitFraction);
            if (hit) hits++;
            switch (dir) {
                case Event.LONG -> { longCnt++; sumLongRet += r; if (hit) longHits++; }
                case Event.SHORT -> { shortCnt++; sumShortRet += r; if (hit) shortHits++; }
                case Event.NEUTRAL -> neutralCnt++;
            }
        }
        double hitRate = (double) hits / n;
        double avgReturn = sumReturn / n;
        double avgLong = longCnt > 0 ? sumLongRet / longCnt : 0;
        double avgShort = shortCnt > 0 ? sumShortRet / shortCnt : 0;

        // v2: stdev + effect_size for noise discrimination
        double sumSq = 0;
        for (Outcome o : outcomes) sumSq += Math.pow(o.returnPct - avgReturn, 2);
        double stdev = n > 1 ? Math.sqrt(sumSq / (n - 1)) : 0;
        double effectSize = stdev > 0 ? avgReturn / stdev : 0;

        String majority = longCnt >= shortCnt && longCnt >= neutralCnt ? Event.LONG
                : shortCnt >= neutralCnt ? Event.SHORT : Event.NEUTRAL;
        String[] verdict = verdictTagAndText(n, hitRate, avgReturn, majority, hitFraction, neutralCnt == n);
        return new OneResult(sourceName, filter, n, skipped, hits, hitRate, avgReturn,
                stdev, effectSize,
                longCnt, longHits, avgLong,
                shortCnt, shortHits, avgShort,
                neutralCnt,
                majority, verdict[0], verdict[1]);
    }

    private String formatVerbose(OneResult r, String symbol, int days, int horizon,
                                 double hitThPct, List<Outcome> outcomes) {
        int n = r.sampleN();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Indicator Outcome Analysis ===\n");
        sb.append("Source     : ").append(r.sourceName())
                .append(" [").append(r.filter() == null ? "" : r.filter()).append("]\n");
        sb.append("Symbol     : ").append(symbol).append("\n");
        sb.append("Window     : ").append(days).append("d  Horizon: ").append(horizon).append("h  ")
                .append("HitTh: ±").append(hitThPct).append("%\n");
        sb.append("Sample     : ").append(n).append(" events");
        if (r.skipped() > 0) sb.append("  (skipped ").append(r.skipped()).append(", price gap)");
        if (n < 10) sb.append("  ⚠️ low N");
        sb.append("\n\n");

        sb.append(String.format("🎯 Hit rate     : %.1f%% (%d/%d)%n", r.hitRate() * 100, r.hits(), n));
        sb.append(String.format("💰 Avg return   : %+.2f%%  (%dh after event)%n", r.avgReturn() * 100, horizon));
        sb.append(String.format("📐 Effect size  : %+.2f  (avg_ret/stdev, |x|≥0.3 strong)%n", r.effectSize()));
        if (r.longCnt() > 0) sb.append(String.format("   ↳ LONG  events: n=%-3d  hit=%-3d  avg=%+.2f%%%n",
                r.longCnt(), r.longHits(), r.avgLongRet() * 100));
        if (r.shortCnt() > 0) sb.append(String.format("   ↳ SHORT events: n=%-3d  hit=%-3d  avg=%+.2f%%%n",
                r.shortCnt(), r.shortHits(), r.avgShortRet() * 100));
        if (r.neutralCnt() > 0) sb.append(String.format("   ↳ NEUTRAL     : n=%-3d (no hit/miss judgment)%n", r.neutralCnt()));
        sb.append("\n");

        // Distribution
        long bGtP5 = 0, b1to5 = 0, b0to1 = 0, bN1to0 = 0, bN5toN1 = 0, bLtN5 = 0;
        for (Outcome o : outcomes) {
            double rr = o.returnPct;
            if      (rr >  0.05)  bGtP5++;
            else if (rr >  0.01)  b1to5++;
            else if (rr >= 0.0)   b0to1++;
            else if (rr >= -0.01) bN1to0++;
            else if (rr >= -0.05) bN5toN1++;
            else                  bLtN5++;
        }
        sb.append("📊 Distribution:\n");
        sb.append(String.format("   > +5%%       : %d  (%.0f%%)%n", bGtP5,   pct(bGtP5,   n)));
        sb.append(String.format("   +1%% ~ +5%%   : %d  (%.0f%%)%n", b1to5,  pct(b1to5,  n)));
        sb.append(String.format("   0   ~ +1%%   : %d  (%.0f%%)%n", b0to1,  pct(b0to1,  n)));
        sb.append(String.format("   -1%% ~  0    : %d  (%.0f%%)%n", bN1to0, pct(bN1to0, n)));
        sb.append(String.format("   -5%% ~ -1%%   : %d  (%.0f%%)%n", bN5toN1, pct(bN5toN1, n)));
        sb.append(String.format("   < -5%%       : %d  (%.0f%%)%n", bLtN5,  pct(bLtN5,  n)));
        sb.append("\n");

        sb.append("🔬 Statistical:\n");
        if (n >= 10) {
            double p = 0.5;
            double z = (r.hitRate() - p) / Math.sqrt(p * (1 - p) / n);
            double zCrit = 1.96;
            double denom = 1 + zCrit * zCrit / n;
            double centre = (r.hitRate() + zCrit * zCrit / (2.0 * n)) / denom;
            double margin = zCrit * Math.sqrt(r.hitRate() * (1 - r.hitRate()) / n + zCrit * zCrit / (4.0 * n * n)) / denom;
            sb.append(String.format("   z vs 0.5 random : %+.2f%n", z));
            sb.append(String.format("   95%% CI hit rate : [%.1f%%, %.1f%%]%n",
                    Math.max(0, centre - margin) * 100, Math.min(1, centre + margin) * 100));
            String confidence = (Math.abs(z) >= 2.0) ? "HIGH"
                              : (Math.abs(z) >= 1.0) ? "MEDIUM" : "LOW";
            sb.append("   Confidence       : ").append(confidence).append("\n");
        } else {
            sb.append("   n<10 → 統計不顯著（建議加大 days 或檢查 filter）\n");
        }
        sb.append("\n");

        sb.append("📅 Recent 5 events:\n");
        double hitFraction = hitThPct / 100.0;
        outcomes.stream()
                .sorted((a, b) -> b.event().ts().compareTo(a.event().ts()))
                .limit(5)
                .forEach(o -> {
                    boolean hit = isHit(o.event().direction(), o.returnPct, hitFraction);
                    sb.append(String.format("   %-16s  %-7s  %+.2f%%  %s  %s%n",
                            o.event().ts().toString().substring(0, 16),
                            o.event().direction(),
                            o.returnPct * 100,
                            hit ? "✅" : (Event.NEUTRAL.equals(o.event().direction()) ? "⚪" : "❌"),
                            o.event().label()));
                });
        sb.append("\n");

        sb.append("✅ Verdict: ").append(r.verdictText()).append("\n");
        return sb.toString();
    }

    private EventSource findSource(String name) {
        return sources.stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private String supportedSourceNames() {
        return sources.stream().map(EventSource::name).collect(Collectors.joining(", "));
    }

    /** 取所有註冊的 EventSource（#338 scanner 用，避免重新 scan Spring context） */
    public List<EventSource> allSources() {
        return List.copyOf(sources);
    }

    /** Expose 給 #338 — 直接拿 PriceLookup（建 shared cache 用） */
    public PriceLookup priceLookup() {
        return priceLookup;
    }

    private static boolean isHit(String direction, double returnPct, double hitFraction) {
        return switch (direction) {
            case Event.LONG  -> returnPct >  hitFraction;
            case Event.SHORT -> returnPct < -hitFraction;
            case Event.NEUTRAL -> false;
            default -> false;
        };
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole * 100;
    }

    /**
     * v2 (#351 Phase 2 partial)：加 avg_return × direction 一致性檢查，防 isHit 對稱性偽陽性。
     *
     * <p>原本單看 hit_rate < 40% 就判 contra，但中性區 (-hitFraction, +hitFraction) 兩邊都 not hit，
     * avg_return 接近 0 時兩邊 hit% 都會 < 50%。例如 btc_short_liq_ratio_1h:gt:0.6:
     * LONG hit 36%, SHORT hit 40%, avg_return +0.02% — 真相是 noise 而非 contra。
     *
     * <p>新邏輯：alpha/contra 判定要求 avg_return 跟 majority direction 一致 ≥ hitFraction。
     */
    private static String[] verdictTagAndText(int n, double hitRate, double avgReturn,
                                              String majorityDirection, double hitFraction,
                                              boolean allNeutral) {
        if (allNeutral) return new String[]{"neutral", "all NEUTRAL events (no hit/miss)"};
        if (n < 10)     return new String[]{"lowN", "insufficient sample, accumulate more"};

        // 把 avg_return 投影到 majority direction 軸上：LONG→+, SHORT→-
        double directionalReturn = switch (majorityDirection) {
            case Event.LONG -> avgReturn;
            case Event.SHORT -> -avgReturn;
            default -> 0;
        };

        // alpha：hit rate 高 AND avg_return 跟 direction 一致到門檻
        if (hitRate >= 0.60 && directionalReturn >= hitFraction) {
            return new String[]{"alpha", "alpha (hit ≥60% + avg_return aligned with direction)"};
        }
        if (hitRate >= 0.55 && directionalReturn > 0) {
            return new String[]{"weak", "weak alpha"};
        }
        if (hitRate >= 0.45) return new String[]{"marginal", "marginal (45-55%)"};
        if (hitRate >= 0.40) return new String[]{"noise", "below random (40-45%)"};

        // 低 hit rate 區分 noise vs contra：要 avg_return 反向且夠強才算 contra
        if (-directionalReturn >= hitFraction) {
            return new String[]{"contra", "contra alpha (反向 avg_return 顯著)"};
        }
        return new String[]{"noise", "noise (低 hit% 但 avg_return 中性 — 非真 contra)"};
    }
}
