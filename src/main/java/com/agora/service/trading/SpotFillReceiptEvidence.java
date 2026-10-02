package com.agora.service.trading;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure accounting of normalized provider fields, never an execution decision. */
public final class SpotFillReceiptEvidence {
    public static final String SCHEMA = "SPOT_FILL_V1";
    private static final BigDecimal EPS = new BigDecimal("0.000000000001");

    private SpotFillReceiptEvidence() { }

    public static Map<String, Object> capture(String side, String clientOrderId,
                                             TradeResult fill, BigDecimal allocatedQuantity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("provider", "OKX");
        out.put("side", side);
        out.put("clientOrderId", clientOrderId);
        out.put("providerOrderId", fill.getOrderId());
        out.put("averagePrice", fill.getAvgPrice());
        out.put("grossQuantity", fill.getGrossQty());
        out.put("netQuantity", fill.getQty());
        out.put("signedFee", fill.getFeeAmount());
        out.put("feeCurrency", fill.getFeeCurrency());
        out.put("feeUsdt", fill.getFeeUsdt());
        out.put("allocatedQuantity", allocatedQuantity);
        out.put("basis", "NORMALIZED_PROVIDER_RECEIPT_NOT_ESTIMATED_FEE");
        return out;
    }

    public static CashFlow parse(JsonNode n) {
        if (n == null || !SCHEMA.equals(n.path("schema").asText())
                || !"OKX".equals(n.path("provider").asText())) {
            throw new IllegalArgumentException("MISSING_RECEIPT_SCHEMA");
        }
        return calculate(n.path("providerOrderId").asText(null), n.path("side").asText(null),
                decimal(n, "averagePrice"), decimal(n, "grossQuantity"), decimal(n, "netQuantity"),
                decimal(n, "signedFee"), n.path("feeCurrency").asText(null),
                decimal(n, "feeUsdt"), decimal(n, "allocatedQuantity"));
    }

    public static CashFlow calculate(String orderId, String side, BigDecimal price,
                                    BigDecimal gross, BigDecimal net, BigDecimal signedFee,
                                    String currency, BigDecimal feeUsdt, BigDecimal allocated) {
        if (orderId == null || orderId.isBlank() || !("BUY".equals(side) || "SELL".equals(side))
                || !positive(price) || !positive(gross) || !positive(net) || !positive(allocated)) {
            throw new IllegalArgumentException("MISSING_PROVIDER_FILL_FIELDS");
        }
        if (signedFee == null || signedFee.signum() > 0 || feeUsdt == null || feeUsdt.signum() < 0
                || !("BTC".equals(currency) || "USDT".equals(currency))) {
            throw new IllegalArgumentException("FEE_RECONCILIATION_INCOMPLETE");
        }
        boolean buy = "BUY".equals(side);
        if (!buy && !"USDT".equals(currency)) {
            throw new IllegalArgumentException("UNSUPPORTED_SELL_FEE_CURRENCY");
        }
        BigDecimal fee = signedFee.negate();
        BigDecimal expectedFeeUsdt = "BTC".equals(currency) ? fee.multiply(price) : fee;
        BigDecimal expectedNet = buy && "BTC".equals(currency) ? gross.subtract(fee) : gross;
        // OKX adapter normalizes base fees to USDT at 8 decimals.
        if ("BTC".equals(currency)) expectedFeeUsdt = expectedFeeUsdt.setScale(8, RoundingMode.HALF_UP);
        if (!near(expectedFeeUsdt, feeUsdt) || !near(expectedNet, net)) {
            throw new IllegalArgumentException("PROVIDER_FEE_OR_NET_QUANTITY_MISMATCH");
        }
        BigDecimal totalQuantity = buy ? net : gross;
        if (allocated.compareTo(totalQuantity) > 0 || buy && !near(allocated, net)) {
            throw new IllegalArgumentException("INVALID_FILL_ALLOCATION");
        }
        BigDecimal ratio = allocated.divide(totalQuantity, 24, RoundingMode.HALF_UP);
        BigDecimal grossQuote = price.multiply(gross);
        // A base-denominated buy fee reduces BTC received; it is already in cash/net cost.
        BigDecimal cash = buy ? grossQuote.add("USDT".equals(currency) ? fee : BigDecimal.ZERO)
                : grossQuote.subtract(fee).multiply(ratio);
        return new CashFlow(orderId, side, allocated, cash, feeUsdt.multiply(ratio));
    }

    static boolean near(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.subtract(b).abs().compareTo(EPS) <= 0;
    }

    private static BigDecimal decimal(JsonNode n, String key) {
        JsonNode value = n.path(key);
        return value.isNumber() || value.isTextual() ? new BigDecimal(value.asText()) : null;
    }

    private static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }

    public record CashFlow(String orderId, String side, BigDecimal quantity,
                           BigDecimal cashUsdt, BigDecimal feeUsdt) { }
}
