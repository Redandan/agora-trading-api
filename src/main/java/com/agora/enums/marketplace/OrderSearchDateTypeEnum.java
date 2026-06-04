package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "訂單搜索日期類型")
public enum OrderSearchDateTypeEnum {
    
    @Schema(description = "按創建時間搜索")
    CREATED_TIME("CREATED_TIME", "按創建時間搜索"),
    
    @Schema(description = "按更新時間搜索")
    UPDATED_TIME("UPDATED_TIME", "按更新時間搜索");
    
    private final String code;
    private final String description;
    
    OrderSearchDateTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
}
