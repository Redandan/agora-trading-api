package com.agora.dto.post;

import com.agora.dto.common.BaseSearchParam;
import com.agora.enums.system.PostStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "貼文搜索參數")
public class PostSearchParam extends BaseSearchParam {
    
    @Schema(description = "關鍵字搜索（標題或內容）")
    private String keyword;
    
    @Schema(description = "商店ID")
    private Long storeId;
    
    @Schema(description = "作者ID")
    private Long authorId;
    
    @Schema(description = "貼文狀態")
    private PostStatusEnum status;
    
    @Schema(description = "分類")
    private String category;
    
    @Schema(description = "標籤")
    private String tag;
    
    @Schema(description = "是否精選")
    private Boolean isFeatured;
    
    @Schema(description = "是否置頂")
    private Boolean isTop;
    
    @Schema(description = "排序方式：view_count（瀏覽次數）, like_count（點讚次數）, comment_count（評論次數）, created_at（創建時間）, publish_time（發布時間）")
    private String sortBy = "publish_time";
    
    @Schema(description = "排序方向：asc（升序）, desc（降序）", defaultValue = "desc")
    private String sortDirection = "desc";
}
