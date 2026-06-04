package com.agora.service;

import com.agora.dto.member.MemberSearchParam;
import com.agora.dto.member.MemberUpdateParam;
import com.agora.model.User;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;

public interface UserService {
    User findById(Long id);

    /**
     * 批量根據 ID 查找用戶（避免迴圈內 N+1 查詢）
     *
     * @param ids 用戶 ID 集合
     * @return 找到的用戶列表（不存在的 ID 會被忽略）
     */
    List<User> findAllById(Collection<Long> ids);
    
    Page<User> searchMembers(MemberSearchParam searchParam);
    
    User updateMember(MemberUpdateParam updateParam);
    
    User findByUsername(String username);
    
    User findByEmail(String email);
    
    boolean existsByUsername(String username);
    
    boolean existsByEmail(String email);
    
    User updateUser(String id, User updateDto);
    
    void deleteUser(String id);
    
    User getUser(Long userId);
    
    /**
     * 更新用戶默認首頁設置
     * @param user 用戶對象
     * @param defaultHomePage 默認首頁類型
     * @return 更新後的用戶對象
     */
    User updateDefaultHomePage(User user, com.agora.enums.system.DefaultHomePageEnum defaultHomePage);

    User acceptTerms(Long userId, String version);
} 