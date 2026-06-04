package com.agora.service;

import com.agora.dto.post.PostCreateParam;
import com.agora.dto.post.PostResponse;
import com.agora.dto.post.PostSearchParam;
import com.agora.dto.post.PostUpdateParam;
import org.springframework.data.domain.Page;

import java.util.List;

public interface PostService {
    
    /**
     * 創建貼文
     */
    PostResponse createPost(PostCreateParam param, Long currentUserId);
    
    /**
     * 更新貼文
     */
    PostResponse updatePost(PostUpdateParam param, Long currentUserId);
    
    /**
     * 獲取貼文詳情
     */
    PostResponse getPostById(Long id, Long currentUserId);
    
    /**
     * 刪除貼文
     */
    void deletePost(Long id, Long currentUserId);
    
    /**
     * 發布貼文
     */
    void publishPost(Long id, Long currentUserId);
    
    /**
     * 下架貼文
     */
    void archivePost(Long id, Long currentUserId);
    
    /**
     * 搜索貼文
     */
    Page<PostResponse> searchPosts(PostSearchParam param, Long currentUserId);
    
    /**
     * 獲取商店的貼文列表
     */
    Page<PostResponse> getStorePosts(Long storeId, PostSearchParam param, Long currentUserId);
    
    /**
     * 獲取作者的貼文列表
     */
    Page<PostResponse> getAuthorPosts(Long authorId, PostSearchParam param, Long currentUserId);
    
    /**
     * 獲取精選貼文
     */
    Page<PostResponse> getFeaturedPosts(PostSearchParam param, Long currentUserId);
    
    /**
     * 獲取置頂貼文
     */
    List<PostResponse> getTopPosts(Long currentUserId);
    
    /**
     * 點讚貼文
     */
    void likePost(Long id, Long currentUserId);
    
    /**
     * 取消點讚貼文
     */
    void unlikePost(Long id, Long currentUserId);
    
    /**
     * 分享貼文
     */
    void sharePost(Long id, Long currentUserId);
    
    /**
     * 設置貼文精選狀態
     */
    void setFeatured(Long id, Boolean isFeatured);
    
    /**
     * 設置貼文置頂狀態
     */
    void setTop(Long id, Boolean isTop);
    
    /**
     * 獲取貼文統計信息
     */
    PostStatistics getPostStatistics(Long postId);
    
    /**
     * 獲取商店貼文統計信息
     */
    StorePostStatistics getStorePostStatistics(Long storeId);
    
    /**
     * 獲取用戶貼文統計信息
     */
    UserPostStatistics getUserPostStatistics(Long userId);
    
    /**
     * 貼文統計信息
     */
    class PostStatistics {
        public Long viewCount;
        public Long likeCount;
        public Long commentCount;
        public Long shareCount;
    }
    
    /**
     * 商店貼文統計信息
     */
    class StorePostStatistics {
        public Long totalPosts;
        public Long publishedPosts;
        public Long draftPosts;
        public Long archivedPosts;
        public Long totalViews;
        public Long totalLikes;
        public Long totalComments;
        public Long totalShares;
    }
    
    /**
     * 用戶貼文統計信息
     */
    class UserPostStatistics {
        public Long totalPosts;
        public Long publishedPosts;
        public Long draftPosts;
        public Long archivedPosts;
        public Long totalViews;
        public Long totalLikes;
        public Long totalComments;
        public Long totalShares;
    }
}
