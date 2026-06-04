package com.agora.repository.trading;

import com.agora.model.StrategyOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StrategyOverrideRepository extends JpaRepository<StrategyOverride, Long> {

    /**
     * 取指定 strategy 當前有效的 override(未 revoke 且未過期)。
     * 同策略可能有多筆(不同 symbol 範圍),caller 再用 symbol/interval 過濾。
     */
    @Query("SELECT o FROM StrategyOverride o " +
           "WHERE o.strategyId = :strategyId " +
           "  AND o.revokedAt IS NULL " +
           "  AND o.expiresAt > :now " +
           "ORDER BY o.createdAt DESC")
    List<StrategyOverride> findActive(@Param("strategyId") Long strategyId,
                                      @Param("now") LocalDateTime now);

    /**
     * 全域掃描所有當前有效 override(供 listActiveOverrides MCP tool)。
     */
    @Query("SELECT o FROM StrategyOverride o " +
           "WHERE o.revokedAt IS NULL AND o.expiresAt > :now " +
           "ORDER BY o.expiresAt ASC")
    List<StrategyOverride> findAllActive(@Param("now") LocalDateTime now);

    /**
     * 找出「剛結束」的 override:有效結束時間(revoked_at 或 expires_at)落在
     * (since, now] 區間內。供 MetaControlAttributionScheduler 每小時掃描
     * 補算 attribution。
     *
     * <p>同一 override 反覆被 scanner 命中無副作用 —— attribution repository
     * 的 UNIQUE (override_type, override_id) 會讓 service 直接 return 既有。
     */
    @Query("SELECT o FROM StrategyOverride o " +
           "WHERE o.action = :action " +
           "  AND COALESCE(o.revokedAt, o.expiresAt) > :since " +
           "  AND COALESCE(o.revokedAt, o.expiresAt) <= :now " +
           "ORDER BY o.id ASC")
    List<StrategyOverride> findRecentlyEnded(
            @Param("action") String action,
            @Param("since") LocalDateTime since,
            @Param("now") LocalDateTime now);
}
