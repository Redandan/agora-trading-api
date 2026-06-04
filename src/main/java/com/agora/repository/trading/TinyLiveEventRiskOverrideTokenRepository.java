package com.agora.repository.trading;

import com.agora.model.TinyLiveEventRiskOverrideToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TinyLiveEventRiskOverrideTokenRepository extends JpaRepository<TinyLiveEventRiskOverrideToken, Long> {

    Optional<TinyLiveEventRiskOverrideToken> findByTokenHash(String tokenHash);

    long countByStatusAndCreatedAtAfter(String status, LocalDateTime since);

    @Query("""
            SELECT t
            FROM TinyLiveEventRiskOverrideToken t
            WHERE t.createdAt >= :since
              AND (:symbol IS NULL OR t.symbol = :symbol)
            ORDER BY t.createdAt DESC
            """)
    List<TinyLiveEventRiskOverrideToken> findRecent(@Param("since") LocalDateTime since,
                                                    @Param("symbol") String symbol,
                                                    Pageable pageable);
}
