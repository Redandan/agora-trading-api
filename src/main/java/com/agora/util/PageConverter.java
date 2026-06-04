package com.agora.util;

/**
 * 分頁轉換工具類
 * 用於處理從 1 開始的頁碼與從 0 開始的頁碼之間的轉換
 */
public class PageConverter {
    
    /**
     * 將從 1 開始的頁碼轉換為從 0 開始的頁碼（用於數據庫查詢）
     *
     * @param pageOneBased 從 1 開始的頁碼
     * @return 從 0 開始的頁碼
     */
    public static int toZeroBased(int pageOneBased) {
        if (pageOneBased < 1) {
            return 0;            
        }
        return pageOneBased - 1;
    }
} 