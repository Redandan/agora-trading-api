package com.agora.dto.auth;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用戶信息")
public class UserInfo {
    @Schema(description = "用戶ID")
    private Long id;

    @Schema(description = "用戶名")
    private String username;

    @Schema(description = "郵箱")
    private String email;

    @Schema(description = "郵箱是否已驗證")
    private Boolean emailVerified;

    @Schema(description = "角色")
    private String role;

    @Schema(description = "餘額")
    private BigDecimal balance;

    @Schema(description = "購物車商品數量")
    private int cartItemCount;

    @Schema(description = "質押餘額")
    private BigDecimal stackingBalance;

    @Schema(description = "總資產（餘額 + 質押餘額）")
    private BigDecimal totalAssets;

    @Schema(description = "凍結餘額")
    private BigDecimal freezeBalance = BigDecimal.ZERO;

    @Schema(description = "是否啟用")
    private boolean enabled;

    @Schema(description = "查詢時間")
    private LocalDateTime queryTime;

    @Schema(description = "店鋪名稱")
    private String storeName;

    @Schema(description = "推廣大使名稱")
    private String ambassadorName;

    @Schema(description = "顯示配送員名稱")
    private String displayDeliveryerName;

    @Schema(description = "頭像URL")
    private String avatar;

    @Schema(description = "餘額對其他法幣的換算")
    private List<BalanceConversion> balanceConversions;

    @Schema(description = "賣家入口是否維護中")
    private Boolean sellerMaintenance;

    @Schema(description = "外送員入口是否維護中")
    private Boolean deliveryMaintenance;

    @Schema(description = "未讀訊息數量")
    private int unreadMessageCount;

    @Schema(description = "默認首頁設置", enumAsRef = true)
    private com.agora.enums.system.DefaultHomePageEnum defaultHomePage;

    /**
     * 餘額換算信息
     */
    @Data
    @Schema(description = "餘額換算信息")
    public static class BalanceConversion {
        @Schema(description = "目標貨幣", example = "TWD")
        private String currency;

        @Schema(description = "貨幣符號", example = "NT$")
        private String symbol;

        @Schema(description = "貨幣名稱", example = "新台幣")
        private String currencyName;

        @Schema(description = "換算後金額", example = "3150.00")
        private BigDecimal convertedAmount;

        @Schema(description = "匯率", example = "31.50")
        private BigDecimal exchangeRate;
    }
}