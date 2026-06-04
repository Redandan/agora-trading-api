package com.agora.model;

import com.agora.enums.marketplace.PickupServiceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用戶地址實體類
 * 支持用戶管理多個收貨地址
 */
@Data
@Entity
@Table(name = "user_addresses")
@Schema(description = "用戶地址")
public class UserAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "地址ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Schema(description = "用戶ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Column(name = "recipient_name", nullable = false, length = 50)
    @Schema(description = "收件人姓名", example = "王小明", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    @Schema(description = "收件人電話", example = "0912345678", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 20)
    private String recipientPhone;

    @Column(name = "postal_code", nullable = false, length = 10)
    @Schema(description = "郵遞區號", example = "106", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 10)
    private String postalCode;

    @Column(nullable = false, length = 100)
    @Schema(description = "縣市", example = "台北市", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String city;

    @Column(nullable = false, length = 100)
    @Schema(description = "鄉鎮市區", example = "大安區", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100)
    private String district;

    @Column(name = "detailed_address", nullable = false, length = 200)
    @Schema(description = "詳細地址", example = "敦化南路二段216號", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    private String detailedAddress;

    @Column(name = "remark", length = 500)
    @Schema(description = "備註", example = "公司地址", nullable = true, maxLength = 500)
    private String remark;

    // ========== 地理位置相關字段 ==========

    @Column(name = "longitude")
    @Schema(description = "經度", example = "121.5432000", nullable = true)
    private Double longitude;

    @Column(name = "latitude")
    @Schema(description = "緯度", example = "25.0330000", nullable = true)
    private Double latitude;

    // ========== 物流相關字段 ==========

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", length = 50)
    @Schema(description = "取件類型", enumAsRef = true, nullable = true, maxLength = 50)
    private PickupServiceTypeEnum serviceType;

    @Column(name = "store_name", length = 100)
    @Schema(description = "門市名稱（便利商店取件用）", example = "7-11信義店", nullable = true, maxLength = 100)
    private String storeName;

    @Column(name = "store_code", length = 20)
    @Schema(description = "門市代號（便利商店取件用）", example = "123456", nullable = true, maxLength = 20)
    private String storeCode;

    @Column(name = "store_address", length = 200)
    @Schema(description = "門市地址（便利商店取件用）", example = "台北市信義區信義路五段7號", nullable = true, maxLength = 200)
    private String storeAddress;

    @Column(name = "is_default", nullable = false)
    @Schema(description = "是否為預設地址", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Schema(description = "是否啟用", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "最後更新時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;

    /**
     * 獲取完整地址字符串
     */
    public String getFullAddress() {
        // 如果是便利商店取件，返回門市信息
        if (isConvenienceStorePickup()) {
            return String.format("%s - %s", storeName, storeAddress);
        }
        // 一般地址
        return String.format("%s%s%s", city, district, detailedAddress);
    }

    /**
     * 獲取格式化地址字符串
     */
    public String getFormattedAddress() {
        return getFullAddress();
    }

    /**
     * 檢查是否為便利商店取件
     */
    public boolean isConvenienceStorePickup() {
        return serviceType != null && serviceType.isStorePickup() &&
               storeName != null && !storeName.isEmpty() &&
               storeCode != null && !storeCode.isEmpty();
    }

    /**
     * 檢查是否為宅配地址
     */
    public boolean isHomeDelivery() {
        return serviceType == PickupServiceTypeEnum.HOME_DELIVERY;
    }

    /**
     * 獲取物流描述
     */
    public String getLogisticsDescription() {
        if (serviceType == null) {
            return "未指定物流服務類型";
        }
        
        String serviceName = serviceType.getDescription();
        
        if (isConvenienceStorePickup()) {
            return String.format("%s - %s取件", serviceName, storeName != null ? storeName : "");
        }
        
        return serviceName;
    }

    /**
     * 獲取收件人完整信息
     */
    public String getRecipientInfo() {
        StringBuilder info = new StringBuilder();
        info.append(recipientName);
        
        if (recipientPhone != null && !recipientPhone.isEmpty()) {
            info.append(" (").append(recipientPhone).append(")");
        }
        
        return info.toString();
    }
} 