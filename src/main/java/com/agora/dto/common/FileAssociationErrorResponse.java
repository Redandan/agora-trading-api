package com.agora.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件關聯錯誤響應")
public class FileAssociationErrorResponse {

    @Schema(description = "錯誤代碼", example = "FILE_ASSOCIATION_ERROR")
    private String errorCode;

    @Schema(description = "錯誤消息", example = "文件關聯驗證失敗")
    private String message;

    @Schema(description = "商品ID", example = "5")
    private String productId;

    @Schema(description = "錯誤的文件ID列表")
    private List<FileErrorDetail> errorFiles;

    @Schema(description = "建議的解決方案")
    private String suggestion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "文件錯誤詳情")
    public static class FileErrorDetail {

        @Schema(description = "文件名", example = "product_image.jpg")
        private String fileName;

        @Schema(description = "期望的業務類型", example = "PRODUCT")
        private String expectedBusinessType;

        @Schema(description = "實際的業務類型", example = "TEMP")
        private String actualBusinessType;

        @Schema(description = "期望的業務ID", example = "5")
        private String expectedBusinessId;

        @Schema(description = "實際的業務ID", example = "null")
        private String actualBusinessId;

        @Schema(description = "錯誤類型", example = "OWNERSHIP_MISMATCH")
        private String errorType;

        @Schema(description = "詳細錯誤信息", example = "文件不屬於該商品")
        private String detailMessage;
    }
}
