package com.agora.repository.system;

import com.agora.model.AiTaskRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiTaskRecordRepository extends JpaRepository<AiTaskRecord, Long> {

    Optional<AiTaskRecord> findFirstByStatusAndAssigneeTypeOrderByCreatedAtAsc(
            AiTaskRecord.Status status,
            AiTaskRecord.AssigneeType assigneeType);

    @Query("""
            select task
            from AiTaskRecord task
            where (:status is null or task.status = :status)
              and (:assigneeType is null or task.assigneeType = :assigneeType)
              and (:taskType is null or task.taskType = :taskType)
              and (:createdAfter is null or task.createdAt >= :createdAfter)
            order by task.createdAt desc
            """)
    List<AiTaskRecord> search(
            @Param("status") AiTaskRecord.Status status,
            @Param("assigneeType") AiTaskRecord.AssigneeType assigneeType,
            @Param("taskType") AiTaskRecord.TaskType taskType,
            @Param("createdAfter") LocalDateTime createdAfter,
            Pageable pageable);
}
