package com.agora.mcp;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.mcp.auth.McpApiKeyFilter;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.EventRiskLevelEngine;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only evidence bundle for the external Sirin trading guardian (#448).
 *
 * <p>The backend must expose enough evidence for Sirin to evaluate vacation-risk
 * rules without giving this tool any write behavior. Action execution remains
 * outside this class and must pass separate authorization gates.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianMcpTools {

    private static final BigDecimal DEFAULT_DAILY_LOSS_USDT = new BigDecimal("5");
    private static final BigDecimal DEFAULT_AGED_LOSS_PCT = new BigDecimal("5");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final OkxTradingService okxTradingService;
    private final PositionMcpTools positionMcpTools;
    private final MarketDataMcpTools marketDataMcpTools;
    private final EventRiskLevelEngine eventRiskLevelEngine;
    private final ObjectMapper objectMapper;

    @Value("${mcp.guardian-key:}")
    private String guardianKey;

    @Value("${mcp.guardian-live-actions-enabled:false}")
    private boolean guardianLiveActionsEnabled;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "#448 Sirin Trading Guardian read-only snapshot. Evaluates vacation guardian rules "
            + "R1/R2/R3/R5 and returns JSON evidence plus proposed risk-reducing actions. "
            + "Does not pause/disable strategies, does not modify OCO, and does not send TG. "
            + "params: lookbackDays=1..14(default 3), symbol=BTCUSDT, includeTrailing=true")
    public String getGuardianSnapshot(
            @ToolParam(required = false, description = "Closed-trade lookback days, 1..14, default 3") Integer lookbackDays,
            @ToolParam(required = false, description = "Symbol filter for open positions, default BTCUSDT") String symbol,
            @ToolParam(required = false, description = "Include getTrailingStopStatus evidence, default true") Boolean includeTrailing) {
        int days = clamp(lookbackDays, 3, 1, 14);
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        boolean trailing = includeTrailing == null || includeTrailing;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusDays(days);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("issue", 448);
        root.put("tool", "getGuardianSnapshot");
        root.put("generatedAtUtc", now.toString());
        root.put("lookbackDays", days);
        root.put("symbol", sym);
        root.put("writeMode", false);
        root.put("liveActionsExecuted", false);
        root.put("notes", "Read-only evidence only. Sirin may propose actions, but this tool never executes them.");

        ArrayNode rules = root.putArray("rules");
        ArrayNode actions = root.putArray("proposedActions");
        addR1ConsecutiveLosses(rules, actions, since);
        addR2AgedPositionRisk(rules, actions, sym, now);
        addR3DailyLossGuard(rules, actions, now.minusDays(1));
        addR4BackendHealth(rules);
        if (trailing) {
            addR5TrailingDryRun(rules);
        }
        root.put("proposedActionCount", actions.size());

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[GuardianSnapshot] serialize failed: {}", e.getMessage(), e);
            return "{\"error\":\"serialize_failed\",\"message\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.GOVERNANCE, Category.REPORTING})
    @Tool(description = "#448 Sirin Trading Guardian auth policy diagnostic. Read-only. "
            + "Reports whether MCP_GUARDIAN_KEY is configured, whether live actions are enabled, "
            + "and the guardian allow/deny tool sets without revealing secrets.")
    public String getGuardianAuthPolicy() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("issue", 448);
        root.put("tool", "getGuardianAuthPolicy");
        root.put("guardianKeyConfigured", guardianKey != null && !guardianKey.isBlank());
        root.put("guardianLiveActionsEnabled", guardianLiveActionsEnabled);
        root.put("defaultMode", guardianLiveActionsEnabled ? "RISK_REDUCING_LIVE_ALLOWED" : "READ_ONLY");
        root.put("secretExposed", false);
        root.put("notes", "This diagnostic never returns MCP_GUARDIAN_KEY. Live actions still require explicit environment opt-in.");
        putSortedArray(root, "readOnlyAllowedTools", McpApiKeyFilter.GUARDIAN_READ_ONLY_TOOLS);
        putSortedArray(root, "riskReducingLiveTools", McpApiKeyFilter.GUARDIAN_RISK_REDUCING_LIVE_TOOLS);
        putSortedArray(root, "strictDenyTools", McpApiKeyFilter.GUARDIAN_STRICT_DENY_TOOLS);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[GuardianAuthPolicy] serialize failed: {}", e.getMessage(), e);
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Read-only position defense status for current autonomous BTCUSDT exposure. "
            + "Connects event-risk, open-position PnL, OCO health, reduction preview, important-notification severity, "
            + "and short-side evaluation. Does not place orders, reduce positions, modify OCO, enable short, or send TG. "
            + "params: symbol=BTCUSDT")
    public String getPositionDefenseStatus(
            @ToolParam(required = false, description = "Symbol filter, default BTCUSDT") String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getPositionDefenseStatus");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram behavior changed");
        root.put("generatedAtUtc", now.toString());
        root.put("symbol", sym);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("shortLiveEnabled", false);

        EventRiskLevelEngine.Snapshot risk = eventRiskLevelEngine.evaluate(sym);
        ObjectNode riskNode = root.putObject("eventRisk");
        riskNode.put("level", risk.level().name());
        riskNode.put("score", risk.score());
        ArrayNode reasons = riskNode.putArray("reasons");
        risk.reasons().forEach(reasons::add);
        riskNode.putPOJO("inputs", risk.inputs());

        ArrayNode positionsNode = root.putArray("positions");
        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .toList();
        int criticalCount = 0;
        int reduceCount = 0;
        for (BtLiveSignal p : positions) {
            PositionDefense defense = assessPositionDefense(p, risk, now);
            ObjectNode item = positionsNode.addObject();
            item.put("positionId", p.getId());
            item.put("strategyId", p.getStrategyId());
            item.put("symbol", p.getSymbol());
            item.put("side", p.getSide() == null ? "LONG" : p.getSide());
            item.put("ageDays", defense.ageDays());
            putDecimal(item, "entry", defense.entry());
            putDecimal(item, "current", defense.current());
            putDecimal(item, "qty", p.getTradedQty());
            putDecimal(item, "unrealizedPnlUsdt", defense.unrealizedPnl());
            putDecimal(item, "paperLossPct", defense.paperLossPct());
            item.put("ocoProtected", p.getOcoOrderListId() != null);
            item.put("protectionMode", BtcBasePositionStatePolicy.isIntentionalNoOco(p)
                    ? "BTC_BASE_MANAGED_NO_OCO" : p.getOcoOrderListId() != null ? "OCO" : "UNPROTECTED");
            item.put("defenseMode", defense.mode());
            item.put("recommendedAction", defense.recommendedAction());
            item.put("suggestedReducePct", defense.suggestedReducePct());
            item.put("suggestedOcoAction", defense.suggestedOcoAction());
            item.put("riskReducingOnly", true);
            item.put("wouldPlaceOrder", false);
            item.put("wouldModifyOco", false);
            ArrayNode blockers = item.putArray("executionBlockers");
            blockers.add("READ_ONLY_PREVIEW");
            blockers.add("EXPLICIT_OPERATOR_APPROVAL_REQUIRED_FOR_REDUCE_OR_OCO_CHANGE");
            if ("CRITICAL".equals(defense.notificationLevel())) criticalCount++;
            if (defense.suggestedReducePct() > 0) reduceCount++;
        }

        ObjectNode reduce = root.putObject("reductionPreview");
        reduce.put("positionsNeedingDefense", reduceCount);
        reduce.put("recommended", reduceCount > 0);
        reduce.put("executionMode", "OPERATOR_APPROVAL_REQUIRED");
        reduce.put("reason", reduceCount > 0
                ? "Open LONG exposure is losing while event risk is elevated; reduce/tighten should be reviewed."
                : "No position currently crosses the defense threshold.");
        reduce.put("orderSent", false);

        ObjectNode notification = root.putObject("importantNotification");
        notification.put("recommended", criticalCount > 0);
        notification.put("level", criticalCount > 0 ? "CRITICAL" : "INFO");
        notification.put("reason", criticalCount > 0
                ? "R3/downside risk plus open losing LONG exposure requires operator attention."
                : "No critical position-defense notification needed.");
        notification.put("telegramSent", false);

        ObjectNode shortEval = root.putObject("shortEvaluation");
        shortEval.put("mode", "SHADOW_ONLY");
        shortEval.put("shortBias", risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3) ? "BEARISH_RISK_ON" : "NEUTRAL_OR_WEAK");
        shortEval.put("canOpenLiveShort", false);
        shortEval.put("reason", "Short-side assessment is allowed only as diagnostics/shadow evaluation in this phase.");
        ArrayNode shortBlockers = shortEval.putArray("liveShortBlockers");
        shortBlockers.add("SHORT_LIVE_EXECUTION_NOT_ENABLED");
        shortBlockers.add("EXISTING_LONG_DEFENSE_COMES_FIRST");
        shortBlockers.add("NO_SHORT_OCO_EXECUTION_PATH_APPROVED");
        shortBlockers.add("REQUIRES_SEPARATE_BACKTEST_AND_SHADOW_EVIDENCE");

        root.put("overallDefenseMode", criticalCount > 0
                ? "RISK_REDUCING_REVIEW_REQUIRED"
                : reduceCount > 0 ? "DEFENSE_REVIEW" : "WATCH");
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[PositionDefense] serialize failed: {}", e.getMessage(), e);
            return "{\"error\":\"serialize_failed\",\"message\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "#517 Phase B read-only position defense preview. Produces an operator-review defense plan "
            + "for current BTCUSDT exposure, including event risk, OCO health, max-loss, risk-reducing eligibility, "
            + "and per-position recommended actions. Does not place orders, modify OCO, enable shorts, alter strategy/grid/fund/Earn, "
            + "or send Telegram. params: symbol=BTCUSDT")
    public String previewPositionDefensePlan(
            @ToolParam(required = false, description = "Symbol filter, default BTCUSDT") String symbol) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewPositionDefensePlan");
        root.put("issue", 517);
        root.put("phase", "POSITION_DEFENSE_PHASE_B_PREVIEW");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram behavior changed");
        root.put("generatedAtUtc", now.toString());
        root.put("symbol", sym);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("strategyModified", false);
        root.put("gridModified", false);
        root.put("fundMoved", false);
        root.put("earnModified", false);
        root.put("telegramSent", false);
        root.put("shortLiveEnabled", false);
        root.put("approvalRequired", true);

        EventRiskLevelEngine.Snapshot risk = eventRiskLevelEngine.evaluate(sym);
        ObjectNode riskNode = root.putObject("eventRisk");
        riskNode.put("level", risk.level().name());
        riskNode.put("score", risk.score());
        ArrayNode reasons = riskNode.putArray("reasons");
        risk.reasons().forEach(reasons::add);
        riskNode.putPOJO("inputs", risk.inputs());

        String ocoHealthRaw = safeOcoHealth();
        ObjectNode ocoHealth = root.putObject("ocoHealth");
        ocoHealth.put("status", ocoHealthStatus(ocoHealthRaw));
        ocoHealth.put("healthy", isOcoHealthHealthy(ocoHealthRaw));
        ocoHealth.put("summary", abbreviate(ocoHealthRaw, 1200));

        List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                .toList();

        ArrayNode positionsNode = root.putArray("positions");
        ArrayNode actions = root.putArray("recommendedActions");
        int positionsNeedingDefense = 0;
        int criticalCount = 0;
        int riskReducingEligibleCount = 0;
        BigDecimal totalUnrealized = BigDecimal.ZERO;
        BigDecimal totalCurrentMaxLoss = BigDecimal.ZERO;
        boolean totalUnrealizedKnown = true;
        boolean totalMaxLossKnown = true;

        for (BtLiveSignal p : positions) {
            PositionDefense defense = assessPositionDefense(p, risk, now);
            RiskReductionGate gate = assessBreakevenRiskReduction(p, defense.current());
            boolean needsDefense = !"WATCH".equals(defense.mode())
                    && !"BTC_BASE_MANAGER_REVIEW".equals(defense.mode());
            String actionType = phaseBActionType(defense);
            if (needsDefense) positionsNeedingDefense++;
            if ("CRITICAL".equals(defense.notificationLevel())) criticalCount++;
            if (gate.allowed()) riskReducingEligibleCount++;
            if (defense.unrealizedPnl() != null) {
                totalUnrealized = totalUnrealized.add(defense.unrealizedPnl());
            } else {
                totalUnrealizedKnown = false;
            }
            if (gate.currentMaxLossUsdt() != null) {
                totalCurrentMaxLoss = totalCurrentMaxLoss.add(gate.currentMaxLossUsdt());
            } else {
                totalMaxLossKnown = false;
            }

            ObjectNode item = positionsNode.addObject();
            item.put("positionId", p.getId());
            item.put("strategyId", p.getStrategyId());
            item.put("symbol", p.getSymbol());
            item.put("side", p.getSide() == null ? "LONG" : p.getSide());
            item.put("ageDays", defense.ageDays());
            putDecimal(item, "entry", defense.entry());
            putDecimal(item, "current", defense.current());
            putDecimal(item, "qty", p.getTradedQty());
            putDecimal(item, "ocoQty", p.getOcoQty());
            putDecimal(item, "currentSl", p.getSuggestedSl());
            putDecimal(item, "currentTp", p.getSuggestedTp());
            putDecimal(item, "unrealizedPnlUsdt", defense.unrealizedPnl());
            putDecimal(item, "paperLossPct", defense.paperLossPct());
            putDecimal(item, "currentMaxLossUsdt", gate.currentMaxLossUsdt());
            putDecimal(item, "previewNewSl", gate.previewNewSl());
            putDecimal(item, "previewNewMaxLossUsdt", gate.previewNewMaxLossUsdt());
            putDecimal(item, "previewRiskReducedUsdt", gate.previewRiskReducedUsdt());
            item.put("ocoProtected", p.getOcoOrderListId() != null);
            item.put("protectionMode", BtcBasePositionStatePolicy.isIntentionalNoOco(p)
                    ? "BTC_BASE_MANAGED_NO_OCO" : p.getOcoOrderListId() != null ? "OCO" : "UNPROTECTED");
            item.put("defenseMode", defense.mode());
            item.put("phaseBActionType", actionType);
            item.put("recommendedAction", defense.recommendedAction());
            item.put("suggestedReducePct", defense.suggestedReducePct());
            item.put("suggestedOcoAction", defense.suggestedOcoAction());
            item.put("riskReducingOnly", true);
            item.put("riskReducingSlCandidateValid", gate.allowed());
            item.put("riskReducingSlCandidateReason", gate.reason());
            item.put("operatorApprovalRequired", needsDefense);
            item.put("previewOnly", true);
            item.put("wouldPlaceOrder", false);
            item.put("wouldModifyOco", false);
            item.put("telegramSent", false);
            ArrayNode blockers = item.putArray("executionBlockers");
            blockers.add("READ_ONLY_PREVIEW");
            blockers.add("EXPLICIT_OPERATOR_APPROVAL_REQUIRED");
            if (!isOcoHealthHealthy(ocoHealthRaw)) blockers.add("OCO_HEALTH_NOT_CONFIRMED");
            if (p.getOcoOrderListId() == null && BtcBasePositionStatePolicy.isIntentionalNoOco(p)) {
                blockers.add("BTC_BASE_MANAGER_OWNS_EXIT_POLICY_NO_OCO_ACTION");
            } else if (p.getOcoOrderListId() == null) {
                blockers.add("POSITION_HAS_NO_ACTIVE_OCO");
            }
            if (needsDefense && !gate.allowed()) blockers.add("RISK_REDUCING_SL_NOT_AVAILABLE:" + gate.reason());

            if (needsDefense) {
                ObjectNode action = actions.addObject();
                action.put("positionId", p.getId());
                action.put("strategyId", p.getStrategyId());
                action.put("actionType", actionType);
                action.put("defenseMode", defense.mode());
                action.put("reason", defense.recommendedAction());
                action.put("requiresOperatorApproval", true);
                action.put("riskReducingOnly", true);
                action.put("previewOnly", true);
                action.put("wouldPlaceOrder", false);
                action.put("wouldModifyOco", false);
                action.put("riskReducingSlCandidateValid", gate.allowed());
                action.put("riskReducingSlCandidateReason", gate.reason());
                putDecimal(action, "previewNewSl", gate.previewNewSl());
                putDecimal(action, "previewRiskReducedUsdt", gate.previewRiskReducedUsdt());
            }
        }

        ObjectNode exposure = root.putObject("exposureSummary");
        exposure.put("openPositionCount", positions.size());
        putDecimal(exposure, "totalUnrealizedPnlUsdt", totalUnrealizedKnown ? totalUnrealized : null);
        putDecimal(exposure, "totalCurrentMaxLossUsdt", totalMaxLossKnown ? totalCurrentMaxLoss : null);
        exposure.put("positionsNeedingDefense", positionsNeedingDefense);
        exposure.put("riskReducingEligiblePositionCount", riskReducingEligibleCount);

        ObjectNode notification = root.putObject("notificationPreview");
        notification.put("telegramWouldSend", criticalCount > 0);
        notification.put("telegramSent", false);
        notification.put("level", criticalCount > 0 ? "CRITICAL" : positionsNeedingDefense > 0 ? "WARN" : "INFO");
        notification.put("reason", criticalCount > 0
                ? "Critical position-defense review is required, but this preview does not send Telegram."
                : positionsNeedingDefense > 0
                ? "Position-defense review is recommended; this preview is read-only."
                : "No position-defense notification needed.");

        ObjectNode shortEval = root.putObject("shortEvaluation");
        shortEval.put("mode", "SHADOW_ONLY");
        shortEval.put("canOpenLiveShort", false);
        shortEval.put("reason", "Short-side assessment remains diagnostics/shadow only in Phase B.");
        ArrayNode shortBlockers = shortEval.putArray("liveShortBlockers");
        shortBlockers.add("SHORT_LIVE_EXECUTION_NOT_ENABLED");
        shortBlockers.add("EXISTING_LONG_DEFENSE_COMES_FIRST");
        shortBlockers.add("NO_SHORT_OCO_EXECUTION_PATH_APPROVED");

        String defenseMode = criticalCount > 0
                ? "RISK_REDUCING_REVIEW_REQUIRED"
                : positionsNeedingDefense > 0 ? "WATCH_PREPARE_RISK_REDUCING_ONLY" : "WATCH";
        root.put("defenseMode", defenseMode);
        root.put("positionsNeedingDefense", positionsNeedingDefense);
        root.put("operatorAction", positionsNeedingDefense > 0
                ? "REVIEW_PHASE_B_PREVIEW; use explicit risk-reducing approval path before any reduce/OCO change."
                : "CONTINUE_MONITORING");
        root.put("nextAllowedWritePath", "operator-approved risk-reducing path only; generic trading/OCO writes remain outside this tool");

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[PositionDefensePlan] serialize failed: {}", e.getMessage(), e);
            return "{\"error\":\"serialize_failed\",\"message\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    private void addR1ConsecutiveLosses(ArrayNode rules, ArrayNode actions, LocalDateTime since) {
        ObjectNode rule = ruleNode(rules, "R1", "three_consecutive_strategy_losses");
        ArrayNode evidence = rule.putArray("evidence");
        try {
            List<BtLiveSignal> closed = liveSignalRepository
                    .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(since).stream()
                    .filter(p -> p.getStrategyId() != null)
                    .filter(p -> p.getRealizedPnl() != null)
                    .sorted(Comparator.comparing(BtLiveSignal::getExitTime).reversed())
                    .toList();
            Map<Long, List<BtLiveSignal>> byStrategy = new HashMap<>();
            for (BtLiveSignal p : closed) {
                byStrategy.computeIfAbsent(p.getStrategyId(), ignored -> new ArrayList<>()).add(p);
            }

            boolean triggered = false;
            for (Map.Entry<Long, List<BtLiveSignal>> entry : byStrategy.entrySet()) {
                List<BtLiveSignal> recent = entry.getValue().stream().limit(3).toList();
                if (recent.size() < 3) continue;
                boolean threeLosses = recent.stream().allMatch(p -> p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0);
                ObjectNode item = evidence.addObject();
                item.put("strategyId", entry.getKey());
                item.put("lastClosedTrades", recent.size());
                item.put("last3AllLosses", threeLosses);
                item.put("last3PnlUsdt", joinPnl(recent));
                if (threeLosses) {
                    triggered = true;
                    proposed(actions, "pauseStrategy", "R1", entry.getKey(), null,
                            "Last 3 closed auto-trades in lookback were losses. Proposal only; no strategy write executed.");
                }
            }
            rule.put("triggered", triggered);
            rule.put("status", triggered ? "PROPOSE_ACTION" : "OK");
        } catch (Exception e) {
            rule.put("status", "ERROR");
            rule.put("error", e.getMessage());
        }
    }

    private void addR2AgedPositionRisk(ArrayNode rules, ArrayNode actions, String symbol, LocalDateTime now) {
        ObjectNode rule = ruleNode(rules, "R2", "aged_position_paper_loss");
        ArrayNode evidence = rule.putArray("evidence");
        try {
            List<BtLiveSignal> positions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                    .filter(p -> symbol.equalsIgnoreCase(p.getSymbol()))
                    .toList();
            boolean triggered = false;
            for (BtLiveSignal p : positions) {
                BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
                BigDecimal current = latestPriceOrNull(p.getSymbol());
                long ageDays = p.getCreatedAt() == null ? 0 : ChronoUnit.DAYS.between(p.getCreatedAt(), now);
                BigDecimal paperLossPct = paperLossPct(p, entry, current);
                boolean hit = ageDays > 5 && paperLossPct.compareTo(DEFAULT_AGED_LOSS_PCT) > 0;

                ObjectNode item = evidence.addObject();
                item.put("positionId", p.getId());
                item.put("strategyId", p.getStrategyId());
                item.put("symbol", p.getSymbol());
                item.put("side", p.getSide() == null ? "LONG" : p.getSide());
                item.put("ageDays", ageDays);
                putDecimal(item, "entry", entry);
                putDecimal(item, "current", current);
                putDecimal(item, "paperLossPct", paperLossPct);
                item.put("triggered", hit);

                if (hit) {
                    triggered = true;
                    proposed(actions, "modifyOco_tighten_sl", "R2", p.getStrategyId(), p.getId(),
                            "Aged position with paper loss above threshold. Proposal only; no OCO write executed.");
                }
            }
            rule.put("triggered", triggered);
            rule.put("status", triggered ? "PROPOSE_ACTION" : "OK");
        } catch (Exception e) {
            rule.put("status", "ERROR");
            rule.put("error", e.getMessage());
        }
    }

    private void addR3DailyLossGuard(ArrayNode rules, ArrayNode actions, LocalDateTime since) {
        ObjectNode rule = ruleNode(rules, "R3", "daily_total_loss_guard");
        ArrayNode evidence = rule.putArray("evidence");
        try {
            BigDecimal dailyPnl = liveSignalRepository
                    .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(since).stream()
                    .map(BtLiveSignal::getRealizedPnl)
                    .filter(v -> v != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BtStrategy> pocStrategies = strategyRepository.findByEnabled(true).stream()
                    .filter(this::isPocStrategy)
                    .toList();
            boolean triggered = dailyPnl.compareTo(DEFAULT_DAILY_LOSS_USDT.negate()) < 0;
            ObjectNode item = evidence.addObject();
            putDecimal(item, "realizedPnlUsdt", dailyPnl);
            putDecimal(item, "lossThresholdUsdt", DEFAULT_DAILY_LOSS_USDT.negate());
            item.put("enabledPocStrategyCount", pocStrategies.size());
            item.put("triggered", triggered);
            if (triggered) {
                for (BtStrategy s : pocStrategies) {
                    proposed(actions, "disableStrategy", "R3", s.getId(), null,
                            "Daily realized loss breached threshold. Proposal only; no strategy write executed.");
                }
            }
            rule.put("triggered", triggered);
            rule.put("status", triggered ? "PROPOSE_ACTION" : "OK");
        } catch (Exception e) {
            rule.put("status", "ERROR");
            rule.put("error", e.getMessage());
        }
    }

    private void addR4BackendHealth(ArrayNode rules) {
        ObjectNode rule = ruleNode(rules, "R4", "backend_health");
        try {
            String health = marketDataMcpTools.getSystemHealth();
            rule.put("triggered", health.contains("DOWN") || health.contains("REFUSING_TRAFFIC") || health.contains("\u274c"));
            rule.put("status", rule.get("triggered").asBoolean() ? "PROPOSE_ALERT" : "OK");
            rule.put("evidenceText", abbreviate(health, 2000));
            rule.put("limitation", "If this MCP tool responds, the backend JVM is up. Sirin must still run an external timeout probe for true backend-down detection.");
        } catch (Exception e) {
            rule.put("triggered", true);
            rule.put("status", "PROPOSE_ALERT");
            rule.put("error", e.getMessage());
        }
    }

    private void addR5TrailingDryRun(ArrayNode rules) {
        ObjectNode rule = ruleNode(rules, "R5", "trailing_stop_dry_run_advisory");
        try {
            String status = positionMcpTools.getTrailingStopStatus();
            rule.put("triggered", status.contains("reached=true") || status.contains("dryRun=true"));
            rule.put("status", "OBSERVE_ONLY");
            rule.put("evidenceText", abbreviate(status, 3000));
        } catch (Exception e) {
            rule.put("status", "ERROR");
            rule.put("error", e.getMessage());
        }
    }

    private ObjectNode ruleNode(ArrayNode rules, String id, String name) {
        ObjectNode rule = rules.addObject();
        rule.put("id", id);
        rule.put("name", name);
        rule.put("writeCapable", false);
        return rule;
    }

    private void proposed(ArrayNode actions, String action, String ruleId, Long strategyId, Long positionId, String reason) {
        ObjectNode node = actions.addObject();
        node.put("ruleId", ruleId);
        node.put("action", action);
        if (strategyId != null) node.put("strategyId", strategyId);
        if (positionId != null) node.put("positionId", positionId);
        node.put("riskReducingOnly", true);
        node.put("executed", false);
        node.put("reason", reason);
    }

    private boolean isPocStrategy(BtStrategy s) {
        String name = s.getName() == null ? "" : s.getName();
        String notes = s.getNotes() == null ? "" : s.getNotes();
        return name.startsWith("NoSL")
                || name.toUpperCase().contains("POC")
                || notes.toUpperCase().contains("POC");
    }

    private BigDecimal latestPriceOrNull(String symbol) {
        try {
            return okxTradingService.getLastPrice(symbol);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal paperLossPct(BtLiveSignal p, BigDecimal entry, BigDecimal current) {
        if (entry == null || current == null || entry.signum() <= 0) return BigDecimal.ZERO;
        boolean isShort = "SHORT".equalsIgnoreCase(p.getSide());
        BigDecimal diff = isShort ? current.subtract(entry) : entry.subtract(current);
        return diff.multiply(new BigDecimal("100")).divide(entry, 4, RoundingMode.HALF_UP);
    }

    private PositionDefense assessPositionDefense(BtLiveSignal p,
                                                  EventRiskLevelEngine.Snapshot risk,
                                                  LocalDateTime now) {
        BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
        BigDecimal current = latestPriceOrNull(p.getSymbol());
        long ageDays = p.getCreatedAt() == null ? 0 : ChronoUnit.DAYS.between(p.getCreatedAt(), now);
        BigDecimal paperLossPct = paperLossPct(p, entry, current);
        BigDecimal pnl = unrealizedPnl(p, entry, current);
        boolean r3 = risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3);
        boolean r2 = risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R2);
        boolean severeLoss = paperLossPct.compareTo(DEFAULT_AGED_LOSS_PCT) >= 0;
        boolean agedLoss = ageDays > 5 && severeLoss;

        if (BtcBasePositionStatePolicy.isBtcBase(p)) {
            return new PositionDefense(entry, current, pnl, paperLossPct, ageDays,
                    "BTC_BASE_MANAGER_REVIEW",
                    "BTC_BASE manager retains the position; no generic reduce, market sell, or OCO action is recommended.",
                    0,
                    "NONE",
                    "INFO");
        }

        if (r3 && severeLoss) {
            return new PositionDefense(entry, current, pnl, paperLossPct, ageDays,
                    "REDUCE_OR_TIGHTEN_REVIEW",
                    "Preview partial reduce 25-50% or tighten protective OCO after operator approval.",
                    50,
                    "TIGHTEN_SL_PREVIEW",
                    "CRITICAL");
        }
        if (r3 || agedLoss) {
            return new PositionDefense(entry, current, pnl, paperLossPct, ageDays,
                    "TIGHTEN_OCO_REVIEW",
                    "Review protective OCO tightening; no automatic action.",
                    0,
                    "TIGHTEN_SL_PREVIEW",
                    r3 ? "WARN" : "INFO");
        }
        if (r2 && paperLossPct.compareTo(new BigDecimal("3")) >= 0) {
            return new PositionDefense(entry, current, pnl, paperLossPct, ageDays,
                    "WATCH_RISK_REDUCING_ONLY",
                    "Watch position; prepare risk-reducing action if risk escalates.",
                    0,
                    "WATCH",
                    "INFO");
        }
        return new PositionDefense(entry, current, pnl, paperLossPct, ageDays,
                "WATCH",
                "No position-defense action currently recommended.",
                0,
                "NONE",
                "INFO");
    }

    private BigDecimal unrealizedPnl(BtLiveSignal p, BigDecimal entry, BigDecimal current) {
        BigDecimal qty = p.getOcoQty() != null ? p.getOcoQty() : p.getTradedQty();
        if (entry == null || current == null || qty == null) return null;
        boolean isShort = "SHORT".equalsIgnoreCase(p.getSide());
        BigDecimal diff = isShort ? entry.subtract(current) : current.subtract(entry);
        return diff.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private RiskReductionGate assessBreakevenRiskReduction(BtLiveSignal p, BigDecimal current) {
        BigDecimal targetSl = previewBreakevenSl(p);
        BigDecimal currentLoss = maxLossUsdt(p, p.getSuggestedSl());
        BigDecimal newLoss = maxLossUsdt(p, targetSl);
        String validation = validatePreviewRiskReducingSl(p, targetSl, current);
        BigDecimal reduced = currentLoss != null && newLoss != null ? currentLoss.subtract(newLoss) : null;
        return new RiskReductionGate(
                validation == null,
                validation == null ? "BREAKEVEN_PREVIEW_AVAILABLE" : validation,
                targetSl,
                currentLoss,
                newLoss,
                reduced);
    }

    private BigDecimal previewBreakevenSl(BtLiveSignal p) {
        BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
        if (entry == null || entry.signum() <= 0) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(p.getSide());
        BigDecimal feeAdjusted = isLong
                ? entry.multiply(new BigDecimal("1.001"))
                : entry.multiply(new BigDecimal("0.999"));
        return protectiveStop(p.getSuggestedSl(), feeAdjusted, isLong);
    }

    private BigDecimal protectiveStop(BigDecimal currentSl, BigDecimal candidate, boolean isLong) {
        if (candidate == null) return currentSl;
        if (currentSl == null) return candidate;
        return isLong ? candidate.max(currentSl) : candidate.min(currentSl);
    }

    private String validatePreviewRiskReducingSl(BtLiveSignal p, BigDecimal newSl, BigDecimal current) {
        if (newSl == null || newSl.signum() <= 0) return "newSl_missing_or_invalid";
        if (p.getOcoOrderListId() == null) return "position_has_no_active_oco";
        if (p.getSuggestedSl() == null) return "old_sl_missing";
        if (p.getSuggestedTp() == null) return "tp_missing";
        BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
        if (entry == null || entry.signum() <= 0) return "entry_missing_or_invalid";
        boolean isLong = !"SHORT".equalsIgnoreCase(p.getSide());
        if (isLong) {
            if (newSl.compareTo(p.getSuggestedSl()) < 0) return "long_sl_would_move_down";
            if (newSl.compareTo(p.getSuggestedTp()) >= 0) return "long_sl_crosses_tp";
            if (current != null && newSl.compareTo(current) >= 0) return "long_sl_crosses_current_price";
        } else {
            if (newSl.compareTo(p.getSuggestedSl()) > 0) return "short_sl_would_move_up";
            if (newSl.compareTo(p.getSuggestedTp()) <= 0) return "short_sl_crosses_tp";
            if (current != null && newSl.compareTo(current) <= 0) return "short_sl_crosses_current_price";
        }
        BigDecimal currentLoss = maxLossUsdt(p, p.getSuggestedSl());
        BigDecimal newLoss = maxLossUsdt(p, newSl);
        if (currentLoss == null || newLoss == null) return "max_loss_unavailable";
        if (newLoss.compareTo(currentLoss) >= 0) return "max_loss_not_reduced";
        return null;
    }

    private BigDecimal maxLossUsdt(BtLiveSignal p, BigDecimal sl) {
        BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
        BigDecimal qty = p.getOcoQty() != null ? p.getOcoQty() : p.getTradedQty();
        if (entry == null || sl == null || qty == null) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(p.getSide());
        BigDecimal lossPerUnit = isLong ? entry.subtract(sl) : sl.subtract(entry);
        if (lossPerUnit.signum() < 0) lossPerUnit = BigDecimal.ZERO;
        return lossPerUnit.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private String phaseBActionType(PositionDefense defense) {
        if ("REDUCE_OR_TIGHTEN_REVIEW".equals(defense.mode())) {
            return "PREPARE_PARTIAL_REDUCE_OR_TIGHTEN_SL";
        }
        if ("TIGHTEN_OCO_REVIEW".equals(defense.mode())) {
            return "PREPARE_TIGHTEN_SL";
        }
        if ("WATCH_RISK_REDUCING_ONLY".equals(defense.mode())) {
            return "PREPARE_DEFENSE_REVIEW";
        }
        return "WATCH";
    }

    private String safeOcoHealth() {
        try {
            String value = positionMcpTools.getOcoHealth();
            return value == null || value.isBlank() ? "UNAVAILABLE" : value;
        } catch (Exception e) {
            return "UNAVAILABLE: " + sanitize(e.getMessage());
        }
    }

    private boolean isOcoHealthHealthy(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("UNAVAILABLE")) return false;
        return !containsPositiveOcoIssue(raw, "SYNC_ERROR")
                && !containsPositiveOcoIssue(raw, "UNPROTECTED")
                && !containsPositiveOcoIssue(raw, "CRITICAL_UNPROTECTED")
                && !containsPositiveOcoIssue(raw, "ABNORMAL")
                && !containsPositiveOcoIssue(raw, "異常");
    }

    private String ocoHealthStatus(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("UNAVAILABLE")) return "UNKNOWN";
        return isOcoHealthHealthy(raw) ? "OK" : "ABNORMAL";
    }

    private boolean containsPositiveOcoIssue(String raw, String marker) {
        String markerUpper = marker.toUpperCase();
        String[] chunks = raw.split("[\\n|,;]");
        for (String chunk : chunks) {
            String text = chunk.trim();
            String upper = text.toUpperCase();
            if (!upper.contains(markerUpper)) continue;
            if (upper.contains("0 " + markerUpper) || upper.contains(markerUpper + "=0") || upper.contains(markerUpper + ":0")) {
                continue;
            }
            if ("異常".equals(marker) && (text.contains("0 異常") || text.contains("異常=0") || text.contains("異常:0"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private record RiskReductionGate(
            boolean allowed,
            String reason,
            BigDecimal previewNewSl,
            BigDecimal currentMaxLossUsdt,
            BigDecimal previewNewMaxLossUsdt,
            BigDecimal previewRiskReducedUsdt
    ) {
    }

    private record PositionDefense(
            BigDecimal entry,
            BigDecimal current,
            BigDecimal unrealizedPnl,
            BigDecimal paperLossPct,
            long ageDays,
            String mode,
            String recommendedAction,
            int suggestedReducePct,
            String suggestedOcoAction,
            String notificationLevel
    ) {
    }

    private String joinPnl(List<BtLiveSignal> signals) {
        return signals.stream()
                .map(p -> p.getRealizedPnl().setScale(4, RoundingMode.HALF_UP).toPlainString())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.setScale(Math.min(Math.max(value.scale(), 2), 8), RoundingMode.HALF_UP));
        }
    }

    private void putSortedArray(ObjectNode root, String field, java.util.Set<String> values) {
        ArrayNode array = root.putArray(field);
        values.stream().sorted().forEach(array::add);
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int v = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, v));
    }

    private String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String sanitize(String text) {
        return text == null ? "" : text.replace("\"", "'");
    }
}
