package com.agora.repository.system;

import com.agora.model.AiPendingQuestion;
import com.agora.model.AiPendingQuestion.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiPendingQuestionRepository extends JpaRepository<AiPendingQuestion, Long> {

    List<AiPendingQuestion> findByStatusOrderByCreatedAtDesc(Status status);

    List<AiPendingQuestion> findAllByOrderByCreatedAtDesc();

    long countByStatus(Status status);

    boolean existsByQuestionAndStatus(String question, Status status);
}
