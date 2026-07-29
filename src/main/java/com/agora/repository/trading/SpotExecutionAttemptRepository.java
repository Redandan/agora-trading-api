package com.agora.repository.trading;

import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.Side;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpotExecutionAttemptRepository
        extends JpaRepository<SpotExecutionAttempt, Long> {

    Optional<SpotExecutionAttempt>
            findByLiveSignalIdAndSideAndAttemptSequence(
                    Long liveSignalId,
                    Side side,
                    Integer attemptSequence);

    Optional<SpotExecutionAttempt> findByClientOrderId(String clientOrderId);

    Optional<SpotExecutionAttempt> findByProviderAndProviderOrderId(
            String provider,
            String providerOrderId);

    Optional<SpotExecutionAttempt>
            findTopByLiveSignalIdAndSideOrderByAttemptSequenceDesc(
                    Long liveSignalId,
                    Side side);

    List<SpotExecutionAttempt>
            findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(
                    Long liveSignalId,
                    Side side);

    List<SpotExecutionAttempt>
            findByStrategyContractAndSideOrderByCreatedAtAsc(
                    String strategyContract,
                    Side side);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT attempt FROM SpotExecutionAttempt attempt "
            + "WHERE attempt.id = :id")
    Optional<SpotExecutionAttempt> findByIdForUpdate(@Param("id") Long id);

    /**
     * Elects exactly one provider submitter across JVMs.
     *
     * @return one only for the winning RESERVED row; zero for every replay
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = """
                    UPDATE bt_spot_execution_attempt
                       SET state = 'SUBMITTING',
                           submitted_at = :submittedAt,
                           updated_at = :submittedAt,
                           version = version + 1
                     WHERE id = :id
                       AND state = 'RESERVED'
                    """,
            nativeQuery = true)
    int claimForSubmission(
            @Param("id") Long id,
            @Param("submittedAt") LocalDateTime submittedAt);
}
