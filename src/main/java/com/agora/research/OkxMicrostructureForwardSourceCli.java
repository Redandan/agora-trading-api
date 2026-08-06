package com.agora.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, forward-only OKX public market-data capture for offline research.
 *
 * <p>This CLI never starts Spring and has no credential, database, scheduler,
 * repository, notification, or order dependency. It accepts no symbol,
 * channel, endpoint, or backfill argument: the source is frozen to public
 * BTC-USDT {@code trades} and {@code books5} messages.</p>
 */
public final class OkxMicrostructureForwardSourceCli {

    static final String SCHEMA_VERSION = "OKX_MICROSTRUCTURE_FORWARD_BUNDLE_V1";
    static final String AUTHORIZATION = OkxMicrostructureCollector.AUTHORIZATION;
    static final String ENDPOINT = OkxMicrostructureCollector.ENDPOINT;
    static final String INSTRUMENT = OkxMicrostructureCollector.INSTRUMENT;
    static final int MIN_DURATION_SECONDS = 5;
    static final int MAX_DURATION_SECONDS = 86_400;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final String SUBSCRIBE_MESSAGE = OkxMicrostructureCollector.SUBSCRIBE_MESSAGE;

    private final ObjectMapper mapper;

    private OkxMicrostructureForwardSourceCli() {
        mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new OkxMicrostructureForwardSourceCli().run(args);
        } catch (Exception error) {
            System.err.println("{\"status\":\"OKX_FORWARD_CAPTURE_ERROR\",\"detail\":\""
                    + jsonEscape(error.getMessage()) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private int run(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path output = arguments.output().toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("OUTPUT_SEAL_REJECT: " + output);
        }

        CollectorState state = new CollectorState(mapper);
        CaptureListener listener = new CaptureListener(state);
        Instant captureStartedAt = Instant.now();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(ENDPOINT), listener)
                .join();

        listener.awaitCapture(arguments.durationSeconds());
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bounded capture complete")
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ignored -> null)
                .join();
        listener.awaitClose(5);
        Instant captureEndedAt = Instant.now();

        Map<String, Object> bundle = state.buildBundle(
                captureStartedAt,
                captureEndedAt,
                arguments.durationSeconds(),
                listener.errorDetail());
        String status = String.valueOf(bundle.get("status"));
        ObjectMapper sealMapper = mapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Map<String, Object> seal = Map.of(
                "algorithm", "SHA-256",
                "payload_sha256", sha256(sealMapper.writeValueAsBytes(bundle)),
                "canonicalization", "UTF-8 compact JSON excluding seal; object keys sorted lexicographically",
                "sealed_at", Instant.now().toString());
        bundle.put("seal", seal);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(mapper.writeValueAsString(Map.of(
                "status", status,
                "output", output.toString(),
                "minutes", state.minuteCount(),
                "canonical_evidence_eligible", bundle.get("canonical_evidence_eligible"))));
        return status.startsWith("CAPTURE_COMPLETE") ? 0 : 2;
    }

    static final class CollectorState {
        private final OkxMicrostructureCollector delegate;

        CollectorState(ObjectMapper mapper) {
            delegate = new OkxMicrostructureCollector(mapper);
        }

        synchronized void acceptRaw(String raw) {
            delegate.acceptRaw(raw);
        }

        synchronized Map<String, Object> buildBundle(
                Instant captureStartedAt,
                Instant captureEndedAt,
                int requestedDurationSeconds,
                String listenerError) {
            return delegate.buildV1Bundle(
                    captureStartedAt,
                    captureEndedAt,
                    requestedDurationSeconds,
                    listenerError);
        }

        synchronized int minuteCount() {
            return delegate.minuteCount();
        }
    }

    private static final class CaptureListener implements WebSocket.Listener {
        private final CollectorState state;
        private final StringBuilder partialText = new StringBuilder();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile String errorDetail;

        private CaptureListener(CollectorState state) {
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            webSocket.sendText(SUBSCRIBE_MESSAGE, true);
            opened.countDown();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                state.acceptRaw(partialText.toString());
                partialText.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            errorDetail = error.getClass().getSimpleName() + ": " + error.getMessage();
            closed.countDown();
        }

        private void awaitCapture(int durationSeconds) throws InterruptedException {
            if (!opened.await(20, TimeUnit.SECONDS)) {
                errorDetail = "WebSocket open timeout";
                return;
            }
            boolean terminatedEarly = closed.await(durationSeconds, TimeUnit.SECONDS);
            if (terminatedEarly && errorDetail == null) {
                errorDetail = "WebSocket closed before requested duration";
            }
        }

        private void awaitClose(int seconds) throws InterruptedException {
            closed.await(seconds, TimeUnit.SECONDS);
        }

        private String errorDetail() {
            return errorDetail;
        }
    }

    private record Arguments(int durationSeconds, Path output) {
        private static Arguments parse(String[] args) {
            Integer durationSeconds = null;
            Path output = null;
            for (int index = 0; index < args.length; index++) {
                if ("--duration-seconds".equals(args[index]) && index + 1 < args.length) {
                    durationSeconds = Integer.parseInt(args[++index]);
                } else if ("--output".equals(args[index]) && index + 1 < args.length) {
                    output = Path.of(args[++index]);
                } else {
                    throw usage();
                }
            }
            if (durationSeconds == null || output == null
                    || durationSeconds < MIN_DURATION_SECONDS
                    || durationSeconds > MAX_DURATION_SECONDS) {
                throw usage();
            }
            return new Arguments(durationSeconds, output);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "usage: --duration-seconds <5..86400> --output <new-sealed.json>");
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
