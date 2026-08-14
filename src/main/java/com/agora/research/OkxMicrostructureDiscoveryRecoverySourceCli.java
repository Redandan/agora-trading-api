package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fixed-input V3R1 source coordinator. It is an offline Java CLI, never a Spring bean. */
public final class OkxMicrostructureDiscoveryRecoverySourceCli {

    static final Path FIXED_BINDING_PATH = Path.of(
            "/etc/agora-research/okx-microstructure-continuous-source-v3r1.json");
    static final Path PRIVATE_STAGING_ROOT = Path.of(
            "/var/lib/agora-evidence-source/microstructure-v3r1-private-staging");
    static final Path DROP_ROOT = Path.of(
            "/var/lib/agora-evidence-source/microstructure-v3r1-drop");
    private static final Set<String> EXACT_BINDING_KEYS = Set.of(
            "schema_version", "authorization", "generation_id", "diagnostic_id",
            "recovery_contract_sha256", "v3_day_schema_sha256",
            "v3_diagnostic_contract_sha256", "complete_envelope_schema_sha256",
            "rejection_envelope_schema_sha256", "intake_state_schema_sha256",
            "producer_release_id", "producer_manifest_sha256", "start_day", "end_day",
            "calendar_day_budget", "required_consecutive_complete_days", "selection_rule");
    private static final String DAY_SCHEMA_SHA256 =
            "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709";
    private static final String DIAGNOSTIC_CONTRACT_SHA256 =
            "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a";
    private static final String COMPLETE_SCHEMA_SHA256 =
            "a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2";
    private static final String REJECTION_SCHEMA_SHA256 =
            "833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497";
    private static final String INTAKE_STATE_SCHEMA_SHA256 =
            "12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d";

