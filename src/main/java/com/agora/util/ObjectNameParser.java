package com.agora.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * ObjectName 解析工具
 * 解析格式：{BUSINESS_TYPE}_{BUSINESS_ID}_{TIMESTAMP}.{EXTENSION}
 * 例如：USER_2_1758556033394.png
 */
@Slf4j
public class ObjectNameParser {
    
    @Data
    public static class ParsedObjectName {
        private String businessType;
        private Long businessId;
        private String timestamp;
        private String extension;
        private boolean valid;
        
        public ParsedObjectName(String businessType, Long businessId, String timestamp, String extension) {
            this.businessType = businessType;
            this.businessId = businessId;
            this.timestamp = timestamp;
            this.extension = extension;
            this.valid = businessType != null && businessId != null;
        }
        
        public ParsedObjectName() {
            this.valid = false;
        }
    }
    
    /**
     * 解析 objectName
     * @param objectName 例如：USER_2_1758556033394.png
     * @return 解析结果
     */
    public static ParsedObjectName parse(String objectName) {
        if (objectName == null || objectName.trim().isEmpty()) {
            log.warn("ObjectName为空，无法解析");
            return new ParsedObjectName();
        }
        
        try {
            // 分离文件名和扩展名
            int lastDotIndex = objectName.lastIndexOf('.');
            String nameWithoutExt;
            String extension = "";
            
            if (lastDotIndex > 0 && lastDotIndex < objectName.length() - 1) {
                nameWithoutExt = objectName.substring(0, lastDotIndex);
                extension = objectName.substring(lastDotIndex + 1);
            } else {
                nameWithoutExt = objectName;
            }
            
            // 按 _ 分割
            String[] parts = nameWithoutExt.split("_");
            
            if (parts.length < 3) {
                log.warn("ObjectName格式不正确，至少需要3部分: {}", objectName);
                return new ParsedObjectName();
            }
            
            String businessType = parts[0];
            String businessIdStr = parts[1];
            String timestamp = parts[2];
            
            // 解析业务ID
            Long businessId;
            try {
                businessId = Long.parseLong(businessIdStr);
            } catch (NumberFormatException e) {
                log.warn("无法解析业务ID: {} from objectName: {}", businessIdStr, objectName);
                return new ParsedObjectName();
            }
            
            return new ParsedObjectName(businessType.toUpperCase(), businessId, timestamp, extension);
            
        } catch (Exception e) {
            log.error("解析ObjectName失败: {}", objectName, e);
            return new ParsedObjectName();
        }
    }
    
    /**
     * 从URL中提取objectName
     * URL格式：https://objectstorage.{region}.oraclecloud.com/n/{namespace}/b/{bucket}/o/{objectName}
     * @param url 完整URL
     * @return objectName，如果无法提取则返回null
     */
    public static String extractObjectNameFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        
        try {
            // 查找 /o/ 后面的部分
            int oIndex = url.indexOf("/o/");
            if (oIndex == -1) {
                log.warn("URL格式不正确，找不到 /o/ 部分: {}", url);
                return null;
            }
            
            String objectName = url.substring(oIndex + 3);
            
            // 移除可能的查询参数
            int queryIndex = objectName.indexOf('?');
            if (queryIndex != -1) {
                objectName = objectName.substring(0, queryIndex);
            }
            
            // URL解码
            objectName = java.net.URLDecoder.decode(objectName, "UTF-8");
            
            return objectName;
            
        } catch (Exception e) {
            log.error("从URL提取objectName失败: {}", url, e);
            return null;
        }
    }
    
    /**
     * 检查URL是否包含指定的objectName
     * @param url 完整URL
     * @param objectName 要检查的objectName
     * @return 如果URL包含该objectName则返回true
     */
    public static boolean urlContainsObjectName(String url, String objectName) {
        if (url == null || objectName == null) {
            return false;
        }
        
        // 方法1：直接检查URL是否包含objectName
        if (url.contains(objectName)) {
            return true;
        }
        
        // 方法2：从URL提取objectName进行比较
        String extractedObjectName = extractObjectNameFromUrl(url);
        if (extractedObjectName != null && extractedObjectName.equals(objectName)) {
            return true;
        }
        
        return false;
    }
}

