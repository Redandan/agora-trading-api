package com.agora.util;

import com.agora.enums.marketplace.PickupServiceTypeEnum;
import com.agora.enums.marketplace.ShippingCompanyEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 枚舉翻譯工具類
 * 提供統一的接口讓前端獲取枚舉的中文描述
 */
public class EnumTranslationUtil {

    /**
     * 物流公司枚舉翻譯數據
     */
    @Data
    @Schema(description = "物流公司枚舉翻譯")
    public static class ShippingCompanyTranslation {
        @Schema(description = "枚舉值", example = "BLACK_CAT")
        private String enumValue;
        
        @Schema(description = "中文描述", example = "黑貓宅急便")
        private String description;
        
        @Schema(description = "是否為超商", example = "false")
        private Boolean isConvenienceStore;
        
        @Schema(description = "是否為宅配", example = "true")
        private Boolean isHomeDelivery;
        
        @Schema(description = "是否為郵政", example = "false")
        private Boolean isPostal;
    }

    /**
     * 物流服務類型枚舉翻譯數據
     */
    @Data
    @Schema(description = "物流服務類型枚舉翻譯")
    public static class LogisticsServiceTypeTranslation {
        @Schema(description = "枚舉值", example = "HOME_DELIVERY")
        private String enumValue;
        
        @Schema(description = "中文描述", example = "宅配服務")
        private String description;
        
        @Schema(description = "是否為宅配", example = "true")
        private Boolean isHomeDelivery;
        
        @Schema(description = "是否為店取", example = "false")
        private Boolean isStorePickup;
        
        @Schema(description = "是否為郵局配送", example = "false")
        private Boolean isPostal;
        
        // Getters and Setters
        public String getEnumValue() { return enumValue; }
        public void setEnumValue(String enumValue) { this.enumValue = enumValue; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public Boolean getIsHomeDelivery() { return isHomeDelivery; }
        public void setIsHomeDelivery(Boolean isHomeDelivery) { this.isHomeDelivery = isHomeDelivery; }
        
        public Boolean getIsStorePickup() { return isStorePickup; }
        public void setIsStorePickup(Boolean isStorePickup) { this.isStorePickup = isStorePickup; }
        
        public Boolean getIsPostal() { return isPostal; }
        public void setIsPostal(Boolean isPostal) { this.isPostal = isPostal; }
    }

    /**
     * 獲取所有物流公司的翻譯數據
     * 前端可以用於下拉選單、顯示等場景
     */
    public static List<ShippingCompanyTranslation> getAllShippingCompanyTranslations() {
        return Arrays.stream(ShippingCompanyEnum.values())
                .map(company -> {
                    ShippingCompanyTranslation translation = new ShippingCompanyTranslation();
                    translation.setEnumValue(company.name());
                    translation.setDescription(company.getDescription());
                    translation.setIsConvenienceStore(company.isConvenienceStore());
                    translation.setIsHomeDelivery(company.isHomeDelivery());
                    translation.setIsPostal(company.isPostal());
                    return translation;
                })
                .collect(Collectors.toList());
    }

    /**
     * 獲取所有物流服務類型的翻譯數據
     * 前端可以用於下拉選單、顯示等場景
     */
    public static List<LogisticsServiceTypeTranslation> getAllLogisticsServiceTypeTranslations() {
        return Arrays.stream(PickupServiceTypeEnum.values())
                .map(serviceType -> {
                    LogisticsServiceTypeTranslation translation = new LogisticsServiceTypeTranslation();
                    translation.setEnumValue(serviceType.name());
                    translation.setDescription(serviceType.getDescription());
                    translation.setIsHomeDelivery(serviceType.isHomeDelivery());
                    translation.setIsStorePickup(serviceType.isStorePickup());
                    translation.setIsPostal(false); // 郵局配送已移除，所有服務類型都不是郵局
                    return translation;
                })
                .collect(Collectors.toList());
    }

    /**
     * 獲取物流公司枚舉值到中文描述的映射
     * 前端可以用於快速查詢
     */
    public static Map<String, String> getShippingCompanyEnumToDescriptionMap() {
        return Arrays.stream(ShippingCompanyEnum.values())
                .collect(Collectors.toMap(
                        ShippingCompanyEnum::name,
                        ShippingCompanyEnum::getDescription
                ));
    }

    /**
     * 獲取物流服務類型枚舉值到中文描述的映射
     * 前端可以用於快速查詢
     */
    public static Map<String, String> getPickupServiceTypeEnumToDescriptionMap() {
        return Arrays.stream(PickupServiceTypeEnum.values())
                .collect(Collectors.toMap(
                        PickupServiceTypeEnum::name,
                        PickupServiceTypeEnum::getDescription
                ));
    }

    /**
     * 獲取中文描述到物流公司枚舉值的映射
     * 前端可以用於反查
     */
    public static Map<String, String> getShippingCompanyDescriptionToEnumMap() {
        return Arrays.stream(ShippingCompanyEnum.values())
                .collect(Collectors.toMap(
                        ShippingCompanyEnum::getDescription,
                        ShippingCompanyEnum::name
                ));
    }

    /**
     * 獲取中文描述到物流服務類型枚舉值的映射
     * 前端可以用於反查
     */
    public static Map<String, String> getLogisticsServiceTypeDescriptionToEnumMap() {
        return Arrays.stream(PickupServiceTypeEnum.values())
                .collect(Collectors.toMap(
                        PickupServiceTypeEnum::getDescription,
                        PickupServiceTypeEnum::name
                ));
    }

    /**
     * 根據枚舉值獲取物流公司中文描述
     */
    public static String getShippingCompanyDescription(String enumValue) {
        try {
            ShippingCompanyEnum company = ShippingCompanyEnum.valueOf(enumValue);
            return company.getDescription();
        } catch (IllegalArgumentException e) {
            return enumValue; // 如果枚舉值無效，返回原值
        }
    }

    /**
     * 根據枚舉值獲取物流服務類型中文描述
     */
    public static String getLogisticsServiceTypeDescription(String enumValue) {
        try {
            PickupServiceTypeEnum serviceType = PickupServiceTypeEnum.valueOf(enumValue);
            return serviceType.getDescription();
        } catch (IllegalArgumentException e) {
            return enumValue; // 如果枚舉值無效，返回原值
        }
    }

    /**
     * 獲取所有枚舉的完整翻譯數據
     * 前端可以用於初始化翻譯字典
     */
    public static Map<String, Object> getAllEnumTranslations() {
        Map<String, Object> translations = new HashMap<>();
        
        // 物流公司翻譯
        translations.put("shippingCompanies", getAllShippingCompanyTranslations());
        translations.put("shippingCompanyEnumToDescription", getShippingCompanyEnumToDescriptionMap());
        translations.put("shippingCompanyDescriptionToEnum", getShippingCompanyDescriptionToEnumMap());
        
        // 物流服務類型翻譯
        translations.put("logisticsServiceTypes", getAllLogisticsServiceTypeTranslations());
        translations.put("logisticsServiceTypeEnumToDescription", getPickupServiceTypeEnumToDescriptionMap());
        translations.put("logisticsServiceTypeDescriptionToEnum", getLogisticsServiceTypeDescriptionToEnumMap());
        
        return translations;
    }
}
