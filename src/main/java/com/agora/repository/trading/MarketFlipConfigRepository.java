package com.agora.repository.trading;

import com.agora.model.MarketFlipConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketFlipConfigRepository extends JpaRepository<MarketFlipConfig, Long> {

    Optional<MarketFlipConfig> findBySymbolAndIndicator(String symbol, String indicator);

    List<MarketFlipConfig> findByEnabledTrue();
}
