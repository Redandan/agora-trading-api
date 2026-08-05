package com.agora.research;

import com.agora.model.MdKline;
import com.agora.service.trading.BtcDraPolicy;
import com.agora.service.trading.BtcDraShadowEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline-only Phase B cross-language economic-ledger generator.
 *
 * <p>This class is intentionally not a Spring component and has no database,
 * exchange, order, scheduler, deployment, or runtime activation path.</p>
 */
public final class BtcDraEconomicLedgerParityCli {

    private static final String AUTHORIZATION =
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    private static final String SCHEMA_VERSION = "JAVA_DRA_ECONOMIC_LEDGER_V2";
    private static final int EXPECTED_ROWS = 52_608;
    private static final String EXPECTED_INPUT_SHA256 =
            "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
    private static final LocalDateTime DESIGN_START =
            LocalDateTime.of(2019, 1, 1, 0, 0);
    private static final LocalDateTime DESIGN_END =
            LocalDateTime.of(2023, 1, 1, 0, 0);
    private static final LocalDateTime VALIDATION_START = DESIGN_END;
    private static final LocalDateTime VALIDATION_END =
            LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final int WARMUP_DAYS = 90;
    private static final int MONEY_SCALE = 8;
    private static final int QUANTITY_SCALE = 12;
    private static final int RETURN_SCALE = 8;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Checkpoint EXPECTED_DESIGN = new Checkpoint(
            "169.89846767", "-79.12049441", "90.77797326", "29.530448",
            126.0, 1818.6, 100, 95, 5, 3, "34.364819", "3019.89846767");
    private static final Checkpoint EXPECTED_VALIDATION = new Checkpoint(
            "89.41118307", "-3.20820121", "86.20298186", "7.121498",
            182.5, 1418.3, 51, 50, 1, 0, "21.632695", "1589.41118307");

    private final ObjectMapper outputMapper = new ObjectMapper();

