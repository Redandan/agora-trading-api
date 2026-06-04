package com.agora.repository.trading;

import com.agora.model.HintOverride;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HintOverrideRepository extends JpaRepository<HintOverride, Long> {

    /**
     * 取指定 (symbol, timeframe) 當前有效 hint_override 的最新一筆。
     * HintMerger 在 strategy evaluate 時查此,per-field 覆蓋 gemini_market_hint。
     * Spring Data JPA 不允許 Optional + Pageable,故 return List + caller 取 first。
     */
    @Query("SELECT h FROM HintOverride h " +
           "WHERE h.symbol = :symbol AND h.timeframe = :timeframe " +
           "  AND h.revokedAt IS NULL AND h.expiresAt > :now " +
           "ORDER BY h.priority DESC, h.createdAt DESC")
    List<HintOverride> findActive(@Param("symbol") String symbol,
                                  @Param("timeframe") String timeframe,
                                  @Param("now") LocalDateTime now,
                                  Pageable pageable);

    @Query("SELECT h FROM HintOverride h " +
           "WHERE h.revokedAt IS NULL AND h.expiresAt > :now " +
           "ORDER BY h.expiresAt ASC")
    List<HintOverride> findAllActive(@Param("now") LocalDateTime now);
}
