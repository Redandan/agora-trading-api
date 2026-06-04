package com.agora.dto.post;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

@Data
@Schema(description = "創建貼文參數")
public class PostCreateParam {
    
    @NotBlank(message = "貼文標題不能為空")
    @Size(max = 200, message = "貼文標題長度不能超過200個字符")
    @Schema(description = "貼文標題", required = true)
    private String title;
    
    @NotBlank(message = "貼文內容不能為空")
    @Schema(description = "貼文內容", required = true)
    private String content;
    
    @NotNull(message = "商店ID不能為空")
    @Schema(description = "商店ID", required = true)
    private Long storeId;
    
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
    
    @Schema(description = "是否立即發布", defaultValue = "false")
    private Boolean publishNow = false;
    
    /**
     * 貼文圖片URL集合
     */
    @Schema(description = "貼文圖片URL集合")
    private Set<String> imageUrls;
    
}