    private BtcDraEconomicLedgerParityCli() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new BtcDraEconomicLedgerParityCli().run(args);
        } catch (Exception error) {
            System.err.println("JAVA_LEDGER_REJECT: " + error.getMessage());
            error.printStackTrace(System.err);
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private int run(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (Files.exists(arguments.outputDir())) {
            throw new IllegalArgumentException(
                    "OUTPUT_SEAL_REJECT: " + arguments.outputDir());
        }
        InputData input = load(arguments.input());
        Files.createDirectories(arguments.outputDir());

        ReplayResult design = replay(
                input.bars(), DESIGN_START, DESIGN_END,
                arguments.outputDir().resolve("design"));
        ReplayResult validation = replay(
                input.bars(), VALIDATION_START, VALIDATION_END,
                arguments.outputDir().resolve("validation"));
        boolean checkpointParity = design.checkpoint().equals(EXPECTED_DESIGN)
                && validation.checkpoint().equals(EXPECTED_VALIDATION);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", SCHEMA_VERSION);
        result.put("status", checkpointParity
                ? "JAVA_LEDGER_GENERATED"
                : "JAVA_LEDGER_BASELINE_REJECT");
        result.put("authorization", AUTHORIZATION);
        result.put("input_rows", input.bars().size());
        result.put("input_sha256", input.sha256());
        result.put("engine", BtcDraShadowEngine.class.getName());
        result.put("policy", BtcDraPolicy.POLICY_MODE);
        result.put("windows", Map.of(
                "design", output(design, EXPECTED_DESIGN),
                "validation", output(validation, EXPECTED_VALIDATION)));

        Path resultPath = arguments.outputDir().resolve("result.json");
        Files.writeString(
                resultPath,
                outputMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(outputMapper.writeValueAsString(Map.of(
                "status", result.get("status"),
                "output_dir",
                arguments.outputDir().toAbsolutePath().normalize().toString())));
        return checkpointParity ? 0 : 2;
    }

    private Map<String, Object> output(
            ReplayResult result,
            Checkpoint expected) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("checkpoint", result.checkpoint().asList());
        value.put("checkpoint_parity", result.checkpoint().equals(expected));
        value.put("event_counts", result.eventCounts());
        value.put("events", result.events());
        value.put("fills", result.fills());
        value.put("states", result.states());
        value.put("terminal_lots", result.terminalLots());
        return value;
    }

    private ReplayResult replay(
            List<MdKline> bars,
            LocalDateTime start,
            LocalDateTime endExclusive,
            Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        BtcDraShadowEngine engine = new BtcDraShadowEngine(new ObjectMapper());
        BtcDraShadowEngine.State state = engine.initialState();
        List<String> events = new ArrayList<>();
        List<String> fills = new ArrayList<>();
        List<String> states = new ArrayList<>();
        Map<String, Integer> eventCounts = new TreeMap<>();
        Map<String, LocalDateTime> buyFillTimes = new LinkedHashMap<>();
        List<Double> holdHours = new ArrayList<>();
        BigDecimal utilizationSum = BigDecimal.ZERO;
        int utilizationPoints = 0;
        LocalDateTime warmupStart = start.minusDays(WARMUP_DAYS);

        for (MdKline bar : bars) {
            if (bar.getOpenTime().isBefore(warmupStart)
                    || !bar.getOpenTime().isBefore(endExclusive)) {
                continue;
            }
            BtcDraShadowEngine.StepResult step;
            if (bar.getOpenTime().isBefore(start)) {
                step = engine.warmup(state, bar);
            } else {
                step = engine.step(state, bar);
                for (BtcDraShadowEngine.RuntimeEvent event : step.events()) {
                    String line = canonicalEvent(event);
                    events.add(line);
                    eventCounts.merge(event.eventType(), 1, Integer::sum);
                    if ("VIRTUAL_BUY_FILL".equals(event.eventType())
                            || "VIRTUAL_SELL_FILL".equals(event.eventType())) {
                        fills.add(line);
                    }
                    if ("VIRTUAL_BUY_FILL".equals(event.eventType())) {
                        buyFillTimes.put(event.lotId(), event.eventBarOpenTime());
                    } else if ("VIRTUAL_SELL_FILL".equals(event.eventType())) {
                        LocalDateTime fillTime = buyFillTimes.remove(event.lotId());
                        if (fillTime == null) {
                            throw new IllegalStateException(
                                    "sell event has no matching buy: " + event.lotId());
                        }
                        holdHours.add(Duration.between(
                                fillTime, event.eventBarOpenTime()).toHours() * 1.0);
                    }
                }
                states.add(canonicalState(step.state()));
                utilizationSum = utilizationSum.add(
                        step.state().openCostUsdt().divide(
                                BtcDraPolicy.MAX_OPEN_COST_USDT,
                                16,
                                RoundingMode.HALF_UP));
                utilizationPoints++;
            }
            state = step.state();
        }
        if (utilizationPoints == 0) {
            throw new IllegalArgumentException("DATA_REJECT: no trading bars");
        }
        if (buyFillTimes.size() != state.openLots().size()) {
            throw new IllegalStateException("terminal buy/lot ledger mismatch");
        }

        List<String> lots = state.openLots().stream()
                .map(this::canonicalLot)
                .toList();
        LedgerEvidence eventEvidence = writeLines(
                outputDir.resolve("events.tsv"), events);
        LedgerEvidence fillEvidence = writeLines(
                outputDir.resolve("fills.tsv"), fills);
        LedgerEvidence stateEvidence = writeLines(
                outputDir.resolve("states.tsv"), states);
        LedgerEvidence lotEvidence = writeLines(
                outputDir.resolve("lots.tsv"), lots);

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
                checkpoint,
                Map.copyOf(eventCounts),
                eventEvidence,
                fillEvidence,
                stateEvidence,
                lotEvidence);
    }

    private String canonicalEvent(BtcDraShadowEngine.RuntimeEvent event) {
        return String.join(
                "\t",
                event.eventType(),
                timestamp(event.eventBarOpenTime()),
                timestamp(event.signalBarOpenTime()),
                event.lotId(),
                money(event.notionalUsdt()),
                money(event.fillPrice()),
                quantity(event.fillQty()),
                money(event.feeUsdt()),
                money(event.netPnlUsdt()),
                ratio(event.netReturn()),
                event.reason());
    }

    private String canonicalLot(BtcDraShadowEngine.Lot lot) {
        return String.join(
                "\t",
                lot.lotId(),
                timestamp(lot.signalBarOpenTime()),
                timestamp(lot.buyFillBarOpenTime()),
                money(lot.grossBuyNotionalUsdt()),
                money(lot.buyFillPrice()),
                quantity(lot.quantity()),
                lot.entryReason(),
                timestamp(lot.exitQueuedAtBarOpenTime()));
    }

    private String canonicalState(BtcDraShadowEngine.State state) throws Exception {
        StringBuilder lotBook = new StringBuilder();
        for (BtcDraShadowEngine.Lot lot : state.openLots()) {
            lotBook.append(canonicalLot(lot)).append('\n');
        }
        return String.join(
                "\t",
                timestamp(state.lastProcessedBarOpenTime()),
                timestamp(state.armedAt()),
                timestamp(state.armExpiresAt()),
                timestamp(state.lastEntrySignalBarOpenTime()),
                timestamp(state.pendingSignalBarOpenTime()),
                money(state.pendingBuyNotionalUsdt()),
                state.pendingReason(),
                Integer.toString(state.openLots().size()),
                sha256(lotBook.toString().getBytes(StandardCharsets.UTF_8)),
                money(state.totalBuyNotionalUsdt()),
                money(state.totalSellProceedsUsdt()),
                money(state.realizedPnlUsdt()),
                money(state.totalFeesUsdt()),
                money(state.openCostUsdt()),
                quantity(state.inventoryQty()),
                money(state.inventoryValueUsdt()),
                money(state.unrealizedPnlUsdt()),
                money(state.totalPnlUsdt()),
                Integer.toString(state.buyFillCount()),
                Integer.toString(state.sellFillCount()),
                Integer.toString(state.winningExitCount()),
                Integer.toString(state.deferredExitCount()),
                Integer.toString(state.queuedEntryCount()),
                Integer.toString(state.blockedEntryCount()),
                Integer.toString(state.armCount()),
                Integer.toString(state.expiredArmCount()),
                money(state.maxOpenCostUsdt()),
                ratio(state.maxOpenCapitalLossPct()),
                money(state.peakVirtualEquityUsdt()),
                ratio(state.maxVirtualDrawdownPct()));
    }

    private LedgerEvidence writeLines(Path path, List<String> lines) throws Exception {
        StringBuilder payloadBuilder = new StringBuilder();
        for (String line : lines) {
            payloadBuilder.append(line).append('\n');
        }
        String payload = payloadBuilder.toString();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Files.write(
                path,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        return new LedgerEvidence(lines.size(), sha256(bytes));
    }

    private InputData load(Path inputPath) throws Exception {
        if (!Files.isRegularFile(inputPath)) {
            throw new IllegalArgumentException("DATA_REJECT: input file missing");
        }
        byte[] bytes = Files.readAllBytes(inputPath);
        String inputHash = sha256(bytes);
        if (!EXPECTED_INPUT_SHA256.equals(inputHash)) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: unexpected input hash " + inputHash);
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
                        "DATA_REJECT: invalid field count at row " + (index + 1));
            }
            MdKline bar = parseBar(fields, index + 1);
            if (previousOpen != null
                    && !previousOpen.plusHours(1).equals(bar.getOpenTime())) {
                throw new IllegalArgumentException(
                        "DATA_REJECT: hourly gap before " + bar.getOpenTime());
            }
            previousOpen = bar.getOpenTime();
            bars.add(bar);
        }
        if (bars.getLast().getCloseTime().isAfter(VALIDATION_END)) {
            throw new IllegalArgumentException(
                    "DATA_REJECT: input crosses frozen selection cutoff");
        }
        return new InputData(List.copyOf(bars), inputHash);
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

    private static String timestamp(LocalDateTime value) {
        return value == null ? "" : TIME_FORMAT.format(value);
    }

    private static String money(BigDecimal value) {
        return normalized(value, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String quantity(BigDecimal value) {
        return normalized(value, QUANTITY_SCALE, RoundingMode.DOWN);
    }

    private static String ratio(BigDecimal value) {
        return normalized(value, RETURN_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalized(
            BigDecimal value,
            int scale,
            RoundingMode roundingMode) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(scale, roundingMode)
                .toPlainString();
    }

    private static Double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> ordered = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
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

    private record Arguments(Path input, Path outputDir) {
        private static Arguments parse(String[] args) {
            Path input = null;
            Path outputDir = null;
            for (int index = 0; index < args.length; index++) {
                if ("--input".equals(args[index]) && index + 1 < args.length) {
                    input = Path.of(args[++index]);
                } else if ("--output-dir".equals(args[index])
                        && index + 1 < args.length) {
                    outputDir = Path.of(args[++index]);
                } else {
                    throw new IllegalArgumentException(
                            "usage: --input <canonical.tsv> --output-dir <sealed-dir>");
                }
            }
            if (input == null || outputDir == null) {
                throw new IllegalArgumentException(
                        "usage: --input <canonical.tsv> --output-dir <sealed-dir>");
            }
            return new Arguments(input, outputDir);
        }
    }

    private record InputData(List<MdKline> bars, String sha256) {
    }

    private record LedgerEvidence(int rows, String sha256) {
    }

    private record ReplayResult(
            Checkpoint checkpoint,
            Map<String, Integer> eventCounts,
            LedgerEvidence events,
            LedgerEvidence fills,
            LedgerEvidence states,
            LedgerEvidence terminalLots) {
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

        private List<Object> asList() {
            return List.of(
                    realizedUsdt,
                    unrealizedUsdt,
                    totalPnlUsdt,
                    maxDrawdownPct,
                    medianHoldHours,
                    p90HoldHours,
                    buyCount,
                    sellCount,
                    openLots,
                    blockedEntries,
                    avgUtilizationPct,
                    turnoverUsdt);
        }
    }
}
