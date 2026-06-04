package com.agora.enums.marketplace;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "取貨服務類型")
public enum PickupServiceTypeEnum {
    @Schema(description = "宅配服務")
    HOME_DELIVERY("宅配服務"),

    @Schema(description = "7-11")
    SEVEN_ELEVEN("7-11"),

    @Schema(description = "全家")
    FAMILY_MART("全家"),

    @Schema(description = "萊爾富")
    HILIFE("萊爾富"),

    @Schema(description = "OK超商")
    OK_MART("OK超商"),

    @Schema(description = "平台配送")
    PLATFORM_DELIVERY("平台配送");

    @JsonValue  // 讓 API 傳輸用 enum 名稱 (HOME_DELIVERY)，而不是中文
    private final String code;

    PickupServiceTypeEnum(String description) {
        this.code = name(); // HOME_DELIVERY / SEVEN_ELEVEN / FAMILY_MART / HILIFE / OK_MART
    }

    /**
     * 獲取中文描述
     */
    public String getDescription() {
        switch (this) {
            case HOME_DELIVERY:
                return "宅配服務";
            case SEVEN_ELEVEN:
                return "7-11";
            case FAMILY_MART:
                return "全家";
            case HILIFE:
                return "萊爾富";
            case OK_MART:
                return "OK超商";
            case PLATFORM_DELIVERY:
                return "平台配送";
            default:
                return name();
        }
    }

    /**
     * 判斷是否為宅配服務（包括平台配送）
     */
    public boolean isHomeDelivery() {
        return this == HOME_DELIVERY || this == PLATFORM_DELIVERY;
    }

    /**
     * 判斷是否為平台配送
     */
    public boolean isPlatformDelivery() {
        return this == PLATFORM_DELIVERY;
    }

    /**
     * 判斷是否為超商取貨（任一超商）
     */
    public boolean isStorePickup() {
        return this == SEVEN_ELEVEN || 
               this == FAMILY_MART || 
               this == HILIFE || 
               this == OK_MART;
    }

    /**
     * 根據枚舉值獲取枚舉實例
     */
    public static PickupServiceTypeEnum fromValue(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

