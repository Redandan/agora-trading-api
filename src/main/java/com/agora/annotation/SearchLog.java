package com.agora.annotation;

import java.lang.annotation.*;

/**
 * 搜尋紀錄註解
 * 用於標記需要記錄搜尋行為的方法
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SearchLog {
    
    /**
     * 搜尋類型
     */
    String searchType() default "UNKNOWN";
    
    /**
     * 是否記錄請求參數
     */
    boolean logRequestParams() default true;
    
    /**
     * 是否記錄請求體
     */
    boolean logRequestBody() default true;
    
    /**
     * 是否記錄響應
     */
    boolean logResponse() default true;
    
    /**
     * 關鍵字參數名稱（用於從請求參數中提取關鍵字）
     */
    String keywordParam() default "keyword";
}
