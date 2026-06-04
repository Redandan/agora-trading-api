package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "system_config", indexes = {
    @Index(name = "idx_config_key", columnList = "config_key")
}, uniqueConstraints = {
    @UniqueConstraint(name = "config_key", columnNames = "config_key")
})
@Schema(description = "系統配置")
public class SystemConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "配置ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    
    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    @Schema(description = "配置鍵", example = "site.name", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String configKey;
    
    @Column(name = "config_value", nullable = false, length = 500)
    @Schema(description = "配置值", example = "Agora Market", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500)
    private String configValue;
    
    @Column(name = "description", length = 500)
    @Schema(description = "配置描述", example = "網站名稱", nullable = true, maxLength = 500)
    private String description;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;
}

