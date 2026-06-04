package com.agora.model;

import com.agora.enums.system.OAuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户OAuth第三方登录绑定实体类
 * 支持一个用户绑定多个第三方账号
 */
@Data
@Entity
@Table(name = "user_oauth_bindings", 
       indexes = {
           @Index(name = "idx_user_oauth_bindings_user_id", columnList = "user_id"),
           @Index(name = "idx_user_oauth_bindings_provider", columnList = "oauth_provider")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_user_oauth_bindings_user_provider", columnNames = {"user_id", "oauth_provider", "oauth_provider_id"}),
           @UniqueConstraint(name = "uk_user_oauth_bindings_provider_id", columnNames = {"oauth_provider", "oauth_provider_id"})
       })
@Schema(description = "用户OAuth第三方登录绑定")
public class UserOAuthBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "绑定ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Schema(description = "用户ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    @Schema(description = "用户", nullable = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false, length = 20)
    @Schema(description = "OAuth提供商: GOOGLE, FACEBOOK", enumAsRef = true, requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 20)
    private OAuthProvider oauthProvider;

    @Column(name = "oauth_provider_id", nullable = false, length = 255)
    @Schema(description = "OAuth提供商的用户ID", example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
    private String oauthProviderId;

    @Column(name = "oauth_name", length = 200)
    @Schema(description = "OAuth用户名", example = "John Doe", nullable = true, maxLength = 200)
    private String oauthName;

    @Column(name = "oauth_avatar", length = 500)
    @Schema(description = "OAuth头像URL", example = "https://example.com/avatar.jpg", nullable = true, maxLength = 500)
    private String oauthAvatar;

    @Column(name = "telegram_user_id", length = 50)
    @Schema(description = "Telegram用户ID（用于Telegram登录和绑定）", example = "123456789", nullable = true, maxLength = 50)
    private String telegramUserId;

    @Column(name = "is_primary", nullable = false)
    @Schema(description = "是否为主要绑定账号", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isPrimary = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "创建时间", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新时间", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;
}

