package com.agora.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Fixed-input, forward-only continuous data-plane producer. It never starts Spring. */
public final class OkxMicrostructureContinuousSourceCli {

    static final String ENDPOINT = OkxMicrostructureCollector.ENDPOINT;
    static final String INSTRUMENT = OkxMicrostructureCollector.INSTRUMENT;
    static final List<String> CHANNELS = List.of("trades", "books5");
    static final int REQUIRED_DAYS = 14;
    static final String PRODUCER_IDENTITY = "agora-evidence-source";
    static final String DAY_SCHEMA_SHA256 =
            "916525b47fcd7f8862522ca740bf987cbb5d5082237d94d8814087b8b3853fc1";
    static final String DIAGNOSTIC_CONTRACT_SHA256 =
            "b58ae60f76bcdb7c60114c0b076730225056e11ca5cfe604fe7415b4e41ffe6c";
    static final Path FIXED_BINDING_PATH =
            Path.of("/etc/agora-research/okx-microstructure-continuous-source-v1.json");
    static final Path PRIVATE_STAGING_ROOT =
            Path.of("/var/lib/agora-evidence-source/microstructure-private-staging");
    static final Path MICROSTRUCTURE_DROP_ROOT =
            Path.of("/var/lib/agora-evidence-source/microstructure-drop");

