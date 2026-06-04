package com.agora.repository.trading;

import com.agora.enums.trading.AttributionStatusEnum;
import com.agora.enums.trading.OverrideTypeEnum;
import com.agora.model.MetaControlAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetaControlAttributionRepository
        extends JpaRepository<MetaControlAttribution, Long> {

    /** 冪等查詢:scheduler 重算同一 override 時先 check 是否已存在。 */
    Optional<MetaControlAttribution> findByOverrideTypeAndOverrideId(
            OverrideTypeEnum overrideType, Long overrideId);

    /** 近 N 天所有 SUCCESS 的 attribution,供 summarizeRecent 聚合。 */
    @Query("SELECT a FROM MetaControlAttribution a " +
           "WHERE a.computationStatus = :status " +
           "  AND a.computedAt >= :since " +
           "ORDER BY a.computedAt DESC")
    List<MetaControlAttribution> findRecentByStatus(
            @Param("since") LocalDateTime since,
            @Param("status") AttributionStatusEnum status);

    /** 近 N 天任意狀態,供 scheduler 偵錯 / brief 顯示 */
    List<MetaControlAttribution> findByComputedAtAfterOrderByComputedAtDesc(
            LocalDateTime since);
}
