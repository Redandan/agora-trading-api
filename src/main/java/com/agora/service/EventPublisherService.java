package com.agora.service;

import com.agora.event.ReturnRejectedEvent;
import com.agora.event.OrderStatusChangedEvent;

/**
 * 事件發布服務
 */
public interface EventPublisherService {
    
    /**
     * 發布退貨申請被拒絕事件
     */
    void publishReturnRejectedEvent(ReturnRejectedEvent event);
    
    /**
     * 發布訂單狀態變更事件
     */
    void publishOrderStatusChangedEvent(OrderStatusChangedEvent event);
}
