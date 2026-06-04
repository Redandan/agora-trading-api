package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 應用程式版本實體
 * 用於記錄和管理不同平台的應用程式版本信息
 */
@Entity
@Table(name = "app_versions", indexes = {
    @Index(name = "idx_platform_version", columnList = "platform,version"),
    @Index(name = "idx_platform", columnList = "platform"),
    @Index(name = "idx_version", columnList = "version")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "應用程式版本")
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "版本ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Column(nullable = false, length = 50)
    @Schema(description = "版本號（例如：1.0.0, 1.2.3）", example = "1.0.0", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50)
    private String version;

    @Column(nullable = false, length = 50)
    @Schema(description = "平台（例如：windows, android, ios, macos, linux）", example = "windows", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50)
    private String platform;

    @Column(name = "file_name", nullable = false, length = 255)
    @Schema(description = "檔案名稱", example = "AgoraMarket-Windows-v1.0.0.exe", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
    private String fileName;

    @Column(name = "object_name", nullable = false, unique = true, length = 500)
    @Schema(description = "OCI Object Storage 中的物件名稱", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500)
    private String objectName;

    @Column(name = "download_url", nullable = false, length = 1000)
    @Schema(description = "檔案下載 URL", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1000)
    private String downloadUrl;

    @Column(name = "file_size", nullable = false)
    @Schema(description = "檔案大小（位元組）", example = "10485760", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fileSize;

    @Column(name = "content_type", length = 100)
    @Schema(description = "檔案類型（MIME type）", example = "application/x-msdownload", nullable = true, maxLength = 100)
    private String contentType;

    @Column(name = "description", columnDefinition = "TEXT")
    @Schema(description = "版本描述/更新日誌", nullable = true)
    private String description;

    @Column(name = "release_time")
    @Schema(description = "發布時間", example = "2024-01-01T10:00:00", nullable = true)
    private LocalDateTime releaseTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "創建時間", example = "2024-01-01T10:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;
}

