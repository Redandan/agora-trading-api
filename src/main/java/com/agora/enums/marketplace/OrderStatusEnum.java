package com.agora.enums.marketplace;

import java.util.Arrays;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "訂單狀態")
public enum OrderStatusEnum {
    // ===============================
    // 訂單基礎狀態 (100~199)
    // ===============================
    @Schema(description = "待賣家出貨")
    PENDING_SHIPMENT(100, "待賣家出貨"),

    @Schema(description = "賣家已出貨")
    SHIPPED(150, "賣家已出貨"),
    // ===============================
    // 配送狀態 (200~299)
    // ===============================
    @Schema(description = "配送派單中")
    DELIVERY_ASSIGNING(200, "配送派單中"),

    @Schema(description = "已指派配送員/物流")
    DELIVERY_ASSIGNED(210, "已指派配送員/物流"),

    @Schema(description = "前往取貨中")
    DELIVERY_EN_ROUTE_TO_PICKUP(220, "前往取貨中"),

    @Schema(description = "取貨延遲")
    DELIVERY_PICKUP_DELAYED(230, "取貨延遲"),

    @Schema(description = "已取貨")
    DELIVERY_PICKED_UP(240, "已取貨"),

    @Schema(description = "配送中")
    DELIVERY_EN_ROUTE_TO_BUYER(250, "配送中"),

    @Schema(description = "配送延遲")
    DELIVERY_DELIVERY_DELAYED(260, "配送延遲"),

    @Schema(description = "配送失敗")
    DELIVERY_FAILED(270, "配送失敗"),

    @Schema(description = "退回賣家中")
    DELIVERY_RETURNING(280, "退回賣家中"),

    @Schema(description = "配送完成")
    DELIVERY_COMPLETED(290, "配送完成"),
    // ===============================
    // 數位代購流程 (300~399)
    // ===============================
    @Schema(description = "代購進行中")
    PURCHASE_IN_PROGRESS(300, "代購進行中"),

    @Schema(description = "賣家已提交交付證明")
    PROOF_SUBMITTED(310, "賣家已提交交付證明"),

    @Schema(description = "買家確認完成")
    BUYER_CONFIRMED(320, "買家確認完成"),
    // ===============================
    // 退貨狀態 (700~799)
    // ===============================
    @Schema(description = "買家申請退貨")
    RETURN_REQUESTED(700, "買家申請退貨"),

    @Schema(description = "賣家拒絕退貨")
    RETURN_REJECTED(710, "賣家拒絕退貨"),

    @Schema(description = "賣家同意退貨")
    RETURN_APPROVED(720, "賣家同意退貨"),

    @Schema(description = "買家已寄出退貨")
    RETURN_SHIPPED_BY_BUYER(725, "買家已寄出退貨"),

    @Schema(description = "退貨寄送延遲")
    RETURN_SHIPPING_DELAYED(730, "退貨寄送延遲"),

    @Schema(description = "賣家已收到退貨")
    RETURN_RECEIVED(740, "賣家已收到退貨"),
    // ===============================
    // 退款不退貨方案 (750~759)
    // ===============================
    @Schema(description = "賣家提供退款不退貨方案")
    REFUND_NO_RETURN_OFFERED(751, "賣家提供退款不退貨方案"),

    @Schema(description = "賣家提供部分退款不退貨方案")
    REFUND_NO_RETURN_PARTIAL_OFFERED(752, "賣家提供部分退款不退貨方案"),

    // ===============================
    // 爭議狀態 (800~899)
    // ===============================
    @Schema(description = "爭議已開啟")
    DISPUTE_OPENED(800, "爭議已開啟"),
    @Schema(description = "賣家已回應")
    DISPUTE_RESPONDED(810, "賣家已回應"),

    // ===============================
    // 最終狀態 (900~999)
    // ===============================
    @Schema(description = "買家取消訂單")
    CANCELLED_BY_BUYER(910, "買家取消訂單"),
    @Schema(description = "賣家取消訂單")
    CANCELLED_BY_SELLER(920, "賣家取消訂單"),
    @Schema(description = "平台取消訂單")
    CANCELLED_BY_PLATFORM(930, "平台取消訂單"),
    @Schema(description = "已退款")
    REFUNDED(940, "已退款"),
    //爭議結案內容保存在爭議表內，不需要額外狀態
    @Schema(description = "爭議已結案")
    DISPUTE_RESOLVED(950, "爭議已結案"),
    @Schema(description = "訂單已完成封存")
    COMPLETED_FINAL(999, "訂單已完成封存");

    private final int id;
    private final String description;

    OrderStatusEnum(int id, String description) {
        this.id = id;
        this.description = description;
    }

    public int getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public boolean isFinalStatus() {
        return id >= 900;
    }

    public boolean isDisputeStatus() {
        return getDisputeStatus().contains(this);
    }

    public static List<OrderStatusEnum> getDisputeStatus() {
        return Arrays.asList(
                DISPUTE_OPENED,
                DISPUTE_RESPONDED,
                DISPUTE_RESOLVED
        );
    }

    /**OrderStatusEnum
     (三方物流配送)
     100-> 150-> 290 -> 940 (9開頭為最終狀態)

     (三方物流配送後退貨)
     100-> 150-> 290 -> 700 -> 720 -> 725 -> 740 -> 940 (9開頭為最終狀態)

     (三方物流配送後只退款)
     100 -> 150 -> 290 -> 700 -> 751 -> 940 (9開頭為最終狀態)

     (三方物流配送後爭議結案)
     100 -> 150 -> 290 -> 700 -> 710 -> 800 -> 950 (9開頭為最終狀態)

     (數位代購正常流程)
     300 -> 310 -> 320 -> 999 (9開頭為最終狀態)

     (數位代購買家未確認爭議)
     300 -> 310 -> 800 -> 950 (9開頭為最終狀態)
     **/
}
