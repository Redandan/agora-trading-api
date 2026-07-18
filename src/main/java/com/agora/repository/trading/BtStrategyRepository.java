package com.agora.repository.trading;

import com.agora.model.BtStrategy;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BtStrategyRepository extends JpaRepository<BtStrategy, Long>, JpaSpecificationExecutor<BtStrategy> {

    /**
     * Finds the first AI-generated strategy whose configuration matches the given fingerprint.
     * Used by {@link com.agora.service.BtStrategyService#createAiGeneratedStrategy} to skip
     * saving a duplicate configuration.
     */
    Optional<BtStrategy> findFirstByConfigFingerprintAndAiGeneratedTrue(String configFingerprint);

    List<BtStrategy> findByEnabled(Boolean enabled);

    /**
     * Database-backed serialization point for one strategy/cohort execution lane.
     * The row lock is transaction scoped and therefore works across JVMs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BtStrategy s where s.id = :id")
    Optional<BtStrategy> findByIdForBootstrapReservation(@Param("id") Long id);
}
