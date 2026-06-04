package com.agora.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 待確認問題
 * <p>
 * 當用戶問了 AI 無法從知識庫找到答案的問題時，
 * 自動記錄到此表，等待管理員確認並補充至知識庫。
 */
@Entity
@Table(name = "ai_pending_questions", indexes = {
        @Index(name = "idx_apq_status", columnList = "status"),
        @Index(name = "idx_apq_group_id", columnList = "group_id"),
        @Index(name = "idx_apq_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPendingQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "asked_by", length = 100)
    private String askedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    /** 管理員補充的回答（選填，解答時填寫） */
    @Column(name = "admin_answer", columnDefinition = "TEXT")
    private String adminAnswer;

    /** 若已加入知識庫，記錄對應的 Chroma document ID */
    @Column(name = "knowledge_id", length = 100)
    private String knowledgeId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Status {
        PENDING,   // 待確認
        RESOLVED,  // 已解答（含加入知識庫）
        IGNORED    // 忽略（不需要回答）
    }
}
