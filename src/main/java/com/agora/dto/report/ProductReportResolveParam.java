package com.agora.dto.report;

import com.agora.enums.marketplace.ProductReportActionEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "admin 處理檢舉參數")
public class ProductReportResolveParam {

    @NotNull(message = "處理行動不能為空")
    @Schema(description = "採取的行動", enumAsRef = true, requiredMode = Schema.RequiredMode.REQUIRED)
    private ProductReportActionEnum actionTaken;

    @Size(max = 1000, message = "admin 備註長度不能超過 1000 字")
    @Schema(description = "admin 處理備註")
    private String adminNote;
}
