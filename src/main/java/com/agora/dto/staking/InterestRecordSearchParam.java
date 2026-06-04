package com.agora.dto.staking;

import com.agora.dto.common.BaseSearchParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "收益發放記錄搜尋參數")
public class InterestRecordSearchParam extends BaseSearchParam {
    
    @Schema(description = "用戶ID")
    private Long userId;
    
    @Schema(description = "質押記錄ID")
    private String stakingId;
    
    // startDate 和 endDate 已從 BaseSearchParam 繼承
}

