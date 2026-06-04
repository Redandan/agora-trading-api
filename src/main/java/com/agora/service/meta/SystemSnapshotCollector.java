package com.agora.service.meta;

import com.agora.model.BtFundingArb;
import com.agora.model.BtStrategy;
import com.agora.model.HintOverride;
import com.agora.model.MarketFlipEvent;
import com.agora.model.StrategyOverride;
import com.agora.repository.trading.BtFundingArbRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.HintOverrideRepository;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.repository.trading.StrategyOverrideRepository;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Gathers a compact system snapshot for LLM consumption (via
 * {@link com.agora.mcp.MetaControlMcpTools#askSystemAssistant}).
 *
 * <p>Each source has a 5s timeout; on failure the field is set to a short
 * "(unavailable: reason)" marker so the LLM can still answer other questions.
 * Target payload size: 2–8 KB JSON.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSnapshotCollector {

    private static final int PER_SOURCE_TIMEOUT_SEC = 5;
    private static final int RECENT_FLIPS_LIMIT = 5;
    private static final int STRATEGY_LIMIT = 10;
    private static final int POSITION_LIMIT = 5;

    private final BtStrategyRepository strategyRepo;
    private final BtLiveSignalRepository liveSignalRepo;
    private final OkxTradingService okxTradingService;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final MarketFlipEventRepository flipEventRepo;
    private final BtFundingArbRepository fundingArbRepo;
    private final StrategyOverrideRepository strategyOverrideRepo;
    private final HintOverrideRepository hintOverrideRepo;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${trading.funding-arb.enabled:false}")
    private boolean fundingArbEnabled;

    /** Serialisable snapshot; all fields may be {@code Object} to accept "(unavailable: X)" strings. */
    public record SystemSnapshot(
            String timestamp,
            Object activeStrategies,
            Object openPositions,
            Object market,
            Object recentFlipEvents,
            Object promotedMlModels,
            Object activeOverrides,
            Object fundingArb
    ) {
        public String toJson(ObjectMapper om) {
            try {
                return om.writeValueAsString(this);
            } catch (Exception e) {
                return "{\"error\":\"serialisation_failed: " + e.getMessage() + "\"}";
            }
        }
    }

    public SystemSnapshot gather() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String ts = nowUtc.atOffset(ZoneOffset.UTC).toString();

        CompletableFuture<Object> stratsF   = safeAsync(this::loadActiveStrategies,   "activeStrategies");
        CompletableFuture<Object> posF      = safeAsync(this::loadOpenPositions,      "openPositions");
        CompletableFuture<Object> marketF   = safeAsync(this::loadMarket,             "market");
        CompletableFuture<Object> flipsF    = safeAsync(this::loadRecentFlips,        "recentFlips");
        CompletableFuture<Object> mlF       = safeAsync(this::loadPromotedModels,     "promotedMlModels");
        CompletableFuture<Object> ovF       = safeAsync(() -> loadActiveOverrides(nowUtc), "activeOverrides");
        CompletableFuture<Object> fundingF  = safeAsync(this::loadFundingArb,         "fundingArb");

        CompletableFuture.allOf(stratsF, posF, marketF, flipsF, mlF, ovF, fundingF).join();

        return new SystemSnapshot(
                ts,
                stratsF.join(),
                posF.join(),
                marketF.join(),
                flipsF.join(),
                mlF.join(),
                ovF.join(),
                fundingF.join()
        );
    }

    private CompletableFuture<Object> safeAsync(Supplier<Object> supplier, String label) {
        return CompletableFuture
                .supplyAsync(supplier)
                .orTimeout(PER_SOURCE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .exceptionally(t -> {
                    Throwable cause = (t instanceof java.util.concurrent.CompletionException && t.getCause() != null)
                            ? t.getCause() : t;
                    String reason = cause instanceof TimeoutException
                            ? "timeout_" + PER_SOURCE_TIMEOUT_SEC + "s"
                            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    log.warn("[SystemSnapshot] {} failed: {}", label, reason);
                    return "(unavailable: " + reason + ")";
                });
    }

    // =========================================================================
    // Sources
    // =========================================================================

    private Object loadActiveStrategies() {
        List<BtStrategy> strategies = strategyRepo.findByEnabled(Boolean.TRUE);
        return strategies.stream()
                .limit(STRATEGY_LIMIT)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("type", s.getStrategyType());
                    m.put("symbols", s.getSymbols());
                    m.put("klineSource", s.getKlineSource());
                    // fired = 策略觸發的訊號總數(含被 filter/F&G 擋下的)
                    // traded = 真的下了單的筆數
                    // fired > traded 表示策略在觸發但被閘道擋(filter / hint / 配額)
                    // 提供兩個數字讓 askSystemAssistant 能準確回答「策略有沒有觸發」
                    long fired = safeCount(() -> liveSignalRepo.countByStrategyId(s.getId()));
                    long traded = safeCount(() -> liveSignalRepo.countByStrategyIdAndAutoTradedIsTrue(s.getId()));
                    m.put("firedSignals", fired);
                    m.put("tradedOrders", traded);
                    m.put("blockedByFilter", Math.max(0, fired - traded));
                    m.put("notes", truncate(s.getNotes(), 200));
                    return m;
                })
                .toList();
    }

    private long safeCount(Supplier<Long> q) {
        try { return q.get(); }
        catch (Exception e) { return -1L; }
    }

    private Object loadOpenPositions() {
        List<OkxTradingService.SwapPosition> swaps = okxTradingService.getOpenSwapPositions();
        return swaps.stream()
                .limit(POSITION_LIMIT)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("symbol", p.toSymbol());
                    m.put("posSide", p.posSide());
                    m.put("qty", p.pos());
                    m.put("avgPx", p.avgPx());
                    m.put("unrealizedPnlUsdt", p.upl());
                    return m;
                })
                .toList();
    }

    private Object loadMarket() {
        Map<String, Object> m = new LinkedHashMap<>();
        try { m.put("fearGreed", fearGreedService.getFearGreedValue()); }
        catch (Exception e) { m.put("fearGreed", "unavailable"); }
        try { m.put("btcWhaleBuyRatio", round4(whaleFlowService.getBuyRatio("BTCUSDT"))); }
        catch (Exception e) { m.put("btcWhaleBuyRatio", "unavailable"); }
        try { m.put("btcFundingRate8h", round6(okxTradingService.getCurrentFundingRate("BTCUSDT"))); }
        catch (Exception e) { m.put("btcFundingRate8h", "unavailable"); }
        return m;
    }

    private Object loadRecentFlips() {
        PageRequest page = PageRequest.of(0, RECENT_FLIPS_LIMIT,
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        List<MarketFlipEvent> events = flipEventRepo.findAll(page).getContent();
        return events.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("symbol", e.getSymbol());
                    m.put("indicator", e.getIndicator());
                    m.put("prev", e.getPrevValue());
                    m.put("curr", e.getCurrentValue());
                    m.put("threshold", e.getThresholdCrossed());
                    m.put("detectedAt", e.getDetectedAt() != null
                            ? e.getDetectedAt().atOffset(ZoneOffset.UTC).toString() : null);
                    m.put("status", e.getStatus());
                    return m;
                })
                .toList();
    }

    private Object loadPromotedModels() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, model_name, version, algorithm, sample_count, promoted_at " +
                        "FROM ml_model_registry WHERE status='PROMOTED' " +
                        "ORDER BY promoted_at DESC LIMIT 10");
        return rows;
    }

    private Object loadActiveOverrides(LocalDateTime nowUtc) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<StrategyOverride> strats = strategyOverrideRepo.findAllActive(nowUtc);
        List<HintOverride> hints = hintOverrideRepo.findAllActive(nowUtc);
        m.put("strategyPauses", strats.stream().map(o -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("strategyId", o.getStrategyId());
            r.put("symbol", o.getSymbol());
            r.put("interval", o.getIntervalCode());
            r.put("action", o.getAction());
            r.put("reason", truncate(o.getReason(), 120));
            r.put("expiresAt", o.getExpiresAt() != null
                    ? o.getExpiresAt().atOffset(ZoneOffset.UTC).toString() : null);
            return r;
        }).toList());
        m.put("hintOverrides", hints.stream().map(h -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", h.getSymbol());
            r.put("timeframe", h.getTimeframe());
            r.put("styleHint", h.getStyleHint());
            r.put("slMult", h.getSlMultiplier());
            r.put("tpMult", h.getTpMultiplier());
            r.put("expiresAt", h.getExpiresAt() != null
                    ? h.getExpiresAt().atOffset(ZoneOffset.UTC).toString() : null);
            return r;
        }).toList());
        return m;
    }

    private Object loadFundingArb() {
        if (!fundingArbEnabled) {
            return Map.of("enabled", false);
        }
        List<BtFundingArb> active = fundingArbRepo.findByStatusIn(
                List.of("OPEN", "OPENING", "CLOSING", "PENDING"));
        List<Map<String, Object>> positions = active.stream().map(a -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", a.getId());
            r.put("symbol", a.getSymbol());
            r.put("status", a.getStatus());
            r.put("notionalUsdt", a.getNotionalUsdt());
            r.put("spotEntryPrice", a.getSpotEntryPrice());
            r.put("perpEntryPrice", a.getPerpEntryPrice());
            return r;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("activeCount", active.size());
        m.put("positions", positions);
        return m;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
