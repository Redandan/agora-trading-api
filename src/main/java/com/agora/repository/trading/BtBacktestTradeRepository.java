package com.agora.repository.trading;

import com.agora.model.BtBacktestTrade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BtBacktestTradeRepository extends JpaRepository<BtBacktestTrade, Long> {

    List<BtBacktestTrade> findByBacktest_IdOrderByTradeIdxAsc(Long backtestId);

    long countByBacktest_Id(Long backtestId);

    void deleteByBacktest_Id(Long backtestId);

    @Query("SELECT t FROM BtBacktestTrade t JOIN FETCH t.backtest b " +
           "WHERE b.symbol = :symbol " +
           "  AND b.intervalCode = :intervalCode " +
           "  AND t.entryTime >= :since " +
           "  AND t.exitTime IS NOT NULL " +
           "  AND t.entryPrice IS NOT NULL " +
           "  AND t.exitPrice IS NOT NULL " +
           "  AND t.quantity IS NOT NULL " +
           "  AND t.netPnl IS NOT NULL " +
           "  AND t.atrPct IS NOT NULL " +
           "ORDER BY t.entryTime DESC")
    List<BtBacktestTrade> findReplayableRecentTrades(@Param("symbol") String symbol,
                                                      @Param("intervalCode") String intervalCode,
                                                      @Param("since") LocalDateTime since,
                                                      Pageable pageable);
}
