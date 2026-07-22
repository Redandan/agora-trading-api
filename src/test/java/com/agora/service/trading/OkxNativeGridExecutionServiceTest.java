package com.agora.service.trading;

import com.agora.config.properties.OkxNativeGridProperties;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkxNativeGridExecutionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkxTradingService okx = mock(OkxTradingService.class);
    private final BtGridRepository grids = mock(BtGridRepository.class);
    private final BtGridLevelRepository levels = mock(BtGridLevelRepository.class);

    @Test
    void dryRunReturnsExactCreateConfirmationWithoutProviderWrite() throws Exception {
        OkxNativeGridExecutionService service = service(false, false);
        readyCreatePreconditions();

        String output = previewCreate(service, false, null);
        JsonNode json = mapper.readTree(output);

        assertThat(json.path("status").asText()).isEqualTo("READY_FOR_SEPARATE_EXACT_CREATE_AUTHORIZATION");
        assertThat(json.path("requiredConfirmText").asText()).isEqualTo(
                "AUTHORIZE_OKX_NATIVE_GRID_CREATE|instId=BTC-USDT|algoOrdType=grid|runType=1"
                        + "|minPx=60000|maxPx=70000|gridNum=10|quoteSz=10|algoClOrdId=OKXGRIDTINY001");
        assertThat(json.path("providerCreateAttempted").asBoolean()).isFalse();
        verify(okx, never()).createNativeSpotGrid(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exactConfirmationStillFailsClosedWhenServerGatesAreOff() throws Exception {
        OkxNativeGridExecutionService service = service(false, false);
        readyCreatePreconditions();
        String confirm = mapper.readTree(previewCreate(service, false, null)).path("requiredConfirmText").asText();

        JsonNode result = mapper.readTree(previewCreate(service, true, confirm));

        assertThat(result.path("status").asText()).isEqualTo("CREATE_BLOCKED");
        assertThat(result.path("executionBlockers").toString()).contains("FEATURE_DISABLED", "LIVE_ACTION_DISABLED");
        verify(okx, never()).createNativeSpotGrid(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nativeGatesCannotBypassExistingOkxMasterTradingSwitch() throws Exception {
        OkxNativeGridExecutionService service = service(true, true);
        when(okx.isAutoTradeEnabled()).thenReturn(false);
        readyCreatePreconditions();
        String confirm = mapper.readTree(previewCreate(service, false, null)).path("requiredConfirmText").asText();

        JsonNode result = mapper.readTree(previewCreate(service, true, confirm));

        assertThat(result.path("status").asText()).isEqualTo("CREATE_BLOCKED");
        assertThat(result.path("executionBlockers").toString()).contains("OKX_AUTO_TRADE_MASTER_DISABLED");
        verify(okx, never()).createNativeSpotGrid(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void armedExecutionSubmitsOnlyTheExactTinySpotPackage() throws Exception {
        OkxNativeGridExecutionService service = service(true, true);
        readyCreatePreconditions();
        ArrayNode accepted = mapper.createArrayNode();
        accepted.addObject().put("algoId", "123456789").put("algoClOrdId", "OKXGRIDTINY001").put("sCode", "0");
        when(okx.createNativeSpotGrid("BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"),
                10, new BigDecimal("10"), "OKXGRIDTINY001")).thenReturn(accepted);
        String confirm = mapper.readTree(previewCreate(service, false, null)).path("requiredConfirmText").asText();

        JsonNode result = mapper.readTree(previewCreate(service, true, confirm));

        assertThat(result.path("status").asText()).isEqualTo("CREATE_ACCEPTED_REQUIRES_READ_ONLY_RECONCILIATION");
        assertThat(result.path("providerCreateAttempted").asBoolean()).isTrue();
        verify(okx).createNativeSpotGrid("BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"),
                10, new BigDecimal("10"), "OKXGRIDTINY001");
    }

    @Test
    void matchingProviderClientIdMakesRetryIdempotentWithoutSecondCreate() throws Exception {
        OkxNativeGridExecutionService service = service(true, true);
        ArrayNode active = mapper.createArrayNode();
        active.addObject()
                .put("algoId", "123456789")
                .put("algoClOrdId", "OKXGRIDTINY001")
                .put("instId", "BTC-USDT")
                .put("minPx", "60000")
                .put("maxPx", "70000")
                .put("gridNum", "10")
                .put("quoteSz", "10");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(active);
        when(grids.findBySymbolAndClosedAtIsNull("BTCUSDT")).thenReturn(List.of());
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(rules());
        when(okx.getLastPrice("BTC-USDT")).thenReturn(new BigDecimal("65000"));

        JsonNode result = mapper.readTree(previewCreate(service, true, "anything"));

        assertThat(result.path("status").asText()).isEqualTo("ALREADY_ACTIVE_IDEMPOTENT_NO_CREATE");
        assertThat(result.path("idempotentExisting").asBoolean()).isTrue();
        verify(okx, never()).createNativeSpotGrid(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void stopRequiresExplicitDispositionAndCurrentBotHashConfirmation() throws Exception {
        OkxNativeGridExecutionService service = service(true, true);
        ArrayNode active = mapper.createArrayNode();
        active.addObject().put("algoId", "123456789").put("instId", "BTC-USDT").put("state", "running");
        when(okx.getNativeSpotGridOrders(false)).thenReturn(active);
        ArrayNode detail = mapper.createArrayNode();
        detail.addObject().put("algoId", "123456789").put("instId", "BTC-USDT")
                .put("state", "running").put("baseSz", "0.00008").put("quoteSz", "10");
        when(okx.getNativeSpotGridOrderDetails("123456789")).thenReturn(detail);
        ArrayNode stopped = mapper.createArrayNode();
        stopped.addObject().put("algoId", "123456789").put("sCode", "0");
        when(okx.stopNativeSpotGrid("123456789", "1")).thenReturn(stopped);

        JsonNode preview = mapper.readTree(service.previewOrStop("123456789", "SELL_BASE", false, null));
        String confirm = preview.path("requiredConfirmText").asText();
        JsonNode executed = mapper.readTree(service.previewOrStop("123456789", "SELL_BASE", true, confirm));

        assertThat(preview.path("status").asText()).isEqualTo("READY_FOR_SEPARATE_EXACT_STOP_AUTHORIZATION");
        assertThat(confirm).contains("disposition=SELL_BASE", "stopType=1", "activeBotSha256=");
        assertThat(executed.path("status").asText())
                .isEqualTo("STOP_ACCEPTED_REQUIRES_READ_ONLY_TERMINAL_RECONCILIATION");
        verify(okx).stopNativeSpotGrid("123456789", "1");
    }

    private OkxNativeGridExecutionService service(boolean enabled, boolean live) {
        when(okx.isAutoTradeEnabled()).thenReturn(true);
        return new OkxNativeGridExecutionService(okx, grids, levels,
                new OkxNativeGridProperties(enabled, live), mapper);
    }

    private void readyCreatePreconditions() {
        when(okx.getNativeSpotGridOrders(false)).thenReturn(mapper.createArrayNode());
        when(okx.getNativeSpotGridOrders(true)).thenReturn(mapper.createArrayNode());
        when(grids.findBySymbolAndClosedAtIsNull("BTCUSDT")).thenReturn(List.of());
        when(okx.getSpotInstrumentRules("BTC-USDT")).thenReturn(rules());
        when(okx.getLastPrice("BTC-USDT")).thenReturn(new BigDecimal("65000"));
    }

    private OkxTradingService.SpotInstrumentRules rules() {
        return new OkxTradingService.SpotInstrumentRules("BTC-USDT", new BigDecimal("0.00001"),
                new BigDecimal("0.00000001"), new BigDecimal("0.1"));
    }

    private String previewCreate(OkxNativeGridExecutionService service, boolean execute, String confirm) {
        return service.previewOrCreate("BTC-USDT", new BigDecimal("60000"), new BigDecimal("70000"),
                10, new BigDecimal("10"), "OKXGRIDTINY001", execute, confirm);
    }
}
