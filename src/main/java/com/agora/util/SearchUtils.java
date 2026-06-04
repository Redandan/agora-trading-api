package com.agora.util;

import com.agora.dto.common.BaseSearchParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 搜索工具類
 * 提供統一的搜索邏輯處理方法
 */
public class SearchUtils {
    
    /**
     * 創建分頁請求對象
     * 
     * @param searchParam 搜索參數
     * @return Pageable 對象
     */
    public static Pageable createPageable(BaseSearchParam searchParam) {
        return createPageable(searchParam, "id");
    }
    
    /**
     * 創建分頁請求對象
     * 
     * @param searchParam 搜索參數
     * @param defaultSortBy 默認排序字段
     * @return Pageable 對象
     */
    public static Pageable createPageable(BaseSearchParam searchParam, String defaultSortBy) {
        int page = Math.max(0, searchParam.getPage() - 1); // Spring Data 從0開始
        int size = Math.max(1, Math.min(100, searchParam.getSize())); // 限制每頁大小
        
        String sortBy = searchParam.getSortBy() != null ? searchParam.getSortBy() : defaultSortBy;
        Sort.Direction direction = "ASC".equalsIgnoreCase(searchParam.getSortDirection()) 
            ? Sort.Direction.ASC : Sort.Direction.DESC;
        
        return PageRequest.of(page, size, Sort.by(direction, sortBy));
    }
    
    /**
     * 驗證搜索參數
     * 
     * @param searchParam 搜索參數
     * @return 是否有效
     */
    public static boolean isValidSearchParam(BaseSearchParam searchParam) {
        if (searchParam == null) {
            return false;
        }
        
        // 驗證頁碼
        if (searchParam.getPage() < 1) {
            return false;
        }
        
        // 驗證每頁大小
        if (searchParam.getSize() < 1 || searchParam.getSize() > 100) {
            return false;
        }
        
        // 驗證日期範圍
        if (searchParam.getStartDate() != null && searchParam.getEndDate() != null) {
            if (searchParam.getStartDate().isAfter(searchParam.getEndDate())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 標準化搜索關鍵字
     * 
     * @param keyword 原始關鍵字
     * @return 標準化後的關鍵字
     */
    public static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }
        
        return keyword.trim().toLowerCase();
    }
    
    /**
     * 檢查是否為空搜索（沒有特定條件）
     * 
     * @param searchParam 搜索參數
     * @return 是否為空搜索
     */
    public static boolean isEmptySearch(BaseSearchParam searchParam) {
        if (searchParam == null) {
            return true;
        }
        
        // 檢查是否有除基礎字段外的搜索條件
        // 這裡需要根據具體實現來判斷
        return searchParam.getKeyword() == null || searchParam.getKeyword().trim().isEmpty();
    }
}
