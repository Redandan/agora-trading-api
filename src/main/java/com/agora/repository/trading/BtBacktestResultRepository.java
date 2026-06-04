package com.agora.repository.trading;

import com.agora.model.BtBacktestResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BtBacktestResultRepository extends JpaRepository<BtBacktestResult, Long> {

    Optional<BtBacktestResult> findTopByStrategy_IdOrderByCreatedAtDesc(Long strategyId);

    List<BtBacktestResult> findByStrategy_IdOrderByCreatedAtDesc(Long strategyId);

    List<BtBacktestResult> findByStrategy_IdOrderByCreatedAtDesc(Long strategyId, Pageable pageable);

    void deleteByStrategy_Id(Long strategyId);

    /** 查詢指定幣種/週期中歷史報酬最佳的回測結果（用於歷史錨點候選）。 */
    @Query("SELECT r FROM BtBacktestResult r WHERE r.symbol = :symbol AND r.intervalCode = :intervalCode " +
           "AND r.tradeCount >= 5 AND r.totalReturn > 0 ORDER BY r.totalReturn DESC")
    List<BtBacktestResult> findTopPerformingBySymbolAndInterval(
            @Param("symbol") String symbol,
            @Param("intervalCode") String intervalCode,
            Pageable pageable);
}
