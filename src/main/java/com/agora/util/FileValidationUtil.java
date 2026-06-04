package com.agora.util;

import com.agora.exception.FileManagementException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class FileValidationUtil {

    // 支持的文件类型
    private static final Set<String> ALLOWED_IMAGE_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    ));

    private static final Set<String> ALLOWED_DOCUMENT_TYPES = new HashSet<>(Arrays.asList(
            "application/pdf", "application/msword", 
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain"
    ));

    private static final Set<String> ALLOWED_ARCHIVE_TYPES = new HashSet<>(Arrays.asList(
            "application/zip", "application/x-rar-compressed", "application/x-7z-compressed"
    ));

    // 支持的文件扩展名（用于备选验证）
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    ));

    private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "pdf", "doc", "docx", "xls", "xlsx", "txt"
    ));

    private static final Set<String> ALLOWED_ARCHIVE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z"
    ));

    // 最大文件大小 (10MB) - 用於普通文件上傳
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 验证文件
     */
    public static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileManagementException("文件不能为空");
        }

        // 检查文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new FileManagementException("文件大小不能超过10MB");
        }

        // 检查文件类型
        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new FileManagementException("文件名不能为空");
        }

        // 如果contentType为null或application/octet-stream，尝试基于文件扩展名验证
        if (contentType == null || "application/octet-stream".equals(contentType)) {
            if (!isAllowedFileByExtension(originalFilename)) {
                throw new FileManagementException("不支持的文件类型: " + originalFilename + " (无法识别的文件格式)");
            }
            log.debug("基于文件扩展名验证通过: fileName={}, extension={}", 
                    originalFilename, getFileExtension(originalFilename));
        } else {
            if (!isAllowedFileType(contentType)) {
                throw new FileManagementException("不支持的文件类型: " + contentType);
            }
            log.debug("基于MIME类型验证通过: fileName={}, contentType={}", 
                    originalFilename, contentType);
        }

        log.debug("文件验证通过: fileName={}, contentType={}, size={}", 
                originalFilename, contentType, file.getSize());
    }

    /**
     * 检查是否为允许的文件类型
     */
    private static boolean isAllowedFileType(String contentType) {
        return ALLOWED_IMAGE_TYPES.contains(contentType) ||
               ALLOWED_DOCUMENT_TYPES.contains(contentType) ||
               ALLOWED_ARCHIVE_TYPES.contains(contentType);
    }

    /**
     * 基于文件扩展名检查是否为允许的文件类型
     */
    private static boolean isAllowedFileByExtension(String filename) {
        String extension = getFileExtension(filename);
        return ALLOWED_IMAGE_EXTENSIONS.contains(extension) ||
               ALLOWED_DOCUMENT_EXTENSIONS.contains(extension) ||
               ALLOWED_ARCHIVE_EXTENSIONS.contains(extension);
    }

    /**
     * 检查是否为图片文件
     */
    public static boolean isImageFile(String contentType) {
        return ALLOWED_IMAGE_TYPES.contains(contentType);
    }

    /**
     * 检查是否为文档文件
     */
    public static boolean isDocumentFile(String contentType) {
        return ALLOWED_DOCUMENT_TYPES.contains(contentType);
    }

    /**
     * 检查是否为压缩文件
     */
    public static boolean isArchiveFile(String contentType) {
        return ALLOWED_ARCHIVE_TYPES.contains(contentType);
    }

    /**
     * Resolves a stable MIME type for object-storage metadata.
     *
     * <p>Browsers and some upstream upload clients send images as
     * application/octet-stream. Validation already accepts those by extension;
     * this method keeps the stored object metadata browser-friendly too.</p>
     */
    public static String resolveContentType(String suppliedContentType, String filename) {
        if (suppliedContentType != null
                && !suppliedContentType.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(suppliedContentType.trim())) {
            return suppliedContentType.trim();
        }
        return switch (getFileExtension(filename)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt" -> "text/plain";
            case "zip" -> "application/zip";
            case "rar" -> "application/x-rar-compressed";
            case "7z" -> "application/x-7z-compressed";
            default -> suppliedContentType == null || suppliedContentType.isBlank()
                    ? "application/octet-stream"
                    : suppliedContentType.trim();
        };
    }

    /**
     * 获取文件扩展名（不包含点号）
     */
    public static String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
    
    /**
     * 获取文件扩展名（包含点号）
     */
    public static String getFileExtensionWithDot(String filename) {
        if (filename == null || filename.lastIndexOf(".") == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }
}
