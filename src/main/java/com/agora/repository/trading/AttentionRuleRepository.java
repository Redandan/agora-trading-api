package com.agora.repository.trading;

import com.agora.model.AttentionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AttentionRuleRepository extends JpaRepository<AttentionRule, Long> {

    /**
     * 取所有當前生效規則(enabled=true 且未過期)。
     * AttentionRuleEvaluator 每 bar 觸發時用此。
     */
    @Query("SELECT r FROM AttentionRule r " +
           "WHERE r.enabled = TRUE " +
           "  AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
    List<AttentionRule> findActive(@Param("now") LocalDateTime now);

    /** hit_count +1 + last_hit_at 更新(async 寫入,不 block evaluate)。 */
    @Transactional
    @Modifying
    @Query("UPDATE AttentionRule r SET r.hitCount = r.hitCount + 1, r.lastHitAt = :hitAt " +
           "WHERE r.id = :id")
    int incrementHit(@Param("id") Long id, @Param("hitAt") LocalDateTime hitAt);

    /** 週報用:所有 enabled=true 規則(含已過期的,依 createdAt 降冪)。 */
    List<AttentionRule> findByEnabledTrueOrderByCreatedAtDesc();
}
