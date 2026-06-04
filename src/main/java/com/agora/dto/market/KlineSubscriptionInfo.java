package com.agora.dto.market;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KlineSubscriptionInfo {
    private String symbol;
    private String intervalCode;
    private String marketType;
    /** CONNECTING / RUNNING / RECONNECTING / STOPPED / CLOSED */
    private String status;
    private LocalDateTime connectedAt;
    private long receivedCount;
    /** WS provider 識別名稱，例如 "binance" / "okx" */
    private String source;
}
