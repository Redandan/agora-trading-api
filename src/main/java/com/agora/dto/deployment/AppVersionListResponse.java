package com.agora.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 應用程式版本列表響應 DTO
 * 按平台分組返回版本列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "應用程式版本列表響應")
public class AppVersionListResponse {

    @Schema(description = "按平台分組的版本列表", example = "{\"windows\": [...], \"android\": [...]}")
    private Map<String, List<AppVersionDTO>> versionsByPlatform;

    @Schema(description = "所有平台列表", example = "[\"windows\", \"android\", \"ios\"]")
    private List<String> platforms;

    @Schema(description = "總版本數")
    private Integer totalVersions;
}

