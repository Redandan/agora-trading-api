package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "WebRTC 通話狀態事件資料傳輸物件")
public class WebRTCCallStatusEventDto {

    @NotBlank(message = "通話ID不能為空")
    @Schema(description = "通話ID", example = "call_12345")
    private String callId;

    @NotNull(message = "用戶ID不能為空")
    @Schema(description = "操作用戶ID", example = "1")
    private Long userId;

    @NotBlank(message = "狀態不能為空")
    @Schema(description = "通話狀態", example = "accepted", allowableValues = {"initiated", "ringing", "accepted", "rejected", "connected", "ended", "failed"})
    private String status;

    @Schema(description = "狀態更新時間")
    private LocalDateTime timestamp;
}
