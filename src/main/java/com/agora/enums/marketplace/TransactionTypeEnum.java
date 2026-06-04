package com.agora.enums.marketplace;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "交易類型")
public enum TransactionTypeEnum {
    @Schema(description = "存款")
    DEPOSIT("存款"),

    @Schema(description = "提款")
    WITHDRAW("提款"),

    @Schema(description = "支付")
    PAYMENT("支付"),

    @Schema(description = "收款")
    RECEIVE("收款"),

    @Schema(description = "退款")
    REFUND("退款"),

    @Schema(description = "質押")
    STAKING("質押"),

    @Schema(description = "解質押")
    UNSTAKING("解質押"),
    
    @Schema(description = "利息")
    INTEREST("利息"),
    
    @Schema(description = "創建市場")
    MARKET_CREATION("創建市場"),
    
    @Schema(description = "投注扣款")
    BET("投注扣款"),
    
    @Schema(description = "投注獲勝")
    BET_WIN("投注獲勝"),
    
    @Schema(description = "投注退款")
    BET_REFUND("投注退款"),

    @Schema(description = "Slot 下注扣款")
    SLOT_BET("Slot 下注扣款"),

    @Schema(description = "Slot 中獎獎金")
    SLOT_WIN("Slot 中獎獎金"),

    @Schema(description = "管理員手動調帳")
    MANUAL_ADJUST("管理員手動調帳");

    private final String description;

    TransactionTypeEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
} 