package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_chat_messages_session_id", columnList = "session_id"),
    @Index(name = "idx_chat_messages_created_at", columnList = "created_at"),
    @Index(name = "idx_chat_messages_session_created", columnList = "session_id,created_at")
})
@Schema(description = "聊天消息")
public class ChatMessage {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "消息ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    @Schema(description = "發送者ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long senderId;

    @Column(name = "receiver_id", nullable = false)
    @Schema(description = "接收者ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long receiverId;

    @Column(name = "session_id", nullable = false, length = 100)
    @Schema(description = "聊天會話ID", example = "1_2", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String sessionId;

    @Column(nullable = false, length = 1000)
    @Schema(description = "消息內容", example = "你好，請問這個商品還有貨嗎？", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000)
    private String content;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    @Schema(description = "刪除時間", example = "2024-01-01T11:00:00", nullable = true)
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // 业务逻辑方法
    /**
     * 检查是否为图片消息
     */
    public boolean isImageMessage() {
        return content != null && content.contains("\"type\"") && 
               (content.contains("\"IMAGE\"") || content.contains("\"MIXED\""));
    }

    /**
     * 检查是否为纯文本消息
     */
    public boolean isTextMessage() {
        return content != null && !content.contains("\"type\"");
    }

    /**
     * 检查是否为混合消息（文本+图片）
     */
    public boolean isMixedMessage() {
        return content != null && content.contains("\"type\"") && content.contains("\"MIXED\"");
    }

    /**
     * 检查是否有图片
     */
    public boolean hasImages() {
        return content != null && content.contains("\"images\"") && content.contains("\"url\"");
    }
}