package com.agora.model;

import com.agora.enums.system.RegistrationMethodEnum;
import com.agora.enums.system.UserStatusEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用戶實體類
 * 存儲用戶的基本信息和認證信息
 */
@Data
@Slf4j
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

    @Column(name = "store_name", length = 200)
    @Schema(description = "店鋪名稱", example = "優質商品專賣店", nullable = true, maxLength = 200)
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_home_page", length = 50)
    @Schema(
        description = "用戶默認首頁設置（登入後跳轉的頁面）",
        example = "BUYER",
        nullable = true,
        enumAsRef = true,
        maxLength = 50
    )
    private com.agora.enums.system.DefaultHomePageEnum defaultHomePage;

    @Column(name = "ambassador_name", length = 200)
    @Schema(description = "推廣大使名稱", example = "推廣大使001", nullable = true, maxLength = 200)
    private String ambassadorName;

    @Column(name = "display_deliveryer_name", length = 200)
    @Schema(description = "顯示配送員名稱", example = "配送員001", nullable = true, maxLength = 200)
    private String displayDeliveryerName;

    @Column(name = "promo_code", length = 50)
    @Schema(description = "註冊推廣碼", example = "PROMO2024", nullable = true, maxLength = 50)
    private String promoCode;

    @Column(name = "referrer_group_id")
    @Schema(description = "Mini App / TG 群組來源歸因 ID，供訂單建立時 snapshot", nullable = true)
    private Long referrerGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_method", length = 30)
    @Schema(description = "註冊方式", example = "FORM", nullable = true, enumAsRef = true)
    private RegistrationMethodEnum registrationMethod;

    @Column(name = "registration_ip", length = 45)
    @Schema(description = "註冊時的 IP 地址", example = "203.0.113.42", nullable = true, maxLength = 45)
    private String registrationIp;

    @Column(name = "registration_ua", length = 512)
    @Schema(description = "註冊時的 User-Agent", nullable = true, maxLength = 512)
    private String registrationUa;

    @Column(name = "two_factor_enabled")
    @Schema(description = "是否啟用雙因素認證", example = "false", nullable = true)
    private Boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret", length = 255)
    @Schema(description = "雙因素認證密鑰", example = "JBSWY3DPEHPK3PXP", nullable = true, maxLength = 255)
    private String twoFactorSecret;

    @Column(name = "email_verified")
    @Schema(description = "郵件是否已驗證", example = "false", nullable = true)
    private Boolean emailVerified = false;

    @Column(name = "trusted_devices", columnDefinition = "JSON")
    @Schema(description = "受信任設備列表（JSON格式），最多保存10組設備", example = "[{\"deviceFingerprint\":\"abc123\",\"ipAddress\":\"192.168.1.1\"}]", nullable = true)
    private String trustedDevicesJson;

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

    @Column(name = "terms_accepted_version", length = 20)
    @Schema(description = "已接受 ToS 版本（null=尚未接受當前版本）", nullable = true, maxLength = 20)
    private String termsAcceptedVersion;

    @Column(name = "terms_accepted_at")
    @Schema(description = "接受 ToS 時間", nullable = true)
    private LocalDateTime termsAcceptedAt;

    @Column(name = "country_code", length = 2)
    @Schema(description = "ISO 3166-1 alpha-2 國家代碼（signup 時由 CF-IPCountry 偵測）", nullable = true, maxLength = 2)
    private String countryCode;

    @Column(name = "country_detected_at")
    @Schema(description = "IP 國家偵測時間", nullable = true)
    private LocalDateTime countryDetectedAt;

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

    /**
     * 設備信息數據類
     */
    @Data
    public static class DeviceInfo {
        private String deviceFingerprint;  // 設備指紋
        private String ipAddress;          // IP地址
        private Integer loginCount;        // 登入次數
        private String firstLoginAt;       // 首次登入時間（ISO格式）
        private String lastLoginAt;        // 最後登入時間（ISO格式）
        private Boolean isTrusted;         // 是否為受信任設備
    }

    /**
     * 獲取設備列表（從JSON反序列化）
     */
    @Transient
    public List<DeviceInfo> getTrustedDevices() {
        return parseJsonToDeviceList(trustedDevicesJson);
    }

    /**
     * 設置設備列表（序列化為JSON）
     */
    @Transient
    public void setTrustedDevices(List<DeviceInfo> devices) {
        this.trustedDevicesJson = deviceListToJson(devices);
    }

    // JSON 工具方法
    private List<DeviceInfo> parseJsonToDeviceList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, new TypeReference<List<DeviceInfo>>() {});
        } catch (Exception e) {
            log.warn("解析設備列表 JSON失敗: {}", json, e);
            return new ArrayList<>();
        }
    }

    private String deviceListToJson(List<DeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            return "[]";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(devices);
        } catch (Exception e) {
            log.error("序列化設備列表失敗", e);
            return "[]";
        }
    }
}
