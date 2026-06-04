package com.agora.dto.staking;

import com.agora.dto.common.BaseSearchParam;
import com.agora.enums.betting.StakingStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "質押搜尋參數")
public class StakingSearchParam extends BaseSearchParam {
    
    @Schema(description = "用戶ID", example = "123")
    private Long userId;
    
    @Schema(description = "質押ID", example = "S2501151430250001A1B2")
    private String stakingId;
    
    @Schema(description = "質押狀態", enumAsRef = true)
    private StakingStatusEnum status;
    
    @Schema(description = "貨幣", example = "USDT")
    private String currency;
} 