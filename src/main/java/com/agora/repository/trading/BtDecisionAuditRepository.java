package com.agora.repository.trading;

import com.agora.model.BtDecisionAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface BtDecisionAuditRepository extends JpaRepository<BtDecisionAudit, Long> {

    /** 最近 N 分鐘決策(供 listRecentDecisions MCP tool)。 */
    @Query("SELECT a FROM BtDecisionAudit a " +
           "WHERE a.eventTime > :since " +
           "  AND (:symbol IS NULL OR a.symbol = :symbol) " +
           "ORDER BY a.eventTime DESC")
    List<BtDecisionAudit> findRecent(@Param("since") LocalDateTime since,
                                     @Param("symbol") String symbol,
                                     Pageable pageable);

    /** 依 strategy 查近期 audit(供 analyzeStrategyTrades / 事後分析用)。 */
    List<BtDecisionAudit> findByStrategyIdAndEventTimeBetweenOrderByEventTimeDesc(
            Long strategyId, LocalDateTime from, LocalDateTime to);

    /** 依 live_signal 反查該倉位所有決策(供 getDecisionContext 展開關聯)。 */
    List<BtDecisionAudit> findByLiveSignalIdOrderByEventTimeAsc(Long liveSignalId);

    /**
     * 分批刪除 N 天前資料(DecisionAuditCleanupScheduler 用)。
     * LIMIT 由 @Query 的 native SQL 支援;JPQL 不支援 LIMIT。
     */
    @Modifying
    @Query(value = "DELETE FROM bt_decision_audit WHERE event_time < :cutoff LIMIT :batchSize",
           nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff,
                        @Param("batchSize") int batchSize);

    /**
     * 近 N 時間的 event_type 計數(供 SessionBrief 顯示 24h 決策量分布)。
     * 回傳 [event_type, count] 二元組,依 count 降冪。
     */
    @Query("SELECT a.eventType, COUNT(a) FROM BtDecisionAudit a " +
           "WHERE a.eventTime > :since " +
           "GROUP BY a.eventType " +
           "ORDER BY COUNT(a) DESC")
    List<Object[]> countByEventTypeSince(@Param("since") LocalDateTime since);

    /** FILTER_BLOCK 詳情(供日匯報 ≥3 次警告)。 */
    @Query("SELECT a FROM BtDecisionAudit a " +
           "WHERE a.eventType = 'FILTER_BLOCK' AND a.eventTime > :since " +
           "ORDER BY a.eventTime DESC")
    List<BtDecisionAudit> findFilterBlockSince(@Param("since") LocalDateTime since);

    @Query("SELECT a FROM BtDecisionAudit a " +
           "WHERE a.eventTime >= :since " +
           "  AND a.eventTime <= :until " +
           "  AND (:symbol IS NULL OR a.symbol = :symbol) " +
           "  AND (:strategyId IS NULL OR a.strategyId = :strategyId) " +
           "  AND (:eventTypesEmpty = true OR a.eventType IN :eventTypes) " +
           "ORDER BY a.eventTime DESC")
    List<BtDecisionAudit> findWindow(
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until,
            @Param("symbol") String symbol,
            @Param("strategyId") Long strategyId,
            @Param("eventTypesEmpty") boolean eventTypesEmpty,
            @Param("eventTypes") Collection<String> eventTypes,
            Pageable pageable);
}
