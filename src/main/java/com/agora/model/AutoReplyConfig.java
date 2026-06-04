package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "auto_reply_configs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_name", columnNames = "name"),
    @UniqueConstraint(name = "uk_keyword", columnNames = "keyword")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "自動回復配置")
public class AutoReplyConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "配置ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    
    @Column(name = "name", nullable = false, unique = true, length = 100)
    @Schema(description = "配置名稱", example = "價格查詢回復", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String name;
    
    @Column(name = "description", length = 500)
    @Schema(description = "配置描述", example = "當用戶詢問價格時的自動回復", nullable = true, maxLength = 500)
    private String description;
    
    @Column(name = "keyword", nullable = false, unique = true, length = 100)
    @Schema(description = "關鍵詞（唯一）", example = "價格", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String keyword;
    
    @Column(name = "reply_content", nullable = false, columnDefinition = "TEXT")
    @Schema(description = "回復內容", example = "抱歉，我無法提供具體的價格信息。請聯繫客服獲取詳細報價。", requiredMode = Schema.RequiredMode.REQUIRED)
    private String replyContent;
    
    @Column(name = "priority", nullable = false)
    @Schema(description = "優先級", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @Builder.Default
    private Integer priority = 1;
    
    @Column(name = "enabled", nullable = false)
    @Schema(description = "是否啟用", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @Builder.Default
    private Boolean enabled = true;
    
    @Column(name = "hit_count", nullable = false)
    @Schema(description = "命中次數", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    @Builder.Default
    private Long hitCount = 0L;
    
    @Column(name = "last_hit_time")
    @Schema(description = "最後命中時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime lastHitTime;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;
    
    /**
     * 檢查是否匹配關鍵詞（包含匹配）
     */
    public boolean matches(String message) {
        if (message == null || message.trim().isEmpty() || !enabled || keyword == null) {
            return false;
        }
        
        String lowerMessage = message.toLowerCase().trim();
        String lowerKeyword = keyword.toLowerCase().trim();
        
        return lowerMessage.contains(lowerKeyword);
    }
    
    /**
     * 增加命中次數
     */
    public void incrementHitCount() {
        this.hitCount++;
        this.lastHitTime = LocalDateTime.now();
    }
} 