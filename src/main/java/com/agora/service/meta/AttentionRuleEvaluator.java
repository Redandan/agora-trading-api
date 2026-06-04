package com.agora.service.meta;

import com.agora.model.AttentionRule;
import com.agora.repository.trading.AttentionRuleRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.indicator.HysteresisAlertGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attention Rule 引擎(Phase 1 極簡版)。
 *
 * <h3>Predicate JSON 語法(單層 AND)</h3>
 * <pre>
 * {
 *   "symbol": "BTCUSDT",           // 精確比對(忽略 case)
 *   "interval": "1h",              // 精確比對
 *   "side": "LONG",                // LONG / SHORT
 *   "fg_gt": 80, "fg_lt": 20,      // Fear&Greed 區間
 *   "rsi_gt": 75, "rsi_lt": 30,    // RSI 區間
 *   "adx_gt": 40, "adx_lt": 15,    // ADX 趨勢強度
 *   "volume_ratio_gt": 2.0,        // 成交量/MA20 倍率
 *   "volume_ratio_lt": 0.3,
 *   "gemini_style": "DISABLE",     // Gemini hint 風格(TREND/CONSERVATIVE/DISABLE/HIGH_FREQ)
 *   "gemini_regime": "TRENDING_UP", // Gemini 市場形態
 *   "strategy_id_in": [7, 12],     // 策略白名單
 *   "mih_indicator": "btc_put_call_ratio", // market_indicator_history 指標名
 *   "mih_gt": 1.5,                 // 指標最新值 > threshold
 *   "mih_lt": -0.1                 // 指標最新值 < threshold（mih_gt/mih_lt 可並用）
 * }
 * </pre>
 * 未指定欄位 = 不約束。所有指定欄位皆需滿足才算 match(AND)。
 *
 * <h3>mih_* 欄位（market_indicator_history 獨立監控）</h3>
 * 含 mih_indicator 的規則由 {@link #evaluateMarketIndicators()} 獨立排程評估（每 30 分鐘），
 * 不依賴策略信號觸發，適合監控 btc_put_call_ratio / btc_basis_pct / us_vix 等宏觀指標。
 *
 * <h3>Phase 1 actions</h3>
 * <ul>
 *   <li>{@code LOG_ONLY}: 寫 ATTENTION_HIT audit,不阻擋不通知</li>
 *   <li>{@code NOTIFY}: audit + 發 TG</li>
 * </ul>
 * <b>不支援</b> REQUIRE_REVIEW / BLOCK(Phase 2 上)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttentionRuleEvaluator {

    private static final Set<String> SUPPORTED_ACTIONS_PHASE1 = Set.of("LOG_ONLY", "NOTIFY");
    private static final Set<String> SUPPORTED_SEVERITIES     = Set.of("INFO", "WARN", "CRITICAL");

    private final AttentionRuleRepository ruleRepo;
    private final DecisionAuditWriter auditWriter;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepository;
    private final com.agora.repository.system.TgNotificationLogRepository tgNotificationLogRepo;
    private final HysteresisAlertGuard hysteresisGuard;

    /** 同一 rule 1 小時內不重複發 TG（去重鎖）。
     *  in-memory 作為加速快取；deploy 重啟後從 tg_notification_log 補充。
     *  訊號路徑（{@link #evaluate(Map)}）走這條；MIH 路徑改走 #428 hysteresis。 */
    private final ConcurrentHashMap<Long, LocalDateTime> lastNotifiedAt = new ConcurrentHashMap<>();
    private static final long DEDUP_MINUTES = 60;

    /** #428 — hover hysteresis reminder 週期（小時）。MIH 路徑用，與 MEI/SBI 同預設。 */
    private static final long ATTENTION_REMINDER_HOURS = 12;
    private static final String HYSTERESIS_KEY_PREFIX = "attn_rule_";

    /**
     * 對產出的 signal 評估所有 active rules。
     * async 呼叫以不阻擋主流程;規則內部 exception 不 throw。
     */
    @Async("metaAuditExecutor")
    public void evaluate(Map<String, Object> context) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            List<AttentionRule> rules = ruleRepo.findActive(now);
            for (AttentionRule r : rules) {
                try {
                    Map<String, Object> pred = objectMapper.readValue(
                            r.getPredicateJson(), new TypeReference<>() {});
                    if (!matchesPredicate(pred, context)) continue;
                    applyAction(r, pred, context, now);
                } catch (Throwable t) {
                    log.warn("[AttentionRule] rule id={} eval failed: {}", r.getId(), t.getMessage());
                }
            }
        } catch (Throwable t) {
            log.warn("[AttentionRule] evaluator fatal: {}", t.getMessage());
        }
    }

    /** Dry-run:給 predicate JSON 與 sample context,驗證 parse 正確 + match 結果。 */
    public DryRunResult dryRun(String predicateJson, Map<String, Object> sampleContext) {
        try {
            Map<String, Object> pred = objectMapper.readValue(predicateJson, new TypeReference<>() {});
            boolean match = matchesPredicate(pred, sampleContext);
            return new DryRunResult(true, match, null);
        } catch (Exception e) {
            return new DryRunResult(false, false, e.getMessage());
        }
    }

    // ===== Internal =====

    /** predicate 所有欄位都要滿足 (AND)。未指定欄位 = 不約束。 */
    @SuppressWarnings("unchecked")
    private boolean matchesPredicate(Map<String, Object> pred, Map<String, Object> ctx) {
        if (pred.containsKey("symbol") && !equalsIgnoreCase(
                (String) pred.get("symbol"), (String) ctx.get("symbol"))) return false;
        if (pred.containsKey("interval") && !equalsIgnoreCase(
                (String) pred.get("interval"), (String) ctx.get("interval"))) return false;
        if (pred.containsKey("side") && !equalsIgnoreCase(
                (String) pred.get("side"), (String) ctx.get("side"))) return false;

        if (pred.containsKey("fg_gt")  && !gt(ctx.get("fg"),  pred.get("fg_gt")))  return false;
        if (pred.containsKey("fg_lt")  && !lt(ctx.get("fg"),  pred.get("fg_lt")))  return false;
        if (pred.containsKey("rsi_gt") && !gt(ctx.get("rsi"), pred.get("rsi_gt"))) return false;
        if (pred.containsKey("rsi_lt") && !lt(ctx.get("rsi"), pred.get("rsi_lt"))) return false;

        if (pred.containsKey("adx_gt") && !gt(ctx.get("adx"), pred.get("adx_gt"))) return false;
        if (pred.containsKey("adx_lt") && !lt(ctx.get("adx"), pred.get("adx_lt"))) return false;

        if (pred.containsKey("volume_ratio_gt") && !gt(ctx.get("volume_ratio"), pred.get("volume_ratio_gt"))) return false;
        if (pred.containsKey("volume_ratio_lt") && !lt(ctx.get("volume_ratio"), pred.get("volume_ratio_lt"))) return false;

        if (pred.containsKey("gemini_style") && !equalsIgnoreCase(
                (String) pred.get("gemini_style"), (String) ctx.get("gemini_style"))) return false;
        if (pred.containsKey("gemini_regime") && !equalsIgnoreCase(
                (String) pred.get("gemini_regime"), (String) ctx.get("gemini_regime"))) return false;

        if (pred.containsKey("strategy_id_in")) {
            Object idIn = pred.get("strategy_id_in");
            if (!(idIn instanceof List)) return false;
            Object ctxId = ctx.get("strategy_id");
            if (ctxId == null) return false;
            long want = toLong(ctxId);
            boolean any = ((List<Object>) idIn).stream().anyMatch(o -> toLong(o) == want);
            if (!any) return false;
        }

        // mih_* predicates: check market_indicator_history latest value
        // Value is pre-fetched into ctx under key "mih:<indicator>" by evaluateMarketIndicators()
        if (pred.containsKey("mih_indicator")) {
            String ind = (String) pred.get("mih_indicator");
            String ctxKey = "mih:" + ind;
            Object mihVal = ctx.get(ctxKey);
            if (mihVal == null) return false; // indicator not available
            if (pred.containsKey("mih_gt") && !gt(mihVal, pred.get("mih_gt"))) return false;
            if (pred.containsKey("mih_lt") && !lt(mihVal, pred.get("mih_lt"))) return false;
        }
        return true;
    }

    /**
     * Standalone market indicator evaluation — called by scheduler every 30 minutes.
     * Checks all active rules that contain {@code mih_indicator} predicate,
     * queries latest value from market_indicator_history, fires NOTIFY if threshold breached.
     * Independent of strategy signal firing.
     */
    public void evaluateMarketIndicators() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            List<AttentionRule> rules = ruleRepo.findActive(now);
            for (AttentionRule r : rules) {
                try {
                    Map<String, Object> pred = objectMapper.readValue(
                            r.getPredicateJson(), new TypeReference<>() {});
                    if (!pred.containsKey("mih_indicator")) continue;

                    String indicator = (String) pred.get("mih_indicator");
                    String symbol = pred.containsKey("symbol")
                            ? (String) pred.get("symbol") : "BTCUSDT";

                    // #383 — use Clean variant to filter error_flag=1 rows;
                    // raw findTopBy was returning flagged outlier values into TG ctx.
                    Double value = indicatorHistoryRepository
                            .findTopCleanBySymbolAndIndicator(symbol, indicator)
                            .map(h -> h.getValue() != null ? h.getValue().doubleValue() : null)
                            .orElse(null);
                    if (value == null) continue;

                    Map<String, Object> ctx = new java.util.HashMap<>();
                    ctx.put("symbol", symbol);
                    ctx.put("mih:" + indicator, value);

                    boolean matches = matchesPredicate(pred, ctx);

                    // #428 — hover hysteresis: 永遠跑 state machine,
                    // 即使 predicate 不 match 也要評估,讓 ELEVATED→NORMAL 的 EXIT 能觸發。
                    HysteresisAlertGuard.Decision dec = hysteresisGuard.evaluateBoolean(
                            HYSTERESIS_KEY_PREFIX + r.getId(),
                            matches, !matches,
                            ATTENTION_REMINDER_HOURS, null, now);

                    if (!matches) continue; // 不 match 不需 audit / TG (state 已 reset)

                    boolean firstFire = dec == HysteresisAlertGuard.Decision.ENTER
                            || dec == HysteresisAlertGuard.Decision.REMINDER;
                    applyMihAction(r, pred, ctx, now, firstFire);

                } catch (Throwable t) {
                    log.warn("[AttentionRule] mih eval rule id={}: {}", r.getId(), t.getMessage());
                }
            }
        } catch (Throwable t) {
            log.warn("[AttentionRule] evaluateMarketIndicators fatal: {}", t.getMessage());
        }
    }

    /** 訊號路徑 (signal-driven): 經 60min cooldown 去重後再發 TG。 */
    private void applyAction(AttentionRule rule, Map<String, Object> pred,
                             Map<String, Object> ctx, LocalDateTime now) {
        if (!recordHitAndCheckAction(rule, ctx, now)) return;
        if (!"NOTIFY".equals(rule.getAction())) return;
        if (!cooldownAllowsFire(rule.getId(), now)) return;
        sendNotification(rule, pred, ctx);
    }

    /** MIH 路徑: hysteresis 已決定 firstFire,跳過 cooldown,直接決定送不送。 */
    private void applyMihAction(AttentionRule rule, Map<String, Object> pred,
                                Map<String, Object> ctx, LocalDateTime now, boolean firstFire) {
        if (!recordHitAndCheckAction(rule, ctx, now)) return;
        if (!"NOTIFY".equals(rule.getAction())) return;
        if (!firstFire) return; // SUPPRESS / EXIT — hysteresis 已決定不送
        sendNotification(rule, pred, ctx);
    }

    /** 寫 audit + 增 hit_count;return false 表示 action 不支援不需繼續。 */
    private boolean recordHitAndCheckAction(AttentionRule rule, Map<String, Object> ctx, LocalDateTime now) {
        String action = rule.getAction();
        if (!SUPPORTED_ACTIONS_PHASE1.contains(action)) {
            log.warn("[AttentionRule] rule id={} action={} 尚未支援(Phase 2),skip", rule.getId(), action);
            return false;
        }
        Long strategyId = ctx.get("strategy_id") != null ? toLong(ctx.get("strategy_id")) : null;
        String symbol   = (String) ctx.get("symbol");
        String interval = (String) ctx.get("interval");
        auditWriter.logAttentionHit(strategyId, symbol, interval,
                rule.getName(), rule.getSeverity(), ctx);
        try {
            ruleRepo.incrementHit(rule.getId(), now);
        } catch (Throwable t) {
            log.warn("[AttentionRule] hit counter update failed: {}", t.getMessage());
        }
        return true;
    }

    /** 60min in-memory + DB fallback dedup;true=可發。 */
    private boolean cooldownAllowsFire(Long ruleId, LocalDateTime now) {
        LocalDateTime previous = lastNotifiedAt.putIfAbsent(ruleId, now);
        if (previous != null) {
            long minutesSince = ChronoUnit.MINUTES.between(previous, now);
            if (minutesSince < DEDUP_MINUTES) return false;
            return lastNotifiedAt.replace(ruleId, previous, now);
        }
        // 搶到空槽 — 查 DB 防 deploy 重啟後重複
        LocalDateTime cooldownSince = now.minusMinutes(DEDUP_MINUTES);
        boolean sentRecently = tgNotificationLogRepo.search(
                cooldownSince, null, null, null, ruleId,
                org.springframework.data.domain.PageRequest.of(0, 1)).size() > 0;
        if (sentRecently) {
            lastNotifiedAt.put(ruleId, now.minusMinutes(1));
            return false;
        }
        return true;
    }

    /** #428 — 渲染 TG 訊息。P1 omit null,P2 threshold compare,P5 不重複 severity 文字。 */
    private void sendNotification(AttentionRule rule, Map<String, Object> pred, Map<String, Object> ctx) {
        try {
            String emoji = switch (rule.getSeverity()) {
                case "CRITICAL" -> "🚨";
                case "WARN"     -> "⚠️";
                default          -> "🔔";
            };

            StringBuilder msg = new StringBuilder();
            msg.append(emoji).append(" <b>Attention: ").append(rule.getName()).append("</b>\n");

            // P2 — threshold 對比 (mih_indicator only)
            String thresholdLine = formatThresholdCompare(pred, ctx);
            if (thresholdLine != null) msg.append(thresholdLine).append('\n');

            // P1 — omit null;只列實際存在的欄位
            String fieldsLine = formatNonNullFields(ctx);
            if (!fieldsLine.isEmpty()) msg.append(fieldsLine).append('\n');

            String ctxLine = summarizeCtx(ctx);
            if (!ctxLine.isEmpty()) msg.append("ctx: ").append(ctxLine);

            notificationPort.broadcast(msg.toString().stripTrailing(), true);
        } catch (Exception e) {
            log.warn("[AttentionRule] NOTIFY TG 發送失敗: {}", e.getMessage());
        }
    }

    /** P1 — 用 "k: v" 列出 ctx 中非 null 的 symbol/interval/side。 */
    String formatNonNullFields(Map<String, Object> ctx) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, "symbol", ctx.get("symbol"));
        appendIfPresent(sb, "interval", ctx.get("interval"));
        appendIfPresent(sb, "side", ctx.get("side"));
        return sb.toString().stripTrailing();
    }

    private static void appendIfPresent(StringBuilder sb, String key, Object val) {
        if (val == null) return;
        String s = String.valueOf(val);
        if (s.isEmpty() || "null".equals(s)) return;
        if (sb.length() > 0) sb.append("  ");
        sb.append(key).append(": ").append(s);
    }

    /**
     * P2 — 從 predicate 的 mih_gt / mih_lt + ctx 中的 mih:&lt;ind&gt; 算出
     * 「實際值(threshold X,Y× 超標)」對比行;非 mih_indicator rule 回 null。
     */
    String formatThresholdCompare(Map<String, Object> pred, Map<String, Object> ctx) {
        Object indObj = pred.get("mih_indicator");
        if (!(indObj instanceof String ind)) return null;
        Object actualObj = ctx.get("mih:" + ind);
        if (!(actualObj instanceof Number actualNum)) return null;
        double actual = actualNum.doubleValue();

        Object gt = pred.get("mih_gt");
        Object lt = pred.get("mih_lt");
        if (!(gt instanceof Number) && !(lt instanceof Number)) return null;

        StringBuilder line = new StringBuilder("觸發: ").append(formatNumber(actual));
        StringBuilder cmp  = new StringBuilder();
        if (gt instanceof Number g) {
            double thr = g.doubleValue();
            cmp.append("threshold > ").append(formatNumber(thr));
            if (thr != 0) {
                double ratio = actual / thr;
                cmp.append(", ").append(String.format("%.2f×", ratio));
                cmp.append(actual >= thr ? " 超標" : " 未到");
            }
        }
        if (lt instanceof Number l) {
            double thr = l.doubleValue();
            if (cmp.length() > 0) cmp.append(" / ");
            cmp.append("threshold < ").append(formatNumber(thr));
            if (thr != 0) {
                double ratio = actual / thr;
                cmp.append(", ").append(String.format("%.2f×", ratio));
                cmp.append(actual <= thr ? " 觸發" : " 未到");
            }
        }
        if ("CRITICAL".equals(pred.getOrDefault("severity", "")) || isOver(actual, gt, lt)) {
            // 不再 inject 額外 emoji — emoji 由 message head 統一提供 (P5)
        }
        line.append(" (").append(cmp).append(')');
        return line.toString();
    }

    private static boolean isOver(double actual, Object gt, Object lt) {
        if (gt instanceof Number g && actual > g.doubleValue()) return true;
        if (lt instanceof Number l && actual < l.doubleValue()) return true;
        return false;
    }

    private static String formatNumber(double v) {
        if (Math.abs(v) >= 100 || v == Math.floor(v)) return String.format("%.2f", v);
        return String.format("%.4f", v);
    }

    private String summarizeCtx(Map<String, Object> ctx) {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"rsi", "fg", "score", "nn", "whale", "adx", "volume_ratio"}) {
            if (ctx.containsKey(k)) {
                Object v = ctx.get(k);
                String fmt = (v instanceof Double d) ? String.format("%.4f", d) : String.valueOf(v);
                sb.append(k).append('=').append(fmt).append(' ');
            }
        }
        for (String k : new String[]{"gemini_style", "gemini_regime"}) {
            if (ctx.containsKey(k)) sb.append(k).append('=').append(ctx.get(k)).append(' ');
        }
        // mih:* keys from market indicator evaluation
        ctx.entrySet().stream()
                .filter(e -> e.getKey().startsWith("mih:"))
                .forEach(e -> {
                    String ind = e.getKey().substring(4);
                    Object v = e.getValue();
                    String fmt = (v instanceof Double d) ? String.format("%.4f", d) : String.valueOf(v);
                    sb.append(ind).append('=').append(fmt).append(' ');
                });
        return sb.toString().trim();
    }

    public static boolean isSupportedPhase1Action(String action) { return SUPPORTED_ACTIONS_PHASE1.contains(action); }
    public static Set<String> supportedSeverities() { return SUPPORTED_SEVERITIES; }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private static boolean gt(Object ctxVal, Object threshold) {
        if (ctxVal == null) return false;
        return toDouble(ctxVal) > toDouble(threshold);
    }

    private static boolean lt(Object ctxVal, Object threshold) {
        if (ctxVal == null) return false;
        return toDouble(ctxVal) < toDouble(threshold);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }

    public record DryRunResult(boolean parseOk, boolean match, String error) {}
}
