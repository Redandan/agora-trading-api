package com.agora.dto.report;

import com.agora.enums.marketplace.ProductReportReasonEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "建立商品檢舉參數")
public class ProductReportCreateParam {

    @NotNull(message = "檢舉原因分類不能為空")
    @Schema(description = "檢舉原因分類", enumAsRef = true, requiredMode = Schema.RequiredMode.REQUIRED)
    private ProductReportReasonEnum reasonCategory;

    @Size(max = 1000, message = "檢舉說明長度不能超過 1000 字")
    @Schema(description = "檢舉說明(建議 ≥ 10 字)")
    private String description;
}
