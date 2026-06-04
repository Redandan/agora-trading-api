package com.agora.repository.trading;

import com.agora.model.SignalOutcomeVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

public interface SignalOutcomeVerificationRepository
        extends JpaRepository<SignalOutcomeVerification, Long> {

    boolean existsByLiveSignalId(Long liveSignalId);

    @Query("""
        SELECT COUNT(v) > 0
        FROM SignalOutcomeVerification v
        WHERE v.createdAt > :since
          AND v.symbol = :symbol
          AND v.intervalCode = :intervalCode
          AND v.side = :side
          AND v.decision = :decision
          AND v.decisionLayer = :decisionLayer
          AND v.entryPrice = :entryPrice
          AND v.slPrice = :slPrice
          AND v.tpPrice = :tpPrice
          AND v.outcome = 'WATCHING'
        """)
    boolean existsWatchingDuplicateShapeSince(
            @Param("since") LocalDateTime since,
            @Param("symbol") String symbol,
            @Param("intervalCode") String intervalCode,
            @Param("side") String side,
            @Param("decision") String decision,
            @Param("decisionLayer") String decisionLayer,
            @Param("entryPrice") BigDecimal entryPrice,
            @Param("slPrice") BigDecimal slPrice,
            @Param("tpPrice") BigDecimal tpPrice);

    /** 批次查已 finalized 的驗證結果（供 analyzeBlockedSignalOutcomes 替換 kline scan）。 */
    @Query("SELECT v FROM SignalOutcomeVerification v " +
           "WHERE v.liveSignalId IN :ids AND v.finalizedAt IS NOT NULL")
    List<SignalOutcomeVerification> findFinalizedByLiveSignalIds(
            @Param("ids") java.util.Collection<Long> ids);

    /** 最近 N 筆（全部 outcome 或指定 outcome），依建立時間倒序。 */
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM SignalOutcomeVerification v WHERE v.createdAt > :since " +
        "AND (:outcome IS NULL OR v.outcome = :outcome) " +
        "ORDER BY v.createdAt DESC")
    List<SignalOutcomeVerification> findRecent(
        @Param("since") java.time.LocalDateTime since,
        @Param("outcome") String outcome,
        org.springframework.data.domain.Pageable pageable);

    /** Rows since a cutoff, ordered newest first; used by MCP reports for in-memory de-dup diagnostics. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM SignalOutcomeVerification v WHERE v.createdAt > :since " +
        "ORDER BY v.createdAt DESC")
    List<SignalOutcomeVerification> findSince(
        @Param("since") java.time.LocalDateTime since);

    /** 7 天滾動正確率，按 decision_layer + decision 分組（用於 alert 和 MCP report）。 */
    @Query(value = """
        SELECT decision_layer, decision,
               SUM(CASE WHEN outcome='CORRECT' THEN 1 ELSE 0 END) AS correct_cnt,
               SUM(CASE WHEN outcome='WRONG'   THEN 1 ELSE 0 END) AS wrong_cnt,
               SUM(CASE WHEN outcome='WATCHING' THEN 1 ELSE 0 END) AS watching_cnt
        FROM signal_outcome_verification
        WHERE created_at > :since
        GROUP BY decision_layer, decision
        ORDER BY correct_cnt + wrong_cnt DESC
        """, nativeQuery = true)
    List<Object[]> accuracyByLayerSince(@Param("since") LocalDateTime since);

    /** De-duplicated 7d rolling accuracy by signal shape, for alerts and compact reports. */
    @Query(value = """
        SELECT decision_layer, decision,
               SUM(CASE WHEN outcome='CORRECT' THEN 1 ELSE 0 END) AS correct_cnt,
               SUM(CASE WHEN outcome='WRONG'   THEN 1 ELSE 0 END) AS wrong_cnt,
               SUM(CASE WHEN outcome='WATCHING' THEN 1 ELSE 0 END) AS watching_cnt
        FROM (
            SELECT symbol, interval_code, side, decision, decision_layer,
                   entry_price, sl_price, tp_price, outcome, finalized_at
            FROM signal_outcome_verification
            WHERE created_at > :since
            GROUP BY symbol, interval_code, side, decision, decision_layer,
                     entry_price, sl_price, tp_price, outcome, finalized_at
        ) deduped
        GROUP BY decision_layer, decision
        ORDER BY correct_cnt + wrong_cnt DESC
        """, nativeQuery = true)
    List<Object[]> accuracyByLayerSinceDedup(@Param("since") LocalDateTime since);
}
