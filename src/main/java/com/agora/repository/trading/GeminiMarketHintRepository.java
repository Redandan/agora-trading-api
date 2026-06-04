package com.agora.repository.trading;

import com.agora.model.GeminiMarketHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GeminiMarketHintRepository extends JpaRepository<GeminiMarketHint, Long> {

    /**
     * 取指定 (symbol, timeframe) 當前仍有效(未過期)的最新 hint。
     * Strategy.evaluate() 在每根 bar 觸發時查此(用 PageRequest.of(0, 1) 限 1 筆,
     * caller 自行 .stream().findFirst())。
     * 註:Spring Data JPA 不允許 Optional + Pageable 組合,故 return List。
     */
    @Query("SELECT h FROM GeminiMarketHint h " +
           "WHERE h.symbol = :symbol AND h.timeframe = :timeframe " +
           "  AND h.expiresAt > :now " +
           "ORDER BY h.createdAt DESC")
    List<GeminiMarketHint> findActiveHints(@Param("symbol") String symbol,
                                           @Param("timeframe") String timeframe,
                                           @Param("now") LocalDateTime now,
                                           Pageable pageable);

    /**
     * 取最近 N 筆 hint(供 MCP 工具 getRecentHints / analyzeGeminiBias 用)。
     */
    List<GeminiMarketHint> findTop50ByOrderByCreatedAtDesc();

    List<GeminiMarketHint> findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(
            String symbol, String timeframe, LocalDateTime since);
}
