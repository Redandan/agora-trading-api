package com.agora.service.impl;

import com.agora.model.UserSearchLog;
import com.agora.repository.system.UserSearchLogRepository;
import com.agora.service.UserSearchLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用戶搜尋紀錄服務實現（AOP異步記錄）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSearchLogServiceImpl implements UserSearchLogService {
    
    private final UserSearchLogRepository searchLogRepository;
    
    @Override
    @Async
    @Transactional
    public void logSearchRequest(UserSearchLog searchLog) {
        try {
            searchLogRepository.save(searchLog);
            
            log.debug("異步記錄搜尋請求: userId={}, searchType={}, keyword={}, responseTime={}ms", 
                     searchLog.getUserId(), searchLog.getSearchType(), 
                     searchLog.getKeyword(), searchLog.getResponseTimeMs());
            
        } catch (Exception e) {
            log.error("異步記錄搜尋請求失敗: userId={}, searchType={}, error={}", 
                     searchLog.getUserId(), searchLog.getSearchType(), e.getMessage(), e);
        }
    }
}
