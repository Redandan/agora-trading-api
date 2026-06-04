package com.agora.repository.trading;

import com.agora.model.BtStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
