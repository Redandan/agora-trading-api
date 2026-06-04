package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "商品分類枚舉")
public enum ProductCategoryEnum {

    @Schema(description = "電子產品")
    ELECTRONICS("電子產品"),

    @Schema(description = "手機與配件")
    MOBILE("手機與配件"),

    @Schema(description = "服飾")
    CLOTHING("服飾"),

    @Schema(description = "鞋子")
    SHOES("鞋子"),

    @Schema(description = "包包與配件")
    BAGS("包包與配件"),

    @Schema(description = "美妝")
    BEAUTY("美妝"),

    @Schema(description = "保健與保養")
    HEALTH("保健與保養"),

    @Schema(description = "食品與飲料")
    FOOD("食品與飲料"),

    @Schema(description = "家居生活")
    HOME("家居生活"),

    @Schema(description = "家具與裝飾")
    FURNITURE("家具與裝飾"),

    @Schema(description = "嬰幼兒用品")
    BABY("嬰幼兒用品"),

    @Schema(description = "玩具與遊戲")
    TOYS("玩具與遊戲"),

    @Schema(description = "寵物用品")
    PET_SUPPLIES("寵物用品"),

    @Schema(description = "運動用品")
    SPORTS("運動用品"),

    @Schema(description = "戶外與旅遊")
    OUTDOOR("戶外與旅遊"),

    @Schema(description = "汽機車與配件")
    AUTOMOTIVE("汽機車與配件"),

    @Schema(description = "書籍與文具")
    BOOKS("書籍與文具"),

    @Schema(description = "二手商品")
    SECOND_HAND("二手商品"),

    @Schema(description = "數位服務代購")
    DIGITAL_SERVICE("數位服務代購"),

    @Schema(description = "其他")
    OTHER("其他");

    private final String displayName;

    ProductCategoryEnum(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
