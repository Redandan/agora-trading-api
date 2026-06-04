package com.agora.scheduler.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.ExitAdjustment;
import com.agora.service.backtest.OpenPositionView;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.trading.OcoManagementService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * #450 Phase 2 — Live OCO 動態管理 scheduler。
 *
 * <p>每分鐘 sweep 所有 auto-traded open positions,呼叫 strategy.adjustExit(),
 * 將回傳的 ExitAdjustment 套用到 OKX OCO(modifyOco)或 forceClose(market sell)。
 *
 * <h3>安全 default = dry-run</h3>
 * 環境變數 {@code position-exit-manager.enabled=false}(default false)→ scheduler 不啟用。
 * 即使啟用,{@code position-exit-manager.dry-run=true}(default true)→ 只 log + audit,不真改 OCO。
 *
 * <h3>Phase 2 範圍</h3>
 * <ul>
 *   <li>✅ Infra:scheduler / config / strategyByType registry / dry-run 開關</li>
 *   <li>✅ TP/SL adjustment(modifyOco)live + dry-run path</li>
 *   <li>⚠️ forceClose:dry-run only(Phase 2.5 加 cancel OCO + market sell 真實實作)</li>
 *   <li>⚠️ Live StrategyContext 是簡化版(current kline only,無 indicator arrays);
 *        strategy.adjustExit 應透過 injected service(如 mihIndicatorRepo)讀資料</li>
 * </ul>
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li>strategy.adjustExit throw → catch + log warn + skip 該 position</li>
 *   <li>modifyOco 失敗 → catch + log error + audit FAIL,下個 tick 再試</li>
 *   <li>strategy 找不到對應 impl(type mismatch)→ skip + debug log</li>
 *   <li>多 thread:scheduler 是 single-thread,但避免和 OcoManagementService.modifyOco 內 retryInProgress race
 *        靠 OcoManagementService 自身 ConcurrentHashMap putIfAbsent 同步</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionExitManagerScheduler {

    private final BtLiveSignalRepository liveSignalRepo;
    private final BtStrategyRepository strategyRepo;
    private final List<Strategy> strategies;
    private final OcoManagementService ocoService;
    private final MdKlineRepository klineRepo;
    private final DecisionAuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    @Value("${position-exit-manager.enabled:false}")
    private boolean enabled;

    @Value("${position-exit-manager.dry-run:true}")
    private boolean dryRun;

    /** strategyType -> Strategy instance.lookup map,@PostConstruct 初始化。 */
    private final Map<String, Strategy> strategyByType = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (Strategy s : strategies) {
            strategyByType.put(s.getType(), s);
        }
        log.info("[ExitMgr] init: enabled={} dryRun={} strategies={}",
                enabled, dryRun, strategyByType.keySet());
    }

    /**
     * 每分鐘 sweep。
     * fixedDelay 比 cron 簡單,且避免和其他 scheduler 撞時刻。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void manageOpenPositions() {
        if (!enabled) {
            return;
        }
        List<BtLiveSignal> open;
        try {
            open = liveSignalRepo.findByAutoTradedIsTrueAndExitTimeIsNull();
        } catch (Exception e) {
            log.error("[ExitMgr] failed to load open positions: {}", e.getMessage());
            return;
        }
        if (open.isEmpty()) {
            return;
        }

        log.debug("[ExitMgr] sweeping {} open positions", open.size());
        for (BtLiveSignal pos : open) {
            try {
                processPosition(pos);
            } catch (Exception e) {
                log.error("[ExitMgr] processPosition failed for id={}: {}", pos.getId(), e.getMessage(), e);
            }
        }
    }

    private void processPosition(BtLiveSignal pos) {
        BtStrategy strat = strategyRepo.findById(pos.getStrategyId()).orElse(null);
        if (strat == null) {
            log.debug("[ExitMgr] strategy {} not found for position {}", pos.getStrategyId(), pos.getId());
            return;
        }
        Strategy strategy = strategyByType.get(strat.getStrategyType());
        if (strategy == null) {
            log.debug("[ExitMgr] no Strategy impl for type {} (position {})",
                    strat.getStrategyType(), pos.getId());
            return;
        }

        Map<String, Object> config = parseConfig(strat.getConfigJson());

        // #450 follow-up: strategy 可以在 config 設 skipAdjustExit=true 完全 disable
        // 適用於 trend-ride strategies(任何 mid-flight 干預都殺 alpha)
        Object skip = config.get("skipAdjustExit");
        if (skip instanceof Boolean && (Boolean) skip) {
            log.debug("[ExitMgr] strategy {} has skipAdjustExit=true, skipping pos {}",
                    strategy.getType(), pos.getId());
            return;
        }

        OpenPositionView view = buildPositionView(pos);
        StrategyContext ctx = buildLiveContext(pos);

        Optional<ExitAdjustment> adj;
        try {
            adj = strategy.adjustExit(ctx, view, config);
        } catch (Throwable t) {
            log.warn("[ExitMgr] adjustExit threw for strategy {} (pos {}): {}",
                    strategy.getType(), pos.getId(), t.getMessage());
            return;
        }
        if (adj.isEmpty() || adj.get().isNoop()) {
            return;
        }

        ExitAdjustment a = adj.get();

        // Audit always (whether dry-run or live)
        Map<String, Object> auditCtx = new HashMap<>();
        auditCtx.put("tag", a.tag());
        auditCtx.put("forceClose", a.forceClose());
        auditCtx.put("newTp", a.newTp());
        auditCtx.put("newSl", a.newSl());
        auditCtx.put("dryRun", dryRun);
        auditCtx.put("currentPrice", view.currentPrice());
        auditCtx.put("ageHours", view.ageHours());
        auditCtx.put("unrealizedPnlPct", view.unrealizedPnlPct());

        if (dryRun) {
            log.info("[ExitMgr] DRY-RUN pos={} strategy={} tag={} reason={} forceClose={} tp={} sl={}",
                    pos.getId(), strategy.getType(), a.tag(), a.reason(),
                    a.forceClose(), a.newTp(), a.newSl());
            auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                    "[DRY-RUN] " + a.reason(), auditCtx);
            return;
        }

        if (a.forceClose()) {
            // Phase 2 之內 forceClose 真實化未做,降回 dry-run 行為。
            // Phase 2.5 會加 cancelOco + placeMarketSell 完整 flow。
            log.warn("[ExitMgr] forceClose live not implemented yet (Phase 2.5). Audit only. pos={} reason={}",
                    pos.getId(), a.reason());
            auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                    "[FORCE_CLOSE_PENDING_PHASE_2.5] " + a.reason(), auditCtx);
            return;
        }

        // Live TP/SL modification
        try {
            BigDecimal targetSl = a.newSl() != null ? a.newSl() : pos.getSuggestedSl();
            BigDecimal targetTp = a.newTp();   // null = keep existing per OcoManagementService.modifyOco contract

            // Sanity guard: SL only ratchets up for LONG (don't widen risk inadvertently)
            if ("LONG".equals(pos.getSide()) && targetSl != null
                    && pos.getSuggestedSl() != null
                    && targetSl.compareTo(pos.getSuggestedSl()) < 0) {
                log.info("[ExitMgr] skip SL widening for LONG pos {} ({}->{})",
                        pos.getId(), pos.getSuggestedSl(), targetSl);
                auditCtx.put("blocked", "SL_WOULD_WIDEN");
                auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                        "[BLOCKED:SL_WIDEN] " + a.reason(), auditCtx);
                return;
            }

            String result = ocoService.modifyOco(pos.getId(), targetSl, targetTp);
            log.info("[ExitMgr] LIVE modifyOco pos={} tag={} -> {}", pos.getId(), a.tag(), result);
            auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                    a.reason(), auditCtx);
        } catch (Exception e) {
            log.error("[ExitMgr] modifyOco failed pos={}: {}", pos.getId(), e.getMessage());
            Map<String, Object> failCtx = new HashMap<>(auditCtx);
            failCtx.put("error", e.getMessage());
            auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                    "[FAIL] " + a.reason(), failCtx);
        }
    }

    /**
     * Build OpenPositionView from BtLiveSignal + 最新 close price。
     */
    private OpenPositionView buildPositionView(BtLiveSignal pos) {
        BigDecimal entry = pos.getEntryPrice() != null ? pos.getEntryPrice() : BigDecimal.ZERO;
        BigDecimal current = fetchLatestClose(pos.getSymbol(), pos.getIntervalCode());
        if (current == null) current = entry;

        BigDecimal pnl;
        BigDecimal pnlPct;
        if (entry.signum() == 0) {
            pnl = BigDecimal.ZERO;
            pnlPct = BigDecimal.ZERO;
        } else {
            BigDecimal diff = "LONG".equals(pos.getSide())
                    ? current.subtract(entry)
                    : entry.subtract(current);
            pnlPct = diff.divide(entry, 6, RoundingMode.HALF_UP);
            BigDecimal qty = pos.getTradedQty() != null ? pos.getTradedQty() : BigDecimal.ZERO;
            pnl = diff.multiply(qty);
        }

        long ageHours = pos.getBarOpenTime() == null ? 0
                : Duration.between(pos.getBarOpenTime(), LocalDateTime.now(ZoneOffset.UTC)).toHours();

        // Phase 2 minimal:entry indicator snapshot 留 empty(Phase 3 會從 bt_live_signal.entry_indicator_snapshot 讀,
        // 那欄位 V110 migration 才會加)
        Map<String, Double> entrySnap = Collections.emptyMap();

        BigDecimal entryNotional = pos.getTradedQty() != null && pos.getActualEntryPrice() != null
                ? pos.getTradedQty().multiply(pos.getActualEntryPrice())
                : BigDecimal.ZERO;

        return new OpenPositionView(
                pos.getSide(),
                entry,
                pos.getBarOpenTime(),
                pos.getSuggestedTp(),
                pos.getSuggestedSl(),
                entryNotional,
                current,
                pnl,
                pnlPct,
                ageHours,
                entrySnap
        );
    }

    /**
     * Build a minimal live StrategyContext.
     *
     * <p>Phase 2 簡化:current = 最新一根 K 線,index=0,indicators=empty。
     * Strategy 的 adjustExit 實作應透過自己的 injected services(如 mihIndicatorRepo)讀資料,
     * 不依賴 ctx.getIndicators() arrays。
     *
     * <p>Phase 3 若要支援 indicator array 路徑,需在這裡 fetch + compute,工作量大,delay 到 v2。
     */
    private StrategyContext buildLiveContext(BtLiveSignal pos) {
        var latestKline = fetchLatestKline(pos.getSymbol(), pos.getIntervalCode());
        return new StrategyContext(0, latestKline, null,
                latestKline != null ? List.of(latestKline) : Collections.emptyList(),
                Collections.emptyMap());
    }

    private BigDecimal fetchLatestClose(String symbol, String intervalCode) {
        var k = fetchLatestKline(symbol, intervalCode);
        return k != null ? k.getClosePrice() : null;
    }

    private com.agora.model.MdKline fetchLatestKline(String symbol, String intervalCode) {
        var list = klineRepo.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                symbol, intervalCode, org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[ExitMgr] failed to parse config_json: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 給測試用。 */
    boolean isEnabled() { return enabled; }
    boolean isDryRun() { return dryRun; }
    Map<String, Strategy> strategyByType() { return strategyByType; }
}
