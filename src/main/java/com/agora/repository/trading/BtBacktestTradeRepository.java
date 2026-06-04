package com.agora.repository.trading;

import com.agora.model.BtBacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BtBacktestTradeRepository extends JpaRepository<BtBacktestTrade, Long> {

    List<BtBacktestTrade> findByBacktest_IdOrderByTradeIdxAsc(Long backtestId);

    long countByBacktest_Id(Long backtestId);

    void deleteByBacktest_Id(Long backtestId);
}
