package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** REST order totals must use fee/feeCcy with accFillSz, never last-fill fields. */
record OkxCumulativeOrderFee(BigDecimal signedAmount, String currency) {
    static OkxCumulativeOrderFee read(JsonNode order) {
        String amount = order.path("fee").asText("").trim();
        String currency = order.path("feeCcy").asText("").trim();
        if (amount.isEmpty() || currency.isEmpty()) return pending();
        BigDecimal signed = new BigDecimal(amount);
        String rebate = order.path("rebate").asText("").trim();
        // The retained ledger supports fee costs, not signed rebate accounting.
        // Leave unsupported receipts pending instead of counting rebates as fees.
        if (signed.signum() > 0 || (!rebate.isEmpty() && new BigDecimal(rebate).signum() != 0)) {
            return pending();
        }
        return new OkxCumulativeOrderFee(signed, currency);
    }

    private static OkxCumulativeOrderFee pending() {
        return new OkxCumulativeOrderFee(null, null);
    }
}
