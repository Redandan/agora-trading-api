package com.agora.enums.system;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "協議")
public enum ProtocolEnum {
    @Schema(description = "TRC20")
    TRC20,

    @Schema(description = "ERC20")
    ERC20,

    @Schema(description = "BEP20")
    BEP20
}
