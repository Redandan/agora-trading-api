package com.agora.repository.trading.evidence;

import com.agora.model.evidence.AppendOnlyEvidence;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Deliberately exposes no save, update, or delete operation. */
@NoRepositoryBean
public interface ReadOnlyEvidenceRepository<T extends AppendOnlyEvidence> extends Repository<T, Long> {

    long countByEventAtGreaterThanEqualAndEventAtLessThan(LocalDateTime start, LocalDateTime end);

    Optional<T> findFirstByOrderByEventAtAsc();

    Optional<T> findFirstByOrderByEventAtDesc();
}
