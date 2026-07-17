package com.agora.repository.trading;

import com.agora.model.RuntimeDecisionEvidence;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RuntimeDecisionEvidenceRepository extends JpaRepository<RuntimeDecisionEvidence, Long> {

    /**
     * Explicit current-cohort episode binding. The join is only through the
     * persisted live-signal id; symbol/time proximity is deliberately not a
     * join key. Provider order identity remains mandatory even though exact
     * all-fill/signed-fee evidence is not yet available.
     */
    @Query(value = """
            SELECT e.decision_id AS decisionId,
                   e.live_signal_id AS liveSignalId,
                   ls.exchange_order_id AS providerOrderId,
                   e.evidence_time AS evidenceTime,
                   e.execution_preview_json AS executionPreviewJson,
                   ls.exit_time AS exitTime,
                   ls.realized_pnl AS realizedPnl
            FROM bt_runtime_decision_evidence e
            INNER JOIN bt_live_signal ls ON ls.id = e.live_signal_id
            WHERE e.strategy_id = :strategyId
              AND e.evidence_time >= :effectiveFrom
              AND COALESCE(e.order_sent, 0) = 1
              AND e.decision_id IS NOT NULL
              AND e.live_signal_id IS NOT NULL
              AND COALESCE(ls.exchange_order_id, '') <> ''
              AND ls.exit_time IS NOT NULL
            ORDER BY e.decision_id ASC
            """, nativeQuery = true)
    List<CanonicalEpisodeBinding> findCanonicalEpisodeBindings(
            @Param("strategyId") Long strategyId,
            @Param("effectiveFrom") LocalDateTime effectiveFrom);

    interface CanonicalEpisodeBinding {
        Long getDecisionId();
        Long getLiveSignalId();
        String getProviderOrderId();
        LocalDateTime getEvidenceTime();
        String getExecutionPreviewJson();
        LocalDateTime getExitTime();
        java.math.BigDecimal getRealizedPnl();
    }

    Optional<RuntimeDecisionEvidence> findByDecisionId(Long decisionId);

    List<RuntimeDecisionEvidence> findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
            String policyMode, LocalDateTime since);

    List<RuntimeDecisionEvidence> findByLiveSignalIdOrderByEvidenceTimeAsc(Long liveSignalId);

    Optional<RuntimeDecisionEvidence> findFirstByLiveSignalIdAndPolicyModeOrderByEvidenceTimeDesc(
            Long liveSignalId, String policyMode);

    List<RuntimeDecisionEvidence> findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
            String policyMode, String symbol, String intervalCode, Pageable pageable);

    @Query("SELECT e FROM RuntimeDecisionEvidence e " +
           "WHERE e.evidenceTime >= :since " +
           "  AND (:symbol IS NULL OR e.symbol = :symbol) " +
           "ORDER BY e.evidenceTime DESC")
    List<RuntimeDecisionEvidence> findRecent(
            @Param("since") LocalDateTime since,
            @Param("symbol") String symbol,
            Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT e.id,
                       COALESCE(
                         CAST(JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entryPrice')) AS DECIMAL(20,8)),
                         CAST(JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.entry')) AS DECIMAL(20,8)),
                         CAST(JSON_UNQUOTE(JSON_EXTRACT(e.features_snapshot_json, '$.currentPrice')) AS DECIMAL(20,8))
                       ) AS entry_price,
                       (
                         SELECT MAX(k.high_price)
                         FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
                         WHERE k.symbol = :symbol
                           AND k.interval_code = '1h'
                           AND k.source = 'okx'
                           AND k.open_time > e.evidence_time
                           AND k.open_time <= DATE_ADD(e.evidence_time, INTERVAL 1 HOUR)
                       ) AS max_high_1h,
                       (
                         SELECT k.close_price
                         FROM md_kline k FORCE INDEX (idx_md_kline_sym_int_src_open)
                         WHERE k.symbol = :symbol
                           AND k.interval_code = '1h'
                           AND k.source = 'okx'
                           AND k.open_time >= DATE_ADD(e.evidence_time, INTERVAL 1 HOUR)
                         ORDER BY k.open_time ASC
                         LIMIT 1
                       ) AS close_after_1h
                FROM bt_runtime_decision_evidence e FORCE INDEX (idx_rt_decision_evidence_symbol_time)
                WHERE e.symbol = :symbol
                  AND e.strategy_id = :strategyId
                  AND e.evidence_time >= :since
                  AND e.evidence_time <= :maturedBefore
                  AND COALESCE(e.order_sent, 0) = 0
                  AND (e.selected_action = 'BLOCK' OR e.policy_mode = 'BLOCK')
                  AND (
                       COALESCE(e.terminal_blocker, '') LIKE '%SCORE_BUY_POST_SCOUT%'
                    OR COALESCE(e.suppression_reason, '') LIKE '%SCORE_BUY_POST_SCOUT%'
                    OR COALESCE(e.blocker_reason, '') LIKE '%SCORE_BUY_POST_SCOUT%'
                    OR COALESCE(e.terminal_blocker, '') LIKE 'POST_SCOUT_ADD_NOT_ELIGIBLE%'
                    OR COALESCE(e.suppression_reason, '') LIKE 'POST_SCOUT_ADD_NOT_ELIGIBLE%'
                    OR COALESCE(e.blocker_reason, '') LIKE '%POST_SCOUT_ADD_NOT_ELIGIBLE%'
                  )
            ) x
            WHERE x.entry_price IS NOT NULL
              AND x.entry_price > 0
              AND (
                    (x.close_after_1h IS NOT NULL
                     AND ((x.close_after_1h - x.entry_price) / x.entry_price * 100) >= :returnThresholdPct)
                 OR (x.max_high_1h IS NOT NULL
                     AND ((x.max_high_1h - x.entry_price) / x.entry_price * 100) >= :mfeThresholdPct)
              )
            """, nativeQuery = true)
    long countScoreBuyPostScoutMissedAlphaBlocksSince(
            @Param("symbol") String symbol,
            @Param("strategyId") Long strategyId,
            @Param("since") LocalDateTime since,
            @Param("maturedBefore") LocalDateTime maturedBefore,
            @Param("returnThresholdPct") double returnThresholdPct,
            @Param("mfeThresholdPct") double mfeThresholdPct);
}
