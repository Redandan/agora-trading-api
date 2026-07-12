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

    Optional<RuntimeDecisionEvidence> findByDecisionId(Long decisionId);

    List<RuntimeDecisionEvidence> findByPolicyModeAndEvidenceTimeAfterOrderByEvidenceTimeAsc(
            String policyMode, LocalDateTime since);

    List<RuntimeDecisionEvidence> findByLiveSignalIdOrderByEvidenceTimeAsc(Long liveSignalId);

    Optional<RuntimeDecisionEvidence> findFirstByLiveSignalIdAndPolicyModeOrderByEvidenceTimeDesc(
            Long liveSignalId, String policyMode);

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
