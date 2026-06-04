package com.agora.service.market;

import com.agora.config.PolymarketKeywords;
import com.agora.model.MdKline;
import com.agora.model.PolymarketAlertLog;
import com.agora.model.PolymarketOddsSnapshot;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.PolymarketAlertLogRepository;
import com.agora.repository.trading.PolymarketOddsSnapshotRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ai.GeminiApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polymarket 預警監控。
 *
 * <p>每 15 分鐘掃描 BTC 相關預測市場，偵測四類信號：
 * <ol>
 *   <li>賠率急速移動（1h delta）</li>
 *   <li>Volume 超出滾動平均倍數（聰明錢流入）</li>
 *   <li>單筆大注（data-api 偵測，含 Yes+No 端）</li>
 *   <li>24h 累積趨勢漂移（TREND_DRIFT — 4次各 4% 的漸進移動也能被捕捉）</li>
 * </ol>
 *
 * <p>優化重點：
 * <ul>
 *   <li>市場列表快取 30 分鐘（Gamma API 呼叫從 8×/15min → 8×/30min）</li>
 *   <li>data-api 大注查詢全並行（虛擬執行緒，21 個市場同時 fetch → 總等待從 ~6s 降到 ~1s）</li>
 *   <li>DB 查詢批次化（從 63 次 N+1 → 5 次 batch query，不管市場數量）</li>
 *   <li>Strength-aware dedup（EXTREME 可突破同市場 60min 內的 MEDIUM/HIGH 壓制）</li>
 *   <li>Volume 加權強度（低流動性市場強度降一級，減少噪音）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolymarketMonitorService {

    private static final String GAMMA_BASE    = "https://gamma-api.polymarket.com";
    private static final String DATA_API_BASE = "https://data-api.polymarket.com";
    private static final long   MIN_VOLUME_USDC = 50_000L;

    // Market list cache TTL (Gamma API: markets change slowly, no need to hit every 15 min)
    private static final long MARKET_CACHE_TTL_MS = 30 * 60 * 1000L;

    // Odds delta thresholds (absolute, 0-1 scale)
    private static final double ODDS_MEDIUM  = 0.05;
    private static final double ODDS_HIGH    = 0.10;
    private static final double ODDS_EXTREME = 0.20;

    // Volume spike ratio thresholds (multiple of 7-day rolling avg)
    private static final double SPIKE_MEDIUM  =  5.0;
    private static final double SPIKE_HIGH    = 10.0;
    private static final double SPIKE_EXTREME = 20.0;

    // Single-bet USDC thresholds (Yes OR No outcome)
    private static final double BET_MEDIUM  =  20_000;
    private static final double BET_HIGH    = 100_000;
    private static final double BET_EXTREME = 300_000;

    // 24h cumulative drift thresholds (P2-7: catches slow creep that 1h windows miss)
    private static final double DRIFT_HIGH    = 0.15;
    private static final double DRIFT_EXTREME = 0.20;

    // Volume tier cutoffs for signal strength adjustment (P2-8)
    private static final double VOL_LOW_THRESHOLD = 100_000;   // below: demote strength 1 level

    // Strength rank for dedup comparison (P1-5)
    private static final Map<String, Integer> STRENGTH_RANK =
            Map.of("MEDIUM", 1, "HIGH", 2, "EXTREME", 3);

    // Same-tick category grouping: ≥N signals in same category → batch summary instead of N individual messages
    private static final int SUMMARY_THRESHOLD = 3;

    // Cross-tick topic cooldown: if a category has already fired ≥N non-EXTREME alerts in the rolling
    // window, silently drop further MEDIUM/HIGH from that category. EXTREME always passes and does
    // NOT consume quota (so bursts of EXTREME don't starve subsequent legitimate non-EXTREME alerts).
    // Motivation: observed 125 alerts / 24h where 100% were same underlying topic sliced across
    // 15+ sub-markets (e.g. "US x Iran ... by Apr 21 / Apr 22 / Apr 30 / ...").
    private static final int  CATEGORY_NOISE_THRESHOLD  = 3;
    private static final long CATEGORY_NOISE_WINDOW_MIN = 45L;

    private static final Map<String, String> KEYWORD_RELEVANCE = PolymarketKeywords.KEYWORD_RELEVANCE;
    private static final Map<String, String> KEYWORD_CATEGORY   = PolymarketKeywords.KEYWORD_CATEGORY;

    private final PolymarketOddsSnapshotRepository snapshotRepo;
    private final PolymarketAlertLogRepository      alertRepo;
    private final MdKlineRepository                 klineRepo;
    private final NotificationPort                   notificationPort;
    private final ObjectMapper                      objectMapper;
    private final GeminiApiClient                   geminiApiClient;

    /** MEDIUM 級別信號佇列 — 每日統一彙整，不即時推送。Thread-safe。 */
    private final List<PolymarketAlertLog> mediumDigestQueue =
            Collections.synchronizedList(new ArrayList<>());
    /** HIGH 級別信號佇列 — 每 4 小時彙整，避免洗版。 */
    private final List<PolymarketAlertLog> highDigestQueue =
            Collections.synchronizedList(new ArrayList<>());

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    // Market list cache (AtomicReference for visibility; scheduler is single-threaded but safe)
    private record CachedMarkets(Map<String, MarketData> markets, long cachedAtMs) {}
    private final AtomicReference<CachedMarkets> marketCache = new AtomicReference<>(null);

    // Virtual thread pool for parallel data-api bet fetching (Java 21)
    private final ExecutorService betFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Rolling dispatch log per category for cross-tick cooldown (only non-EXTREME recorded).
    // Single-threaded scheduler writes; ConcurrentHashMap is defensive/cheap.
    private final Map<String, Deque<LocalDateTime>> categoryDispatchLog = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Public API called by scheduler
    // -----------------------------------------------------------------------

    public void runSnapshot() {
        // ── 1. Market list (cached 30 min; Gamma API only called when stale) ──
        Map<String, MarketData> markets = fetchAllRelevantMarkets();
        if (markets.isEmpty()) {
            log.info("[PolymarketMonitor] No relevant markets fetched");
            return;
        }

        Set<String> ids     = markets.keySet();
        BigDecimal btcPrice = latestBtcPrice();
        LocalDateTime now   = LocalDateTime.now();

        // ── 2. Batch DB (5 queries total regardless of market count) ──────────
        //    Previously: 3 × N queries (N=21 markets → 63 individual queries)
        Map<String, PolymarketOddsSnapshot> prevSnaps  = batchLatestSnaps(ids);
        Map<String, PolymarketOddsSnapshot> snaps1h    = batchWindowSnaps(ids,
                now.minusMinutes(75), now.minusMinutes(45));          // closest to 1h ago
        Map<String, BigDecimal>             rollAvgs   = batchRollingAvg(ids, now.minusDays(7));
        Map<String, PolymarketOddsSnapshot> snaps24h   = batchWindowSnaps(ids,
                now.minusHours(25),   now.minusHours(23));            // closest to 24h ago
        Map<String, PolymarketAlertLog>     lastAlerts = batchLastAlert(ids, now.minusMinutes(60));

        // ── 3. Parallel data-api bet fetch (all markets simultaneously) ────────
        //    Previously: 21 serial HTTP calls (~6s+); now: ~1s concurrent
        Map<String, BigDecimal> bets = fetchBetsParallel(markets.values());

        // ── 4. Per-market processing (pure in-memory, no more per-market DB/HTTP) ──
        //    Collect all alerts first; dispatch at end for same-category batch grouping.
        List<PendingAlert> pendingAlerts = new ArrayList<>();
        for (MarketData m : markets.values()) {
            try {
                PendingAlert pa = processMarket(m, btcPrice, now,
                        prevSnaps.get(m.id()), snaps1h.get(m.id()),
                        rollAvgs.get(m.id()), snaps24h.get(m.id()),
                        bets.get(m.id()), lastAlerts.get(m.id()));
                if (pa != null) pendingAlerts.add(pa);
            } catch (Exception e) {
                log.warn("[PolymarketMonitor] Failed market '{}': {}", m.title(), e.getMessage());
            }
        }
        dispatchAlerts(pendingAlerts);
        log.info("[PolymarketMonitor] tick done — markets={} alerts={}", markets.size(), pendingAlerts.size());
    }

    /**
     * Called by scheduler every 30 min to fill btc_price_4h_later for ML labels.
     *
     * <p>Two passes:
     * <ol>
     *   <li>Normal: alerts fired 4h ± 2h ago — uses current BTC price (within window)</li>
     *   <li>Orphan: alerts older than 8h with NULL label — backfills from historical kline</li>
     * </ol>
     */
    public void backfill4hBtcPrice() {
        // Pass 1 — normal window (expanded from ±30m to ±2h for scheduler jitter tolerance)
        LocalDateTime to   = LocalDateTime.now().minusHours(4);
        LocalDateTime from = to.minusHours(2);
        List<PolymarketAlertLog> pending = alertRepo.findByNotifiedAtBetweenAndBtcPrice4hLaterIsNull(from, to);
        if (!pending.isEmpty()) {
            BigDecimal btcNow = latestBtcPrice();
            if (btcNow != null) {
                applyBtcPrice(pending, btcNow);
                alertRepo.saveAll(pending);
                log.info("[PolymarketMonitor] backfilled {} alert(s) with current BTC price", pending.size());
            }
        }

        // Pass 2 — orphan scan: alerts older than 8h still with NULL label
        List<PolymarketAlertLog> orphans =
                alertRepo.findByBtcPrice4hLaterIsNullAndNotifiedAtBefore(LocalDateTime.now().minusHours(8));
        if (orphans.isEmpty()) return;

        List<PolymarketAlertLog> toSave = new ArrayList<>();
        for (PolymarketAlertLog orphan : orphans) {
            LocalDateTime targetHour = orphan.getNotifiedAt().plusHours(4)
                    .withMinute(0).withSecond(0).withNano(0);
            List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                    "BTCUSDT", "1h", targetHour, targetHour.plusMinutes(90));
            if (klines.isEmpty()) continue;
            applyBtcPrice(List.of(orphan), klines.get(0).getClosePrice());
            toSave.add(orphan);
        }
        if (!toSave.isEmpty()) {
            alertRepo.saveAll(toSave);
            log.info("[PolymarketMonitor] orphan backfill: {} alert(s) from kline history", toSave.size());
        }
        int missed = orphans.size() - toSave.size();
        if (missed > 0)
            log.warn("[PolymarketMonitor] {} orphan(s) could not backfill (no kline at target hour)", missed);
    }

    private void applyBtcPrice(List<PolymarketAlertLog> alerts, BigDecimal btcPrice) {
        for (PolymarketAlertLog a : alerts) {
            if (a.getBtcPriceAtAlert() != null && a.getBtcPriceAtAlert().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = btcPrice.subtract(a.getBtcPriceAtAlert())
                        .divide(a.getBtcPriceAtAlert(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
                a.setBtcPrice4hLater(btcPrice);
                a.setBtcPctChange4h(pct);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Batch DB helpers (replace N+1 pattern)
    // -----------------------------------------------------------------------

    /** Latest snapshot per market — one subquery instead of N separate top-1 queries. */
    private Map<String, PolymarketOddsSnapshot> batchLatestSnaps(Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<String, PolymarketOddsSnapshot> result = new LinkedHashMap<>();
        snapshotRepo.findLatestForMarkets(ids)
                .forEach(s -> result.putIfAbsent(s.getMarketId(), s));
        return result;
    }

    /**
     * Snapshots within a time window for all markets — one query, ordered DESC.
     * putIfAbsent keeps the first (= latest in window) per market.
     * Reused for both 1h-ago and 24h-ago lookups (different window params).
     */
    private Map<String, PolymarketOddsSnapshot> batchWindowSnaps(
            Set<String> ids, LocalDateTime from, LocalDateTime to) {
        if (ids.isEmpty()) return Map.of();
        Map<String, PolymarketOddsSnapshot> result = new LinkedHashMap<>();
        snapshotRepo.findInWindowForMarkets(ids, from, to)
                .forEach(s -> result.putIfAbsent(s.getMarketId(), s));
        return result;
    }

    /** Rolling avg per market — one GROUP BY query instead of N avg queries. */
    private Map<String, BigDecimal> batchRollingAvg(Set<String> ids, LocalDateTime since) {
        if (ids.isEmpty()) return Map.of();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        snapshotRepo.findRollingAvgForMarkets(ids, since).forEach(row -> {
            Object avg = row[1];
            if (avg != null && ((Number) avg).doubleValue() > 0) {
                result.put((String) row[0],
                        BigDecimal.valueOf(((Number) avg).doubleValue()).setScale(2, RoundingMode.HALF_UP));
            }
        });
        return result;
    }

    /** Most recent alert per market for strength-aware dedup — one query, ordered DESC. */
    private Map<String, PolymarketAlertLog> batchLastAlert(Set<String> ids, LocalDateTime since) {
        if (ids.isEmpty()) return Map.of();
        Map<String, PolymarketAlertLog> result = new LinkedHashMap<>();
        alertRepo.findRecentAlertsForMarkets(ids, since)
                .forEach(a -> result.putIfAbsent(a.getMarketId(), a));
        return result;
    }

    // -----------------------------------------------------------------------
    // Parallel data-api bet fetching (virtual threads, Java 21)
    // -----------------------------------------------------------------------

    private Map<String, BigDecimal> fetchBetsParallel(Collection<MarketData> markets) {
        // Submit all fetches simultaneously
        Map<String, CompletableFuture<BigDecimal>> futures = new LinkedHashMap<>();
        for (MarketData m : markets) {
            String cid = m.conditionId();
            if (cid == null || cid.isBlank()) {
                futures.put(m.id(), CompletableFuture.completedFuture(null));
            } else {
                futures.put(m.id(),
                        CompletableFuture.supplyAsync(() -> fetchLargestRecentBet(cid), betFetchExecutor)
                                .orTimeout(8, TimeUnit.SECONDS)
                                .exceptionally(ex -> null)); // timeout or error → null (no bet signal)
            }
        }
        // Collect results (all futures already running, this just gathers them)
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        futures.forEach((id, f) -> {
            try { result.put(id, f.get()); }
            catch (Exception e) { result.put(id, null); }
        });
        return result;
    }

    // -----------------------------------------------------------------------
    // Market list cache (30 min TTL — Gamma API markets change slowly)
    // -----------------------------------------------------------------------

    private Map<String, MarketData> fetchAllRelevantMarkets() {
        CachedMarkets cached = marketCache.get();
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMs() < MARKET_CACHE_TTL_MS) {
            log.debug("[PolymarketMonitor] Cache hit — {} markets (age {}s)",
                    cached.markets().size(),
                    (System.currentTimeMillis() - cached.cachedAtMs()) / 1000);
            return cached.markets();
        }

        Map<String, MarketData> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : KEYWORD_RELEVANCE.entrySet()) {
            try {
                String category = KEYWORD_CATEGORY.getOrDefault(entry.getKey(), "other");
                for (MarketData m : searchGamma(entry.getKey(), entry.getValue(), category))
                    result.putIfAbsent(m.id(), m);
            } catch (Exception e) {
                log.warn("[PolymarketMonitor] Gamma search failed for '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        if (!result.isEmpty()) {
            marketCache.set(new CachedMarkets(Collections.unmodifiableMap(result),
                    System.currentTimeMillis()));
            log.info("[PolymarketMonitor] Market cache refreshed — {} markets", result.size());
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Per-market processing (receives pre-fetched data — no DB/HTTP inside)
    // -----------------------------------------------------------------------

    private PendingAlert processMarket(MarketData m, BigDecimal btcPrice, LocalDateTime now,
                               PolymarketOddsSnapshot prev,
                               PolymarketOddsSnapshot snap1hAgo,
                               BigDecimal rollingAvg,
                               PolymarketOddsSnapshot snap24hAgo,
                               BigDecimal largestBet,
                               PolymarketAlertLog lastAlert) {

        // ── Compute deltas ────────────────────────────────────────────────
        BigDecimal volumeDelta = null;
        BigDecimal spikeRatio  = null;
        if (prev != null) {
            volumeDelta = m.volume().subtract(prev.getVolumeTotal()).max(BigDecimal.ZERO);
            if (rollingAvg != null && rollingAvg.compareTo(BigDecimal.valueOf(100)) > 0)
                spikeRatio = volumeDelta.divide(rollingAvg, 2, RoundingMode.HALF_UP);
        }

        BigDecimal probDelta1h = null;
        if (snap1hAgo != null)
            probDelta1h = m.prob().subtract(snap1hAgo.getProb()).setScale(4, RoundingMode.HALF_UP);

        BigDecimal probDelta24h = null;
        if (snap24hAgo != null)
            probDelta24h = m.prob().subtract(snap24hAgo.getProb()).setScale(4, RoundingMode.HALF_UP);

        // ── Build and save snapshot ───────────────────────────────────────
        PolymarketOddsSnapshot snap = new PolymarketOddsSnapshot();
        snap.setMarketId(m.id());
        snap.setMarketTitle(m.title());
        snap.setRelevanceTag(m.relevanceTag());
        snap.setProb(m.prob());
        snap.setVolumeTotal(m.volume());
        snap.setVolumeDelta15m(volumeDelta);
        snap.setRollingAvgVolume15m(rollingAvg);
        snap.setVolumeSpikeRatio(spikeRatio);
        snap.setProb1hAgo(snap1hAgo != null ? snap1hAgo.getProb() : null);
        snap.setProbDelta1h(probDelta1h);
        snap.setLargestSingleBetUsdc(largestBet);
        snap.setBtcPrice(btcPrice);
        snap.setIsResolved(m.prob().compareTo(BigDecimal.valueOf(0.97)) >= 0
                        || m.prob().compareTo(BigDecimal.valueOf(0.03)) <= 0);
        snap.setSnapshottedAt(now);
        snapshotRepo.save(snap);

        if (prev == null) return null;  // baseline run — no deltas yet, skip signal check

        // ── Signal detection (includes 24h drift + volume weighting) ─────
        boolean resolved = snap.getIsResolved()
                && prev.getProb() != null
                && prev.getProb().doubleValue() > 0.10
                && prev.getProb().doubleValue() < 0.90;
        // Near-resolved: market already parked at extreme prob for a while (prev also extreme).
        // Further LARGE_BET / VOLUME_SPIKE / TREND_DRIFT on these are settlement-era liquidity
        // and 24h-window residue, not new information. Only real ODDS_SPIKE (re-opening) matters.
        boolean nearResolved = snap.getIsResolved() && !resolved;
        SignalResult signal = detectSignal(probDelta1h, probDelta24h, spikeRatio, largestBet,
                resolved, nearResolved, m.volume());
        if (signal == null) return null;

        // ── Strength-aware dedup (P1-5) ───────────────────────────────────
        // Classic dedup: skip if same-or-stronger alert in the last 60 min.
        // Escalation: new signal STRONGER than last alert → break through dedup.
        if (lastAlert != null) {
            int lastRank = STRENGTH_RANK.getOrDefault(lastAlert.getSignalStrength(), 0);
            int newRank  = STRENGTH_RANK.getOrDefault(signal.strength(), 0);
            if (newRank <= lastRank) {
                log.debug("[PolymarketMonitor] Suppressed '{}': {} ≤ last {}", m.title(),
                        signal.strength(), lastAlert.getSignalStrength());
                return null;
            }
            log.info("[PolymarketMonitor] Escalation '{}': {} → {}",
                    m.title(), lastAlert.getSignalStrength(), signal.strength());
        }

        // ── Save alert + notify ───────────────────────────────────────────
        PolymarketAlertLog alert = new PolymarketAlertLog();
        alert.setMarketId(m.id());
        alert.setMarketTitle(m.title());
        alert.setAlertType(signal.type());
        alert.setSignalStrength(signal.strength());
        alert.setProbBefore(prev.getProb());
        alert.setProbAfter(m.prob());
        alert.setProbDelta(probDelta1h);
        alert.setVolumeSpikeRatio(spikeRatio);
        alert.setLargestSingleBet(largestBet);
        alert.setBtcPriceAtAlert(btcPrice);
        alert.setNotifiedAt(now);
        alertRepo.save(alert);

        return new PendingAlert(alert, m.relevanceTag(), m.category(), probDelta24h);
    }

    // -----------------------------------------------------------------------
    // Signal detection
    // -----------------------------------------------------------------------

    private record SignalResult(String type, String strength) {}

    /** Holds a fired alert before TG dispatch, enabling same-tick category grouping. */
    private record PendingAlert(PolymarketAlertLog alert, String relevanceTag,
                                 String category, BigDecimal probDelta24h) {}

    /**
     * Detects signal type and strength from available indicators.
     *
     * <p>Priority order:
     * <ol>
     *   <li>RESOLVED — market just settled from mid-range (one-shot; fires once per resolution)</li>
     *   <li>COMBINED — both odds delta AND volume spike triggered</li>
     *   <li>LARGE_BET EXTREME — single-bet dominates</li>
     *   <li>ODDS_SPIKE, VOLUME_SPIKE, LARGE_BET — single-indicator</li>
     *   <li>TREND_DRIFT — 24h cumulative drift (lowest priority; catches slow creep)</li>
     * </ol>
     *
     * <p>Near-resolved filter: when a market has been at extreme prob (≤3% or ≥97%) for more
     * than one tick, only ODDS_SPIKE can fire — everything else is settlement-era liquidity
     * (LARGE_BET on 100% markets) or 24h-window residue (TREND_DRIFT from past collapse),
     * neither of which carries predictive value.
     *
     * <p>TREND_DRIFT volume gate: a 24h drift alert only fires when current volume is at least
     * baseline (spikeRatio ≥ 1.0x). Pure drift with dried-up volume is stale signal.
     *
     * <p>Volume weighting: low-volume markets (< $100K total) have their strength demoted
     * one level to reduce noise from thinly traded markets.
     */
    private SignalResult detectSignal(BigDecimal probDelta1h, BigDecimal probDelta24h,
                                      BigDecimal spikeRatio, BigDecimal largestBet,
                                      boolean resolved, boolean nearResolved,
                                      BigDecimal marketVolume) {
        if (resolved) return new SignalResult("RESOLVED", "HIGH");

        double odds  = probDelta1h  != null ? Math.abs(probDelta1h.doubleValue())  : 0;
        double spike = spikeRatio   != null ? spikeRatio.doubleValue()             : 0;
        double bet   = largestBet   != null ? largestBet.doubleValue()             : 0;
        double drift = probDelta24h != null ? Math.abs(probDelta24h.doubleValue()) : 0;

        String oddsStr  = adjustForVolume(oddsStrength(odds),   marketVolume);
        String spikeStr = adjustForVolume(spikeStrength(spike), marketVolume);
        String betStr   = adjustForVolume(betStrength(bet),     marketVolume);
        String driftStr = adjustForVolume(driftStrength(drift), marketVolume);

        // Near-resolved suppression — only ODDS_SPIKE can survive (real re-opening)
        if (nearResolved) {
            if (!oddsStr.equals("NONE"))
                return new SignalResult("ODDS_SPIKE", oddsStr);
            return null;
        }

        // COMBINED: both odds delta and volume spike triggered simultaneously
        if (!oddsStr.equals("NONE") && !spikeStr.equals("NONE"))
            return new SignalResult("COMBINED",      highest(oddsStr, spikeStr));
        if (betStr.equals("EXTREME"))
            return new SignalResult("LARGE_BET",   "EXTREME");
        if (!oddsStr.equals("NONE"))
            return new SignalResult("ODDS_SPIKE",  oddsStr);
        if (!spikeStr.equals("NONE"))
            return new SignalResult("VOLUME_SPIKE", spikeStr);
        if (!betStr.equals("NONE"))
            return new SignalResult("LARGE_BET",   betStr);
        // TREND_DRIFT last: catches slow 24h drift that 1h windows miss.
        // Volume gate: require current volume at/above baseline — stale drift alone is noise.
        if (!driftStr.equals("NONE") && spike >= 1.0)
            return new SignalResult("TREND_DRIFT", driftStr);
        return null;
    }

    private String oddsStrength(double delta) {
        if (delta >= ODDS_EXTREME) return "EXTREME";
        if (delta >= ODDS_HIGH)    return "HIGH";
        if (delta >= ODDS_MEDIUM)  return "MEDIUM";
        return "NONE";
    }

    private String spikeStrength(double ratio) {
        if (ratio >= SPIKE_EXTREME) return "EXTREME";
        if (ratio >= SPIKE_HIGH)    return "HIGH";
        if (ratio >= SPIKE_MEDIUM)  return "MEDIUM";
        return "NONE";
    }

    private String betStrength(double usdc) {
        if (usdc >= BET_EXTREME) return "EXTREME";
        if (usdc >= BET_HIGH)    return "HIGH";
        if (usdc >= BET_MEDIUM)  return "MEDIUM";
        return "NONE";
    }

    /** 24h cumulative drift strength — catches slow market creep (P2-7). */
    private String driftStrength(double drift) {
        if (drift >= DRIFT_EXTREME) return "EXTREME";
        if (drift >= DRIFT_HIGH)    return "HIGH";
        return "NONE";
    }

    /**
     * Volume-weighted strength adjustment (P2-8).
     * Markets below $100K total volume get strength demoted one level to reduce noise.
     * HIGH-volume markets ($1M+) are unaffected.
     */
    private String adjustForVolume(String strength, BigDecimal marketVolume) {
        if ("NONE".equals(strength)) return "NONE";
        if (marketVolume == null || marketVolume.doubleValue() >= VOL_LOW_THRESHOLD) return strength;
        // Low-volume: demote one level
        return switch (strength) {
            case "EXTREME" -> "HIGH";
            case "HIGH"    -> "MEDIUM";
            default        -> "NONE";   // MEDIUM in thin market → suppress
        };
    }

    private String highest(String a, String b) {
        List<String> order = List.of("MEDIUM", "HIGH", "EXTREME");
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }

    // -----------------------------------------------------------------------
    // TG dispatch — individual or batch summary
    // -----------------------------------------------------------------------

    /**
     * Dispatches collected tick alerts.
     *
     * <p>Groups by market category. If a category fires ≥ {@code SUMMARY_THRESHOLD} signals
     * in the same tick (e.g. 7 Iran-related markets at once), collapses them into one
     * summary message to prevent alert fatigue. Smaller groups are sent individually.
     */
    private void dispatchAlerts(List<PendingAlert> alerts) {
        if (alerts.isEmpty()) return;

        // Cross-tick topic cooldown — drop non-EXTREME from categories already saturated in window.
        List<PendingAlert> toSend = applyCategoryCooldown(alerts, LocalDateTime.now());
        if (toSend.isEmpty()) return;

        // Group by category (preserve insertion order for deterministic output)
        Map<String, List<PendingAlert>> byCategory = new LinkedHashMap<>();
        for (PendingAlert pa : toSend) {
            String cat = pa.category() != null ? pa.category() : "other";
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(pa);
        }

        for (Map.Entry<String, List<PendingAlert>> entry : byCategory.entrySet()) {
            List<PendingAlert> group = entry.getValue();
            if (group.size() >= SUMMARY_THRESHOLD) {
                sendTgSummary(entry.getKey(), group);
            } else {
                for (PendingAlert pa : group) {
                    sendTgAlert(pa.alert(), pa.relevanceTag(), pa.category(), pa.probDelta24h());
                }
            }
        }
    }

    /**
     * Filters pending alerts through a rolling-window per-category noise cap.
     *
     * <p>Purge → evaluate → record happens per alert so multi-alert ticks respect quota within themselves.
     * EXTREME bypasses the cap and is not recorded — bursts of EXTREME won't starve later legitimate
     * MEDIUM/HIGH alerts. PolymarketAlertLog rows are already persisted by processMarket before this
     * filter runs, so suppression only silences TG (DB audit trail intact for backtest/ML).
     */
    private List<PendingAlert> applyCategoryCooldown(List<PendingAlert> alerts, LocalDateTime now) {
        LocalDateTime windowStart = now.minusMinutes(CATEGORY_NOISE_WINDOW_MIN);
        List<PendingAlert> keep = new ArrayList<>();
        int suppressed = 0;
        for (PendingAlert pa : alerts) {
            String cat = pa.category() != null ? pa.category() : "other";
            String strength = pa.alert().getSignalStrength();
            Deque<LocalDateTime> dq = categoryDispatchLog.computeIfAbsent(cat, k -> new ArrayDeque<>());
            while (!dq.isEmpty() && dq.peekFirst().isBefore(windowStart)) dq.pollFirst();

            if ("EXTREME".equals(strength)) {
                keep.add(pa);  // always pass; do not consume quota
                continue;
            }
            if (dq.size() < CATEGORY_NOISE_THRESHOLD) {
                keep.add(pa);
                dq.addLast(now);
            } else {
                suppressed++;
                log.debug("[PolymarketMonitor] Topic cooldown suppressed '{}' [{}/{}] — category='{}' window-count={}",
                        pa.alert().getMarketTitle(), pa.alert().getAlertType(), strength, cat, dq.size());
            }
        }
        if (suppressed > 0)
            log.info("[PolymarketMonitor] Topic cooldown suppressed {} non-EXTREME alert(s) this tick", suppressed);
        return keep;
    }

    /**
     * Compact multi-signal summary for same-category tick bursts.
     *
     * <p>Sorts by signal strength DESC (EXTREME first). Truncates long market titles.
     * Appends BTC price and historical hint from the highest-severity signal.
     */
    private void sendTgSummary(String category, List<PendingAlert> alerts) {
        // 全部都是 MEDIUM → 排入每日彙整佇列
        if (alerts.stream().allMatch(pa -> "MEDIUM".equals(pa.alert().getSignalStrength()))) {
            alerts.forEach(pa -> mediumDigestQueue.add(pa.alert()));
            log.debug("[PolymarketMonitor] MEDIUM batch ({}) queued for daily digest, cat={}", alerts.size(), category);
            return;
        }

        String topStrength = alerts.stream()
                .map(pa -> pa.alert().getSignalStrength())
                .max(Comparator.comparingInt(s -> STRENGTH_RANK.getOrDefault(s, 0)))
                .orElse("MEDIUM");

        String headerEmoji = switch (topStrength) {
            case "EXTREME" -> "🔴";
            case "HIGH"    -> "🟡";
            default        -> "🔵";
        };

        StringBuilder sb = new StringBuilder();
        sb.append(headerEmoji).append(" Polymarket 摘要 — ")
          .append(category).append(" (").append(alerts.size()).append(" 個信號)\n\n");
        sb.append("非交易指令：宏觀事件風險，不是 BUY/SELL；只作為倉位與風控背景。\n\n");

        // Sort EXTREME → HIGH → MEDIUM
        alerts.stream()
              .sorted(Comparator.comparingInt(
                      pa -> -STRENGTH_RANK.getOrDefault(pa.alert().getSignalStrength(), 0)))
              .forEach(pa -> {
                  PolymarketAlertLog a = pa.alert();
                  String emoji = switch (a.getSignalStrength()) {
                      case "EXTREME" -> "🔴";
                      case "HIGH"    -> "🟡";
                      default        -> "🔵";
                  };
                  double before = a.getProbBefore() != null ? a.getProbBefore().doubleValue() * 100 : 0;
                  double after  = a.getProbAfter()  != null ? a.getProbAfter().doubleValue()  * 100 : 0;
                  double delta  = after - before;

                  String title = a.getMarketTitle() != null ? a.getMarketTitle() : "─";
                  if (title.length() > 52) title = title.substring(0, 49) + "...";

                  sb.append(emoji).append(" ").append(title).append("\n");
                  sb.append(String.format("  賠率 %.0f%%→%.0f%% (%+.0f%%)", before, after, delta));

                  // Volume spike — only shown when above baseline
                  if (a.getVolumeSpikeRatio() != null && a.getVolumeSpikeRatio().doubleValue() >= 1.0) {
                      sb.append(String.format(" | vol %.1fx", a.getVolumeSpikeRatio().doubleValue()));
                  }
                  // Large bet
                  if (a.getLargestSingleBet() != null && a.getLargestSingleBet().doubleValue() >= BET_MEDIUM) {
                      sb.append(String.format(" | bet $%,.0f", a.getLargestSingleBet().doubleValue()));
                  }
                  // Low/high probability warning
                  if (after < 10) sb.append(" ⚠️低機率");
                  else if (after > 90) sb.append(" ⚠️高機率");

                  sb.append(" [").append(a.getAlertType()).append("/")
                    .append(a.getSignalStrength()).append("]\n");
              });

        // BTC price from any alert in the group
        alerts.stream()
              .map(pa -> pa.alert().getBtcPriceAtAlert())
              .filter(Objects::nonNull)
              .findFirst()
              .ifPresent(p -> sb.append(String.format("\nBTC: $%,.0f", p.doubleValue())));

        // Historical BTC hint — use the majority odds direction
        boolean anyRising = alerts.stream().anyMatch(
                pa -> pa.alert().getProbDelta() != null && pa.alert().getProbDelta().doubleValue() > 0);
        double maxDelta = alerts.stream()
                .filter(pa -> pa.alert().getProbDelta() != null)
                .mapToDouble(pa -> Math.abs(pa.alert().getProbDelta().doubleValue()) * 100)
                .max().orElse(0);
        String hint = btcHint(category, anyRising, maxDelta);
        if (hint != null) sb.append("\n").append(hint);

        notificationPort.broadcast(sb.toString());
        log.info("[PolymarketMonitor] TG summary: cat={} count={} top={}", category, alerts.size(), topStrength);
    }

    // -----------------------------------------------------------------------
    // TG notification (individual)
    // -----------------------------------------------------------------------

    private void sendTgAlert(PolymarketAlertLog a, String relevanceTag,
                              String category, BigDecimal probDelta24h) {
        // MEDIUM/HIGH 先排入彙整，EXTREME 即時推送。
        if ("MEDIUM".equals(a.getSignalStrength())) {
            mediumDigestQueue.add(a);
            log.debug("[PolymarketMonitor] MEDIUM queued for daily digest: '{}'", a.getMarketTitle());
            return;
        }
        if ("HIGH".equals(a.getSignalStrength())) {
            highDigestQueue.add(a);
            log.debug("[PolymarketMonitor] HIGH queued for 4h digest: '{}'", a.getMarketTitle());
            return;
        }

        String emoji = switch (a.getSignalStrength()) {
            case "EXTREME" -> "🔴";
            case "HIGH"    -> "🟡";
            default        -> "🔵";
        };
        if ("RESOLVED".equals(a.getAlertType()))    emoji = "⚫";
        if ("TREND_DRIFT".equals(a.getAlertType())) emoji = "📈";

        boolean oddsRising = a.getProbDelta() != null && a.getProbDelta().doubleValue() > 0;
        String dirArrow = oddsRising ? "↑" : "↓";
        double absDelta = a.getProbDelta() != null ? Math.abs(a.getProbDelta().doubleValue()) * 100 : 0;

        String message = buildPolymarketAlertMessage(a, relevanceTag, category, probDelta24h, emoji, dirArrow, oddsRising, absDelta);

        notificationPort.broadcast(message);
        log.info("[PolymarketMonitor] TG sent: {} {} '{}' dir={}",
                emoji, a.getSignalStrength(), a.getMarketTitle(), dirArrow);
    }

    static String buildPolymarketAlertMessage(PolymarketAlertLog a, String relevanceTag,
                                              String category, BigDecimal probDelta24h,
                                              String emoji, String dirArrow,
                                              boolean oddsRising, double absDelta) {
        StringBuilder sb = new StringBuilder();
        String strength = safeText(a.getSignalStrength(), "INFO");
        String alertType = safeText(a.getAlertType(), "UNKNOWN");
        String relevance = safeText(relevanceTag, "UNKNOWN");
        String cat = category != null && !category.isBlank() ? category : "─";

        sb.append("【外部事件｜僅供背景參考】\n");
        sb.append("等級：").append(strength).append("（").append(alertType).append("）").append("\n");
        sb.append("處置：觀察；不追單、不加倉，只納入 BTCUSDT 風控背景\n");
        sb.append("事件：").append(safeText(a.getMarketTitle(), "-")).append("\n");
        sb.append("關聯：").append(renderPolymarketRelevance(relevance))
          .append("｜類別：").append(renderPolymarketCategory(cat)).append("\n\n");

        if (a.getProbBefore() != null && a.getProbAfter() != null) {
            double before = a.getProbBefore().doubleValue() * 100;
            double after  = a.getProbAfter().doubleValue()  * 100;
            double delta  = after - before;
            sb.append(String.format("賠率：%.0f%% → %.0f%% (%+.0f%%) %s\n",
                    before, after, delta, dirArrow));
        }
        // 24h cumulative drift line — shown if significant (helps contextualize TREND_DRIFT)
        if (probDelta24h != null && Math.abs(probDelta24h.doubleValue()) >= 0.10) {
            sb.append(String.format("24h 累積漂移：%+.0f%%\n",
                    probDelta24h.doubleValue() * 100));
        }
        // Volume spike — only shown when above baseline (≥1x); sub-1x on an ODDS_SPIKE is noise
        if (a.getVolumeSpikeRatio() != null && a.getVolumeSpikeRatio().doubleValue() >= 1.0) {
            sb.append(String.format("Volume：%.1fx 正常量\n",
                    a.getVolumeSpikeRatio().doubleValue()));
        }
        if (a.getLargestSingleBet() != null && a.getLargestSingleBet().doubleValue() >= BET_MEDIUM) {
            sb.append(String.format("最大單筆：$%,.0f\n",
                    a.getLargestSingleBet().doubleValue()));
        }
        if (a.getBtcPriceAtAlert() != null) {
            sb.append(String.format("BTC：$%,.0f\n", a.getBtcPriceAtAlert().doubleValue()));
        }

        // Low/high probability market warning — signal direction less reliable at extremes
        if (a.getProbAfter() != null) {
            double oddsAfterPct = a.getProbAfter().doubleValue() * 100;
            if (oddsAfterPct < 10)
            sb.append("\n注意：低機率市場（").append(String.format("%.0f%%", oddsAfterPct))
                  .append(")，大量買進可能是看空 No 端，方向需謹慎解讀");
            else if (oddsAfterPct > 90)
                sb.append("\n注意：高機率市場（").append(String.format("%.0f%%", oddsAfterPct))
                  .append(")，賠率接近結算，波動意義有限");
        }

        String hint = btcHint(category, oddsRising, absDelta);
        if (hint != null) sb.append("\n").append(hint);
        sb.append("\n標籤：CONTEXT_ONLY / WATCH / POLYMARKET");

        return sb.toString();
    }

    private static String renderPolymarketRelevance(String relevanceTag) {
        return switch (safeText(relevanceTag, "UNKNOWN").toUpperCase(Locale.ROOT)) {
            case "HIGH" -> "HIGH 直接相關";
            case "MEDIUM" -> "MEDIUM 間接相關";
            case "LOW" -> "LOW 低相關";
            default -> relevanceTag;
        };
    }

    private static String renderPolymarketCategory(String category) {
        return switch (safeText(category, "─").toLowerCase(Locale.ROOT)) {
            case "geopolitical" -> "地緣政治";
            case "trade-war" -> "貿易戰";
            case "crypto" -> "Crypto";
            case "macro" -> "宏觀";
            default -> category;
        };
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 建立並回傳每日 MEDIUM 彙整訊息（含 Gemini 翻譯 + BTC 資金方向分析）。
     * 清空佇列後回傳，由 DailyTgReportOrchestrator 於 UTC 00:00 呼叫。
     * 若佇列為空回傳 null（不發送）。
     */
    public String buildDailyDigest() {
        List<PolymarketAlertLog> batch;
        synchronized (mediumDigestQueue) {
            if (mediumDigestQueue.isEmpty()) return null;
            batch = new ArrayList<>(mediumDigestQueue);
            mediumDigestQueue.clear();
        }

        // 同標的去重（同一 marketTitle 只保留最後一筆）
        Map<String, PolymarketAlertLog> dedupMap = new LinkedHashMap<>();
        for (PolymarketAlertLog a : batch) {
            if (a.getMarketTitle() != null) dedupMap.put(a.getMarketTitle(), a);
        }
        List<PolymarketAlertLog> deduped = new ArrayList<>(dedupMap.values());

        // BTC 價格取最新一筆
        BigDecimal btcPrice = deduped.stream()
                .filter(a -> a.getBtcPriceAtAlert() != null)
                .reduce((a, b) -> b)
                .map(PolymarketAlertLog::getBtcPriceAtAlert)
                .orElse(BigDecimal.ZERO);

        // ── Gemini 翻譯 + BTC 資金方向分析 ────────────────────────────────
        StringBuilder eventsText = new StringBuilder();
        for (int i = 0; i < deduped.size(); i++) {
            PolymarketAlertLog a = deduped.get(i);
            double before = a.getProbBefore() != null ? a.getProbBefore().doubleValue() * 100 : 0;
            double after  = a.getProbAfter()  != null ? a.getProbAfter().doubleValue()  * 100 : 0;
            eventsText.append(String.format("%d. %s\n   賠率 %.0f%% → %.0f%% (%+.0f%%) [%s]\n",
                    i + 1, a.getMarketTitle(), before, after, after - before,
                    a.getAlertType() != null ? a.getAlertType() : "─"));
        }

        String systemPrompt = "你是加密貨幣市場分析師，專注判斷 Polymarket 預測市場事件對 BTC 資金流向的影響。用繁體中文回覆。";
        String userPrompt = String.format(
                "以下是今日 Polymarket MEDIUM 級別預測市場信號（共 %d 條）。BTC 現價：$%,.0f\n\n%s\n" +
                "請：\n1. 用繁體中文翻譯每個事件標題（簡潔）\n" +
                "2. 判斷每個事件對 BTC 資金的影響：利多/利空/中性\n" +
                "3. 綜合判斷今日整體偏向\n\n" +
                "輸出純 JSON（無 markdown）：\n" +
                "{\"events\":[{\"zh\":\"中文標題\",\"impact\":\"利多/利空/中性\",\"reason\":\"10字原因\"}]," +
                "\"overall_bias\":\"偏多/偏空/中性\"," +
                "\"btc_flow_analysis\":\"50字以內分析\"}",
                deduped.size(), btcPrice.doubleValue(), eventsText);

        // 只在高衝擊事件才呼叫 Gemini（volumeSpike > 15x 或 |probDelta| > 8%）
        // 低衝擊事件不值得花 token 翻譯，直接顯示原文
        boolean needsAi = deduped.stream().anyMatch(a ->
                (a.getVolumeSpikeRatio() != null && a.getVolumeSpikeRatio().doubleValue() > 15)
                || (a.getProbDelta() != null && Math.abs(a.getProbDelta().doubleValue()) > 0.08));

        String aiRaw = null;
        if (needsAi) {
            try {
                List<Map<String, String>> messages = List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt));
                aiRaw = geminiApiClient.chat(messages, 600, 0.3);
            } catch (Exception e) {
                log.warn("[PolymarketMonitor] Gemini digest analysis failed: {}", e.getMessage());
            }
        } else {
            log.debug("[PolymarketMonitor] 低衝擊事件（{}筆），跳過 Gemini 翻譯省 token", deduped.size());
        }

        // ── 組裝 TG 訊息 ──────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("🔵 <b>Polymarket 每日彙整</b>\n");
        sb.append(String.format("今日 %d 條 MEDIUM 信號（去重後 %d 個標的）\n\n", batch.size(), deduped.size()));

        if (aiRaw != null) {
            try {
                int s = aiRaw.indexOf('{'), e = aiRaw.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    com.fasterxml.jackson.databind.JsonNode json =
                            objectMapper.readTree(aiRaw.substring(s, e + 1));
                    com.fasterxml.jackson.databind.JsonNode events = json.path("events");
                    if (events.isArray()) {
                        for (int i = 0; i < events.size() && i < deduped.size(); i++) {
                            com.fasterxml.jackson.databind.JsonNode ev = events.get(i);
                            PolymarketAlertLog a = deduped.get(i);
                            double before = a.getProbBefore() != null ? a.getProbBefore().doubleValue() * 100 : 0;
                            double after  = a.getProbAfter()  != null ? a.getProbAfter().doubleValue()  * 100 : 0;
                            String impactEmoji = switch (ev.path("impact").asText("中性")) {
                                case "利多" -> "🟢";
                                case "利空" -> "🔴";
                                default     -> "⚪";
                            };
                            sb.append(String.format("%s <b>%s</b>\n", impactEmoji, ev.path("zh").asText(a.getMarketTitle())));
                            sb.append(String.format("   %.0f%%→%.0f%% (%+.0f%%) | %s\n\n",
                                    before, after, after - before, ev.path("reason").asText("")));
                        }
                    }
                    String bias     = json.path("overall_bias").asText("");
                    String analysis = json.path("btc_flow_analysis").asText("");
                    if (!bias.isEmpty())     sb.append("📊 <b>整體偏向：").append(bias).append("</b>\n");
                    if (!analysis.isEmpty()) sb.append("💬 ").append(analysis).append("\n");
                } else {
                    appendRawDigestList(sb, deduped);
                }
            } catch (Exception ex) {
                log.warn("[PolymarketMonitor] AI parse failed, fallback to raw list: {}", ex.getMessage());
                appendRawDigestList(sb, deduped);
            }
        } else {
            appendRawDigestList(sb, deduped);
        }

        if (btcPrice.compareTo(BigDecimal.ZERO) > 0)
            sb.append(String.format("\nBTC: $%,.0f", btcPrice.doubleValue()));
        sb.append("\n處置：暫不加倉；等待策略觸發");
        sb.append("\n標籤：MARKET_CONTEXT / POLYMARKET / DO_NOT_ADD");

        return sb.toString();
    }

    public String buildHighPriorityDigest(int windowHours) {
        List<PolymarketAlertLog> batch;
        synchronized (highDigestQueue) {
            if (highDigestQueue.isEmpty()) return null;
            batch = new ArrayList<>(highDigestQueue);
            highDigestQueue.clear();
        }
        Map<String, PolymarketAlertLog> dedupMap = new LinkedHashMap<>();
        for (PolymarketAlertLog a : batch) {
            if (a.getMarketTitle() != null) dedupMap.put(a.getMarketTitle(), a);
        }
        List<PolymarketAlertLog> deduped = new ArrayList<>(dedupMap.values());
        StringBuilder sb = new StringBuilder();
        sb.append("【外部事件彙整｜Polymarket】\n");
        sb.append("期間：過去 ").append(windowHours).append(" 小時\n");
        sb.append("事件數：").append(deduped.size()).append("\n");
        sb.append("最高等級：高\n");
        sb.append("BTCUSDT 影響：外部風險偏高\n");
        sb.append("處置：暫不加倉；等待策略觸發\n\n");
        int i = 1;
        for (PolymarketAlertLog a : deduped.stream().limit(7).toList()) {
            double before = a.getProbBefore() != null ? a.getProbBefore().doubleValue() * 100 : 0;
            double after = a.getProbAfter() != null ? a.getProbAfter().doubleValue() * 100 : 0;
            sb.append(i++).append(". ")
                    .append(safeText(a.getMarketTitle(), "─"))
                    .append("：")
                    .append(String.format("%.0f%% → %.0f%% (%+.0f%%)", before, after, after - before))
                    .append("\n");
        }
        sb.append("\n標籤：MARKET_CONTEXT / POLYMARKET / DO_NOT_ADD");
        return sb.toString();
    }

    public void sendHighPriorityDigestIfAny(int windowHours) {
        String digest = buildHighPriorityDigest(windowHours);
        if (digest != null && !digest.isBlank()) {
            notificationPort.broadcast(digest, true);
        }
    }

    private void appendRawDigestList(StringBuilder sb, List<PolymarketAlertLog> alerts) {
        for (PolymarketAlertLog a : alerts) {
            double before = a.getProbBefore() != null ? a.getProbBefore().doubleValue() * 100 : 0;
            double after  = a.getProbAfter()  != null ? a.getProbAfter().doubleValue()  * 100 : 0;
            sb.append(String.format("🔵 %s\n   %.0f%%→%.0f%% (%+.0f%%)\n\n",
                    a.getMarketTitle() != null ? a.getMarketTitle() : "─",
                    before, after, after - before));
        }
    }

    /**
     * One-line BTC signal hint based on historical backtest findings
     * (derived from 11,697-row corpus, directional × delta analysis).
     */
    private static String btcHint(String category, boolean oddsRising, double absDelta) {
        if ("geopolitical".equals(category) && !oddsRising)
            return "📊 歷史信號: 地緣和平機率↓ → BTC 傾向下跌 (avg -0.4%, 上漲率 33%)";
        if ("geopolitical".equals(category) && oddsRising && absDelta >= 10)
            return "📊 歷史信號: 地緣風險機率↑≥10% → BTC 波動放大 (abs avg 0.73%)";
        if ("macro".equals(category) && !oddsRising)
            return "📊 歷史信號: 降息機率↓ → BTC 傾向下跌 (avg -0.6%)";
        if (absDelta >= 20)
            return "📊 歷史信號: 大幅賠率波動≥20% → BTC 4h 波動率明顯放大 (abs avg 0.64%)";
        return null;
    }

    // -----------------------------------------------------------------------
    // Polymarket API fetchers
    // -----------------------------------------------------------------------

    private List<MarketData> searchGamma(String keyword, String relevanceTag, String category)
            throws Exception {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = GAMMA_BASE + "/public-search?q=" + encoded + "&limit=10";
        Request req = new Request.Builder().url(url).get().build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return List.of();
            JsonNode root = objectMapper.readTree(resp.body().string());
            JsonNode events = root.path("events");
            if (!events.isArray()) return List.of();

            List<MarketData> list = new ArrayList<>();
            for (JsonNode event : events) {
                if (event.path("closed").asBoolean(false)) continue;
                for (JsonNode m : event.path("markets")) {
                    if (!m.path("active").asBoolean(false)) continue;
                    if (m.path("closed").asBoolean(false)) continue;

                    double prob = parseYesProb(m.path("outcomePrices"));
                    if (prob < 0) continue;

                    long vol = (long) m.path("volume").asDouble(0);
                    if (vol < MIN_VOLUME_USDC) continue;

                    String title = m.path("question").asText("").trim();
                    if (title.isBlank()) continue;

                    String id = m.path("conditionId").asText("");
                    if (id.isBlank()) id = m.path("id").asText("");
                    if (id.isBlank()) id = "q-" + Math.abs(title.hashCode());

                    String conditionId = m.path("conditionId").asText(null);
                    list.add(new MarketData(
                            id, conditionId, title, relevanceTag, category,
                            BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(vol)));
                }
            }
            return list;
        }
    }

    /**
     * Fetch largest single USDC trade (Yes OR No outcome) from data-api in the last 15 min.
     *
     * <p>Previously filtered to Yes only — a large No bet is equally significant
     * (e.g. $500K "No" on Iran nuclear deal = big player thinks it won't happen).
     */
    private BigDecimal fetchLargestRecentBet(String conditionId) {
        try {
            String url = DATA_API_BASE + "/trades?market=" + conditionId + "&limit=50";
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonNode root = objectMapper.readTree(resp.body().string());
                if (!root.isArray() || root.isEmpty()) return null;

                long cutoffEpoch = System.currentTimeMillis() / 1000 - 900; // last 15 min
                double maxBet = 0;
                for (JsonNode t : root) {
                    String outcome = t.path("outcome").asText();
                    // Include both Yes and No bets (P1-6)
                    if (!"Yes".equals(outcome) && !"No".equals(outcome)) continue;
                    long ts = t.path("timestamp").asLong(0);
                    if (ts > 0 && ts < cutoffEpoch) continue;
                    double size = t.path("size").asDouble(0);
                    maxBet = Math.max(maxBet, size);
                }
                return maxBet >= 1 ? BigDecimal.valueOf(maxBet).setScale(2, RoundingMode.HALF_UP) : null;
            }
        } catch (Exception e) {
            log.debug("[PolymarketMonitor] data-api fetch failed for {}: {}", conditionId, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private double parseYesProb(JsonNode outcomePrices) {
        try {
            if (outcomePrices.isArray() && !outcomePrices.isEmpty())
                return outcomePrices.get(0).asDouble(-1);
            String raw = outcomePrices.asText("");
            if (!raw.isBlank()) {
                JsonNode arr = objectMapper.readTree(raw);
                if (arr.isArray() && !arr.isEmpty()) return arr.get(0).asDouble(-1);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private BigDecimal latestBtcPrice() {
        try {
            // Fetch latest 1m K-line for real-time price snapshot (vs. 1h delayed)
            // Falls back to 1h if 1m unavailable (e.g., during early WS startup)
            var result1m = klineRepo
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc("BTCUSDT", "1m", PageRequest.of(0, 1))
                    .stream().findFirst().map(k -> k.getClosePrice());
            if (result1m.isPresent()) {
                return result1m.get();
            }
            // Fallback to 1h if 1m not yet available
            return klineRepo
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc("BTCUSDT", "1h", PageRequest.of(0, 1))
                    .stream().findFirst().map(k -> k.getClosePrice()).orElse(null);
        } catch (Exception e) {
            log.debug("[PolymarketMonitor] BTC price fetch failed: {}", e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Internal record
    // -----------------------------------------------------------------------

    private record MarketData(
            String id,
            String conditionId,
            String title,
            String relevanceTag,
            String category,
            BigDecimal prob,
            BigDecimal volume
    ) {}
}
