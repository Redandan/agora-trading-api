package com.agora.enums.betting;

/**
 * Moon Dance Slot – 9 種 Symbol 定義
 *
 * <p>每個 Symbol 包含：
 * <ul>
 *   <li>ID (0-8) – Reel Strip 中的數字代碼</li>
 *   <li>display – Emoji 顯示字元</li>
 *   <li>wild – 是否為萬能牌（APPLE = WILD）</li>
 *   <li>description – Symbol 中文名稱備註</li>
 * </ul>
 */
public enum SlotSymbol {

    /** ID 0 – 蘋果 (WILD) */
    APPLE(0, "🍎", true, "蘋果 (WILD)"),

    /** ID 1 – 櫻桃 */
    CHERRY(1, "🍒", false, "櫻桃"),

    /** ID 2 – 七 */
    SEVEN(2, "7️⃣", false, "七"),

    /** ID 3 – 檸檬 */
    LEMON(3, "🍋", false, "檸檬"),

    /** ID 4 – BAR */
    BAR(4, "BAR", false, "BAR"),

    /** ID 5 – 葡萄 */
    GRAPE(5, "🍇", false, "葡萄"),

    /** ID 6 – 愛心 */
    HEART(6, "❤️", false, "愛心"),

    /** ID 7 – 鈴鐺 */
    BELL(7, "🔔", false, "鈴鐺"),

    /** ID 8 – 西瓜 */
    WATERMELON(8, "🍉", false, "西瓜");

    private final int id;
    private final String display;
    private final boolean wild;
    /** Symbol 中文名稱備註 */
    private final String description;

    SlotSymbol(int id, String display, boolean wild, String description) {
        this.id = id;
        this.display = display;
        this.wild = wild;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public String getDisplay() {
        return display;
    }

    public boolean isWild() {
        return wild;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根據 ID 返回對應 Symbol
     */
    public static SlotSymbol fromId(int id) {
        for (SlotSymbol s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown SlotSymbol id: " + id);
    }
}
