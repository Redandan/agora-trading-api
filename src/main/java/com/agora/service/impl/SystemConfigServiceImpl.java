package com.agora.service.impl;

import com.agora.model.SystemConfig;
import com.agora.repository.system.SystemConfigRepository;
import com.agora.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {
    
    private final SystemConfigRepository systemConfigRepository;
    
    // 配置鍵常量
    private static final String SELLER_MAINTENANCE_KEY = "seller_maintenance_enabled";
    private static final String DELIVERY_MAINTENANCE_KEY = "delivery_maintenance_enabled";
    
    // 緩存維護狀態，使用 AtomicBoolean 保證線程安全
    private final AtomicBoolean sellerMaintenanceCache = new AtomicBoolean(false);
    private final AtomicBoolean deliveryMaintenanceCache = new AtomicBoolean(false);
    
    /**
     * 項目啟動時預加載維護狀態
     */
    @PostConstruct
    public void init() {
        loadMaintenanceStatus();
        log.info("Maintenance status loaded - Seller: {}, Delivery: {}", 
                sellerMaintenanceCache.get(), deliveryMaintenanceCache.get());
    }
    
    /**
     * 從數據庫加載維護狀態到緩存
     */
    private void loadMaintenanceStatus() {
        sellerMaintenanceCache.set(isConfigEnabled(SELLER_MAINTENANCE_KEY));
        deliveryMaintenanceCache.set(isConfigEnabled(DELIVERY_MAINTENANCE_KEY));
    }
    
    @Override
    public String getConfigValue(String configKey, String defaultValue) {
        Optional<SystemConfig> configOpt = systemConfigRepository.findByConfigKey(configKey);
        return configOpt.map(SystemConfig::getConfigValue).orElse(defaultValue);
    }
    
    @Override
    @Transactional
    public void setConfigValue(String configKey, String configValue, String description) {
        Optional<SystemConfig> configOpt = systemConfigRepository.findByConfigKey(configKey);
        SystemConfig config;
        if (configOpt.isPresent()) {
            config = configOpt.get();
            config.setConfigValue(configValue);
            if (description != null) {
                config.setDescription(description);
            }
        } else {
            config = new SystemConfig();
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setDescription(description);
        }
        systemConfigRepository.save(config);
        
        // 更新緩存
        if (SELLER_MAINTENANCE_KEY.equals(configKey)) {
            sellerMaintenanceCache.set("true".equalsIgnoreCase(configValue));
            log.info("Seller maintenance status updated to: {}", configValue);
        } else if (DELIVERY_MAINTENANCE_KEY.equals(configKey)) {
            deliveryMaintenanceCache.set("true".equalsIgnoreCase(configValue));
            log.info("Delivery maintenance status updated to: {}", configValue);
        }
    }
    
    @Override
    public boolean isConfigEnabled(String configKey) {
        String value = getConfigValue(configKey, "false");
        return "true".equalsIgnoreCase(value);
    }
    
    @Override
    public boolean isSellerMaintenanceEnabled() {
        return sellerMaintenanceCache.get();
    }
    
    @Override
    public boolean isDeliveryMaintenanceEnabled() {
        return deliveryMaintenanceCache.get();
    }
}

