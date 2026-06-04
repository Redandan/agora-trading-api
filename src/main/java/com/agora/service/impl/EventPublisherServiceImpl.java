package com.agora.service.impl;

import com.agora.event.ReturnRejectedEvent;
import com.agora.event.OrderStatusChangedEvent;
import com.agora.service.EventPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 事件發布服務實現
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisherServiceImpl implements EventPublisherService {
    
    private final ApplicationEventPublisher eventPublisher;
    
    @Override
    public void publishReturnRejectedEvent(ReturnRejectedEvent event) {
        log.info("發布退貨申請被拒絕事件: orderId={}, buyerId={}, sellerId={}", 
                event.getOrderId(), event.getBuyerId(), event.getSellerId());
        eventPublisher.publishEvent(event);
    }
    
    @Override
    public void publishOrderStatusChangedEvent(OrderStatusChangedEvent event) {
        log.info("發布訂單狀態變更事件: orderId={}, {} -> {}", 
                event.getOrderId(), event.getOldStatus(), event.getNewStatus());
        eventPublisher.publishEvent(event);
    }
}
