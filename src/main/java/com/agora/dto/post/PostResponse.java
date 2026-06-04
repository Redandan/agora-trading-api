package com.agora.dto.post;

import com.agora.enums.system.PostStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
@Schema(description = "貼文響應")
public class PostResponse {
    
    @Schema(description = "貼文ID")
    private Long id;
    
    @Schema(description = "貼文標題")
    private String title;
    
    @Schema(description = "貼文內容")
    private String content;
    
    @Schema(description = "商店ID")
    private Long storeId;
    
    @Schema(description = "商店名稱")
    private String storeName;
    
    @Schema(description = "商店Logo")
    private String storeLogo;
    
    @Schema(description = "作者ID")
    private Long authorId;
    
    @Schema(description = "作者名稱")
    private String authorName;
    
    @Schema(description = "作者頭像")
    private String authorAvatar;
    
    @Schema(description = "貼文狀態")
    private PostStatusEnum status;
    
    @Schema(description = "瀏覽次數")
    private Long viewCount;
    
    @Schema(description = "點讚次數")
    private Long likeCount;
    
    @Schema(description = "評論次數")
    private Long commentCount;
    
    @Schema(description = "分享次數")
    private Long shareCount;
    
    @Schema(description = "是否精選")
    private Boolean isFeatured;
    
    @Schema(description = "是否置頂")
    private Boolean isTop;
    
    @Schema(description = "發布時間")
    private LocalDateTime publishTime;
    
    @Schema(description = "精選時間")
    private LocalDateTime featuredTime;
    
    @Schema(description = "置頂時間")
    private LocalDateTime topTime;
    
    @Schema(description = "標籤列表")
    private List<String> tags;
    
    @Schema(description = "分類")
    private String category;
    
    @Schema(description = "創建時間")
    private LocalDateTime createdAt;
    
    @Schema(description = "更新時間")
    private LocalDateTime updatedAt;
    
    @Schema(description = "當前用戶是否已點讚")
    private Boolean isLikedByCurrentUser;
    
    @Schema(description = "當前用戶是否已收藏")
    private Boolean isBookmarkedByCurrentUser;
    
    @Schema(description = "貼文圖片URL列表")
    private Set<String> imageUrls;
   
}
