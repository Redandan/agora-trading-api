package com.agora.repository.trading;

import com.agora.model.MarketFlipAiAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketFlipAiAnalysisRepository extends JpaRepository<MarketFlipAiAnalysis, Long> {

    List<MarketFlipAiAnalysis> findByEventId(Long eventId);
}
