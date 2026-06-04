package com.agora.repository.trading;

import com.agora.model.PositionAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PositionAnnotationRepository extends JpaRepository<PositionAnnotation, Long> {

    List<PositionAnnotation> findByLiveSignalIdOrderByCreatedAtDesc(Long liveSignalId);

    List<PositionAnnotation> findByTagAndCreatedAtAfterOrderByCreatedAtDesc(String tag, LocalDateTime since);

    List<PositionAnnotation> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);
}
