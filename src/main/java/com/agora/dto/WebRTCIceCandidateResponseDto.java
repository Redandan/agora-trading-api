package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WebRTC ICE Candidate 響應")
public class WebRTCIceCandidateResponseDto extends WebRTCResponseDto {
    
    @Schema(description = "通話唯一識別碼", example = "call_1758976586035_2")
    private String callId;
    
    @Schema(description = "目標用戶ID", example = "3")
    private Long toUserId;
}