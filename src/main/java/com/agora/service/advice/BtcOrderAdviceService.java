package com.agora.service.advice;

import com.agora.config.BtcOrderAdviceProperties;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.time.Instant;
import java.util.Objects;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;

@Slf4j
@Service
public class BtcOrderAdviceService {
    public record State(String schema, long revision, Advice advice, String change, String previousPlan,
                        long deliveredRevision, boolean pendingNotification, String deliveryStatus, Advice deliveredAdvice) {
        public State(String schema, long revision, Advice advice, String change, String previousPlan,
                     long deliveredRevision, boolean pendingNotification, String deliveryStatus) {
            this(schema, revision, advice, change, previousPlan, deliveredRevision, pendingNotification, deliveryStatus, null);
        }
    }
    public record View(Instant checkedAt, State state, boolean monitorEnabled, boolean notificationsEnabled, Market latestMarket) {
        public View(Instant checkedAt, State state, boolean monitorEnabled, boolean notificationsEnabled) {
            this(checkedAt, state, monitorEnabled, notificationsEnabled, state.advice().market());
        }
    }
    private final BtcOrderAdviceSnapshotReader reader;
    private final BtcOrderAdviceProperties properties;
    private final ObjectMapper mapper;
    private final TelegramService telegram;
    private final BtcOrderAdviceEngine engine = new BtcOrderAdviceEngine();
    private final Path path;
    private State state;
    private boolean loaded;

    public BtcOrderAdviceService(BtcOrderAdviceSnapshotReader reader, BtcOrderAdviceProperties properties,
                                ObjectMapper mapper, TelegramService telegram) {
        this.reader = reader; this.properties = properties; this.mapper = mapper; this.telegram = telegram;
        this.path = Path.of(properties.getStateFile()).toAbsolutePath();
    }

    /** On-demand reads never send a message and never touch an exchange write path. */
    public synchronized View current() {
        return withStateLock(this::refresh);
    }

    public synchronized void monitorOnce() {
        if (!properties.isMonitorEnabled()) return;
        withStateLock(() -> { refresh(); notifyPendingInternal(); return null; });
    }

    private View refresh() {
        ensureLoaded();
        Advice candidate;
        try { candidate = engine.evaluate(reader.read(), properties.policy()); }
        catch (RuntimeException e) {
            // Provider exceptions can contain request details. Keep reports and logs credential-free.
            log.warn("[BtcOrderAdvice] fresh input unavailable ({})", e.getClass().getSimpleName());
            candidate = engine.unavailable(Instant.now(), properties.policy(), "最新行情、持倉或掛單資料不完整；舊建議暫停使用");
        }
        accept(candidate);
        return new View(Instant.now(), state, properties.isMonitorEnabled(), properties.isNotificationsEnabled(), candidate.market());
    }

    synchronized void accept(Advice candidate) {
        String change = changeReason(state == null ? null : state.advice(), candidate);
        Advice delivered = state == null ? null : state.deliveredAdvice();
        Advice recipientBaseline = delivered != null ? delivered : state == null ? null : state.advice();
        String deliveredChange = changeReason(recipientBaseline, candidate);
        boolean materialToRecipient = deliveredChange != null && !"VALIDITY_REFRESH".equals(deliveredChange);
        if (change == null && !materialToRecipient) return;
        if ((change == null || "VALIDITY_REFRESH".equals(change)) && materialToRecipient) change = deliveredChange;
        boolean notify = materialToRecipient || (state != null && state.pendingNotification());
        Advice previous = delivered != null && notify ? delivered : state == null ? null : state.advice();
        State updated = new State(VERSION, state == null ? 1 : state.revision() + 1, candidate, change,
                previous == null ? "無" : BtcOrderAdviceFormatter.orderSummary(previous),
                state == null ? 0 : state.deliveredRevision(), notify, notify ? "PENDING" : "NO_MATERIAL_CHANGE", delivered);
        persist(updated);
        state = updated;
    }

    /** Called only by the explicitly enabled monitor, after a fresh current() check. */
    synchronized void notifyPending() {
        withStateLock(() -> { ensureLoaded(); notifyPendingInternal(); return null; });
    }

