package com.agora.enums.betting;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "質押狀態")
public enum StakingStatusEnum {
    @Schema(description = "質押中")
    STAKING,
    @Schema(description = "已完成")
    COMPLETED
}