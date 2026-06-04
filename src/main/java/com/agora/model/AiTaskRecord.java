package com.agora.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_task")
public class AiTaskRecord {

    public enum TaskType {
        SIRIN_RESEARCH_SENTINEL_RUN_ONCE,
        SIRIN_RESEARCH_SENTINEL_MONITOR_STATUS,
        SIRIN_BROWSER_TEST,
        KB_PUBLISH_REVIEW
    }

    public enum Status {
        QUEUED,
        ASSIGNED,
        RUNNING,
        NEEDS_REVIEW,
        ACCEPTED,
        REJECTED,
        FAILED,
        CANCELLED
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH
    }

    public enum AssigneeType {
        SIRIN,
        CODEX,
        AGORA_INTERNAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 80)
    private TaskType taskType;

    @Column(name = "objective", nullable = false, columnDefinition = "TEXT")
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private Priority priority = Priority.NORMAL;

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false, length = 40)
    private AssigneeType assigneeType;

    @Column(name = "assignee_id", length = 120)
    private String assigneeId;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
