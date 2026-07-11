package com.agora.service.backtest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TradingViewGoldenTruthVerifier {

    private static final double NN_TOLERANCE = 1e-6;

    public VerificationResult verify(String configuredCsvPath, List<Intent> actualIntents) {
        if (configuredCsvPath == null || configuredCsvPath.isBlank()) {
            return VerificationResult.unavailable("GOLDEN_TRUTH_PATH_NOT_CONFIGURED");
        }
        Path path = Path.of(configuredCsvPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return VerificationResult.unavailable("GOLDEN_TRUTH_FILE_NOT_FOUND:" + path);
        }
        try {
            List<Intent> expected = readCsv(path);
            List<Intent> actual = actualIntents == null ? List.of() : List.copyOf(actualIntents);
            Map<IntentKey, Integer> expectedCounts = counts(expected);
            Map<IntentKey, Integer> actualCounts = counts(actual);
            int missing = differenceCount(expectedCounts, actualCounts);
            int extra = differenceCount(actualCounts, expectedCounts);
            boolean keysMatch = missing == 0 && extra == 0;
            boolean nnCompared = hasCompleteNnEvidence(expected, actual, keysMatch);
            double maxNnError = nnError(expected, actual, nnCompared);
            boolean nnWithinTolerance = nnCompared
                    && Double.isFinite(maxNnError)
                    && maxNnError <= NN_TOLERANCE;
            boolean exact = !expected.isEmpty() && keysMatch && nnWithinTolerance;
            return new VerificationResult(
                    exact ? "PASS_EXACT_PARITY" : "FAIL_PARITY_MISMATCH",
                    exact,
                    expected.size(),
                    actual.size(),
                    missing,
                    extra,
                    nnCompared,
                    maxNnError,
                    sha256(path),
                    path.toString(),
                    exact ? "NONE" : mismatchBlocker(expected, missing, extra, nnCompared, maxNnError));
        } catch (Exception e) {
            return VerificationResult.unavailable("GOLDEN_TRUTH_PARSE_FAILED:" + e.getMessage());
        }
    }

    private List<Intent> readCsv(Path path) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            Map<String, String> headers = normalizedHeaders(parser.getHeaderMap());
            String timeHeader = requiredHeader(headers, "time", "bar_time", "baropen_time", "date");
            String reasonHeader = requiredHeader(headers, "reason", "order_reason");
            String labelHeader = requiredHeader(headers, "label", "order_label");
            String qtyHeader = requiredHeader(headers, "qty", "quantity", "order_qty");
            String nnHeader = optionalHeader(headers, "nn_output", "nn", "neural_output");
            List<Intent> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                rows.add(new Intent(
                        parseTime(record.get(timeHeader)),
                        record.get(reasonHeader),
                        record.get(labelHeader),
                        new BigDecimal(record.get(qtyHeader)),
                        nnHeader == null || record.get(nnHeader).isBlank()
                                ? null : Double.parseDouble(record.get(nnHeader))));
            }
            return rows;
        }
    }

    private Map<String, String> normalizedHeaders(Map<String, Integer> headerMap) {
        Map<String, String> result = new HashMap<>();
        headerMap.keySet().forEach(header -> result.put(normalizeHeader(header), header));
        return result;
    }

    private String requiredHeader(Map<String, String> headers, String... names) {
        String value = optionalHeader(headers, names);
        if (value == null) {
            throw new IllegalArgumentException("missing required CSV header: " + String.join("/", names));
        }
        return value;
    }

    private String optionalHeader(Map<String, String> headers, String... names) {
        for (String name : names) {
            String header = headers.get(normalizeHeader(name));
            if (header != null) {
                return header;
            }
        }
        return null;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private LocalDateTime parseTime(String value) {
        String text = value.trim();
        if (text.matches("\\d{10,13}")) {
            long epoch = Long.parseLong(text);
            if (text.length() == 10) epoch *= 1000L;
            return Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private Map<IntentKey, Integer> counts(List<Intent> intents) {
        Map<IntentKey, Integer> counts = new HashMap<>();
        intents.stream().map(this::key).forEach(key -> counts.merge(key, 1, Integer::sum));
        return counts;
    }

    private int differenceCount(Map<IntentKey, Integer> left, Map<IntentKey, Integer> right) {
        return left.entrySet().stream()
                .mapToInt(entry -> Math.max(0, entry.getValue() - right.getOrDefault(entry.getKey(), 0)))
                .sum();
    }

    private boolean hasCompleteNnEvidence(List<Intent> expected, List<Intent> actual, boolean keysMatch) {
        return keysMatch
                && !expected.isEmpty()
                && expected.stream().allMatch(intent -> intent.nnOutput() != null)
                && actual.stream().allMatch(intent -> intent.nnOutput() != null);
    }

    private double nnError(List<Intent> expected, List<Intent> actual, boolean nnCompared) {
        if (!nnCompared) {
            return Double.POSITIVE_INFINITY;
        }
        Comparator<Intent> comparator = Comparator.comparing(intent -> key(intent).toString());
        List<Intent> expectedSorted = expected.stream().sorted(comparator).toList();
        List<Intent> actualSorted = actual.stream().sorted(comparator).toList();
        double max = 0.0;
        for (int i = 0; i < expectedSorted.size(); i++) {
            Double expectedNn = expectedSorted.get(i).nnOutput();
            if (expectedNn == null) continue;
            Double actualNn = actualSorted.get(i).nnOutput();
            if (actualNn == null) return Double.POSITIVE_INFINITY;
            max = Math.max(max, Math.abs(expectedNn - actualNn));
        }
        return max;
    }

    private String mismatchBlocker(List<Intent> expected, int missing, int extra,
                                   boolean nnCompared, double maxNnError) {
        if (expected.isEmpty()) return "GOLDEN_TRUTH_NO_INTENTS";
        if (missing > 0 || extra > 0) return "BUY_POINT_MISMATCH";
        if (!nnCompared) return "NN_EVIDENCE_REQUIRED";
        if (!Double.isFinite(maxNnError) || maxNnError > NN_TOLERANCE) return "NN_MISMATCH";
        return "UNKNOWN_PARITY_MISMATCH";
    }

    private IntentKey key(Intent intent) {
        return new IntentKey(intent.time(), normalize(intent.reason()), normalize(intent.label()),
                intent.quantity().stripTrailingZeros().toPlainString());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest);
    }

    public record Intent(LocalDateTime time, String reason, String label, BigDecimal quantity, Double nnOutput) {
    }

    private record IntentKey(LocalDateTime time, String reason, String label, String quantity) {
    }

    public record VerificationResult(String status,
                                     boolean exactParity,
                                     int expectedIntentCount,
                                     int actualIntentCount,
                                     int missingIntentCount,
                                     int extraIntentCount,
                                     boolean nnCompared,
                                     double maxNnError,
                                     String goldenSha256,
                                     String goldenPath,
                                     String blocker) {
        static VerificationResult unavailable(String blocker) {
            return new VerificationResult("GOLDEN_TRUTH_UNAVAILABLE", false, 0, 0, 0, 0,
                    false, Double.NaN, "N/A", "N/A", blocker);
        }
    }
}
