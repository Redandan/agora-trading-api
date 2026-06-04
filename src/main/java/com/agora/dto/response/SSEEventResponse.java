package com.agora.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 統一的SSE事件回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "SSE事件回應")
public class SSEEventResponse {

    @Schema(description = "回應消息", example = "事件已發送")
    private String message;

    @Schema(description = "動態數據", example = "{\"userId\":1,\"amount\":\"100\",\"currency\":\"USDT\"}")
    private Map<String, Object> data;
}
