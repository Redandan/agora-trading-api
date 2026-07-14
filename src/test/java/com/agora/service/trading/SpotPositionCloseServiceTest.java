package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpotPositionCloseServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ocoAlreadyFilledNeverSendsAnotherSell() throws Exception {
        Fixture fixture = fixture(position(1L));
        fixture.position.setOcoOrderListId(123L);
        when(fixture.okx.getAlgoOrder("BTCUSDT", 123L))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\"}"));

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(1L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("OCO_ALREADY_FILLED");
        verify(fixture.okx, never()).cancelOco(any(), any());
        verify(fixture.okx, never()).placeMarketSellWithFill(any(), any());
    }

    @Test
    void secondOcoChildFilledNeverCancelsOrSendsAnotherSell() throws Exception {
        Fixture fixture = fixture(position(8L));
        fixture.position.setOcoOrderListId(800L);
        when(fixture.okx.getAlgoOrder("BTCUSDT", 800L)).thenReturn(MAPPER.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-8\",\"sl-8\"]}"));
        when(fixture.okx.querySpotOrderDetail("BTCUSDT", "tp-8"))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\"}"));
        when(fixture.okx.querySpotOrderDetail("BTCUSDT", "sl-8"))
                .thenReturn(MAPPER.readTree("{\"state\":\"filled\",\"avgPx\":\"88\"}"));

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(8L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("OCO_ALREADY_FILLED");
        verify(fixture.okx, never()).cancelOco(any(), any());
        verify(fixture.okx, never()).placeMarketSellWithFill(any(), any());
        verify(fixture.okx).querySpotOrderDetail("BTCUSDT", "tp-8");
        verify(fixture.okx).querySpotOrderDetail("BTCUSDT", "sl-8");
    }

    @Test
    void unconfirmedOcoCancellationFailsClosedWithoutSelling() throws Exception {
        Fixture fixture = fixture(position(2L));
        fixture.position.setOcoOrderListId(456L);
        when(fixture.okx.getAlgoOrder("BTCUSDT", 456L))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\",\"ordIdList\":[]}"));
        org.mockito.Mockito.doThrow(new RuntimeException("cancel timeout"))
                .when(fixture.okx).cancelOco("BTCUSDT", 456L);

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(2L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.reason()).isEqualTo("OCO_CANCEL_NOT_CONFIRMED");
        verify(fixture.okx, never()).placeMarketSellWithFill(any(), any());
    }

    @Test
    void zeroFreshBalanceAfterConfirmedCancelReprotectsInsteadOfSellingFromCache() throws Exception {
        Fixture fixture = fixture(position(7L));
        fixture.position.setOcoOrderListId(700L);
        when(fixture.okx.getAlgoOrder("BTCUSDT", 700L))
                .thenReturn(MAPPER.readTree("{\"state\":\"live\",\"ordIdList\":[]}"));
        when(fixture.okx.getFreshSpotHoldings()).thenReturn(List.of());
        when(fixture.okx.placeOco("BTCUSDT", bd("1"), bd("106"), bd("88"))).thenReturn(701L);

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(7L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.reason()).isEqualTo("AVAILABLE_BASE_QTY_ZERO_AFTER_OCO_CANCEL:REPROTECTED");
        assertThat(result.replacementOcoId()).isEqualTo(701L);
        assertThat(fixture.position.getOcoOrderListId()).isEqualTo(701L);
        verify(fixture.okx, never()).placeMarketSellWithFill(any(), any());
    }

    @Test
    void fullMarketFillClosesPositionWithGrossPnlAndFeeEvidence() {
        Fixture fixture = fixture(position(3L));
        when(fixture.okx.placeMarketSellWithFill("BTCUSDT", bd("1"))).thenReturn(fill("sell-3", "110", "1", "0.11"));

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(3L, "TIME_EXIT_24H");

        assertThat(result.closedSuccessfully()).isTrue();
        assertThat(result.grossPnlUsdt()).isEqualByComparingTo("10");
        assertThat(result.exitFeeUsdt()).isEqualByComparingTo("0.11");
        assertThat(fixture.position.getExitReason()).isEqualTo("TIME_EXIT_24H");
        assertThat(fixture.position.getRealizedPnl()).isEqualByComparingTo("10");
        assertThat(fixture.position.getOcoOrderListId()).isNull();
    }

    @Test
    void partialMarketFillPersistsRemainderAndReattachesOco() {
        Fixture fixture = fixture(position(4L));
        when(fixture.okx.placeMarketSellWithFill("BTCUSDT", bd("1"))).thenReturn(fill("sell-4", "110", "0.5", "0.055"));
        when(fixture.okx.placeOco("BTCUSDT", bd("0.5"), bd("106"), bd("88"))).thenReturn(999L);

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(4L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.remainingQty()).isEqualByComparingTo("0.5");
        assertThat(result.replacementOcoId()).isEqualTo(999L);
        assertThat(fixture.position.getExitTime()).isNull();
        assertThat(fixture.position.getTradedQty()).isEqualByComparingTo("0.5");
        assertThat(fixture.position.getOcoOrderListId()).isEqualTo(999L);
    }

    @Test
    void duplicateSchedulerInvocationReturnsBusyWhileFirstCloseIsRunning() throws Exception {
        Fixture fixture = fixture(position(5L));
        CountDownLatch sellStarted = new CountDownLatch(1);
        CountDownLatch allowSell = new CountDownLatch(1);
        when(fixture.okx.placeMarketSellWithFill("BTCUSDT", bd("1"))).thenAnswer(invocation -> {
            sellStarted.countDown();
            assertThat(allowSell.await(5, TimeUnit.SECONDS)).isTrue();
            return fill("sell-5", "110", "1", "0.11");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SpotPositionCloseService.CloseResult> first = executor.submit(
                    () -> fixture.service.closeAtMarket(5L, "TIME_EXIT_24H"));
            assertThat(sellStarted.await(5, TimeUnit.SECONDS)).isTrue();

            SpotPositionCloseService.CloseResult duplicate = fixture.service.closeAtMarket(5L, "TIME_EXIT_24H");
            allowSell.countDown();

            assertThat(duplicate.status()).isEqualTo("BUSY");
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo("CLOSED");
            verify(fixture.okx, times(1)).placeMarketSellWithFill("BTCUSDT", bd("1"));
        } finally {
            allowSell.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void restartedServiceSeesClosedPositionAndDoesNotSellTwice() {
        Fixture fixture = fixture(position(6L));
        when(fixture.okx.placeMarketSellWithFill("BTCUSDT", bd("1"))).thenReturn(fill("sell-6", "110", "1", "0.11"));

        assertThat(fixture.service.closeAtMarket(6L, "TIME_EXIT_24H").status()).isEqualTo("CLOSED");
        SpotPositionCloseService restarted = new SpotPositionCloseService(
                fixture.repository, fixture.okx, new OcoOrderStateInspector(fixture.okx));
        SpotPositionCloseService.CloseResult second = restarted.closeAtMarket(6L, "TIME_EXIT_24H");

        assertThat(second.status()).isEqualTo("ALREADY_CLOSED");
        verify(fixture.okx, times(1)).placeMarketSellWithFill("BTCUSDT", bd("1"));
    }

    @Test
    void btcBaseManagedPositionCannotUseGenericMarketClosePath() {
        BtLiveSignal position = position(9L);
        position.setFilterReason(BtcBasePositionStatePolicy.adoptedMarkerFromPending(
                BtcBasePositionStatePolicy.pendingMarker(900L, null)));
        position.setOcoOrderListId(null);
        position.setOcoQty(null);
        Fixture fixture = fixture(position);

        SpotPositionCloseService.CloseResult result = fixture.service.closeAtMarket(9L, "TIME_EXIT_24H");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.reason()).isEqualTo("BTC_BASE_MANAGED_MARKET_SELL_BLOCKED");
        verify(fixture.okx, never()).cancelOco(any(), any());
        verify(fixture.okx, never()).placeMarketSellWithFill(any(), any());
    }

    private Fixture fixture(BtLiveSignal position) {
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        when(repository.findByIdForUpdate(position.getId())).thenReturn(Optional.of(position));
        when(repository.findById(position.getId())).thenReturn(Optional.of(position));
        when(repository.save(any(BtLiveSignal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(okx.getFreshSpotHoldings()).thenReturn(List.of(
                new OkxTradingService.SpotHolding("BTC", bd("1"), bd("1"), bd("100"))));
        return new Fixture(new SpotPositionCloseService(
                repository, okx, new OcoOrderStateInspector(okx)), repository, okx, position);
    }

    private BtLiveSignal position(Long id) {
        BtLiveSignal position = new BtLiveSignal();
        position.setId(id);
        position.setStrategyId(508L);
        position.setSymbol("BTCUSDT");
        position.setIntervalCode("4h");
        position.setSide("LONG");
        position.setAutoTraded(true);
        position.setEntryPrice(bd("100"));
        position.setActualEntryPrice(bd("100"));
        position.setTradedQty(bd("1"));
        position.setOcoQty(bd("1"));
        position.setSuggestedTp(bd("106"));
        position.setSuggestedSl(bd("88"));
        return position;
    }

    private TradeResult fill(String id, String price, String quantity, String feeUsdt) {
        TradeResult result = new TradeResult();
        result.setOrderId(id);
        result.setAvgPrice(bd(price));
        result.setQty(bd(quantity));
        result.setGrossQty(bd(quantity));
        result.setNetQty(bd(quantity));
        result.setFeeAmount(bd(feeUsdt).negate());
        result.setFeeCurrency("USDT");
        result.setFeeUsdt(bd(feeUsdt));
        return result;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Fixture(SpotPositionCloseService service,
                           BtLiveSignalRepository repository,
                           OkxTradingService okx,
                           BtLiveSignal position) {
    }
}
