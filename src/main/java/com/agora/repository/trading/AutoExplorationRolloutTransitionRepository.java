package com.agora.repository.trading;

import com.agora.model.AutoExplorationRolloutTransition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoExplorationRolloutTransitionRepository
        extends JpaRepository<AutoExplorationRolloutTransition, Long> {

    Optional<AutoExplorationRolloutTransition> findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(
            String symbol, Long strategyId, String side);

    @Query("SELECT t FROM AutoExplorationRolloutTransition t " +
           "WHERE t.symbol = :symbol AND t.strategyId = :strategyId AND t.side = :side " +
           "ORDER BY t.generatedAt DESC")
    List<AutoExplorationRolloutTransition> findRecent(@Param("symbol") String symbol,
                                                      @Param("strategyId") Long strategyId,
                                                      @Param("side") String side,
                                                      Pageable pageable);
}
