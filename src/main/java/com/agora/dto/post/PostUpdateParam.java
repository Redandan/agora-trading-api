package com.agora.dto.post;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

@Data
@Schema(description = "更新貼文參數")
public class PostUpdateParam {
    
    @NotNull(message = "貼文ID不能為空")
    @Schema(description = "貼文ID", required = true)
    private Long id;
    
    @Size(max = 200, message = "貼文標題長度不能超過200個字符")
    @Schema(description = "貼文標題")
    private String title;
    
    @Schema(description = "貼文內容")
    private String content;
    
    @Schema(description = "標籤列表")
    private List<String> tags;
    
    @Schema(description = "分類")
    private String category;
    
    @Schema(description = "SEO標題")
    private String metaTitle;
    
    @Schema(description = "SEO描述")
    private String metaDescription;
    
    @Schema(description = "SEO關鍵字")
    private String metaKeywords;
    
    /**
     * 貼文圖片URL集合
     */
    @Schema(description = "貼文圖片URL集合")
    private Set<String> imageUrls;

}
