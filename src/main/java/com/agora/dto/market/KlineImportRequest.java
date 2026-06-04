package com.agora.dto.market;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class KlineImportRequest {

    @NotBlank
    private String symbol;

    @NotBlank
    private String intervalCode;

    /** SPOT / FUTURES（預設 SPOT） */
    private String marketType = "SPOT";

    /**
     * 寫入的 K 線資料源 tag（"binance" / "okx"）。預設 binance（向後相容）。
     * 雙寫上線後,若需修補單一源缺口（例如 binance 缺 1 根但 okx 有）,設為對應 source 就能避開
     * 另一源的 existence check 誤判。
     */
    private String source = "binance";

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;
}
