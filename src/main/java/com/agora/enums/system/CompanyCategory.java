package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 物流公司分類
 */
@Schema(description = "物流公司分類")
public enum CompanyCategory {
    @Schema(description = "宅配公司")
    HOME_DELIVERY("宅配公司"),
    
    @Schema(description = "超商")
    CONVENIENCE_STORE("超商"),
    
    @Schema(description = "郵政")
    POSTAL("郵政"),
    
    @Schema(description = "平台配送")
    PLATFORM("平台配送"),
    
    @Schema(description = "其他")
    OTHER("其他");
    
    private final String description;
    
    CompanyCategory(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}

