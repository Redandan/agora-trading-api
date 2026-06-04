package com.agora.service;

import com.agora.dto.deployment.FlutterAppDeploymentResponse;
import com.agora.model.AppVersion;
import com.agora.repository.system.AppVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Flutter Windows App 部署服務
 * 支持從 URL 下載或直接上傳文件
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlutterAppDeploymentService {

    private final OciObjectStorageService ociObjectStorageService;
    private final AppVersionRepository appVersionRepository;
    private final com.agora.config.properties.FlutterDeploymentProperties props;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // 5分鐘讀取超時，適用於大文件
            .writeTimeout(300, TimeUnit.SECONDS)
            .build();

    /**
     * 部署 Flutter Windows App（從 URL 下載）
     */
    public FlutterAppDeploymentResponse deployAppFromUrl(
            String downloadUrl,
            String version,
            String fileName,
            String platform,
            Boolean deleteOldVersions,
            String filePrefix,
            String description) {
        
        log.info("開始部署 Flutter App（從 URL）: version={}, platform={}, downloadUrl={}", 
                version, platform, downloadUrl);
        
        List<String> deletedFiles = new ArrayList<>();
        String uploadedFileName = null;
        String fileUrl = null;
        long fileSize = 0;

        try {
            // 1. 確定檔案名稱
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = extractFileNameFromUrl(downloadUrl);
            }
            
            // 確保檔案名稱包含前綴
            String finalFileName = buildFinalFileName(fileName, filePrefix);
            
            log.info("目標檔案名稱: {}", finalFileName);

            // 2. 獲取檔案大小（使用 HEAD 請求）
            log.info("獲取檔案大小: {}", downloadUrl);
            long contentLength = getContentLength(downloadUrl);
            if (contentLength <= 0) {
                log.warn("無法從 URL 獲取檔案大小，將使用默認值 -1（讓 OCI SDK 自動處理）");
                contentLength = -1;
            }

            // 3. 下載檔案
            log.info("開始下載檔案: {}", downloadUrl);
            InputStream fileInputStream = downloadFile(downloadUrl);
            if (fileInputStream == null) {
                throw new RuntimeException("無法下載檔案: " + downloadUrl);
            }

            // 4. 刪除舊版本（如果需要）
            if (Boolean.TRUE.equals(deleteOldVersions)) {
                deletedFiles = deleteOldVersions(buildFilePrefix(filePrefix), finalFileName);
                log.info("已刪除 {} 個舊版本檔案", deletedFiles.size());
            }

            // 5. 上傳到 OCI Object Storage
            log.info("開始上傳檔案到 OCI Object Storage: {}", finalFileName);
            fileUrl = ociObjectStorageService.uploadFileFromStream(
                    fileInputStream, 
                    finalFileName, 
                    contentLength > 0 ? contentLength : -1
            );
            
            // 如果無法獲取檔案大小，嘗試從上傳後的響應中獲取
            if (contentLength <= 0) {
                try {
                    fileSize = ociObjectStorageService.getFileSize(finalFileName);
                } catch (Exception e) {
                    log.warn("無法獲取上傳後的檔案大小: {}", e.getMessage());
                    fileSize = -1;
                }
            } else {
                fileSize = contentLength;
            }
            
            uploadedFileName = finalFileName;
            
            log.info("檔案上傳成功: {} -> {}", finalFileName, fileUrl);

            // 6. 關閉流
            try {
                fileInputStream.close();
            } catch (IOException e) {
                log.warn("關閉輸入流時發生錯誤: {}", e.getMessage());
            }

            // 7. 保存版本信息到數據庫
            saveAppVersion(version, platform != null ? platform : props.defaultPlatform(), fileName, 
                    finalFileName, fileUrl, fileSize, deletedFiles, description);

            return FlutterAppDeploymentResponse.builder()
                    .success(true)
                    .message("部署成功")
                    .uploadedFileName(uploadedFileName)
                    .fileUrl(fileUrl)
                    .fileSize(fileSize)
                    .version(version)
                    .deletedFiles(deletedFiles)
                    .deploymentTime(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("部署 Flutter App 失敗: {}", e.getMessage(), e);
            return FlutterAppDeploymentResponse.builder()
                    .success(false)
                    .message("部署失敗: " + e.getMessage())
                    .uploadedFileName(uploadedFileName)
                    .fileUrl(fileUrl)
                    .fileSize(fileSize)
                    .version(version)
                    .deletedFiles(deletedFiles)
                    .deploymentTime(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 部署 Flutter Windows App（直接上傳文件）
     */
    public FlutterAppDeploymentResponse deployAppFromFile(
            MultipartFile file,
            String version,
            String fileName,
            String platform,
            Boolean deleteOldVersions,
            String filePrefix,
            String description) throws IOException {
        
        log.info("開始部署 Flutter App（直接上傳）: version={}, platform={}, fileName={}", 
                version, platform, file.getOriginalFilename());
        
        List<String> deletedFiles = new ArrayList<>();
        String uploadedFileName = null;
        String fileUrl = null;
        long fileSize = file.getSize();

        try {
            // 1. 確定檔案名稱
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = file.getOriginalFilename();
                if (fileName == null || fileName.trim().isEmpty()) {
                    fileName = "app-" + version + ".exe";
                }
            }
            
            // 確保檔案名稱包含前綴
            String finalFileName = buildFinalFileName(fileName, filePrefix);
            
            log.info("目標檔案名稱: {}", finalFileName);

            // 2. 刪除舊版本（如果需要）
            if (Boolean.TRUE.equals(deleteOldVersions)) {
                deletedFiles = deleteOldVersions(buildFilePrefix(filePrefix), finalFileName);
                log.info("已刪除 {} 個舊版本檔案", deletedFiles.size());
            }

            // 3. 上傳到 OCI Object Storage
            log.info("開始上傳檔案到 OCI Object Storage: {}", finalFileName);
            fileUrl = ociObjectStorageService.uploadFile(file, finalFileName);
            
            uploadedFileName = finalFileName;
            
            log.info("檔案上傳成功: {} -> {}", finalFileName, fileUrl);

            // 4. 保存版本信息到數據庫
            saveAppVersion(version, platform != null ? platform : props.defaultPlatform(), fileName, 
                    finalFileName, fileUrl, fileSize, deletedFiles, description);

            return FlutterAppDeploymentResponse.builder()
                    .success(true)
                    .message("部署成功")
                    .uploadedFileName(uploadedFileName)
                    .fileUrl(fileUrl)
                    .fileSize(fileSize)
                    .version(version)
                    .deletedFiles(deletedFiles)
                    .deploymentTime(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("部署 Flutter App 失敗: {}", e.getMessage(), e);
            return FlutterAppDeploymentResponse.builder()
                    .success(false)
                    .message("部署失敗: " + e.getMessage())
                    .uploadedFileName(uploadedFileName)
                    .fileUrl(fileUrl)
                    .fileSize(fileSize)
                    .version(version)
                    .deletedFiles(deletedFiles)
                    .deploymentTime(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 從 URL 下載檔案
     */
    private InputStream downloadFile(String url) throws IOException {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            Response response = httpClient.newCall(request).execute();
            
            if (!response.isSuccessful()) {
                log.error("下載檔案失敗: HTTP {}, message: {}", response.code(), response.message());
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("錯誤響應內容: {}", errorBody);
                response.close();
                return null;
            }

            return response.body().byteStream();
        } catch (Exception e) {
            log.error("下載檔案時發生錯誤: {}", e.getMessage(), e);
            throw new IOException("無法下載檔案: " + e.getMessage(), e);
        }
    }

    /**
     * 獲取檔案大小（使用 HEAD 請求）
     */
    private long getContentLength(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String contentLengthHeader = response.header("Content-Length");
                    if (contentLengthHeader != null) {
                        return Long.parseLong(contentLengthHeader);
                    }
                } else {
                    log.warn("獲取檔案大小失敗: HTTP {}, message: {}", response.code(), response.message());
                }
            }
        } catch (Exception e) {
            log.warn("無法獲取檔案大小: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 從 URL 中提取檔案名稱
     */
    private String extractFileNameFromUrl(String url) {
        try {
            String path = url;
            // 移除查詢參數
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf('?'));
            }
            // 移除錨點
            if (path.contains("#")) {
                path = path.substring(0, path.indexOf('#'));
            }
            // 獲取最後一部分
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            
            if (fileName.isEmpty()) {
                fileName = "app.exe";
            }
            
            return fileName;
        } catch (Exception e) {
            log.warn("無法從 URL 提取檔案名稱: {}", e.getMessage());
            return "app.exe";
        }
    }

    /**
     * 構建檔案前綴
     */
    private String buildFilePrefix(String filePrefix) {
        return (filePrefix != null && !filePrefix.trim().isEmpty()) ? filePrefix : props.filePrefix();
    }

    /**
     * 構建最終檔案名稱（包含前綴）
     */
    private String buildFinalFileName(String fileName, String filePrefix) {
        return buildFilePrefix(filePrefix) + fileName;
    }

    /**
     * 刪除舊版本檔案
     */
    private List<String> deleteOldVersions(String prefix, String currentFileName) {
        List<String> deletedFiles = new ArrayList<>();
        
        try {
            // 列出所有具有相同前綴的檔案
            List<String> objects = ociObjectStorageService.listObjectsByPrefix(prefix);
            
            log.info("找到 {} 個具有前綴 '{}' 的檔案", objects.size(), prefix);
            
            // 刪除除當前檔案外的所有檔案
            for (String objectName : objects) {
                if (!objectName.equals(currentFileName)) {
                    try {
                        ociObjectStorageService.deleteFile(objectName);
                        deletedFiles.add(objectName);
                        log.info("已刪除舊版本檔案: {}", objectName);
                    } catch (Exception e) {
                        log.error("刪除檔案失敗: {} - {}", objectName, e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("刪除舊版本檔案時發生錯誤: {}", e.getMessage(), e);
        }
        
        return deletedFiles;
    }

    /**
     * 保存應用程式版本信息到數據庫
     */
    @Transactional
    private void saveAppVersion(
            String version,
            String platform,
            String fileName,
            String objectName,
            String downloadUrl,
            long fileSize,
            List<String> deletedFiles,
            String description) {
        
        try {
            // 檢查是否已存在相同平台和版本的記錄
            appVersionRepository.findByPlatformAndVersion(platform, version)
                    .ifPresent(existing -> {
                        log.warn("版本已存在，將刪除現有記錄: platform={}, version={}", platform, version);
                        appVersionRepository.delete(existing);
                    });

            // 創建新版本記錄
            AppVersion appVersion = AppVersion.builder()
                    .version(version)
                    .platform(platform)
                    .fileName(fileName)
                    .objectName(objectName)
                    .downloadUrl(downloadUrl)
                    .fileSize(fileSize)
                    .contentType("application/x-msdownload") // Windows exe 文件
                    .description(description)
                    .releaseTime(LocalDateTime.now())
                    .build();

            appVersionRepository.save(appVersion);
            log.info("已保存版本信息到數據庫: platform={}, version={}", platform, version);

            // 硬刪除已刪除的舊版本數據庫記錄
            if (deletedFiles != null && !deletedFiles.isEmpty()) {
                for (String deletedObjectName : deletedFiles) {
                    appVersionRepository.findByObjectName(deletedObjectName)
                            .ifPresent(deletedVersion -> {
                                appVersionRepository.delete(deletedVersion);
                                log.info("已硬刪除舊版本數據庫記錄: {}", deletedVersion.getVersion());
                            });
                }
            }

        } catch (Exception e) {
            log.error("保存版本信息到數據庫時發生錯誤: {}", e.getMessage(), e);
            // 不拋出異常，避免影響部署流程
        }
    }
}

