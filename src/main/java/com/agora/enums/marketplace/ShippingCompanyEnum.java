package com.agora.enums.marketplace;

import com.agora.enums.system.CompanyCategory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 物流公司枚舉
 * 包含分類信息和服務類型映射
 */
@Schema(description = "物流公司")
public enum ShippingCompanyEnum {

    // ========== 宅配公司 ==========
    @Schema(description = "黑貓宅急便")
    BLACK_CAT("黑貓宅急便", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    @Schema(description = "新竹物流")
    HCT("新竹物流", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    @Schema(description = "大榮貨運")
    KERRY("大榮貨運", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    @Schema(description = "順豐速運")
    SF_EXPRESS("順豐速運", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    @Schema(description = "宅配通")
    HOME_DELIVERY_EXPRESS("宅配通", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    @Schema(description = "台灣宅配")
    TAIWAN_HOME_DELIVERY("台灣宅配", CompanyCategory.HOME_DELIVERY, PickupServiceTypeEnum.HOME_DELIVERY),

    // ========== 平台配送 ==========
    @Schema(description = "平台配送")
    PLATFORM_DELIVERY("平台配送", CompanyCategory.PLATFORM, PickupServiceTypeEnum.PLATFORM_DELIVERY),

    // ========== 超商 ==========
    @Schema(description = "7-11")
    SEVEN_ELEVEN("7-11", CompanyCategory.CONVENIENCE_STORE, PickupServiceTypeEnum.SEVEN_ELEVEN),

    @Schema(description = "全家")
    FAMILY_MART("全家", CompanyCategory.CONVENIENCE_STORE, PickupServiceTypeEnum.FAMILY_MART),

    @Schema(description = "萊爾富")
    HILIFE("萊爾富", CompanyCategory.CONVENIENCE_STORE, PickupServiceTypeEnum.HILIFE),

    @Schema(description = "OK超商")
    OK_MART("OK超商", CompanyCategory.CONVENIENCE_STORE, PickupServiceTypeEnum.OK_MART),

    // ========== 郵政 ==========
    @Schema(description = "中華郵政")
    CHUNGHWA_POST("中華郵政", CompanyCategory.POSTAL, PickupServiceTypeEnum.HOME_DELIVERY),

    // ========== 其他 ==========
    @Schema(description = "其他")
    OTHER("其他", CompanyCategory.OTHER, null);

    @JsonValue  // 讓 API 傳輸用 enum 名稱 (BLACK_CAT)，而不是中文
    private final String code;
    private final String description;
    private final CompanyCategory category;
    private final PickupServiceTypeEnum supportedServiceType;

    ShippingCompanyEnum(String description, CompanyCategory category, PickupServiceTypeEnum supportedServiceType) {
        this.code = name();
        this.description = description;
        this.category = category;
        this.supportedServiceType = supportedServiceType;
    }


    /**
     * 根據中文名稱獲取枚舉值
     * 前端可以根據中文名稱反查枚舉值
     */
    public static ShippingCompanyEnum fromDescription(String description) {
        for (ShippingCompanyEnum company : values()) {
            if (company.getDescription().equals(description)) {
                return company;
            }
        }
        throw new IllegalArgumentException("未知的物流公司描述: " + description);
    }

    /**
     * Jackson反序列化方法
     * 支持從枚舉名稱字符串反序列化為枚舉實例
     */
    @JsonCreator
    public static ShippingCompanyEnum fromValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的物流公司枚舉值: " + value);
        }
    }

    /**
     * 獲取所有中文描述列表
     * 前端可以用於下拉選單等場景
     */
    public static String[] getAllDescriptions() {
        ShippingCompanyEnum[] values = values();
        String[] descriptions = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            descriptions[i] = values[i].getDescription();
        }
        return descriptions;
    }

    /**
     * 獲取中文描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 獲取分類
     */
    public CompanyCategory getCategory() {
        return category;
    }

    /**
     * 獲取支援的服務類型
     */
    public PickupServiceTypeEnum getSupportedServiceType() {
        return supportedServiceType;
    }

    /**
     * 檢查是否為超商物流公司
     */
    public boolean isConvenienceStore() {
        return category == CompanyCategory.CONVENIENCE_STORE;
    }

    /**
     * 檢查是否為宅配物流公司
     * 包括宅配公司分類和平台配送（因為平台配送支持宅配服務類型）
     * 注意：郵政雖然支持宅配服務類型，但不屬於宅配公司分類
     */
    public boolean isHomeDelivery() {
        return category == CompanyCategory.HOME_DELIVERY || 
               category == CompanyCategory.PLATFORM;
    }

    /**
     * 檢查是否為郵政物流公司
     */
    public boolean isPostal() {
        return category == CompanyCategory.POSTAL;
    }

    /**
     * 檢查是否為平台配送
     */
    public boolean isPlatform() {
        return category == CompanyCategory.PLATFORM;
    }

    /**
     * 檢查是否為其他
     */
    public boolean isOther() {
        return category == CompanyCategory.OTHER;
    }

    /**
     * 根據服務類型獲取所有可用的物流公司
     * 包含"其他"選項，因為"其他"支持所有服務類型
     */
    public static List<ShippingCompanyEnum> getByServiceType(PickupServiceTypeEnum serviceType) {
        return Arrays.stream(values())
                .filter(company -> company.getSupportedServiceType() == serviceType || company.isOther())
                .collect(Collectors.toList());
    }

    /**
     * 根據分類獲取所有物流公司
     */
    public static List<ShippingCompanyEnum> getByCategory(CompanyCategory category) {
        return Arrays.stream(values())
                .filter(company -> company.getCategory() == category)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return getDescription();
    }
} 