    private OkxMicrostructureContinuousSourceCli() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            requireNoArguments(args);
            Clock clock = Clock.systemUTC();
            SourceBinding binding = SourceBinding.loadFixed(clock);
            Producer producer = new Producer(
                    new ObjectMapper().findAndRegisterModules(),
                    clock,
                    new HttpWebSocketTransport(clock),
                    new OkxMicrostructureCanonicalDrop.FileDropSink(
                            PRIVATE_STAGING_ROOT,
                            MICROSTRUCTURE_DROP_ROOT),
                    binding);
            producer.run();
            if (producer.state() != ProducerState.COMPLETE) {
                throw new IllegalStateException(producer.blockedReason());
            }
        } catch (Exception error) {
            System.err.println("{\"status\":\"MICROSTRUCTURE_CONTINUOUS_SOURCE_BLOCKED\",\"detail\":\""
                    + jsonEscape(error.getMessage()) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static void requireNoArguments(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("continuous producer accepts no caller-selected inputs");
        }
    }

    record SourceBinding(
            LocalDate forwardStartDay,
            int requiredCompleteUtcDays,
            String diagnosticId,
            String producerReleaseId,
            String producerManifestSha256) {

        private static final Set<String> EXACT_KEYS = Set.of(
                "schema_version",
                "authorization",
                "forward_start_day",
                "required_complete_utc_days",
                "diagnostic_id",
                "source_contract_sha256",
                "day_schema_sha256",
                "diagnostic_contract_sha256",
                "producer_release_id",
                "producer_manifest_sha256");
        private static final String LOWER_SHA256 = "^[0-9a-f]{64}$";
        private static final String DIAGNOSTIC_ID_PATTERN = "^[a-z0-9][a-z0-9-]{2,79}$";

        static SourceBinding loadFixed(Clock clock) throws Exception {
            if (!Files.isRegularFile(FIXED_BINDING_PATH, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(FIXED_BINDING_PATH)) {
                throw new IllegalArgumentException("BINDING_MISSING_OR_SYMLINK");
            }
            return parse(Files.readAllBytes(FIXED_BINDING_PATH), clock);
        }

        static SourceBinding parse(byte[] rawBytes, Clock clock) throws Exception {
            ObjectMapper strictMapper = new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = strictMapper.readTree(rawBytes);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("BINDING_NOT_OBJECT");
            }
            Set<String> actualKeys = new HashSet<>();
            root.fieldNames().forEachRemaining(actualKeys::add);
            if (!actualKeys.equals(EXACT_KEYS)) {
                throw new IllegalArgumentException("BINDING_KEYS_MISMATCH");
            }
            if (!"1".equals(requiredText(root, "schema_version"))) {
                throw new IllegalArgumentException("BINDING_SCHEMA_MISMATCH");
            }
            if (!OkxMicrostructureCollector.AUTHORIZATION.equals(
                    requiredText(root, "authorization"))) {
                throw new IllegalArgumentException("BINDING_AUTHORIZATION_MISMATCH");
            }
            JsonNode requiredDaysNode = root.get("required_complete_utc_days");
            if (!requiredDaysNode.isIntegralNumber()
                    || !requiredDaysNode.canConvertToInt()
                    || requiredDaysNode.intValue() != REQUIRED_DAYS) {
                throw new IllegalArgumentException("BINDING_REQUIRED_DAYS_MISMATCH");
            }
            if (!OkxMicrostructureCanonicalDrop.SOURCE_CONTRACT_SHA256.equals(
                    requiredText(root, "source_contract_sha256"))) {
                throw new IllegalArgumentException("BINDING_SOURCE_CONTRACT_HASH_MISMATCH");
            }
            if (!DAY_SCHEMA_SHA256.equals(requiredText(root, "day_schema_sha256"))) {
                throw new IllegalArgumentException("BINDING_DAY_SCHEMA_HASH_MISMATCH");
            }
            if (!DIAGNOSTIC_CONTRACT_SHA256.equals(
                    requiredText(root, "diagnostic_contract_sha256"))) {
                throw new IllegalArgumentException("BINDING_DIAGNOSTIC_HASH_MISMATCH");
            }

            LocalDate startDay;
            try {
                startDay = LocalDate.parse(requiredText(root, "forward_start_day"));
            } catch (Exception error) {
                throw new IllegalArgumentException("BINDING_START_DAY_INVALID", error);
            }
            if (!startDay.isAfter(LocalDate.now(clock))) {
                throw new IllegalArgumentException("BINDING_START_DAY_NOT_STRICTLY_FUTURE");
            }
            String diagnosticId = requiredText(root, "diagnostic_id");
            if (!diagnosticId.matches(DIAGNOSTIC_ID_PATTERN)) {
                throw new IllegalArgumentException("BINDING_DIAGNOSTIC_ID_INVALID");
            }
            String releaseId = requiredText(root, "producer_release_id");
            if (releaseId.length() > 128 || !releaseId.equals(releaseId.strip())) {
                throw new IllegalArgumentException("BINDING_RELEASE_ID_INVALID");
            }
            String manifestSha256 = requiredText(root, "producer_manifest_sha256");
            if (!manifestSha256.matches(LOWER_SHA256)) {
                throw new IllegalArgumentException("BINDING_MANIFEST_HASH_INVALID");
            }
            return new SourceBinding(
                    startDay,
                    REQUIRED_DAYS,
                    diagnosticId,
                    releaseId,
                    manifestSha256);
        }

        private static String requiredText(JsonNode root, String field) {
            JsonNode value = root.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("BINDING_FIELD_INVALID: " + field);
            }
            return value.textValue();
        }
    }

    enum ProducerState {
        ARMED_FOR_FUTURE_START,
        CAPTURING,
        BLOCKED,
        COMPLETE
    }

    interface TransportListener {
        void onRaw(String raw);

        void onDisconnect(Instant at);

        void onReconnect(Instant at, boolean losslessIntervalProven);

        boolean accepting();
    }

    interface WebSocketTransport {
        void run(TransportListener listener) throws Exception;
    }

    static final class Producer implements TransportListener {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final WebSocketTransport transport;
        private final OkxMicrostructureCanonicalDrop.DropSink dropSink;
        private final SourceBinding binding;
        private final LocalDate startDay;
        private final Set<String> acknowledgements = new java.util.HashSet<>();
        private final List<OkxMicrostructureCanonicalDrop.DropDocuments> published = new ArrayList<>();
        private ProducerState state = ProducerState.ARMED_FOR_FUTURE_START;
        private OkxMicrostructureCollector collector;
        private OkxMicrostructureCollector.Continuity continuity =
                OkxMicrostructureCollector.Continuity.empty();
        private LocalDate activeDay;
        private LocalDate predecessorDay;
        private String predecessorBundleSha256;
        private Instant disconnectedAt;
        private String blockedReason;

        Producer(
                ObjectMapper mapper,
                Clock clock,
                WebSocketTransport transport,
                OkxMicrostructureCanonicalDrop.DropSink dropSink,
                SourceBinding binding) {
            this.mapper = mapper;
            this.clock = clock;
            this.transport = transport;
            this.dropSink = dropSink;
            this.binding = binding;
            this.startDay = binding.forwardStartDay();
        }

        void run() throws Exception {
            if (!startDay.isAfter(LocalDate.now(clock))) {
                block("BINDING_START_DAY_NOT_STRICTLY_FUTURE_AT_PROCESS_START");
                throw new IllegalStateException(blockedReason);
            }
            transport.run(this);
        }

        @Override
        public synchronized void onRaw(String raw) {
            requireAccepting();
            final OkxMicrostructureCollector.ParsedMessage message;
            try {
                message = OkxMicrostructureCollector.inspect(mapper, raw);
            } catch (Exception error) {
                block(error instanceof OkxMicrostructureCollector.CrossedBookException
                        ? "CROSSED_BOOK"
                        : "MALFORMED_MESSAGE");
                throw new IllegalStateException(blockedReason, error);
            }

            if (message.kind() == OkxMicrostructureCollector.MessageKind.EXCHANGE_ERROR) {
                block("EXCHANGE_ERROR");
                throw new IllegalStateException(blockedReason);
            }
            if (message.kind() == OkxMicrostructureCollector.MessageKind.OTHER_EVENT) {
                block("UNEXPECTED_EXCHANGE_EVENT");
                throw new IllegalStateException(blockedReason);
            }
            if (message.kind() == OkxMicrostructureCollector.MessageKind.ACKNOWLEDGEMENT) {
                acknowledgements.add(message.channel());
                if (collector != null) {
                    collector.acceptRaw(raw);
                }
                return;
            }
            if (message.day() == null) {
                block("EMPTY_STREAM_PAYLOAD");
                throw new IllegalStateException(blockedReason);
            }

            LocalDate messageDay = message.day();
            if (activeDay == null) {
                if (messageDay.isBefore(startDay)) {
                    return;
                }
                if (!messageDay.equals(startDay)) {
                    block("START_DAY_GAP");
                    throw new IllegalStateException(blockedReason);
                }
                beginDay(startDay);
            } else if (messageDay.isBefore(activeDay)) {
                block("TIMESTAMP_OR_DAY_REGRESSION");
                throw new IllegalStateException(blockedReason);
            } else if (messageDay.isAfter(activeDay)) {
                if (!messageDay.equals(activeDay.plusDays(1))) {
                    block("NONCONTIGUOUS_DAY");
                    throw new IllegalStateException(blockedReason);
                }
                publishActiveDay();
                if (published.size() == binding.requiredCompleteUtcDays()) {
                    state = ProducerState.COMPLETE;
                    return;
                }
                beginDay(messageDay);
            }

            collector.acceptRaw(raw);
            if (collector.anomalyCount() != 0) {
                block("INTEGRITY_NOT_CLEAN");
                throw new IllegalStateException(blockedReason);
            }
        }

        @Override
        public synchronized void onDisconnect(Instant at) {
            if (!accepting()) {
                return;
            }
            if (disconnectedAt != null) {
                block("RECONNECT_STATE_CONFLICT");
                return;
            }
            acknowledgements.clear();
            if (collector != null) {
                collector.clearAcknowledgements();
            }
            disconnectedAt = at;
        }

        @Override
        public synchronized void onReconnect(Instant at, boolean losslessIntervalProven) {
            if (!accepting()) {
                return;
            }
            if (disconnectedAt == null) {
                block("RECONNECT_WITHOUT_DISCONNECT");
                return;
            }
            if (state == ProducerState.CAPTURING
                    && (!losslessIntervalProven || at.isBefore(disconnectedAt))) {
                block("UNPROVED_RECONNECT_INTERVAL");
                return;
            }
            disconnectedAt = null;
        }

        synchronized void onRestartDetected() {
            if (state == ProducerState.CAPTURING) {
                block("ACTIVE_WINDOW_PROCESS_RESTART");
            }
        }

        @Override
        public synchronized boolean accepting() {
            return state != ProducerState.BLOCKED && state != ProducerState.COMPLETE;
        }

        synchronized ProducerState state() {
            return state;
        }

        synchronized String blockedReason() {
            return blockedReason == null ? "CONTINUOUS_SOURCE_ENDED_BEFORE_COMPLETE" : blockedReason;
        }

        synchronized List<OkxMicrostructureCanonicalDrop.DropDocuments> published() {
            return List.copyOf(published);
        }

        synchronized LocalDate activeDay() {
            return activeDay;
        }

        synchronized int activeMinuteCount() {
            return collector == null ? 0 : collector.minuteCount();
        }

        synchronized int acknowledgementCount() {
            return acknowledgements.size();
        }

        private void beginDay(LocalDate day) {
            activeDay = day;
            collector = new OkxMicrostructureCollector(mapper, continuity);
            collector.carryAcknowledgements(acknowledgements);
            state = ProducerState.CAPTURING;
        }

        private void publishActiveDay() {
            Instant dayEnd = activeDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant publishedAt = clock.instant();
            if (publishedAt.isBefore(dayEnd)) {
                block("CLOCK_PRECEDES_UTC_DAY_CLOSE");
                throw new IllegalStateException(blockedReason);
            }
            try {
                OkxMicrostructureCanonicalDrop.DropDocuments documents =
                        OkxMicrostructureCanonicalDrop.create(
                                collector.buildV2Payload(activeDay),
                                activeDay,
                                predecessorDay,
                                predecessorBundleSha256,
                                binding.diagnosticId(),
                                binding.producerReleaseId(),
                                binding.producerManifestSha256(),
                                publishedAt);
                dropSink.publish(documents);
                published.add(documents);
                predecessorDay = activeDay;
                predecessorBundleSha256 = documents.bundleSha256();
                continuity = collector.continuity();
            } catch (Exception error) {
                block("PUBLICATION_FAILED: " + error.getMessage());
                throw new IllegalStateException(blockedReason, error);
            }
        }

        private void requireAccepting() {
            if (!accepting()) {
                throw new IllegalStateException(blockedReason());
            }
        }

        private void block(String reason) {
            state = ProducerState.BLOCKED;
            blockedReason = reason;
        }
    }

    static final class HttpWebSocketTransport implements WebSocketTransport {
        private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
        private final Clock clock;

        HttpWebSocketTransport(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void run(TransportListener listener) throws Exception {
            boolean reconnect = false;
            while (listener.accepting()) {
                SessionListener session = new SessionListener(listener, clock, reconnect);
                HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                try {
                    client.newWebSocketBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .buildAsync(URI.create(ENDPOINT), session)
                            .orTimeout(20, TimeUnit.SECONDS)
                            .join();
                    session.completion().join();
                } catch (Exception error) {
                    session.fail(error);
                }
                reconnect = true;
            }
        }
    }

    private static final class SessionListener implements WebSocket.Listener {
        private final TransportListener listener;
        private final Clock clock;
        private final boolean reconnect;
        private final StringBuilder partialText = new StringBuilder();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private boolean disconnected;

        private SessionListener(TransportListener listener, Clock clock, boolean reconnect) {
            this.listener = listener;
            this.clock = clock;
            this.reconnect = reconnect;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (reconnect) {
                listener.onReconnect(clock.instant(), false);
                if (!listener.accepting()) {
                    webSocket.abort();
                    completion.complete(null);
                    return;
                }
            }
            webSocket.request(1);
            webSocket.sendText(OkxMicrostructureCollector.SUBSCRIBE_MESSAGE, true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                try {
                    listener.onRaw(partialText.toString());
                } catch (RuntimeException error) {
                    webSocket.abort();
                    completion.completeExceptionally(error);
                    return CompletableFuture.failedFuture(error);
                } finally {
                    partialText.setLength(0);
                }
            }
            if (listener.accepting()) {
                webSocket.request(1);
            } else {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "capture complete");
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnectOnce();
            completion.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnectOnce();
            completion.completeExceptionally(error);
        }

        CompletableFuture<Void> completion() {
            return completion;
        }

        void fail(Throwable error) {
            disconnectOnce();
            completion.completeExceptionally(error);
        }

        private synchronized void disconnectOnce() {
            if (!disconnected) {
                disconnected = true;
                listener.onDisconnect(clock.instant());
            }
        }
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
