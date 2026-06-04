package com.agora.service;

import com.agora.model.UserSearchLog;

/**
 * 用戶搜尋紀錄服務接口（AOP異步記錄）
 */
public interface UserSearchLogService {
    
    /**
     * 異步記錄搜尋請求
     */
    void logSearchRequest(UserSearchLog searchLog);
}
