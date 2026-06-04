package com.agora.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 分頁響應類
 * 統一所有分頁 API 的響應格式
 * 使用 1-based pageNumber（與 API 請求的 page 參數一致）
 * 
 * ⚠️ 重要：此類應該只通過 {@link com.agora.config.PageResponseAdvice} 自動創建
 * 請不要在控制器或服務層手動創建 PageResponse 對象
 * 正確做法：直接返回 {@link org.springframework.data.domain.Page}，讓 PageResponseAdvice 自動轉換
 */
@Data
@Schema(description = "分頁響應")
public class PageResponse<T> {
    
    @Schema(description = "數據列表")
    private List<T> content;
    
    @Schema(description = "當前頁碼（1-based）", example = "1")
    private int page;
    
    @Schema(description = "當前頁碼（1-based，與 page 相同）", example = "1")
    private int pageNumber;
    
    @Schema(description = "每頁數量", example = "20")
    private int size;
    
    @Schema(description = "總記錄數", example = "100")
    private long totalElements;
    
    @Schema(description = "總頁數", example = "5")
    private int totalPages;
    
    @Schema(description = "是否為第一頁", example = "false")
    private boolean first;
    
    @Schema(description = "是否為最後一頁", example = "false")
    private boolean last;
    
    @Schema(description = "是否有下一頁", example = "true")
    private boolean hasNext;
    
    @Schema(description = "是否有上一頁", example = "true")
    private boolean hasPrevious;
    
    @Schema(description = "當前頁的元素數量", example = "10")
    private int numberOfElements;
    
    @Schema(description = "是否為空", example = "false")
    private boolean empty;
    
    /**
     * 無參構造函數（供 JSON 反序列化使用）
     * ⚠️ 不應該手動調用此構造函數創建 PageResponse
     */
    PageResponse() {}
    
    /**
     * 構造函數（package-private，只允許同包或 PageResponseFactory 使用）
     * ⚠️ 不應該手動調用此構造函數創建 PageResponse
     * 請使用 {@link com.agora.config.PageResponseAdvice} 自動轉換
     */
    PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.pageNumber = page; // pageNumber 與 page 相同（都是 1-based）
        this.size = size;
        this.totalElements = totalElements;
        // 防止除以零，當 size 為 0 時，totalPages 設為 0
        this.totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        this.first = page == 1;
        this.last = page >= totalPages;
        this.hasNext = page < totalPages;
        this.hasPrevious = page > 1;
        this.numberOfElements = content != null ? content.size() : 0;
        this.empty = content == null || content.isEmpty();
    }
    
    /**
     * 創建 PageResponse 的靜態工廠方法（package-private，只允許同包或 PageResponseFactory 使用）
     * ⚠️ 不應該手動調用此方法創建 PageResponse
     * 請使用 {@link com.agora.config.PageResponseAdvice} 自動轉換
     */
    static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResponse<>(content, page, size, totalElements);
    }
}
