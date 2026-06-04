package com.agora.service;

import com.agora.model.User;

/**
 * 智能自動回復服務接口
 */
public interface AutoReplyService {
    
    /**
     * 生成智能自動回復
     * @param userMessage 用戶消息
     * @param user 用戶信息
     * @param sessionId 會話ID（String類型）
     * @return 自動回復內容
     */
    String generateAutoReply(String userMessage, User user, String sessionId);
    
    /**
     * 獲取用戶會話歷史摘要
     * @param userId 用戶ID
     * @param sessionId 會話ID（String類型）
     * @return 會話摘要
     */
    String getSessionSummary(Long userId, String sessionId);
} 