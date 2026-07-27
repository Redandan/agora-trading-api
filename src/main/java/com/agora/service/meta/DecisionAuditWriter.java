package com.agora.service.meta;

import com.agora.model.BtDecisionAudit;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.service.trading.RuntimeDecisionEvidenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Meta-Control 決策審計 async 寫入器。
 *
 * <h3>不變量(務必遵守)</h3>
 * <ul>
 *   <li>所有寫入 {@link Async @Async("metaAuditExecutor")},絕不 block 主流程</li>
 *   <li>catch all Throwable,寫入失敗 log warn,不往上 throw</li>
 *   <li>時間統一 UTC({@code ZoneOffset.UTC}),runtime timezone 偏移防護</li>
 *   <li>context 強制純量(BigDecimal/Integer/String/Boolean),禁塞 array / klines</li>
 * </ul>
 *
 * <h3>@Async self-invocation 陷阱</h3>
 * 必須是**獨立 bean**,不是 LiveSignalEvaluator 的 private method —— 同 class
 * 呼叫 @Async 會 bypass Spring AOP proxy 變成同步執行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional   // #385 — defensive: each public @Async entry opens its own tx,
                 // matches #329 fix pattern. Private save() is reached via the
                 // tx-proxied public method, so SimpleJpaRepository.save() runs
                 // with a guaranteed surrounding tx context (no silent fail).
public class DecisionAuditWriter {

    private final BtDecisionAuditRepository repo;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RuntimeDecisionEvidenceService> runtimeDecisionEvidenceService;

    // ===== High-level helpers(避免 LiveSignalEvaluator 每次塞 10 個欄位)=====

    /** Signal evaluation 完成(BUY/SELL/HOLD 皆可記)。 */
    @Async("metaAuditExecutor")
    public void logSignalEval(Long strategyId, String symbol, String intervalCode,
                              LocalDateTime barOpenTime, String side,
                              Map<String, Object> context) {
        save(build("SIGNAL_EVAL", "INFO", strategyId, symbol, intervalCode,
                barOpenTime, null, side, context, null));
    }

    /** Record a pre-order block; historical blocker names remain readable. */
    @Async("metaAuditExecutor")
    public void logFilterBlock(Long strategyId, String symbol, String intervalCode,
                               String blocker, String reason,
                               Map<String, Object> context) {
        logFilterBlock(strategyId, symbol, intervalCode, blocker, reason, context, null);
    }

    /** Filter block after a live signal row has already been created. */
    @Async("metaAuditExecutor")
    public void logFilterBlock(Long strategyId, String symbol, String intervalCode,
                               String blocker, String reason,
                               Map<String, Object> context, Long liveSignalId) {
        save(build("FILTER_BLOCK", "BLOCKED", strategyId, symbol, intervalCode,
                null, blocker, reason, context, liveSignalId));
    }

    /**
     * BUY/SELL signal was evaluated but intentionally skipped before/around
     * order placement. This is not a model filter and should not pollute
     * FILTER_BLOCK alpha statistics; it exists to close observability gaps
     * such as duplicate bar, cooldown, existing open position dedup, or
     * trading disabled.
     */
    @Async("metaAuditExecutor")
    public void logEntrySkip(Long strategyId, String symbol, String intervalCode,
                             LocalDateTime barOpenTime, String blocker, String reason,
                             Map<String, Object> context) {
        logEntrySkip(strategyId, symbol, intervalCode, barOpenTime, blocker, reason, context, null);
    }

    /** Entry skip with a live_signal_id when a row already exists. */
    @Async("metaAuditExecutor")
    public void logEntrySkip(Long strategyId, String symbol, String intervalCode,
                             LocalDateTime barOpenTime, String blocker, String reason,
                             Map<String, Object> context, Long liveSignalId) {
        save(build("ENTRY_SKIP", "BLOCKED", strategyId, symbol, intervalCode,
                barOpenTime, blocker, reason, context, liveSignalId));
    }

    /** autoTrade 成功,含 live_signal_id。 */
    @Async("metaAuditExecutor")
    public void logAutoTradeOk(Long strategyId, String symbol, Long liveSignalId,
                               Map<String, Object> context) {
        save(build("AUTOTRADE_OK", "PASS", strategyId, symbol, null,
                null, null, null, context, liveSignalId));
    }

    /** autoTrade 失敗(餘額不足 / OKX 錯誤 / OCO 重試用光)。 */
    @Async("metaAuditExecutor")
    public void logAutoTradeFail(Long strategyId, String symbol, String reason,
                                 Map<String, Object> context) {
        save(build("AUTOTRADE_FAIL", "ERROR", strategyId, symbol, null,
                null, null, reason, context, null));
    }

