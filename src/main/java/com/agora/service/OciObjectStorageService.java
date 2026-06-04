package com.agora.service;

import com.agora.config.OciConfig;
import com.agora.util.FileValidationUtil;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.requests.*;
import com.oracle.bmc.objectstorage.responses.*;
import com.oracle.bmc.model.BmcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OciObjectStorageService {

    private final OciConfig ociConfig;
    private volatile ObjectStorageClient objectStorageClient;

    /**
     * 上傳檔案到 OCI Object Storage
     * 改善資源管理，確保流資源正確關閉
     */
    public String uploadFile(MultipartFile file, String objectName) throws IOException {
        InputStream inputStream = null;
        try {
            // 如果沒有指定物件名稱，使用原始檔案名
            if (objectName == null || objectName.trim().isEmpty()) {
                objectName = file.getOriginalFilename();
            }

            // 準備檔案內容
            byte[] fileContent = file.getBytes();
            inputStream = new ByteArrayInputStream(fileContent);
            String contentType = FileValidationUtil.resolveContentType(file.getContentType(), objectName);

            // 創建上傳請求
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .objectName(objectName)
                    .contentLength((long) fileContent.length)
                    .contentType(contentType)
                    .putObjectBody(inputStream)
                    .build();

            // 執行上傳
            PutObjectResponse response = objectStorageClient().putObject(putObjectRequest);
            
            log.info("File uploaded successfully: {} -> ETag: {}", objectName, response.getETag());
            
            // 返回檔案的 URL
            return String.format("https://objectstorage.us-phoenix-1.oraclecloud.com/n/%s/b/%s/o/%s", 
                    ociConfig.getNamespace(), ociConfig.getBucket(), objectName);
                    
        } catch (BmcException e) {
            log.error("Failed to upload file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        } finally {
            // 確保流資源被正確關閉
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.warn("Failed to close input stream: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 列出 bucket 中的所有物件
     */
    public List<String> listObjects() {
        try {
            ListObjectsRequest listObjectsRequest = ListObjectsRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .build();

            ListObjectsResponse response = objectStorageClient().listObjects(listObjectsRequest);
            
            return response.getListObjects().getObjects().stream()
                    .map(ObjectSummary::getName)
                    .collect(Collectors.toList());
                    
        } catch (BmcException e) {
            log.error("Failed to list objects: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to list objects: " + e.getMessage(), e);
        }
    }

    /**
     * 刪除檔案
     */
    public void deleteFile(String objectName) {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .objectName(objectName)
                    .build();

            objectStorageClient().deleteObject(deleteObjectRequest);
            
            log.info("File deleted successfully: {}", objectName);
            
        } catch (BmcException e) {
            log.error("Failed to delete file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
        }
    }

    /**
     * 生成公開訪問 URL（bucket已設為公開）
     * @param objectName 物件名稱
     * @return 公開 URL
     */
    public String generatePublicUrl(String objectName) {
        String publicUrl = String.format("https://objectstorage.%s.oraclecloud.com/n/%s/b/%s/o/%s",
                ociConfig.getRegion(),
                ociConfig.getNamespace(),
                ociConfig.getBucket(),
                objectName);
        
        log.info("Generated public URL for {}: {}", objectName, publicUrl);
        return publicUrl;
    }

    /**
     * 獲取檔案大小
     * @param objectName 物件名稱
     * @return 檔案大小（位元組）
     */
    public long getFileSize(String objectName) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .objectName(objectName)
                    .build();

            HeadObjectResponse response = objectStorageClient().headObject(headObjectRequest);
            long contentLength = response.getContentLength();
            
            log.info("File size for {}: {} bytes", objectName, contentLength);
            
            return contentLength;
            
        } catch (BmcException e) {
            if (e.getStatusCode() == 404) {
                log.warn("File not found: {}", objectName);
                return -1; // 返回 -1 表示檔案不存在
            }
            log.error("Failed to get file size for {}: {}", objectName, e.getMessage(), e);
            throw new RuntimeException("Failed to get file size: " + e.getMessage(), e);
        }
    }
    
    /**
     * 獲取檔案內容（流）
     * 注意：調用者必須確保關閉返回的流
     * @param objectName 物件名稱
     * @return 檔案內容流
     */
    public InputStream getObjectContent(String objectName) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .objectName(objectName)
                    .build();

            GetObjectResponse response = objectStorageClient().getObject(getObjectRequest);
            
            log.info("Retrieved object content for: {}", objectName);
            
            // 注意：調用者必須確保關閉這個流
            return response.getInputStream();
            
        } catch (BmcException e) {
            if (e.getStatusCode() == 404) {
                log.warn("File not found: {}", objectName);
                return null;
            }
            log.error("Failed to get object content for {}: {}", objectName, e.getMessage(), e);
            throw new RuntimeException("Failed to get object content: " + e.getMessage(), e);
        }
    }
    
    /**
     * 安全地獲取檔案內容並處理流資源
     * @param objectName 物件名稱
     * @param processor 處理流的函數
     * @return 處理結果
     */
    public <T> T processObjectContent(String objectName, java.util.function.Function<InputStream, T> processor) {
        InputStream inputStream = null;
        try {
            inputStream = getObjectContent(objectName);
            if (inputStream == null) {
                return null;
            }
            return processor.apply(inputStream);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.warn("Failed to close input stream for {}: {}", objectName, e.getMessage());
                }
            }
        }
    }

    /**
     * 從 InputStream 上傳檔案到 OCI Object Storage
     * @param inputStream 檔案輸入流
     * @param objectName 物件名稱
     * @param contentLength 檔案大小（位元組），如果為 -1 則不設置（讓 OCI SDK 自動處理）
     * @return 檔案的公開 URL
     */
    public String uploadFileFromStream(InputStream inputStream, String objectName, long contentLength) throws IOException {
        try {
            // 創建上傳請求
            PutObjectRequest.Builder builder = PutObjectRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket())
                    .objectName(objectName)
                    .contentType(FileValidationUtil.resolveContentType(null, objectName))
                    .putObjectBody(inputStream);
            
            // 只有在 contentLength > 0 時才設置
            if (contentLength > 0) {
                builder.contentLength(contentLength);
            }

            PutObjectRequest putObjectRequest = builder.build();

            // 執行上傳
            PutObjectResponse response = objectStorageClient().putObject(putObjectRequest);
            
            log.info("File uploaded successfully from stream: {} -> ETag: {}", objectName, response.getETag());
            
            // 返回檔案的 URL
            return generatePublicUrl(objectName);
                    
        } catch (BmcException e) {
            log.error("Failed to upload file from stream: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload file from stream: " + e.getMessage(), e);
        }
    }

    /**
     * 列出指定前綴的物件
     * @param prefix 物件名稱前綴
     * @return 物件名稱列表
     */
    public List<String> listObjectsByPrefix(String prefix) {
        try {
            ListObjectsRequest.Builder builder = ListObjectsRequest.builder()
                    .namespaceName(ociConfig.getNamespace())
                    .bucketName(ociConfig.getBucket());
            
            if (prefix != null && !prefix.trim().isEmpty()) {
                builder.prefix(prefix);
            }

            ListObjectsResponse response = objectStorageClient().listObjects(builder.build());
            
            return response.getListObjects().getObjects().stream()
                    .map(ObjectSummary::getName)
                    .collect(Collectors.toList());
                    
        } catch (BmcException e) {
            log.error("Failed to list objects by prefix: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to list objects by prefix: " + e.getMessage(), e);
        }
    }

    private ObjectStorageClient objectStorageClient() {
        ObjectStorageClient client = objectStorageClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (objectStorageClient == null) {
                objectStorageClient = ociConfig.createObjectStorageClient();
            }
            return objectStorageClient;
        }
    }

}
