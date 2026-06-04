package com.agora.repository.trading;

import com.agora.model.AutonomousExplorationLoopTransition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutonomousExplorationLoopTransitionRepository
        extends JpaRepository<AutonomousExplorationLoopTransition, Long> {

    Optional<AutonomousExplorationLoopTransition> findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(
            String symbol, Long strategyId, String side);

    @Query("SELECT t FROM AutonomousExplorationLoopTransition t " +
           "WHERE t.symbol = :symbol AND t.strategyId = :strategyId AND t.side = :side " +
           "ORDER BY t.generatedAt DESC")
    List<AutonomousExplorationLoopTransition> findRecent(@Param("symbol") String symbol,
                                                         @Param("strategyId") Long strategyId,
                                                         @Param("side") String side,
                                                         Pageable pageable);
}