    /** 平倉事件(TP/SL/手動/信號反轉)。 */
    @Async("metaAuditExecutor")
    public void logExit(Long strategyId, String symbol, Long liveSignalId,
                        String reason, Map<String, Object> context) {
        save(build("EXIT", "INFO", strategyId, symbol, null,
                null, null, reason, context, liveSignalId));
    }

    /**
     * #450 — Strategy.adjustExit 觸發的 OCO 修改事件(非平倉,只調 TP/SL)。
     * Dry-run 模式也用此 method 記錄(會在 reason 標 [DRY-RUN])。
     */
    @Async("metaAuditExecutor")
    public void logExitAdjustment(Long strategyId, String symbol, Long liveSignalId,
                                   String reason, Map<String, Object> context) {
        save(build("EXIT_ADJUST", "INFO", strategyId, symbol, null,
                null, null, reason, context, liveSignalId));
    }

    /** Claude 透過 MCP 工具套用 override(PAUSE / hint override / attention rule)。 */
    @Async("metaAuditExecutor")
    public void logOverrideApplied(Long strategyId, String symbol, String blocker,
                                   String reason) {
        save(build("OVERRIDE_APPLIED", "INFO", strategyId, symbol, null,
                null, blocker, reason, null, null));
    }

    /**
     * Synchronous guard for live autonomous execution.
     * If runtime evidence is disabled, unavailable, or cannot be written, callers
     * must suppress the real order. This is intentionally not @Async.
     */
    public boolean logAutonomousExecutionIntentSync(Long strategyId, String symbol,
                                                    String intervalCode, Long liveSignalId,
                                                    Map<String, Object> context) {
        try {
            BtDecisionAudit saved = repo.save(build("ATTENTION_HIT", "INFO", strategyId, symbol, intervalCode,
                    null, "AutonomousExecutionIntent", "Live autonomous execution evidence required",
                    context, liveSignalId));
            RuntimeDecisionEvidenceService service = runtimeDecisionEvidenceService.getIfAvailable();
            if (service == null || !service.isEnabled()) {
                log.warn("[RuntimeEvidence] live autonomous order blocked: evidence service unavailable or disabled decisionId={} symbol={}",
                        saved.getId(), symbol);
                return false;
            }
            boolean written = service.writeFromDecisionAudit(saved).isPresent();
            if (!written) {
                log.warn("[RuntimeEvidence] live autonomous order blocked: evidence write failed decisionId={} symbol={}",
                        saved.getId(), symbol);
            }
            return written;
        } catch (Throwable t) {
            log.warn("[RuntimeEvidence] live autonomous order blocked: audit/evidence write exception symbol={} err={}",
                    symbol, t.getMessage());
            return false;
        }
    }

    // ===== Internal =====

    private BtDecisionAudit build(String eventType, String outcome,
                                   Long strategyId, String symbol, String intervalCode,
                                   LocalDateTime barOpenTime, String blocker, String reason,
                                   Map<String, Object> context, Long liveSignalId) {
        BtDecisionAudit a = new BtDecisionAudit();
        a.setEventTime(LocalDateTime.now(ZoneOffset.UTC));
        a.setStrategyId(strategyId);
        a.setSymbol(symbol);
        a.setIntervalCode(intervalCode);
        a.setBarOpenTime(barOpenTime);
        a.setEventType(eventType);
        a.setOutcome(outcome);
        a.setBlocker(blocker);
        a.setReason(truncate(reason, 500));
        a.setContextJson(serializeContext(context));
        a.setLiveSignalId(liveSignalId);
        return a;
    }

    private void save(BtDecisionAudit audit) {
        try {
            BtDecisionAudit saved = repo.save(audit);
            try {
                runtimeDecisionEvidenceService.ifAvailable(service -> service.writeFromDecisionAudit(saved));
            } catch (Throwable t) {
                log.warn("[RuntimeEvidence] sidecar write failed: decisionId={} symbol={} err={}",
                        saved.getId(), saved.getSymbol(), t.getMessage());
            }
        } catch (Throwable t) {
            // 絕不 throw — audit 寫失敗不得影響主交易流程
            log.warn("[DecisionAudit] write failed: event={} symbol={} err={}",
                    audit.getEventType(), audit.getSymbol(), t.getMessage());
        }
    }

    private String serializeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            log.warn("[DecisionAudit] context serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
