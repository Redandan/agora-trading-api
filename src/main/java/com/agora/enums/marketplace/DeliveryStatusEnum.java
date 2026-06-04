package com.agora.enums.marketplace;

/**
 * 配送狀態
 */
public enum DeliveryStatusEnum {
    /**
     * 待分配
     */
    PENDING,

    /**
     * 取貨中
     */
    PICKING_UP,

    /**
     * 運送中
     */
    DELIVERING,

    /**
     * 已送達
     */
    DELIVERED,

    /**
     * 已取消
     */
    CANCELLED
} 