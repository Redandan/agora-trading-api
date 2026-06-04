package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系統決策審計 — 每次 signal 評估 / 過濾 / 下單 / 平倉 皆寫一筆。
 *
 * <p>Async 寫入(透過 DecisionAuditWriter),寫失敗不得影響主流程。
 *
 * <p>context_json 強制只存純量(rsi/score/nn/fg/side),禁塞 klines / indicator array,
 * 避免單筆 > 1KB,百萬列後爆量。
 */
@Data
@Entity
@Table(name = "bt_decision_audit", indexes = {
        @Index(name = "idx_audit_time",        columnList = "event_time"),
        @Index(name = "idx_audit_strat_time",  columnList = "strategy_id,event_time"),
        @Index(name = "idx_audit_symbol_time", columnList = "symbol,event_time"),
        @Index(name = "idx_audit_event_time",  columnList = "event_type,event_time"),
        @Index(name = "idx_audit_live_signal", columnList = "live_signal_id")
})
public class BtDecisionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** soft ref -> bt_strategy.id;null=system-level event */
    @Column(name = "strategy_id")
    private Long strategyId;

    @Column(length = 20)
    private String symbol;

    @Column(name = "interval_code", length = 10)
    private String intervalCode;

    @Column(name = "bar_open_time")
    private LocalDateTime barOpenTime;

    /** SIGNAL_EVAL / SIGNAL_BUY / SIGNAL_SELL / FILTER_BLOCK / AUTOTRADE_OK / AUTOTRADE_FAIL / EXIT / OVERRIDE_APPLIED / ATTENTION_HIT */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    /** PASS / BLOCKED / ERROR / INFO */
    @Column(nullable = false, length = 16)
    private String outcome;

    /** LongAiFilter / ShortAiFilter / DailyLossGuard / StrategyOverride / HintDisable / AttentionRule */
    @Column(length = 64)
    private String blocker;

    @Column(length = 500)
    private String reason;

    /** 純量快照 JSON 字串:{"score":0.72,"nn":0.85,"rsi":48,"fg":62,"side":"LONG"} */
    @Column(name = "context_json", columnDefinition = "JSON")
    private String contextJson;

    /** soft ref -> bt_live_signal.id */
    @Column(name = "live_signal_id")
    private Long liveSignalId;
}
