package com.agora.research;

import com.agora.config.JacksonConfig;
import com.agora.model.MdKline;
import com.agora.service.trading.BtcDraPolicy;
import com.agora.service.trading.BtcDraShadowEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline, research-only parity CLI for the frozen BTC DRA V1 economic core.
 *
 * <p>This class never starts Spring and has no database, repository, exchange,
 * scheduler, HTTP, MCP, notification, or order dependency. It consumes a
 * sealed local TSV and calls {@link BtcDraShadowEngine} directly.</p>
 */
public final class BtcDraResearchCli {

    private static final String AUTHORIZATION =
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    private static final String EXPECTED_INPUT_SHA256 =
            "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
    private static final int EXPECTED_ROWS = 52_608;
    private static final LocalDateTime DESIGN_START =
            LocalDateTime.parse("2019-01-01T00:00:00");
    private static final LocalDateTime DESIGN_END =
            LocalDateTime.parse("2023-01-01T00:00:00");
    private static final LocalDateTime VALIDATION_START = DESIGN_END;
    private static final LocalDateTime VALIDATION_END =
            LocalDateTime.parse("2025-01-01T00:00:00");
    private static final int WARMUP_DAYS = 90;

    private static final Checkpoint EXPECTED_DESIGN = new Checkpoint(
            "169.89846767",
            "-79.12049441",
            "90.77797326",
            "29.530448",
            126.0,
            1818.6,
            100,
            95,
            5,
            3,
            "34.364819",
            "3019.89846767");
    private static final Checkpoint EXPECTED_VALIDATION = new Checkpoint(
            "89.41118307",
            "-3.20820121",
            "86.20298186",
            "7.121498",
            182.5,
            1418.3,
            51,
            50,
            1,
            0,
            "21.632695",
            "1589.41118307");

    private final ObjectMapper outputMapper;
    private final ObjectMapper engineObjectMapper;

