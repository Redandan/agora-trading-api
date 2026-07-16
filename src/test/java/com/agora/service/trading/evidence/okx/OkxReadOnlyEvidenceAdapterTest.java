package com.agora.service.trading.evidence.okx;

import com.agora.service.diagnostic.coverage.CoverageProfiler;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.CaptureContext;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AccountMode;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.AccountSemantics;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.PositionMode;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FillAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.FundingAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.NormalizationBatch;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.QuoteAppend;
import com.agora.service.trading.evidence.okx.OkxEvidenceModels.RejectReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class OkxReadOnlyEvidenceAdapterTest {

    private static final Instant EVENT = Instant.ofEpochMilli(1_710_000_000_000L);
    private static final String ACCOUNT = "full-provider-account-id-must-not-persist";

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeClient client;
    private OkxReadOnlyEvidenceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        client = new FakeClient(Map.of(
                OkxEvidenceReadClient.Endpoint.EXECUTABLE_BOOKS, fixture("books.json"),
                OkxEvidenceReadClient.Endpoint.FILL_HISTORY, fixture("fills.json"),
                OkxEvidenceReadClient.Endpoint.FUNDING_BILLS, fixture("funding-bills.json"),
                OkxEvidenceReadClient.Endpoint.ACCOUNT_BALANCE, fixture("margin.json")));
        adapter = new OkxReadOnlyEvidenceAdapter(client, mapper);
    }

    @Test
    void fixturesNormalizeAllFourDatasetsWithSignedFactsAndRedaction() {
        NormalizationBatch quoteBatch = adapter.executableDepth("BTC-USDT", "SPOT", 5, null, capture());
        NormalizationBatch fillBatch = adapter.fills("SPOT", null, capture());
        NormalizationBatch fundingBatch = adapter.fundingBills(null, capture());
        NormalizationBatch marginBatch = adapter.marginSnapshots(
                new AccountSemantics(AccountMode.MULTI_CURRENCY, PositionMode.NET), null, capture());

        assertThat(quoteBatch.validForAppend()).isTrue();
        assertThat(fillBatch.validForAppend()).isTrue();
        assertThat(fundingBatch.validForAppend()).isTrue();
        assertThat(marginBatch.validForAppend()).isTrue();
        QuoteAppend quote = (QuoteAppend) quoteBatch.accepted().getFirst();
        FillAppend fill = (FillAppend) fillBatch.accepted().getFirst();
        FundingAppend funding = (FundingAppend) fundingBatch.accepted().getFirst();
        assertThat(quote.depthJson()).doesNotContain("apiKey", "MUST_NOT_PERSIST", "headers", "OK-ACCESS-SIGN");
        assertThat(fill.signedFeeAmount()).isNegative();
        assertThat(funding.signedFundingAmount()).isNegative();
        assertThat(fill.accountRefHash()).hasSize(64).doesNotContain(ACCOUNT);
        assertThat(funding.accountRefHash()).isEqualTo(fill.accountRefHash());
        assertThat(quoteBatch.toString() + fillBatch + fundingBatch + marginBatch)
                .doesNotContain(ACCOUNT, "MUST_NOT_PERSIST");
        assertThat(client.lastRequest.endpoint().access()).isIn(
                OkxEvidenceReadClient.Access.PUBLIC, OkxEvidenceReadClient.Access.AUTHENTICATED_READ_ONLY);
    }

    @Test
    void outOfOrderCanonicalTimestampsFailClosed() {
        client.availableAt = EVENT.minusMillis(1);
        NormalizationBatch batch = adapter.executableDepth("BTC-USDT", "SPOT", 5, null, capture());

        assertThat(batch.accepted()).isEmpty();
        assertThat(batch.validForAppend()).isFalse();
        assertThat(batch.rejected()).extracting(OkxEvidenceModels.RejectedEvidence::reason)
                .containsExactly(RejectReason.TIMESTAMP_ORDER_VIOLATION);
    }

    @Test
    void missingSignedFeeAndFundingFailClosed() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) client.responses
                .get(OkxEvidenceReadClient.Endpoint.FILL_HISTORY).path("data").get(0)).remove("fee");
        ((com.fasterxml.jackson.databind.node.ObjectNode) client.responses
                .get(OkxEvidenceReadClient.Endpoint.FUNDING_BILLS).path("data").get(0)).remove("balChg");

        assertThat(adapter.fills("SPOT", null, capture()).rejected().getFirst().reason())
                .isEqualTo(RejectReason.MISSING_SIGNED_FEE);
        assertThat(adapter.fundingBills(null, capture()).rejected().getFirst().reason())
                .isEqualTo(RejectReason.MISSING_SIGNED_FUNDING);
    }

    @Test
    void portfolioAccountCannotClaimUnsupportedLongShortPositionMode() {
        NormalizationBatch batch = adapter.marginSnapshots(
                new AccountSemantics(AccountMode.PORTFOLIO, PositionMode.LONG_SHORT), null, capture());

        assertThat(batch.accepted()).isEmpty();
        assertThat(batch.rejected().getFirst().reason()).isEqualTo(RejectReason.UNSUPPORTED_MARGIN_MODE);
    }

    @Test
    void mixedValidAndInvalidPageRetainsDiagnosticsButCannotAppend() {
        ArrayNode fills = (ArrayNode) client.responses.get(OkxEvidenceReadClient.Endpoint.FILL_HISTORY).path("data");
        var invalid = fills.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).remove("fee");
        fills.add(invalid);

        NormalizationBatch batch = adapter.fills("SPOT", "cursor-before", capture());

        assertThat(batch.accepted()).hasSize(1);
        assertThat(batch.rejected()).hasSize(1);
        assertThat(batch.validForAppend()).isFalse();
        assertThat(batch.rejected().getFirst().reason()).isEqualTo(RejectReason.MISSING_SIGNED_FEE);
    }

    @Test
    void dedupeAndRawHashesAreDeterministic() {
        QuoteAppend first = (QuoteAppend) adapter.executableDepth("BTC-USDT", "SPOT", 5, null, capture())
                .accepted().getFirst();
        QuoteAppend second = (QuoteAppend) adapter.executableDepth("BTC-USDT", "SPOT", 5, null, capture())
                .accepted().getFirst();

        assertThat(second.dedupeKey()).isEqualTo(first.dedupeKey()).hasSize(64);
        assertThat(second.provenance().rawPayloadSha256())
                .isEqualTo(first.provenance().rawPayloadSha256()).hasSize(64);
    }

    private CaptureContext capture() {
        return new CaptureContext(EVENT.plusMillis(300), EVENT.plusMillis(400),
                CoverageProfiler.Provenance.FORWARD, ACCOUNT);
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream input = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("fixtures/okx-evidence/" + name))) {
            return mapper.readTree(input);
        }
    }

    private static final class FakeClient implements OkxEvidenceReadClient {
        private final Map<Endpoint, JsonNode> responses = new EnumMap<>(Endpoint.class);
        private Instant availableAt = EVENT.plusMillis(100);
        private Instant observedAt = EVENT.plusMillis(200);
        private ReadRequest lastRequest;

        private FakeClient(Map<Endpoint, JsonNode> responses) {
            this.responses.putAll(responses);
        }

        @Override
        public ReadPage get(ReadRequest request) {
            lastRequest = request;
            return new ReadPage(responses.get(request.endpoint()), availableAt, observedAt,
                    "next-cursor", "page-1", true);
        }
    }
}
