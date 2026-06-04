package com.agora.service.impl;

import com.agora.enums.system.UserStatusEnum;
import com.agora.model.User;
import com.agora.repository.system.UserRepository;
import com.agora.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 自定義用戶詳細信息服務
 * 實現 Spring Security 的 UserDetailsService 接口
 * 用於從數據庫加載用戶信息並轉換為 Spring Security 可用的格式
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;

    /**
     * 根據用戶名加載用戶信息
     *
     * @param username 用戶名
     * @return 用戶詳細信息
     * @throws UsernameNotFoundException 當用戶不存在時拋出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 從數據庫查找用戶
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("找不到用戶名為 " + username + " 的用戶"));

        // 檢查用戶是否被禁用
        if (user.getStatus().equals(UserStatusEnum.BANNED)) {
            throw new UsernameNotFoundException("用戶已被禁用");
        }
        if( user.getStatus().equals(UserStatusEnum.SUSPENDED)){
            throw new UsernameNotFoundException("用戶已被暫停");
        }
        if (user.getStatus().equals(UserStatusEnum.DELETED)) {
            throw new UsernameNotFoundException("用戶已被刪除");
        }

        // 返回用戶詳細信息
        return new UserPrincipal(user);
    }
} 