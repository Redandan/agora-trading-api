package com.agora.dto.transaction;

import com.agora.dto.common.BaseSearchParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "交易搜索參數")
public class TransactionSearchParam extends BaseSearchParam {
    
    @Schema(description = "會員ID", example = "123" ,required = false)
    private Long userId;
    
    @Schema(description = "幣種", example = "USDT" ,required = false)
    private String token;
    
    @Schema(description = "交易類型" ,required = false)
    private String transactionType;
    
    @Schema(description = "交易狀態" ,required = false)
    private String status;
}
