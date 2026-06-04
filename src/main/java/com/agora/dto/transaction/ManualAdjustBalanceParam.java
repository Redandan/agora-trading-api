package com.agora.dto.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Data
@Schema(description = "管理員手動調帳請求")
public class ManualAdjustBalanceParam {

    @Schema(description = "會員 ID", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "userId 不得為空")
    private Long userId;

    @Schema(description = "調帳金額（正數）", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "amount 不得為空")
    @DecimalMin(value = "0.00000001", message = "amount 必須大於 0")
    private BigDecimal amount;

    @Schema(description = "操作類型：ADD(加分) 或 SUBTRACT(減分)", example = "ADD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "operation 不得為空")
    @Pattern(regexp = "^(?i)(ADD|SUBTRACT)$", message = "operation 僅接受 ADD 或 SUBTRACT")
    private String operation;

    @Schema(description = "幣種（預設 USDT）", example = "USDT")
    @Size(max = 20, message = "token 長度不得超過 20")
    private String token = "USDT";

    @Schema(description = "調帳備註", example = "客服補償", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "remark 不得為空")
    @Size(max = 200, message = "remark 長度不得超過 200")
    private String remark;

    public boolean isAdd() {
        return "ADD".equalsIgnoreCase(operation);
    }
}
