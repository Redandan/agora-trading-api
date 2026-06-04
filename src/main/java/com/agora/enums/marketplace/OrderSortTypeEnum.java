package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "訂單排序類型")
public enum OrderSortTypeEnum {
    
    @Schema(description = "按更新時間新到舊（默認）")
    UPDATED_DESC("updatedAt", "DESC", "按更新時間新到舊"),
    
    @Schema(description = "按更新時間舊到新")
    UPDATED_ASC("updatedAt", "ASC", "按更新時間舊到新"),
    
    @Schema(description = "按創建時間新到舊")
    CREATED_DESC("createdAt", "DESC", "按創建時間新到舊"),
    
    @Schema(description = "按創建時間舊到新")
    CREATED_ASC("createdAt", "ASC", "按創建時間舊到新"),
    
    @Schema(description = "按訂單金額高到低")
    AMOUNT_DESC("orderAmount", "DESC", "按訂單金額高到低"),
    
    @Schema(description = "按訂單金額低到高")
    AMOUNT_ASC("orderAmount", "ASC", "按訂單金額低到高");
    
    private final String field;
    private final String direction;
    private final String description;
    
    OrderSortTypeEnum(String field, String direction, String description) {
        this.field = field;
        this.direction = direction;
        this.description = description;
    }
    
    public String getField() {
        return field;
    }
    
    public String getDirection() {
        return direction;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 判斷是否為降序
     */
    public boolean isDescending() {
        return "DESC".equals(direction);
    }
}
