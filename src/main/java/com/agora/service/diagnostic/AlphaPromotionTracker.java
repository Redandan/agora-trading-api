package com.agora.service.diagnostic;

import com.agora.infra.notification.NotificationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * #352 AlphaPromotionTracker — 每週日 09:00 UTC 自動跑 #338 scanIndicatorAccuracy，
 * 跟上週 snapshot 比對，找出：
 * <ul>
 *   <li>✅ 新升級 alpha：原 lowN（n&lt;10）→ 現 n≥10 + hit≥60%</li>
 *   <li>🔻 新淪為 contra：原 alpha/marginal → 現 hit&lt;40% + avg_return 反向</li>
 * </ul>
 *
 * <p>有變更才發 TG（避免噪音）。第一次跑只寫 baseline，跳過比較。
 *
 * <p>Snapshot 存在 {@code SNAPSHOT_PATH}（預設 ~/.agora/alpha-tracker-snapshot.json），
 * 重啟後仍可比對。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlphaPromotionTracker {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IndicatorOutcomeService outcomeService;
    private final IndicatorAccuracyScanner scanner;
    private final NotificationPort tg;
    private final ObjectMapper objectMapper;

    /** Snapshot file 位置（可被 application.yml 覆蓋） */
    @Value("${agora.alpha-tracker.snapshot-path:/home/ubuntu/agora-trading-api/alpha-tracker-snapshot.json}")
    private String snapshotPath;

    /** Cron：每週日 09:00 UTC */
    @Scheduled(cron = "0 0 9 * * SUN", zone = "UTC")
    public void weeklyScan() {
        log.info("[AlphaPromotionTracker] weekly scan starting");
        try {
            scanAndCompare();
        } catch (Exception e) {
            log.warn("[AlphaPromotionTracker] weekly scan failed", e);
        }
    }

    /** 公開呼叫（給 MCP @Tool runAlphaPromotionTracker） */
    public String scanAndCompare() {
        // 1. 跑 scan 拿 30d × 24h baseline
        List<EntrySnapshot> currentEntries = collectCurrentEntries();
        if (currentEntries.isEmpty()) {
            log.warn("[AlphaPromotionTracker] no entries from scan");
            return "❌ scan 無候選";
        }

        // 2. 讀上次 snapshot
        Map<String, EntrySnapshot> previousMap = loadSnapshot();
        boolean firstRun = previousMap.isEmpty();

        // 3. 比較
        List<EntrySnapshot> newlyPromoted = new ArrayList<>();
        List<EntrySnapshot> newlyContra = new ArrayList<>();
        if (!firstRun) {
            for (EntrySnapshot cur : currentEntries) {
                EntrySnapshot prev = previousMap.get(cur.key());
                boolean wasLowN = prev == null || prev.sampleN < 10;
                boolean nowAlpha = cur.sampleN >= 10 && cur.hitRate >= 0.60
                        && Math.abs(cur.avgReturn) >= 0.005;  // avg ≥ 0.5% 一致性
                if (wasLowN && nowAlpha) newlyPromoted.add(cur);

                boolean wasAlphaOrMarginal = prev != null && prev.sampleN >= 10
                        && prev.hitRate >= 0.45;
                boolean nowContra = cur.sampleN >= 10 && cur.hitRate < 0.40
                        && cur.avgReturn * directionSign(cur.direction) < -0.005;
                if (wasAlphaOrMarginal && nowContra) newlyContra.add(cur);
            }
        }

        // 4. 寫新 snapshot
        saveSnapshot(currentEntries);

        // 5. 發 TG
        String today = LocalDateTime.now(ZoneOffset.UTC).format(DATE_FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 Alpha Tracker — ").append(today).append("\n\n");

        if (firstRun) {
            sb.append("✅ Baseline 已建立（").append(currentEntries.size()).append(" sources）\n");
            sb.append("下週日比對後若有 promotion/demotion 會推播。\n");
            log.info("[AlphaPromotionTracker] baseline established (first run)");
            return sb.toString();
        }

        if (newlyPromoted.isEmpty() && newlyContra.isEmpty()) {
            sb.append("☑️ 本週無變更（lowN→alpha 0, alpha→contra 0）\n");
            log.info("[AlphaPromotionTracker] no change this week");
            return sb.toString();
        }

        if (!newlyPromoted.isEmpty()) {
            sb.append("✅ 新升級為 alpha (lowN → n≥10 hit≥60%):\n");
            for (EntrySnapshot e : newlyPromoted) {
                sb.append(String.format("  • %s  %s  n=%d  hit=%.1f%%  avg=%+.2f%%%n",
                        e.label, e.direction, e.sampleN, e.hitRate * 100, e.avgReturn * 100));
            }
            sb.append("\n");
        }
        if (!newlyContra.isEmpty()) {
            sb.append("🔻 新淪為 contra (原 alpha/marginal → hit<40% 反向 alpha):\n");
            for (EntrySnapshot e : newlyContra) {
                sb.append(String.format("  • %s  %s  n=%d  hit=%.1f%%  avg=%+.2f%%%n",
                        e.label, e.direction, e.sampleN, e.hitRate * 100, e.avgReturn * 100));
            }
            sb.append("\n");
        }
        sb.append("📊 完整 leaderboard：scanIndicatorAccuracy(days=30)");

        String msg = sb.toString();
        try {
            tg.broadcast(msg);
            log.info("[AlphaPromotionTracker] TG alert sent: promoted={} contra={}",
                    newlyPromoted.size(), newlyContra.size());
        } catch (Exception e) {
            log.warn("[AlphaPromotionTracker] TG send failed", e);
        }
        return msg;
    }

    private List<EntrySnapshot> collectCurrentEntries() {
        // 直接呼 IndicatorAccuracyScanner.scan 預設參數，再 reparse 不太理想；
        // V1 用「掃所有 mih_threshold preset + TG distinct」內部邏輯：
        // 為避免重複實作，這裡呼 OneResult level
        // 但 scan() 只回 String → 改用 collectTasks + analyzeOne 重跑（共用 cache）
        List<EntrySnapshot> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime from = now.minusDays(30);
        PriceLookup.Cache priceCache = outcomeService.priceLookup().buildCache("BTCUSDT", from, now);
        if (priceCache.isEmpty()) return result;

        // mih presets — 跟 IndicatorAccuracyScanner 對齊
        List<String[]> mihPresets = scanner.mihPresetsView();
        for (String[] presetPair : mihPresets) {
            String filter = presetPair[0];
            Optional<IndicatorOutcomeService.OneResult> opt = outcomeService.analyzeOne(
                    "mih_threshold", filter, 30, 24, 0.5, "BTCUSDT", priceCache);
            opt.ifPresent(r -> result.add(EntrySnapshot.from("mih_threshold", filter, r)));
        }
        // TG distinct sources
        List<String> tgSources = scanner.tgDistinctSourcesView(30);
        for (String src : tgSources) {
            Optional<IndicatorOutcomeService.OneResult> opt = outcomeService.analyzeOne(
                    "tg_indicator", src, 30, 24, 0.5, "BTCUSDT", priceCache);
            opt.ifPresent(r -> result.add(EntrySnapshot.from("tg_indicator", src, r)));
        }
        return result;
    }

    private Map<String, EntrySnapshot> loadSnapshot() {
        File f = new File(snapshotPath);
        if (!f.exists() || f.length() == 0) return new HashMap<>();
        try {
            EntrySnapshot[] arr = objectMapper.readValue(f, EntrySnapshot[].class);
            Map<String, EntrySnapshot> map = new HashMap<>();
            for (EntrySnapshot e : arr) map.put(e.key(), e);
            return map;
        } catch (Exception e) {
            log.warn("[AlphaPromotionTracker] load snapshot failed: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveSnapshot(List<EntrySnapshot> entries) {
        try {
            File f = new File(snapshotPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            objectMapper.writeValue(f, entries);
        } catch (Exception e) {
            log.warn("[AlphaPromotionTracker] save snapshot failed: {}", e.getMessage());
        }
    }

    private static int directionSign(String direction) {
        return switch (direction) {
            case "LONG" -> 1;
            case "SHORT" -> -1;
            default -> 0;
        };
    }

    /** Snapshot entry — JSON-serializable */
    public static class EntrySnapshot {
        public String source;
        public String filter;
        public String label;
        public String direction;
        public int sampleN;
        public double hitRate;
        public double avgReturn;
        public String verdictTag;

        public EntrySnapshot() {}

        static EntrySnapshot from(String source, String filter, IndicatorOutcomeService.OneResult r) {
            EntrySnapshot e = new EntrySnapshot();
            e.source = source;
            e.filter = filter;
            e.label = filter;
            e.direction = r.majorityDirection();
            e.sampleN = r.sampleN();
            e.hitRate = r.hitRate();
            e.avgReturn = r.avgReturn();
            e.verdictTag = r.verdictTag();
            return e;
        }

        String key() { return source + "::" + filter; }
    }
}
