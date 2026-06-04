package com.agora.repository.trading;

import com.agora.model.MarketFlipEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketFlipEventRepository extends JpaRepository<MarketFlipEvent, Long> {

    /** 取 PENDING 事件(Claude scheduled task 每 15min 查此) */
    List<MarketFlipEvent> findByStatusOrderByDetectedAtAsc(String status, Pageable pageable);

    /** 找老化未處理的 PENDING(給 auto-escalate scheduler) */
    @Query("SELECT e FROM MarketFlipEvent e " +
           "WHERE e.status = 'PENDING' AND e.detectedAt < :cutoff")
    List<MarketFlipEvent> findStaleEvents(@Param("cutoff") LocalDateTime cutoff);

    /** symbol+indicator 最新一筆(用於同源去重) */
    @Query("SELECT e FROM MarketFlipEvent e " +
           "WHERE e.symbol = :symbol AND e.indicator = :indicator " +
           "ORDER BY e.detectedAt DESC")
    List<MarketFlipEvent> findLatestBySymbolAndIndicator(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            Pageable pageable);

    /** Most recent flip event for this symbol (any indicator/status) — for recency-based scoring. */
    @Query("SELECT e FROM MarketFlipEvent e WHERE e.symbol = :symbol ORDER BY e.detectedAt DESC")
    List<MarketFlipEvent> findLatestBySymbol(@Param("symbol") String symbol, Pageable pageable);

    /**
     * 最近 N 小時被 DataQualityMonitor flag 為 anomalous 的 event。
     *
     * <p>使用 MySQL 8 JSON_EXTRACT 過濾 {@code context_json.anomalous = true}。
     * 執行計畫走 idx_mfe_status_detected(detected_at) 再在 result 上套 JSON filter。
     */
    @Query(value = "SELECT * FROM market_flip_event " +
                   "WHERE detected_at > :since " +
                   "  AND JSON_EXTRACT(context_json, '$.anomalous') = true " +
                   "ORDER BY detected_at DESC " +
                   "LIMIT :maxRows",
           nativeQuery = true)
    List<MarketFlipEvent> findAnomalousSince(@Param("since") LocalDateTime since,
                                             @Param("maxRows") int maxRows);
}
