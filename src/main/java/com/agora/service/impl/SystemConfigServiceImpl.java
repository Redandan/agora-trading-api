package com.agora.service.impl;

import com.agora.model.SystemConfig;
import com.agora.repository.system.SystemConfigRepository;
import com.agora.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {
    
    private final SystemConfigRepository systemConfigRepository;
    
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
    }
    
    @Override
    public boolean isConfigEnabled(String configKey) {
        String value = getConfigValue(configKey, "false");
        return "true".equalsIgnoreCase(value);
    }
}