    private OkxMicrostructureDiscoveryRecoverySourceCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            requireNoArguments(args);
            Clock clock = Clock.systemUTC();
            var binding = loadFixedBinding();
            var host = HostContext.readLinux(clock);
            var producer = new Producer(
                    strictMapper(),
                    clock,
                    binding,
                    host,
                    new FileCheckpointAccess(
                            new OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Store(
                                    OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.FIXED_ROOT)),
                    new OkxMicrostructureDiscoveryRecoveryDropV3R1.FileDropSink(
                            PRIVATE_STAGING_ROOT, DROP_ROOT));
            producer.initialize();
            if (producer.accepting()) {
                new OkxMicrostructureContinuousSourceCli.HttpWebSocketTransport(clock)
                        .run(producer);
            }
            if (producer.state() == ProducerState.BLOCKED) {
                throw new IllegalStateException(producer.blockedReason());
            }
        } catch (Exception error) {
            System.err.println("{\"status\":\"MICROSTRUCTURE_DISCOVERY_RECOVERY_SOURCE_BLOCKED\","
                    + "\"detail\":\"" + jsonEscape(error.getMessage()) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static void requireNoArguments(String[] args) {
        if (args == null || args.length != 0) {
            throw new IllegalArgumentException("V3R1 source accepts no caller-selected inputs");
        }
    }

    static OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding loadFixedBinding() throws Exception {
        if (!Files.isRegularFile(FIXED_BINDING_PATH, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(FIXED_BINDING_PATH)) {
            throw new IllegalArgumentException("BINDING_MISSING_OR_SYMLINK");
        }
        return parseBinding(Files.readAllBytes(FIXED_BINDING_PATH));
    }

    static OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding parseBinding(byte[] bytes)
            throws Exception {
        JsonNode root = strictMapper().readValue(bytes, JsonNode.class);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("BINDING_NOT_OBJECT");
        }
        Set<String> keys = new HashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(EXACT_BINDING_KEYS)
                || !"OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1".equals(
                requiredText(root, "schema_version"))
                || !OkxMicrostructureDiscoveryRecoveryDropV3R1.AUTHORIZATION.equals(
                requiredText(root, "authorization"))
                || !OkxMicrostructureDiscoveryRecoveryDropV3R1.RECOVERY_CONTRACT_SHA256.equals(
                requiredText(root, "recovery_contract_sha256"))
                || !DAY_SCHEMA_SHA256.equals(requiredText(root, "v3_day_schema_sha256"))
                || !DIAGNOSTIC_CONTRACT_SHA256.equals(
                requiredText(root, "v3_diagnostic_contract_sha256"))
                || !COMPLETE_SCHEMA_SHA256.equals(
                requiredText(root, "complete_envelope_schema_sha256"))
                || !REJECTION_SCHEMA_SHA256.equals(
                requiredText(root, "rejection_envelope_schema_sha256"))
                || !INTAKE_STATE_SCHEMA_SHA256.equals(
                requiredText(root, "intake_state_schema_sha256"))
                || requiredInt(root, "calendar_day_budget") != 42
                || requiredInt(root, "required_consecutive_complete_days") != 14
                || !"FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK".equals(
                requiredText(root, "selection_rule"))) {
            throw new IllegalArgumentException("BINDING_CONTRACT_MISMATCH");
        }
        LocalDate start = LocalDate.parse(requiredText(root, "start_day"));
        LocalDate end = LocalDate.parse(requiredText(root, "end_day"));
        return new OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding(
                requiredText(root, "generation_id"),
                requiredText(root, "diagnostic_id"),
                requiredText(root, "producer_release_id"),
                requiredText(root, "producer_manifest_sha256"),
                start,
                end);
    }

    enum ProducerState {
        NOT_INITIALIZED,
        ARMED_OR_BETWEEN_DAYS,
        ACTIVE_DAY,
        TERMINAL,
        BLOCKED
    }

    interface CheckpointAccess {
        OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot load(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding) throws Exception;

        void save(
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot expectedPrevious,
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot next) throws Exception;
    }

    private record FileCheckpointAccess(
            OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Store store)
            implements CheckpointAccess {
        @Override
        public OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot load(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding) throws Exception {
            return store.loadAndRecover(binding);
        }

        @Override
        public void save(
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot expectedPrevious,
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot next) throws Exception {
            store.save(expectedPrevious, next);
        }
    }

    record HostContext(String bootId, Instant hostStartedAt) {
        HostContext {
            if (bootId == null || bootId.isBlank() || hostStartedAt == null) {
                throw new IllegalArgumentException("HOST_CONTEXT_INVALID");
            }
        }

        static HostContext readLinux(Clock clock) throws Exception {
            String bootId = Files.readString(
                    Path.of("/proc/sys/kernel/random/boot_id"), StandardCharsets.US_ASCII).strip();
            String uptimeText = Files.readString(
                    Path.of("/proc/uptime"), StandardCharsets.US_ASCII).strip();
            String first = uptimeText.split("\\s+", 2)[0];
            long uptimeMillis = new java.math.BigDecimal(first)
                    .multiply(java.math.BigDecimal.valueOf(1_000))
                    .longValueExact();
            return new HostContext(bootId, clock.instant().minusMillis(uptimeMillis));
        }
    }

    static final class Producer implements OkxMicrostructureContinuousSourceCli.TransportListener {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding;
        private final HostContext host;
        private final CheckpointAccess checkpointStore;
        private final OkxMicrostructureDiscoveryRecoveryDropV3R1.DropSink dropSink;
        private final Set<String> acknowledgements = new HashSet<>();
        private ProducerState state = ProducerState.NOT_INITIALIZED;
        private OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot checkpoint;
        private OkxMicrostructureCollector collector;
        private OkxMicrostructureCollector.Continuity continuity =
                OkxMicrostructureCollector.Continuity.empty();
        private LocalDate activeDay;
        private Instant readySince;
        private Instant disconnectedAt;
        private Instant lastObservedAt;
        private Instant latestDataInstant;
        private Instant activeStartedAt;
        private String rawArrivalChain =
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
        private String controlEventChain =
                OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
        private long dataMessageCount;
        private long controlEventCount;
        private int lastCheckpointMinuteCount = -1;
        private boolean awaitingNoticeDisconnect;
        private String blockedReason;

        Producer(
                ObjectMapper mapper,
                Clock clock,
                OkxMicrostructureDiscoveryRecoveryDropV3R1.Binding binding,
                HostContext host,
                CheckpointAccess checkpointStore,
                OkxMicrostructureDiscoveryRecoveryDropV3R1.DropSink dropSink) {
            if (mapper == null || clock == null || binding == null || host == null
                    || checkpointStore == null || dropSink == null) {
                throw new IllegalArgumentException("SOURCE_DEPENDENCY_MISSING");
            }
            this.mapper = mapper.copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            this.clock = clock;
            this.binding = binding;
            this.host = host;
            this.checkpointStore = checkpointStore;
            this.dropSink = dropSink;
        }

        synchronized void initialize() throws Exception {
            if (state != ProducerState.NOT_INITIALIZED) {
                throw new IllegalStateException("SOURCE_ALREADY_INITIALIZED");
            }
            Instant processStartedAt = clock.instant();
            checkpoint = checkpointStore.load(binding);
            if (checkpoint == null) {
                if (!binding.startDay().isAfter(
                        processStartedAt.atZone(ZoneOffset.UTC).toLocalDate())) {
                    throw new IllegalStateException("NEW_BINDING_START_NOT_STRICTLY_FUTURE");
                }
                checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.initial(
                        binding, host.bootId(), processStartedAt);
                checkpointStore.save(null, checkpoint);
            }
            if (checkpoint.pendingPublication() != null) {
                completePendingPublication();
            }
            for (var planned : OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.restartPlan(
                    checkpoint, processStartedAt, host.hostStartedAt(), host.bootId())) {
                publishRejection(
                        planned.day(),
                        planned.reason(),
                        planned.observation(),
                        planned.sanitizedControlEvent(),
                        planned.rejectedAt());
            }
            state = checkpoint.phase()
                    == OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.TERMINAL
                    ? ProducerState.TERMINAL : ProducerState.ARMED_OR_BETWEEN_DAYS;
        }

        @Override
        public synchronized void onRaw(String raw) {
            requireAccepting();
            if (raw == null || raw.isEmpty()) {
                block("EMPTY_RAW_MESSAGE");
                throw new IllegalStateException(blockedReason);
            }
            try {
                JsonNode root = mapper.readValue(raw, JsonNode.class);
                if (root == null || !root.isObject()) {
                    throw new IllegalArgumentException("raw message is not an object");
                }
                if (root.has("event")) {
                    handleControl(raw);
                } else {
                    handleData(raw);
                }
            } catch (RuntimeException error) {
                if (state != ProducerState.BLOCKED) {
                    block(error instanceof OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException
                            ? ((OkxMicrostructureDiscoveryRecoveryV3R1.BlockedException) error).code()
                            : "SOURCE_MESSAGE_INTEGRITY_FAILURE");
                }
                throw new IllegalStateException(blockedReason, error);
            } catch (Exception error) {
                block("SOURCE_PERSISTENCE_FAILURE");
                throw new IllegalStateException(blockedReason, error);
            }
        }

        private void handleControl(String raw) throws Exception {
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            var classification = OkxMicrostructureDiscoveryRecoveryV3R1.classify(bytes);
            if (state == ProducerState.ACTIVE_DAY) {
                observeRaw(bytes, true, clock.instant());
            }
            switch (classification.action()) {
                case ACKNOWLEDGE -> {
                    if (awaitingNoticeDisconnect) {
                        throw new IllegalStateException("ACK_BEFORE_NOTICE_DISCONNECT");
                    }
                    acknowledgements.add(classification.channel());
                    if (acknowledgements.containsAll(
                            OkxMicrostructureDiscoveryRecoveryV3R1.CHANNELS)
                            && readySince == null) {
                        readySince = clock.instant();
                    }
                    if (state == ProducerState.ACTIVE_DAY) {
                        checkpointActive(true);
                    }
                }
                case SEAL_CONTROL_EVENT_AND_CONTINUE -> {
                    if (state == ProducerState.ACTIVE_DAY) {
                        checkpointActive(true);
                    }
                }
                case REJECT_ACTIVE_DAY -> {
                    awaitingNoticeDisconnect = true;
                    if (state == ProducerState.ACTIVE_DAY) {
                        checkpointActive(true);
                        var sanitized = new OkxMicrostructureDiscoveryRecoveryDropV3R1
                                .SanitizedControlEvent(
                                classification.sanitizedControlEvent().event(),
                                classification.sanitizedControlEvent().code());
                        var previous = checkpoint;
                        checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1
                                .pendingRejection(
                                checkpoint, classification.rejectionReason(), sanitized,
                                clock.instant());
                        checkpointStore.save(previous, checkpoint);
                    }
                    if (state == ProducerState.ACTIVE_DAY) {
                        publishPendingRejection(clock.instant());
                    } else {
                        acknowledgements.clear();
                        readySince = null;
                        continuity = OkxMicrostructureCollector.Continuity.empty();
                    }
                }
            }
        }

        private void handleData(String raw) throws Exception {
            if (awaitingNoticeDisconnect) {
                throw new IllegalStateException("DATA_AFTER_UPGRADE_NOTICE");
            }
            OkxMicrostructureCollector.ParsedMessage message =
                    OkxMicrostructureCollector.inspect(mapper, raw);
            if (message.kind() != OkxMicrostructureCollector.MessageKind.TRADES
                    && message.kind() != OkxMicrostructureCollector.MessageKind.BOOKS5) {
                throw new IllegalArgumentException("NON_DATA_MESSAGE_ON_DATA_PATH");
            }
            LocalDate messageDay = message.day();
            Instant messageInstant = message.latestDataInstant();
            if (messageDay == null || messageInstant == null) {
                throw new IllegalArgumentException("EMPTY_DATA_MESSAGE");
            }
            if (state == ProducerState.ACTIVE_DAY && messageDay.isBefore(activeDay)) {
                throw new IllegalStateException("MESSAGE_DAY_REGRESSION");
            }

            if (state == ProducerState.ACTIVE_DAY && messageDay.isAfter(activeDay)) {
                if (!messageDay.equals(activeDay.plusDays(1))) {
                    throw new IllegalStateException("NONCONTIGUOUS_MESSAGE_DAY");
                }
                publishActiveCompleteDay(clock.instant());
            }
            boolean collect = prepareForDay(messageDay, clock.instant());
            if (!collect) {
                warmContinuity(raw);
                return;
            }
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            observeRaw(bytes, false, clock.instant());
            collector.acceptRaw(raw);
            if (collector.anomalyCount() != 0) {
                throw new IllegalStateException("MARKET_INTEGRITY_FAILURE");
            }
            String v3Failure = collector.v3IntegrityFailureReason();
            if (v3Failure != null) {
                throw new IllegalStateException(v3Failure);
            }
            latestDataInstant = messageInstant;
            int completed = collector.completedMinuteCountBefore(messageInstant);
            if (completed != lastCheckpointMinuteCount) {
                checkpointActive(false);
                lastCheckpointMinuteCount = completed;
            }
        }

        private boolean prepareForDay(LocalDate messageDay, Instant at) throws Exception {
            if (state == ProducerState.TERMINAL) {
                return false;
            }
            LocalDate expected = nextCalendarDay();
            if (messageDay.isBefore(expected)) {
                return false;
            }
            while (expected.isBefore(messageDay) && !isTerminal()) {
                publishEmptyRejection(
                        expected, "DUAL_CHANNEL_NOT_READY_AT_DAY_START", at);
                expected = nextCalendarDay();
            }
            if (isTerminal() || messageDay.isBefore(nextCalendarDay())) {
                return false;
            }
            if (!messageDay.equals(nextCalendarDay())) {
                throw new IllegalStateException("MESSAGE_DAY_OUTSIDE_FROZEN_CALENDAR");
            }
            if (state == ProducerState.ACTIVE_DAY) {
                return messageDay.equals(activeDay);
            }
            Instant dayStart = messageDay.atStartOfDay().toInstant(ZoneOffset.UTC);
            if (readySince == null || !readySince.isBefore(dayStart)
                    || !acknowledgements.containsAll(
                    OkxMicrostructureDiscoveryRecoveryV3R1.CHANNELS)) {
                publishEmptyRejection(
                        messageDay, "DUAL_CHANNEL_NOT_READY_AT_DAY_START", at);
                return false;
            }
            beginActiveDay(messageDay, at);
            return true;
        }

        private void beginActiveDay(LocalDate day, Instant observedAt) throws Exception {
            activeDay = day;
            activeStartedAt = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            lastObservedAt = observedAt;
            latestDataInstant = null;
            rawArrivalChain = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
            controlEventChain = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
            dataMessageCount = 0;
            controlEventCount = 0;
            lastCheckpointMinuteCount = -1;
            collector = new OkxMicrostructureCollector(mapper, continuity);
            collector.carryAcknowledgements(acknowledgements);
            state = ProducerState.ACTIVE_DAY;
        }

        private void publishActiveCompleteDay(Instant publishedAt) throws Exception {
            var documents = OkxMicrostructureDiscoveryRecoveryDropV3R1.complete(
                    collector.buildV3Payload(activeDay), binding, activeDay, publishedAt);
            prepareAndCommit(documents, publishedAt);
            continuity = collector.continuity();
            clearActive();
            state = isTerminal()
                    ? ProducerState.TERMINAL : ProducerState.ARMED_OR_BETWEEN_DAYS;
        }

        private void publishEmptyRejection(LocalDate day, String reason, Instant at)
                throws Exception {
            publishRejection(
                    day,
                    reason,
                    new OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation(
                            null, null, List.of(), 0, 0, 0,
                            OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256,
                            OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256),
                    null,
                    at);
        }

        private void publishPendingRejection(Instant at) throws Exception {
            publishRejection(
                    checkpoint.activeDay(),
                    checkpoint.pendingRejectionReason(),
                    checkpoint.observation(),
                    checkpoint.pendingSanitizedControlEvent(),
                    at);
        }

        private void publishRejection(
                LocalDate day,
                String reason,
                OkxMicrostructureDiscoveryRecoveryDropV3R1.RejectionObservation observation,
                OkxMicrostructureDiscoveryRecoveryDropV3R1.SanitizedControlEvent sanitized,
                Instant at) throws Exception {
            var documents = OkxMicrostructureDiscoveryRecoveryDropV3R1.rejection(
                    binding, day, reason, observation, sanitized, at);
            prepareAndCommit(documents, at);
            clearActive();
            state = isTerminal()
                    ? ProducerState.TERMINAL : ProducerState.ARMED_OR_BETWEEN_DAYS;
        }

        private void prepareAndCommit(
                OkxMicrostructureDiscoveryRecoveryDropV3R1.DispositionDocuments documents,
                Instant at) throws Exception {
            dropSink.prepare(documents);
            var previous = checkpoint;
            checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.pendingPublication(
                    checkpoint, documents.pending(at), at);
            checkpointStore.save(previous, checkpoint);
            completePendingPublication();
        }

        private void completePendingPublication() throws Exception {
            var pending = checkpoint.pendingPublication();
            if (pending == null) {
                throw new IllegalStateException("PENDING_PUBLICATION_MISSING");
            }
            dropSink.commit(pending);
            var previous = checkpoint;
            checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.afterDisposition(
                    checkpoint, host.bootId(), pending.day(), pending.kind(),
                    pending.envelopeSha256(), pending.dispositionAt());
            checkpointStore.save(previous, checkpoint);
        }

        private void observeRaw(byte[] bytes, boolean control, Instant at) {
            if (lastObservedAt != null && at.isBefore(lastObservedAt)) {
                throw new IllegalStateException("OBSERVATION_CLOCK_REGRESSION");
            }
            rawArrivalChain = OkxMicrostructureDiscoveryRecoveryV3R1.nextRawArrivalChain(
                    rawArrivalChain, bytes);
            if (control) {
                controlEventChain = OkxMicrostructureDiscoveryRecoveryV3R1
                        .nextControlEventChain(controlEventChain, bytes);
                controlEventCount++;
            } else {
                dataMessageCount++;
            }
            lastObservedAt = at;
        }

        private void checkpointActive(boolean force) throws Exception {
            if (state != ProducerState.ACTIVE_DAY) {
                return;
            }
            int completed = latestDataInstant == null
                    ? 0 : collector.completedMinuteCountBefore(latestDataInstant);
            if (!force && completed == lastCheckpointMinuteCount) {
                return;
            }
            var previous = checkpoint;
            checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.active(
                    checkpoint,
                    host.bootId(),
                    activeDay,
                    activeStartedAt,
                    lastObservedAt,
                    acknowledgements.stream().sorted().toList(),
                    Math.min(completed, 1439),
                    dataMessageCount,
                    controlEventCount,
                    rawArrivalChain,
                    controlEventChain,
                    clock.instant());
            checkpointStore.save(previous, checkpoint);
        }

        private void warmContinuity(String raw) {
            OkxMicrostructureCollector warmup = new OkxMicrostructureCollector(mapper, continuity);
            warmup.carryAcknowledgements(acknowledgements);
            warmup.acceptRaw(raw);
            if (warmup.anomalyCount() != 0 || warmup.unresolvedV3TradeOverflowed()) {
                throw new IllegalStateException("WARMUP_INTEGRITY_FAILURE");
            }
            continuity = warmup.continuity();
        }

        @Override
        public synchronized void onDisconnect(Instant at) {
            if (!accepting()) {
                return;
            }
            try {
                if (disconnectedAt != null) {
                    throw new IllegalStateException("DUPLICATE_DISCONNECT");
                }
                disconnectedAt = at;
                if (state == ProducerState.ACTIVE_DAY) {
                    checkpointActive(true);
                    Instant rejectionAt = at.isBefore(checkpoint.updatedAt())
                            ? checkpoint.updatedAt() : at;
                    var previous = checkpoint;
                    checkpoint = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1
                            .pendingRejection(
                            checkpoint, "TRANSPORT_DISCONNECT_UNPROVED_GAP", null,
                            rejectionAt);
                    checkpointStore.save(previous, checkpoint);
                    publishPendingRejection(rejectionAt);
                }
                acknowledgements.clear();
                readySince = null;
                continuity = OkxMicrostructureCollector.Continuity.empty();
                awaitingNoticeDisconnect = false;
            } catch (Exception error) {
                block("DISCONNECT_REJECTION_FAILED");
            }
        }

        @Override
        public synchronized void onReconnect(Instant at, boolean losslessIntervalProven) {
            if (!accepting()) {
                return;
            }
            if (disconnectedAt == null || at.isBefore(disconnectedAt)
                    || state == ProducerState.ACTIVE_DAY) {
                block("RECONNECT_STATE_CONFLICT");
                return;
            }
            disconnectedAt = null;
        }

        @Override
        public synchronized boolean accepting() {
            return state != ProducerState.TERMINAL && state != ProducerState.BLOCKED;
        }

        synchronized ProducerState state() {
            return state;
        }

        synchronized String blockedReason() {
            return blockedReason == null ? "SOURCE_ENDED_WITHOUT_TERMINAL_STATE" : blockedReason;
        }

        synchronized OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Snapshot checkpoint() {
            return checkpoint;
        }

        private LocalDate nextCalendarDay() {
            return checkpoint.lastDispositionDay() == null
                    ? binding.startDay() : checkpoint.lastDispositionDay().plusDays(1);
        }

        private boolean isTerminal() {
            return checkpoint.phase()
                    == OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.Phase.TERMINAL;
        }

        private void clearActive() {
            collector = null;
            activeDay = null;
            activeStartedAt = null;
            lastObservedAt = null;
            latestDataInstant = null;
            rawArrivalChain = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
            controlEventChain = OkxMicrostructureDiscoveryRecoveryCheckpointV3R1.ZERO_SHA256;
            dataMessageCount = 0;
            controlEventCount = 0;
            lastCheckpointMinuteCount = -1;
        }

        private void requireAccepting() {
            if (!accepting() || state == ProducerState.NOT_INITIALIZED) {
                throw new IllegalStateException(blockedReason());
            }
        }

        private void block(String reason) {
            state = ProducerState.BLOCKED;
            blockedReason = reason;
        }
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .findAndRegisterModules();
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("BINDING_FIELD_INVALID: " + field);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("BINDING_FIELD_INVALID: " + field);
        }
        return value.intValue();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
