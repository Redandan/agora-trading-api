package com.agora.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 應用程式版本 DTO
 * 用於返回版本信息給前端
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "應用程式版本信息")
public class AppVersionDTO {

    @Schema(description = "版本ID")
    private Long id;

    @Schema(description = "版本號", example = "1.0.0")
    private String version;

    @Schema(description = "平台", example = "windows")
    private String platform;

    @Schema(description = "檔案名稱", example = "AgoraMarket-Windows-v1.0.0.exe")
    private String fileName;

    @Schema(description = "檔案下載 URL")
    private String downloadUrl;

    @Schema(description = "檔案大小（位元組）")
    private Long fileSize;

    @Schema(description = "檔案大小（人類可讀格式）", example = "50.0 MB")
    private String fileSizeFormatted;

    @Schema(description = "檔案類型", example = "application/x-msdownload")
    private String contentType;

    @Schema(description = "版本描述/更新日誌")
    private String description;

    @Schema(description = "發布時間")
    private LocalDateTime releaseTime;

    @Schema(description = "創建時間")
    private LocalDateTime createdAt;
}

