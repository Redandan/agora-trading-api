package com.agora.model;

import com.agora.enums.marketplace.IssueStatusEnum;
import com.agora.enums.marketplace.IssueTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "customer_issues")
@Schema(description = "客戶問題")
@EntityListeners(AuditingEntityListener.class)
public class CustomerIssue {

    @Id
    @Schema(description = "工單號")
    private String id;

    @Column(nullable = false)
    @Schema(description = "用戶ID")
    private Long userId;

    @Column(nullable = false)
    @Schema(description = "用戶名")
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Schema(description = "問題類型", enumAsRef = true)
    private IssueTypeEnum issueType;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Schema(description = "問題內容")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Schema(description = "問題狀態", enumAsRef = true)
    private IssueStatusEnum status = IssueStatusEnum.PENDING;

    @Column(columnDefinition = "TEXT")
    @Schema(description = "回覆內容")
    private String reply;

    @Column
    @Schema(description = "處理時間")
    private LocalDateTime processedAt;

    @Column
    @Schema(description = "操作人ID")
    private Long operatorId;

    @Column
    @Schema(description = "操作人姓名")
    private String operatorName;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    @Schema(description = "創建時間")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    @Schema(description = "最後更新時間")
    private LocalDateTime updatedAt;
} 