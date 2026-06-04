package com.agora.service.impl;

import com.agora.dto.member.MemberSearchParam;
import com.agora.dto.member.MemberUpdateParam;
import com.agora.enums.system.UserStatusEnum;
import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.repository.system.UserRepository;
import com.agora.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 用戶服務實現類
 * 處理用戶相關的業務邏輯
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    /**
     * 更新用戶信息
     *
     * @param id        用戶ID
     * @param updateDto 更新後的用戶信息
     * @return 更新後的用戶對象
     */
    @Override
    @Transactional
    public User updateUser(String id, User updateDto) {
        User user = findById(Long.parseLong(id));
        
        // 更新基本信息
        if (updateDto.getUsername() != null && !updateDto.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(updateDto.getUsername())) {
                throw new BusinessException("用戶名已存在");
            }
            user.setUsername(updateDto.getUsername());
        }
        
        if (updateDto.getEmail() != null && !updateDto.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(updateDto.getEmail())) {
                throw new BusinessException("郵箱已存在");
            }
            user.setEmail(updateDto.getEmail());
        }
        
        if (updateDto.getPhone() != null) {
            user.setPhone(updateDto.getPhone());
        }
        
        if (updateDto.getName() != null) {
            user.setName(updateDto.getName());
        }
        
        if (updateDto.getAvatar() != null) {
            user.setAvatar(updateDto.getAvatar());
        }
        
        return userRepository.save(user);
    }

    /**
     * 刪除用戶
     *
     * @param id 用戶ID
     */
    @Override
    @Transactional
    public void deleteUser(String id) {
        User user = findById(Long.parseLong(id));
        user.setStatus(UserStatusEnum.DELETED);
        userRepository.save(user);
    }

    /**
     * 根據ID查找用戶
     *
     * @param id 用戶ID
     * @return 用戶對象
     */
    @Override
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用戶不存在"));
    }

    /**
     * 批量根據 ID 查找用戶
     * 用於避免迴圈內 N+1 查詢（例如訂單統計、訂單列表等需要關聯多個賣家資訊的場景）
     */
    @Override
    public List<User> findAllById(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return userRepository.findAllById(ids);
    }

    @Override
    public Page<User> searchMembers(MemberSearchParam searchParam) {
        Pageable pageable = createPageableWithSort(searchParam);
        Specification<User> specification = buildMemberSearchSpecification(searchParam);
        return userRepository.findAll(specification, pageable);
    }

    private Specification<User> buildMemberSearchSpecification(MemberSearchParam searchParam) {
        return (root, query, criteriaBuilder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (searchParam.getUserId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("id"), searchParam.getUserId()));
            }

            if (StringUtils.hasText(searchParam.getUsername())) {
                predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("username")),
                    toContainsPattern(searchParam.getUsername())
                ));
            }

            if (StringUtils.hasText(searchParam.getEmail())) {
                predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("email")),
                    toContainsPattern(searchParam.getEmail())
                ));
            }

            if (searchParam.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), searchParam.getStatus()));
            }

            if (searchParam.getStartDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), searchParam.getStartDate()));
            }

            if (searchParam.getEndDate() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), searchParam.getEndDate()));
            }

            if (StringUtils.hasText(searchParam.getKeyword())) {
                predicates.add(buildKeywordPredicate(searchParam.getKeyword(), root, criteriaBuilder));
            }

            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private jakarta.persistence.criteria.Predicate buildKeywordPredicate(
            String keyword,
            jakarta.persistence.criteria.Root<User> root,
            jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder) {
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        List<jakarta.persistence.criteria.Predicate> keywordPredicates = new ArrayList<>();

        keywordPredicates.add(criteriaBuilder.like(
            criteriaBuilder.lower(root.get("username")),
            toContainsPattern(normalizedKeyword)
        ));
        keywordPredicates.add(criteriaBuilder.like(
            criteriaBuilder.lower(root.get("email")),
            toContainsPattern(normalizedKeyword)
        ));
        keywordPredicates.add(criteriaBuilder.like(
            criteriaBuilder.lower(root.get("name")),
            toContainsPattern(normalizedKeyword)
        ));

        try {
            keywordPredicates.add(criteriaBuilder.equal(root.get("id"), Long.parseLong(normalizedKeyword)));
        } catch (NumberFormatException ex) {
            log.debug("會員搜索關鍵字不是數字，跳過 userId 匹配: {}", keyword);
        }

        return criteriaBuilder.or(keywordPredicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
    }

    private String toContainsPattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * 創建支持自定義排序的 Pageable 對象
     * 
     * @param searchParam 搜索參數
     * @return 配置了排序的 Pageable 對象
     */
    private Pageable createPageableWithSort(MemberSearchParam searchParam) {
        Sort sort = createSort(searchParam);
        // #424: convert 1-based page → 0-based for Spring Data + size cap [1,100]
        int page = Math.max(0, searchParam.getPage() - 1);
        int size = Math.max(1, Math.min(100, searchParam.getSize()));
        return PageRequest.of(page, size, sort);
    }

    /**
     * 創建排序對象
     * 
     * @param searchParam 搜索參數
     * @return 配置好的 Sort 對象
     */
    private Sort createSort(MemberSearchParam searchParam) {
        // 如果沒有指定排序字段，使用默認的 createdAt 降序
        if (searchParam.getSortBy() == null || searchParam.getSortBy().trim().isEmpty()) {
            return Sort.by("createdAt").descending();
        }

        String sortBy = searchParam.getSortBy().trim();
        String sortDirection = searchParam.getSortDirection() != null ? 
            searchParam.getSortDirection().trim().toUpperCase() : "DESC";

        // 驗證排序字段是否合法
        if (!isValidSortField(sortBy)) {
            // 如果排序字段不合法，回退到默認排序
            return Sort.by("createdAt").descending();
        }

        // 創建排序
        if ("ASC".equals(sortDirection)) {
            return Sort.by(sortBy).ascending();
        } else {
            return Sort.by(sortBy).descending();
        }
    }

    /**
     * 驗證排序字段是否合法
     * 
     * @param sortBy 排序字段
     * @return 是否為合法的排序字段
     */
    private boolean isValidSortField(String sortBy) {
        // 定義允許排序的字段列表
        String[] allowedSortFields = {
            "id", "username", "email", "phone", "status", 
            "createdAt", "updatedAt", "lastLoginAt", "creditLevel"
        };
        
        for (String allowedField : allowedSortFields) {
            if (allowedField.equals(sortBy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional
    public User updateMember(MemberUpdateParam updateParam) {
        User user = findById(Long.parseLong(updateParam.getId()));

        // 更新基本信息
        if (updateParam.getUsername() != null && !updateParam.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(updateParam.getUsername())) {
                throw new BusinessException("用戶名已存在");
            }
            user.setUsername(updateParam.getUsername());
        }

        if (updateParam.getEmail() != null && !updateParam.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(updateParam.getEmail())) {
                throw new BusinessException("郵箱已存在");
            }
            user.setEmail(updateParam.getEmail());
        }

        if (updateParam.getPhone() != null) {
            user.setPhone(updateParam.getPhone());
        }

        // 更新狀態相關信息
        if (updateParam.getStatus() != null) {
            user.setStatus(updateParam.getStatus());
            // 如果狀態是禁用，則同時設置 enabled 為 false
            if (updateParam.getStatus() == UserStatusEnum.BANNED || 
                updateParam.getStatus() == UserStatusEnum.SUSPENDED) {
            } else if (updateParam.getStatus() == UserStatusEnum.ACTIVE) {
            }
        }

        if (updateParam.getRemark() != null) {
            user.setRemark(updateParam.getRemark());
        }

        return userRepository.save(user);
    }

    @Override
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用戶不存在"));
    }

    @Override
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("用戶不存在"));
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    public User getUser(Long userId) {
        return findById(userId);
    }
    
    @Override
    @Transactional
    public User updateDefaultHomePage(User user, com.agora.enums.system.DefaultHomePageEnum defaultHomePage) {
        // 驗證權限：管理員只能設置為ADMIN
        if ("ADMIN".equals(user.getRole())) {
            if (defaultHomePage != com.agora.enums.system.DefaultHomePageEnum.ADMIN) {
                throw new BusinessException("管理員只能設置為管理員首頁");
            }
        }
        // 驗證權限：如果設置為賣家首頁，必須有店鋪名稱
        else if (defaultHomePage == com.agora.enums.system.DefaultHomePageEnum.SELLER) {
            if (user.getStoreName() == null || user.getStoreName().trim().isEmpty()) {
                throw new BusinessException("您沒有開設店鋪，無法設置賣家首頁");
            }
        }
        
        user.setDefaultHomePage(defaultHomePage);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User acceptTerms(Long userId, String version) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用戶不存在"));
        user.setTermsAcceptedVersion(version);
        user.setTermsAcceptedAt(java.time.LocalDateTime.now());
        return userRepository.save(user);
    }
}
