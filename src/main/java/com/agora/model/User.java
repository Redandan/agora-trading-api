package com.agora.model;

import com.agora.enums.system.UserStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用戶實體類
 * 存儲用戶的基本信息和認證信息
 */
@Data
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "UK_r43af9ap4edm43mmtq01oddj6", columnNames = "username")
})
@Schema(description = "用戶")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "用戶ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    @Schema(description = "用戶名", example = "user123", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
    private String username;

    @Column(nullable = false, length = 255)
    @Schema(description = "密碼", example = "$2a$10$...", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
    private String password;

    @Column(nullable = false, length = 255)
    @Schema(
        description = "角色 — ADMIN=管理員, USER=一般用戶/買家, SELLER=賣家, DELIVERYER=外送員。"
                    + "前端顯示須做 enum→label 轉換(此 field 回傳為 enum name 大寫英文)。",
        example = "USER",
        allowableValues = {"ADMIN", "USER", "SELLER", "DELIVERYER"},
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 255)
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 255)
    @Schema(
        description = "用戶狀態",
        enumAsRef = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        maxLength = 255
    )
    private UserStatusEnum status = UserStatusEnum.ACTIVE;

    @Column(name = "name", length = 50)
    @Schema(description = "姓名", example = "王小明", nullable = true, maxLength = 50)
    private String name;

    @Column(name = "phone", length = 20)
    @Schema(description = "電話號碼", example = "0912345678", nullable = true, maxLength = 20)
    private String phone;

    @Column(name = "email", length = 100)
    @Schema(description = "電子郵件", example = "user@example.com", nullable = true, maxLength = 100)
    private String email;

    @Column(name = "avatar", length = 255)
    @Schema(description = "頭像", example = "https://example.com/avatar.jpg", nullable = true, maxLength = 255)
    private String avatar;

    @Column(name = "remark", length = 500)
    @Schema(description = "備註", example = "VIP用戶", nullable = true, maxLength = 500)
    private String remark;

    /**
     * 當前請求的設備指紋（從JWT token中提取，不持久化到數據庫）
     */
    @Transient
    @Schema(description = "當前請求的設備指紋", example = "TW-TAIPEI-WIN-CHROME-ABCDEF", nullable = true)
    private String currentDeviceFingerprint;

    /**
     * 當前請求的IP地址（從JWT token中提取，不持久化到數據庫）
     */
    @Transient
    @Schema(description = "當前請求的IP地址", example = "192.168.1.1", nullable = true)
    private String currentIpAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最後更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;

    public boolean isAdmin() {
        return role.equals("ADMIN");
    }
}
