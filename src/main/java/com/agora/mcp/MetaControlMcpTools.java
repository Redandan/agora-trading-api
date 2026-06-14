package com.agora.mcp;

import com.agora.dto.meta.AttributionSummary;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpApiKeyFilter;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.AttentionRule;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtLiveSignal;
import com.agora.model.HintOverride;
import com.agora.model.PositionAnnotation;
import com.agora.model.StrategyOverride;
import com.agora.model.SystemReminder;
import com.agora.model.TgNotificationLog;
import com.agora.repository.trading.AttentionRuleRepository;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.HintOverrideRepository;
import com.agora.repository.trading.PositionAnnotationRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.repository.trading.StrategyOverrideRepository;
import com.agora.service.SystemReminderService;
import com.agora.service.TelegramService;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgTradingNotificationClassifier;
import com.agora.service.TgTradingNotificationClassifier.Bucket;
import com.agora.service.ai.chroma.ChromaDocument;
import com.agora.service.ai.knowledge.DevKnowledgeService;
import com.agora.service.ai.router.AiResponse;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.agora.service.ai.router.AiUsageTracker;
import com.agora.service.meta.AttentionRuleEvaluator;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.meta.MetaControlAttributionService;
import com.agora.service.meta.StrategyOverrideService;
import com.agora.service.meta.SystemSnapshotCollector;
import com.agora.service.telegram.EventScanTelegramButtons;
import com.agora.service.telegram.MarketSignalRiskTelegramButtons;
import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Meta-Control MCP 工具集 — Claude(via MCP)調整系統參數 / 注入 hint / 建立注意力規則。
 *
 * <p>與 {@link StrategyManagementMcpTools} / {@link BacktestValidationMcpTools}
 * 等平行,透過 {@code McpToolsConfig} 自動註冊。
 *
 * <p>**寫入類工具**統一 {@code @McpAuth(DEV)},成功後發 TG 讓人類可見。
 * **觀測類工具**使用 {@code @McpAuth(OPS)} 便於未來接 dashboard。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaControlMcpTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final int RECENT_DECISIONS_DEFAULT_LIMIT = 30;
    private static final int RECENT_DECISIONS_MAX_LIMIT = 200;

    private final StrategyOverrideService strategyOverrideService;
    private final StrategyOverrideRepository strategyOverrideRepository;
    private final HintOverrideRepository hintOverrideRepository;
    private final AttentionRuleRepository attentionRuleRepository;
    private final AttentionRuleEvaluator attentionRuleEvaluator;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final PositionAnnotationRepository positionAnnotationRepository;
    private final SystemReminderService reminderService;
    private final com.agora.repository.system.SystemReminderRepository reminderRepository;
    private final DecisionAuditWriter auditWriter;
    private final NotificationPort notificationPort;
    private final TelegramService telegramService;
    private final MetaControlAttributionService attributionService;
    private final com.agora.scheduler.trading.AttentionRuleWeeklyDigest attentionWeeklyDigest;
    private final java.util.List<org.springframework.scheduling.config.ScheduledTaskHolder> scheduledTaskHolders;
    private final com.agora.repository.trading.MarketFlipEventRepository marketFlipEventRepository;
    private final com.agora.repository.trading.MarketFlipConfigRepository marketFlipConfigRepository;
    private final SystemSnapshotCollector systemSnapshotCollector;
    private final AiTaskRouter aiTaskRouter;
    private final DevKnowledgeService devKnowledgeService;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepo;
    private final TgNotificationLogRepository tgNotificationLogRepo;
    private final TgTradingNotificationClassifier tgNotificationClassifier;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final AiUsageTracker aiUsageTracker;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private volatile String lastScheduledEventScanNoActionFingerprint;
    private volatile String lastScheduledMarketSignalRiskFingerprint;

    /**
     * {@link McpApiKeyFilter} 是 MCP 工具分類的單一權威(啟動掃 @McpCategory 建 index)。
     * 用 {@code @Lazy} 注入避免 @PostConstruct 階段的循環依賴 —— 這個 class 本身
     * 也是 filter 會掃到的 tool bean 之一。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private McpApiKeyFilter mcpApiKeyFilter;

    @Value("${meta-control.hint-override.max-ttl-hours:6}")
    private int hintMaxTtlHours;

    // =========================================================================
    // 控制類 — PAUSE / RESUME 策略(Phase 1 最核心的 AI-in-the-loop 干預)
    // =========================================================================

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "暫停指定策略(冪等,下次 bar 收盤後生效)。" +
            "若 symbol=null 則暫停此策略所有 symbols;ttlMinutes 上限 1440(24h)。" +
            "冪等:相同 scope 已有 active PAUSE 則延長 expires_at。" +
            "成功後發 TG 通知人類。" +
            "params: strategyId, symbol(可選,null=all), intervalCode(可選,null=all), " +
            "ttlMinutes(1~1440), reason(必填)")
    public String pauseStrategy(Long strategyId, String symbol, String intervalCode,
                                 Integer ttlMinutes, String reason) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        if (ttlMinutes == null || ttlMinutes <= 0) return "❌ ttlMinutes 必須 > 0";
        String sym  = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();
        String ival = (intervalCode == null || intervalCode.isBlank()) ? null : intervalCode.toLowerCase().trim();

        StrategyOverrideService.PauseResult result = strategyOverrideService.pauseStrategy(
                strategyId, sym, ival, ttlMinutes, reason, "claude");

        if (!result.ok()) {
            return "❌ " + result.error();
        }

        String scope = String.format("strategy=%d symbol=%s interval=%s",
                strategyId, sym != null ? sym : "*", ival != null ? ival : "*");
        String msg = String.format(
                "✅ PAUSE %s(%s)\n" +
                "   reason: %s\n" +
                "   expires_at: %s UTC\n" +
                "   override_id: %d\n" +
                "⚠️ 下次 bar 收盤後生效(evaluator 每 bar 查 override)",
                scope, result.extended() ? "延長" : "新建",
                reason, result.expiresAt().format(FMT), result.overrideId());

        auditWriter.logOverrideApplied(strategyId, sym, "StrategyOverride.PAUSE",
                "ttl=" + ttlMinutes + "min reason=" + reason);
        try {
            notificationPort.broadcast(String.format(
                    "🛑 <b>Claude PAUSE 策略</b>\n%s\nreason: %s\n直到: %s UTC",
                    scope, reason, result.expiresAt().format(FMT)), true);
        } catch (Exception ignored) {}

        return msg;
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "撤銷策略的 PAUSE(revoked_at=now)。symbol=null 則撤銷此策略所有 active PAUSE。" +
            "若無 active PAUSE 則回傳『無效動作』但不當 error。" +
            "params: strategyId, symbol(可選), intervalCode(可選)")
    public String resumeStrategy(Long strategyId, String symbol, String intervalCode) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        String sym  = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();
        String ival = (intervalCode == null || intervalCode.isBlank()) ? null : intervalCode.toLowerCase().trim();

        int revoked = strategyOverrideService.resumeStrategy(strategyId, sym, ival);
        String scope = String.format("strategy=%d symbol=%s interval=%s",
                strategyId, sym != null ? sym : "*", ival != null ? ival : "*");

        if (revoked == 0) {
            return "ℹ️ " + scope + " 當前無 active PAUSE,無需撤銷";
        }

        auditWriter.logOverrideApplied(strategyId, sym, "StrategyOverride.RESUME",
                "revoked_count=" + revoked);
        try {
            notificationPort.broadcast(String.format(
                    "▶️ <b>Claude RESUME 策略</b>\n%s\n撤銷 %d 筆 PAUSE",
                    scope, revoked), true);
        } catch (Exception ignored) {}

        return String.format("✅ 已撤銷 %d 筆 active PAUSE (%s)\n⚠️ 下次 bar 收盤恢復評估", revoked, scope);
    }

    // =========================================================================
    // 控制類 — 注入 / 撤銷 Hint Override(per-field 覆蓋 Gemini hint)
    // =========================================================================

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "注入手動 hint(per-field 覆蓋 Gemini)。null 欄位不覆蓋;" +
            "styleHint=DISABLE 為 kill switch(策略完全跳過)。ttlMinutes 上限 360(6h)。" +
            "params: symbol, timeframe(1h/4h), styleHint(TREND/HIGH_FREQ/CONSERVATIVE/DISABLE,null=不覆蓋), " +
            "slMult(0.5~2.0,null=不覆蓋), tpMult(0.5~2.0,null=不覆蓋), allowShort(null=不覆蓋), " +
            "ttlMinutes(1~360), reason(必填)")
    public String overrideHint(String symbol, String timeframe, String styleHint,
                                BigDecimal slMult, BigDecimal tpMult, Boolean allowShort,
                                Integer ttlMinutes, String reason) {
        { String _e = McpParamValidator.requireNonBlank(symbol, "symbol"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(timeframe, "timeframe"); if (_e != null) return _e; }
        if (ttlMinutes == null || ttlMinutes <= 0) return "❌ ttlMinutes 必須 > 0";
        if (ttlMinutes > hintMaxTtlHours * 60) {
            return String.format("❌ ttlMinutes %d 超出範圍 (1~%d,硬上限 %dh)",
                    ttlMinutes, hintMaxTtlHours * 60, hintMaxTtlHours);
        }
        { String _e = McpParamValidator.requireNonBlank(reason, "reason"); if (_e != null) return _e; }
        if (styleHint != null && !styleHint.isBlank()) {
            String upper = styleHint.toUpperCase();
            if (!java.util.Set.of("TREND", "HIGH_FREQ", "CONSERVATIVE", "DISABLE").contains(upper)) {
                return "❌ styleHint 必須是 TREND/HIGH_FREQ/CONSERVATIVE/DISABLE";
            }
            styleHint = upper;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        HintOverride ho = new HintOverride();
        ho.setSymbol(symbol.toUpperCase().trim());
        ho.setTimeframe(timeframe.toLowerCase().trim());
        ho.setStyleHint(styleHint != null && !styleHint.isBlank() ? styleHint : null);
        ho.setSlMultiplier(slMult);
        ho.setTpMultiplier(tpMult);
        ho.setAllowShort(allowShort);
        ho.setReason(reason);
        ho.setCreatedBy("claude");
        ho.setCreatedAt(now);
        ho.setExpiresAt(now.plusMinutes(ttlMinutes));
        hintOverrideRepository.save(ho);

        String msg = String.format(
                "✅ HintOverride 已注入\n" +
                "   scope: %s @%s\n" +
                "   style=%s sl×=%s tp×=%s allowShort=%s\n" +
                "   reason: %s\n" +
                "   expires: %s UTC(%dm)\n" +
                "   override_id: %d\n" +
                "⚠️ 下次 bar 評估時生效",
                ho.getSymbol(), ho.getTimeframe(),
                ho.getStyleHint() != null ? ho.getStyleHint() : "-",
                ho.getSlMultiplier() != null ? ho.getSlMultiplier() : "-",
                ho.getTpMultiplier() != null ? ho.getTpMultiplier() : "-",
                ho.getAllowShort() != null ? ho.getAllowShort() : "-",
                reason, ho.getExpiresAt().format(FMT), ttlMinutes, ho.getId());

        auditWriter.logOverrideApplied(null, ho.getSymbol(), "HintOverride",
                "style=" + ho.getStyleHint() + " ttl=" + ttlMinutes + "min reason=" + reason);
        try {
            notificationPort.broadcast(String.format(
                    "🎛 <b>Claude 注入 HintOverride</b>\n%s@%s style=%s sl×=%s tp×=%s\n直到:%s UTC",
                    ho.getSymbol(), ho.getTimeframe(),
                    ho.getStyleHint() != null ? ho.getStyleHint() : "-",
                    ho.getSlMultiplier() != null ? ho.getSlMultiplier() : "-",
                    ho.getTpMultiplier() != null ? ho.getTpMultiplier() : "-",
                    ho.getExpiresAt().format(FMT)), true);
        } catch (Exception ignored) {}

        return msg;
    }

    // =========================================================================
    // 控制類 — 建立注意力規則(Phase 1 只 LOG_ONLY / NOTIFY)
    // =========================================================================

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "建立注意力規則。Phase 1 action 只支援 LOG_ONLY / NOTIFY(不阻擋流程)。" +
            "predicateJson 單層 AND,範例:{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"fg_gt\":80}。" +
            "支援欄位:symbol, interval, side(LONG/SHORT), fg_gt, fg_lt, rsi_gt, rsi_lt, " +
            "adx_gt, adx_lt, volume_ratio_gt, volume_ratio_lt, gemini_style, gemini_regime, strategy_id_in。" +
            "mih_indicator 欄位(market_indicator_history 獨立監控,每 30 分鐘排程評估,不依賴策略信號):" +
            "mih_indicator=指標名稱, mih_gt=閾值(大於), mih_lt=閾值(小於)。" +
            "⚠️ 正確指標名稱(#288)：" +
            "funding_rate(非btc_funding_rate), long_short_ratio(非btc_long_short_account_ratio), " +
            "oi_change_pct_1h(非btc_oi_change_pct_1h), whale_buy_ratio, btc_put_call_ratio, " +
            "btc_basis_pct, btc_dvol, us_vix, fear_greed, us_10y_yield, stablecoin_supply_b。" +
            "入庫前驗 JSON parse + DB 存在性(名稱不存在回傳 MIH_INDICATOR_NOT_FOUND + 正確名稱清單)。" +
            "若確定要建立未收集的指標，在 name 加前綴 [UNVERIFIED] 強制略過。" +
            "params: name, predicateJson, action(LOG_ONLY/NOTIFY), severity(INFO/WARN/CRITICAL), ttlHours(null=永久)")
    public String createAttentionRule(String name, String predicateJson, String action,
                                       String severity, Integer ttlHours) {
        { String _e = McpParamValidator.requireNonBlank(name, "name"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(predicateJson, "predicateJson"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(action, "action"); if (_e != null) return _e; }
        String actUpper = action.toUpperCase();
        if (!AttentionRuleEvaluator.isSupportedPhase1Action(actUpper)) {
            return "❌ Phase 1 只支援 LOG_ONLY / NOTIFY;REQUIRE_REVIEW / BLOCK 為 Phase 2";
        }
        String sevUpper = severity != null && !severity.isBlank() ? severity.toUpperCase() : "INFO";
        if (!AttentionRuleEvaluator.supportedSeverities().contains(sevUpper)) {
            return "❌ severity 必須是 INFO / WARN / CRITICAL";
        }

        // dry-run:parse + 用空 context 測(只驗 JSON 合法,不驗 match)
        AttentionRuleEvaluator.DryRunResult dry = attentionRuleEvaluator.dryRun(
                predicateJson, java.util.Map.of());
        if (!dry.parseOk()) {
            return "❌ predicateJson 解析失敗: " + dry.error();
        }

        // ── #288 mih_indicator 名稱驗證 ──────────────────────────────────────
        // 若 predicate 含 mih_indicator，查 market_indicator_history 確認名稱存在。
        // 若不存在回傳 WARNING（仍允許建立，因為資料可能尚未收集）+ 列出最近可用指標。
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parsedPred = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(predicateJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            if (parsedPred.containsKey("mih_indicator")) {
                String indicator = (String) parsedPred.get("mih_indicator");
                String sym = parsedPred.containsKey("symbol") ? (String) parsedPred.get("symbol") : "BTCUSDT";
                // #384 — clean variant ensures pre-flight check matches design intent
                // (don't accept indicator existence based on flagged-error rows alone)
                boolean exists = indicatorHistoryRepo
                        .findTopCleanBySymbolAndIndicator(sym, indicator)
                        .isPresent();
                if (!exists) {
                    // Allow bypass: if name starts with "[UNVERIFIED]" the caller knowingly skips check
                    boolean bypass = name != null && name.startsWith("[UNVERIFIED]");
                    if (!bypass) {
                        return String.format(
                                "⚠️ [MIH_INDICATOR_NOT_FOUND] mih_indicator='%s' 在 market_indicator_history 中無記錄（symbol=%s）。\n\n" +
                                "常見正確名稱（MarketIndicatorHistoryCollector 存儲）：\n" +
                                "  funding_rate               ← 非 btc_funding_rate\n" +
                                "  long_short_ratio           ← 非 btc_long_short_account_ratio\n" +
                                "  oi_change_pct_1h           ← 非 btc_oi_change_pct_1h\n" +
                                "  whale_buy_ratio  /  whale_buy_ratio_3h_ma\n" +
                                "  btc_put_call_ratio  /  btc_dvol  /  btc_basis_pct\n" +
                                "  us_vix  /  us_10y_yield  /  fear_greed\n" +
                                "  stablecoin_supply_b  /  defi_tvl_total_b\n\n" +
                                "若確認要建立（指標尚未收集或使用特殊名稱），\n" +
                                "請在 name 欄位加前綴 [UNVERIFIED] 強制跳過驗證。",
                                indicator, sym);
                    }
                    log.warn("[AttentionRule] mih_indicator='{}' not found in DB, bypassed via [UNVERIFIED] prefix", indicator);
                }
            }
        } catch (Exception mihCheckEx) {
            log.warn("[AttentionRule] mih_indicator validation failed (non-blocking): {}", mihCheckEx.getMessage());
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AttentionRule r = new AttentionRule();
        r.setName(name);
        r.setEnabled(true);
        r.setPredicateJson(predicateJson);
        r.setAction(actUpper);
        r.setSeverity(sevUpper);
        r.setCreatedBy("claude");
        r.setCreatedAt(now);
        if (ttlHours != null && ttlHours > 0) {
            r.setExpiresAt(now.plusHours(ttlHours));
        }
        attentionRuleRepository.save(r);

        auditWriter.logOverrideApplied(null, null, "AttentionRule.CREATE",
                "name=" + name + " action=" + actUpper);
        try {
            notificationPort.broadcast(String.format(
                    "📋 <b>Claude 建立 Attention Rule</b>\n%s\naction=%s severity=%s\npredicate=%s",
                    name, actUpper, sevUpper, predicateJson), true);
        } catch (Exception ignored) {}

        String exp = r.getExpiresAt() != null ? r.getExpiresAt().format(FMT) + " UTC" : "永久";
        return String.format(
                "✅ AttentionRule 已建立\n" +
                "   id=%d name=%s\n" +
                "   action=%s severity=%s expires=%s\n" +
                "   predicate=%s",
                r.getId(), r.getName(), r.getAction(), r.getSeverity(), exp, predicateJson);
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "停用指定 Attention Rule(設 enabled=false)。" +
            "WeeklyDigest 發現「💤 從未觸發」時可以直接用此工具關掉對應規則。" +
            "停用後規則不再評估新 signal,但歷史 hit 記錄保留。" +
            "params: ruleId, reason(必填,附加到 description)")
    public String disableAttentionRule(Long ruleId, String reason) {
        { String _e = McpParamValidator.requireNonNull(ruleId, "ruleId"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(reason, "reason"); if (_e != null) return _e; }
        AttentionRule r = attentionRuleRepository.findById(ruleId).orElse(null);
        if (r == null) return "❌ Rule " + ruleId + " 不存在";
        if (!Boolean.TRUE.equals(r.getEnabled())) {
            return "ℹ️ Rule " + ruleId + " (" + r.getName() + ") 已是停用狀態";
        }
        r.setEnabled(false);
        String appended = (r.getDescription() != null ? r.getDescription() + " | " : "")
                + "disabled by claude: " + reason;
        r.setDescription(appended.length() > 500 ? appended.substring(0, 500) : appended);
        attentionRuleRepository.save(r);
        auditWriter.logOverrideApplied(null, null, "AttentionRule.DISABLE",
                "ruleId=" + ruleId + " name=" + r.getName() + " reason=" + reason);
        return String.format("✅ Rule %d [%s] 已停用\n   reason: %s\n   hits=%d lastHit=%s",
                r.getId(), r.getName(), reason,
                r.getHitCount() != null ? r.getHitCount() : 0,
                r.getLastHitAt() != null ? r.getLastHitAt().format(FMT) + " UTC" : "從未");
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "延長 Attention Rule 的 TTL(重設 expiresAt)。" +
            "若目前無 expires_at(永久),此操作會設定一個新的過期時間。" +
            "params: ruleId, ttlHours(從現在起算,1~2160=90d)")
    public String extendAttentionRule(Long ruleId, Integer ttlHours) {
        { String _e = McpParamValidator.requireNonNull(ruleId, "ruleId"); if (_e != null) return _e; }
        if (ttlHours == null || ttlHours <= 0) return "❌ ttlHours 必須 > 0";
        if (ttlHours > 2160) return "❌ ttlHours 上限 2160(90 天),要更長請用手動 SQL";
        AttentionRule r = attentionRuleRepository.findById(ruleId).orElse(null);
        if (r == null) return "❌ Rule " + ruleId + " 不存在";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime newExp = now.plusHours(ttlHours);
        LocalDateTime oldExp = r.getExpiresAt();
        r.setExpiresAt(newExp);
        attentionRuleRepository.save(r);
        auditWriter.logOverrideApplied(null, null, "AttentionRule.EXTEND",
                "ruleId=" + ruleId + " newExpires=" + newExp);
        return String.format("✅ Rule %d [%s] 已延長\n   舊過期: %s\n   新過期: %s UTC (+%dh)",
                r.getId(), r.getName(),
                oldExp != null ? oldExp.format(FMT) + " UTC" : "永久",
                newExp.format(FMT), ttlHours);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.DIAGNOSTIC})
    @Tool(description = "列出所有已註冊的 Spring @Scheduled 任務(cron / fixedDelay / fixedRate)。" +
            "用於確認 scheduler 有真的被 Spring 載入運作,以及查看各 scheduler 的觸發頻率。" +
            "顯示 target bean / method 名稱 + 觸發 expression。")
    public String listSchedulers() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 已註冊 Scheduled 任務 ===\n\n");
        int total = 0;
        int cronCount = 0, fixedDelayCount = 0, fixedRateCount = 0;

        java.util.List<String> cronTasks = new java.util.ArrayList<>();
        java.util.List<String> fixedDelayTasks = new java.util.ArrayList<>();
        java.util.List<String> fixedRateTasks = new java.util.ArrayList<>();

        for (var holder : scheduledTaskHolders) {
            for (var task : holder.getScheduledTasks()) {
                total++;
                Object rawTask = task.getTask();
                String taskDesc = describeTarget(rawTask);
                if (rawTask instanceof org.springframework.scheduling.config.CronTask ct) {
                    cronCount++;
                    cronTasks.add(String.format("  🕐 %s  cron=\"%s\"", taskDesc, ct.getExpression()));
                } else if (rawTask instanceof org.springframework.scheduling.config.FixedDelayTask fdt) {
                    fixedDelayCount++;
                    fixedDelayTasks.add(String.format("  ⏱ %s  every=%dms  initialDelay=%dms",
                            taskDesc, fdt.getInterval(), fdt.getInitialDelay()));
                } else if (rawTask instanceof org.springframework.scheduling.config.FixedRateTask frt) {
                    fixedRateCount++;
                    fixedRateTasks.add(String.format("  ⏲ %s  every=%dms", taskDesc, frt.getInterval()));
                }
            }
        }

        sb.append(String.format("總計: %d 個 scheduled task (cron=%d, fixedDelay=%d, fixedRate=%d)%n%n",
                total, cronCount, fixedDelayCount, fixedRateCount));

        if (!cronTasks.isEmpty()) {
            sb.append("— CRON tasks —\n");
            cronTasks.stream().sorted().forEach(t -> sb.append(t).append('\n'));
            sb.append('\n');
        }
        if (!fixedDelayTasks.isEmpty()) {
            sb.append("— FIXED DELAY tasks —\n");
            fixedDelayTasks.stream().sorted().forEach(t -> sb.append(t).append('\n'));
            sb.append('\n');
        }
        if (!fixedRateTasks.isEmpty()) {
            sb.append("— FIXED RATE tasks —\n");
            fixedRateTasks.stream().sorted().forEach(t -> sb.append(t).append('\n'));
        }

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.DIAGNOSTIC})
    @Tool(description = "Read-only compatibility alias for listSchedulers. Lists registered Spring @Scheduled tasks in the split trading service. No scheduler is triggered and no trading/OCO/order/fund/Earn state is changed.")
    public String listSchedulerTasks() {
        return listSchedulers();
    }

    private static String describeTarget(Object task) {
        Object runnable = null;
        try {
            if (task instanceof org.springframework.scheduling.config.Task t) {
                runnable = t.getRunnable();
            }
        } catch (Exception ignored) {}
        if (runnable == null) return task.getClass().getSimpleName();

        // Spring 6.1+ wraps in Task$OutcomeTrackingRunnable — unwrap until we find ScheduledMethodRunnable
        // Common delegate field names: "runnable", "delegate", "target"
        Object current = runnable;
        for (int depth = 0; depth < 5; depth++) {
            try {
                var targetM = current.getClass().getMethod("getTarget");
                var methodM = current.getClass().getMethod("getMethod");
                Object target = targetM.invoke(current);
                java.lang.reflect.Method method = (java.lang.reflect.Method) methodM.invoke(current);
                String targetName = target != null ? target.getClass().getSimpleName() : "?";
                int idx = targetName.indexOf("$$");  // strip CGLIB proxy suffix
                if (idx > 0) targetName = targetName.substring(0, idx);
                return targetName + "." + method.getName() + "()";
            } catch (NoSuchMethodException e) {
                // Not a ScheduledMethodRunnable — try to unwrap
                Object next = tryUnwrap(current);
                if (next == null || next == current) break;
                current = next;
            } catch (Exception e) {
                break;
            }
        }
        return runnable.getClass().getSimpleName();
    }

    private static Object tryUnwrap(Object obj) {
        for (String fieldName : new String[]{"runnable", "delegate", "target"}) {
            try {
                java.lang.reflect.Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val != null && val != obj) return val;
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                return null;
            }
        }
        // Try superclass fields
        Class<?> superClass = obj.getClass().getSuperclass();
        if (superClass != null && superClass != Object.class) {
            for (String fieldName : new String[]{"runnable", "delegate", "target"}) {
                try {
                    java.lang.reflect.Field f = superClass.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null && val != obj) return val;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.GOVERNANCE, Category.REPORTING})
    @Tool(description = "手動觸發 Attention Rule 週報(即時產生摘要,不等排程)。" +
            "sendToTg=true 時同步發 Telegram,false 則只回傳內容不發 TG(預覽用)。")
    public String triggerAttentionDigest(Boolean sendToTg) {
        boolean send = Boolean.TRUE.equals(sendToTg);
        String digest = attentionWeeklyDigest.buildDigest(LocalDateTime.now(ZoneOffset.UTC));
        if (send) {
            try {
                notificationPort.broadcast(digest, true);
            } catch (Exception e) {
                return "⚠️ TG 發送失敗 (" + e.getMessage() + "),但已生成 digest:\n\n" + digest;
            }
            return "✅ 已發送 TG + 回傳內容:\n\n" + digest;
        }
        return "📋 預覽模式 (未發 TG):\n\n" + digest;
    }

    // =========================================================================
    // Market Flip (Phase 2A — shadow mode + AI-assisted decision preview)
    // =========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.GOVERNANCE, Category.MARKET_DATA})
    @Tool(description = "列出待處理的 MarketFlipEvent (status=PENDING)。" +
            "Claude scheduled task 每 15 分鐘醒來時先跑此工具拿 pending 清單,再分析每筆。" +
            "param: limit(預設 20,上限 100)")
    public String listPendingFlipEvents(Integer limit) {
        int n = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;
        java.util.List<com.agora.model.MarketFlipEvent> events = marketFlipEventRepository
                .findByStatusOrderByDetectedAtAsc("PENDING",
                        org.springframework.data.domain.PageRequest.of(0, n));
        if (events.isEmpty()) return "ℹ️ 目前無 PENDING 的 MarketFlipEvent";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Pending Market Flip Events (%d 筆) ===%n", events.size()));
        for (var e : events) {
            long ageMinutes = java.time.Duration.between(e.getDetectedAt(),
                    LocalDateTime.now(ZoneOffset.UTC)).toMinutes();
            sb.append(String.format("%n[%d] %s / %s%n", e.getId(), e.getSymbol(), e.getIndicator()));
            sb.append(String.format("  %s → %s  (Δ=%s, crossed=%s)%n",
                    e.getPrevValue().toPlainString(),
                    e.getCurrentValue().toPlainString(),
                    e.getDeltaValue().toPlainString(),
                    e.getThresholdCrossed() != null ? e.getThresholdCrossed() : "(delta only)"));
            sb.append(String.format("  detected: %s UTC (%d 分鐘前)%n",
                    e.getDetectedAt().format(FMT), ageMinutes));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "標記 MarketFlipEvent 為已審閱(Phase 2A 簡化版:Claude 直接決定 dismiss/alert)。" +
            "Phase 2B 會由多 AI 並行分析自動產生 decision。" +
            "params: eventId, decision (DISMISS/ALERT), reasoning (必填)")
    public String reviewFlipEvent(Long eventId, String decision, String reasoning) {
        { String _e = McpParamValidator.requireNonNull(eventId, "eventId"); if (_e != null) return _e; }
        if (decision == null || (!"DISMISS".equals(decision) && !"ALERT".equals(decision))) {
            return "❌ decision 必須是 DISMISS 或 ALERT";
        }
        { String _e = McpParamValidator.requireNonBlank(reasoning, "reasoning"); if (_e != null) return _e; }

        var event = marketFlipEventRepository.findById(eventId).orElse(null);
        if (event == null) return "❌ Event " + eventId + " 不存在";
        if (!"PENDING".equals(event.getStatus())) {
            return String.format("ℹ️ Event %d 狀態已是 %s,無需重複審閱", eventId, event.getStatus());
        }

        event.setStatus("REVIEWED");
        event.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        marketFlipEventRepository.save(event);

        String logLine = String.format("event=%d %s/%s decision=%s reason=%s",
                eventId, event.getSymbol(), event.getIndicator(), decision, reasoning);
        auditWriter.logOverrideApplied(null, event.getSymbol(), "MarketFlip.Review", logLine);

        if ("ALERT".equals(decision)) {
            try {
                notificationPort.broadcast(String.format(
                        "📊 <b>Market Flip 分析</b> — %s%n" +
                        "<code>%s</code> %s→%s%n" +
                        "💬 %s",
                        event.getSymbol(), event.getIndicator(),
                        event.getPrevValue().toPlainString(),
                        event.getCurrentValue().toPlainString(),
                        reasoning), true);
            } catch (Exception e) {
                log.warn("[MarketFlip] TG send failed: {}", e.getMessage());
            }
        }
        return String.format("✅ Event %d reviewed as %s%n   reasoning: %s",
                eventId, decision, reasoning);
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE, Category.WRITE_TRADING})
    @Tool(description = "調整 MarketIndicatorFlipDetector 的門檻設定。變更寫入 market_flip_config_audit 表供追溯。" +
            "param: symbol, indicator(fear_greed/whale_buy_ratio), " +
            "thresholdLo, thresholdHi, deltaThreshold (任一 null 表示不改), reason(必填)")
    public String tuneFlipThreshold(String symbol, String indicator,
                                     Double thresholdLo, Double thresholdHi, Double deltaThreshold,
                                     String reason) {
        if (symbol == null || indicator == null) return "❌ symbol + indicator 為必填";
        { String _e = McpParamValidator.requireNonBlank(reason, "reason"); if (_e != null) return _e; }

        var cfg = marketFlipConfigRepository.findBySymbolAndIndicator(symbol, indicator).orElse(null);
        if (cfg == null) return "❌ (" + symbol + ", " + indicator + ") 的 config 不存在";

        // 記舊值
        Map<String, Object> oldVal = new LinkedHashMap<>();
        oldVal.put("threshold_lo", cfg.getThresholdLo() != null ? cfg.getThresholdLo().doubleValue() : null);
        oldVal.put("threshold_hi", cfg.getThresholdHi() != null ? cfg.getThresholdHi().doubleValue() : null);
        oldVal.put("delta_threshold", cfg.getDeltaThreshold() != null ? cfg.getDeltaThreshold().doubleValue() : null);

        // 改值
        if (thresholdLo != null) cfg.setThresholdLo(java.math.BigDecimal.valueOf(thresholdLo));
        if (thresholdHi != null) cfg.setThresholdHi(java.math.BigDecimal.valueOf(thresholdHi));
        if (deltaThreshold != null) cfg.setDeltaThreshold(java.math.BigDecimal.valueOf(deltaThreshold));
        cfg.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        marketFlipConfigRepository.save(cfg);

        // 寫 audit (透過 auditWriter 統一入 bt_decision_audit;Phase 2C 專屬 config_audit 表之後補)
        Map<String, Object> newVal = new LinkedHashMap<>();
        newVal.put("threshold_lo", cfg.getThresholdLo() != null ? cfg.getThresholdLo().doubleValue() : null);
        newVal.put("threshold_hi", cfg.getThresholdHi() != null ? cfg.getThresholdHi().doubleValue() : null);
        newVal.put("delta_threshold", cfg.getDeltaThreshold() != null ? cfg.getDeltaThreshold().doubleValue() : null);
        String auditReason = String.format("symbol=%s indicator=%s old=%s new=%s reason=%s",
                symbol, indicator, oldVal, newVal, reason);
        auditWriter.logOverrideApplied(null, symbol, "MarketFlip.Tune", auditReason);

        return String.format("✅ %s/%s 門檻已更新%n   舊: %s%n   新: %s%n   reason: %s",
                symbol, indicator, oldVal, newVal, reason);
    }

    @McpAuth(McpAuthLevel.OPS)
    @Tool(description = "列出最近 N 小時被 DataQualityMonitor flag 為 anomalous 的 MarketFlipEvent。" +
            "判斷規則:單次變化過大 / 3h 內連續震盪 / 值超出合理範圍。用於審查資料品質," +
            "若發現大量 anomalous 可考慮升 flip 門檻或調 upstream 聚合視窗。" +
            "param: hours(預設 24,上限 168 = 7d)")
    public String listRecentAnomalies(Integer hours) {
        int h = hours != null ? Math.min(Math.max(hours, 1), 168) : 24;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(h);

        List<com.agora.model.MarketFlipEvent> events =
                marketFlipEventRepository.findAnomalousSince(since, 200);
        if (events.isEmpty()) {
            return String.format("✅ 最近 %d 小時無 anomalous flip events (資料品質良好)", h);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Recent Anomalous Flips (%d 筆, 最近 %d 小時) ===%n", events.size(), h));
        for (var e : events) {
            long ageMin = Duration.between(e.getDetectedAt(), LocalDateTime.now(ZoneOffset.UTC)).toMinutes();
            String reasons = extractAnomalyReasons(e.getContextJson());
            sb.append(String.format("%n[%d] %s / %s  status=%s%n",
                    e.getId(), e.getSymbol(), e.getIndicator(), e.getStatus()));
            sb.append(String.format("  %s → %s  (Δ=%s)%n",
                    e.getPrevValue().toPlainString(),
                    e.getCurrentValue().toPlainString(),
                    e.getDeltaValue().toPlainString()));
            sb.append(String.format("  detected: %s UTC (%d 分鐘前)%n",
                    e.getDetectedAt().format(FMT), ageMin));
            sb.append(String.format("  reasons: %s%n", reasons));
        }
        return sb.toString();
    }

    /**
     * Pretty-print context_json. V2 schema (from DecisionContextBuilder) gets
     * a structured breakdown; v1 sparse JSON falls back to raw display.
     */
    private void renderContextJson(StringBuilder sb, String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            sb.append("context_json : -\n");
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(contextJson);
            if (!com.agora.service.meta.DecisionContextBuilder.isV2(root)) {
                // v1 legacy — just dump raw
                sb.append("context_json : ").append(contextJson).append("  [v1 legacy]\n");
                return;
            }
            sb.append("context_json : v").append(root.path("version").asInt()).append("\n");
            appendCategory(sb, root, "indicators",   "  Indicators  ");
            appendCategory(sb, root, "sentiment",    "  Sentiment   ");
            appendCategory(sb, root, "regime",       "  Regime      ");
            appendCategory(sb, root, "strategy",     "  Strategy    ");
            appendCategory(sb, root, "execution",    "  Execution   ");
            appendCategory(sb, root, "data_quality", "  DataQuality ");
            com.fasterxml.jackson.databind.JsonNode extras = root.path("extras");
            if (extras.isObject() && !extras.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode strategyDecision = extras.path("strategy_decision");
                if (strategyDecision.isObject() && !strategyDecision.isEmpty()) {
                    appendObject(sb, strategyDecision, "  StrategyDecision");
                }
                com.fasterxml.jackson.databind.JsonNode ensemble = extras.path("ensemble");
                if (ensemble.isObject() && !ensemble.isEmpty()) {
                    appendObject(sb, ensemble, "  Ensemble    ");
                }
            }
            com.fasterxml.jackson.databind.JsonNode filters = root.path("filters");
            if (filters.isArray() && filters.size() > 0) {
                sb.append("  Filters     :\n");
                for (com.fasterxml.jackson.databind.JsonNode f : filters) {
                    sb.append(String.format("    - %s [%s]%n",
                            f.path("name").asText("?"), f.path("outcome").asText("?")));
                    com.fasterxml.jackson.databind.JsonNode rules = f.path("rules");
                    if (rules.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode r : rules) {
                            sb.append(String.format("        %s %s: %s%n",
                                    r.path("pass").asBoolean() ? "✓" : "✗",
                                    r.path("id").asText("?"),
                                    r.path("detail").asText("")));
                        }
                    }
                    String reason = f.path("reason").asText("");
                    if (!reason.isBlank()) sb.append("        reason: ").append(reason).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("context_json : ").append(contextJson).append("  [parse err: ").append(e.getMessage()).append("]\n");
        }
    }

    private void appendCategory(StringBuilder sb,
                                com.fasterxml.jackson.databind.JsonNode root,
                                String field, String label) {
        com.fasterxml.jackson.databind.JsonNode node = root.path(field);
        if (!node.isObject() || node.isEmpty()) return;
        appendObject(sb, node, label);
    }

    private void appendObject(StringBuilder sb,
                              com.fasterxml.jackson.databind.JsonNode node,
                              String label) {
        sb.append(label).append(": ");
        boolean first = true;
        java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("=").append(compactJsonValue(e.getValue()));
            first = false;
        }
        sb.append("\n");
    }

    private String compactJsonValue(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        return value.toString();
    }

    /** 從 context_json 解出 anomaly_reasons 陣列,失敗回傳 "(unparseable)"。 */
    private String extractAnomalyReasons(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return "(empty context)";
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(contextJson);
            com.fasterxml.jackson.databind.JsonNode reasons = node.path("anomaly_reasons");
            if (reasons.isArray() && reasons.size() > 0) {
                List<String> list = new java.util.ArrayList<>();
                reasons.forEach(n -> list.add(n.asText()));
                return String.join(", ", list);
            }
        } catch (Exception ignored) {
            // 落回
        }
        return "(unparseable)";
    }

    // =========================================================================
    // 觀測類 — 一眼看全局 Meta-Control 狀態
    // =========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.GOVERNANCE, Category.READ_TRADING})
    @Tool(description = "列出當前生效的所有 Meta-Control override:strategy PAUSE/TWEAK + hint override + attention rule。" +
            "一眼看全局狀態,Claude 每次協作開始前應先跑這個。")
    public String listActiveOverrides() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<StrategyOverride> strategies = strategyOverrideRepository.findAllActive(now);
        List<HintOverride>    hints      = hintOverrideRepository.findAllActive(now);
        List<AttentionRule>   rules      = attentionRuleRepository.findActive(now);

        if (strategies.isEmpty() && hints.isEmpty() && rules.isEmpty()) {
            return "ℹ️ 當前無任何 active override(系統照原策略自動交易)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Active Meta-Control State ===\n\n");

        sb.append("▸ Strategy Overrides (").append(strategies.size()).append(")\n");
        for (StrategyOverride ov : strategies) {
            long remainMin = java.time.Duration.between(now, ov.getExpiresAt()).toMinutes();
            sb.append(String.format("  [%d] strategy=%d %s scope=(%s,%s) by=%s 剩 %dh%02dm  reason=%s%n",
                    ov.getId(), ov.getStrategyId(), ov.getAction(),
                    ov.getSymbol() != null ? ov.getSymbol() : "*",
                    ov.getIntervalCode() != null ? ov.getIntervalCode() : "*",
                    ov.getCreatedBy(), remainMin / 60, remainMin % 60, ov.getReason()));
        }
        sb.append("\n▸ Hint Overrides (").append(hints.size()).append(")\n");
        for (HintOverride h : hints) {
            long remainMin = java.time.Duration.between(now, h.getExpiresAt()).toMinutes();
            sb.append(String.format("  [%d] %s@%s style=%s sl×=%s tp×=%s allowShort=%s 剩 %dh%02dm  reason=%s%n",
                    h.getId(), h.getSymbol(), h.getTimeframe(),
                    h.getStyleHint() != null ? h.getStyleHint() : "-",
                    h.getSlMultiplier() != null ? h.getSlMultiplier() : "-",
                    h.getTpMultiplier() != null ? h.getTpMultiplier() : "-",
                    h.getAllowShort() != null ? h.getAllowShort() : "-",
                    remainMin / 60, remainMin % 60, h.getReason()));
        }
        sb.append("\n▸ Attention Rules (").append(rules.size()).append(")\n");
        for (AttentionRule r : rules) {
            String exp = r.getExpiresAt() != null
                    ? r.getExpiresAt().format(FMT) + " UTC"
                    : "永久";
            sb.append(String.format("  [%d] %s %s/%s hits=%d last=%s expires=%s%n    predicate=%s%n",
                    r.getId(), r.getName(), r.getAction(), r.getSeverity(),
                    r.getHitCount(),
                    r.getLastHitAt() != null ? r.getLastHitAt().format(FMT) : "-",
                    exp, r.getPredicateJson()));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#235 信號全鏈路追蹤：給定 liveSignalId，顯示該信號從技術觸發到最終結果的完整決策鏈路。" +
            "包括：策略名稱、技術指標值、RegimeFilter 結果、ML gate 結果（p_win）、最終 PASS/BLOCK/TRADE。" +
            "用於快速理解「這個信號為什麼被擋了」或「這個交易是怎麼觸發的」。" +
            "param: liveSignalId（必填，從 getOpenPositions 或 listRecentDecisions 取得）")
    public String getSignalDecisionTrace(Long liveSignalId) {
        { String _e = com.agora.mcp.util.McpParamValidator.requireNonNull(liveSignalId, "liveSignalId"); if (_e != null) return _e; }
        try {
            // Get live signal
            var signalOpt = liveSignalRepository.findById(liveSignalId);
            if (signalOpt.isEmpty()) return "❌ Live signal #" + liveSignalId + " 不存在";
            var signal = signalOpt.get();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Signal Decision Trace #%d ===\n", liveSignalId));
            sb.append(String.format("策略: #%d\n", signal.getStrategyId()));
            sb.append(String.format("幣種: %s %s %s\n", signal.getSymbol(),
                    signal.getIntervalCode(), signal.getSide()));
            sb.append(String.format("訊號時間: %s\n\n", signal.getCreatedAt()));

            // Show key technical features from score/context
            if (signal.getScore() != null)
                sb.append(String.format("📊 技術評分: %.3f\n", signal.getScore().doubleValue()));
            if (signal.getFilterReason() != null)
                sb.append(String.format("🚫 Filter: %s\n", signal.getFilterReason()));

            // Get audit trail
            List<BtDecisionAudit> audits = decisionAuditRepository.findByLiveSignalIdOrderByEventTimeAsc(liveSignalId);
            if (!audits.isEmpty()) {
                sb.append("\n--- 決策鏈路 ---\n");
                for (BtDecisionAudit a : audits) {
                    String icon = switch (a.getEventType()) {
                        case "SIGNAL_EVAL" -> "🔍";
                        case "FILTER_BLOCK" -> "🚫";
                        case "AUTOTRADE_OK" -> "✅";
                        case "AUTOTRADE_FAIL" -> "❌";
                        case "EXIT" -> "🏁";
                        case "OVERRIDE_APPLIED" -> "⚡";
                        default -> "ℹ️";
                    };
                    sb.append(String.format("%s [%s] %s\n", icon, a.getEventType(),
                            a.getBlocker() != null ? "blocker=" + a.getBlocker() : ""));
                    if (a.getReason() != null && !a.getReason().isBlank())
                        sb.append(String.format("   reason: %s\n", a.getReason()));
                }
            }

            // Final outcome
            sb.append("\n--- 最終結果 ---\n");
            if (Boolean.TRUE.equals(signal.getAutoTraded())) {
                sb.append("✅ 自動下單\n");
                if (signal.getExitTime() != null) {
                    sb.append(String.format("🏁 已平倉 @ %s\n", signal.getExitTime()));
                    if (signal.getRealizedPnl() != null)
                        sb.append(String.format("   PnL: %+.2f USDT\n", signal.getRealizedPnl().doubleValue()));
                } else {
                    sb.append("📦 仍持倉中\n");
                }
            } else if (signal.getFilterReason() != null) {
                sb.append("🚫 被 Filter 攔截，未下單\n");
            } else {
                sb.append("ℹ️ 僅評估（HOLD 或 shadow mode）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpCategory({Category.ANALYTICS, Category.GOVERNANCE})
    @Tool(description = "列出最近 N 分鐘的決策審計(signal 評估 / 過濾 / 下單 / 平倉 / override)。" +
            "可按 symbol 過濾。顯示 event_type/outcome/blocker/reason,便於 Claude 排查異常。" +
            "params: minutes(1~1440,預設 60), symbol(可選), limit(1~200,預設 30；超過會 cap 到 200)")
    public String listRecentDecisions(Integer minutes, String symbol, Integer limit) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 60 : minutes;
        int lim = normalizeRecentDecisionsLimit(limit);
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                since, sym, org.springframework.data.domain.PageRequest.of(0, lim));

        if (audits.isEmpty()) {
            return String.format("ℹ️ 近 %d 分鐘%s無任何決策記錄",
                    m, sym != null ? " (" + sym + ")" : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Recent Decisions (近 %dmin, %d 筆) ===%n%n", m, audits.size()));
        for (BtDecisionAudit a : audits) {
            // #443 Gap 1 — SIGNAL_EVAL 用 reason(BUY/SELL/HOLD)選 icon,而非全部歸 ℹ️。
            // 原本 BUY signal 進 audit 但 listRecentDecisions 看不出 — 看起來像「audit 沒寫」。
            String icon;
            if ("SIGNAL_EVAL".equals(a.getEventType()) && a.getReason() != null) {
                String side = a.getReason().toUpperCase();
                icon = switch (side) {
                    case "BUY"  -> "🟢";
                    case "SELL" -> "🔴";
                    case "HOLD" -> "⚪";
                    default     -> "ℹ️";
                };
            } else {
                icon = switch (a.getOutcome()) {
                    case "BLOCKED" -> "🚫";
                    case "ERROR"   -> "❌";
                    case "PASS"    -> "✅";
                    default         -> "ℹ️";
                };
            }
            sb.append(String.format("%s [%d] %s %s/%s  %s%s  %s%s%n",
                    icon, a.getId(), a.getEventTime().format(FMT),
                    a.getEventType(), a.getOutcome(),
                    a.getSymbol() != null ? a.getSymbol() : "-",
                    a.getIntervalCode() != null ? "@" + a.getIntervalCode() : "",
                    a.getBlocker() != null ? "blocker=" + a.getBlocker() + " " : "",
                    a.getReason() != null ? a.getReason() : ""));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING})
    @Tool(description = "#476 POC event-scan outbound hook preview. Read-only/manual only: " +
            "builds a compact JSON payload for operators to paste into Codex; it does not send TG/Slack/email/webhook " +
            "and does not enable a scheduler. params: minutes(1~1440, default 90), symbol(optional)")
    public String previewEventScanNotification(Integer minutes, String symbol) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 90 : minutes;
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                since, sym, org.springframework.data.domain.PageRequest.of(0, RECENT_DECISIONS_MAX_LIMIT));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        payload.put("windowMinutes", m);
        payload.put("symbol", sym != null ? sym : "ALL");

        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (BtDecisionAudit audit : audits) {
            events.add(toEventScanItem(audit));
        }
        payload.put("events", events);
        payload.put("operatorSummary", summarizeEntryDedupCluster(audits).toPayload());

        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("dryRun", true);
        transport.put("outboundEnabled", false);
        transport.put("mcpFailures", 0);
        transport.put("affectedTools", List.of());
        transport.put("operatorAction", "paste_this_payload_into_codex_thread");
        payload.put("transport", transport);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return "❌ event-scan preview JSON render failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#476 手動觸發 event-scan notification。dryRun=true 只預覽不發送；dryRun=false 會透過 Telegram 頻道送出並附 #501 drill-down buttons。" +
            "不修改交易/策略/OCO/資金。params: minutes(1~1440, default 90), symbol(optional), maxEvents(1~50, default 12), dryRun(default true)")
    public String sendEventScanNotification(Integer minutes, String symbol, Integer maxEvents, Boolean dryRun) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 90 : minutes;
        int max = normalizeEventScanMaxEvents(maxEvents);
        boolean previewOnly = dryRun == null || dryRun;
        String message = buildEventScanNotificationMessage(m, symbol, RECENT_DECISIONS_MAX_LIMIT, max);

        if (previewOnly) {
            return "DRY_RUN: event-scan notification not sent\n\n" + message;
        }

        sendEventScanTelegramMessage(message, symbol, m);
        return "SENT: event-scan notification sent with Telegram drill-down buttons\n\n" + message;
    }

    public String sendScheduledEventScanNotification(Integer minutes, String symbol, Integer scanLimit,
                                                     Integer maxEvents, boolean dryRun) {
        return sendScheduledEventScanNotification(minutes, symbol, scanLimit, maxEvents, 0, dryRun);
    }

    public String sendScheduledEventScanNotification(Integer minutes, String symbol, Integer scanLimit,
                                                     Integer maxEvents, Integer suppressRepeatMinutes,
                                                     boolean dryRun) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 90 : minutes;
        int scan = normalizeRecentDecisionsLimit(scanLimit);
        int max = normalizeEventScanMaxEvents(maxEvents);
        int suppressMinutes = suppressRepeatMinutes == null || suppressRepeatMinutes < 0
                ? 0 : Math.min(suppressRepeatMinutes, 1440);
        String message = buildEventScanNotificationMessage(m, symbol, scan, max);

        if (dryRun) {
            log.info("[EventScanNotification] dryRun=true; message:\n{}", message);
            return "DRY_RUN";
        }

        String noActionFingerprint = eventScanNoActionFingerprint(message);
        if (noActionFingerprint != null
                && noActionFingerprint.equals(lastScheduledEventScanNoActionFingerprint)) {
            log.info("[EventScanNotification] skipped repeated no-action fingerprint={}", noActionFingerprint);
            return "SKIPPED: repeated no-action Event Scan fingerprint=" + noActionFingerprint;
        }

        String noActionThrottleKey = eventScanNoActionThrottleKey(message);
        String sendSource = "EventScanNotification";
        if (suppressMinutes > 0 && noActionThrottleKey != null) {
            if (hasRecentLegacyEventScanNotification(suppressMinutes)) {
                log.info("[EventScanNotification] throttled no-action key={} suppressMinutes={} legacySourceHit=true",
                        noActionThrottleKey, suppressMinutes);
                return "SKIPPED: throttled no-action Event Scan key=" + noActionThrottleKey
                        + " suppressMinutes=" + suppressMinutes;
            }
            String dedupKey = "EventScanNotification:" + noActionThrottleKey;
            if (!tgNotificationDeduper.shouldSend(
                    dedupKey,
                    Duration.ofMinutes(suppressMinutes),
                    TgNotificationDeduper.Severity.FYI)) {
                log.info("[EventScanNotification] throttled no-action key={} suppressMinutes={}",
                        noActionThrottleKey, suppressMinutes);
                return "SKIPPED: throttled no-action Event Scan key=" + noActionThrottleKey
                        + " suppressMinutes=" + suppressMinutes;
            }
            sendSource = dedupKey;
        }

        sendEventScanTelegramMessage(message, symbol, m, sendSource);
        lastScheduledEventScanNoActionFingerprint = noActionFingerprint;
        log.info("[EventScanNotification] Telegram keyboard message sent ({} chars)", message.length());
        return "SENT";
    }

    private boolean hasRecentLegacyEventScanNotification(int suppressMinutes) {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(suppressMinutes);
            return tgNotificationLogRepo.findLatestSentAtBySourceAfter("EventScanNotification", cutoff) != null;
        } catch (Exception e) {
            log.debug("[EventScanNotification] legacy cooldown lookup failed: {}", e.getMessage());
            return false;
        }
    }

    static String eventScanNoActionFingerprint(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        boolean blockedBuyPressureMessage = isBlockedBuyPressureMessage(message);
        if (!blockedBuyPressureMessage) {
            return null;
        }
        String countsLine = message.lines()
                .filter(line -> line.startsWith("counts="))
                .findFirst()
                .orElse("");
        if (countsLine.contains("FILTER_BLOCK")
                || countsLine.contains("TRADE")
                || countsLine.contains("ERROR")
                || countsLine.contains("ANOMALY")
                || countsLine.contains("STALE")) {
            return null;
        }
        return message.lines()
                .filter(line -> !line.startsWith("generatedAtUtc="))
                .filter(line -> !line.startsWith("timeTaipei="))
                .filter(line -> !line.startsWith("counts="))
                .filter(line -> !line.startsWith("reason="))
                .filter(line -> !line.startsWith("時間："))
                .filter(line -> !line.startsWith("摘要："))
                .filter(line -> !line.startsWith("原因："))
                .filter(line -> !line.startsWith("Operator action:"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(java.util.stream.Collectors.joining("|"));
    }

    static String eventScanNoActionThrottleKey(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        boolean blockedBuyPressureMessage = isBlockedBuyPressureMessage(message);
        if (!blockedBuyPressureMessage) {
            return null;
        }
        String countsLine = message.lines()
                .filter(line -> line.startsWith("counts=") || line.startsWith("摘要："))
                .findFirst()
                .orElse("");
        if (countsLine.contains("FILTER_BLOCK")
                || countsLine.contains("Filter ")
                || countsLine.contains("TRADE")
                || countsLine.contains("Trade ")
                || countsLine.contains("ERROR")
                || countsLine.contains("Error ")
                || countsLine.contains("ANOMALY")
                || countsLine.contains("STALE")
                || countsLine.contains("Stale ")) {
            return null;
        }
        String header = message.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("[事件掃描]")
                        || line.startsWith("[Event Scan]")
                        || (line.startsWith("【") && line.contains("事件掃描")))
                .findFirst()
                .orElse("event-scan");
        return "blocked_buy_pressure|" + header;
    }

    private static boolean isBlockedBuyPressureMessage(String message) {
        return message.contains("event=blocked_buy_pressure")
                || message.contains("事件：買壓已阻擋")
                || (message.contains("EVENT_SCAN / NO_ACTION")
                && message.contains("EntryDedup"));
    }

    private void sendEventScanTelegramMessage(String message, String symbol, int minutes) {
        sendEventScanTelegramMessage(message, symbol, minutes, "EventScanNotification");
    }

    private void sendEventScanTelegramMessage(String message, String symbol, int minutes, String source) {
        telegramService.sendChannelMessageWithKeyboard(
                message,
                false,
                EventScanTelegramButtons.buildKeyboard(symbol, minutes),
                source,
                "INFO");
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#501 read-only Event Scan drill-down backend for future TG inline buttons. " +
            "Does not send TG and does not modify trading/OCO/strategy/funds. " +
            "params: minutes(1~1440, default 90), symbol(optional), detailType=buy_details|skip_reasons|full_scan, limit(1~50, default 20)")
    public String getEventScanDrillDown(Integer minutes, String symbol, String detailType, Integer limit) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 90 : minutes;
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();
        String type = normalizeEventScanDetailType(detailType);
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                since, sym, org.springframework.data.domain.PageRequest.of(0, RECENT_DECISIONS_MAX_LIMIT));
        EventScanEntryDedupSummary dedupSummary = summarizeEntryDedupCluster(audits);

        List<BtDecisionAudit> rows = audits.stream()
                .filter(audit -> switch (type) {
                    case "buy_details" -> "BUY".equals(classifyEventScanType(audit));
                    case "skip_reasons" -> "ENTRY_SKIP".equals(classifyEventScanType(audit));
                    default -> isNotableEventScanAudit(audit);
                })
                .limit(size)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Event Scan Drill-down (#501) ===\n")
                .append("symbol=").append(sym != null ? sym : "ALL")
                .append(" | windowMinutes=").append(m)
                .append(" | detailType=").append(type)
                .append(" | mode=READ_ONLY")
                .append(" | rows=").append(rows.size()).append("/")
                .append(audits.size()).append("\n");
        sb.append("operatorSummary: ")
                .append("buyEvaluations=").append(dedupSummary.buyEvaluations)
                .append(" entryDedupSkips=").append(dedupSummary.entryDedupSkips)
                .append(" category=").append(dedupSummary.category)
                .append(" action=").append(dedupSummary.operatorAction)
                .append("\n\n");

        if (rows.isEmpty()) {
            sb.append("No rows matched this drill-down.\n");
        } else {
            int idx = 1;
            for (BtDecisionAudit audit : rows) {
                sb.append(idx++).append(". ")
                        .append(audit.getEventTime() != null ? audit.getEventTime().format(FMT) : "-")
                        .append(" UTC ")
                        .append(renderEventScanDetail(audit))
                        .append(" auditId=").append(audit.getId());
                if (audit.getStrategyId() != null) {
                    sb.append(" strategyId=").append(audit.getStrategyId());
                }
                if (audit.getLiveSignalId() != null) {
                    sb.append(" liveSignalId=").append(audit.getLiveSignalId());
                }
                sb.append("\n");
            }
        }
        sb.append("\nNo TG was sent. No trading, OCO, strategy, grid order, or fund behavior was changed.");
        return truncateForTelegram(sb.toString());
    }

    static int normalizeRecentDecisionsLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return RECENT_DECISIONS_DEFAULT_LIMIT;
        }
        return Math.min(limit, RECENT_DECISIONS_MAX_LIMIT);
    }

    static int normalizeEventScanMaxEvents(Integer maxEvents) {
        if (maxEvents == null || maxEvents <= 0) {
            return 12;
        }
        return Math.min(maxEvents, 50);
    }

    static String normalizeEventScanDetailType(String detailType) {
        if (detailType == null || detailType.isBlank()) {
            return "full_scan";
        }
        String value = detailType.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "buy", "buy_details", "buy_details_button" -> "buy_details";
            case "skip", "skip_reason", "skip_reasons", "entry_skip" -> "skip_reasons";
            case "full", "full_scan", "scan" -> "full_scan";
            default -> "full_scan";
        };
    }

    private String buildEventScanNotificationMessage(Integer minutes, String symbol, int scanLimit, int maxEvents) {
        int m = (minutes == null || minutes <= 0 || minutes > 1440) ? 90 : minutes;
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
        List<BtDecisionAudit> audits = decisionAuditRepository.findRecent(
                since, sym, org.springframework.data.domain.PageRequest.of(0, scanLimit));

        Map<String, Long> counts = audits.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        MetaControlMcpTools::classifyEventScanType,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));

        List<BtDecisionAudit> notable = audits.stream()
                .filter(this::isNotableEventScanAudit)
                .limit(maxEvents)
                .toList();

        EventScanEntryDedupSummary dedupSummary = summarizeEntryDedupCluster(audits);
        boolean blockedBuyPressure = dedupSummary.hasEntryDedupCluster()
                && !hasActionableEventScanIncident(counts);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(sym != null ? sym : "ALL").append("｜事件掃描摘要】\n");
        sb.append("期間：近 ").append(m).append(" 分鐘\n");
        sb.append("時間：").append(LocalDateTime.now(TAIPEI_ZONE).format(FMT)).append(" 台北\n");
        sb.append("摘要：").append(renderEventScanCountsSummary(counts, dedupSummary)).append("\n");

        if (blockedBuyPressure) {
            sb.append("狀態：")
                    .append(dedupSummary.fullyBlockedByEntryDedup
                            ? "HOLD（已有 LONG 曝險；無新下單事件）"
                            : "HOLD/觀察（買壓已被保護擋下；非加倉訊號）")
                    .append("\n");
            sb.append("原因：BUY ").append(dedupSummary.buyEvaluations)
                    .append(" / Skip ").append(dedupSummary.entrySkips)
                    .append(" / EntryDedup ").append(dedupSummary.entryDedupSkips)
                    .append("；EntryDedup 表示既有 LONG 已承載曝險，DuplicateBar 表示同一根 K 已處理。\n");
            sb.append("處置：").append(renderEventScanOperatorActionZh(dedupSummary)).append("\n");
            sb.append("細節：用下方按鈕查 BUY 明細 / Skip 原因 / 倉位 / OCO / Trailing；按鈕只查詢，不交易。\n");
            sb.append("標籤：EVENT_SCAN / NO_ACTION / WATCH");
            return truncateForTelegram(sb.toString());
        }

        sb.append("狀態：").append(notable.isEmpty() ? "大多安靜" : "需要檢查").append("\n");
        sb.append("處置：").append(notable.isEmpty()
                ? "觀察即可，無需手動操作"
                : "需要檢查 drill-down，不要直接交易").append("\n\n");

        if (notable.isEmpty()) {
            sb.append("本窗口沒有 BUY / skip / block / trade / error / stale 事件。\n");
        } else {
            sb.append("重點：").append(renderEventScanCompactHighlights(audits, notable)).append("\n");
            BtDecisionAudit primary = pickPrimaryEventScanAudit(notable);
            if (primary != null) {
                sb.append("最近重點：").append(renderEventScanCompactEvent(primary)).append("\n");
            }
            sb.append("細節：用下方按鈕查 BUY 明細 / Skip 原因 / 倉位 / OCO / Trailing；按鈕只查詢，不交易。\n");
        }
        if (dedupSummary.hasEntryDedupCluster()) {
            sb.append("\n買壓處置：").append(renderEventScanOperatorActionZh(dedupSummary));
        } else {
            sb.append("\n需要深挖時，用下方 drill-down 按鈕；不要把摘要直接當交易指令。");
        }
        sb.append("\n標籤：EVENT_SCAN / ").append(notable.isEmpty() ? "NO_ACTION" : "REVIEW");
        return truncateForTelegram(sb.toString());
    }

    private String renderEventScanCompactHighlights(List<BtDecisionAudit> audits, List<BtDecisionAudit> notable) {
        List<String> parts = new ArrayList<>();
        long executions = audits.stream().filter(audit -> "TRADE".equals(classifyEventScanType(audit))).count();
        long errors = audits.stream().filter(audit -> "ERROR".equals(classifyEventScanType(audit))).count();
        long stale = audits.stream().filter(audit -> "STALE".equals(classifyEventScanType(audit))).count();
        long anomalies = audits.stream().filter(audit -> "ANOMALY".equals(classifyEventScanType(audit))).count();
        appendEventScanCount(parts, "執行/成交", executions);
        appendEventScanCount(parts, "錯誤", errors);
        appendEventScanCount(parts, "資料陳舊", stale);
        appendEventScanCount(parts, "異常", anomalies);

        List<String> topBlockers = topEventScanBlockers(notable);
        if (!topBlockers.isEmpty()) {
            parts.add("Top阻擋 " + String.join(" / ", topBlockers));
        }
        if (parts.isEmpty()) {
            return "有事件，但沒有交易/錯誤/高優先阻擋；用 drill-down 查看。";
        }
        return String.join("；", parts);
    }

    private List<String> topEventScanBlockers(List<BtDecisionAudit> notable) {
        Map<String, Long> counts = new LinkedHashMap<>();
        notable.stream()
                .filter(audit -> "ENTRY_SKIP".equals(classifyEventScanType(audit))
                        || "FILTER_BLOCK".equals(classifyEventScanType(audit)))
                .map(this::eventScanBlockerKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(key -> counts.merge(key, 1L, Long::sum));
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> e.getKey() + " × " + e.getValue())
                .toList();
    }

    private String eventScanBlockerKey(BtDecisionAudit audit) {
        if (audit.getBlocker() != null && !audit.getBlocker().isBlank()) {
            return audit.getBlocker().trim();
        }
        if (audit.getReason() != null && !audit.getReason().isBlank()) {
            String reason = audit.getReason().trim();
            int idx = reason.indexOf(':');
            if (idx >= 0 && idx + 1 < reason.length()) {
                reason = reason.substring(idx + 1);
            }
            return reason.length() > 48 ? reason.substring(0, 48) + "..." : reason;
        }
        return "UNKNOWN";
    }

    private BtDecisionAudit pickPrimaryEventScanAudit(List<BtDecisionAudit> notable) {
        return notable.stream()
                .filter(audit -> !classifyEventScanType(audit).equals("BUY"))
                .min((a, b) -> {
                    int rank = Integer.compare(eventScanPriority(a), eventScanPriority(b));
                    if (rank != 0) return rank;
                    LocalDateTime at = a.getEventTime();
                    LocalDateTime bt = b.getEventTime();
                    if (at == null && bt == null) return 0;
                    if (at == null) return 1;
                    if (bt == null) return -1;
                    return bt.compareTo(at);
                })
                .orElse(null);
    }

    private int eventScanPriority(BtDecisionAudit audit) {
        return switch (classifyEventScanType(audit)) {
            case "TRADE", "ERROR" -> 1;
            case "ANOMALY", "STALE" -> 2;
            case "FILTER_BLOCK" -> 3;
            case "ENTRY_SKIP" -> 4;
            default -> 9;
        };
    }

    private String renderEventScanCompactEvent(BtDecisionAudit audit) {
        StringBuilder sb = new StringBuilder();
        sb.append(audit.getEventTime() != null ? audit.getEventTime().format(FMT) + " UTC " : "");
        sb.append(classifyEventScanType(audit)).append(" ");
        sb.append(audit.getSymbol() != null ? audit.getSymbol() : "-");
        if (audit.getIntervalCode() != null && !audit.getIntervalCode().isBlank()) {
            sb.append("@").append(audit.getIntervalCode());
        }
        String blocker = eventScanBlockerKey(audit);
        if (blocker != null && !blocker.isBlank() && !"UNKNOWN".equals(blocker)) {
            sb.append(" blocker=").append(blocker);
        }
        sb.append(" auditId=").append(audit.getId());
        return sb.toString();
    }

    private String renderEventScanCountsSummary(Map<String, Long> counts, EventScanEntryDedupSummary dedupSummary) {
        if (counts.isEmpty()) {
            return "無";
        }
        List<String> parts = new ArrayList<>();
        appendEventScanCount(parts, "BUY", counts.get("BUY"));
        appendEventScanCount(parts, "Skip", counts.get("ENTRY_SKIP"));
        if (dedupSummary.entryDedupSkips > 0) {
            appendEventScanCount(parts, "EntryDedup", dedupSummary.entryDedupSkips);
        }
        appendEventScanCount(parts, "Filter", counts.get("FILTER_BLOCK"));
        appendEventScanCount(parts, "Trade", counts.get("TRADE"));
        appendEventScanCount(parts, "Error", counts.get("ERROR"));
        appendEventScanCount(parts, "Stale", counts.get("STALE"));
        appendEventScanCount(parts, "Signal", counts.get("SIGNAL_EVAL"));
        counts.forEach((key, value) -> {
            if (!java.util.Set.of("BUY", "ENTRY_SKIP", "FILTER_BLOCK", "TRADE", "ERROR", "STALE", "SIGNAL_EVAL")
                    .contains(key)) {
                appendEventScanCount(parts, key, value);
            }
        });
        return String.join(" / ", parts);
    }

    private void appendEventScanCount(List<String> parts, String label, Long value) {
        if (value != null && value > 0) {
            parts.add(label + " " + value);
        }
    }

    private String renderEventScanOperatorActionZh(EventScanEntryDedupSummary dedupSummary) {
        if (dedupSummary.fullyBlockedByEntryDedup) {
            return "暫不加倉；等待策略觸發，只在 OCO、倉位風險或真實成交狀態改變時檢查既有倉位。";
        }
        if (dedupSummary.entryDedupSkips > 0) {
            return "觀察；先看混合 blocker，不要把 EntryDedup 當新下單或加倉指令。";
        }
        return "觀察即可，無需手動操作。";
    }

    private boolean hasActionableEventScanIncident(Map<String, Long> counts) {
        return counts.containsKey("FILTER_BLOCK")
                || counts.containsKey("TRADE")
                || counts.containsKey("ERROR")
                || counts.containsKey("ANOMALY")
                || counts.containsKey("STALE");
    }

    private EventScanEntryDedupSummary summarizeEntryDedupCluster(List<BtDecisionAudit> audits) {
        long buyEvaluations = audits.stream()
                .filter(audit -> "BUY".equals(classifyEventScanType(audit)))
                .count();
        List<BtDecisionAudit> entrySkips = audits.stream()
                .filter(audit -> "ENTRY_SKIP".equals(classifyEventScanType(audit)))
                .toList();
        long entryDedupSkips = entrySkips.stream()
                .filter(this::isEntryDedupAudit)
                .count();
        boolean fullyBlocked = buyEvaluations > 0
                && entrySkips.size() == buyEvaluations
                && entryDedupSkips == entrySkips.size();
        String category = entryDedupSkips >= 3
                ? "dedup_repeated_buy_pressure"
                : entryDedupSkips > 0 ? "dedup_normal" : "none";
        String operatorAction = fullyBlocked
                ? "HOLD / do not chase or add exposure; review existing position only if risk changed."
                : entryDedupSkips > 0
                    ? "HOLD / review mixed blockers; do not treat EntryDedup rows as new order/add-exposure instructions."
                    : "No EntryDedup action.";
        return new EventScanEntryDedupSummary(
                buyEvaluations,
                entrySkips.size(),
                entryDedupSkips,
                fullyBlocked,
                category,
                operatorAction);
    }

    private boolean isEntryDedupAudit(BtDecisionAudit audit) {
        return audit.getBlocker() != null && "EntryDedup".equalsIgnoreCase(audit.getBlocker().trim());
    }

    private static class EventScanEntryDedupSummary {
        private final long buyEvaluations;
        private final long entrySkips;
        private final long entryDedupSkips;
        private final boolean fullyBlockedByEntryDedup;
        private final String category;
        private final String operatorAction;

        private EventScanEntryDedupSummary(long buyEvaluations, long entrySkips, long entryDedupSkips,
                                           boolean fullyBlockedByEntryDedup, String category,
                                           String operatorAction) {
            this.buyEvaluations = buyEvaluations;
            this.entrySkips = entrySkips;
            this.entryDedupSkips = entryDedupSkips;
            this.fullyBlockedByEntryDedup = fullyBlockedByEntryDedup;
            this.category = category;
            this.operatorAction = operatorAction;
        }

        private boolean hasEntryDedupCluster() {
            return entryDedupSkips > 0;
        }

        private boolean shouldSuppressMainRow(BtDecisionAudit audit) {
            if (!fullyBlockedByEntryDedup) {
                return false;
            }
            String type = classifyEventScanType(audit);
            return "BUY".equals(type) || ("ENTRY_SKIP".equals(type)
                    && audit.getBlocker() != null
                    && "EntryDedup".equalsIgnoreCase(audit.getBlocker().trim()));
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("buyEvaluations", buyEvaluations);
            payload.put("entrySkips", entrySkips);
            payload.put("entryDedupSkips", entryDedupSkips);
            payload.put("fullyBlockedByEntryDedup", fullyBlockedByEntryDedup);
            payload.put("category", category);
            payload.put("operatorAction", operatorAction);
            payload.put("mainRowsSuppressed", fullyBlockedByEntryDedup);
            return payload;
        }
    }

    private boolean isNotableEventScanAudit(BtDecisionAudit audit) {
        String type = classifyEventScanType(audit);
        return switch (type) {
            case "BUY", "ENTRY_SKIP", "FILTER_BLOCK", "TRADE", "ERROR", "ANOMALY", "STALE" -> true;
            default -> false;
        };
    }

    private String truncateForTelegram(String message) {
        int maxLength = 3800;
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength - 80)
                + "\n...[truncated for Telegram safety]\nOperator action: run previewEventScanNotification for full JSON.";
    }

    private Map<String, Object> toEventScanItem(BtDecisionAudit audit) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", audit.getEventTime() != null ? audit.getEventTime().toString() : null);
        event.put("type", classifyEventScanType(audit));
        event.put("detail", renderEventScanDetail(audit));
        event.put("kpiAttribution", "pending");
        event.put("auditId", audit.getId());
        event.put("eventType", audit.getEventType());
        event.put("outcome", audit.getOutcome());
        event.put("symbol", audit.getSymbol());
        event.put("interval", audit.getIntervalCode());
        return event;
    }

    static String classifyEventScanType(BtDecisionAudit audit) {
        String eventType = audit.getEventType() != null ? audit.getEventType().toUpperCase() : "";
        String outcome = audit.getOutcome() != null ? audit.getOutcome().toUpperCase() : "";
        String reason = audit.getReason() != null ? audit.getReason().toUpperCase() : "";
        String blocker = audit.getBlocker() != null ? audit.getBlocker().toUpperCase() : "";

        if ("SIGNAL_EVAL".equals(eventType) && "BUY".equals(reason)) {
            return "BUY";
        }
        if ("ENTRY_SKIP".equals(eventType)) {
            return "ENTRY_SKIP";
        }
        if ("FILTER_BLOCK".equals(eventType) || "BLOCKED".equals(outcome)) {
            return "FILTER_BLOCK";
        }
        if ("ERROR".equals(outcome) || eventType.contains("ERROR") || eventType.contains("FAIL")) {
            return "ERROR";
        }
        if (eventType.contains("TRADE") || eventType.contains("AUTOTRADE") || eventType.contains("EXIT")
                || eventType.endsWith("_EXEC") || eventType.contains("_EXEC_")) {
            return "TRADE";
        }
        if (eventType.contains("OCO") || eventType.contains("ORDER") || eventType.contains("GRID")
                || blocker.contains("OCO") || blocker.contains("ORDER") || blocker.contains("GRID")) {
            return "ANOMALY";
        }
        if (eventType.contains("STALE") || reason.contains("STALE")) {
            return "STALE";
        }
        return eventType.isBlank() ? "ANOMALY" : eventType;
    }

    private String renderEventScanDetail(BtDecisionAudit audit) {
        StringBuilder detail = new StringBuilder();
        detail.append(audit.getSymbol() != null ? audit.getSymbol() : "-");
        if (audit.getIntervalCode() != null && !audit.getIntervalCode().isBlank()) {
            detail.append("@").append(audit.getIntervalCode());
        }
        detail.append(" ").append(audit.getEventType() != null ? audit.getEventType() : "-");
        detail.append("/").append(audit.getOutcome() != null ? audit.getOutcome() : "-");
        if (audit.getBlocker() != null && !audit.getBlocker().isBlank()) {
            detail.append(" blocker=").append(audit.getBlocker());
        }
        if (audit.getReason() != null && !audit.getReason().isBlank()) {
            detail.append(" reason=").append(audit.getReason());
        }
        return detail.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#504 read-only TG market-signal risk card preview. " +
            "Compresses recent MARKET_SIGNAL Telegram rows into one operator summary; it does not change trading/OCO/strategy/funds " +
            "and is not a BUY/SELL instruction. params: hours(1~168, default 24), symbol(optional)")
    public String previewMarketSignalRiskCard(Integer hours, String symbol) {
        return buildMarketSignalRiskCard(hours, symbol).message();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#504 manual TG market-signal risk card sender. dryRun=true previews only; dryRun=false sends a Telegram card with drill-down buttons. " +
            "Does not modify trading/OCO/strategy/funds. params: hours(1~168, default 24), symbol(optional), dryRun(default true)")
    public String sendMarketSignalRiskCard(Integer hours, String symbol, Boolean dryRun) {
        MarketSignalRiskCard card = buildMarketSignalRiskCard(hours, symbol);
        boolean previewOnly = dryRun == null || dryRun;
        if (previewOnly) {
            return "DRY_RUN: market-signal risk card not sent\n\n" + card.message();
        }

        telegramService.sendChannelMessageWithKeyboard(
                card.message(),
                false,
                MarketSignalRiskTelegramButtons.buildKeyboard(card.symbol(), card.hours()),
                "MarketSignalRiskSummary",
                "INFO");
        return "SENT: market-signal risk card sent with Telegram drill-down buttons\n\n" + card.message();
    }

    public String sendScheduledMarketSignalRiskCard(Integer hours, String symbol,
                                                    Integer minMarketSignals,
                                                    Integer minRouteFamilies,
                                                    boolean statusChangeOnly,
                                                    boolean dryRun) {
        int minSignals = minMarketSignals == null || minMarketSignals < 0 ? 3 : minMarketSignals;
        int minRoutes = minRouteFamilies == null || minRouteFamilies < 0 ? 2 : minRouteFamilies;
        MarketSignalRiskCard card = buildMarketSignalRiskCard(hours, symbol);

        if (card.marketSignalCount() < minSignals && card.routeFamilies() < minRoutes) {
            return "SKIPPED: quiet market-signal window signals=" + card.marketSignalCount()
                    + " routes=" + card.routeFamilies();
        }
        if (statusChangeOnly && card.fingerprint().equals(lastScheduledMarketSignalRiskFingerprint)) {
            return "SKIPPED: repeated market-signal risk fingerprint=" + card.fingerprint();
        }
        if (dryRun) {
            log.info("[MarketSignalRiskCard] dryRun=true; message:\n{}", card.message());
            return "DRY_RUN";
        }

        telegramService.sendChannelMessageWithKeyboard(
                card.message(),
                false,
                MarketSignalRiskTelegramButtons.buildKeyboard(card.symbol(), card.hours()),
                "MarketSignalRiskSummary",
                "INFO");
        lastScheduledMarketSignalRiskFingerprint = card.fingerprint();
        log.info("[MarketSignalRiskCard] Telegram keyboard message sent fingerprint={} chars={}",
                card.fingerprint(), card.message().length());
        return "SENT";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "#504 read-only market-signal risk drill-down backend for TG buttons. " +
            "params: hours(1~168, default 24), symbol(optional), detailType=market_details|signal_routes|full_summary, limit(1~50, default 12)")
    public String getMarketSignalRiskDrillDown(Integer hours, String symbol, String detailType, Integer limit) {
        int h = normalizeMarketSignalRiskHours(hours);
        int lim = limit == null || limit <= 0 ? 12 : Math.min(limit, 50);
        String type = normalizeMarketSignalRiskDetailType(detailType);
        String sym = normalizeMarketSignalSymbol(symbol);
        List<TgNotificationLog> logs = loadRecentTgLogs(h, sym, 100);
        List<TgNotificationLog> marketLogs = logs.stream()
                .filter(log -> tgBucket(log) == Bucket.MARKET_SIGNAL)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Market Signal Risk Drill-down (#504) ===\n")
                .append("symbol=").append(sym != null ? sym : "ALL")
                .append(" | windowHours=").append(h)
                .append(" | detailType=").append(type)
                .append(" | mode=READ_ONLY")
                .append(" | marketSignals=").append(marketLogs.size())
                .append("\n\n");

        if ("signal_routes".equals(type)) {
            Map<String, Long> routes = countRoutes(marketLogs);
            if (routes.isEmpty()) {
                sb.append("No MARKET_SIGNAL routes in this window.\n");
            } else {
                routes.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(lim)
                        .forEach(e -> sb.append(e.getKey()).append(" × ").append(e.getValue()).append("\n"));
            }
        } else if ("market_details".equals(type)) {
            appendMarketSignalRows(sb, marketLogs, lim);
        } else {
            sb.append(buildMarketSignalRiskCard(h, sym).message()).append("\n\n");
            appendMarketSignalRows(sb, marketLogs, lim);
        }

        sb.append("\nNo TG was sent. No trading, OCO, strategy, grid order, or fund behavior was changed.");
        return truncateForTelegram(sb.toString());
    }

    static int normalizeMarketSignalRiskHours(Integer hours) {
        if (hours == null || hours <= 0) {
            return 24;
        }
        return Math.min(hours, 168);
    }

    static String normalizeMarketSignalRiskDetailType(String detailType) {
        if (detailType == null || detailType.isBlank()) {
            return "full_summary";
        }
        String value = detailType.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "market", "market_details", "details" -> "market_details";
            case "routes", "signal_routes", "routing" -> "signal_routes";
            default -> "full_summary";
        };
    }

    private MarketSignalRiskCard buildMarketSignalRiskCard(Integer hours, String symbol) {
        int h = normalizeMarketSignalRiskHours(hours);
        String sym = normalizeMarketSignalSymbol(symbol);
        List<TgNotificationLog> logs = loadRecentTgLogs(h, sym, 100);
        List<TgNotificationLog> marketLogs = logs.stream()
                .filter(log -> tgBucket(log) == Bucket.MARKET_SIGNAL)
                .toList();
        long actionable = logs.stream()
                .filter(log -> tgBucket(log) == Bucket.ACTIONABLE_TRADE)
                .count();
        Map<String, Long> routes = countRoutes(marketLogs);
        String status = marketSignalRiskStatus(marketLogs.size(), actionable, routes);
        String fingerprint = marketSignalRiskFingerprint(h, sym, status, actionable, routes);

        StringBuilder sb = new StringBuilder();
        sb.append("[市場風險摘要] ").append(sym != null ? sym : "ALL")
                .append(" | ").append(h).append("h\n");
        sb.append("時間：").append(LocalDateTime.now(TAIPEI_ZONE).format(FMT)).append(" 台北\n");
        sb.append("狀態：").append(status).append("\n");
        sb.append("摘要：MARKET_SIGNAL ").append(marketLogs.size())
                .append(" / ACTIONABLE_TRADE ").append(actionable)
                .append(" / routes ").append(routes.size()).append("\n");
        sb.append("原因：").append(renderMarketSignalReasons(routes)).append("\n");
        sb.append("建議：").append(renderMarketSignalOperatorAction(status)).append("\n");
        sb.append("非交易指令：這張卡不是 BUY/SELL；Polymarket、MEI、Market Flip 只當風險背景。\n");
        sb.append("下一步：用下方按鈕查市場明細 / 訊號分層 / 倉位 / OCO / Trailing。");
        return new MarketSignalRiskCard(
                h,
                sym,
                truncateForTelegram(sb.toString()),
                status,
                marketLogs.size(),
                actionable,
                routes.size(),
                fingerprint);
    }

    private List<TgNotificationLog> loadRecentTgLogs(int hours, String symbol, int limit) {
        LocalDateTime from = LocalDateTime.now(ZoneOffset.UTC).minusHours(hours);
        List<TgNotificationLog> logs = tgNotificationLogRepo.search(
                from, null, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
        return logs.stream()
                .filter(log -> matchesMarketSignalSymbol(log, symbol))
                .toList();
    }

    private boolean matchesMarketSignalSymbol(TgNotificationLog log, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return true;
        }
        if (log.getSymbol() != null && symbol.equalsIgnoreCase(log.getSymbol())) {
            return true;
        }
        if (log.getMessage() == null) {
            return false;
        }
        String message = log.getMessage().toUpperCase(java.util.Locale.ROOT);
        String normalizedSymbol = symbol.toUpperCase(java.util.Locale.ROOT);
        String baseAsset = normalizedSymbol.endsWith("USDT")
                ? normalizedSymbol.substring(0, normalizedSymbol.length() - 4)
                : normalizedSymbol;
        return message.contains(normalizedSymbol)
                || (!baseAsset.isBlank() && message.contains(baseAsset));
    }

    private Map<String, Long> countRoutes(List<TgNotificationLog> logs) {
        Map<String, Long> routes = new LinkedHashMap<>();
        for (TgNotificationLog log : logs) {
            routes.merge(tgRoutingKey(log), 1L, Long::sum);
        }
        return routes;
    }

    private String marketSignalRiskStatus(long marketSignalCount, long actionableCount, Map<String, Long> routes) {
        if (actionableCount > 0) {
            return "REVIEW_POSITION";
        }
        if (marketSignalCount == 0) {
            return "HOLD";
        }
        long flip = routes.getOrDefault("market-signal:market-flip", 0L);
        boolean hasExternalRisk = routes.containsKey("market-signal:polymarket")
                || routes.containsKey("market-signal:macro");
        if (marketSignalCount >= 10 || flip >= 3 || (flip > 0 && hasExternalRisk)) {
            return "DO_NOT_ADD";
        }
        return "WATCH";
    }

    private String renderMarketSignalReasons(Map<String, Long> routes) {
        if (routes.isEmpty()) {
            return "近窗口沒有市場風險訊號。";
        }
        return routes.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(e -> marketSignalRouteLabel(e.getKey()) + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private String marketSignalRouteLabel(String route) {
        return switch (route) {
            case "market-signal:market-flip" -> "MarketFlip";
            case "market-signal:polymarket" -> "Polymarket";
            case "market-signal:whale" -> "Whale";
            case "market-signal:gemini-advisor" -> "Gemini";
            case "market-signal:put-call" -> "PutCall";
            case "market-signal:macro" -> "Macro/MEI";
            default -> route;
        };
    }

    private String renderMarketSignalOperatorAction(String status) {
        return switch (status) {
            case "REVIEW_POSITION" -> "先看是否已有交易/風控訊息；不要只靠市場訊號加倉。";
            case "DO_NOT_ADD" -> "不要追單或加倉；保留現有保護，等待策略 trigger。";
            case "WATCH" -> "觀察即可；只在倉位風險或策略 trigger 改變時處理。";
            default -> "維持持有/觀察；不用新增操作。";
        };
    }

    private String marketSignalRiskFingerprint(int hours, String symbol, String status,
                                               long actionableCount, Map<String, Long> routes) {
        String routeKey = routes.entrySet().stream()
                .sorted((a, b) -> {
                    int countCompare = Long.compare(b.getValue(), a.getValue());
                    return countCompare != 0 ? countCompare : a.getKey().compareTo(b.getKey());
                })
                .limit(4)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining(","));
        return (symbol != null ? symbol : "ALL")
                + "|" + hours + "h"
                + "|" + status
                + "|" + (actionableCount > 0 ? "actionable" : "no-actionable")
                + "|" + routeKey;
    }

    private void appendMarketSignalRows(StringBuilder sb, List<TgNotificationLog> marketLogs, int limit) {
        if (marketLogs.isEmpty()) {
            sb.append("No MARKET_SIGNAL rows in this window.\n");
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        int idx = 1;
        for (TgNotificationLog log : marketLogs.stream().limit(limit).toList()) {
            String msg = log.getMessage() != null ? log.getMessage().replaceAll("<[^>]+>", "") : "";
            if (msg.length() > 120) {
                msg = msg.substring(0, 117) + "...";
            }
            sb.append(idx++).append(". ")
                    .append(log.getSentAt() != null ? log.getSentAt().format(fmt) : "-")
                    .append(" ")
                    .append(tgRoutingKey(log))
                    .append(" [").append(log.getLevel() != null ? log.getLevel() : "?").append("] ")
                    .append(msg)
                    .append("\n");
        }
    }

    private String normalizeMarketSignalSymbol(String symbol) {
        if (symbol == null || symbol.isBlank() || "ALL".equalsIgnoreCase(symbol.trim())) {
            return null;
        }
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private record MarketSignalRiskCard(int hours, String symbol, String message, String status,
                                        long marketSignalCount, long actionableCount, int routeFamilies,
                                        String fingerprint) {
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "展開單筆 decision audit 的完整 context(JSON snapshot、關聯倉位、blocker 詳情)。" +
            "供 Claude 對可疑事件深入分析。params: auditId")
    public String getDecisionContext(Long auditId) {
        { String _e = McpParamValidator.requireNonNull(auditId, "auditId"); if (_e != null) return _e; }
        var opt = decisionAuditRepository.findById(auditId);
        if (opt.isEmpty()) return "❌ audit id=" + auditId + " 不存在";
        BtDecisionAudit a = opt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Decision Audit id=").append(a.getId()).append(" ===\n");
        sb.append("event_time   : ").append(a.getEventTime().format(FMT)).append(" UTC\n");
        sb.append("event_type   : ").append(a.getEventType()).append("\n");
        sb.append("outcome      : ").append(a.getOutcome()).append("\n");
        sb.append("strategy_id  : ").append(a.getStrategyId()).append("\n");
        sb.append("symbol       : ").append(a.getSymbol()).append("\n");
        sb.append("interval     : ").append(a.getIntervalCode()).append("\n");
        sb.append("blocker      : ").append(a.getBlocker() != null ? a.getBlocker() : "-").append("\n");
        sb.append("reason       : ").append(a.getReason() != null ? a.getReason() : "-").append("\n");
        renderContextJson(sb, a.getContextJson());

        if (a.getLiveSignalId() != null) {
            liveSignalRepository.findById(a.getLiveSignalId()).ifPresent(ls -> {
                sb.append("\n── Linked BtLiveSignal ──\n");
                sb.append(String.format("id=%d  side=%s  autoTraded=%s  entry=%s  exit=%s  pnl=%s%n",
                        ls.getId(), ls.getSide(), ls.getAutoTraded(),
                        ls.getActualEntryPrice() != null ? ls.getActualEntryPrice() : ls.getEntryPrice(),
                        ls.getExitPrice() != null ? ls.getExitPrice() : "-",
                        ls.getRealizedPnl() != null ? ls.getRealizedPnl() : "-"));
            });
        }
        return sb.toString();
    }

    // =========================================================================
    // 學習類 — Claude 事後分析筆記
    // =========================================================================

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "對已平倉 bt_live_signal 加註事後分析。" +
            "常用 tag: WIN_STRUCTURAL / LOSS_CHOP / FALSE_BREAKOUT / REGIME_MISMATCH / " +
            "LATE_ENTRY / EARLY_EXIT / STOP_HUNT。note 為 Claude 自由文字分析。" +
            "params: liveSignalId, tag(可選), note(必填)")
    public String annotatePosition(Long liveSignalId, String tag, String note) {
        { String _e = McpParamValidator.requireNonNull(liveSignalId, "liveSignalId"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(note, "note"); if (_e != null) return _e; }

        // 軟驗證 live_signal 存在(不存在時仍可寫,避免 Claude 猜錯 id 直接失敗)
        var ls = liveSignalRepository.findById(liveSignalId);
        if (ls.isEmpty()) {
            return "⚠️ bt_live_signal id=" + liveSignalId + " 不存在,請確認 id 後再註";
        }

        PositionAnnotation pa = new PositionAnnotation();
        pa.setLiveSignalId(liveSignalId);
        pa.setTag(tag != null && !tag.isBlank() ? tag.toUpperCase().trim() : null);
        pa.setNote(note);
        pa.setCreatedBy("claude");
        pa.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        positionAnnotationRepository.save(pa);

        return String.format(
                "✅ Annotation 已寫入\n   id=%d  live_signal=%d  tag=%s\n   note 長度=%d",
                pa.getId(), liveSignalId, pa.getTag() != null ? pa.getTag() : "-", note.length());
    }

    // =========================================================================
    // 預約提醒 — Claude 預約「到時點醒人類」的 TG 通知
    // =========================================================================

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.META})
    @Tool(description = "預約系統提醒,到指定時間自動發 TG 通知。" +
            "用於『明天早上 10 點來看 snapshot』『3 天後檢查策略 PnL』這類待辦。" +
            "fireAt 必須是未來時間,ISO 8601 格式含時區(如 '2026-04-16T10:00:00+08:00')。" +
            "params: fireAt(ISO 8601), message(必填,支援 HTML), tag(可選分類如 'snapshot'/'review')")
    public String createReminder(String fireAt, String message, String tag) {
        { String _e = McpParamValidator.requireNonBlank(fireAt, "fireAt"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(message, "message"); if (_e != null) return _e; }

        java.time.LocalDateTime fireAtUtc;
        try {
            // 接受 ISO 8601 含時區(優先)或不含時區(視為 UTC)
            try {
                fireAtUtc = java.time.OffsetDateTime.parse(fireAt)
                        .atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
            } catch (Exception e) {
                fireAtUtc = java.time.LocalDateTime.parse(fireAt);
            }
        } catch (Exception e) {
            return "❌ fireAt 解析失敗:" + e.getMessage() + "(範例:'2026-04-16T10:00:00+08:00')";
        }

        try {
            SystemReminder r = reminderService.create(fireAtUtc, message, tag, "claude");
            long minutesUntil = java.time.Duration.between(
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), r.getFireAt()).toMinutes();
            return String.format(
                    "✅ Reminder 已預約\n   id=%d  tag=%s\n   fire_at=%s UTC(%dh%dm 後)\n   message: %s",
                    r.getId(), r.getTag() != null ? r.getTag() : "-",
                    r.getFireAt().format(FMT),
                    minutesUntil / 60, minutesUntil % 60,
                    r.getMessage().length() > 100 ? r.getMessage().substring(0, 100) + "..." : r.getMessage());
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META})
    @Tool(description = "列出所有 PENDING reminder + 最近 20 筆已 FIRED/CANCELLED/FAILED 歷史。")
    public String listReminders() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        java.util.List<SystemReminder> pending = reminderRepository.findByStatusOrderByFireAtAsc("PENDING");
        java.util.List<SystemReminder> history = reminderRepository.findTop20ByStatusInOrderByFireAtDesc(
                java.util.List.of("FIRED", "CANCELLED", "FAILED"));

        StringBuilder sb = new StringBuilder();
        sb.append("=== Pending Reminders (").append(pending.size()).append(") ===\n");
        if (pending.isEmpty()) {
            sb.append("ℹ️ 無 pending\n");
        } else {
            for (SystemReminder r : pending) {
                long min = java.time.Duration.between(now, r.getFireAt()).toMinutes();
                sb.append(String.format("[%d] %s UTC(%+dh%02dm) %s\n   tag=%s msg=%s\n---\n",
                        r.getId(), r.getFireAt().format(FMT),
                        min / 60, Math.abs(min % 60),
                        r.getCreatedBy(),
                        r.getTag() != null ? r.getTag() : "-",
                        r.getMessage().length() > 80 ? r.getMessage().substring(0, 80) + "..." : r.getMessage()));
            }
        }
        sb.append("\n=== History (recent ").append(history.size()).append(") ===\n");
        for (SystemReminder r : history) {
            String icon = switch (r.getStatus()) {
                case "FIRED"     -> "✅";
                case "CANCELLED" -> "🚫";
                case "FAILED"    -> "❌";
                default -> "ℹ️";
            };
            sb.append(String.format("%s [%d] %s UTC %s tag=%s msg=%s\n",
                    icon, r.getId(), r.getFireAt().format(FMT), r.getStatus(),
                    r.getTag() != null ? r.getTag() : "-",
                    r.getMessage().length() > 60 ? r.getMessage().substring(0, 60) + "..." : r.getMessage()));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.META})
    @Tool(description = "取消 PENDING reminder。已 FIRED/CANCELLED 的不能再取消。params: reminderId")
    public String cancelReminder(Long reminderId) {
        { String _e = McpParamValidator.requireNonNull(reminderId, "reminderId"); if (_e != null) return _e; }
        boolean ok = reminderService.cancel(reminderId);
        return ok ? "✅ Reminder " + reminderId + " 已取消" : "ℹ️ Reminder " + reminderId + " 不存在或非 PENDING(無需取消)";
    }

    // =========================================================================
    // 學習類 — Claude 事後分析筆記
    // =========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.GOVERNANCE, Category.READ_TRADING})
    @Tool(description = "列出最近 N 天的事後分析筆記。可按 tag 過濾。" +
            "用於 Claude 彙整虧損模式、優化策略建議。params: days(1~90,預設 30), tag(可選)")
    public String listPositionAnnotations(Integer days, String tag) {
        int d = (days == null || days <= 0 || days > 90) ? 30 : days;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<PositionAnnotation> list = (tag != null && !tag.isBlank())
                ? positionAnnotationRepository.findByTagAndCreatedAtAfterOrderByCreatedAtDesc(tag.toUpperCase().trim(), since)
                : positionAnnotationRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since);

        if (list.isEmpty()) {
            return "ℹ️ 近 " + d + " 天無筆記" + (tag != null ? " (tag=" + tag + ")" : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Position Annotations (近 %dd, %d 筆) ===%n%n", d, list.size()));
        for (PositionAnnotation pa : list) {
            sb.append(String.format("[%d] live_signal=%d  tag=%s  by=%s  at=%s UTC%n",
                    pa.getId(), pa.getLiveSignalId(),
                    pa.getTag() != null ? pa.getTag() : "-",
                    pa.getCreatedBy(), pa.getCreatedAt().format(FMT)));
            sb.append("  note: ");
            String n = pa.getNote();
            sb.append(n.length() > 200 ? n.substring(0, 200) + "..." : n);
            sb.append("\n---\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "列出最近 N 天已平倉倉位(bt_live_signal with exit_time)。" +
            "顯示 entry/exit/pnl + 是否已有 annotation。供 Claude 事後選樣本分析。" +
            "params: days(1~90,預設 7), symbol(可選)")
    public String listRecentClosed(Integer days, String symbol) {
        int d = (days == null || days <= 0 || days > 90) ? 7 : days;
        String sym = (symbol == null || symbol.isBlank()) ? null : symbol.toUpperCase().trim();

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<BtLiveSignal> closed = liveSignalRepository.findAll().stream()
                .filter(ls -> ls.getExitTime() != null && ls.getExitTime().isAfter(since))
                .filter(ls -> sym == null || sym.equalsIgnoreCase(ls.getSymbol()))
                .sorted((a, b) -> b.getExitTime().compareTo(a.getExitTime()))
                .limit(50)
                .toList();

        if (closed.isEmpty()) {
            return String.format("ℹ️ 近 %d 天%s無已平倉倉位", d, sym != null ? " (" + sym + ")" : "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Recent Closed (近 %dd, %d 筆) ===%n%n", d, closed.size()));
        for (BtLiveSignal ls : closed) {
            boolean hasNote = !positionAnnotationRepository.findByLiveSignalIdOrderByCreatedAtDesc(ls.getId()).isEmpty();
            sb.append(String.format(
                    "[%d] %s %s strat=%d  entry=%s  exit=%s  pnl=%s  reason=%s  %s%n",
                    ls.getId(), ls.getSymbol(), ls.getSide() != null ? ls.getSide() : "LONG",
                    ls.getStrategyId(),
                    ls.getActualEntryPrice() != null ? ls.getActualEntryPrice() : ls.getEntryPrice(),
                    ls.getExitPrice() != null ? ls.getExitPrice() : "-",
                    ls.getRealizedPnl() != null ? ls.getRealizedPnl() : "-",
                    ls.getExitReason() != null ? ls.getExitReason() : "-",
                    hasNote ? "📝" : ""));
        }
        return sb.toString();
    }

    // =========================================================================
    // Attribution / Session Brief — Meta-Control 決策歸因 + session 冷啟動摘要
    // =========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.GOVERNANCE})
    @Tool(description = "Meta-Control override(目前僅 STRATEGY_PAUSE)的 counterfactual alpha 歸因摘要。" +
            "對每個到期/revoked override,系統已 hourly 算過『不 pause 會怎樣』的反事實 backtest。" +
            "本工具聚合近 N 天 SUCCESS 結果,顯示全局淨 alpha + per-strategy breakdown。" +
            "累積 alpha < 0 的策略會標示『建議減少 pause』。" +
            "params: days(1~365,預設 30)")
    public String getAttributionSummary(Integer days) {
        int d = (days == null || days <= 0 || days > 365) ? 30 : days;
        AttributionSummary s = attributionService.summarizeRecent(d);

        if (s.getTotalOverrides() == 0) {
            return String.format("ℹ️ 近 %d 天無 attribution(SUCCESS)資料。可能原因:%n" +
                    "  • 近期無 PAUSE override 結束%n" +
                    "  • V036 migration 未執行%n" +
                    "  • meta-control.attribution.enabled=false%n" +
                    "  • 所有 override scope=null 被標 SCOPE_TOO_BROAD", d);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Meta-Control Alpha (近 %dd) ===%n%n", d));
        sb.append(String.format("總計: %+.2f USDT  |  %d 筆 override  |  正 %d / 負 %d / 零 %d%n%n",
                s.getTotalAlpha().doubleValue(), s.getTotalOverrides(),
                s.getPositiveCount(), s.getNegativeCount(), s.getNeutralCount()));

        sb.append("── Per-Strategy ──\n");
        for (AttributionSummary.StrategyBreakdown b : s.getPerStrategy()) {
            int cmp = b.getCumulativeAlpha().compareTo(BigDecimal.ZERO);
            String verdict = cmp > 0 ? "✓"
                    : cmp < 0 ? "✗ 建議減少 pause"
                    : "~";
            sb.append(String.format("  %s [%d] %s  %d 次  累計 %+.2f  (max+ %+.2f / max- %+.2f)%n",
                    verdict, b.getStrategyId(), b.getStrategyName(),
                    b.getOverrideCount(), b.getCumulativeAlpha().doubleValue(),
                    b.getMaxPositive().doubleValue(), b.getMaxNegative().doubleValue()));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META})
    @Tool(description = "Session 冷啟動全景摘要 — 一次取得:(1) 近 30d Meta-Control alpha 歸因 " +
            "(2) 當前 active overrides (3) 近 7d annotation backlog (4) 近 24h 決策量分布 " +
            "(5) 近 7d attention rule 命中 (6) MCP 工具分類清單(依 @McpCategory)。" +
            "Claude 開 session 第一件事就跑這個,取代 5+ 個分散 MCP call。" +
            "顯示用 Asia/Taipei 時區。")
    public String getSessionBrief() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Session Brief (Taipei ").append(fmtTpe(nowUtc)).append(") ===\n\n");

        // 1. Meta-Control Alpha (30d)
        AttributionSummary attr = attributionService.summarizeRecent(30);
        sb.append("📈 Meta-Control Alpha (30d)\n");
        if (attr.getTotalOverrides() == 0) {
            sb.append("  ℹ️ 無資料(近 30d 無 PAUSE override 結束,或系統剛上線)\n");
        } else {
            sb.append(String.format("  總計 %+.2f USDT  [%d 筆 | 正 %d / 負 %d / 零 %d]%n",
                    attr.getTotalAlpha().doubleValue(), attr.getTotalOverrides(),
                    attr.getPositiveCount(), attr.getNegativeCount(), attr.getNeutralCount()));
            int showN = Math.min(5, attr.getPerStrategy().size());
            for (int i = 0; i < showN; i++) {
                AttributionSummary.StrategyBreakdown b = attr.getPerStrategy().get(i);
                String icon = b.getCumulativeAlpha().compareTo(BigDecimal.ZERO) >= 0 ? "✓" : "✗";
                sb.append(String.format("    %s strat %d (%s): %+.2f from %d pauses%n",
                        icon, b.getStrategyId(), b.getStrategyName(),
                        b.getCumulativeAlpha().doubleValue(), b.getOverrideCount()));
            }
        }

        // 2. Active overrides
        List<StrategyOverride> strategies = strategyOverrideRepository.findAllActive(nowUtc);
        List<HintOverride> hints = hintOverrideRepository.findAllActive(nowUtc);
        List<AttentionRule> rules = attentionRuleRepository.findActive(nowUtc);
        int totalActive = strategies.size() + hints.size() + rules.size();
        sb.append(String.format("%n🔴 Active Overrides (%d)%n", totalActive));
        if (totalActive == 0) {
            sb.append("  ℹ️ 無 active override(系統照原策略自動交易)\n");
        } else {
            for (StrategyOverride ov : strategies) {
                long remainMin = Duration.between(nowUtc, ov.getExpiresAt()).toMinutes();
                sb.append(String.format("  [%s] strat=%d (%s@%s) 剩 %dh%02dm — %s%n",
                        ov.getAction(), ov.getStrategyId(),
                        ov.getSymbol() != null ? ov.getSymbol() : "*",
                        ov.getIntervalCode() != null ? ov.getIntervalCode() : "*",
                        remainMin / 60, remainMin % 60, ov.getReason()));
            }
            for (HintOverride h : hints) {
                long remainMin = Duration.between(nowUtc, h.getExpiresAt()).toMinutes();
                sb.append(String.format("  [HINT] %s@%s style=%s sl×=%s tp×=%s 剩 %dh%02dm%n",
                        h.getSymbol(), h.getTimeframe(),
                        h.getStyleHint() != null ? h.getStyleHint() : "-",
                        h.getSlMultiplier() != null ? h.getSlMultiplier() : "-",
                        h.getTpMultiplier() != null ? h.getTpMultiplier() : "-",
                        remainMin / 60, remainMin % 60));
            }
            for (AttentionRule r : rules) {
                sb.append(String.format("  [RULE] %s hits=%s %s/%s%n",
                        r.getName(),
                        r.getHitCount() != null ? r.getHitCount() : 0,
                        r.getAction(), r.getSeverity()));
            }
        }

        // 3. Annotation backlog (7d)
        List<BtLiveSignal> backlog = liveSignalRepository
                .findClosedWithoutAnnotationSince(nowUtc.minusDays(7));
        sb.append(String.format("%n📋 Annotation Backlog (7d, %d 筆)%n", backlog.size()));
        if (backlog.isEmpty()) {
            sb.append("  ✅ 近 7d 所有平倉皆已 annotate(或無平倉)\n");
        } else {
            int showN = Math.min(5, backlog.size());
            for (int i = 0; i < showN; i++) {
                BtLiveSignal ls = backlog.get(i);
                String pnlStr = ls.getRealizedPnl() != null
                        ? String.format("%+.2f", ls.getRealizedPnl().doubleValue()) : "?";
                long hoursAgo = Duration.between(ls.getExitTime(), nowUtc).toHours();
                sb.append(String.format("  #%d %s %s pnl=%s (%dh 前平)%n",
                        ls.getId(), ls.getSymbol(),
                        ls.getSide() != null ? ls.getSide() : "LONG", pnlStr, hoursAgo));
            }
            if (backlog.size() > showN) {
                sb.append(String.format("  ... 還有 %d 筆(用 listRecentClosed 看完整)%n",
                        backlog.size() - showN));
            }
        }

        // 4. Last 24h decisions
        List<Object[]> eventCounts = decisionAuditRepository
                .countByEventTypeSince(nowUtc.minusHours(24));
        sb.append("\n📊 Last 24h Decisions\n");
        if (eventCounts.isEmpty()) {
            sb.append("  ℹ️ 近 24h 無 audit(可能剛重啟未滿 24h,或 evaluator 停機)\n");
        } else {
            long total = eventCounts.stream()
                    .mapToLong(o -> ((Number) o[1]).longValue()).sum();
            sb.append(String.format("  總 %d 筆  |", total));
            for (Object[] row : eventCounts) {
                sb.append(String.format("  %s=%d", row[0], ((Number) row[1]).longValue()));
            }
            sb.append("\n");
        }

        // 5. Attention hits (7d)
        LocalDateTime sevenDaysAgo = nowUtc.minusDays(7);
        List<AttentionRule> hitRules = rules.stream()
                .filter(r -> r.getHitCount() != null && r.getHitCount() > 0)
                .filter(r -> r.getLastHitAt() != null && r.getLastHitAt().isAfter(sevenDaysAgo))
                .sorted((a, b) -> Integer.compare(
                        b.getHitCount() != null ? b.getHitCount() : 0,
                        a.getHitCount() != null ? a.getHitCount() : 0))
                .limit(5)
                .toList();
        sb.append(String.format("%n🎯 Attention Hits (7d, %d rules)%n", hitRules.size()));
        if (hitRules.isEmpty()) {
            sb.append("  ℹ️ 近 7d 無 attention rule 命中\n");
        } else {
            for (AttentionRule r : hitRules) {
                sb.append(String.format("  \"%s\" %d hits (%s/%s)%n",
                        r.getName(), r.getHitCount(), r.getAction(), r.getSeverity()));
            }
        }

        // 6. MCP Tool Categories — 讓 Claude 掃 session 首次 call 時就知道有哪些分類
        //    與各類別下的工具,作為 selective loading 的基礎(目前全量載入,此為 metadata)。
        try {
            java.util.Map<Category, java.util.List<String>> byCat =
                    mcpApiKeyFilter.getToolsByCategory();
            int totalCategorized = byCat.values().stream().mapToInt(java.util.List::size).sum();
            sb.append(String.format("%n🧰 MCP Tool Categories (%d 類, %d 次分類標記)%n",
                    byCat.size(), totalCategorized));
            if (byCat.isEmpty()) {
                sb.append("  ℹ️ 尚無 @McpCategory 標記(檢查啟動日誌)\n");
            } else {
                for (java.util.Map.Entry<Category, java.util.List<String>> e : byCat.entrySet()) {
                    java.util.List<String> tools = e.getValue();
                    // 一行一個 category,inline 列出工具名(最多 6 個，再多用 "+N more")
                    String shown;
                    if (tools.size() <= 6) {
                        shown = String.join(", ", tools);
                    } else {
                        shown = String.join(", ", tools.subList(0, 6))
                                + ", +" + (tools.size() - 6) + " more";
                    }
                    sb.append(String.format("  [%s] %d: %s%n",
                            e.getKey().name(), tools.size(), shown));
                }
            }
        } catch (Exception e) {
            sb.append("  ⚠️ 讀取 category map 失敗: ").append(e.getMessage()).append('\n');
        }

        // 7. AI Token Usage Today
        try {
            var todayUsage = aiUsageTracker.today();
            long totalTokens = todayUsage.stream()
                    .mapToLong(u -> u.getPromptTok() + u.getCompleteTok()).sum();
            sb.append(String.format("%n🤖 AI Token Usage Today (%d models, %,d total tokens)%n",
                    todayUsage.size(), totalTokens));
            if (todayUsage.isEmpty()) {
                sb.append("  ℹ️ 今日尚無 router 路由記錄(GeminiMarketAdvisor 等排程未觸發)\n");
            } else {
                todayUsage.stream()
                        .sorted((a, b) -> Long.compare(
                                b.getPromptTok() + b.getCompleteTok(),
                                a.getPromptTok() + a.getCompleteTok()))
                        .limit(5)
                        .forEach(u -> sb.append(String.format(
                                "  %s: %d req / in=%,d out=%,d tok%n",
                                u.getModel(), u.getReqCount(),
                                u.getPromptTok(), u.getCompleteTok())));
            }
        } catch (Exception e) {
            sb.append("  ⚠️ AI usage 讀取失敗: ").append(e.getMessage()).append('\n');
        }

        return sb.toString();
    }

    /** 把 UTC LocalDateTime 轉 Taipei 顯示字串。 */
    private String fmtTpe(LocalDateTime utc) {
        if (utc == null) return "-";
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(TAIPEI_ZONE).format(FMT);
    }

    // =========================================================================
    // 自然語言 — askSystemAssistant(snapshot-grounded AI Q&A)
    // =========================================================================

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.READ_TRADING})
    @Tool(description = "自然語言詢問系統狀態。會自動收集 session brief / 策略 / 倉位 / 市況 / " +
            "最近 flips / ML / funding arb 等 snapshot 餵給 AI,答案基於 snapshot 事實。" +
            "範例問題: '315 今天觸發幾次?' '市場 flip 這週多少 ALERT?' 'Funding arb ROI?'。" +
            "params: question(必填), maxTokens(可選, 預設 500, 上限 2048)")
    public String askSystemAssistant(String question, Integer maxTokens) {
        if (question == null || question.isBlank()) {
            return "❌ question 不可空";
        }
        int mt = (maxTokens == null || maxTokens <= 0) ? 500 : Math.min(maxTokens, 2048);

        SystemSnapshotCollector.SystemSnapshot snap;
        String snapJson;
        try {
            snap = systemSnapshotCollector.gather();
            snapJson = snap.toJson(objectMapper);
        } catch (Exception e) {
            log.error("[askSystemAssistant] snapshot gather failed", e);
            return "❌ snapshot 收集失敗: " + e.getMessage();
        }

        AiTask task = new AiTask.AnswerUserQuery(
                question,
                List.of("System snapshot (as-of " + snap.timestamp() + "):\n" + snapJson),
                mt);

        AiResponse response;
        try {
            response = aiTaskRouter.execute(task);
        } catch (AiTaskRouter.AllProvidersFailedException e) {
            log.warn("[askSystemAssistant] AI call failed: {}", e.getMessage());
            return String.format("❌ AI 回應失敗 (%s). 建議直接 call 對應 MCP tool。",
                    e.getMessage());
        } catch (Exception e) {
            log.error("[askSystemAssistant] unexpected", e);
            return "❌ 未預期錯誤: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }

        return String.format(
                "❓ Q: %s%n💬 A: %s%n%n(Snapshot: %s, provider: %s, tokens in/out: %d/%d)",
                question,
                response.text() != null ? response.text().trim() : "(空回應)",
                snap.timestamp(),
                response.provider(),
                response.inputTokens(),
                response.outputTokens());
    }

    // ── Factor R&D Loop (issue #204) ──────────────────────────────────────────

    private static final String LONG_AI_FILTER_RULES_SUMMARY =
            "Current LongAiFilter Phase1 rules (7 rules):\n" +
            "1. Event calendar block (FOMC/CPI ±2h/4h)\n" +
            "2. F&G > 75 + 4h non-bullish continuation → block\n" +
            "3. 4h trend BEARISH or MACD histogram < 0 → block\n" +
            "4. RSI > 80 (overbought) → block\n" +
            "5. Whale buy ratio < 0.35 (whales selling) → block\n" +
            "6. Funding rate > 0.0005 + non-bullish (perp only) → block\n" +
            "7. Orderbook imbalance < -0.5 (sell wall) → block\n" +
            "\nAvailable ta4j indicators in Ta4jPhaseOneEvaluator:\n" +
            "- CMF(20): Chaikin Money Flow (volume-weighted buy/sell pressure)\n" +
            "- VWAP: Volume-Weighted Average Price (series start)\n" +
            "- EMA(9), EMA(21): fast/slow trend\n" +
            "- RSIIndicator, EMAIndicator, BollingerBandsIndicator available from ta4j-core 0.15";

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.GOVERNANCE})
    @Tool(description = "AI 因子探索：根據近期勝率與現有 LongAiFilter 規則，請 LLM 生成 3 個新 ta4j Rule 假說" +
            "及 Java 代碼片段，結果寫入 KB 供下次 session 實作。" +
            "param: recentWinRate=近期實盤勝率(0.0-1.0), lookbackDays=取樣天數(預設 30)")
    public String runFactorExploration(Double recentWinRate, Integer lookbackDays) {
        int days = (lookbackDays == null || lookbackDays <= 0) ? 30 : lookbackDays;
        double wr = (recentWinRate == null) ? 0.25 : recentWinRate;

        String prompt = String.format("""
                You are a quantitative trading researcher. Analyze the current BTC 1h trading filter system
                and suggest 3 NEW ta4j Rule ideas that could improve the LONG signal quality.

                CONTEXT:
                - Recent win rate: %.1f%% over last %d days (baseline market win rate ~25%%)
                - If win rate < 30%%, suggest DEFENSIVE rules to reduce false positives
                - If win rate > 40%%, suggest AGGRESSIVE rules to catch more winners

                %s

                YOUR TASK: Generate exactly 3 new rule ideas. For each:
                1. Rule name (kebab-case)
                2. Indicator + condition (specific threshold)
                3. Rationale (1-2 sentences why this could help)
                4. Java ta4j code snippet using available indicators
                5. Expected effect: reduces false positives OR increases true positives

                Format each rule as:
                ### Rule N: [name]
                Condition: [indicator] [operator] [threshold]
                Rationale: [why this helps]
                Code:
                ```java
                [ta4j code using RSIIndicator, EMAIndicator, CMFIndicator, VWAPIndicator, etc.]
                ```
                Effect: [reduces-FP | increases-TP]

                Focus on rules that complement the existing 7 rules, not duplicate them.
                """, wr * 100, days, LONG_AI_FILTER_RULES_SUMMARY);

        AiTask task = new AiTask.AnswerUserQuery(prompt, List.of(), 2000);
        AiResponse response;
        try {
            response = aiTaskRouter.execute(task);
        } catch (Exception e) {
            log.warn("[FactorExploration] AI call failed: {}", e.getMessage());
            return "❌ AI 回應失敗: " + e.getMessage();
        }

        String aiText = response.text() != null ? response.text().trim() : "(no response)";

        // Write exploration result to KB
        String topicKey = "factor-exploration-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        devKnowledgeService.writeAsync(
                topicKey,
                "Factor Exploration " + java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                String.format("## Input\n- recentWinRate=%.1f%% lookbackDays=%d\n\n## LLM Output\n\n%s",
                        wr * 100, days, aiText),
                "trading", "raw", "factor-exploration,ta4j,rule-ideas",
                0.7, "src/main/java/com/agora/service/trading/LongAiFilter.java",
                "draft", "claude-session", "agora-trading-api");

        return String.format(
                "=== Factor Exploration ===\nrecentWinRate=%.1f%%  lookbackDays=%d\n\n%s\n\n✅ KB written: %s",
                wr * 100, days, aiText, topicKey);
    }

    // ── TG 通知歷史 ───────────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "查詢 TG 通知歷史記錄，可依時間範圍/來源/級別/規則ID 搜尋。" +
            "用於分析誤報模式、評估告警頻率、優化指標閾值。" +
            "params: hours=回溯小時數(預設 24,最多 720), source=發送方 keyword(可選,如 SqiIndicator), " +
            "level=告警級別(INFO/WARN/CRITICAL,可選), ruleId=Attention Rule ID(可選), limit=筆數(預設 20)")
    public String getTgNotificationHistory(
            Integer hours, String source, String level, Long ruleId, Integer limit) {

        int h = (hours == null || hours <= 0) ? 24 : Math.min(hours, 720);
        int lim = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);

        var from = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(h);
        String sourceLike = (source != null && !source.isBlank()) ? "%" + source.trim() + "%" : null;
        String lvl = (level != null && !level.isBlank()) ? level.trim().toUpperCase() : null;

        // ruleId=0 表示「不過濾」，null 才會跳過 WHERE rule_id = ? 條件
        Long effectiveRuleId = (ruleId != null && ruleId > 0) ? ruleId : null;
        var logs = tgNotificationLogRepo.search(from, null, lvl, sourceLike, effectiveRuleId,
                org.springframework.data.domain.PageRequest.of(0, lim));

        if (logs.isEmpty()) {
            return String.format("=== TG 通知歷史（近 %dh）===\n\n無符合記錄。", h);
        }

        // 統計摘要
        var stats = tgNotificationLogRepo.countBySourceAndLevel(from);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== TG 通知歷史（近 %dh，共 %d 筆）===\n\n", h, logs.size()));

        appendTgActionabilitySummary(sb, logs);

        // 頻率摘要
        if (!stats.isEmpty()) {
            sb.append("── 發送頻率摘要 ─────────────────────────────────\n");
            for (Object[] row : stats) {
                String src = row[0] != null ? row[0].toString() : "unknown";
                String lv  = row[1] != null ? row[1].toString() : "?";
                long cnt   = ((Number) row[2]).longValue();
                sb.append(String.format("  %-35s [%s] × %d\n", src, lv, cnt));
            }
            sb.append("\n");
        }

        // 詳細記錄
        sb.append("── 詳細記錄 ─────────────────────────────────────\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (var log : logs) {
            String ts  = log.getSentAt().format(fmt);
            String src = log.getSource() != null ? log.getSource() : "?";
            String lv  = log.getLevel() != null ? log.getLevel() : "?";
            Bucket bucket = tgBucket(log);
            String routingKey = tgRoutingKey(log);
            // 訊息截斷顯示（最多 80 字）
            String msg = log.getMessage().replaceAll("<[^>]+>", ""); // 移除 HTML tag
            if (msg.length() > 80) msg = msg.substring(0, 77) + "...";
            sb.append(String.format("[%s] [%s] [%s] %s | %s\n  %s\n",
                    ts, lv, bucket, src, routingKey, msg));
        }

        if (logs.size() >= lim) {
            sb.append(String.format("\n（只顯示最新 %d 筆，可增加 limit 參數或縮短 hours 範圍）", lim));
        }

        return sb.toString();
    }

    private void appendTgActionabilitySummary(StringBuilder sb, List<TgNotificationLog> logs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ACTIONABLE_TRADE", 0L);
        counts.put("MARKET_SIGNAL", 0L);
        counts.put("SYSTEM_NOISE", 0L);
        counts.put("OPS_AUDIT", 0L);
        counts.put("GRID_INCIDENT", 0L);
        counts.put("OTHER", 0L);
        Map<String, Long> routeCounts = new LinkedHashMap<>();

        for (TgNotificationLog log : logs) {
            String bucket = tgBucket(log).name();
            counts.computeIfPresent(bucket, (k, v) -> v + 1);
            routeCounts.merge(tgRoutingKey(log), 1L, Long::sum);
        }

        sb.append("── 交易可操作性分層 ─────────────────────────────\n");
        counts.forEach((k, v) -> sb.append(String.format("  %-18s × %d\n", k, v)));
        long actionable = counts.getOrDefault("ACTIONABLE_TRADE", 0L);
        long noise = counts.getOrDefault("SYSTEM_NOISE", 0L)
                + counts.getOrDefault("OPS_AUDIT", 0L)
                + counts.getOrDefault("GRID_INCIDENT", 0L);
        if (noise > Math.max(5, actionable * 3)) {
            sb.append("  ⚠️ system/ops/grid noise 訊息明顯多於 actionable trade；分析 TG 誤判時請先過濾 ACTIONABLE_TRADE。\n");
        }
        appendTgRoutingSummary(sb, routeCounts);
        sb.append("\n");
    }

    private void appendTgRoutingSummary(StringBuilder sb, Map<String, Long> routeCounts) {
        sb.append("\n── Routing / incident 摘要 ─────────────────────\n");
        routeCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1
                        || e.getKey().startsWith("grid-incident:")
                        || e.getKey().startsWith("ops-audit:")
                        || e.getKey().equals("trade-signal:buy-resend"))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> sb.append(String.format("  %-36s × %d\n", e.getKey(), e.getValue())));
        boolean hasImportantRoute = routeCounts.keySet().stream().anyMatch(k ->
                k.startsWith("grid-incident:")
                        || k.startsWith("ops-audit:")
                        || k.equals("trade-signal:buy-resend"));
        if (!hasImportantRoute) {
            sb.append("  No repeated grid/ops/resend routing keys in shown rows.\n");
        }
    }

    private Bucket tgBucket(TgNotificationLog log) {
        return tgNotificationClassifier.classify(log.getMessage(), log.getSource(), log.getLevel());
    }

    private String tgRoutingKey(TgNotificationLog log) {
        return tgNotificationClassifier.routingKey(log.getMessage(), log.getSource(), log.getLevel());
    }

    /**
     * #307 查詢 CMI 指標歷史數值，方便偵錯和驗證指標計算是否正確。
     * params: indicator=指標 key（如 sqi/vdi/sqi_short_crowding）,
     *         hours=回溯小時數（預設 24，最多 720）,
     *         minValue=只顯示 >= 此值（可選，預設 -1 不過濾）,
     *         limit=筆數（預設 50）
     */
    public String getMihIndicatorHistory(String indicator, Integer hours, Double minValue, Integer limit) {
        if (indicator == null || indicator.isBlank()) {
            return "❌ indicator 為必填（如 sqi、vdi、sqi_short_crowding）";
        }
        int h   = (hours == null || hours <= 0) ? 24 : Math.min(hours, 720);
        int lim = (limit == null || limit <= 0) ? 50 : Math.min(limit, 500);
        double minV = (minValue != null) ? minValue : Double.NEGATIVE_INFINITY;

        var from = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(h);
        var rows = indicatorHistoryRepo.findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                "BTCUSDT", indicator.trim(), from);

        if (rows.isEmpty()) {
            return String.format("=== %s（近 %dh）===\n\n無數據（指標不存在或尚無歷史）", indicator, h);
        }

        // 統計
        double minVal = rows.stream().mapToDouble(r -> r.getValue().doubleValue()).min().orElse(0);
        double maxVal = rows.stream().mapToDouble(r -> r.getValue().doubleValue()).max().orElse(0);
        double avgVal = rows.stream().mapToDouble(r -> r.getValue().doubleValue()).average().orElse(0);
        long nonZero  = rows.stream().filter(r -> r.getValue().doubleValue() > 0).count();

        var filtered = rows.stream()
                .filter(r -> r.getValue().doubleValue() >= minV)
                .sorted((a, b) -> b.getCapturedAt().compareTo(a.getCapturedAt()))
                .limit(lim)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== %s（近 %dh）===\n", indicator, h));
        sb.append(String.format("總計 %d 筆 | 非零 %d | min=%.1f max=%.1f avg=%.2f\n\n",
                rows.size(), nonZero, minVal, maxVal, avgVal));

        if (minValue != null) {
            sb.append(String.format("（只顯示 >= %.1f 的記錄，共 %d 筆）\n", minV, filtered.size()));
        }

        DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (var r : filtered) {
            sb.append(String.format("[%s] %.1f\n", r.getCapturedAt().format(fmt), r.getValue().doubleValue()));
        }

        if (filtered.size() >= lim) {
            sb.append(String.format("\n（只顯示最新 %d 筆）", lim));
        }
        return sb.toString();
    }
}
