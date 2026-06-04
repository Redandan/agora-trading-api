package com.agora.repository.system;

import com.agora.model.IpRiskScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IpRiskScoreRepository extends JpaRepository<IpRiskScore, String> {

    /** #367 startup hydration — load all rows touched in last N days. */
    List<IpRiskScore> findByLastUpdatedAfter(LocalDateTime since);

    /** Native upsert to avoid race when two requests hit same IP simultaneously. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO ip_risk_score (ip_address, score, activity_count) " +
                   "VALUES (:ip, :score, :activity) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "  score = LEAST(100, score + VALUES(score)), " +
                   "  activity_count = activity_count + VALUES(activity_count)",
            nativeQuery = true)
    void upsertDelta(@Param("ip") String ip,
                     @Param("score") int scoreDelta,
                     @Param("activity") int activityDelta);
}
