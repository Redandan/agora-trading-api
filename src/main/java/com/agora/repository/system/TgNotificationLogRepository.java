package com.agora.repository.system;

import com.agora.model.TgNotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TgNotificationLogRepository extends JpaRepository<TgNotificationLog, Long> {

    /** 按時間範圍 + 可選 level + 可選 source 查詢（MCP 搜尋用）。 */
    @Query("SELECT t FROM TgNotificationLog t " +
           "WHERE t.sentAt >= :from " +
           "AND (:to IS NULL OR t.sentAt <= :to) " +
           "AND (:level IS NULL OR t.level = :level) " +
           "AND (:source IS NULL OR t.source LIKE :source) " +
           "AND (:ruleId IS NULL OR t.ruleId = :ruleId) " +
           "ORDER BY t.sentAt DESC")
    List<TgNotificationLog> search(
            @Param("from")   LocalDateTime from,
            @Param("to")     LocalDateTime to,
            @Param("level")  String level,
            @Param("source") String source,
            @Param("ruleId") Long ruleId,
            org.springframework.data.domain.Pageable pageable);

    /** 統計各 source 在時間範圍內的發送次數（優化分析用）。 */
    @Query("SELECT t.source, t.level, COUNT(t) FROM TgNotificationLog t " +
           "WHERE t.sentAt >= :from " +
           "GROUP BY t.source, t.level ORDER BY COUNT(t) DESC")
    List<Object[]> countBySourceAndLevel(@Param("from") LocalDateTime from);

    /** #338 scanIndicatorAccuracy — 列出 since 後出現的 distinct source，用於批次跑事後正確率。 */
    @Query("SELECT DISTINCT t.source FROM TgNotificationLog t " +
           "WHERE t.sentAt >= :from AND t.source IS NOT NULL ORDER BY t.source")
    List<String> findDistinctSourcesSince(@Param("from") LocalDateTime from);

    /** 清除 30 天前的記錄。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM TgNotificationLog t WHERE t.sentAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);

    /**
     * #362 — TgNotificationDeduper DB warm-up.
     *
     * <p>Returns the most recent {@code sentAt} for log rows where
     * {@code source = :source} and {@code sentAt > :cutoff}. {@code null}
     * if no such row exists. Used by the deduper on cache miss
     * (post-restart) to decide whether a same-key send happened recently
     * in DB.
     *
     * <p>Caller must pass a stable dedup key as {@code source} (e.g.
     * {@code "AttentionRule:18:2026-05-03T14"}) so the row written by
     * {@link com.agora.service.TelegramService#sendAlert} matches.
     */
    @Query("SELECT MAX(t.sentAt) FROM TgNotificationLog t " +
           "WHERE t.source = :source AND t.sentAt > :cutoff")
    LocalDateTime findLatestSentAtBySourceAfter(
            @Param("source") String source,
            @Param("cutoff") LocalDateTime cutoff);
}
