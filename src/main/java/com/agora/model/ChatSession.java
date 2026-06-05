package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.persistence.*;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_sessions", indexes = {
    @Index(name = "idx_chat_sessions_user_id", columnList = "user_id"),
    @Index(name = "idx_partner_id", columnList = "partner_id"),
    @Index(name = "idx_user_partner", columnList = "user_id,partner_id"),
    @Index(name = "idx_chat_sessions_latest_time", columnList = "latest_message_time"),
    @Index(name = "idx_chat_sessions_user_partner", columnList = "user_id,partner_id")
})
@Schema(description = "聊天會話")
public class ChatSession {
    
    @Id
    @Column(name = "id", nullable = false, length = 100)
    @Schema(description = "會話唯一標識ID（主鍵）", example = "1_2", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String id;

    @Column(name = "user_id", nullable = false)
    @Schema(description = "用戶ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Column(name = "partner_id", nullable = false)
    @Schema(description = "聊天對象ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long partnerId;

    @Column(name = "user_unread_count", nullable = false)
    @Schema(description = "用戶未讀消息數", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userUnreadCount = 0L;

    @Column(name = "partner_unread_count", nullable = false)
    @Schema(description = "聊天對象未讀消息數", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long partnerUnreadCount = 0L;

    @Column(name = "latest_message_id")
    @Schema(description = "最新消息ID", example = "100", nullable = true)
    private Long latestMessageId;

    @Column(name = "latest_message_time")
    @Schema(description = "最新消息時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime latestMessageTime;

    @Column(name = "is_pinned", nullable = false)
    @Schema(description = "是否置頂", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean pinned = false;

    @Column(name = "partner_name", length = 50)
    @Schema(description = "聊天對象名稱", example = "張三", nullable = true, maxLength = 50)
    private String partnerName;

    @Column(name = "partner_avatar", length = 255)
    @Schema(description = "聊天對象頭像", example = "https://example.com/avatar.jpg", nullable = true, maxLength = 255)
    private String partnerAvatar;

    @Column(name = "latest_message_content", length = 1000)
    @Schema(description = "最新消息內容", example = "你好，請問這個商品還有貨嗎？", nullable = true, maxLength = 1000)
    private String latestMessageContent;

    @Column(name = "user_read_message_id")
    @Schema(description = "用戶已讀消息ID", example = "95", nullable = true)
    private Long userReadMessageId;

    @Column(name = "partner_read_message_id")
    @Schema(description = "聊天對象已讀消息ID", example = "90", nullable = true)
    private Long partnerReadMessageId;

    @Column(name = "user_read_at")
    @Schema(description = "用戶已讀時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime userReadAt;

    @Column(name = "partner_read_at")
    @Schema(description = "聊天對象已讀時間", example = "2024-01-01T09:55:00", nullable = true)
    private LocalDateTime partnerReadAt;

    @Column(name = "created_at", nullable = false)
    @Schema(description = "創建時間", example = "2024-01-01T09:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;

    @Transient
    @Schema(description = "聊天消息列表", nullable = true)
    private Page<ChatMessage> messages;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 生成會話的唯一標識ID
     * 格式：userId1_userId2 (較小的ID在前)
     */
    public static String generateId(Long userId1, Long userId2) {
        if (userId1 == null || userId2 == null) {
            throw new IllegalArgumentException("用戶ID不能為空");
        }
        
        // 確保較小的ID在前，保證雙向唯一性
        Long minId = Math.min(userId1, userId2);
        Long maxId = Math.max(userId1, userId2);
        
        return minId + "_" + maxId;
    }

    /**
     * 設置會話ID（自動生成）
     */
    public void setIdFromUsers() {
        this.id = generateId(this.userId, this.partnerId);
    }

    /**
     * 獲取指定用戶的已讀消息ID
     */
    public Long getReadMessageId(Long userId) {
        return userId.equals(this.userId) ? userReadMessageId : partnerReadMessageId;
    }

    /**
     * 獲取指定用戶的已讀時間
     */
    public LocalDateTime getReadAt(Long userId) {
        return userId.equals(this.userId) ? userReadAt : partnerReadAt;
    }

    /**
     * 獲取指定用戶的未讀消息數
     */
    public Long getUnreadCount(Long userId) {
        return userId.equals(this.userId) ? userUnreadCount : partnerUnreadCount;
    }

    /**
     * 設置指定用戶的未讀消息數
     */
    public void setUnreadCount(Long userId, Long count) {
        if (userId.equals(this.userId)) {
            this.userUnreadCount = count;
        } else {
            this.partnerUnreadCount = count;
        }
    }

    /**
     * 增加指定用戶的未讀消息數
     */
    public void incrementUnreadCount(Long userId) {
        if (userId.equals(this.userId)) {
            this.userUnreadCount++;
        } else {
            this.partnerUnreadCount++;
        }
    }

    /**
     * 重置指定用戶的未讀消息數為0
     */
    public void resetUnreadCount(Long userId) {
        if (userId.equals(this.userId)) {
            this.userUnreadCount = 0L;
        } else {
            this.partnerUnreadCount = 0L;
        }
    }


    /**
     * 設置指定用戶的已讀消息ID和時間
     */
    public void setReadMessageId(Long userId, Long messageId) {
        LocalDateTime now = LocalDateTime.now();
        if (userId.equals(this.userId)) {
            this.userReadMessageId = messageId;
            this.userReadAt = now;
        } else {
            this.partnerReadMessageId = messageId;
            this.partnerReadAt = now;
        }
    }

    /**
     * 檢查指定用戶是否已讀到指定消息
     */
    public boolean isReadUpTo(Long userId, Long messageId) {
        Long readMessageId = getReadMessageId(userId);
        return readMessageId != null && readMessageId >= messageId;
    }

    /**
     * 創建空的一般會話
     * @param sessionId 會話ID
     * @param userId 用戶ID
     * @param partnerId 聊天對象ID
     * @param partnerName 聊天對象名稱
     * @param partnerAvatar 聊天對象頭像
     * @return 創建的空會話對象
     */
    public static ChatSession createEmptyGeneralSession(String sessionId, Long userId, Long partnerId, 
                                                       String partnerName, String partnerAvatar) {
        ChatSession session = new ChatSession();
        
        // 基本信息
        session.setId(sessionId);
        session.setUserId(userId);
        session.setPartnerId(partnerId);
        
        // 聊天對象信息
        session.setPartnerName(partnerName);
        session.setPartnerAvatar(partnerAvatar);
        
        // 初始化未讀計數
        session.setUserUnreadCount(0L);
        session.setPartnerUnreadCount(0L);
        
        // 初始化狀態
        session.setPinned(false);
        
        return session;
    }

    /**
     * 創建空的機器人會話
     * @param sessionId 會話ID
     * @param userId 用戶ID
     * @return 創建的空機器人會話對象
     */
    public static ChatSession createEmptyBotSession(String sessionId, Long userId) {
        ChatSession session = new ChatSession();
        
        // 基本信息
        session.setId(sessionId);
        session.setUserId(userId);
        session.setPartnerId(0L);
        
        // 機器人信息
        session.setPartnerName("AI 助手");
        session.setPartnerAvatar("https://api.dicebear.com/7.x/bottts/svg?seed=ai-assistant");
        
        // 初始化未讀計數
        session.setUserUnreadCount(0L);
        session.setPartnerUnreadCount(0L);
        
        // 初始化狀態
        session.setPinned(false);
        
        // 設置默認歡迎消息
        session.setLatestMessageContent("歡迎使用AI助手！");
        
        return session;
    }

} 
