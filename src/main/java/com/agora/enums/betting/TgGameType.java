package com.agora.enums.betting;

/**
 * TG Game Type Enum
 * 遊戲類型
 */
public enum TgGameType {
    SLOT_MACHINE("🎰", "拉霸機"),
    DICE("🎲", "骰子"),
    DARTS("🎯", "飛鏢"),
    BASKETBALL("🏀", "籃球"),
    FOOTBALL("⚽", "足球"),
    BOWLING("🎳", "保齡球");
    
    private final String emoji;
    private final String displayName;
    
    TgGameType(String emoji, String displayName) {
        this.emoji = emoji;
        this.displayName = displayName;
    }
    
    public String getEmoji() {
        return emoji;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
