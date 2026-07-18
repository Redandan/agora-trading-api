package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawFill;
import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Narrow adapter over the authenticated GET-only fills-history method. */
@Component
@RequiredArgsConstructor
public class OkxTradingEvidenceReadClient implements ExactTradeFillReadClient {
    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    @Override
    public RawPage getPage(String instrumentId, String instrumentType, int limit, String cursor,
                           String accountRefHash) {
        JsonNode body = okxTradingService.getFillHistoryPage(instrumentType, instrumentId, limit, cursor);
        Instant collectedAt = clock.instant();
        if (body == null || !"0".equals(body.path("code").asText()) || !body.path("data").isArray()) {
            return new RawPage(cursor, null, hash("invalid", cursor), hashJson(body), collectedAt,
                    false, false, List.of());
        }
        List<RawFill> fills = new ArrayList<>();
        String pageKey = hash("okx", instrumentId, instrumentType, cursor, hashJson(body.path("data")));
        for (JsonNode item : body.path("data")) fills.add(fill(item, accountRefHash, pageKey, collectedAt));
        boolean terminal = fills.isEmpty();
        String next = terminal ? null : required(body.path("data").get(body.path("data").size() - 1), "billId");
        return new RawPage(cursor, next, pageKey, hashJson(body.path("data")), collectedAt,
                terminal, true, fills);
    }

    private RawFill fill(JsonNode item, String accountRefHash, String pageKey, Instant collectedAt) {
        String provider = "okx";
        String instId = required(item, "instId");
        String instType = required(item, "instType").toUpperCase();
        String orderId = required(item, "ordId");
        String tradeId = required(item, "tradeId");
        String billId = required(item, "billId");
        Instant fillAt = Instant.ofEpochMilli(Long.parseLong(required(item, "fillTime")));
        String side = required(item, "side").toUpperCase();
        BigDecimal price = positive(item, "fillPx");
        BigDecimal quantity = positive(item, "fillSz");
        BigDecimal fee = decimal(item, "fee");
        String feeCurrency = required(item, "feeCcy").toUpperCase();
        String liquidityRole = blankToNull(item.path("execType").asText(null));
        String rawHash = hashJson(allowlisted(item));
        RawFill draft = new RawFill(provider, accountRefHash, instId, instType, orderId, tradeId, billId,
                fillAt, side, price, quantity, fee, feeCurrency, liquidityRole, rawHash, pageKey, collectedAt,
                null, null, null, null, null, null, null);
        return new RawFill(draft.provider(), draft.accountRefHash(), draft.instrumentId(), draft.instrumentType(),
                draft.orderId(), draft.tradeId(), draft.billId(), draft.fillAt(), draft.side(), draft.fillPrice(),
                draft.fillQuantity(), draft.signedFeeAmount(), draft.feeCurrency(), draft.liquidityRole(),
                draft.rawPayloadSha256(), draft.sourcePageKey(), draft.collectedAt(), draft.cohortId(),
                draft.runtimeDecisionId(), draft.liveSignalId(), draft.intendedChildOrderId(),
                draft.actualChildOrderId(), ExactTradeFillHashing.identity(draft), ExactTradeFillHashing.content(draft));
    }

    private JsonNode allowlisted(JsonNode item) {
        var safe = objectMapper.createObjectNode();
        for (String field : List.of("instId","instType","ordId","tradeId","billId","fillTime","side",
                "fillPx","fillSz","fee","feeCcy","execType")) {
            if (item.hasNonNull(field)) safe.set(field, item.get(field).deepCopy());
        }
        return safe;
    }

    private String hashJson(JsonNode node) {
        try { return hash(objectMapper.writeValueAsString(node)); }
        catch (Exception e) { return hash("INVALID_JSON"); }
    }
    private static String hash(String... values) { return ExactTradeFillHashing.hash(values); }
    private static String required(JsonNode n, String f) {
        String v = n == null ? null : n.path(f).asText(null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing exact fill field: " + f);
        return v;
    }
    private static BigDecimal decimal(JsonNode n, String f) {
        try { return new BigDecimal(required(n, f)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid exact fill number: " + f, e); }
    }
    private static BigDecimal positive(JsonNode n, String f) {
        BigDecimal value = decimal(n, f);
        if (value.signum() <= 0) throw new IllegalArgumentException("non-positive exact fill number: " + f);
        return value;
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
