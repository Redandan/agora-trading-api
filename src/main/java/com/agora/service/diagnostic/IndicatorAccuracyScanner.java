package com.agora.service.diagnostic;

import com.agora.repository.system.TgNotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * #338 scanIndicatorAccuracy — 自動枚舉 source × filter 組合，批次跑 #337 核心邏輯，
 * 產出 Tier 分組的事後正確率排行榜。
 *
 * <p>V1 範圍：
 * <ul>
 *   <li>TG sources：自動 distinct（過濾掉 *Scheduler / system / null）</li>
 *   <li>mih_threshold：16 條 hardcoded preset（issue body 列表）</li>
 *   <li>ML / strategy / attention rule：V1.1 再加</li>
 * </ul>
 *
 * <p>性能：所有 source 共用同一個 {@link PriceLookup.Cache}，避免重複建表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorAccuracyScanner {

    private final IndicatorOutcomeService outcomeService;
    private final TgNotificationLogRepository tgRepo;

    /** mih_threshold preset 清單 — 來自 #338 issue body */
    private static final List<String> MIH_PRESETS = List.of(
            "funding_rate:lte:-0.0001",        // BTC funding 範圍 ±1e-4，調整為現實值
            "funding_rate:lte:-0.00007",
            "long_short_ratio:lt:0.85",
            "long_short_ratio:lt:0.75",
            "whale_buy_ratio:gt:0.65",
            "whale_buy_ratio:lt:0.30",
            "sqi:gte:40",
            // sqi:gte:30 removed (#386): 5/3 leaderboard n=56 hit=1.8% — extreme noise
            "short_build_index:gte:60",
            "vdi:lt:30",
            "vdi:gte:75",
            "mei:gte:70",
            "btc_basis_pct:lt:-0.1",
            "btc_short_liq_ratio_1h:gt:0.6",
            "oi_change_pct_1h:gt:5",
            "oi_change_pct_1h:lt:-1.5",
            // v3 (#338 補進)：F&G 極端區
            "fear_greed:lt:25",                // 極度恐懼 → LONG（mean-revert）
            "fear_greed:gt:75"                 // 極度貪婪 → SHORT（修正風險）
    );

    /** Tier 1：微觀結構 (orderbook / position / funding) — TG source 與 mih indicator name */
    private static final List<String> TIER1_KEYS = List.of(
            "ShortBuildIndicator", "WhaleBuyMonitor",
            "FundingRateMonitor", "LongShortRatioMonitor",
            "OpenInterestMonitor", "LiquidationMonitor",
            "funding_rate", "long_short_ratio", "whale_buy_ratio",
            "sqi", "short_build_index", "btc_basis_pct",
            "btc_short_liq_ratio_1h", "oi_change_pct_1h"
    );

    /** Tier 2：情緒 / 技術 */
    private static final List<String> TIER2_KEYS = List.of(
            "MarketEntropyIndicator", "StablecoinDemandIndicator",
            "EtfPressureIndicator", "VolumeDecayIndicator",
            "vdi", "mei", "fear_greed"
    );

    /** TG source 黑名單（系統 / scheduler 不算指標）*/
    private static final List<String> TG_BLACKLIST_KEYWORDS = List.of(
            "system", "Scheduler", "scheduler"
    );

    /**
     * 主入口。
     *
     * @return 格式化的 leaderboard（直接給 MCP 回 user）
     */
    public String scan(Integer days, Integer horizonHours, Double hitThresholdPct,
                       String symbol, Integer minSampleN, String sortBy, Boolean groupByTier) {
        // —— 1. 參數正規化 ——
        int d = (days != null && days > 0) ? Math.min(days, 180) : 30;
        int horizon = (horizonHours != null && horizonHours > 0) ? horizonHours : 24;
        double hitTh = (hitThresholdPct != null) ? Math.abs(hitThresholdPct) : 0.5;
        String sym = (symbol != null && !symbol.isBlank()) ? symbol.toUpperCase() : "BTCUSDT";
        int minN = (minSampleN != null && minSampleN > 0) ? minSampleN : 3;
        String sort = (sortBy != null && !sortBy.isBlank()) ? sortBy.toLowerCase() : "hit_rate";
        boolean grouped = groupByTier == null || groupByTier;

        // —— 2. 蒐集 (sourceName, filter) 候選 ——
        List<TaskSpec> tasks = collectTasks(d);
        if (tasks.isEmpty()) {
            return "⚠️ 無候選 source（過去 " + d + "d 內 TG 無 indicator + mih preset 全數異常）";
        }

        // —— 3. 建 shared price cache（一次建立，所有 source 共用）——
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = now.minusDays(d);
        PriceLookup.Cache priceCache = outcomeService.priceLookup().buildCache(sym, from, now);
        if (priceCache.isEmpty()) {
            return "❌ price cache 為空（無 " + sym + " 價格資料）";
        }

        // —— 4. 跑批次 analyzeOne ——
        List<Row> rows = new ArrayList<>(tasks.size());
        long startMs = System.currentTimeMillis();
        for (TaskSpec t : tasks) {
            Optional<IndicatorOutcomeService.OneResult> opt = outcomeService.analyzeOne(
                    t.source, t.filter, d, horizon, hitTh, sym, priceCache);
            if (opt.isPresent()) {
                rows.add(new Row(t, opt.get()));
            } else {
                rows.add(new Row(t, null));
            }
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        // —— 5. 過濾 + 排序 ——
        List<Row> visibleRows = rows.stream()
                .filter(r -> r.result != null)
                .sorted(comparator(sort))
                .toList();

        // —— 6. 格式化 ——
        return format(visibleRows, sym, d, horizon, hitTh, minN, grouped, rows.size(), elapsedMs);
    }

    private record TaskSpec(String source, String filter, String tier, String displayLabel) {}

    private record Row(TaskSpec task, IndicatorOutcomeService.OneResult result) {}

    private List<TaskSpec> collectTasks(int days) {
        List<TaskSpec> tasks = new ArrayList<>();
        // mih_threshold presets
        for (String preset : MIH_PRESETS) {
            String indicator = preset.split(":", 2)[0];
            tasks.add(new TaskSpec("mih_threshold", preset, classifyTier(indicator), preset));
        }
        // TG distinct sources (過去 days 天)
        try {
            LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
            List<String> sources = tgRepo.findDistinctSourcesSince(from);
            for (String src : sources) {
                if (src == null || src.isBlank()) continue;
                if (isBlacklisted(src)) continue;
                tasks.add(new TaskSpec("tg_indicator", src, classifyTier(src), src));
            }
        } catch (Exception e) {
            log.warn("[scanIndicatorAccuracy] TG source enumeration failed: {}", e.getMessage());
        }
        return tasks;
    }

    /** #352 AlphaPromotionTracker 用：取 mih preset 清單（filter strings） */
    public List<String[]> mihPresetsView() {
        List<String[]> result = new ArrayList<>(MIH_PRESETS.size());
        for (String p : MIH_PRESETS) {
            String indicator = p.split(":", 2)[0];
            result.add(new String[]{p, classifyTier(indicator)});
        }
        return result;
    }

    /** #352 AlphaPromotionTracker 用：取過去 days 內 TG distinct sources（已過濾 blacklist） */
    public List<String> tgDistinctSourcesView(int days) {
        try {
            LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
            return tgRepo.findDistinctSourcesSince(from).stream()
                    .filter(s -> s != null && !s.isBlank() && !isBlacklisted(s))
                    .toList();
        } catch (Exception e) {
            log.warn("[tgDistinctSourcesView] failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isBlacklisted(String src) {
        for (String kw : TG_BLACKLIST_KEYWORDS) {
            if (src.contains(kw)) return true;
        }
        return false;
    }

    private static String classifyTier(String key) {
        if (key == null) return "Other";
        String stem = key.split("[#:]", 2)[0].trim();
        if (TIER1_KEYS.contains(stem)) return "Tier 1";
        if (TIER2_KEYS.contains(stem)) return "Tier 2";
        return "Other";
    }

    private static Comparator<Row> comparator(String sort) {
        // 沒結果（result == null）排最後
        Comparator<Row> base = switch (sort) {
            case "avg_return" -> Comparator.comparingDouble((Row r) -> r.result.avgReturn()).reversed();
            case "sample_n"   -> Comparator.comparingInt((Row r) -> r.result.sampleN()).reversed();
            case "tier"       -> Comparator.comparing((Row r) -> r.task.tier);
            default           -> Comparator.comparingDouble((Row r) -> r.result.hitRate()).reversed();
        };
        return base;
    }

    private String format(List<Row> rows, String symbol, int days, int horizon,
                          double hitThPct, int minN, boolean grouped, int totalCandidates, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Indicator Accuracy Leaderboard (")
                .append(days).append("d, ").append(horizon).append("h horizon) ===\n");
        sb.append("Symbol: ").append(symbol)
                .append("  |  Hit threshold: ±").append(hitThPct).append("%")
                .append("  |  Candidates: ").append(totalCandidates)
                .append("  |  Elapsed: ").append(elapsedMs).append("ms\n\n");

        if (rows.isEmpty()) {
            sb.append("⚠️ 無 source 跑出有效樣本（全部 0 events 或 price gap）。\n");
            return sb.toString();
        }

        // —— Tier grouping ——
        Map<String, List<Row>> byTier = new LinkedHashMap<>();
        if (grouped) {
            byTier.put("Tier 1", new ArrayList<>());
            byTier.put("Tier 2", new ArrayList<>());
            byTier.put("Other", new ArrayList<>());
            for (Row r : rows) {
                byTier.get(r.task.tier).add(r);
            }
        } else {
            byTier.put("All", rows);
        }

        // 顯示每組
        for (Map.Entry<String, List<Row>> e : byTier.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sb.append("━━━━━━━━━━━━ ").append(e.getKey()).append(" ━━━━━━━━━━━━\n");
            sb.append(String.format("%-32s %-8s %4s %7s %9s %7s  %s%n",
                    "source/filter", "dir", "n", "hit%", "avg_ret", "effect", "verdict"));
            for (Row r : e.getValue()) {
                IndicatorOutcomeService.OneResult res = r.result;
                String emoji = verdictEmoji(res.verdictTag(), res.sampleN(), minN);
                sb.append(String.format("%-32s %-8s %4d %6.1f%% %+8.2f%% %+7.2f  %s %s%n",
                        truncate(r.task.displayLabel, 32),
                        res.majorityDirection(),
                        res.sampleN(),
                        res.hitRate() * 100,
                        res.avgReturn() * 100,
                        res.effectSize(),
                        emoji,
                        res.verdictTag()));
            }
            sb.append("\n");
        }

        // —— Summary ——
        long alpha = rows.stream().filter(r -> "alpha".equals(r.result.verdictTag()) && r.result.sampleN() >= 10).count();
        long weak = rows.stream().filter(r -> "weak".equals(r.result.verdictTag()) && r.result.sampleN() >= 10).count();
        long marginal = rows.stream().filter(r -> "marginal".equals(r.result.verdictTag()) && r.result.sampleN() >= 10).count();
        long noise = rows.stream().filter(r -> "noise".equals(r.result.verdictTag()) && r.result.sampleN() >= 10).count();
        long contra = rows.stream().filter(r -> "contra".equals(r.result.verdictTag()) && r.result.sampleN() >= 10).count();
        long lowN = rows.stream().filter(r -> r.result.sampleN() < minN || "lowN".equals(r.result.verdictTag())).count();

        sb.append("━━━━━━━━━━━━ Summary ━━━━━━━━━━━━\n");
        sb.append(String.format("✅ alpha    (hit ≥60%%, n≥10): %d%n", alpha));
        sb.append(String.format("🟢 weak alpha (55-60%%, n≥10): %d%n", weak));
        sb.append(String.format("🟡 marginal   (45-55%%, n≥10): %d%n", marginal));
        sb.append(String.format("❌ noise/below (40-45%%, n≥10): %d%n", noise));
        sb.append(String.format("🔻 contra     (<40%%, n≥10):    %d  ← 考慮反向用%n", contra));
        sb.append(String.format("⏳ low N      (n<%d 或<10):     %d%n", minN, lowN));

        return sb.toString();
    }

    private static String verdictEmoji(String tag, int n, int minN) {
        if (n < minN) return "⏳";
        return switch (tag) {
            case "alpha"    -> "✅";
            case "weak"     -> "🟢";
            case "marginal" -> "🟡";
            case "noise"    -> "❌";
            case "contra"   -> "🔻";
            case "lowN"     -> "⏳";
            case "neutral"  -> "⚪";
            default         -> "  ";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
