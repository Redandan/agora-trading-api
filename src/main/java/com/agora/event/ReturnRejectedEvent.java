package com.agora.event;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 退貨申請被拒絕事件
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReturnRejectedEvent {
    private String orderId;
    private Long buyerId;
    private Long sellerId;
    private String rejectionReason;
    private String description;
}
