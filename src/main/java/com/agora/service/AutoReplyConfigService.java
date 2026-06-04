package com.agora.service;

import com.agora.model.AutoReplyConfig;
import com.agora.dto.autoreply.AutoReplyConfigSearchRequest;
import com.agora.repository.system.AutoReplyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoReplyConfigService {
    
    private final AutoReplyConfigRepository configRepository;
    
    /**
     * 搜尋配置
     */
    @Transactional(readOnly = true)
    public Page<AutoReplyConfig> searchConfigs(AutoReplyConfigSearchRequest searchRequest) {
        // 處理 null 值，設置默認值
        String sortBy = searchRequest.getSortBy();
        if (sortBy == null || sortBy.trim().isEmpty()) {
            sortBy = "priority";
        }
        
        String sortDirection = searchRequest.getSortDirection();
        if (sortDirection == null || sortDirection.trim().isEmpty()) {
            sortDirection = "asc";
        }
        
        // 構建排序
        Sort sort = Sort.by(
            "asc".equalsIgnoreCase(sortDirection) ? 
                Sort.Direction.ASC : Sort.Direction.DESC, 
            sortBy
        );
        
        // 構建分頁
        Pageable pageable = PageRequest.of(
            searchRequest.getPage(), 
            searchRequest.getSize(), 
            sort
        );
        
        // 執行搜尋
        Page<AutoReplyConfig> result = configRepository.searchConfigs(
            searchRequest.getName(),
            searchRequest.getKeyword(),
            searchRequest.getEnabled(),
            searchRequest.getMinPriority(),
            searchRequest.getMaxPriority(),
            searchRequest.getMinHitCount(),
            searchRequest.getMaxHitCount(),
            pageable
        );
        
        log.info("搜尋自動回復配置: 條件={}, 結果數量={}", searchRequest, result.getTotalElements());
        
        return result;
    }
    
    /**
     * 創建新配置
     */
    @Transactional
    public AutoReplyConfig createConfig(AutoReplyConfig config) {
        // 檢查名稱是否已存在
        if (configRepository.existsByName(config.getName())) {
            throw new IllegalArgumentException("配置名稱已存在: " + config.getName());
        }
        
        // 檢查關鍵詞是否已存在
        if (configRepository.existsByKeyword(config.getKeyword())) {
            throw new IllegalArgumentException("關鍵詞已存在: " + config.getKeyword());
        }
        
        // 設置默認值
        if (config.getPriority() == null) {
            config.setPriority(1);
        }
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        if (config.getHitCount() == null) {
            config.setHitCount(0L);
        }
        
        AutoReplyConfig savedConfig = configRepository.save(config);
        log.info("創建自動回復配置: id={}, name={}, keyword={}", 
            savedConfig.getId(), savedConfig.getName(), savedConfig.getKeyword());
        
        return savedConfig;
    }
    
    /**
     * 更新配置
     */
    @Transactional
    public AutoReplyConfig updateConfig(Long id, AutoReplyConfig config) {
        AutoReplyConfig existingConfig = configRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        
        // 檢查名稱是否與其他配置衝突
        if (!existingConfig.getName().equals(config.getName()) && 
            configRepository.existsByName(config.getName())) {
            throw new IllegalArgumentException("配置名稱已存在: " + config.getName());
        }
        
        // 檢查關鍵詞是否與其他配置衝突
        if (!existingConfig.getKeyword().equals(config.getKeyword()) && 
            configRepository.existsByKeyword(config.getKeyword())) {
            throw new IllegalArgumentException("關鍵詞已存在: " + config.getKeyword());
        }
        
        // 更新字段
        existingConfig.setName(config.getName());
        existingConfig.setDescription(config.getDescription());
        existingConfig.setKeyword(config.getKeyword());
        existingConfig.setReplyContent(config.getReplyContent());
        existingConfig.setPriority(config.getPriority());
        existingConfig.setEnabled(config.getEnabled());
        
        AutoReplyConfig savedConfig = configRepository.save(existingConfig);
        log.info("更新自動回復配置: id={}, name={}, keyword={}", 
            savedConfig.getId(), savedConfig.getName(), savedConfig.getKeyword());
        
        return savedConfig;
    }
    
    /**
     * 刪除配置
     */
    @Transactional
    public void deleteConfig(Long id) {
        if (!configRepository.existsById(id)) {
            throw new IllegalArgumentException("配置不存在: " + id);
        }
        
        configRepository.deleteById(id);
        log.info("刪除自動回復配置: id={}", id);
    }
    
    /**
     * 啟用/禁用配置
     */
    @Transactional
    public AutoReplyConfig toggleConfig(Long id, Boolean enabled) {
        AutoReplyConfig config = configRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        
        config.setEnabled(enabled);
        AutoReplyConfig savedConfig = configRepository.save(config);
        
        log.info("{}自動回復配置: id={}, name={}, keyword={}", 
            enabled ? "啟用" : "禁用", savedConfig.getId(), savedConfig.getName(), savedConfig.getKeyword());
        
        return savedConfig;
    }
    
    /**
     * 根據消息查找匹配的配置
     */
    @Transactional(readOnly = true)
    public Optional<AutoReplyConfig> findMatchingConfig(String message) {
        // 使用搜尋方法查找所有啟用的配置
        AutoReplyConfigSearchRequest searchRequest = AutoReplyConfigSearchRequest.builder()
            .enabled(true)
            .page(0)
            .size(1000) // 獲取所有啟用的配置
            .sortBy("priority")
            .sortDirection("asc")
            .build();
        
        Page<AutoReplyConfig> enabledConfigs = configRepository.searchConfigs(
            searchRequest.getName(),
            searchRequest.getKeyword(),
            searchRequest.getEnabled(),
            searchRequest.getMinPriority(),
            searchRequest.getMaxPriority(),
            searchRequest.getMinHitCount(),
            searchRequest.getMaxHitCount(),
            PageRequest.of(0, 1000, Sort.by(Sort.Direction.ASC, "priority"))
        );
        
        for (AutoReplyConfig config : enabledConfigs.getContent()) {
            if (config.matches(message)) {
                log.debug("找到匹配的自動回復配置: id={}, name={}, keyword={}", 
                    config.getId(), config.getName(), config.getKeyword());
                return Optional.of(config);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * 處理自動回復並更新統計
     */
    @Transactional
    public Optional<String> processAutoReply(String message) {
        Optional<AutoReplyConfig> matchingConfig = findMatchingConfig(message);
        
        if (matchingConfig.isPresent()) {
            AutoReplyConfig config = matchingConfig.get();
            
            // 增加命中次數
            config.incrementHitCount();
            configRepository.save(config);
            
            log.info("自動回復觸發: configId={}, name={}, keyword={}, hitCount={}", 
                config.getId(), config.getName(), config.getKeyword(), config.getHitCount());
            
            return Optional.of(config.getReplyContent());
        }
        
        return Optional.empty();
    }
    
    /**
     * 獲取統計信息
     */
    @Transactional(readOnly = true)
    public AutoReplyStats getStats() {
        List<AutoReplyConfig> allConfigs = configRepository.findAll();
        Long totalHitCount = configRepository.getTotalHitCount();
        
        long enabledCount = allConfigs.stream().mapToLong(c -> c.getEnabled() ? 1 : 0).sum();
        long disabledCount = allConfigs.size() - enabledCount;
        
        AutoReplyStats stats = new AutoReplyStats();
        stats.setTotalConfigs((long) allConfigs.size());
        stats.setEnabledConfigs(enabledCount);
        stats.setDisabledConfigs(disabledCount);
        stats.setTotalHitCount(totalHitCount != null ? totalHitCount : 0L);
        stats.setLastUpdated(LocalDateTime.now());
        
        return stats;
    }
    
    /**
     * 重置所有配置的命中次數
     */
    @Transactional
    public Long resetAllHitCounts() {
        List<AutoReplyConfig> allConfigs = configRepository.findAll();
        for (AutoReplyConfig config : allConfigs) {
            config.setHitCount(0L);
            config.setLastHitTime(null);
        }
        configRepository.saveAll(allConfigs);
        
        log.info("重置所有配置的命中次數統計，共重置 {} 個配置", allConfigs.size());
        return (long) allConfigs.size();
    }
    
    /**
     * 自動回復統計信息
     */
    public static class AutoReplyStats {
        private Long totalConfigs = 0L;
        private Long enabledConfigs = 0L;
        private Long disabledConfigs = 0L;
        private Long totalHitCount = 0L;
        private LocalDateTime lastUpdated;
        
        // Getters and Setters
        public Long getTotalConfigs() { return totalConfigs; }
        public void setTotalConfigs(Long totalConfigs) { this.totalConfigs = totalConfigs; }
        
        public Long getEnabledConfigs() { return enabledConfigs; }
        public void setEnabledConfigs(Long enabledConfigs) { this.enabledConfigs = enabledConfigs; }
        
        public Long getDisabledConfigs() { return disabledConfigs; }
        public void setDisabledConfigs(Long disabledConfigs) { this.disabledConfigs = disabledConfigs; }
        
        public Long getTotalHitCount() { return totalHitCount; }
        public void setTotalHitCount(Long totalHitCount) { this.totalHitCount = totalHitCount; }
        
        public LocalDateTime getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
    }
}