    private void notifyPendingInternal() {
        if (!properties.isMonitorEnabled() || !properties.isNotificationsEnabled() || state == null || !state.pendingNotification()) return;
        if (!Instant.now().isBefore(state.advice().validUntil())) return;
        boolean accepted;
        try { accepted = telegram.sendBtcOrderAdvice(BtcOrderAdviceFormatter.format(new View(Instant.now(), state, true, true))); }
        catch (RuntimeException e) { accepted = false; }
        State updated = new State(VERSION, state.revision(), state.advice(), state.change(), state.previousPlan(),
                accepted ? state.revision() : state.deliveredRevision(), !accepted,
                accepted ? "TELEGRAM_ACCEPTED" : "DELIVERY_PENDING_RETRY", accepted ? state.advice() : state.deliveredAdvice());
        persist(updated);
        state = updated;
    }

    static String changeReason(Advice old, Advice next) {
        if (old == null) return "INITIAL";
        if (!Objects.equals(old.status(), next.status()) || !Objects.equals(old.reason(), next.reason())) return "STATUS_CHANGED";
        if (!Objects.equals(old.policy(), next.policy())) return "POLICY_CHANGED";
        if (!Objects.equals(old.inventoryKey(), next.inventoryKey())) return "ACCOUNT_OR_ORDERS_CHANGED";
        if (!Objects.equals(old.regime(), next.regime())) return "MARKET_REGIME_CHANGED";
        if (old.market() != null && next.market() != null) {
            BigDecimal threshold = old.market().atr().multiply(new BigDecimal("0.25"))
                    .max(old.market().last().multiply(new BigDecimal("0.0025")));
            if (next.market().last().compareTo(old.market().invalidateBelow()) < 0
                    || next.market().last().compareTo(old.market().invalidateAbove()) > 0) return "PRICE_INVALIDATED";
            if (next.market().atr().subtract(old.market().atr()).abs().compareTo(old.market().atr().multiply(new BigDecimal("0.25"))) > 0)
                return "VOLATILITY_CHANGED";
            if (old.orders().size() != next.orders().size()) return "ORDER_PLAN_CHANGED";
            for (int i = 0; i < old.orders().size(); i++) {
                Leg a = old.orders().get(i), b = next.orders().get(i);
                if (("SELL".equals(a.side()) && next.market().last().compareTo(a.price()) >= 0)
                        || ("BUY".equals(a.side()) && next.market().last().compareTo(a.price()) <= 0)) return "ORDER_PRICE_REACHED";
                if (a.quantity().subtract(b.quantity()).abs().compareTo(a.quantity().multiply(new BigDecimal("0.05"))) > 0
                        || a.price().subtract(b.price()).abs().compareTo(threshold) >= 0)
                    return "ORDER_LEVEL_CHANGED";
            }
        }
        if (!next.asOf().isBefore(old.validUntil())) return "VALIDITY_REFRESH";
        return null;
    }

    private void ensureLoaded() {
        if (loaded) return;
        if (Files.exists(path)) {
            try {
                State restored = mapper.readValue(path.toFile(), State.class);
                if (!VERSION.equals(restored.schema()) || restored.revision() < 1 || restored.advice() == null
                        || restored.advice().validUntil() == null) throw new IOException("Invalid advice state");
                state = restored;
            } catch (IOException e) { throw new IllegalStateException("BTC advice state cannot be read; inspect before replacing it"); }
        }
        loaded = true;
    }

    private <T> T withStateLock(java.util.function.Supplier<T> work) {
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("BTC advice is being refreshed by another process");
                loaded = false; state = null; // consume the latest durable state after a blue/green overlap
                return work.get();
            }
        } catch (IOException | OverlappingFileLockException e) {
            throw new IllegalStateException("BTC advice state is unavailable or locked");
        }
    }
    private void persist(State value) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "btc-advice-", ".tmp");
            mapper.writeValue(temporary.toFile(), value);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new IllegalStateException("BTC advice state could not be saved atomically"); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* isolated temporary file */ }
        }
    }
}
