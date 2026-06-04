package com.agora.util;

import com.agora.model.User;
import com.agora.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.extern.slf4j.Slf4j;

/**
 * 安全工具類
 * 提供獲取當前登入用戶的便捷方法
 */
@Slf4j
public class SecurityUtils {

    /**
     * 獲取當前登入的用戶
     *
     * @return 當前用戶對象
     * @throws RuntimeException 當用戶未登入時拋出
     */
    public static User getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            log.debug("Authentication object: {}", authentication);
            
            if (authentication == null) {
                log.warn("Authentication is null");
                throw new RuntimeException("用戶未登入");
            }
            
            log.debug("Authentication isAuthenticated: {}, principal: {}", 
                    authentication.isAuthenticated(), authentication.getPrincipal());
            
            return getCurrentUser(authentication);
        } catch (Exception e) {
            log.error("Error in getCurrentUser(): {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 從認證信息中獲取用戶對象
     *
     * @param authentication 認證信息
     * @return 用戶對象
     * @throws RuntimeException 當認證信息無效時拋出
     */
    public static User getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("認證信息無效");
        }

        Object principal = authentication.getPrincipal();
        log.debug("Authentication principal type: {}", principal.getClass().getName());
        
        if (principal instanceof User) {
            return (User) principal;
        } else if (principal instanceof UserPrincipal) {
            // 如果 principal 是 UserPrincipal，直接獲取 User 對象
            return ((UserPrincipal) principal).getUser();
        } else if (principal instanceof UserDetails) {
            // 如果 principal 是其他 UserDetails 實現，嘗試通過用戶名重新加載
            String username = ((UserDetails) principal).getUsername();
            log.debug("Principal is UserDetails, username: {}", username);
            // 這裡需要重新加載用戶信息，但為了避免循環依賴，我們拋出異常
            throw new RuntimeException("無法從 UserDetails 獲取用戶信息，請檢查配置");
        }

        throw new RuntimeException("無法獲取用戶信息，principal 類型: " + principal.getClass().getName());
    }
} 