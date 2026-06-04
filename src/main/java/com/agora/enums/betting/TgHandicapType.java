package com.agora.enums.betting;

/**
 * TG Game Handicap Type Enum
 * 盤口類型
 */
public enum TgHandicapType {
    STANDARD("標準盤", "標準賠率"),
    HIGH_ROLLER("高額盤", "高額玩家專用"),
    PROMOTIONAL("促銷盤", "促銷活動專用"),
    VIP("VIP盤", "VIP會員專用");
    
    private final String displayName;
    private final String description;
    
    TgHandicapType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
}
