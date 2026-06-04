package com.agora.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Web Push 推送詳情")
public class WebPushDetails {

    @Schema(description = "是否觸發 Web Push", example = "true")
    private Boolean triggered;

    @Schema(description = "推送狀態", example = "SUCCESS", allowableValues = {"SUCCESS", "FAILED", "NO_SUBSCRIPTION", "USER_ONLINE"})
    private String status;

    @Schema(description = "推送標題")
    private String title;

    @Schema(description = "推送內容")
    private String body;

    @Schema(description = "推送圖標URL")
    private String icon;

    @Schema(description = "點擊跳轉URL")
    private String url;

    @Schema(description = "推送數據")
    private Map<String, Object> data;

    @Schema(description = "推送時間戳")
    private Long timestamp;

    @Schema(description = "錯誤信息（如果推送失敗）")
    private String errorMessage;

    @Schema(description = "訂閱數量")
    private Integer subscriptionCount;

    @Schema(description = "成功推送數量")
    private Integer successCount;

    @Schema(description = "失敗推送數量")
    private Integer failureCount;
}
