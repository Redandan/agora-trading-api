package com.agora.dto.trading;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 自動交易開倉狀態 + OCO 保護資訊。
 *
 * <p>取代 {@code AdminOcoController.listPositions} 原本回傳的 {@code List<Map<String, Object>>},
 * 讓 SDK generator 能產出 typed 客戶端程式碼(Map 版會觸發 {@code Map.listFromJson} 不存在 bug)。
 *
 * <p>JSON wire format 完全與舊版 Map 相同 — 所有欄位名稱、型別、nullable 行為保留;
 * 特別地 {@code isProtected} 透過 {@link JsonProperty} 序列化為 {@code "protected"}。
 */
@Data
@Builder
@Schema(description = "自動交易開倉狀態 + OCO 保護資訊")
public class OpenPositionDto {

    @Schema(description = "倉位 ID (bt_live_signal.id)")
    private Long id;

    @Schema(description = "交易對", example = "BTCUSDT")
    private String symbol;

    @Schema(description = "成交數量")
    private BigDecimal tradedQty;

    @Schema(description = "實際進場均價")
    private BigDecimal actualEntryPrice;

    @Schema(description = "建議止盈價")
    private BigDecimal suggestedTp;

    @Schema(description = "建議止損價")
    private BigDecimal suggestedSl;

    @Schema(description = "OCO 訂單組 ID(交易所端)", nullable = true)
    private Long ocoOrderListId;

    /** JSON key 保持為 {@code protected}(wire 相容);Lombok getter 會是 {@code isProtected()}。 */
    @JsonProperty("protected")
    @Schema(description = "是否已有 OCO 保護")
    private boolean isProtected;

    @Schema(description = "倉位建立時間(格式: yyyy-MM-dd HH:mm:ss)")
    private String createdAt;
}
