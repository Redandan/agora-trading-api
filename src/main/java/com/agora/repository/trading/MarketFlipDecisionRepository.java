package com.agora.repository.trading;

import com.agora.model.MarketFlipDecision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketFlipDecisionRepository extends JpaRepository<MarketFlipDecision, Long> {

    Optional<MarketFlipDecision> findByEventId(Long eventId);

    List<MarketFlipDecision> findByDecidedAtAfterOrderByDecidedAtDesc(LocalDateTime since, Pageable pageable);
}
