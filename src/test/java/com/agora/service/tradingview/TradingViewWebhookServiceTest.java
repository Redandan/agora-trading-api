package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewWebhookProperties;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TradingViewWebhookServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);

    @Test
    void disabledWebhookFailsClosedWithoutAudit() throws Exception {
        TradingViewWebhookService service = new TradingViewWebhookService(
                properties(false, true), auditWriter);

        TradingViewWebhookService.HandlingResult result = service.handle(payload("""
                {"secret":"tv-secret","action":"BUY","symbol":"BTCUSDT"}
                """), "127.0.0.1");

        assertThat(result.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.body().status()).isEqualTo("DISABLED");
        verifyNoInteractions(auditWriter);
    }

    @Test
    void invalidSecretFailsClosedWithoutAudit() throws Exception {
        TradingViewWebhookService service = new TradingViewWebhookService(
                properties(true, true), auditWriter);

        TradingViewWebhookService.HandlingResult result = service.handle(payload("""
                {"secret":"wrong","action":"BUY","symbol":"BTCUSDT"}
                """), "127.0.0.1");

        assertThat(result.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(result.body().status()).isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(auditWriter);
    }

    @Test
    void validBuyAlertIsAcceptedAsDryRunAndAudited() throws Exception {
        TradingViewWebhookService service = new TradingViewWebhookService(
                properties(true, true), auditWriter);

        TradingViewWebhookService.HandlingResult result = service.handle(payload("""
                {
                  "secret":"tv-secret",
                  "strategy":"AI",
                  "action":"BUY",
                  "symbol":"BINANCE:BTCUSDT",
                  "timeframe":"1D",
                  "barTime":"2026-07-02T00:00:00Z",
                  "price":"60400",
                  "notionalUsdt":"10"
                }
                """), "127.0.0.1");

        assertThat(result.httpStatus()).isEqualTo(HttpStatus.OK);
        assertThat(result.body().status()).isEqualTo("ACCEPTED_DRY_RUN");
        assertThat(result.body().accepted()).isTrue();
        assertThat(result.body().wouldExecute()).isTrue();
        assertThat(result.body().orderSent()).isFalse();
        assertThat(result.body().symbol()).isEqualTo("BTCUSDT");

        verify(auditWriter).logSignalEval(isNull(), eq("BTCUSDT"), eq("1D"),
                eq(java.time.LocalDateTime.of(2026, 7, 2, 0, 0)), eq("BUY"), anyMap());
        verify(auditWriter).logEntrySkip(isNull(), eq("BTCUSDT"), eq("1D"),
                eq(java.time.LocalDateTime.of(2026, 7, 2, 0, 0)),
                eq("TradingViewDryRun"), eq("TradingView webhook dry-run; no order sent"), anyMap());
    }

    @Test
    void duplicateAlertIsIgnoredByIdempotencyKey() throws Exception {
        TradingViewWebhookService service = new TradingViewWebhookService(
                properties(true, true), auditWriter);
        JsonNode payload = payload("""
                {"secret":"tv-secret","action":"BUY","symbol":"BTCUSDT","timeframe":"1D","alertId":"same-alert"}
                """);

        TradingViewWebhookService.HandlingResult first = service.handle(payload, "127.0.0.1");
        TradingViewWebhookService.HandlingResult second = service.handle(payload, "127.0.0.1");

        assertThat(first.body().status()).isEqualTo("ACCEPTED_DRY_RUN");
        assertThat(second.httpStatus()).isEqualTo(HttpStatus.OK);
        assertThat(second.body().status()).isEqualTo("DUPLICATE");
        assertThat(second.body().duplicate()).isTrue();
    }

    @Test
    void notionalAboveCapIsRejectedBeforeExecution() throws Exception {
        TradingViewWebhookService service = new TradingViewWebhookService(
                properties(true, true), auditWriter);

        TradingViewWebhookService.HandlingResult result = service.handle(payload("""
                {"secret":"tv-secret","action":"BUY","symbol":"BTCUSDT","notionalUsdt":"25"}
                """), "127.0.0.1");

        assertThat(result.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result.body().status()).isEqualTo("REJECTED");
        assertThat(result.body().blockers()).contains("NOTIONAL_ABOVE_CAP");
    }

    private JsonNode payload(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private TradingViewWebhookProperties properties(boolean enabled, boolean dryRun) {
        return new TradingViewWebhookProperties(
                enabled,
                dryRun,
                "tv-secret",
                "BTCUSDT",
                new BigDecimal("10.0"),
                new BigDecimal("10.0"),
                24);
    }
}
