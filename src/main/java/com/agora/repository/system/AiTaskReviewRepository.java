package com.agora.repository.system;

import com.agora.model.AiTaskReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiTaskReviewRepository extends JpaRepository<AiTaskReview, Long> {

    List<AiTaskReview> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
