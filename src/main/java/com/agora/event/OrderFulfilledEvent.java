package com.agora.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when admin completes fulfillment of a digital order via
 * {@code OrderFulfillmentLogService.recordFulfillment(...)} (with codePayload).
 *
 * <p>Listener side: {@code OrderFulfilledNotificationListener} sends in-app
 * notification + SSE push. Future extension: TG bot DM (needs user→tgChatId
 * mapping) and email (needs EmailService extension).
 *
 * <p>Use {@code @TransactionalEventListener(phase = AFTER_COMMIT)} on listener
 * to avoid notifying buyer before DB commit succeeds.
 */
@Getter
public class OrderFulfilledEvent extends ApplicationEvent {

    private final String orderId;
    private final Long buyerId;
    private final Long sellerId;
    private final Long fulfillmentLogId;
    private final Long deliveryProofId;

    public OrderFulfilledEvent(Object source, String orderId, Long buyerId,
                                Long sellerId, Long fulfillmentLogId, Long deliveryProofId) {
        super(source);
        this.orderId = orderId;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.fulfillmentLogId = fulfillmentLogId;
        this.deliveryProofId = deliveryProofId;
    }
}
