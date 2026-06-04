package com.agora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "購物車統計報告")
public class CartSummaryDTO {
    @Schema(description = "購物車商品總數")
    private long totalItems;

    @Schema(description = "購物車商品總價值")
    private BigDecimal totalValue;

    @Schema(description = "平均每個商品的價值")
    private BigDecimal averageCartValue;

    @Schema(description = "熱門商品列表（前5名）")
    private List<TopProductDTO> topProducts;

    @Schema(description = "庫存警告列表（庫存小於10的商品）")
    private List<LowStockWarningDTO> lowStockWarnings;

    @Data
    @Schema(description = "熱門商品信息")
    public static class TopProductDTO {
        @Schema(description = "商品ID")
        private Long productId;

        @Schema(description = "購物車中的數量")
        private Long quantity;
    }

    @Data
    @Schema(description = "庫存警告信息")
    public static class LowStockWarningDTO {
        @Schema(description = "商品ID")
        private Long productId;

        @Schema(description = "當前庫存")
        private Integer currentStock;
    }
} 