package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtOcoAdjustmentAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OkxEarnService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.meta.DecisionAuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 倉位管理工具集。
 * 提供開倉查詢、OCO 補掛、帳戶餘額查詢，無需 SSH 即可操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionMcpTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final BigDecimal BTC_SPOT_DISASTER_SL_PCT = new BigDecimal("0.12");
    private static final BigDecimal BTC_SPOT_POLICY_SL_TOLERANCE_PCT = new BigDecimal("0.005");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository mdKlineRepository;
    private final OkxTradingService okxTradingService;
    private final OcoManagementService ocoManagementService;
    private final com.agora.service.trading.OcoOutcomeAnalysisService ocoOutcomeAnalysisService;
    private final com.agora.service.trading.PriceScenarioSimulationService priceScenarioSimulationService;
    private final com.agora.service.trading.OpportunityScannerService opportunityScannerService;
    private final OkxEarnService okxEarnService;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;
    private final DecisionAuditWriter auditWriter;

    @Autowired(required = false)
    private BtOcoAdjustmentAuditRepository ocoAdjustmentAuditRepository;

    private final Map<String, RiskReductionPreview> riskReductionPreviews = new ConcurrentHashMap<>();
    private static final Duration RISK_REDUCTION_PREVIEW_TTL = Duration.ofMinutes(10);

    @Value("${trailing-stop.enabled:false}")
    private boolean trailingStopEnabled;

    @Value("${trailing-stop.dry-run:true}")
    private boolean trailingStopDryRun;

    private record RiskReductionPreview(
            Long positionId,
            BigDecimal oldSl,
            BigDecimal newSl,
            BigDecimal oldTp,
            BigDecimal currentPrice,
            boolean isLong,
            LocalDateTime expiresAtUtc) {}

    private record RiskGuardResult(boolean allowed, String reason) {}

    private record PositionExtreme(BigDecimal price, LocalDateTime time, String source, int bars) {}

    private record StructuralStop(BigDecimal price, BigDecimal swing, BigDecimal buffer,
                                  BigDecimal atrAbs, int bars, String source,
                                  LocalDateTime startUtc, LocalDateTime endUtc) {}

    private record ForwardRecovery(BigDecimal maxHigh, BigDecimal minLow, LocalDateTime maxHighTime,
                                   BigDecimal recoveryToEntryPct, BigDecimal recoveryFromExitPct,
                                   int bars) {}

    private record ReplayOutcome(String reason, BigDecimal exitPrice, LocalDateTime exitTime,
                                 BigDecimal netPnlUsdt, boolean ambiguousSameBar) {}

    private record TrailingReplayResult(String state,
                                        BigDecimal extreme,
                                        BigDecimal theoreticalStop,
                                        LocalDateTime breakevenAt,
                                        LocalDateTime trailingAt,
                                        int bars,
                                        boolean sameBarTransition) {}

    private static class TpStretchDecision {
        String status;
        String action;
        String reason;
        String preview;
        BigDecimal entry;
        BigDecimal current;
        BigDecimal extreme;
        LocalDateTime extremeTime;
        String extremeSource;
        int extremeBars;
        BigDecimal progress;
        BigDecimal pullback;
        BigDecimal gapToTp;
        BigDecimal recentExtremeTpCap;
        BigDecimal tpCapBuffer;
        BigDecimal tpReductionToCap;
    }

    private static class StopSweepDecision {
        String status;
        String action;
        String reason;
        BigDecimal entry;
        BigDecimal current;
        BigDecimal currentSl;
        BigDecimal structuralSl;
        BigDecimal disasterSl;
        BigDecimal policySl;
        BigDecimal swingLow;
        BigDecimal buffer;
        BigDecimal atrAbs;
        BigDecimal currentSlGapToStructuralPct;
        BigDecimal currentSlGapToPolicyPct;
        BigDecimal recommendedAmountUsdt;
        BigDecimal riskBudgetUsdt;
        BigDecimal structuralRiskPct;
        BigDecimal policyRiskPct;
        String policyMode;
        int structureBars;
        String structureSource;
    }

    private static class SpotWickAwareDecision {
        String status;
        String action;
        String reason;
        BigDecimal entry;
        BigDecimal current;
        BigDecimal currentSl;
        BigDecimal structuralSl;
        BigDecimal disasterSl;
        BigDecimal policySl;
        BigDecimal swingLow;
        BigDecimal buffer;
        BigDecimal atrAbs;
        BigDecimal lastLow;
        BigDecimal lastClose;
        BigDecimal previousClose;
        BigDecimal wickDepthPct;
        BigDecimal currentSlGapToStructuralPct;
        BigDecimal currentSlGapToPolicyPct;
        BigDecimal disasterRiskPct;
        BigDecimal policyRiskPct;
        BigDecimal suggestedDcaNotionalUsdt;
        String policyMode;
        String partialTpAction;
        String partialTpReason;
        int structureBars;
        int confirmationBars;
        String structureSource;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#237 #284 OCO 訂單一致性健康檢查：對每個開倉中的 bt_live_signal，" +
            "查 OKX 確認 OCO algoOrder 狀態是否與記錄一致。" +
            "偵測：OCO 已成交但倉位記錄仍 OPEN（SYNC_ERROR/SYNC_ERROR_FULL_FILLED）、" +
            "OCO 已取消無保護（UNPROTECTED）。" +
            "修正 #284：state=effective 時做子單交叉驗證，不再誤報 unknown。")
    public String getOcoHealth() {
        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        if (positions.isEmpty()) return "✅ 無開倉中的自動交易倉位";

        StringBuilder sb = new StringBuilder("=== OCO Health Check ===\n\n");
        int ok = 0, syncErr = 0, unprotected = 0;
        for (BtLiveSignal pos : positions) {
            try {
                if (pos.getOcoOrderListId() == null && isSoftExitNoHardSl(pos)) {
                    sb.append(String.format("🟡 [SOFT_EXIT_NO_HARD_SL] Position #%d %s entry=%.2f — hard OCO intentionally disabled; no exchange-side SL/TP\n",
                            pos.getId(), pos.getSymbol(), entryPrice(pos).doubleValue()));
                    ok++;
                    continue;
                }
                if (pos.getOcoOrderListId() == null) {
                    sb.append(String.format("⚠️ [UNPROTECTED] Position #%d %s entry=%.2f — 無 OCO 保護\n  → retryOco(positionId=%d)\n",
                            pos.getId(), pos.getSymbol(), entryPrice(pos).doubleValue(), pos.getId()));
                    unprotected++;
                    continue;
                }
                // getAlgoOrder() already returns data[0] — do NOT double-navigate (#284 bug 1)
                JsonNode algo = okxTradingService.getAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId());
                String state = (algo != null && !algo.isMissingNode() && !algo.isNull())
                        ? algo.path("state").asText("unknown")
                        : "unknown";

                if ("live".equalsIgnoreCase(state) || "partially_effective".equalsIgnoreCase(state)) {
                    sb.append(String.format("✅ Position #%d %s entry=%.2f — OCO active (%s)\n",
                            pos.getId(), pos.getSymbol(), entryPrice(pos).doubleValue(), state));
                    ok++;

                } else if ("filled".equalsIgnoreCase(state)) {
                    // Parent clearly filled — definite SYNC_ERROR
                    sb.append(String.format(
                            "🔴 [SYNC_ERROR_FULL_FILLED] Position #%d %s — OCO state=filled but DB still OPEN\n" +
                            "  → forceClosePosition(positionId=%d, exitPrice=?, exitReason=SL/TP)\n",
                            pos.getId(), pos.getSymbol(), pos.getId()));
                    syncErr++;

                } else if ("effective".equalsIgnoreCase(state)) {
                    // OKX bug: parent may stay "effective" after child fills (#284 bug 2 + #285 root-cause)
                    // Cross-check child order to distinguish "still active" vs "silently filled"
                    boolean isShort = "SHORT".equals(pos.getSide());
                    String childOrdId = algo.path("ordIdList").path(0).asText("");
                    boolean childFilled = false;
                    String childAvgPx = "";
                    if (!childOrdId.isEmpty()) {
                        try {
                            JsonNode child = isShort
                                    ? okxTradingService.querySwapOrderDetail(pos.getSymbol(), childOrdId)
                                    : okxTradingService.querySpotOrderDetail(pos.getSymbol(), childOrdId);
                            String childState = child.path("state").asText("");
                            if ("filled".equals(childState)) {
                                childFilled = true;
                                childAvgPx = child.path("avgPx").asText("?");
                            }
                        } catch (Exception ignored) {}
                    }
                    if (childFilled) {
                        sb.append(String.format(
                                "🔴 [SYNC_ERROR] Position #%d %s — child %s filled @ %s but parent=effective & DB still OPEN\n" +
                                "  (OKX bug: parent algoId stays effective after fill)\n" +
                                "  → forceClosePosition(positionId=%d, exitPrice=%s, exitReason=SL)\n",
                                pos.getId(), pos.getSymbol(), childOrdId, childAvgPx, pos.getId(), childAvgPx));
                        syncErr++;
                    } else {
                        sb.append(String.format("✅ Position #%d %s entry=%.2f — OCO active (effective, child not yet filled)\n",
                                pos.getId(), pos.getSymbol(), entryPrice(pos).doubleValue()));
                        ok++;
                    }

                } else if ("unknown".equals(state) || algo == null || algo.isMissingNode() || algo.isNull()) {
                    sb.append(String.format("⚠️ Position #%d %s — algoId=%d not found on OKX (algo may have expired or been cancelled)\n" +
                            "  → check getOkxTradeHistory; if already sold use forceClosePosition\n",
                            pos.getId(), pos.getSymbol(), pos.getOcoOrderListId()));
                    unprotected++;

                } else {
                    // effective_cancel, order_failed, etc.
                    sb.append(String.format("⚠️ [UNPROTECTED] Position #%d %s — OCO state=%s algoId=%d\n" +
                            "  → retryOco(positionId=%d)\n",
                            pos.getId(), pos.getSymbol(), state, pos.getOcoOrderListId(), pos.getId()));
                    unprotected++;
                }
            } catch (Exception e) {
                sb.append(String.format("⚠️ Position #%d — OCO 查詢失敗: %s\n", pos.getId(), e.getMessage()));
            }
        }
        sb.append(String.format("\n✅ %d OK | 🔴 %d SYNC_ERROR | ⚠️ %d 異常", ok, syncErr, unprotected));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "Deprecated compatibility alias for getOcoHealth(). Read-only OCO health check.")
    public String checkOcoHealth() {
        return getOcoHealth();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "列出所有開倉中的自動交易倉位，含 OCO 保護狀態、入場價、TP/SL、持倉時間。" +
            "protected=false 表示無止損保護，需立即用 retryOco 補掛。")
    public String getOpenPositions() {

        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();

        if (positions.isEmpty()) {
            return "目前無開倉。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 開倉清單 (共 ").append(positions.size()).append(" 筆) ===\n\n");

        for (BtLiveSignal pos : positions) {
            boolean protected_ = pos.getOcoOrderListId() != null;
            boolean softExit = isSoftExitNoHardSl(pos);
            sb.append(protected_ ? "🟢 " : softExit ? "🟡 " : "🔴 ").append(pos.getSymbol()).append("\n");
            sb.append("  ID: ").append(pos.getId()).append("\n");
            sb.append("  入場價: ").append(fmt(pos.getActualEntryPrice() != null
                    ? pos.getActualEntryPrice() : pos.getEntryPrice())).append("\n");
            sb.append("  數量: ").append(fmt(pos.getTradedQty())).append("\n");
            sb.append("  TP: ").append(fmt(pos.getSuggestedTp()))
              .append(" | SL: ").append(fmt(pos.getSuggestedSl())).append("\n");
            sb.append("  OCO 保護: ").append(protected_
                    ? "✅ algoId=" + pos.getOcoOrderListId()
                    : softExit ? "🟡 Soft Exit / 無 hard SL（不會被插針 SL 自動賣出）" : "❌ 無保護！").append("\n");
            sb.append("  開倉時間: ").append(pos.getCreatedAt() != null ? pos.getCreatedAt().format(FMT) : "N/A").append("\n");
            sb.append("---\n");
        }

        long unprotected = positions.stream()
                .filter(p -> p.getOcoOrderListId() == null)
                .filter(p -> !isSoftExitNoHardSl(p))
                .count();
        if (unprotected > 0) {
            sb.append("\n⚠️ 有 ").append(unprotected).append(" 筆倉位無 OCO 保護！\n");
            sb.append("請用 retryOco(positionId) 補掛止損止盈。\n");
        }

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "Deprecated compatibility alias for getOpenPositions(). Read-only open position listing.")
    public String listOpenPositions() {
        return getOpenPositions();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#439 Trailing stop read-only diagnostics. Shows global enabled/dry-run mode, " +
            "open OCO positions, per-strategy trailingStopEnabled opt-in, persisted trailing_state/ATR/high, " +
            "current vs historical trigger status, and theoretical dry-run SL. No trading writes.")
    public String getTrailingStopStatus() {
        List<BtLiveSignal> positions = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull();

        StringBuilder sb = new StringBuilder("=== Trailing Stop Status (#439) ===\n\n");
        sb.append("global.enabled: ").append(trailingStopEnabled).append("\n");
        sb.append("global.dryRun: ").append(trailingStopDryRun).append("\n");
        sb.append("open_oco_positions: ").append(positions.size()).append("\n\n");

        if (positions.isEmpty()) {
            sb.append("目前沒有 open OCO position,無 dry-run 樣本。\n");
            return sb.toString();
        }

        int optIn = 0;
        int initialized = 0;
        for (BtLiveSignal pos : positions) {
            boolean strategyOptIn = strategyTrailingEnabled(pos.getStrategyId());
            if (strategyOptIn) optIn++;
            if (pos.getTrailingAtr() != null) initialized++;

            BigDecimal entry = pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
            BigDecimal current = latestPriceOrNull(pos.getSymbol());
            BigDecimal atr = pos.getTrailingAtr();
            boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());

            sb.append(String.format("Position #%d %s [%s] strategy=%s optIn=%s\n",
                    pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()),
                    pos.getStrategyId(), strategyOptIn));
            sb.append("  state: ").append(nullSafe(pos.getTrailingState()))
                    .append(" | atr: ").append(fmt(atr))
                    .append(" | high/low: ").append(fmt(pos.getTrailingHigh()))
                    .append("\n");
            sb.append("  entry/current: ").append(fmt(entry))
                    .append(" / ").append(fmt(current))
                    .append(" | SL/TP: ").append(fmt(pos.getSuggestedSl()))
                    .append(" / ").append(fmt(pos.getSuggestedTp()))
                    .append("\n");

            if (!trailingStopEnabled) {
                sb.append("  status: global scheduler disabled\n");
            } else if (!strategyOptIn) {
                sb.append("  status: waiting_strategy_opt_in (scheduler skips this position)\n");
            } else if (entry == null || entry.signum() <= 0) {
                sb.append("  status: invalid_entry_price\n");
            } else if (atr == null || atr.signum() <= 0) {
                sb.append("  status: opt-in but not initialized yet (next scheduler tick should fetch ATR)\n");
            } else if (current == null || current.signum() <= 0) {
                sb.append("  status: price_unavailable\n");
            } else {
                String state = pos.getTrailingState() != null ? pos.getTrailingState() : "ENTERED";
                BigDecimal trailingExtreme = pos.getTrailingHigh();
                BigDecimal breakevenTrigger = trigger(entry, atr, new BigDecimal("0.5"), isLong);
                BigDecimal trailingTrigger = trigger(entry, atr, BigDecimal.ONE, isLong);
                boolean currentAboveBreakeven = isLong
                        ? current.compareTo(breakevenTrigger) >= 0
                        : current.compareTo(breakevenTrigger) <= 0;
                boolean currentAboveTrailing = isLong
                        ? current.compareTo(trailingTrigger) >= 0
                        : current.compareTo(trailingTrigger) <= 0;
                boolean everReachedBreakeven = hasReachedState(state, "BREAKEVEN_LOCKED")
                        || extremeReached(trailingExtreme, breakevenTrigger, isLong);
                boolean everReachedTrailing = hasReachedState(state, "TRAILING")
                        || extremeReached(trailingExtreme, trailingTrigger, isLong);
                BigDecimal theoreticalStop = theoreticalTrailingStop(pos, entry, current, atr, isLong);
                BigDecimal theoreticalDeltaPct = stopDeltaPct(pos.getSuggestedSl(), theoreticalStop);
                String promotionGate = trailingLivePromotionGate(
                        state,
                        current,
                        breakevenTrigger,
                        trailingTrigger,
                        theoreticalStop,
                        currentAboveBreakeven,
                        currentAboveTrailing,
                        everReachedBreakeven,
                        everReachedTrailing,
                        pos.getTrailingLastTransitionAt(),
                        isLong);
                sb.append("  breakevenTrigger: ").append(fmt(breakevenTrigger))
                        .append(" currentAbove=").append(currentAboveBreakeven)
                        .append(" everReached=").append(everReachedBreakeven).append("\n");
                sb.append("  trailingTrigger: ").append(fmt(trailingTrigger))
                        .append(" currentAbove=").append(currentAboveTrailing)
                        .append(" everReached=").append(everReachedTrailing).append("\n");
                sb.append("  theoreticalStopPrice: ").append(fmt(theoreticalStop))
                        .append(" | theoreticalStopDeltaPct: ").append(fmtPct(theoreticalDeltaPct))
                        .append("\n");
                sb.append("  lastTransitionAt: ").append(fmtTime(pos.getTrailingLastTransitionAt())).append("\n");
                sb.append("  livePromotionGate: ").append(promotionGate).append("\n");
            }
            sb.append("---\n");
        }

        sb.append(String.format("\nsummary: optIn=%d/%d initialized=%d/%d dryRun=%s",
                optIn, positions.size(), initialized, positions.size(), trailingStopDryRun));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "#439 Trailing stop historical replay. Replays the dry-run state machine over OKX K-line highs/lows " +
            "for open OCO positions to detect whether trigger samples exist or whether scheduler polling may have missed an intrabar touch. " +
            "READ_ONLY only; does not modify OCO, orders, strategies, grid, funds, or DB state.")
    public String analyzeTrailingStopReplay(
            @ToolParam(required = false, description = "Symbol filter, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "Replay kline interval, default 1m") String intervalCode,
            @ToolParam(required = false, description = "Max open OCO positions to include, default 10") Integer limit) {

        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.trim().toUpperCase();
        String replayInterval = (intervalCode == null || intervalCode.isBlank()) ? "1m" : intervalCode.trim();
        int max = limit == null || limit <= 0 ? 10 : Math.min(limit, 25);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<BtLiveSignal> positions = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .sorted(Comparator.comparing(BtLiveSignal::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(max)
                .toList();

        StringBuilder sb = new StringBuilder("=== Trailing Stop Historical Replay (#439) ===\n\n");
        sb.append("boundary: READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/DB behavior changed\n");
        sb.append("symbol: ").append(sym).append("\n");
        sb.append("replayInterval: ").append(replayInterval).append(" source=okx\n");
        sb.append("global.enabled: ").append(trailingStopEnabled).append("\n");
        sb.append("global.dryRun: ").append(trailingStopDryRun).append("\n");
        sb.append("orderSent=false ocoModified=false dbWritten=false\n\n");

        if (positions.isEmpty()) {
            sb.append("No open OCO positions for symbol.\n");
            return sb.toString();
        }

        int replayed = 0;
        int noTrigger = 0;
        int replayAdvanced = 0;
        int coverageGapReview = 0;
        int pollingGapReview = 0;
        int missingData = 0;

        for (BtLiveSignal pos : positions) {
            boolean strategyOptIn = strategyTrailingEnabled(pos.getStrategyId());
            boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
            BigDecimal entry = entryPrice(pos);
            BigDecimal atr = pos.getTrailingAtr();
            BigDecimal current = latestPriceOrNull(pos.getSymbol());
            String persistedState = pos.getTrailingState() == null ? "ENTERED" : pos.getTrailingState();
            LocalDateTime start = pos.getCreatedAt() != null ? pos.getCreatedAt() : pos.getBarOpenTime();

            sb.append(String.format("Position #%d %s [%s] strategy=%s optIn=%s\n",
                    pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()),
                    pos.getStrategyId(), strategyOptIn));
            sb.append("  persistedState=").append(persistedState)
                    .append(" entry=").append(fmt(entry))
                    .append(" current=").append(fmt(current))
                    .append(" atr=").append(fmt(atr))
                    .append(" start=").append(fmtTime(start))
                    .append("\n");

            if (!trailingStopEnabled) {
                sb.append("  replayStatus=SKIPPED_GLOBAL_DISABLED\n---\n");
                continue;
            }
            if (!strategyOptIn) {
                sb.append("  replayStatus=SKIPPED_STRATEGY_NOT_OPTED_IN\n---\n");
                continue;
            }
            if (entry == null || entry.signum() <= 0 || atr == null || atr.signum() <= 0 || start == null) {
                sb.append("  replayStatus=UNREPLAYABLE_MISSING_ENTRY_ATR_OR_START\n---\n");
                missingData++;
                continue;
            }

            List<MdKline> bars = mdKlineRepository
                    .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                            pos.getSymbol(), replayInterval, "okx", start.minusMinutes(1), now);
            if (bars.isEmpty()) {
                sb.append("  replayStatus=UNREPLAYABLE_MISSING_OKX_KLINES\n---\n");
                missingData++;
                continue;
            }

            TrailingReplayResult replay = replayTrailing(pos, bars, entry, atr, isLong);
            replayed++;
            boolean advanced = trailingStateRank(replay.state()) > trailingStateRank(persistedState);
            if (advanced) {
                replayAdvanced++;
            }
            if ("ENTERED".equalsIgnoreCase(replay.state())) {
                noTrigger++;
            }

            BigDecimal breakevenTrigger = trigger(entry, atr, new BigDecimal("0.5"), isLong);
            BigDecimal trailingTrigger = trigger(entry, atr, BigDecimal.ONE, isLong);
            BigDecimal deltaPct = stopDeltaPct(pos.getSuggestedSl(), replay.theoreticalStop());
            boolean stopCrossesCurrent = stopCrossesCurrentPrice(replay.theoreticalStop(), current, isLong);
            String coverageGapReason = trailingReplayCoverageGapReason(pos, replay, isLong, advanced);
            boolean schedulerPollingGapReview = "possible_intrabar_polling_gap".equals(coverageGapReason);
            if (advanced) {
                coverageGapReview++;
            }
            if (schedulerPollingGapReview) {
                pollingGapReview++;
            }
            String replayGate = trailingReplayGate(replay, persistedState, current,
                    stopCrossesCurrent, advanced, coverageGapReason);

            sb.append("  bars=").append(replay.bars())
                    .append(" breakevenTrigger=").append(fmt(breakevenTrigger))
                    .append(" trailingTrigger=").append(fmt(trailingTrigger))
                    .append("\n");
            sb.append("  replayState=").append(replay.state())
                    .append(" replayExtreme=").append(fmt(replay.extreme()))
                    .append(" replayTheoreticalStop=").append(fmt(replay.theoreticalStop()))
                    .append(" replayStopDeltaPct=").append(fmtPct(deltaPct))
                    .append("\n");
            sb.append("  replayBreakevenAt=").append(fmtTime(replay.breakevenAt()))
                    .append(" replayTrailingAt=").append(fmtTime(replay.trailingAt()))
                    .append(" sameBarTransition=").append(replay.sameBarTransition())
                    .append("\n");
            sb.append("  replayWouldAdvancePersistedState=").append(advanced)
                    .append(" dryRunCoverageGapReview=").append(advanced)
                    .append(" schedulerPollingGapReview=").append(schedulerPollingGapReview)
                    .append(" coverageGapReason=").append(coverageGapReason)
                    .append(" stopCrossesCurrentPrice=").append(stopCrossesCurrent)
                    .append("\n");
            sb.append("  replayPromotionGate=").append(replayGate).append("\n");
            sb.append("---\n");
        }

        sb.append("\nsummary: positions=").append(positions.size())
                .append(" replayed=").append(replayed)
                .append(" noTrigger=").append(noTrigger)
                .append(" replayAdvancedPersistedState=").append(replayAdvanced)
                .append(" coverageGapReview=").append(coverageGapReview)
                .append(" pollingGapReview=").append(pollingGapReview)
                .append(" missingData=").append(missingData)
                .append(" dryRun=").append(trailingStopDryRun)
                .append("\n");
        sb.append("operatorAction: ")
                .append(replayAdvanced > 0
                        ? "REVIEW_INTRABAR_TRIGGER_EVIDENCE_BEFORE_LIVE_PROMOTION"
                        : "CONTINUE_DRY_RUN_OBSERVATION")
                .append("\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Transactional
    @Tool(description = "#439 設定單一策略是否 opt-in TrailingStopScheduler。notes 必填。"
            + "只更新 strategy config_json.trailingStopEnabled 與 notes，不修改任何 OCO，不改 global dry-run。"
            + "param: strategyId, enabled, notes")
    public String setTrailingStopOptIn(Long strategyId, Boolean enabled, String notes) {
        if (strategyId == null) return "❌ strategyId 必填";
        if (enabled == null) return "❌ enabled 必填";
        if (notes == null || notes.isBlank()) return "❌ notes 必填";

        BtStrategy strategy = strategyRepository.findById(strategyId).orElse(null);
        if (strategy == null) return "❌ strategy not found: " + strategyId;
        try {
            ObjectNode config = strategy.getConfigJson() == null || strategy.getConfigJson().isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(strategy.getConfigJson());
            config.put("trailingStopEnabled", enabled);
            strategy.setConfigJson(objectMapper.writeValueAsString(config));
            strategy.setNotes(notes.trim());
            strategyRepository.save(strategy);
            log.info("[MCP:setTrailingStopOptIn] strategyId={} enabled={} dryRun={} notes={}",
                    strategyId, enabled, trailingStopDryRun, notes.trim());
            return "✅ trailingStopEnabled updated\n"
                    + "strategyId=" + strategyId + "\n"
                    + "strategyName=" + strategy.getName() + "\n"
                    + "trailingStopEnabled=" + enabled + "\n"
                    + "global.dryRun=" + trailingStopDryRun + "\n"
                    + "OCO writes performed: false\n"
                    + "notes=" + notes.trim();
        } catch (Exception e) {
            return "❌ failed to update trailingStopEnabled: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "補掛 OCO 止盈止損訂單。自動查詢 OKX 實際餘額調整下單量（解決手續費扣除導致的數量差異）。" +
            "param: positionId=倉位ID（從 getOpenPositions 取得）。" +
            "成功後發送 Telegram 通知。")
    public String retryOco(Long positionId) {

        try {
            return ocoManagementService.retryOco(positionId);
        } catch (IllegalArgumentException e) {
            log.warn("[PositionMcpTools] retryOco validation failed: positionId={} msg={}", positionId, e.getMessage());
            return "❌ 驗證失敗：參數無效或倉位不存在（id=" + positionId + "）";
        } catch (Exception e) {
            log.error("[PositionMcpTools] retryOco failed: positionId={}", positionId, e);
            return "❌ OCO 補掛失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "修改開倉的 OCO 止盈止損價格。取消現有 OCO 後以新價格重掛，並同步更新 DB 記錄（suggestedSl/Tp）。" +
            "param: positionId=倉位ID（從 getOpenPositions 取得），newSl=新止損價（必填），" +
            "newTp=新止盈價（選填，省略或傳 null 則保留原值不動）。" +
            "成功後發送 Telegram 通知。")
    public String modifyOco(Long positionId, BigDecimal newSl, BigDecimal newTp) {
        try {
            return ocoManagementService.modifyOco(positionId, newSl, newTp);
        } catch (IllegalArgumentException e) {
            log.warn("[PositionMcpTools] modifyOco validation failed: positionId={} msg={}", positionId, e.getMessage());
            return "❌ 驗證失敗：" + e.getMessage();
        } catch (Exception e) {
            log.error("[PositionMcpTools] modifyOco failed: positionId={}", positionId, e);
            return "❌ OCO 修改失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#408 給定 OCO 保護中的倉位，計算 P(TP first) / P(SL first) / EV USDT，" +
            "自動把 first-touch 機率（random walk 基準）依當前 regime（BULLISH/BEARISH/SIDEWAYS）" +
            "與即時 indicators（funding_rate, long_short_ratio, btc_short_liq_ratio_1h）做加權調整，" +
            "輸出 HOLD / MODIFY / CLOSE / WARN 建議。取代每次市況變動就要 5 個 MCP call + 5 個手算的流程。" +
            "param: positionId=倉位ID（從 getOpenPositions 取得，必須有 active OCO），" +
            "horizonHours=分析時間視窗（預設 168=7d，僅供顯示）。" +
            "免責：regime/indicator adjustment 是直觀加權（±5pp/±10pp 量級）非統計校準；" +
            "EV 用作正/負方向訊號，不要當精確金額預測。")
    public String analyzeOcoOutcome(Long positionId, Integer horizonHours) {
        try {
            int hours = horizonHours != null && horizonHours > 0 ? horizonHours : 168;
            return ocoOutcomeAnalysisService.analyze(positionId, hours).report();
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("[PositionMcpTools] analyzeOcoOutcome failed: positionId={}", positionId, e);
            return "❌ 分析失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "#412 What-if 倉位模擬：給定目標價，沿 LINEAR 路徑（不反轉）逐一觸發目前所有 OCO TP/SL " +
            "與 Grid PENDING/HOLDING level 事件，輸出觸發時間軸、每事件 PnL、最終 USDT/BTC/Total。" +
            "取代「BTC 漲到 $80K 我會多多少」的 5-10 分鐘手算。" +
            "param: symbol=交易對（預設 BTCUSDT），targetPrice=目標價（如 80000）。" +
            "免責：LINEAR path 假設價格單調，不模擬中途回檔→Grid 重複觸發 cascade（會少算 grid alpha）。" +
            "fee 用 OKX taker 0.1%。不模擬策略 entry 與 ML gate（Phase 2 才做）。")
    public String simulatePriceScenario(String symbol, BigDecimal targetPrice) {
        try {
            String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.toUpperCase();
            if (targetPrice == null || targetPrice.signum() <= 0) {
                return "❌ targetPrice 為必填且必須 > 0";
            }
            // 自動讀 OKX trading account 餘額（避免 caller 還要先 getBalance 抄一遍）
            double startUsdt = 0;
            double startBtc = 0;
            try {
                List<OkxTradingService.SpotHolding> holdings = okxTradingService.getSpotHoldings();
                String baseCcy = sym.endsWith("USDT") ? sym.substring(0, sym.length() - 4) : sym;
                for (OkxTradingService.SpotHolding h : holdings) {
                    if ("USDT".equalsIgnoreCase(h.ccy) && h.cashBal != null) {
                        startUsdt = h.cashBal.doubleValue();
                    } else if (baseCcy.equalsIgnoreCase(h.ccy) && h.cashBal != null) {
                        startBtc = h.cashBal.doubleValue();
                    }
                }
            } catch (Exception e) {
                log.warn("[PositionMcpTools] simulatePriceScenario balance fetch failed: {}", e.getMessage());
                return "❌ 無法讀取 OKX 帳戶餘額（前置條件），請稍後重試";
            }
            return priceScenarioSimulationService
                    .simulate(sym, targetPrice.doubleValue(), startUsdt, startBtc).report();
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            log.error("[PositionMcpTools] simulatePriceScenario failed: symbol={} target={}", symbol, targetPrice, e);
            return "❌ 模擬失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "#413 跨源機會掃描：整合 OCO / Grid / Earn / Funding Arb / 啟用策略 5 大來源，" +
            "輸出按 EV(USDT)排序的 ranked action list。取代每次「現在該做什麼」5-10 分鐘手動串接 7-8 個 MCP " +
            "+ 心算 EV + 心算 vs baseline 的流程。每個 action 標 EV±std + win rate + capital + 對比 do-nothing baseline。" +
            "param: horizonDays=評估時間視窗（預設 7）；" +
            "riskTolerance=風險偏好 CONSERVATIVE/MODERATE/AGGRESSIVE（預設 MODERATE）—" +
            "影響投機性 EV 縮放（0.7×/1.0×/1.2×），Earn 不受影響。" +
            "Phase 4 features：" +
            "(1) Multi-symbol concentration（per-symbol Map，BTCUSDT/ETHUSDT 獨立追蹤；任一 > 60% 觸發 0.7× incremental penalty）；" +
            "(2) Per-row variance / 95% CI（OCO Bernoulli std、Grid 50%-CoV heuristic、Earn=0、WAIT=NaN）；" +
            "(3) MODIFY_OCO_TIGHTEN 模板（WARN_OCO P(TP)≥90% 自動建議 SL→entry+0.5% 鎖部分利潤；EV ≈ current × 0.85）；" +
            "(4) Full Monte Carlo GBM（500 sims, BTC vol from last 30d 1d klines, deterministic seed）取代 Phase 3 5%/extra-row haircut；" +
            "(5) ADD_GRID 模板（Phase 3 沿用：active grid 有正 history + 集中度<70% + free>$50）；" +
            "(6) HODL baseline 用最近 7d BTC 收盤漂移（Phase 3 沿用）。" +
            "Capital solver 仍純 read-only，不下單。")
    public String scanOpportunities(Integer horizonDays, String riskTolerance) {
        try {
            int days = horizonDays != null && horizonDays > 0 ? horizonDays : 7;
            com.agora.service.trading.OpportunityScannerService.RiskTolerance risk =
                    com.agora.service.trading.OpportunityScannerService.RiskTolerance.MODERATE;
            if (riskTolerance != null && !riskTolerance.isBlank()) {
                try {
                    risk = com.agora.service.trading.OpportunityScannerService.RiskTolerance
                            .valueOf(riskTolerance.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    return "❌ 無效 riskTolerance：" + riskTolerance
                            + "（CONSERVATIVE / MODERATE / AGGRESSIVE）";
                }
            }
            return opportunityScannerService.scan(days, risk).report();
        } catch (Exception e) {
            log.error("[PositionMcpTools] scanOpportunities failed: horizonDays={} risk={}",
                    horizonDays, riskTolerance, e);
            return "❌ 機會掃描失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "取消現貨 LONG 倉位的 hard OCO，但保留倉位並標記為 SOFT_EXIT_NO_HARD_SL，"
            + "避免 OcoPoller 自動補回 SL。這會移除交易所側 TP/SL 掛單；不賣出、不改策略/Grid/資金。"
            + "params: positionId, reason optional")
    public String cancelHardOcoKeepPosition(Long positionId, String reason) {
        try {
            return ocoManagementService.cancelHardOcoKeepPosition(positionId, reason);
        } catch (IllegalArgumentException e) {
            log.warn("[PositionMcpTools] cancelHardOcoKeepPosition validation failed: positionId={} msg={}",
                    positionId, e.getMessage());
            return "❌ 驗證失敗：" + e.getMessage();
        } catch (Exception e) {
            log.error("[PositionMcpTools] cancelHardOcoKeepPosition failed: positionId={}", positionId, e);
            return "❌ Hard OCO 取消失敗，請至 app.log 查看詳情";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "AI Execution Manager read-only risk snapshot. Summarizes open positions, OCO coverage, "
            + "current price, max-loss before SL, unrealized PnL, trailing eligibility, and max action level. "
            + "No trading/OCO/strategy/fund behavior changed. param: symbol optional, default BTCUSDT")
    public String getExecutionRiskSnapshot(String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getExecutionRiskSnapshot");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("writeMode", false);
        root.put("boundary", "read-only; no trading, OCO, strategy, grid, or fund behavior changed");
        var positions = root.putArray("positions");

        List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .toList();
        root.put("openPositionCount", open.size());
        int unprotected = 0;
        BigDecimal totalMaxLoss = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal lastPrice = latestPriceOrNull(sym);

        for (BtLiveSignal pos : open) {
            boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
            BigDecimal entry = entryPrice(pos);
            BigDecimal qty = ocoQuantity(pos);
            BigDecimal current = latestPriceOrNull(pos.getSymbol());
            if (current == null) current = lastPrice;
            BigDecimal maxLoss = maxLossUsdt(pos, pos.getSuggestedSl());
            BigDecimal pnl = unrealizedPnl(pos, current);
            if (maxLoss != null) totalMaxLoss = totalMaxLoss.add(maxLoss);
            if (pnl != null) totalPnl = totalPnl.add(pnl);
            if (pos.getOcoOrderListId() == null) unprotected++;

            ObjectNode node = positions.addObject();
            node.put("positionId", pos.getId());
            node.put("strategyId", pos.getStrategyId());
            node.put("symbol", pos.getSymbol());
            node.put("intervalCode", pos.getIntervalCode());
            node.put("side", isLong ? "LONG" : "SHORT");
            putDecimal(node, "entry", entry);
            putDecimal(node, "current", current);
            putDecimal(node, "qtyForRisk", qty);
            putDecimal(node, "sl", pos.getSuggestedSl());
            putDecimal(node, "tp", pos.getSuggestedTp());
            putDecimal(node, "maxLossUsdt", maxLoss);
            putDecimal(node, "unrealizedPnlUsdt", pnl);
            putDecimal(node, "unrealizedPnlPct", pnlPct(pos, current));
            node.put("ocoProtected", pos.getOcoOrderListId() != null);
            if (pos.getOcoOrderListId() != null) node.put("ocoAlgoId", pos.getOcoOrderListId());
            node.put("trailingState", nullSafe(pos.getTrailingState()));
            node.put("strategyTrailingOptIn", strategyTrailingEnabled(pos.getStrategyId()));
            node.put("riskReducingActionEligible", riskReducingCandidate(pos, current).allowed());
            node.put("riskReducingActionReason", riskReducingCandidate(pos, current).reason());
        }

        putDecimal(root, "totalOpenMaxLossUsdt", totalMaxLoss);
        putDecimal(root, "totalUnrealizedPnlUsdt", totalPnl);
        root.put("unprotectedPositionCount", unprotected);
        String maxActionLevel = unprotected > 0 ? "L2_RETRY_OCO"
                : open.isEmpty() ? "L1_READ_ONLY"
                : "L2_RISK_REDUCING_ONLY";
        root.put("recommendedMaxActionLevel", maxActionLevel);
        root.put("operatorAction", "Use previewOcoRiskReduction before any OCO change; do not use generic modifyOco for AI-managed actions.");
        return writeJson(root);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.REPORTING})
    @Tool(description = "Read-only active-position EV lifecycle reassessment. "
            + "Aggregates analyzeOcoOutcome, analyzeStopSweepRisk, analyzeTpStretchProtection, and previewOcoRiskReduction. "
            + "Returns HOLD / WATCH / RISK_REDUCING_PREVIEW_AVAILABLE / NO_SAFE_ACTION. "
            + "No OCO modification, trading, strategy, grid, or fund behavior changed. "
            + "params: positionId optional, symbol optional default BTCUSDT, horizonHours optional default 168")
    public String reassessActivePositionEv(Long positionId, String symbol, Integer horizonHours) {
        BtLiveSignal pos = positionId != null ? findOpenPosition(positionId) : null;
        String sym = pos != null ? pos.getSymbol()
                : symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int hours = horizonHours != null && horizonHours > 0 ? horizonHours : 168;

        StringBuilder sb = new StringBuilder();
        sb.append("=== Active Position EV Reassessment ===\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("symbol: ").append(sym).append(" | horizonHours=").append(hours).append("\n");
        if (positionId != null) sb.append("positionId: ").append(positionId).append("\n");
        sb.append("safeActionPath: previewOcoRiskReduction -> modifyOcoRiskReducingOnly only; generic modifyOco is not authorized here.\n\n");

        String oco = positionId != null ? analyzeOcoOutcome(positionId, hours)
                : "positionId not supplied; OCO EV section skipped.";
        String stopSweep = analyzeStopSweepRisk(sym, 5, null, null);
        String tpStretch = analyzeTpStretchProtection(sym);
        String preview = positionId != null ? previewOcoRiskReduction(positionId, "BREAKEVEN", null)
                : "positionId not supplied; risk-reduction preview skipped.";

        String action = classifyActivePositionAction(oco, stopSweep, tpStretch, preview);
        sb.append("Decision: ").append(action).append("\n");
        sb.append("Reason: ").append(activePositionReason(action, oco, stopSweep, tpStretch, preview)).append("\n\n");

        sb.append("--- OCO EV ---\n").append(oco).append("\n\n");
        sb.append("--- Stop Sweep ---\n").append(stopSweep).append("\n\n");
        sb.append("--- TP Stretch ---\n").append(tpStretch).append("\n\n");
        sb.append("--- Risk-Reducing Preview ---\n").append(preview).append("\n\n");
        sb.append("Operator action: ").append(operatorActionForActivePosition(action)).append("\n");
        return sb.toString();
    }

    private String classifyActivePositionAction(String oco, String stopSweep, String tpStretch, String preview) {
        String all = (oco + "\n" + stopSweep + "\n" + tpStretch + "\n" + preview).toLowerCase();
        if (all.contains("\"allowed\" : true") || all.contains("\"allowed\":true")) {
            return "RISK_REDUCING_PREVIEW_AVAILABLE";
        }
        if (all.contains("watch") || all.contains("suggestion=modify") || all.contains("suggestedaction: watch")) {
            return "WATCH";
        }
        if (all.contains("negative ev") || all.contains("ev≈-") || all.contains("ev=-")) {
            return "NO_SAFE_ACTION";
        }
        return "HOLD";
    }

    private String activePositionReason(String action, String oco, String stopSweep, String tpStretch, String preview) {
        return switch (action) {
            case "RISK_REDUCING_PREVIEW_AVAILABLE" ->
                    "A safe risk-reducing preview is available; execution still requires modifyOcoRiskReducingOnly with preview token.";
            case "WATCH" ->
                    "At least one read-only analyzer reports WATCH/MODIFY context, but no safe write is executed by this tool.";
            case "NO_SAFE_ACTION" ->
                    "EV/risk may be unfavorable, but the safe risk-reduction preview is unavailable or blocked.";
            default ->
                    "No active read-only analyzer requires intervention.";
        };
    }

    private String operatorActionForActivePosition(String action) {
        return switch (action) {
            case "RISK_REDUCING_PREVIEW_AVAILABLE" ->
                    "Review preview token manually; execute only via modifyOcoRiskReducingOnly if still desired.";
            case "WATCH" ->
                    "Keep OCO, re-check if pullback deepens or P(TP) drops.";
            case "NO_SAFE_ACTION" ->
                    "Do not use generic modifyOco; wait for a valid risk-reducing preview or explicit human decision.";
            default ->
                    "HOLD; no active-position EV action required.";
        };
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only TP stretch protection scan. Checks whether open OCO positions moved close to TP, "
            + "failed to hit it, then pulled back from the recent in-position high/low. "
            + "Outputs risk-reducing SL preview guidance only; does not issue modify tokens and does not change OCO. "
            + "params: symbol optional default BTCUSDT")
    public String analyzeTpStretchProtection(String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> p.getOcoOrderListId() != null)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== TP Stretch Protection Scan ===\n");
        sb.append("symbol: ").append(sym).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("rule: flag when in-position extreme reached >=75% of TP distance, TP not hit, and current pulled back >=0.50%.\n\n");

        if (positions.isEmpty()) {
            sb.append("Summary: openOcoPositions=0 stretched=0 watch=0 ok=0\n");
            sb.append("Operator action: no open OCO position to review.\n");
            return sb.toString();
        }

        int reconciliation = 0;
        int stretched = 0;
        int watch = 0;
        int ok = 0;
        for (BtLiveSignal pos : positions) {
            TpStretchDecision d = evaluateTpStretch(pos);
            if ("TARGET_TOUCHED_BUT_RECORD_OPEN".equals(d.status)) {
                reconciliation++;
            } else if ("TP_STRETCHED".equals(d.status)) {
                stretched++;
            } else if ("WATCH".equals(d.status)) {
                watch++;
            } else {
                ok++;
            }
            sb.append(String.format("Position #%d %s [%s] status=%s\n",
                    pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()), d.status));
            sb.append("  entry/current/tp/sl: ")
                    .append(fmt(d.entry)).append(" / ").append(fmt(d.current))
                    .append(" / ").append(fmt(pos.getSuggestedTp()))
                    .append(" / ").append(fmt(pos.getSuggestedSl())).append("\n");
            sb.append("  recentExtreme: ").append(fmt(d.extreme))
                    .append(" at ").append(fmtTime(d.extremeTime))
                    .append(" source=").append(nullSafe(d.extremeSource))
                    .append(" bars=").append(d.extremeBars).append("\n");
            sb.append("  tpProgressFromExtreme: ").append(fmtPct(d.progress))
                    .append(" | pullbackFromExtreme: ").append(fmtPct(d.pullback))
                    .append(" | gapExtremeToTp: ").append(fmtPct(d.gapToTp)).append("\n");
            sb.append("  recentExtremeTpCap: ").append(fmt(d.recentExtremeTpCap))
                    .append(" | capBuffer: ").append(fmt(d.tpCapBuffer))
                    .append(" | tpReductionToCap: ").append(fmt(d.tpReductionToCap)).append("\n");
            sb.append("  suggestedAction: ").append(d.action).append("\n");
            if (d.preview != null && !d.preview.isBlank()) {
                sb.append("  riskReducingPreview: ").append(d.preview).append("\n");
            }
            sb.append("  reason: ").append(d.reason).append("\n");
            sb.append("---\n");
        }

        sb.append(String.format("\nSummary: openOcoPositions=%d reconciliation=%d stretched=%d watch=%d ok=%d\n",
                positions.size(), reconciliation, stretched, watch, ok));
        sb.append("openOcoPositions=").append(positions.size()).append("\n");
        sb.append("reconciliation=").append(reconciliation).append("\n");
        sb.append("stretched=").append(stretched).append("\n");
        sb.append("watch=").append(watch).append("\n");
        sb.append("ok=").append(ok).append("\n");
        if (reconciliation > 0) {
            sb.append("Operator action: RECONCILIATION_REQUIRED; suppress normal OK/HOLD until external parent/child/history evidence is reviewed.\n");
        } else if (stretched > 0) {
            sb.append("Operator action: REVIEW_ONLY; consider risk-reducing SL preview before any OCO change. Do not chase or modify TP blindly.\n");
        } else if (watch > 0) {
            sb.append("Operator action: WATCH; TP is not proven too high, but protect profit if pullback deepens.\n");
        } else {
            sb.append("Operator action: HOLD; no TP-stretch warning detected.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only STOP_SWEPT / structural SL risk scan. "
            + "Checks open spot LONG positions against recent swing-low structural SL and reviews recently closed SL trades "
            + "for post-stop recovery. Outputs risk-sizing guidance only; no OCO/order/strategy/fund behavior changed. "
            + "params: symbol optional default BTCUSDT, days default 14 max 60, riskBudgetUsdt optional, minTradeUsdt default 15")
    public String analyzeStopSweepRisk(String symbol, Integer days, BigDecimal riskBudgetUsdt, BigDecimal minTradeUsdt) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int lookbackDays = days == null || days <= 0 ? 14 : Math.min(days, 60);
        BigDecimal minTrade = minTradeUsdt != null && minTradeUsdt.signum() > 0 ? minTradeUsdt : new BigDecimal("15");

        List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> !"SHORT".equalsIgnoreCase(p.getSide()))
                .toList();
        List<BtLiveSignal> closed = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(
                        LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays)).stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> !"SHORT".equalsIgnoreCase(p.getSide()))
                .filter(p -> "SL".equalsIgnoreCase(p.getExitReason()))
                .sorted(Comparator.comparing(BtLiveSignal::getExitTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Stop Sweep Risk Scan ===\n");
        sb.append("symbol: ").append(sym).append(" | days=").append(lookbackDays).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("rule: BTC spot LONG keeps hard OCO, but SL policy is ultra-low disaster protection by default; risk is controlled by smaller size, not a tight wick-prone SL.\n");
        sb.append("tolerance: SL within 0.5% of entry above policySl is treated as acceptable to avoid false alarms on rounded disaster stops.\n");
        sb.append("softSlPolicy: small spot watch-only exception only; normal auto entries still need hard disaster protection.\n\n");

        int initialTooTight = 0;
        int profitLocked = 0;
        int openOk = 0;
        if (open.isEmpty()) {
            sb.append("Open positions: none\n\n");
        } else {
            sb.append("Open position structural SL review:\n");
            for (BtLiveSignal pos : open) {
                StopSweepDecision d = evaluateOpenStopSweepRisk(pos, riskBudgetUsdt, minTrade);
                if ("INITIAL_SL_TOO_TIGHT".equals(d.status)) initialTooTight++;
                else if ("PROFIT_LOCKED".equals(d.status)) profitLocked++;
                else openOk++;
                sb.append(String.format("Position #%d %s [%s] status=%s\n",
                        pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()), d.status));
                sb.append("  entry/current/sl: ").append(fmt(d.entry)).append(" / ")
                        .append(fmt(d.current)).append(" / ").append(fmt(d.currentSl)).append("\n");
                sb.append("  policyMode: ").append(nullSafe(d.policyMode))
                        .append(" | policySl: ").append(fmt(d.policySl))
                        .append(" | structuralSl: ").append(fmt(d.structuralSl))
                        .append(" | disasterSl: ").append(fmt(d.disasterSl)).append("\n");
                sb.append("  swingLow: ").append(fmt(d.swingLow))
                        .append(" | buffer: ").append(fmt(d.buffer))
                        .append(" | atrAbs: ").append(fmt(d.atrAbs))
                        .append(" | bars=").append(d.structureBars)
                        .append(" source=").append(nullSafe(d.structureSource)).append("\n");
                sb.append("  currentSlGapToStructural: ").append(fmtPct(d.currentSlGapToStructuralPct))
                        .append(" | currentSlGapToPolicy: ").append(fmtPct(d.currentSlGapToPolicyPct))
                        .append(" | policyRiskPct: ").append(fmtPct(d.policyRiskPct)).append("\n");
                sb.append("  riskBudgetUsdt: ").append(fmt(d.riskBudgetUsdt))
                        .append(" | recommendedAmountUsdt: ").append(fmt(d.recommendedAmountUsdt))
                        .append(" | minTradeUsdt: ").append(fmt(minTrade)).append("\n");
                sb.append("  suggestedAction: ").append(d.action).append("\n");
                sb.append("  reason: ").append(d.reason).append("\n");
                sb.append("---\n");
            }
            sb.append("\n");
        }

        int stopSwept = 0;
        int validStop = 0;
        int unknown = 0;
        if (closed.isEmpty()) {
            sb.append("Closed SL postmortem: no recent SL exits in window.\n");
        } else {
            sb.append("Closed SL postmortem:\n");
            for (BtLiveSignal pos : closed.stream().limit(10).toList()) {
                ForwardRecovery r = loadForwardRecovery(pos, 24);
                String status = classifyClosedSl(pos, r);
                if ("STOP_SWEPT".equals(status)) stopSwept++;
                else if ("VALID_STOP".equals(status)) validStop++;
                else unknown++;
                BigDecimal entry = entryPrice(pos);
                BigDecimal exit = pos.getExitPrice();
                sb.append(String.format("Position #%d %s [%s] status=%s\n",
                        pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()), status));
                sb.append("  entry/exit/sl: ").append(fmt(entry)).append(" / ")
                        .append(fmt(exit)).append(" / ").append(fmt(pos.getSuggestedSl())).append("\n");
                sb.append("  forward24h maxHigh/minLow: ").append(fmt(r != null ? r.maxHigh() : null))
                        .append(" / ").append(fmt(r != null ? r.minLow() : null))
                        .append(" | maxHighAt: ").append(fmtTime(r != null ? r.maxHighTime() : null))
                        .append(" | bars=").append(r != null ? r.bars() : 0).append("\n");
                sb.append("  recoveryToEntry: ").append(fmtPct(r != null ? r.recoveryToEntryPct() : null))
                        .append(" | recoveryFromExit: ").append(fmtPct(r != null ? r.recoveryFromExitPct() : null)).append("\n");
                sb.append("  interpretation: ").append(closedSlReason(status)).append("\n");
                sb.append("---\n");
            }
        }

        sb.append("\nSummary:\n");
        sb.append("openPositions=").append(open.size()).append("\n");
        sb.append("initialSlTooTight=").append(initialTooTight).append("\n");
        sb.append("profitLocked=").append(profitLocked).append("\n");
        sb.append("openOk=").append(openOk).append("\n");
        sb.append("closedSlReviewed=").append(closed.size()).append("\n");
        sb.append("stopSwept=").append(stopSwept).append("\n");
        sb.append("validStop=").append(validStop).append("\n");
        sb.append("unknown=").append(unknown).append("\n");
        if (initialTooTight > 0 || stopSwept > 0) {
            sb.append("Operator action: APPLY_BTC_SPOT_DISASTER_SL_SIZING for new entries; do not use tight wick-prone SL. Use ultra-low disaster SL with smaller size, or skip if recommendedAmountUsdt < minTradeUsdt.\n");
        } else if (profitLocked > 0) {
            sb.append("Operator action: HOLD profit-locked OCO; do not loosen SL just because structural SL is lower.\n");
        } else {
            sb.append("Operator action: HOLD; no immediate stop-sweep pattern detected.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only BTC spot wick-aware exit plan. "
            + "Classifies open spot LONG positions into HOLD, WICK_ONLY, DCA_CANDIDATE, "
            + "SOFT_EXIT_REVIEW, or HARD_OCO_WICK_VULNERABLE using structural SL and close-confirmation. "
            + "Outputs proposal only; no OCO/order/strategy/grid/fund behavior changed. "
            + "params: symbol optional default BTCUSDT, confirmBars default 2, dcaNotionalUsdt default 25")
    public String analyzeSpotWickAwarePlan(String symbol, Integer confirmBars, BigDecimal dcaNotionalUsdt) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int confirms = confirmBars == null || confirmBars <= 0 ? 2 : Math.min(confirmBars, 4);
        BigDecimal dcaNotional = dcaNotionalUsdt != null && dcaNotionalUsdt.signum() > 0
                ? dcaNotionalUsdt
                : new BigDecimal("25");

        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> !"SHORT".equalsIgnoreCase(p.getSide()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Spot Wick-Aware Exit Plan ===\n");
        sb.append("symbol: ").append(sym).append(" | confirmBars=").append(confirms).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("rule: BTC spot hard OCO SL should be ultra-low disaster protection; wick-only moves require close-confirmation before exit.\n");
        sb.append("tolerance: SL within 0.5% of entry above policySl is treated as acceptable to avoid false alarms on rounded disaster stops.\n");
        sb.append("dcaRule: DCA is candidate-only; require wick pierce + close recovery + exposure review before any add.\n\n");

        int vulnerable = 0;
        int wickOnly = 0;
        int dca = 0;
        int softExit = 0;
        int hold = 0;

        if (positions.isEmpty()) {
            sb.append("Open spot positions: none\n");
        } else {
            for (BtLiveSignal pos : positions) {
                SpotWickAwareDecision d = evaluateSpotWickAware(pos, confirms, dcaNotional);
                if ("HARD_OCO_WICK_VULNERABLE".equals(d.status)) vulnerable++;
                else if ("WICK_ONLY".equals(d.status)) wickOnly++;
                else if ("DCA_CANDIDATE".equals(d.status)) dca++;
                else if ("SOFT_EXIT_REVIEW".equals(d.status)) softExit++;
                else hold++;

                sb.append(String.format("Position #%d %s [%s] status=%s\n",
                        pos.getId(), pos.getSymbol(), nullSafe(pos.getIntervalCode()), d.status));
                sb.append("  entry/current/sl: ").append(fmt(d.entry)).append(" / ")
                        .append(fmt(d.current)).append(" / ").append(fmt(d.currentSl)).append("\n");
                sb.append("  policyMode: ").append(nullSafe(d.policyMode))
                        .append(" | policySl: ").append(fmt(d.policySl))
                        .append(" | structuralSl: ").append(fmt(d.structuralSl))
                        .append(" | disasterSl: ").append(fmt(d.disasterSl)).append("\n");
                sb.append("  swingLow: ").append(fmt(d.swingLow))
                        .append(" | buffer: ").append(fmt(d.buffer))
                        .append(" | atrAbs: ").append(fmt(d.atrAbs))
                        .append(" | bars=").append(d.structureBars)
                        .append(" source=").append(nullSafe(d.structureSource)).append("\n");
                sb.append("  lastLow/lastClose/prevClose: ").append(fmt(d.lastLow))
                        .append(" / ").append(fmt(d.lastClose))
                        .append(" / ").append(fmt(d.previousClose)).append("\n");
                sb.append("  currentSlGapToStructural: ").append(fmtPct(d.currentSlGapToStructuralPct))
                        .append(" | currentSlGapToPolicy: ").append(fmtPct(d.currentSlGapToPolicyPct))
                        .append(" | policyRiskPct: ").append(fmtPct(d.policyRiskPct))
                        .append(" | wickDepthPct: ").append(fmtPct(d.wickDepthPct)).append("\n");
                sb.append("  suggestedDcaNotionalUsdt: ").append(fmt(d.suggestedDcaNotionalUsdt)).append("\n");
                sb.append("  partialTp: ").append(nullSafe(d.partialTpAction))
                        .append(" — ").append(nullSafe(d.partialTpReason)).append("\n");
                sb.append("  suggestedAction: ").append(d.action).append("\n");
                sb.append("  reason: ").append(d.reason).append("\n");
                sb.append("---\n");
            }
        }

        sb.append("\nSummary:\n");
        sb.append("openPositions=").append(positions.size()).append("\n");
        sb.append("hardOcoWickVulnerable=").append(vulnerable).append("\n");
        sb.append("wickOnly=").append(wickOnly).append("\n");
        sb.append("dcaCandidates=").append(dca).append("\n");
        sb.append("softExitReviews=").append(softExit).append("\n");
        sb.append("hold=").append(hold).append("\n");
        if (softExit > 0) {
            sb.append("Operator action: REVIEW_SOFT_EXIT; do not DCA into confirmed breakdown.\n");
        } else if (dca > 0) {
            sb.append("Operator action: DCA_CANDIDATE_REVIEW_ONLY; check exposure cap and macro filters before any add.\n");
        } else if (vulnerable > 0 || wickOnly > 0) {
            sb.append("Operator action: CONVERT_NEXT_ENTRIES_TO_BTC_SPOT_DISASTER_SL; hard OCO should sit at ultra-low disaster SL, not inside wick zone.\n");
        } else {
            sb.append("Operator action: HOLD; no wick-aware action needed now.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only BTC spot anti-wick policy coverage scan. "
            + "Lists enabled BTC strategies and whether live auto entries default to ultra-low disaster SL. "
            + "No OCO/order/strategy/grid/fund behavior changed. params: symbol optional default BTCUSDT")
    public String analyzeSpotAntiWickPolicyCoverage(String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        List<BtStrategy> strategies = strategyRepository.findByEnabled(Boolean.TRUE).stream()
                .filter(s -> strategyCoversSymbol(s, sym))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== BTC Spot Anti-Wick Policy Coverage ===\n");
        sb.append("symbol: ").append(sym).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("policy: live BTC spot LONG entries default to ULTRA_LOW_DISASTER SL (12% below entry or lower than structural SL) with risk-sized notional.\n\n");

        int liveCovered = 0;
        int shadowCovered = 0;
        int review = 0;
        int shortOnly = 0;
        if (strategies.isEmpty()) {
            sb.append("Enabled strategies covering ").append(sym).append(": none\n");
        } else {
            for (BtStrategy strategy : strategies) {
                JsonNode cfg = readConfig(strategy.getConfigJson());
                boolean notifyOnly = configBoolean(cfg, "notifyOnly", false);
                boolean allowShort = configBoolean(cfg, "allowShort", false)
                        || configBoolean(cfg, "shortOnly", false);
                boolean wickEnabled = configBoolean(cfg, "spotWickAwareExitEnabled", true);
                String mode = configText(cfg, "spotWickAwareSlMode",
                        "BTCUSDT".equalsIgnoreCase(sym) ? "ULTRA_LOW_DISASTER" : "STRUCTURAL")
                        .trim().toUpperCase();
                double disasterPct = configDouble(cfg, "spotWickAwareDisasterSlPct", 0.12);
                String status;
                String action;
                if (allowShort) {
                    shortOnly++;
                    status = "SHORT_REVIEW";
                    action = "Do not blindly apply spot disaster SL to SHORT/SWAP logic.";
                } else if (!wickEnabled) {
                    review++;
                    status = "REVIEW_DISABLED";
                    action = "Enable spotWickAwareExitEnabled or explain why this BTC spot strategy is exempt.";
                } else if (!"ULTRA_LOW_DISASTER".equals(mode)) {
                    review++;
                    status = "REVIEW_MODE";
                    action = "Set spotWickAwareSlMode=ULTRA_LOW_DISASTER for BTC spot live entries.";
                } else if (notifyOnly) {
                    shadowCovered++;
                    status = "SHADOW_COVERED";
                    action = "Covered before promotion; still notifyOnly now.";
                } else {
                    liveCovered++;
                    status = "LIVE_COVERED";
                    action = "Future live LONG entries use disaster SL plus risk sizing.";
                }

                sb.append(String.format("#%d %s status=%s\n",
                        strategy.getId(), nullSafe(strategy.getName()), status));
                sb.append("  type=").append(nullSafe(strategy.getStrategyType()))
                        .append(" | symbols=").append(nullSafe(strategy.getSymbols()))
                        .append(" | notifyOnly=").append(notifyOnly)
                        .append(" | allowShort=").append(allowShort).append("\n");
                sb.append("  spotWickAwareExitEnabled=").append(wickEnabled)
                        .append(" | spotWickAwareSlMode=").append(mode)
                        .append(" | disasterSlPct=").append(String.format(java.util.Locale.ROOT, "%.2f%%", disasterPct * 100.0))
                        .append("\n");
                sb.append("  action: ").append(action).append("\n");
                sb.append("---\n");
            }
        }

        sb.append("\nSummary:\n");
        sb.append("enabledStrategies=").append(strategies.size()).append("\n");
        sb.append("liveCovered=").append(liveCovered).append("\n");
        sb.append("shadowCovered=").append(shadowCovered).append("\n");
        sb.append("review=").append(review).append("\n");
        sb.append("shortReview=").append(shortOnly).append("\n");
        if (review > 0) {
            sb.append("Operator action: REVIEW_POLICY_GAPS before promoting or relying on those BTC spot strategies.\n");
        } else {
            sb.append("Operator action: HOLD; enabled BTC spot LONG strategies are covered by anti-wick disaster-SL policy.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only A/B replay for BTC spot anti-wick SL policy. "
            + "Uses existing live auto-trade entries and OKX 1h klines, keeps TP unchanged, compares original SL vs "
            + "ultra-low disaster SL. This is exit-policy replay, not a new order or strategy enable. "
            + "params: symbol optional default BTCUSDT, days default 90 max 180, disasterSlPct default 0.12, notionalUsdt default 50")
    public String backtestSpotAntiWickPolicy(
            @ToolParam(required = false, description = "交易對，預設 BTCUSDT") String symbol,
            @ToolParam(required = false, description = "回溯天數，預設 90，最多 180") Integer days,
            @ToolParam(required = false, description = "災難 SL 百分比，例如 0.12=12%，預設 0.12") BigDecimal disasterSlPct,
            @ToolParam(required = false, description = "每筆 replay 名目金額，預設 50 USDT") BigDecimal notionalUsdt) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int lookbackDays = days == null || days <= 0 ? 90 : Math.min(days, 180);
        BigDecimal pct = disasterSlPct != null && disasterSlPct.signum() > 0
                ? disasterSlPct
                : BTC_SPOT_DISASTER_SL_PCT;
        pct = pct.max(new BigDecimal("0.03")).min(new BigDecimal("0.50"));
        BigDecimal notional = notionalUsdt != null && notionalUsdt.signum() > 0
                ? notionalUsdt
                : new BigDecimal("50");
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);

        List<BtLiveSignal> positions = new ArrayList<>();
        positions.addAll(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(since));
        positions.addAll(liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> {
                    LocalDateTime t = p.getCreatedAt() != null ? p.getCreatedAt() : p.getBarOpenTime();
                    return t == null || !t.isBefore(since);
                })
                .toList());
        positions = positions.stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .filter(p -> !"SHORT".equalsIgnoreCase(p.getSide()))
                .filter(p -> Boolean.TRUE.equals(p.getAutoTraded()))
                .sorted(Comparator.comparing(p -> {
                    LocalDateTime t = p.getCreatedAt() != null ? p.getCreatedAt() : p.getBarOpenTime();
                    return t != null ? t : LocalDateTime.MIN;
                }))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== BTC Spot Anti-Wick Policy Replay ===\n");
        sb.append("symbol: ").append(sym).append(" | days=").append(lookbackDays)
                .append(" | disasterSlPct=").append(fmtPct(pct))
                .append(" | notionalUsdt=").append(fmt(notional)).append("\n");
        sb.append("boundary: READ_ONLY; no trading, OCO, strategy, grid, or fund behavior changed.\n");
        sb.append("method: existing auto-trade entries only; TP unchanged; compare original suggested SL vs min(structural SL, entry*(1-disasterSlPct)); OKX 1h first-touch replay, SL-first when TP and SL touch same candle.\n");
        sb.append("caveat: this validates exit-policy direction; it does not regenerate entries or model live order-book slippage.\n\n");

        if (positions.isEmpty()) {
            sb.append("No auto-traded BTC spot positions in window.\n");
            return sb.toString();
        }

        int evaluated = 0;
        int skipped = 0;
        int savedStops = 0;
        int policyDisasterHits = 0;
        int policyTp = 0;
        int originalTp = 0;
        int originalSlCount = 0;
        int ambiguous = 0;
        BigDecimal originalNet = BigDecimal.ZERO;
        BigDecimal policyNet = BigDecimal.ZERO;
        BigDecimal deltaNet = BigDecimal.ZERO;

        for (BtLiveSignal pos : positions) {
            BigDecimal entry = entryPrice(pos);
            BigDecimal originalSlPrice = pos.getSuggestedSl();
            BigDecimal tp = pos.getSuggestedTp();
            LocalDateTime start = pos.getCreatedAt() != null ? pos.getCreatedAt() : pos.getBarOpenTime();
            LocalDateTime end = pos.getExitTime() != null ? pos.getExitTime() : LocalDateTime.now(ZoneOffset.UTC);
            if (entry == null || entry.signum() <= 0 || originalSlPrice == null || originalSlPrice.signum() <= 0
                    || tp == null || tp.signum() <= 0 || start == null || end == null || !end.isAfter(start)) {
                skipped++;
                continue;
            }
            List<MdKline> bars = loadBars(pos.getSymbol(), "1h", start.minusHours(1), end.plusHours(1));
            if (bars.isEmpty()) {
                skipped++;
                continue;
            }
            StructuralStop structural = loadStructuralStop(pos);
            BigDecimal disasterSl = entry.multiply(BigDecimal.ONE.subtract(pct)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal policySl = selectedPolicySl(
                    structural != null ? structural.price() : null,
                    disasterSl,
                    "ULTRA_LOW_DISASTER");

            ReplayOutcome original = replayLongOco(entry, tp, originalSlPrice, notional, bars, start, end);
            ReplayOutcome policy = replayLongOco(entry, tp, policySl, notional, bars, start, end);
            if (original == null || policy == null) {
                skipped++;
                continue;
            }
            evaluated++;
            originalNet = originalNet.add(original.netPnlUsdt());
            policyNet = policyNet.add(policy.netPnlUsdt());
            BigDecimal diff = policy.netPnlUsdt().subtract(original.netPnlUsdt());
            deltaNet = deltaNet.add(diff);
            if ("TP".equals(original.reason())) originalTp++;
            if ("SL".equals(original.reason())) originalSlCount++;
            if ("TP".equals(policy.reason())) policyTp++;
            if ("SL".equals(policy.reason())) policyDisasterHits++;
            if (original.ambiguousSameBar() || policy.ambiguousSameBar()) ambiguous++;
            if ("SL".equals(original.reason()) && !"SL".equals(policy.reason())) {
                savedStops++;
            }

            sb.append(String.format("Position #%d strategy=%d [%s]\n",
                    pos.getId(), pos.getStrategyId(), nullSafe(pos.getIntervalCode())));
            sb.append("  entry/tp/originalSl/policySl: ").append(fmt(entry)).append(" / ")
                    .append(fmt(tp)).append(" / ").append(fmt(originalSlPrice)).append(" / ")
                    .append(fmt(policySl)).append("\n");
            sb.append("  original: ").append(original.reason()).append(" @ ").append(fmt(original.exitPrice()))
                    .append(" pnl=").append(fmt(original.netPnlUsdt())).append(" time=")
                    .append(fmtTime(original.exitTime())).append("\n");
            sb.append("  policy:   ").append(policy.reason()).append(" @ ").append(fmt(policy.exitPrice()))
                    .append(" pnl=").append(fmt(policy.netPnlUsdt())).append(" time=")
                    .append(fmtTime(policy.exitTime())).append(" delta=")
                    .append(fmt(diff)).append("\n");
            sb.append("---\n");
        }

        sb.append("\nSummary:\n");
        sb.append("evaluated=").append(evaluated).append("\n");
        sb.append("skipped=").append(skipped).append("\n");
        sb.append("originalTp=").append(originalTp).append("\n");
        sb.append("originalSl=").append(originalSlCount).append("\n");
        sb.append("policyTp=").append(policyTp).append("\n");
        sb.append("policyDisasterSl=").append(policyDisasterHits).append("\n");
        sb.append("savedStopSweeps=").append(savedStops).append("\n");
        sb.append("ambiguousSameBar=").append(ambiguous).append("\n");
        sb.append("originalNetUsdt=").append(fmt(originalNet)).append("\n");
        sb.append("policyNetUsdt=").append(fmt(policyNet)).append("\n");
        sb.append("deltaNetUsdt=").append(fmt(deltaNet)).append("\n");
        if (evaluated == 0) {
            sb.append("Operator action: INSUFFICIENT_SAMPLE; no replayable positions.\n");
        } else if (policyDisasterHits > 0 && deltaNet.signum() < 0) {
            sb.append("Operator action: REVIEW; disaster SL avoided noise but worsened replay PnL. Consider smaller notional or tighter policy.\n");
        } else if (savedStops > 0 && deltaNet.signum() >= 0) {
            sb.append("Operator action: KEEP_BTC_SPOT_DISASTER_SL_POLICY; replay supports avoiding wick-prone SL with small notional.\n");
        } else {
            sb.append("Operator action: WATCH; sample does not strongly prove or disprove the policy yet.\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Preview an OCO risk-reducing SL move for AI Execution Manager. "
            + "Modes: BREAKEVEN, TIGHTEN_BY_ATR, TRAILING_LOCK, CUSTOM_SL. "
            + "Returns previewToken valid for 10 minutes when allowed. No OCO write. "
            + "params: positionId, targetMode, customSl optional")
    public String previewOcoRiskReduction(Long positionId, String targetMode, BigDecimal customSl) {
        BtLiveSignal pos = findOpenPosition(positionId);
        if (pos == null) return "❌ open position not found: " + positionId;
        String mode = targetMode == null || targetMode.isBlank() ? "BREAKEVEN" : targetMode.trim().toUpperCase();
        BigDecimal targetSl = proposeRiskReducingSl(pos, mode, customSl);
        return buildRiskReductionPreview(pos, mode, targetSl, true);
    }

    @McpAuth(McpAuthLevel.LOCAL_ONLY)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "AI Execution Manager safe OCO SL modify. Risk-reducing only: TP unchanged, OCO required, "
            + "LONG SL may only move up, SHORT SL may only move down, and max loss must decrease. "
            + "Requires previewToken from previewOcoRiskReduction. Sends TG through modifyOco and writes EXIT_ADJUST audit. "
            + "params: positionId, newSl, reason, previewToken")
    public String modifyOcoRiskReducingOnly(Long positionId, BigDecimal newSl, String reason, String previewToken) {
        if (positionId == null) return "❌ positionId 必填";
        if (newSl == null || newSl.signum() <= 0) return "❌ newSl 必須 > 0";
        if (reason == null || reason.isBlank()) return "❌ reason 必填";
        if (previewToken == null || previewToken.isBlank()) return "❌ previewToken 必填，請先呼叫 previewOcoRiskReduction";

        RiskReductionPreview preview = riskReductionPreviews.get(previewToken);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (preview == null) return "❌ previewToken 無效或已被使用";
        if (preview.expiresAtUtc().isBefore(now)) {
            riskReductionPreviews.remove(previewToken);
            return "❌ previewToken 已過期，請重新 preview";
        }
        if (!positionId.equals(preview.positionId()) || newSl.compareTo(preview.newSl()) != 0) {
            return "❌ previewToken 與 positionId/newSl 不匹配，請重新 preview";
        }

        BtLiveSignal pos = findOpenPosition(positionId);
        if (pos == null) return "❌ open position not found: " + positionId;
        String validation = validateRiskReducingSl(pos, newSl, latestPriceOrNull(pos.getSymbol()));
        if (validation != null) return "❌ risk-reducing guard blocked: " + validation;

        try {
            String result = ocoManagementService.modifyOco(positionId, newSl, null);
            riskReductionPreviews.remove(previewToken);
            Map<String, Object> context = Map.of(
                    "tool", "modifyOcoRiskReducingOnly",
                    "oldSl", safePlain(preview.oldSl()),
                    "newSl", safePlain(newSl),
                    "tpUnchanged", true,
                    "previewToken", previewToken,
                    "reason", reason.trim());
            auditWriter.logExitAdjustment(pos.getStrategyId(), pos.getSymbol(), pos.getId(),
                    "[AI_EXECUTION_MANAGER_RISK_REDUCING] " + reason.trim(), context);
            return "✅ Risk-reducing OCO SL update executed\n"
                    + "positionId=" + positionId + "\n"
                    + "SL: " + fmt(preview.oldSl()) + " -> " + fmt(newSl) + "\n"
                    + "TP unchanged: " + fmt(preview.oldTp()) + "\n"
                    + "reason=" + reason.trim() + "\n\n"
                    + result;
        } catch (Exception e) {
            log.error("[PositionMcpTools] modifyOcoRiskReducingOnly failed: positionId={}", positionId, e);
            return "❌ risk-reducing OCO 修改失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "查詢 OKX 帳戶現貨持倉、資金帳戶餘額與 USDT 可用餘額。顯示各幣種數量與估值。" +
            "包含交易帳戶（Trading）+ 資金帳戶（Funding），合計才是完整可動用資產。")
    public String getBalance() {

        List<OkxTradingService.SpotHolding> holdings;
        try {
            holdings = okxTradingService.getSpotHoldings();
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getSpotHoldings failed: {}", e.getMessage());
            return "❌ 無法查詢 OKX 餘額，請稍後重試";
        }

        // Issue #155: 加查資金帳戶 — 漏算會讓總資產低估數十美元
        List<OkxTradingService.SpotHolding> fundingHoldings;
        try {
            fundingHoldings = okxTradingService.getFundingHoldings();
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getFundingHoldings failed: {}", e.getMessage());
            fundingHoldings = java.util.Collections.emptyList();
        }

        if (holdings.isEmpty() && fundingHoldings.isEmpty()) {
            return "OKX 帳戶無現貨持倉。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== OKX 帳戶餘額 ===\n\n");

        BigDecimal totalUsd = BigDecimal.ZERO;
        if (!holdings.isEmpty()) {
            sb.append("【交易帳戶】\n");
        }
        for (OkxTradingService.SpotHolding h : holdings) {
            // Display cashBal (total holding) rather than availBal — availBal excludes
            // quantity locked in open OCO/algo orders, which makes the number look
            // ~1e6x smaller when most of the coin is parked under stop-loss protection.
            // cashBal matches getCurrentReport / buildHoldingsSection formatting.
            sb.append(h.ccy).append(": ").append(h.cashBal.toPlainString());
            if (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(" (~$").append(String.format("%.2f", h.eqUsd.doubleValue())).append(")");
                totalUsd = totalUsd.add(h.eqUsd);
            }
            // Surface availBal when it materially differs from cashBal so the user
            // can see how much is locked in open orders. Threshold: > 1e-6 abs diff.
            if (h.availBal != null && h.cashBal != null
                    && h.availBal.subtract(h.cashBal).abs()
                        .compareTo(new BigDecimal("0.000001")) > 0) {
                sb.append("  [avail: ").append(h.availBal.toPlainString()).append("]");
            }
            sb.append("\n");
        }

        if (!fundingHoldings.isEmpty()) {
            sb.append("\n【資金帳戶】\n");
            for (OkxTradingService.SpotHolding h : fundingHoldings) {
                sb.append(h.ccy).append(": ").append(h.cashBal.toPlainString());
                if (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" (~$").append(String.format("%.2f", h.eqUsd.doubleValue())).append(")");
                    totalUsd = totalUsd.add(h.eqUsd);
                }
                sb.append("\n");
            }
        }

        if (totalUsd.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("\n總估值: ~$").append(String.format("%.2f", totalUsd.doubleValue())).append(" USD\n");
        }

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "一次回傳 OKX 三帳戶（Trading 交易帳戶 + Earn 靈活存款 + Funding 資金帳戶）" +
            "完整餘額與總估值（USD），結構化 JSON 格式。" +
            "用來取代呼叫端各自呼叫 getBalance / getEarnBalance 三次後手動 sum；" +
            "與 OKX App 首頁總資產對齊（誤差僅來自即時價格波動）。")
    public String getTotalAssets() {

        ObjectNode root = objectMapper.createObjectNode();
        BigDecimal totalUsd = BigDecimal.ZERO;

        // ── Trading 帳戶 ──
        ObjectNode trading = root.putObject("trading");
        try {
            List<OkxTradingService.SpotHolding> holdings = okxTradingService.getSpotHoldings();
            for (OkxTradingService.SpotHolding h : holdings) {
                if (h.cashBal != null) {
                    trading.put(h.ccy, h.cashBal);
                }
                if (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.ZERO) > 0) {
                    // Surface USD value for non-stablecoins so the caller can see how
                    // much each holding contributes (e.g. BTC qty + BTC_value_usd).
                    if (!"USDT".equalsIgnoreCase(h.ccy) && !"USDC".equalsIgnoreCase(h.ccy)) {
                        trading.put(h.ccy + "_value_usd", h.eqUsd.setScale(2, RoundingMode.HALF_UP));
                    }
                    totalUsd = totalUsd.add(h.eqUsd);
                }
            }
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getTotalAssets trading failed: {}", e.getMessage());
            trading.put("error", e.getMessage());
        }

        // ── Earn 帳戶（靈活存款） ──
        ObjectNode earn = root.putObject("earn");
        try {
            List<OkxEarnService.EarnBalance> earnBalances = okxEarnService.getBalance(null);
            for (OkxEarnService.EarnBalance b : earnBalances) {
                if (b.amt() == null || b.amt().compareTo(BigDecimal.ZERO) <= 0) continue;
                earn.put(b.ccy() + "_principal", b.amt());
                earn.put(b.ccy() + "_interest", b.earnings() != null ? b.earnings() : BigDecimal.ZERO);
                BigDecimal apyPct = b.apyAnnualized();
                if (apyPct != null) {
                    // EarnBalance.apyAnnualized() already returns percent (e.g. 3.65).
                    // JSON shape per #166 spec uses decimal APY (0.0365).
                    earn.put(b.ccy() + "_APY",
                            apyPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                }
                // Earn 多以 stablecoin 為主，本金與利息直接以 USD 計入總額；
                // 非 USD/USDC 幣種無價計算則略過總額累計。
                if ("USDT".equalsIgnoreCase(b.ccy()) || "USDC".equalsIgnoreCase(b.ccy())) {
                    totalUsd = totalUsd.add(b.amt());
                    if (b.earnings() != null) totalUsd = totalUsd.add(b.earnings());
                }
            }
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getTotalAssets earn failed: {}", e.getMessage());
            earn.put("error", e.getMessage());
        }

        // ── Funding 帳戶 ──
        ObjectNode funding = root.putObject("funding");
        try {
            List<OkxTradingService.SpotHolding> fundingHoldings = okxTradingService.getFundingHoldings();
            for (OkxTradingService.SpotHolding h : fundingHoldings) {
                if (h.cashBal != null) {
                    funding.put(h.ccy, h.cashBal);
                }
                if (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.ZERO) > 0) {
                    if (!"USDT".equalsIgnoreCase(h.ccy) && !"USDC".equalsIgnoreCase(h.ccy)) {
                        funding.put(h.ccy + "_value_usd", h.eqUsd.setScale(2, RoundingMode.HALF_UP));
                    }
                    totalUsd = totalUsd.add(h.eqUsd);
                }
            }
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getTotalAssets funding failed: {}", e.getMessage());
            funding.put("error", e.getMessage());
        }

        root.put("total_usd", totalUsd.setScale(2, RoundingMode.HALF_UP));
        root.put("as_of", Instant.now().atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("[PositionMcpTools] getTotalAssets serialize failed", e);
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "直接向 OKX 查詢每個開倉的 OCO 止損止盈單狀態（依 algoId 逐筆查詢），" +
            "確認交易所端是否真的有保護，不依賴本地 DB 快取。" +
            "顯示：algoId、交易對、TP 觸發價、SL 觸發價、OKX 當前狀態。")
    public String getOkxPositions() {
        List<com.agora.model.BtLiveSignal> positions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull();

        if (positions.isEmpty()) {
            return "本地 DB 無帶 OCO 的開倉記錄。";
        }

        StringBuilder sb = new StringBuilder("=== OKX OCO 訂單直查 (共 ")
                .append(positions.size()).append(" 筆) ===\n\n");

        for (com.agora.model.BtLiveSignal pos : positions) {
            boolean isShort = "SHORT".equals(pos.getSide());
            sb.append(pos.getSymbol()).append(" #").append(pos.getId())
              .append(" (").append(isShort ? "SHORT/SWAP" : "LONG/SPOT").append(")\n");
            sb.append("  algoId: ").append(pos.getOcoOrderListId()).append("\n");
            try {
                JsonNode o = isShort
                        ? okxTradingService.getSwapAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId())
                        : okxTradingService.getAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId());

                if (o.isMissingNode() || o.isNull()) {
                    sb.append("  ⚠️ OKX 查無此單（已觸發或已取消）\n");
                } else {
                    String state = o.path("state").asText("?");
                    String stateEmoji = switch (state) {
                        case "live", "effective" -> "✅";
                        case "filled"   -> "🎯 已成交";
                        case "canceled" -> "❌ 已取消";
                        default -> "⚠️";
                    };
                    String cTimeRaw = o.path("cTime").asText("");
                    String cTime = cTimeRaw.isEmpty() ? "N/A" : Instant
                            .ofEpochMilli(Long.parseLong(cTimeRaw))
                            .atZone(ZoneId.of("Asia/Taipei"))
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                    sb.append("  state: ").append(stateEmoji).append(" ").append(state).append("\n");
                    sb.append("  TP: ").append(o.path("tpTriggerPx").asText("N/A"))
                      .append("  SL: ").append(o.path("slTriggerPx").asText("N/A")).append("\n");
                    sb.append("  sz: ").append(o.path("sz").asText("N/A"))
                      .append("  建立: ").append(cTime).append("\n");
                }
            } catch (Exception e) {
                sb.append("  ❌ 查詢失敗: ").append(e.getMessage()).append("\n");
            }
            sb.append("---\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "查詢 OKX 近期成交記錄（SPOT 現貨 + SWAP 合約），顯示幣種、買賣方向、成交價、數量、已實現損益與時間。" +
            "可用來查手動交易或系統自動交易的 OKX 端實際成交。param: limit=筆數（預設20，最多100）。")
    public String getOkxTradeHistory(Integer limit) {
        if (limit == null || limit <= 0 || limit > 100) limit = 20;

        StringBuilder sb = new StringBuilder("=== OKX 近期成交記錄 ===\n\n");
        int total = 0;

        for (String instType : List.of("SWAP", "SPOT")) {
            try {
                com.fasterxml.jackson.databind.JsonNode fills = okxTradingService.getRecentFills(instType, limit);
                if (!fills.isArray() || fills.size() == 0) continue;

                sb.append("【").append(instType).append("】\n");
                for (com.fasterxml.jackson.databind.JsonNode f : fills) {
                    long tsMs = f.path("ts").asLong(0);
                    String time = tsMs > 0
                            ? java.time.Instant.ofEpochMilli(tsMs)
                                .atZone(java.time.ZoneId.of("Asia/Taipei"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                            : "N/A";
                    String instId  = f.path("instId").asText("-");
                    String side    = f.path("side").asText("-");
                    String px      = f.path("fillPx").asText("-");
                    String sz      = f.path("fillSz").asText("-");
                    String pnl     = f.path("fillPnl").asText("0");
                    String fee     = f.path("fee").asText("0");
                    String feeCcy  = f.path("feeCcy").asText("");
                    String ordId   = f.path("ordId").asText("");
                    String sideEmoji = "buy".equals(side) ? "🟢買" : "🔴賣";
                    sb.append(String.format("  %s  %-18s %s  px=%-10s sz=%-8s pnl=%s  fee=%s%s  ordId=%s\n",
                            time, instId, sideEmoji, px, sz, pnl,
                            fee, feeCcy.isEmpty() ? "" : " " + feeCcy, ordId));
                    total++;
                }
                sb.append("\n");
            } catch (Exception e) {
                log.warn("[PositionMcpTools] getOkxTradeHistory {} failed: {}", instType, e.getMessage());
                sb.append("  ❌ ").append(instType).append(" 查詢失敗: ").append(e.getMessage()).append("\n\n");
            }
        }

        if (total == 0) sb.append("近期無成交記錄。\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "查詢 OKX OCO 止盈止損歷史訂單（SPOT + SWAP），不需要 algoId 即可列出所有已觸發/已取消的 OCO 訂單。" +
            "顯示：幣種、TP/SL 觸發價、實際成交均價、建立時間、狀態。" +
            "適合用來溯源找不到 algoId 的歷史 OCO，或驗證止損確實成交。param: limit=筆數（預設20，最多100）。")
    public String getAlgoOrderHistory(Integer limit) {
        if (limit == null || limit <= 0 || limit > 100) limit = 20;

        StringBuilder sb = new StringBuilder("=== OKX OCO 歷史訂單 ===\n\n");
        int total = 0;

        for (String instType : List.of("SWAP", "SPOT")) {
            try {
                JsonNode orders = okxTradingService.getAlgoOrderHistory(instType, limit);
                if (!orders.isArray() || orders.size() == 0) continue;

                sb.append("【").append(instType).append("】\n");
                for (JsonNode o : orders) {
                    String cTimeRaw = o.path("cTime").asText("");
                    String cTime = cTimeRaw.isEmpty() ? "N/A" : Instant
                            .ofEpochMilli(Long.parseLong(cTimeRaw))
                            .atZone(ZoneId.of("Asia/Taipei"))
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                    String instId  = o.path("instId").asText("-");
                    String state   = o.path("state").asText("-");
                    String avgPx   = o.path("avgPx").asText("0");
                    String tpPx    = o.path("tpTriggerPx").asText("N/A");
                    String slPx    = o.path("slTriggerPx").asText("N/A");
                    String sz      = o.path("sz").asText("-");
                    String algoId  = o.path("algoId").asText("-");
                    String stateEmoji = switch (state) {
                        case "filled"   -> "🎯";
                        case "canceled" -> "❌";
                        case "live", "effective" -> "✅";
                        default -> "⚠️";
                    };
                    sb.append(String.format("  %s %s  %-20s  algoId=%-14s state=%s\n",
                            cTime, stateEmoji, instId, algoId, state));
                    sb.append(String.format("    TP=%-10s SL=%-10s avgPx=%-10s sz=%s\n",
                            tpPx, slPx, "0".equals(avgPx) ? "未成交" : avgPx, sz));
                    total++;
                }
                sb.append("\n");
            } catch (Exception e) {
                log.warn("[PositionMcpTools] getAlgoOrderHistory {} failed: {}", instType, e.getMessage());
                sb.append("  ❌ ").append(instType).append(" 查詢失敗: ").append(e.getMessage()).append("\n\n");
            }
        }

        if (total == 0) sb.append("無歷史 OCO 訂單記錄。\n");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "查詢 OKX SWAP 合約已平倉歷史及官方已實現損益（realizedPnl）。" +
            "可用來交叉驗證系統計算的 PnL 是否與 OKX 端一致，或查手動平倉的實際損益。" +
            "顯示：幣種、開倉均價、平倉均價、合約數、OKX 官方 PnL、方向、平倉時間。param: limit=筆數（可省略；預設 20，上限 100）。")
    public String getSwapPnlHistory(Integer limit) {
        // MCP client may omit `limit` → Spring converts missing Integer to null; guard
        // explicitly to avoid NPE when the primitive-int unbox happens downstream.
        int n = (limit == null || limit <= 0 || limit > 100) ? 20 : limit;

        JsonNode history;
        try {
            history = okxTradingService.getSwapPositionsHistory(n);
        } catch (Exception e) {
            log.warn("[PositionMcpTools] getSwapPnlHistory failed: {}", e.getMessage());
            return "❌ 查詢 SWAP 平倉歷史失敗：" + e.getMessage();
        }

        if (!history.isArray() || history.size() == 0) {
            return "無 SWAP 平倉歷史記錄。";
        }

        StringBuilder sb = new StringBuilder("=== OKX SWAP 平倉歷史 ===\n\n");
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (JsonNode p : history) {
            String uTimeRaw = p.path("uTime").asText("");
            String closeTime = uTimeRaw.isEmpty() ? "N/A" : Instant
                    .ofEpochMilli(Long.parseLong(uTimeRaw))
                    .atZone(ZoneId.of("Asia/Taipei"))
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            String instId      = p.path("instId").asText("-");
            String openAvgPx   = p.path("openAvgPx").asText("0");
            String closeAvgPx  = p.path("closeAvgPx").asText("0");
            String pnlStr      = p.path("realizedPnl").asText("0");
            String contracts   = p.path("closeTotalPos").asText("-");
            String direction   = p.path("direction").asText("-");
            String dirEmoji    = "short".equals(direction) ? "📉" : "📈";
            double pnlVal = 0;
            try { pnlVal = Double.parseDouble(pnlStr); } catch (NumberFormatException ignored) {}
            String pnlEmoji = pnlVal >= 0 ? "🟢" : "🔴";

            sb.append(String.format("  %s  %s %s  合約:%s\n",
                    closeTime, dirEmoji, instId, contracts));
            sb.append(String.format("    開=%s → 收=%s  %s PnL: %+.4f USDT\n",
                    openAvgPx, closeAvgPx, pnlEmoji, pnlVal));

            try { totalPnl = totalPnl.add(new BigDecimal(pnlStr)); } catch (NumberFormatException ignored) {}
        }

        sb.append(String.format("\n合計已實現 PnL: %+.4f USDT（共 %d 筆）\n",
                totalPnl.doubleValue(), history.size()));
        return sb.toString();
    }

    /**
     * 強制關閉 DB 倉位記錄（不操作 OKX）。用於 OCO 已在交易所成交但 DB 未同步的場景。
     * exitQty 為實際成交量（可選，預設用 ocoQty 或 tradedQty）。
     */
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "強制關閉 DB 倉位記錄（不操作 OKX）。用於 OCO 已在交易所成交但 DB 未同步的情況（DB sync error）。" +
            "params: positionId=倉位ID, exitPrice=實際成交價, exitReason=原因(SL/TP/MANUAL/FORCE), " +
            "exitQty=實際成交量（可選，省略則用 ocoQty/tradedQty）")
    public String forceClosePosition(Long positionId, BigDecimal exitPrice,
                                     String exitReason, BigDecimal exitQty) {
        if (positionId == null || exitPrice == null || exitReason == null) {
            return "❌ 參數缺少：positionId, exitPrice, exitReason 為必填";
        }
        BtLiveSignal pos = liveSignalRepository.findById(positionId).orElse(null);
        if (pos == null) return "❌ 找不到倉位 id=" + positionId;
        if (pos.getExitTime() != null) {
            return "⚠️ 倉位已於 " + pos.getExitTime().format(FMT) + " 關閉，無需重複操作";
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        pos.setExitTime(now);
        pos.setExitPrice(exitPrice);
        pos.setExitReason(exitReason.toUpperCase());

        // 計算已實現 PnL
        BigDecimal qty = exitQty != null && exitQty.compareTo(BigDecimal.ZERO) > 0
                ? exitQty
                : (pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty());
        boolean isShort = "SHORT".equals(pos.getSide());
        if (qty != null && pos.getActualEntryPrice() != null) {
            BigDecimal pnl = isShort
                    ? pos.getActualEntryPrice().subtract(exitPrice).multiply(qty)
                    : exitPrice.subtract(pos.getActualEntryPrice()).multiply(qty);
            pos.setRealizedPnl(pnl.setScale(8, RoundingMode.HALF_UP));
        }
        liveSignalRepository.save(pos);

        String pnlStr = pos.getRealizedPnl() != null
                ? String.format("%+.4f USDT", pos.getRealizedPnl().doubleValue()) : "N/A";
        String msg = String.format(
                "🔒 <b>倉位強制關閉</b>\n#%d %s [%s]\n" +
                "入場: $%s → 出場: $%s\n數量: %s\n原因: %s\n已實現 PnL: %s\n" +
                "<i>（DB sync error 修復，OKX 操作不受影響）</i>",
                positionId, pos.getSymbol(), pos.getIntervalCode(),
                pos.getActualEntryPrice() != null
                        ? pos.getActualEntryPrice().toPlainString() : "N/A",
                exitPrice.toPlainString(),
                qty != null ? qty.toPlainString() : "N/A",
                exitReason.toUpperCase(), pnlStr);
        try { notificationPort.broadcast(msg, true); } catch (Exception ignored) {}

        log.info("[ForceClose] Closed position id={} exitPrice={} reason={} pnl={}",
                positionId, exitPrice, exitReason, pnlStr);
        return String.format("✅ 倉位 #%d 已標記關閉\n出場價: $%s  數量: %s  PnL: %s",
                positionId, exitPrice.toPlainString(),
                qty != null ? qty.toPlainString() : "N/A", pnlStr);
    }

    private String fmt(BigDecimal val) {
        return val == null ? "N/A" : val.toPlainString();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private boolean isSoftExitNoHardSl(BtLiveSignal pos) {
        return pos != null
                && pos.getFilterReason() != null
                && pos.getFilterReason().startsWith(OcoManagementService.SOFT_EXIT_NO_HARD_SL_MARKER);
    }

    private BigDecimal latestPriceOrNull(String symbol) {
        try {
            return okxTradingService.getLastPrice(symbol);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal trigger(BigDecimal entry, BigDecimal atr, BigDecimal multiple, boolean isLong) {
        BigDecimal offset = atr.multiply(multiple);
        return isLong
                ? entry.multiply(BigDecimal.ONE.add(offset))
                : entry.multiply(BigDecimal.ONE.subtract(offset));
    }

    private boolean hasReachedState(String state, String thresholdState) {
        return trailingStateRank(state) >= trailingStateRank(thresholdState);
    }

    private int trailingStateRank(String state) {
        if ("TRAILING".equalsIgnoreCase(state)) return 3;
        if ("BREAKEVEN_LOCKED".equalsIgnoreCase(state)) return 2;
        if ("ENTERED".equalsIgnoreCase(state)) return 1;
        return 0;
    }

    private boolean extremeReached(BigDecimal extreme, BigDecimal threshold, boolean isLong) {
        if (extreme == null || threshold == null) return false;
        return isLong
                ? extreme.compareTo(threshold) >= 0
                : extreme.compareTo(threshold) <= 0;
    }

    private BigDecimal theoreticalTrailingStop(BtLiveSignal pos, BigDecimal entry,
                                               BigDecimal current, BigDecimal atr,
                                               boolean isLong) {
        if (entry == null || current == null || atr == null) return null;
        String state = pos.getTrailingState() != null ? pos.getTrailingState() : "ENTERED";
        BigDecimal extreme = pos.getTrailingHigh() != null ? pos.getTrailingHigh() : current;
        BigDecimal candidate = null;

        if (hasReachedState(state, "TRAILING")) {
            BigDecimal trailDistance = extreme.multiply(atr);
            candidate = isLong ? extreme.subtract(trailDistance) : extreme.add(trailDistance);
        } else if (hasReachedState(state, "BREAKEVEN_LOCKED")) {
            candidate = feeAdjustedBreakeven(entry, isLong);
        } else {
            BigDecimal breakevenTrigger = trigger(entry, atr, new BigDecimal("0.5"), isLong);
            boolean currentReached = isLong
                    ? current.compareTo(breakevenTrigger) >= 0
                    : current.compareTo(breakevenTrigger) <= 0;
            if (currentReached) candidate = feeAdjustedBreakeven(entry, isLong);
        }

        return protectiveStop(pos.getSuggestedSl(), candidate, isLong);
    }

    private BigDecimal feeAdjustedBreakeven(BigDecimal entry, boolean isLong) {
        return isLong
                ? entry.multiply(new BigDecimal("1.001"))
                : entry.multiply(new BigDecimal("0.999"));
    }

    private BigDecimal protectiveStop(BigDecimal currentSl, BigDecimal candidate, boolean isLong) {
        if (candidate == null) return currentSl;
        if (currentSl == null) return candidate;
        return isLong
                ? candidate.max(currentSl)
                : candidate.min(currentSl);
    }

    private BigDecimal stopDeltaPct(BigDecimal currentSl, BigDecimal theoreticalStop) {
        if (currentSl == null || theoreticalStop == null || currentSl.signum() == 0) return null;
        return theoreticalStop.subtract(currentSl)
                .divide(currentSl, 6, RoundingMode.HALF_UP);
    }

    private BtLiveSignal findOpenPosition(Long positionId) {
        if (positionId == null) return null;
        return liveSignalRepository.findById(positionId)
                .filter(p -> Boolean.TRUE.equals(p.getAutoTraded()))
                .filter(p -> p.getExitTime() == null)
                .orElse(null);
    }

    private BigDecimal entryPrice(BtLiveSignal pos) {
        return pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
    }

    private BigDecimal ocoQuantity(BtLiveSignal pos) {
        return pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty();
    }

    private BigDecimal unrealizedPnl(BtLiveSignal pos, BigDecimal current) {
        BigDecimal entry = entryPrice(pos);
        BigDecimal qty = ocoQuantity(pos);
        if (entry == null || current == null || qty == null) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal diff = isLong ? current.subtract(entry) : entry.subtract(current);
        return diff.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal pnlPct(BtLiveSignal pos, BigDecimal current) {
        BigDecimal entry = entryPrice(pos);
        if (entry == null || current == null || entry.signum() <= 0) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal diff = isLong ? current.subtract(entry) : entry.subtract(current);
        return diff.divide(entry, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal maxLossUsdt(BtLiveSignal pos, BigDecimal sl) {
        BigDecimal entry = entryPrice(pos);
        BigDecimal qty = ocoQuantity(pos);
        if (entry == null || sl == null || qty == null) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal lossPerUnit = isLong ? entry.subtract(sl) : sl.subtract(entry);
        if (lossPerUnit.signum() < 0) lossPerUnit = BigDecimal.ZERO;
        return lossPerUnit.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal proposeRiskReducingSl(BtLiveSignal pos, String mode, BigDecimal customSl) {
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal entry = entryPrice(pos);
        BigDecimal current = latestPriceOrNull(pos.getSymbol());
        BigDecimal currentSl = pos.getSuggestedSl();
        if ("CUSTOM_SL".equals(mode)) return customSl;
        if (entry == null) return null;
        if ("BREAKEVEN".equals(mode)) {
            return protectiveStop(currentSl, feeAdjustedBreakeven(entry, isLong), isLong);
        }
        if ("TIGHTEN_BY_ATR".equals(mode) || "TRAILING_LOCK".equals(mode)) {
            BigDecimal atr = pos.getTrailingAtr();
            if (atr == null || atr.signum() <= 0 || current == null) {
                return protectiveStop(currentSl, feeAdjustedBreakeven(entry, isLong), isLong);
            }
            BigDecimal extreme = pos.getTrailingHigh() != null ? pos.getTrailingHigh() : current;
            BigDecimal distance = extreme.multiply(atr);
            BigDecimal candidate = isLong ? extreme.subtract(distance) : extreme.add(distance);
            return protectiveStop(currentSl, candidate, isLong);
        }
        return null;
    }

    private String buildRiskReductionPreview(BtLiveSignal pos, String mode, BigDecimal targetSl, boolean issueToken) {
        BigDecimal current = latestPriceOrNull(pos.getSymbol());
        String validation = validateRiskReducingSl(pos, targetSl, current);
        BigDecimal oldLoss = maxLossUsdt(pos, pos.getSuggestedSl());
        BigDecimal newLoss = maxLossUsdt(pos, targetSl);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewOcoRiskReduction");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("writeMode", false);
        root.put("positionId", pos.getId());
        root.put("symbol", pos.getSymbol());
        root.put("side", "SHORT".equalsIgnoreCase(pos.getSide()) ? "SHORT" : "LONG");
        root.put("mode", mode);
        putDecimal(root, "current", current);
        putDecimal(root, "entry", entryPrice(pos));
        putDecimal(root, "oldSl", pos.getSuggestedSl());
        putDecimal(root, "newSl", targetSl);
        putDecimal(root, "tp", pos.getSuggestedTp());
        putDecimal(root, "oldMaxLossUsdt", oldLoss);
        putDecimal(root, "newMaxLossUsdt", newLoss);
        putDecimal(root, "riskReducedUsdt", oldLoss != null && newLoss != null ? oldLoss.subtract(newLoss) : null);
        root.put("tpUnchanged", true);
        root.put("allowed", validation == null);
        if (validation != null) {
            root.put("blockReason", validation);
        } else if (issueToken) {
            String token = UUID.randomUUID().toString();
            LocalDateTime expires = LocalDateTime.now(ZoneOffset.UTC).plus(RISK_REDUCTION_PREVIEW_TTL);
            riskReductionPreviews.put(token, new RiskReductionPreview(
                    pos.getId(), pos.getSuggestedSl(), targetSl, pos.getSuggestedTp(),
                    current, !"SHORT".equalsIgnoreCase(pos.getSide()), expires));
            root.put("previewToken", token);
            root.put("expiresAtUtc", expires.toString());
            root.put("nextTool", "modifyOcoRiskReducingOnly(positionId,newSl,reason,previewToken)");
        }
        root.put("boundary", "preview only; no OCO write");
        return writeJson(root);
    }

    private String validateRiskReducingSl(BtLiveSignal pos, BigDecimal newSl, BigDecimal current) {
        if (newSl == null || newSl.signum() <= 0) return "newSl_missing_or_invalid";
        if (pos.getOcoOrderListId() == null) return "position_has_no_active_oco";
        if (pos.getSuggestedSl() == null) return "old_sl_missing";
        if (pos.getSuggestedTp() == null) return "tp_missing";
        BigDecimal entry = entryPrice(pos);
        if (entry == null || entry.signum() <= 0) return "entry_missing_or_invalid";
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        if (isLong) {
            if (newSl.compareTo(pos.getSuggestedSl()) < 0) return "long_sl_would_move_down";
            if (newSl.compareTo(pos.getSuggestedTp()) >= 0) return "long_sl_crosses_tp";
            if (current != null && newSl.compareTo(current) >= 0) return "long_sl_crosses_current_price";
        } else {
            if (newSl.compareTo(pos.getSuggestedSl()) > 0) return "short_sl_would_move_up";
            if (newSl.compareTo(pos.getSuggestedTp()) <= 0) return "short_sl_crosses_tp";
            if (current != null && newSl.compareTo(current) <= 0) return "short_sl_crosses_current_price";
        }
        BigDecimal oldLoss = maxLossUsdt(pos, pos.getSuggestedSl());
        BigDecimal newLoss = maxLossUsdt(pos, newSl);
        if (oldLoss == null || newLoss == null) return "max_loss_unavailable";
        if (newLoss.compareTo(oldLoss) >= 0) return "max_loss_not_reduced";
        return null;
    }

    private RiskGuardResult riskReducingCandidate(BtLiveSignal pos, BigDecimal current) {
        BigDecimal candidate = proposeRiskReducingSl(pos, "BREAKEVEN", null);
        String validation = validateRiskReducingSl(pos, candidate, current);
        return validation == null
                ? new RiskGuardResult(true, "BREAKEVEN preview available")
                : new RiskGuardResult(false, validation);
    }

    private StopSweepDecision evaluateOpenStopSweepRisk(BtLiveSignal pos, BigDecimal overrideRiskBudget,
                                                        BigDecimal minTradeUsdt) {
        StopSweepDecision d = new StopSweepDecision();
        d.entry = entryPrice(pos);
        d.current = latestPriceOrNull(pos.getSymbol());
        d.currentSl = pos.getSuggestedSl();

        if (d.entry == null || d.entry.signum() <= 0 || d.currentSl == null || d.currentSl.signum() <= 0) {
            d.status = "UNKNOWN";
            d.action = "REVIEW_ONLY / entry or SL missing.";
            d.reason = "insufficient entry/sl data for structural stop review.";
            return d;
        }

        StructuralStop structural = loadStructuralStop(pos);
        if (structural != null) {
            d.structuralSl = structural.price();
            d.swingLow = structural.swing();
            d.buffer = structural.buffer();
            d.atrAbs = structural.atrAbs();
            d.structureBars = structural.bars();
            d.structureSource = structural.source();
        }
        if (d.structuralSl == null || d.structuralSl.signum() <= 0) {
            d.status = "UNKNOWN";
            d.action = "REVIEW_ONLY / insufficient kline history for structural SL.";
            d.reason = "unable to compute recent swing-low structural stop.";
            return d;
        }
        d.policyMode = spotAntiWickPolicyMode(pos);
        d.disasterSl = disasterSlForEntry(d.entry);
        d.policySl = selectedPolicySl(d.structuralSl, d.disasterSl, d.policyMode);

        d.currentSlGapToStructuralPct = d.currentSl.subtract(d.structuralSl)
                .divide(d.entry, 8, RoundingMode.HALF_UP);
        d.currentSlGapToPolicyPct = d.policySl != null
                ? d.currentSl.subtract(d.policySl).divide(d.entry, 8, RoundingMode.HALF_UP)
                : null;
        d.structuralRiskPct = d.entry.subtract(d.structuralSl)
                .divide(d.entry, 8, RoundingMode.HALF_UP);
        d.policyRiskPct = d.policySl != null
                ? d.entry.subtract(d.policySl).divide(d.entry, 8, RoundingMode.HALF_UP)
                : d.structuralRiskPct;
        d.riskBudgetUsdt = overrideRiskBudget != null && overrideRiskBudget.signum() > 0
                ? overrideRiskBudget
                : maxLossUsdt(pos, d.currentSl);
        if (d.riskBudgetUsdt == null || d.riskBudgetUsdt.signum() <= 0) {
            d.riskBudgetUsdt = new BigDecimal("1.25");
        }
        d.recommendedAmountUsdt = recommendedAmountForStructuralRisk(d.riskBudgetUsdt, d.policyRiskPct);

        if (d.currentSl.compareTo(d.entry) >= 0) {
            d.status = "PROFIT_LOCKED";
            d.action = "HOLD_OCO / do not loosen SL; this is risk-reducing profit protection.";
            d.reason = "current SL is at or above entry, so structural lower stop is no longer relevant for this open position.";
            return d;
        }

        if (slAbovePolicyWithTolerance(d.entry, d.currentSl, d.policySl)) {
            d.status = "INITIAL_SL_TOO_TIGHT";
            d.action = d.recommendedAmountUsdt != null && d.recommendedAmountUsdt.compareTo(minTradeUsdt) >= 0
                    ? "NEXT_ENTRY_USE_BTC_SPOT_DISASTER_SL_AND_SIZE_DOWN"
                    : "SKIP_RISK_TOO_WIDE";
            d.reason = "current initial SL sits above BTC spot anti-wick policy SL; this is vulnerable to wick/liquidity sweep.";
            return d;
        }

        d.status = "OK";
        d.action = "HOLD / current initial SL is not above BTC spot anti-wick policy stop.";
        d.reason = "current SL is already at or below the selected anti-wick policy area.";
        return d;
    }

    private SpotWickAwareDecision evaluateSpotWickAware(BtLiveSignal pos, int confirmBars,
                                                        BigDecimal dcaNotionalUsdt) {
        SpotWickAwareDecision d = new SpotWickAwareDecision();
        d.entry = entryPrice(pos);
        d.current = latestPriceOrNull(pos.getSymbol());
        d.currentSl = pos.getSuggestedSl();
        d.confirmationBars = confirmBars;
        d.suggestedDcaNotionalUsdt = dcaNotionalUsdt;

        if (d.entry == null || d.entry.signum() <= 0 || d.currentSl == null || d.currentSl.signum() <= 0) {
            d.status = "UNKNOWN";
            d.action = "REVIEW_ONLY";
            d.reason = "entry/sl data unavailable.";
            return d;
        }

        StructuralStop structural = loadStructuralStop(pos);
        if (structural == null || structural.price() == null || structural.price().signum() <= 0) {
            d.status = "UNKNOWN";
            d.action = "REVIEW_ONLY";
            d.reason = "unable to compute structural/disaster SL.";
            return d;
        }
        d.structuralSl = structural.price();
        d.swingLow = structural.swing();
        d.buffer = structural.buffer();
        d.atrAbs = structural.atrAbs();
        d.structureBars = structural.bars();
        d.structureSource = structural.source();
        d.policyMode = spotAntiWickPolicyMode(pos);
        d.disasterSl = disasterSlForEntry(d.entry);
        d.policySl = selectedPolicySl(d.structuralSl, d.disasterSl, d.policyMode);
        d.currentSlGapToStructuralPct = d.currentSl.subtract(d.structuralSl)
                .divide(d.entry, 8, RoundingMode.HALF_UP);
        d.currentSlGapToPolicyPct = d.policySl != null
                ? d.currentSl.subtract(d.policySl).divide(d.entry, 8, RoundingMode.HALF_UP)
                : null;
        d.policyRiskPct = d.policySl != null
                ? d.entry.subtract(d.policySl).divide(d.entry, 8, RoundingMode.HALF_UP)
                : null;
        d.disasterRiskPct = d.policyRiskPct;

        List<MdKline> recentBars = loadBars(pos.getSymbol(), "1h",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(Math.max(6, confirmBars + 2)),
                LocalDateTime.now(ZoneOffset.UTC));
        if (recentBars.isEmpty()) {
            d.status = "HARD_OCO_WICK_VULNERABLE";
            d.action = "REVIEW_ONLY / kline confirmation unavailable.";
            d.reason = "hard OCO can still protect, but close-confirmation bars are unavailable.";
            d.partialTpAction = "N/A";
            d.partialTpReason = "kline unavailable";
            return d;
        }
        MdKline last = recentBars.get(recentBars.size() - 1);
        MdKline prev = recentBars.size() >= 2 ? recentBars.get(recentBars.size() - 2) : null;
        d.lastLow = last.getLowPrice();
        d.lastClose = last.getClosePrice();
        d.previousClose = prev != null ? prev.getClosePrice() : null;
        BigDecimal policySl = d.policySl != null ? d.policySl : d.structuralSl;
        d.wickDepthPct = d.lastLow != null && d.lastLow.compareTo(policySl) < 0
                ? policySl.subtract(d.lastLow).divide(policySl, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);

        TpStretchDecision tp = evaluateTpStretch(pos);
        d.partialTpAction = tp != null ? tp.action : "N/A";
        d.partialTpReason = tp != null ? tp.reason : "TP stretch data unavailable.";

        boolean confirmedBreakdown = lastClosesBelow(recentBars, policySl, confirmBars);
        boolean wickPiercedStructural = d.lastLow != null && d.lastLow.compareTo(policySl) <= 0;
        boolean recoveredAboveSwing = d.lastClose != null && d.swingLow != null
                && d.lastClose.compareTo(d.swingLow) >= 0;
        boolean hardSlInsideWickZone = slAbovePolicyWithTolerance(d.entry, d.currentSl, policySl);
        boolean priceAlreadyBelowStructural = d.current != null && d.current.compareTo(policySl) < 0;

        if (confirmedBreakdown || priceAlreadyBelowStructural) {
            d.status = "SOFT_EXIT_REVIEW";
            d.action = "REVIEW_SOFT_EXIT / do not DCA; confirm OCO and exit plan.";
            d.reason = "price closed below structural/disaster SL enough times or current price is below structure.";
            return d;
        }

        if (wickPiercedStructural && recoveredAboveSwing) {
            d.status = "DCA_CANDIDATE";
            d.action = "DCA_CANDIDATE_REVIEW_ONLY / small add only after exposure and macro checks.";
            d.reason = "latest bar pierced structural area but closed back above swing low; this is wick-recovery, not confirmed breakdown.";
            return d;
        }

        if (d.lastLow != null && d.lastLow.compareTo(d.currentSl) <= 0 && recoveredAboveSwing) {
            d.status = "WICK_ONLY";
            d.action = "HOLD / do not treat wick touch as confirmed exit.";
            d.reason = "latest low touched the hard-OCO zone but close recovered above swing low.";
            return d;
        }

        if (hardSlInsideWickZone) {
            d.status = "HARD_OCO_WICK_VULNERABLE";
            d.action = "NEXT_ENTRY_USE_BTC_SPOT_DISASTER_SL / do not keep initial hard SL inside wick zone.";
            d.reason = "current hard OCO SL is above BTC spot anti-wick policy SL, so it can be swept before structure actually breaks.";
            return d;
        }

        d.status = "HOLD";
        d.action = "HOLD / hard OCO already sits outside wick zone.";
        d.reason = "current SL is at or below BTC spot anti-wick policy SL and no close-confirmed breakdown is visible.";
        return d;
    }

    private boolean lastClosesBelow(List<MdKline> bars, BigDecimal level, int count) {
        if (bars == null || level == null || count <= 0 || bars.size() < count) return false;
        for (int i = bars.size() - count; i < bars.size(); i++) {
            BigDecimal close = bars.get(i).getClosePrice();
            if (close == null || close.compareTo(level) >= 0) return false;
        }
        return true;
    }

    private StructuralStop loadStructuralStop(BtLiveSignal pos) {
        LocalDateTime end = pos.getCreatedAt() != null ? pos.getCreatedAt() : pos.getBarOpenTime();
        if (end == null) end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusHours(72);
        List<MdKline> bars = loadBars(pos.getSymbol(), "1h", start, end);
        if (bars.size() < 6) {
            bars = loadBars(pos.getSymbol(), "1m", start, end);
        }
        if (bars.isEmpty()) return null;

        BigDecimal swingLow = null;
        String source = null;
        for (MdKline bar : bars) {
            BigDecimal low = bar.getLowPrice();
            if (low == null) continue;
            if (swingLow == null || low.compareTo(swingLow) < 0) {
                swingLow = low;
                source = bar.getSource();
            }
        }
        if (swingLow == null) return null;

        BigDecimal entry = entryPrice(pos);
        BigDecimal pctBuffer = entry != null
                ? entry.multiply(new BigDecimal("0.0015"))
                : swingLow.multiply(new BigDecimal("0.0015"));
        BigDecimal atrAbs = computeAtrAbs(bars, 14);
        BigDecimal atrBuffer = atrAbs != null ? atrAbs.multiply(new BigDecimal("0.30")) : BigDecimal.ZERO;
        BigDecimal buffer = pctBuffer.max(atrBuffer).setScale(2, RoundingMode.HALF_UP);
        return new StructuralStop(
                swingLow.subtract(buffer).setScale(2, RoundingMode.HALF_UP),
                swingLow.setScale(2, RoundingMode.HALF_UP),
                buffer,
                atrAbs != null ? atrAbs.setScale(2, RoundingMode.HALF_UP) : null,
                bars.size(),
                source,
                start,
                end);
    }

    private List<MdKline> loadBars(String symbol, String intervalCode, LocalDateTime start, LocalDateTime end) {
        try {
            List<MdKline> bars = mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                    symbol, intervalCode, "okx", start, end);
            if (!bars.isEmpty()) return bars;
            return mdKlineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                    symbol, intervalCode, start, end);
        } catch (Exception e) {
            log.debug("[PositionMcpTools] kline lookup failed symbol={} interval={}: {}",
                    symbol, intervalCode, e.getMessage());
            return List.of();
        }
    }

    private BigDecimal computeAtrAbs(List<MdKline> bars, int period) {
        if (bars == null || bars.size() < 2) return null;
        int start = Math.max(1, bars.size() - Math.max(2, period));
        List<BigDecimal> ranges = new ArrayList<>();
        for (int i = start; i < bars.size(); i++) {
            MdKline cur = bars.get(i);
            MdKline prev = bars.get(i - 1);
            if (cur.getHighPrice() == null || cur.getLowPrice() == null) continue;
            BigDecimal highLow = cur.getHighPrice().subtract(cur.getLowPrice()).abs();
            BigDecimal highClose = prev.getClosePrice() != null
                    ? cur.getHighPrice().subtract(prev.getClosePrice()).abs()
                    : BigDecimal.ZERO;
            BigDecimal lowClose = prev.getClosePrice() != null
                    ? cur.getLowPrice().subtract(prev.getClosePrice()).abs()
                    : BigDecimal.ZERO;
            ranges.add(highLow.max(highClose).max(lowClose));
        }
        if (ranges.isEmpty()) return null;
        BigDecimal sum = ranges.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(ranges.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal recommendedAmountForStructuralRisk(BigDecimal riskBudgetUsdt, BigDecimal riskPct) {
        if (riskBudgetUsdt == null || riskBudgetUsdt.signum() <= 0 || riskPct == null || riskPct.signum() <= 0) {
            return null;
        }
        return riskBudgetUsdt.divide(riskPct, 2, RoundingMode.DOWN);
    }

    private String spotAntiWickPolicyMode(BtLiveSignal pos) {
        if (pos != null && "BTCUSDT".equalsIgnoreCase(pos.getSymbol())
                && !"SHORT".equalsIgnoreCase(pos.getSide())) {
            return "ULTRA_LOW_DISASTER";
        }
        return "STRUCTURAL";
    }

    private BigDecimal disasterSlForEntry(BigDecimal entry) {
        if (entry == null || entry.signum() <= 0) return null;
        return entry.multiply(BigDecimal.ONE.subtract(BTC_SPOT_DISASTER_SL_PCT))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal selectedPolicySl(BigDecimal structuralSl, BigDecimal disasterSl, String policyMode) {
        if ("ULTRA_LOW_DISASTER".equalsIgnoreCase(policyMode) && disasterSl != null && disasterSl.signum() > 0) {
            return structuralSl != null && structuralSl.signum() > 0 ? structuralSl.min(disasterSl) : disasterSl;
        }
        return structuralSl;
    }

    private boolean slAbovePolicyWithTolerance(BigDecimal entry, BigDecimal currentSl, BigDecimal policySl) {
        if (entry == null || entry.signum() <= 0 || currentSl == null || policySl == null) return false;
        BigDecimal tolerance = entry.multiply(BTC_SPOT_POLICY_SL_TOLERANCE_PCT);
        return currentSl.compareTo(policySl.add(tolerance)) > 0;
    }

    private ReplayOutcome replayLongOco(BigDecimal entry, BigDecimal tp, BigDecimal sl, BigDecimal notional,
                                        List<MdKline> bars, LocalDateTime start, LocalDateTime end) {
        if (entry == null || tp == null || sl == null || notional == null || bars == null || bars.isEmpty()) {
            return null;
        }
        MdKline last = null;
        for (MdKline bar : bars) {
            if (bar == null || bar.getOpenTime() == null) continue;
            if (bar.getOpenTime().isBefore(start) || bar.getOpenTime().isAfter(end)) continue;
            last = bar;
            boolean hitSl = bar.getLowPrice() != null && bar.getLowPrice().compareTo(sl) <= 0;
            boolean hitTp = bar.getHighPrice() != null && bar.getHighPrice().compareTo(tp) >= 0;
            if (hitSl || hitTp) {
                // Conservative for hourly bars: if both touch inside the same bar, count SL first.
                boolean ambiguous = hitSl && hitTp;
                BigDecimal exit = hitSl ? sl : tp;
                String reason = hitSl ? "SL" : "TP";
                return new ReplayOutcome(reason, exit, bar.getOpenTime(),
                        replayPnl(entry, exit, notional), ambiguous);
            }
        }
        if (last == null || last.getClosePrice() == null) return null;
        return new ReplayOutcome("END", last.getClosePrice(), last.getOpenTime(),
                replayPnl(entry, last.getClosePrice(), notional), false);
    }

    private BigDecimal replayPnl(BigDecimal entry, BigDecimal exit, BigDecimal notional) {
        if (entry == null || exit == null || notional == null || entry.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal qty = notional.divide(entry, 12, RoundingMode.HALF_UP);
        BigDecimal gross = exit.subtract(entry).multiply(qty);
        BigDecimal entryFee = notional.multiply(new BigDecimal("0.001"));
        BigDecimal exitFee = exit.multiply(qty).multiply(new BigDecimal("0.001"));
        return gross.subtract(entryFee).subtract(exitFee).setScale(8, RoundingMode.HALF_UP);
    }

    private ForwardRecovery loadForwardRecovery(BtLiveSignal pos, int hoursAhead) {
        if (pos.getExitTime() == null) return null;
        LocalDateTime start = pos.getExitTime();
        LocalDateTime end = start.plusHours(hoursAhead);
        List<MdKline> bars = loadBars(pos.getSymbol(), "1h", start, end);
        if (bars.isEmpty()) {
            bars = loadBars(pos.getSymbol(), "1m", start, end);
        }
        if (bars.isEmpty()) return null;

        BigDecimal maxHigh = null;
        BigDecimal minLow = null;
        LocalDateTime maxHighTime = null;
        for (MdKline bar : bars) {
            if (bar.getHighPrice() != null && (maxHigh == null || bar.getHighPrice().compareTo(maxHigh) > 0)) {
                maxHigh = bar.getHighPrice();
                maxHighTime = bar.getOpenTime();
            }
            if (bar.getLowPrice() != null && (minLow == null || bar.getLowPrice().compareTo(minLow) < 0)) {
                minLow = bar.getLowPrice();
            }
        }

        BigDecimal entry = entryPrice(pos);
        BigDecimal exit = pos.getExitPrice();
        BigDecimal recoveryToEntry = null;
        if (entry != null && entry.signum() > 0 && maxHigh != null) {
            recoveryToEntry = maxHigh.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP);
        }
        BigDecimal recoveryFromExit = null;
        if (exit != null && exit.signum() > 0 && maxHigh != null) {
            recoveryFromExit = maxHigh.subtract(exit).divide(exit, 8, RoundingMode.HALF_UP);
        }
        return new ForwardRecovery(maxHigh, minLow, maxHighTime, recoveryToEntry, recoveryFromExit, bars.size());
    }

    private String classifyClosedSl(BtLiveSignal pos, ForwardRecovery r) {
        if (r == null || r.maxHigh() == null) return "UNKNOWN";
        BigDecimal entry = entryPrice(pos);
        BigDecimal exit = pos.getExitPrice();
        if (entry == null || exit == null || entry.signum() <= 0 || exit.signum() <= 0) return "UNKNOWN";
        boolean recoveredToEntry = r.maxHigh().compareTo(entry.multiply(new BigDecimal("0.998"))) >= 0;
        boolean reboundedStrongly = r.recoveryFromExitPct() != null
                && r.recoveryFromExitPct().compareTo(new BigDecimal("0.015")) >= 0;
        if (recoveredToEntry || reboundedStrongly) return "STOP_SWEPT";
        return "VALID_STOP";
    }

    private String closedSlReason(String status) {
        return switch (status) {
            case "STOP_SWEPT" -> "SL was hit, then price recovered enough within 24h; entry may be OK but SL placement was too sweep-prone.";
            case "VALID_STOP" -> "Price did not recover enough after SL; treat the stop as valid protection, not a placement bug.";
            default -> "Insufficient forward data to classify.";
        };
    }

    private TpStretchDecision evaluateTpStretch(BtLiveSignal pos) {
        TpStretchDecision d = new TpStretchDecision();
        d.entry = entryPrice(pos);
        d.current = latestPriceOrNull(pos.getSymbol());
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal tp = pos.getSuggestedTp();
        if (d.entry == null || d.entry.signum() <= 0 || d.current == null || d.current.signum() <= 0
                || tp == null || tp.signum() <= 0) {
            d.status = "OK";
            d.action = "HOLD";
            d.reason = "insufficient entry/current/tp data for TP-stretch check.";
            return d;
        }

        BigDecimal tpDistance = isLong ? tp.subtract(d.entry) : d.entry.subtract(tp);
        if (tpDistance.signum() <= 0) {
            d.status = "OK";
            d.action = "HOLD";
            d.reason = "invalid TP direction for position side.";
            return d;
        }

        PositionExtreme extreme = loadInPositionExtreme(pos, isLong);
        d.extreme = extreme != null ? extreme.price() : d.current;
        d.extremeTime = extreme != null ? extreme.time() : null;
        d.extremeSource = extreme != null ? extreme.source() : "current_price";
        d.extremeBars = extreme != null ? extreme.bars() : 0;

        boolean tpAlreadyTouched = isLong ? d.extreme.compareTo(tp) >= 0 : d.extreme.compareTo(tp) <= 0;
        BigDecimal travelled = isLong ? d.extreme.subtract(d.entry) : d.entry.subtract(d.extreme);
        if (travelled.signum() < 0) travelled = BigDecimal.ZERO;
        d.progress = travelled.divide(tpDistance, 8, RoundingMode.HALF_UP);
        d.pullback = isLong
                ? d.extreme.subtract(d.current).divide(d.extreme, 8, RoundingMode.HALF_UP)
                : d.current.subtract(d.extreme).divide(d.extreme, 8, RoundingMode.HALF_UP);
        if (d.pullback.signum() < 0) d.pullback = BigDecimal.ZERO;
        d.gapToTp = tp.subtract(d.extreme).abs().divide(tp, 8, RoundingMode.HALF_UP);
        d.tpCapBuffer = tpCapBuffer(pos, d.extreme);
        d.recentExtremeTpCap = recentExtremeTpCap(pos, d.extreme, d.tpCapBuffer, isLong);
        d.tpReductionToCap = tpReductionToCap(tp, d.recentExtremeTpCap, isLong);

        BigDecimal progressWarn = new BigDecimal("0.70");
        BigDecimal progressStretch = new BigDecimal("0.75");
        BigDecimal pullbackWarn = new BigDecimal("0.0030");
        BigDecimal pullbackStretch = new BigDecimal("0.0050");

        if (tpAlreadyTouched) {
            d.status = "TARGET_TOUCHED_BUT_RECORD_OPEN";
            d.action = "RECONCILIATION_REQUIRED / query external parent status, child statuses, and fill history before reporting normal HOLD.";
            d.reason = "anomalyType=TARGET_TOUCHED_BUT_RECORD_OPEN severity=HIGH explanationBucket=UNKNOWN_NEEDS_REVIEW; observed in-position extreme crossed configured TP while local record remains OPEN.";
            return d;
        }

        if (d.progress.compareTo(progressStretch) >= 0 && d.pullback.compareTo(pullbackStretch) >= 0) {
            d.status = "TP_STRETCHED";
            d.action = "REVIEW_ONLY / compare recent-high TP cap and preview risk-reducing SL; do not modify OCO without approval.";
            d.reason = "recent extreme completed >=75% of TP distance, failed to hit TP, then pulled back >=0.50%; TP should be reviewed against recentExtremeTpCap.";
            d.preview = tpStretchSlPreview(pos, isLong);
            return d;
        }

        if (d.progress.compareTo(progressWarn) >= 0 || d.pullback.compareTo(pullbackWarn) >= 0) {
            d.status = "WATCH";
            d.action = "WATCH / keep OCO; re-check if pullback deepens or P(TP) drops.";
            d.reason = "TP is not proven too high, but price action is close enough to track.";
            d.preview = tpStretchSlPreview(pos, isLong);
            return d;
        }

        d.status = "OK";
        d.action = "HOLD";
        d.reason = "recent extreme did not show a TP-stretch pattern.";
        return d;
    }

    private PositionExtreme loadInPositionExtreme(BtLiveSignal pos, boolean isLong) {
        LocalDateTime start = pos.getCreatedAt() != null ? pos.getCreatedAt() : pos.getBarOpenTime();
        if (start == null) start = LocalDateTime.now(ZoneOffset.UTC).minusHours(72);
        LocalDateTime currentOcoEffectiveAt = currentOcoEffectiveAt(pos);
        if (currentOcoEffectiveAt != null && currentOcoEffectiveAt.isAfter(start)) {
            start = currentOcoEffectiveAt;
        }
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        List<MdKline> bars;
        try {
            bars = mdKlineRepository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                    pos.getSymbol(), "1m", "okx", start, end);
            if (bars.isEmpty()) {
                bars = mdKlineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                        pos.getSymbol(), "1m", start, end);
            }
        } catch (Exception e) {
            log.debug("[PositionMcpTools] TP stretch kline lookup failed for pos {}: {}",
                    pos.getId(), e.getMessage());
            bars = List.of();
        }

        BigDecimal best = null;
        LocalDateTime bestTime = null;
        String bestSource = null;
        for (MdKline bar : bars) {
            BigDecimal candidate = isLong ? bar.getHighPrice() : bar.getLowPrice();
            if (candidate == null) continue;
            boolean better = best == null || (isLong ? candidate.compareTo(best) > 0 : candidate.compareTo(best) < 0);
            if (better) {
                best = candidate;
                bestTime = bar.getOpenTime();
                bestSource = bar.getSource();
            }
        }
        if (best == null && pos.getTrailingHigh() != null) {
            return new PositionExtreme(pos.getTrailingHigh(), pos.getTrailingLastTransitionAt(), "trailing_state", 0);
        }
        return best == null ? null : new PositionExtreme(best, bestTime, bestSource, bars.size());
    }

    private LocalDateTime currentOcoEffectiveAt(BtLiveSignal pos) {
        if (pos == null || pos.getOcoOrderListId() == null || pos.getSymbol() == null) {
            return null;
        }
        LocalDateTime localEffectiveAt = currentOcoEffectiveAtFromAudit(pos);
        if (localEffectiveAt != null) {
            return localEffectiveAt;
        }
        try {
            boolean isShort = "SHORT".equalsIgnoreCase(pos.getSide());
            JsonNode algo = isShort
                    ? okxTradingService.getSwapAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId())
                    : okxTradingService.getAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId());
            if (algo == null || algo.isNull() || algo.isMissingNode()) {
                return null;
            }
            String cTime = algo.path("cTime").asText("");
            if (cTime == null || cTime.isBlank()) {
                return null;
            }
            return Instant.ofEpochMilli(Long.parseLong(cTime))
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception e) {
            log.debug("[PositionMcpTools] current OCO effective time lookup failed posId={} algoId={}: {}",
                    pos.getId(), pos.getOcoOrderListId(), e.getMessage());
            return null;
        }
    }

    private LocalDateTime currentOcoEffectiveAtFromAudit(BtLiveSignal pos) {
        if (ocoAdjustmentAuditRepository == null || pos == null
                || pos.getId() == null || pos.getOcoOrderListId() == null) {
            return null;
        }
        try {
            return ocoAdjustmentAuditRepository
                    .findFirstByLiveSignalIdAndNewOcoOrderListIdOrderByEffectiveAtDesc(
                            pos.getId(), pos.getOcoOrderListId())
                    .map(com.agora.model.BtOcoAdjustmentAudit::getEffectiveAt)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[PositionMcpTools] local OCO audit lookup failed posId={} algoId={}: {}",
                    pos.getId(), pos.getOcoOrderListId(), e.getMessage());
            return null;
        }
    }

    private String tpStretchSlPreview(BtLiveSignal pos, boolean isLong) {
        BigDecimal entry = entryPrice(pos);
        if (entry == null || entry.signum() <= 0) return "N/A(entry unavailable)";
        BigDecimal candidate = protectiveStop(pos.getSuggestedSl(), feeAdjustedBreakeven(entry, isLong), isLong);
        String validation = validateRiskReducingSl(pos, candidate, latestPriceOrNull(pos.getSymbol()));
        if (validation != null) {
            return "blocked=" + validation + " candidateSl=" + fmt(candidate);
        }
        BigDecimal oldLoss = maxLossUsdt(pos, pos.getSuggestedSl());
        BigDecimal newLoss = maxLossUsdt(pos, candidate);
        String reduction = oldLoss != null && newLoss != null
                ? oldLoss.subtract(newLoss).stripTrailingZeros().toPlainString() + " USDT"
                : "N/A";
        return "candidateSl=" + fmt(candidate)
                + " tpUnchanged=" + fmt(pos.getSuggestedTp())
                + " riskReduced=" + reduction
                + " tokenIssued=false";
    }

    private BigDecimal tpCapBuffer(BtLiveSignal pos, BigDecimal extreme) {
        if (extreme == null || extreme.signum() <= 0) return null;
        BigDecimal pctBuffer = extreme.multiply(new BigDecimal("0.0015"));
        BigDecimal atr = pos.getTrailingAtr();
        if (atr == null || atr.signum() <= 0) {
            return pctBuffer.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal atrBuffer = extreme.multiply(atr).multiply(new BigDecimal("0.30"));
        return pctBuffer.max(atrBuffer).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal recentExtremeTpCap(BtLiveSignal pos, BigDecimal extreme, BigDecimal buffer, boolean isLong) {
        BigDecimal entry = entryPrice(pos);
        if (entry == null || extreme == null || buffer == null) return null;
        BigDecimal cap = isLong ? extreme.subtract(buffer) : extreme.add(buffer);
        if (isLong && cap.compareTo(entry) <= 0) return null;
        if (!isLong && cap.compareTo(entry) >= 0) return null;
        return cap.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal tpReductionToCap(BigDecimal tp, BigDecimal cap, boolean isLong) {
        if (tp == null || cap == null) return null;
        BigDecimal reduction = isLong ? tp.subtract(cap) : cap.subtract(tp);
        if (reduction.signum() <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return reduction.setScale(2, RoundingMode.HALF_UP);
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.stripTrailingZeros().toPlainString());
        }
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String safePlain(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private TrailingReplayResult replayTrailing(BtLiveSignal pos, List<MdKline> bars,
                                                BigDecimal entry, BigDecimal atr, boolean isLong) {
        String state = "ENTERED";
        BigDecimal extreme = null;
        BigDecimal stop = pos.getSuggestedSl();
        LocalDateTime breakevenAt = null;
        LocalDateTime trailingAt = null;
        boolean sameBarTransition = false;
        BigDecimal breakevenTrigger = trigger(entry, atr, new BigDecimal("0.5"), isLong);
        BigDecimal trailingTrigger = trigger(entry, atr, BigDecimal.ONE, isLong);

        for (MdKline bar : bars) {
            BigDecimal high = bar.getHighPrice();
            BigDecimal low = bar.getLowPrice();
            if (high == null || low == null) continue;

            if (extreme == null) {
                extreme = isLong ? high : low;
            } else {
                extreme = isLong ? extreme.max(high) : extreme.min(low);
            }

            boolean touchedBreakeven = isLong
                    ? high.compareTo(breakevenTrigger) >= 0
                    : low.compareTo(breakevenTrigger) <= 0;
            boolean touchedTrailing = isLong
                    ? high.compareTo(trailingTrigger) >= 0
                    : low.compareTo(trailingTrigger) <= 0;

            if ("ENTERED".equals(state) && touchedBreakeven) {
                state = "BREAKEVEN_LOCKED";
                breakevenAt = bar.getOpenTime();
                stop = protectiveStop(stop, feeAdjustedBreakeven(entry, isLong), isLong);
            }
            if ("BREAKEVEN_LOCKED".equals(state) && touchedTrailing) {
                state = "TRAILING";
                trailingAt = bar.getOpenTime();
                sameBarTransition = breakevenAt != null && breakevenAt.equals(trailingAt);
                stop = protectiveStop(stop, replayTrailingStop(extreme, atr, isLong), isLong);
            } else if ("TRAILING".equals(state)) {
                stop = protectiveStop(stop, replayTrailingStop(extreme, atr, isLong), isLong);
            }
        }
        return new TrailingReplayResult(state, extreme, stop, breakevenAt, trailingAt, bars.size(), sameBarTransition);
    }

    private BigDecimal replayTrailingStop(BigDecimal extreme, BigDecimal atr, boolean isLong) {
        if (extreme == null || atr == null) return null;
        BigDecimal trailDistance = extreme.multiply(atr);
        return isLong ? extreme.subtract(trailDistance) : extreme.add(trailDistance);
    }

    private String trailingReplayCoverageGapReason(BtLiveSignal pos, TrailingReplayResult replay,
                                                   boolean isLong, boolean advanced) {
        if (!advanced) return "none";
        BigDecimal persistedExtreme = pos.getTrailingHigh();
        BigDecimal replayExtreme = replay.extreme();
        if (persistedExtreme == null || replayExtreme == null) {
            return "persisted_dry_run_extreme_missing";
        }
        boolean persistedCoversReplay = isLong
                ? persistedExtreme.compareTo(replayExtreme) >= 0
                : persistedExtreme.compareTo(replayExtreme) <= 0;
        if (!persistedCoversReplay) {
            return "historical_replay_extreme_not_covered_by_persisted_dry_run_state";
        }
        return "possible_intrabar_polling_gap";
    }

    private String trailingReplayGate(TrailingReplayResult replay, String persistedState,
                                      BigDecimal current, boolean stopCrossesCurrent,
                                      boolean advanced, String coverageGapReason) {
        if (replay == null) return "BLOCKED reason=replay_unavailable";
        if (replay.bars() <= 0) return "BLOCKED reason=no_replay_bars";
        if ("ENTERED".equalsIgnoreCase(replay.state())) {
            return "WAIT_NO_TRIGGER_SAMPLE";
        }
        if (replay.theoreticalStop() == null || current == null || current.signum() <= 0) {
            return "BLOCKED reason=stop_or_current_unavailable";
        }
        if (stopCrossesCurrent) {
            return "BLOCKED reason=replay_stop_crosses_current_price";
        }
        if (advanced) {
            return "REVIEW reason=replay_state_ahead_of_persisted_dry_run_state persistedState="
                    + nullSafe(persistedState)
                    + " coverageGapReason=" + nullSafe(coverageGapReason);
        }
        return "REVIEW_READY_READ_ONLY_REPLAY_SAMPLE";
    }

    private String trailingLivePromotionGate(String state,
                                             BigDecimal current,
                                             BigDecimal breakevenTrigger,
                                             BigDecimal trailingTrigger,
                                             BigDecimal theoreticalStop,
                                             boolean currentAboveBreakeven,
                                             boolean currentAboveTrailing,
                                             boolean everReachedBreakeven,
                                             boolean everReachedTrailing,
                                             java.time.LocalDateTime lastTransitionAt,
                                             boolean isLong) {
        java.util.List<String> blockers = new java.util.ArrayList<>();
        java.util.List<String> warnings = new java.util.ArrayList<>();

        if (!trailingStopDryRun) {
            warnings.add("global_dry_run_already_false");
        }
        if (theoreticalStop == null) {
            blockers.add("theoretical_stop_unavailable");
        } else if (current == null || current.signum() <= 0) {
            blockers.add("current_price_unavailable");
        } else if (stopCrossesCurrentPrice(theoreticalStop, current, isLong)) {
            blockers.add("theoretical_stop_crosses_current_price");
        }

        if (hasReachedState(state, "BREAKEVEN_LOCKED") && !currentAboveBreakeven) {
            warnings.add("historical_breakeven_only_current_below_trigger");
        }
        if (hasReachedState(state, "TRAILING") && !currentAboveTrailing) {
            blockers.add("historical_trailing_only_current_below_trigger");
        }
        if (!everReachedBreakeven) {
            blockers.add("missing_breakeven_transition_sample");
        }
        if (hasReachedState(state, "TRAILING") && !everReachedTrailing) {
            blockers.add("trailing_state_without_historical_trigger_evidence");
        }
        if (hasReachedState(state, "BREAKEVEN_LOCKED") && lastTransitionAt == null) {
            warnings.add("last_transition_at_not_persisted");
        }
        if (breakevenTrigger == null || trailingTrigger == null) {
            blockers.add("trigger_unavailable");
        }

        if (!blockers.isEmpty()) {
            return "BLOCKED reasons=" + String.join(",", blockers)
                    + (warnings.isEmpty() ? "" : " warnings=" + String.join(",", warnings));
        }
        if (!warnings.isEmpty()) {
            return "REVIEW warnings=" + String.join(",", warnings);
        }
        return "ELIGIBLE_READ_ONLY_CHECK_PASSED";
    }

    private boolean stopCrossesCurrentPrice(BigDecimal stop, BigDecimal current, boolean isLong) {
        if (stop == null || current == null) return true;
        return isLong
                ? stop.compareTo(current) >= 0
                : stop.compareTo(current) <= 0;
    }

    private String fmtPct(BigDecimal value) {
        if (value == null) return "N/A";
        return value.multiply(new BigDecimal("100"))
                .setScale(3, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private String fmtTime(java.time.LocalDateTime value) {
        if (value == null) return "not_persisted";
        return value.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.of("Asia/Taipei"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'Taipei'"));
    }

    private boolean strategyTrailingEnabled(Long strategyId) {
        if (strategyId == null) return false;
        return strategyRepository.findById(strategyId)
                .map(s -> configFlag(s.getConfigJson(), "trailingStopEnabled"))
                .orElse(false);
    }

    private boolean strategyCoversSymbol(BtStrategy strategy, String symbol) {
        if (strategy == null || symbol == null || symbol.isBlank()) return false;
        String symbols = strategy.getSymbols();
        if (symbols == null || symbols.isBlank()) {
            return "BTCUSDT".equalsIgnoreCase(symbol);
        }
        for (String part : symbols.split(",")) {
            if (symbol.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    private JsonNode readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(configJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean configBoolean(JsonNode node, String key, boolean def) {
        if (node == null || key == null) return def;
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return def;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asInt() != 0;
        if (value.isTextual()) return Boolean.parseBoolean(value.asText());
        return def;
    }

    private String configText(JsonNode node, String key, String def) {
        if (node == null || key == null) return def;
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return def;
        return value.asText(def);
    }

    private double configDouble(JsonNode node, String key, double def) {
        if (node == null || key == null) return def;
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return def;
        if (value.isNumber()) return value.asDouble(def);
        try {
            return Double.parseDouble(value.asText());
        } catch (Exception ignored) {
            return def;
        }
    }

    private boolean configFlag(String configJson, String key) {
        if (configJson == null || configJson.isBlank()) return false;
        try {
            JsonNode flag = objectMapper.readTree(configJson).path(key);
            return flag.isBoolean() ? flag.asBoolean()
                    : flag.isNumber() ? flag.asInt() != 0
                    : flag.isTextual() && "true".equalsIgnoreCase(flag.asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
