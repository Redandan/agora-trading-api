package com.agora.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Mini App / TG 來源歸因追蹤參數")
public class TrackReferrerRequest {

    @NotNull(message = "referrerGroupId 不能為空")
    @Schema(description = "Telegram group id 或 Mini App ref 解析出的 group id", example = "-1001234567")
    private Long referrerGroupId;
}
