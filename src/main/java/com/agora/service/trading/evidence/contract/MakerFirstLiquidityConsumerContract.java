package com.agora.service.trading.evidence.contract;

import java.math.BigDecimal;

/** Consumer-only input not represented by an actual fill or by the V2 ledgers. */
public record MakerFirstLiquidityConsumerContract(BigDecimal requestedQuantity) {
}