    private BtcDraResearchCli() {
        this.outputMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JavaTimeModule runtimeTimeModule = new JavaTimeModule();
        runtimeTimeModule.addSerializer(
                LocalDateTime.class,
                new JacksonConfig.LocalDateTimeWithTimezoneSerializer());
        runtimeTimeModule.addDeserializer(
                LocalDateTime.class,
                new JacksonConfig.LocalDateTimeWithTimezoneDeserializer());
        this.engineObjectMapper = new ObjectMapper()
                .registerModule(runtimeTimeModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new BtcDraResearchCli().run(args);
        } catch (Exception error) {
            System.err.println("{\"status\":\"JAVA_PARITY_ERROR\",\"detail\":\""
                    + jsonEscape(error.getMessage()) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private int run(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (Files.exists(arguments.output())) {
            throw new IllegalArgumentException(
                    "OUTPUT_SEAL_REJECT: " + arguments.output());
        }
        InputData input = load(arguments.input());
        ReplayResult design = replay(input.bars(), DESIGN_START, DESIGN_END);
        ReplayResult validation = replay(
                input.bars(), VALIDATION_START, VALIDATION_END);

        boolean parityPassed = design.checkpoint().equals(EXPECTED_DESIGN)
                && validation.checkpoint().equals(EXPECTED_VALIDATION);
        String status = parityPassed
                ? "JAVA_PARITY_PASS_RESEARCH_ONLY"
                : "JAVA_PARITY_REJECT";

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "JAVA_DRA_RESEARCH_PARITY_V1");
        output.put("status", status);
        output.put("authorization", AUTHORIZATION);
        output.put("engine", BtcDraShadowEngine.class.getName());
        output.put("policy", BtcDraPolicy.POLICY_MODE);
        output.put("input", Map.of(
                "file_name", arguments.input().getFileName().toString(),
                "rows", input.bars().size(),
                "sha256", input.sha256(),
                "first_open_time", input.bars().getFirst().getOpenTime(),
                "last_close_time", input.bars().getLast().getCloseTime()));
        output.put("windows", Map.of(
                "design", windowOutput(design, EXPECTED_DESIGN),
                "validation", windowOutput(validation, EXPECTED_VALIDATION)));
        output.put("phase_b_required", List.of(
                "cross_language_event_hash",
                "cross_language_fill_and_lot_hash",
                "cross_language_economic_state_hash",
                "representative_complex_overlay_parity"));

        Path parent = arguments.output().toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                arguments.output(),
                outputMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(output) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(outputMapper.writeValueAsString(Map.of(
                "status", status,
                "output", arguments.output().toAbsolutePath().normalize().toString(),
                "design_parity", design.checkpoint().equals(EXPECTED_DESIGN),
                "validation_parity", validation.checkpoint().equals(EXPECTED_VALIDATION))));
        return parityPassed ? 0 : 2;
    }

    private Map<String, Object> windowOutput(
            ReplayResult result,
            Checkpoint expected) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("start", result.start());
        value.put("end_exclusive", result.endExclusive());
        value.put("checkpoint", result.checkpoint());
        value.put("expected_checkpoint", expected);
        value.put("checkpoint_parity", result.checkpoint().equals(expected));
        value.put("event_counts", result.eventCounts());
        value.put("event_ledger_sha256", result.eventLedgerSha256());
        value.put("runtime_state_sha256", result.runtimeStateSha256());
        value.put("open_lots", result.openLots());
        return value;
    }

    private InputData load(Path inputPath) throws Exception {
        if (!Files.isRegularFile(inputPath)) {
            throw new IllegalArgumentException("DATA_REJECT: input file missing");
        }
        byte[] bytes = Files.readAllBytes(inputPath);
        String sha256 = sha256(bytes);
        if (!EXPECTED_INPUT_SHA256.equals(sha256)) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: expected input sha256 "
                            + EXPECTED_INPUT_SHA256 + " but found " + sha256);
        }
        List<String> lines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
        if (lines.size() != EXPECTED_ROWS) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: expected " + EXPECTED_ROWS
                            + " rows but found " + lines.size());
        }
        List<MdKline> bars = new ArrayList<>(lines.size());
        LocalDateTime previousOpen = null;
        for (int index = 0; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            if (fields.length != 7) {
                throw new IllegalArgumentException(
                        "DATA_REJECT: row " + (index + 1)
                                + " has " + fields.length + " fields");
            }
            MdKline bar = parseBar(fields, index + 1);
            if (previousOpen != null
                    && !previousOpen.plusHours(1).equals(bar.getOpenTime())) {
                throw new IllegalArgumentException(
                        "DATA_REJECT: hourly sequence gap before "
                                + bar.getOpenTime());
            }
            previousOpen = bar.getOpenTime();
            bars.add(bar);
        }
        if (bars.getLast().getCloseTime().isAfter(VALIDATION_END)) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: input crosses frozen selection cutoff");
        }
        return new InputData(List.copyOf(bars), sha256);
    }

    private MdKline parseBar(String[] fields, int rowNumber) {
        try {
            LocalDateTime openTime = LocalDateTime.parse(fields[0]);
            LocalDateTime closeTime = LocalDateTime.parse(fields[1]);
            BigDecimal open = new BigDecimal(fields[2]);
            BigDecimal high = new BigDecimal(fields[3]);
            BigDecimal low = new BigDecimal(fields[4]);
            BigDecimal close = new BigDecimal(fields[5]);
            BigDecimal volume = new BigDecimal(fields[6]);
            if (!openTime.plusHours(1).equals(closeTime)
                    || open.signum() <= 0
                    || high.signum() <= 0
                    || low.signum() <= 0
                    || close.signum() <= 0
                    || volume.signum() < 0
                    || high.compareTo(open.max(close)) < 0
                    || low.compareTo(open.min(close)) > 0
                    || high.compareTo(low) < 0) {
                throw new IllegalArgumentException("invalid time/OHLCV");
            }
            MdKline bar = new MdKline();
            bar.setSymbol(BtcDraPolicy.SYMBOL);
            bar.setIntervalCode(BtcDraPolicy.INTERVAL);
            bar.setSource(BtcDraPolicy.SOURCE);
            bar.setOpenTime(openTime);
            bar.setCloseTime(closeTime);
            bar.setOpenPrice(open);
            bar.setHighPrice(high);
            bar.setLowPrice(low);
            bar.setClosePrice(close);
            bar.setVolume(volume);
            return bar;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: invalid row " + rowNumber + ": "
                            + error.getMessage(), error);
        }
    }

    private ReplayResult replay(
            List<MdKline> bars,
            LocalDateTime start,
            LocalDateTime endExclusive) throws Exception {
        LocalDateTime warmupStart = start.minusDays(WARMUP_DAYS);
        BtcDraShadowEngine engine = new BtcDraShadowEngine(engineObjectMapper);
        BtcDraShadowEngine.State state = engine.initialState();
        MessageDigest eventDigest = MessageDigest.getInstance("SHA-256");
        Map<String, Integer> eventCounts = new TreeMap<>();
        Map<String, LocalDateTime> fillTimes = new LinkedHashMap<>();
        List<Double> holdHours = new ArrayList<>();
        BigDecimal utilizationSum = BigDecimal.ZERO;
        int utilizationPoints = 0;
        boolean observedTradingBar = false;

        for (MdKline bar : bars) {
            if (bar.getOpenTime().isBefore(warmupStart)
                    || !bar.getOpenTime().isBefore(endExclusive)) {
                continue;
            }
            BtcDraShadowEngine.StepResult step;
            if (bar.getOpenTime().isBefore(start)) {
                step = engine.warmup(state, bar);
            } else {
                observedTradingBar = true;
                step = engine.step(state, bar);
                for (BtcDraShadowEngine.RuntimeEvent event : step.events()) {
                    eventCounts.merge(event.eventType(), 1, Integer::sum);
                    eventDigest.update((canonicalEvent(event) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    if ("VIRTUAL_BUY_FILL".equals(event.eventType())) {
                        fillTimes.put(event.lotId(), event.eventBarOpenTime());
                    } else if ("VIRTUAL_SELL_FILL".equals(event.eventType())) {
                        LocalDateTime fillTime = fillTimes.remove(event.lotId());
                        if (fillTime == null) {
                            throw new IllegalStateException(
                                    "sell event has no matching buy: " + event.lotId());
                        }
                        holdHours.add(Duration.between(
                                fillTime, event.eventBarOpenTime()).toHours() * 1.0);
                    }
                }
                utilizationSum = utilizationSum.add(
                        step.state().openCostUsdt().divide(
                                BtcDraPolicy.MAX_OPEN_COST_USDT,
                                16,
                                RoundingMode.HALF_UP));
                utilizationPoints++;
            }
            state = step.state();
        }
        if (!observedTradingBar || utilizationPoints == 0) {
            throw new IllegalArgumentException("DATA_REJECT: no trading bars in window");
        }
        if (fillTimes.size() != state.openLots().size()) {
            throw new IllegalStateException(
                    "open lot/fill ledger mismatch: " + fillTimes.size()
                            + " vs " + state.openLots().size());
        }
        Checkpoint checkpoint = new Checkpoint(
                money(state.realizedPnlUsdt()),
                money(state.unrealizedPnlUsdt()),
                money(state.totalPnlUsdt()),
                state.maxVirtualDrawdownPct().multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP).toPlainString(),
                percentile(holdHours, 0.5),
                percentile(holdHours, 0.9),
                state.buyFillCount(),
                state.sellFillCount(),
                state.openLots().size(),
                state.blockedEntryCount(),
                utilizationSum.divide(
                                BigDecimal.valueOf(utilizationPoints),
                                16,
                                RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP).toPlainString(),
                money(state.totalSellProceedsUsdt()));
        return new ReplayResult(
                start,
                endExclusive,
                checkpoint,
                Map.copyOf(eventCounts),
                HexFormat.of().formatHex(eventDigest.digest()),
                engine.stateSha256(state),
                state.openLots());
    }

    private String canonicalEvent(BtcDraShadowEngine.RuntimeEvent event) {
        return String.join(
                "\t",
                event.eventType(),
                text(event.eventBarOpenTime()),
                text(event.signalBarOpenTime()),
                event.lotId(),
                decimal(event.notionalUsdt()),
                decimal(event.fillPrice()),
                decimal(event.fillQty()),
                decimal(event.feeUsdt()),
                decimal(event.netPnlUsdt()),
                decimal(event.netReturn()),
                event.reason());
    }

    private static String money(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP).toPlainString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> ordered = values.stream().sorted(Comparator.naturalOrder()).toList();
        if (ordered.size() == 1) {
            return roundTwo(ordered.getFirst());
        }
        double position = (ordered.size() - 1) * percentile;
        int lower = (int) position;
        int upper = Math.min(lower + 1, ordered.size() - 1);
        double interpolated = ordered.get(lower)
                + (ordered.get(upper) - ordered.get(lower)) * (position - lower);
        return roundTwo(interpolated);
    }

    private static double roundTwo(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
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

    private record Arguments(Path input, Path output) {
        private static Arguments parse(String[] args) {
            Path input = null;
            Path output = null;
            for (int index = 0; index < args.length; index++) {
                if ("--input".equals(args[index]) && index + 1 < args.length) {
                    input = Path.of(args[++index]);
                } else if ("--output".equals(args[index]) && index + 1 < args.length) {
                    output = Path.of(args[++index]);
                } else {
                    throw new IllegalArgumentException(
                            "usage: --input <canonical.tsv> --output <sealed.json>");
                }
            }
            if (input == null || output == null) {
                throw new IllegalArgumentException(
                        "usage: --input <canonical.tsv> --output <sealed.json>");
            }
            return new Arguments(input, output);
        }
    }

    private record InputData(List<MdKline> bars, String sha256) {
    }

    private record ReplayResult(
            LocalDateTime start,
            LocalDateTime endExclusive,
            Checkpoint checkpoint,
            Map<String, Integer> eventCounts,
            String eventLedgerSha256,
            String runtimeStateSha256,
            List<BtcDraShadowEngine.Lot> openLots) {
    }

    private record Checkpoint(
            String realizedUsdt,
            String unrealizedUsdt,
            String totalPnlUsdt,
            String maxDrawdownPct,
            Double medianHoldHours,
            Double p90HoldHours,
            int buyCount,
            int sellCount,
            int openLots,
            int blockedEntries,
            String avgUtilizationPct,
            String turnoverUsdt) {
    }
}
