package com.agora.service.advice;

import com.agora.config.BtcOrderAdviceProperties;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;
import static com.agora.service.advice.BtcOrderAdviceEngineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceServiceTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final BtcOrderAdviceEngine engine = new BtcOrderAdviceEngine();
    final AtomicInteger sends = new AtomicInteger();
    final AtomicBoolean delivered = new AtomicBoolean();

    BtcOrderAdviceProperties props() {
        var p = new BtcOrderAdviceProperties(); p.setStateFile(temp.resolve("state.json").toString());
        p.setMonitorEnabled(true); p.setNotificationsEnabled(true); return p;
    }
    TelegramService telegram() {
        return (TelegramService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TelegramService.class}, (p, m, a) -> {
            if (!m.getName().equals("sendBtcOrderAdvice")) throw new AssertionError("Unexpected notification path: " + m.getName());
            sends.incrementAndGet(); return delivered.get();
        });
    }
    BtcOrderAdviceSnapshotReader reader() {
        return new BtcOrderAdviceSnapshotReader(null, null, null, null) {
            @Override public Input read() { return input(Instant.now(), "84000", "0.0008871693", List.of(), List.of()); }
        };
    }
    BtcOrderAdviceService service(BtcOrderAdviceProperties p) { return new BtcOrderAdviceService(reader(), p, mapper, telegram()); }

    @Test void smallPriceNoiseDoesNotChurnPlansButBreakoutInvalidatesThem() {
        var old = engine.evaluate(input(), Policy.defaults());
        assertNull(BtcOrderAdviceService.changeReason(old, engine.evaluate(input(NOW.plusSeconds(30), "84001", "0.0008871693", List.of(), List.of()), Policy.defaults())));
        var breakout = engine.evaluate(input(NOW.plusSeconds(30), "92000", "0.0008871693", List.of(), List.of()), Policy.defaults());
        assertNotNull(BtcOrderAdviceService.changeReason(old, breakout)); assertTrue(breakout.orders().isEmpty());
    }

    @Test void queriesNeverSendAndFailureRemainsPendingUntilAcknowledged() {
        var p = props(); var service = service(p);
        assertEquals(1, service.current().state().revision()); assertEquals(0, sends.get());
        service.notifyPending(); assertEquals(1, sends.get());
        assertEquals("DELIVERY_PENDING_RETRY", service.current().state().deliveryStatus());
        assertEquals(1, service.current().state().revision(), "An unchanged query must preserve the pending revision");
        delivered.set(true); service.notifyPending(); assertEquals(2, sends.get());
        assertFalse(service.current().state().pendingNotification());
        service.notifyPending(); assertEquals(2, sends.get());
        // A new process consumes durable acknowledgement without re-sending unchanged advice.
        var restarted = service(p); restarted.current(); restarted.notifyPending(); assertEquals(2, sends.get());
    }

    @Test void disabledNotificationsCannotSendAndDataFailureReplacesExecutableAdvice() {
        var p = props(); p.setNotificationsEnabled(false);
        var service = service(p); service.current(); service.notifyPending(); assertEquals(0, sends.get());
        var failedReader = new BtcOrderAdviceSnapshotReader(null, null, null, null) {
            @Override public Input read() { throw new IllegalStateException("provider unavailable"); }
        };
        var failed = new BtcOrderAdviceService(failedReader, p, mapper, telegram()).current();
        assertEquals(2, failed.state().revision()); assertEquals("DATA_UNAVAILABLE", failed.state().advice().status());
        assertTrue(failed.state().advice().orders().isEmpty());
    }

    @Test void ordinaryRenewalIsQueryableWithoutPeriodicUnchangedPushes() {
        var service = service(props()); service.current(); delivered.set(true); service.notifyPending();
        var in = input(Instant.now().plusSeconds(3700), "84000", "0.0008871693", List.of(), List.of());
        service.accept(engine.evaluate(in, Policy.defaults()));
        service.notifyPending(); assertEquals(1, sends.get());
    }

    @Test void corruptStateCannotSilentlyOverwriteDeliveryHistory() throws Exception {
        var p = props(); Files.writeString(Path.of(p.getStateFile()), "{broken");
        assertThrows(IllegalStateException.class, () -> service(p).current());
        assertEquals("{broken", Files.readString(Path.of(p.getStateFile())));
    }

    @Test void textExplainsConditionalFundingAndFitsOneTelegramMessage() {
        var service = service(props()); var view = service.current();
        String text = BtcOrderAdviceFormatter.format(view);
        assertTrue(text.contains("不能現在同時掛兩邊")); assertTrue(text.contains("含本金")); assertTrue(text.length() < 4096);
    }

    @Test void twoServiceInstancesShareTheLatestAcknowledgement() {
        var p = props(); var a = service(p); var b = service(p);
        a.current(); b.current(); delivered.set(true);
        a.notifyPending(); b.notifyPending(); assertEquals(1, sends.get());
    }

    @Test void quietRenewalsDoNotEraseCumulativeMovementSinceTheLastDeliveredPlan() {
        var p = props(); var service = service(p); var first = service.current();
        delivered.set(true); service.notifyPending(); assertEquals(1, sends.get());
        Instant start = first.state().advice().asOf();
        for (int hour = 1; hour <= 9; hour++) {
            var next = input(start.plusSeconds(hour * 3600L), Integer.toString(84000 + hour * 100), "0.0008871693", List.of(), List.of());
            service.accept(engine.evaluate(next, Policy.defaults())); service.notifyPending();
            assertEquals(1, sends.get(), "Small expiry renewals must stay quiet");
        }
        // All individual moves are below threshold, but their sum crosses it.
        service.accept(engine.evaluate(input(start.plusSeconds(36000), "85000", "0.0008871693", List.of(), List.of()), Policy.defaults()));
        service.notifyPending(); assertEquals(2, sends.get());
    }

    @Test void monitorRefreshesBeforeSendingAndNeverPublishesAnOldExecutablePlanOnFailure() {
        var p = props(); var original = service(p); original.current(); delivered.set(true); original.notifyPending();
        var failing = new BtcOrderAdviceSnapshotReader(null, null, null, null) {
            @Override public Input read() { throw new IllegalStateException("offline"); }
        };
        var monitor = new BtcOrderAdviceService(failing, p, mapper, telegram());
        monitor.monitorOnce(); assertEquals(2, sends.get());
        assertEquals("DATA_UNAVAILABLE", monitor.current().state().advice().status());
    }

    @Test void generatesClearlyLabelledReviewExamplesFromTheRealFormatter() throws Exception {
        var first = engine.evaluate(input(), Policy.defaults());
        var moved = engine.evaluate(input(NOW.plusSeconds(300), "87000", "0.0008871693", List.of(), List.of()), Policy.defaults());
        var invalid = engine.evaluate(input(NOW.plusSeconds(600), "92000", "0.0008871693", List.of(), List.of()), Policy.defaults());
        var cases = List.of(first, moved, invalid);
        StringBuilder out = new StringBuilder("# BTC 掛單功能預覽\n\n**以下全部使用合成行情與示範持倉，僅展示功能，不是當前市場的下單建議。**\n\n");
        for (int i = 0; i < cases.size(); i++) {
            var a = cases.get(i);
            String change = BtcOrderAdviceService.changeReason(i == 0 ? null : cases.get(i - 1), a);
            var state = new BtcOrderAdviceService.State(VERSION, i + 1, a, change, i == 0 ? "無" : BtcOrderAdviceFormatter.orderSummary(cases.get(i - 1)), 0, true, "NOT_SENT");
            String text = BtcOrderAdviceFormatter.format(new BtcOrderAdviceService.View(a.asOf(), state, false, false));
            assertTrue(text.length() < 4096);
            out.append("## 情境 ").append(i + 1).append(i == 0 ? "：區間掛單" : i == 1 ? "：價格上移，更新條件" : "：突破原區間，撤回建議")
                    .append("\n\n```text\n").append(text).append("\n```\n\n");
        }
        Files.createDirectories(Path.of("target")); Files.writeString(Path.of("target/btc-order-advice-preview.md"), out.toString());
    }
}
