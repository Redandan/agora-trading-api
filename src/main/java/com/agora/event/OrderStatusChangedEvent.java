package com.agora.event;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 訂單狀態變更事件
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderStatusChangedEvent {
    private String orderId;
    private String oldStatus;
    private String newStatus;
    private Long buyerId;
    private Long sellerId;
}
