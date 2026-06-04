package com.agora.service;

import com.agora.dto.deployment.AppVersionDTO;
import com.agora.dto.deployment.AppVersionListResponse;
import com.agora.model.AppVersion;
import com.agora.repository.system.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 應用程式版本服務
 * 提供版本查詢和管理功能
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppVersionService {

    private final AppVersionRepository appVersionRepository;

    /**
     * 格式化檔案大小為人類可讀格式
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return new DecimalFormat("#.##").format(bytes / 1024.0) + " KB";
        } else if (bytes < 1024 * 1024 * 1024) {
            return new DecimalFormat("#.##").format(bytes / (1024.0 * 1024.0)) + " MB";
        } else {
            return new DecimalFormat("#.##").format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
        }
    }

    /**
     * 將 AppVersion 實體轉換為 DTO
     */
    private AppVersionDTO toDTO(AppVersion appVersion) {
        return AppVersionDTO.builder()
                .id(appVersion.getId())
                .version(appVersion.getVersion())
                .platform(appVersion.getPlatform())
                .fileName(appVersion.getFileName())
                .downloadUrl(appVersion.getDownloadUrl())
                .fileSize(appVersion.getFileSize())
                .fileSizeFormatted(formatFileSize(appVersion.getFileSize()))
                .contentType(appVersion.getContentType())
                .description(appVersion.getDescription())
                .releaseTime(appVersion.getReleaseTime())
                .createdAt(appVersion.getCreatedAt())
                .build();
    }

    /**
     * 獲取所有平台的版本列表（按平台分組）
     */
    public AppVersionListResponse getAllVersions() {
        List<AppVersion> allVersions = appVersionRepository.findAllActiveVersions();
        
        // 按平台分組
        Map<String, List<AppVersionDTO>> versionsByPlatform = allVersions.stream()
                .map(this::toDTO)
                .collect(Collectors.groupingBy(AppVersionDTO::getPlatform));

        // 獲取所有平台列表
        List<String> platforms = appVersionRepository.findAllPlatforms();

        return AppVersionListResponse.builder()
                .versionsByPlatform(versionsByPlatform)
                .platforms(platforms)
                .totalVersions(allVersions.size())
                .build();
    }

    /**
     * 獲取指定平台的版本列表
     */
    public List<AppVersionDTO> getVersionsByPlatform(String platform) {
        List<AppVersion> versions = appVersionRepository.findByPlatformOrderByVersionDesc(platform);
        return versions.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 獲取指定平台的最新版本（按版本號排序）
     */
    public AppVersionDTO getLatestVersionByPlatform(String platform) {
        return appVersionRepository.findByPlatformOrderByVersionDesc(platform).stream()
                .findFirst()
                .map(this::toDTO)
                .orElse(null);
    }

    /**
     * 獲取所有平台列表
     */
    public List<String> getAllPlatforms() {
        return appVersionRepository.findAllPlatforms();
    }

    /**
     * 獲取所有平台的版本列表（返回實體）
     */
    public List<AppVersion> getAllVersionsAsEntity() {
        return appVersionRepository.findAllActiveVersions();
    }

    /**
     * 獲取指定平台的版本列表（返回實體）
     */
    public List<AppVersion> getVersionsByPlatformAsEntity(String platform) {
        return appVersionRepository.findByPlatformOrderByVersionDesc(platform);
    }

    /**
     * 獲取指定平台的最新版本（返回實體，按版本號排序）
     */
    public Optional<AppVersion> getLatestVersionByPlatformAsEntity(String platform) {
        return appVersionRepository.findByPlatformOrderByVersionDesc(platform).stream()
                .findFirst();
    }

    /**
     * 根據 ID 獲取版本
     */
    public Optional<AppVersion> findById(Long versionId) {
        return appVersionRepository.findById(versionId);
    }

    /**
     * 根據 ID 刪除版本（硬刪除）
     */
    public void deleteVersionById(Long versionId) {
        AppVersion version = appVersionRepository.findById(versionId)
                .orElseThrow(() -> {
                    log.warn("版本不存在，無法硬刪除: versionId={}", versionId);
                    return new RuntimeException("版本不存在: " + versionId);
                });
        
        appVersionRepository.delete(version);
        log.info("已硬刪除版本: versionId={}, platform={}, version={}", 
                versionId, version.getPlatform(), version.getVersion());
    }
}

