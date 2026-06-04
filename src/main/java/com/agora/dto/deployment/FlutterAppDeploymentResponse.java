package com.agora.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Flutter Windows App 部署響應 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Flutter Windows App 部署響應")
public class FlutterAppDeploymentResponse {

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "訊息")
    private String message;

    @Schema(description = "上傳的檔案名稱")
    private String uploadedFileName;

    @Schema(description = "檔案 URL")
    private String fileUrl;

    @Schema(description = "檔案大小（位元組）")
    private Long fileSize;

    @Schema(description = "版本號")
    private String version;

    @Schema(description = "已刪除的舊檔案列表")
    private List<String> deletedFiles;

    @Schema(description = "部署時間")
    private LocalDateTime deploymentTime;
}

