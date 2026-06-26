package com.agora.service.trading;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PanicBottomContextPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";

    private final MdKlineRepository klineRepository;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepository;
    private final ObjectMapper objectMapper;

    public String preview(String symbol, String ocoHealthText) {
        return write(previewNode(symbol, ocoHealthText));
    }

    public ObjectNode previewNode(String symbol, String ocoHealthText) {
        String sym = normalizeSymbol(symbol);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewPanicBottomContext");
        root.put("boundary", "READ_ONLY");
        root.put("readOnlyScope", "reads md_kline, market_indicator_history, and OCO health text only");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", sym);
        root.put("orderAllowed", false);
        root.put("gridMutationAllowed", false);
        root.put("ocoMutationAllowed", false);
        root.put("telegramSendAllowed", false);
        root.put("writesRuntimeEvidence", false);

        List<MdKline> daily = loadBars(sym, "1d", 90);
        List<MdKline> oneHour = loadBars(sym, "1h", 72);
        List<MdKline> fourHour = loadBars(sym, "4h", 60);
        List<MdKline> weekly = loadBars(sym, "1w", 220);

        WaveContext wave = analyzeWaves(daily);
        FearGreedContext fearGreed = latestFearGreed(sym);
        WmaContext wma = calculate200Wma(weekly, daily, wave.currentPrice());
        TrendContext trend1h = trend("1h", oneHour, new BigDecimal("-2.0"));
        TrendContext trend4h = trend("4h", fourHour, new BigDecimal("-3.0"));
        boolean ocoAbnormal = isOcoAbnormal(ocoHealthText);

        root.set("waveStructure", wave.toJson(objectMapper));
        root.set("fearGreed", fearGreed.toJson(objectMapper));
        root.set("twoHundredWma", wma.toJson(objectMapper));
        ObjectNode trendNode = root.putObject("trendGuard");
        trendNode.set("oneHour", trend1h.toJson(objectMapper));
        trendNode.set("fourHour", trend4h.toJson(objectMapper));
        trendNode.put("oneHourOrFourHourStillTrendingBearish",
                "TRENDING_BEARISH".equals(trend1h.status()) || "TRENDING_BEARISH".equals(trend4h.status()));
        ObjectNode ocoNode = root.putObject("ocoGuard");
        ocoNode.put("status", ocoAbnormal ? "ABNORMAL" : "OK_OR_UNKNOWN");
        ocoNode.put("abnormal", ocoAbnormal);
        ocoNode.put("source", ocoHealthText == null || ocoHealthText.isBlank() ? "not provided" : "getOcoHealth text");

        int score = panicBottomScore(wave, fearGreed, wma);
        String phase = phase(score, wave, fearGreed, wma);
        boolean confirmedDeployBlocked = ocoAbnormal
                || "TRENDING_BEARISH".equals(trend1h.status())
                || "TRENDING_BEARISH".equals(trend4h.status());
        String suggestedAction = suggestedAction(score, confirmedDeployBlocked);

        root.put("panicBottomScore", score);
        root.put("phase", phase);
        root.put("confirmedDeployBlocked", confirmedDeployBlocked);
        root.put("confirmedDeployBlockReason", confirmedDeployBlocked
                ? "OCO_ABNORMAL_OR_1H_4H_TRENDING_BEARISH"
                : "NONE");
        root.put("suggestedAction", suggestedAction);
        ArrayNode notes = root.putArray("safetyNotes");
        notes.add("SCOUT_PRE_POSITION is a read-only recommendation label, not execution authorization.");
        notes.add("CONFIRMED_DEPLOY_REVIEW is an operator-review label only; this MCP never places orders.");
        notes.add("OCO abnormal or 1h/4h TRENDING_BEARISH forces suggestedAction to SCOUT_PRE_POSITION or WATCH.");
        return root;
    }

    private List<MdKline> loadBars(String symbol, String intervalCode, int limit) {
        try {
            List<MdKline> rows = new ArrayList<>(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    symbol, intervalCode, PageRequest.of(0, limit)));
            rows.sort(Comparator.comparing(MdKline::getOpenTime));
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    private WaveContext analyzeWaves(List<MdKline> daily) {
        if (daily == null || daily.size() < 30) {
            BigDecimal current = latestClose(daily).orElse(null);
            return new WaveContext(0, null, null, "INSUFFICIENT_DATA", current, null, null, daily == null ? 0 : daily.size());
        }
        List<MdKline> window = daily.size() > 60 ? daily.subList(daily.size() - 60, daily.size()) : daily;
        BigDecimal current = latestClose(window).orElse(null);
        BigDecimal largestDrawdownPct = BigDecimal.ZERO;
        BigDecimal peak = window.get(0).getHighPrice();
        BigDecimal recentHigh = peak;
        BigDecimal recentLow = window.get(0).getLowPrice();
        int downWaves = 0;
        boolean inWave = false;
        for (MdKline bar : window) {
            if (bar.getHighPrice().compareTo(recentHigh) > 0) {
                recentHigh = bar.getHighPrice();
            }
            if (bar.getLowPrice().compareTo(recentLow) < 0) {
                recentLow = bar.getLowPrice();
            }
            if (bar.getHighPrice().compareTo(peak) > 0) {
                peak = bar.getHighPrice();
                inWave = false;
            }
            BigDecimal dd = pct(bar.getLowPrice(), peak);
            if (dd.compareTo(largestDrawdownPct) < 0) {
                largestDrawdownPct = dd;
            }
            if (dd.compareTo(new BigDecimal("-5.0")) <= 0 && !inWave) {
                downWaves++;
                inWave = true;
            }
            if (dd.compareTo(new BigDecimal("-2.0")) > 0) {
                inWave = false;
            }
        }
        BigDecimal currentLegPct = current == null ? null : pct(current, recentHigh);
        String retest = "UNKNOWN";
        if (current != null && recentLow != null && recentLow.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal distance = pct(current, recentLow);
            if (distance.compareTo(BigDecimal.ZERO) < 0) {
                retest = "BREAKING_LOW";
            } else if (distance.compareTo(new BigDecimal("3.0")) <= 0) {
                retest = "RETESTING_LOW";
            } else {
                retest = "ABOVE_RECENT_LOW";
            }
        }
        return new WaveContext(downWaves, largestDrawdownPct, currentLegPct, retest, current, recentHigh, recentLow, window.size());
    }

    private FearGreedContext latestFearGreed(String symbol) {
        Optional<MarketIndicatorHistory> latest = indicatorHistoryRepository
                .findTopCleanBySymbolAndIndicator(symbol, "fear_greed");
        if (latest.isEmpty()) {
            latest = indicatorHistoryRepository.findTopCleanBySymbolAndIndicator("BTCUSDT", "fear_greed");
        }
        if (latest.isEmpty()) {
            return new FearGreedContext(null, "NO_DATA", "NO_DATA", null, null);
        }
        MarketIndicatorHistory row = latest.get();
        int value = row.getValue() == null ? -1 : row.getValue().setScale(0, RoundingMode.HALF_UP).intValue();
        long ageHours = ChronoUnit.HOURS.between(row.getCapturedAt(), LocalDateTime.now(ZoneOffset.UTC));
        String freshness = ageHours <= 48 ? "FRESH" : "STALE";
        return new FearGreedContext(value, classifyFearGreed(value), freshness, ageHours, row.getCapturedAt());
    }

    private WmaContext calculate200Wma(List<MdKline> weekly, List<MdKline> daily, BigDecimal currentPrice) {
        BigDecimal reference = averageClose(tail(weekly, 200));
        String source = "md_kline:1w";
        if (reference == null) {
            reference = averageClose(tail(daily, 1400));
            source = "md_kline:1d_1400bar_reference";
        }
        if (reference == null || currentPrice == null) {
            return new WmaContext(null, null, "INSUFFICIENT_DATA", source);
        }
        return new WmaContext(reference, pct(currentPrice, reference), "OK", source);
    }

    private TrendContext trend(String intervalCode, List<MdKline> bars, BigDecimal bearishThresholdPct) {
        if (bars == null || bars.size() < 24) {
            return new TrendContext(intervalCode, "INSUFFICIENT_DATA", null, bars == null ? 0 : bars.size());
        }
        BigDecimal first = bars.get(0).getClosePrice();
        BigDecimal last = bars.get(bars.size() - 1).getClosePrice();
        BigDecimal trendPct = pct(last, first);
        String status = trendPct.compareTo(bearishThresholdPct) <= 0 ? "TRENDING_BEARISH" : "NOT_TRENDING_BEARISH";
        return new TrendContext(intervalCode, status, trendPct, bars.size());
    }

    private int panicBottomScore(WaveContext wave, FearGreedContext fear, WmaContext wma) {
        int score = 0;
        if (fear.value() != null && fear.value() <= 20) score += 25;
        else if (fear.value() != null && fear.value() <= 30) score += 15;
        if (wma.priceVs200wmaPct() != null && wma.priceVs200wmaPct().compareTo(new BigDecimal("10.0")) <= 0) score += 25;
        else if (wma.priceVs200wmaPct() != null && wma.priceVs200wmaPct().compareTo(new BigDecimal("25.0")) <= 0) score += 10;
        if (wave.downWaveCount() >= 3) score += 20;
        else if (wave.downWaveCount() >= 2) score += 12;
        if (wave.largestDrawdownPct() != null && wave.largestDrawdownPct().compareTo(new BigDecimal("-25.0")) <= 0) score += 20;
        else if (wave.largestDrawdownPct() != null && wave.largestDrawdownPct().compareTo(new BigDecimal("-15.0")) <= 0) score += 10;
        if ("RETESTING_LOW".equals(wave.retestLowStatus()) || "BREAKING_LOW".equals(wave.retestLowStatus())) score += 10;
        return Math.min(100, score);
    }

    private String phase(int score, WaveContext wave, FearGreedContext fear, WmaContext wma) {
        if (score >= 75) return "PANIC_BOTTOM_CANDIDATE";
        if (score >= 55) return "CAPITULATION_WATCH";
        if (fear.value() != null && fear.value() <= 25 && wave.downWaveCount() >= 2) return "FEAR_RETEST_WATCH";
        if (wma.priceVs200wmaPct() != null && wma.priceVs200wmaPct().compareTo(new BigDecimal("25.0")) <= 0) {
            return "VALUE_ZONE_WATCH";
        }
        return "NORMAL_OR_UNCONFIRMED";
    }

    private String suggestedAction(int score, boolean confirmedDeployBlocked) {
        if (confirmedDeployBlocked) {
            return score >= 55 ? "SCOUT_PRE_POSITION" : "WATCH";
        }
        if (score >= 75) return "CONFIRMED_DEPLOY_REVIEW";
        if (score >= 55) return "SCOUT_PRE_POSITION";
        return "WATCH";
    }

    private boolean isOcoAbnormal(String text) {
        if (text == null || text.isBlank()) return false;
        String upper = text.toUpperCase(Locale.ROOT);
        Integer syncErrors = countBeforeMarker(upper, "SYNC_ERROR");
        Integer abnormal = countBeforeMarker(upper, "ABNORMAL");
        if (syncErrors != null && syncErrors > 0) return true;
        if (abnormal != null && abnormal > 0) return true;
        if (syncErrors == null && upper.contains("SYNC_ERROR")) return true;
        if (abnormal == null && upper.contains("ABNORMAL")) return true;
        return upper.contains("OCO_HEALTH_ABNORMAL")
                || upper.contains("FAILED");
    }

    private Integer countBeforeMarker(String text, String marker) {
        Matcher matcher = Pattern.compile("(\\d+)\\s+" + Pattern.quote(marker)).matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String classifyFearGreed(int value) {
        if (value < 0) return "NO_DATA";
        if (value <= 24) return "EXTREME_FEAR";
        if (value <= 44) return "FEAR";
        if (value <= 55) return "NEUTRAL";
        if (value <= 75) return "GREED";
        return "EXTREME_GREED";
    }

    private static <T> List<T> tail(List<T> rows, int size) {
        if (rows == null || rows.size() < size) return List.of();
        return rows.subList(rows.size() - size, rows.size());
    }

    private static Optional<BigDecimal> latestClose(List<MdKline> rows) {
        if (rows == null || rows.isEmpty()) return Optional.empty();
        return Optional.ofNullable(rows.get(rows.size() - 1).getClosePrice());
    }

    private static BigDecimal averageClose(List<MdKline> rows) {
        if (rows == null || rows.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (MdKline row : rows) {
            if (row.getClosePrice() != null) {
                sum = sum.add(row.getClosePrice());
                count++;
            }
        }
        return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal value, BigDecimal reference) {
        if (value == null || reference == null || reference.compareTo(BigDecimal.ZERO) == 0) return null;
        return value.subtract(reference)
                .multiply(new BigDecimal("100"))
                .divide(reference, 4, RoundingMode.HALF_UP);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private record WaveContext(int downWaveCount,
                               BigDecimal largestDrawdownPct,
                               BigDecimal currentLegPct,
                               String retestLowStatus,
                               BigDecimal currentPrice,
                               BigDecimal recentSwingHigh,
                               BigDecimal recentSwingLow,
                               int barsUsed) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("barsUsed", barsUsed);
            n.put("downWaveCount", downWaveCount);
            putDecimal(n, "largestDrawdownPct", largestDrawdownPct);
            putDecimal(n, "currentLegPct", currentLegPct);
            n.put("retestLowStatus", retestLowStatus);
            putDecimal(n, "currentPrice", currentPrice);
            putDecimal(n, "recentSwingHigh", recentSwingHigh);
            putDecimal(n, "recentSwingLow", recentSwingLow);
            return n;
        }
    }

    private record FearGreedContext(Integer value,
                                    String classification,
                                    String freshness,
                                    Long ageHours,
                                    LocalDateTime capturedAt) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            if (value == null) n.putNull("latestValue"); else n.put("latestValue", value);
            n.put("classification", classification);
            n.put("freshness", freshness);
            if (ageHours == null) n.putNull("ageHours"); else n.put("ageHours", ageHours);
            if (capturedAt != null) n.put("capturedAt", capturedAt.toString());
            return n;
        }
    }

    private record WmaContext(BigDecimal referencePrice,
                              BigDecimal priceVs200wmaPct,
                              String status,
                              String source) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("status", status);
            n.put("source", source);
            putDecimal(n, "referencePrice", referencePrice);
            putDecimal(n, "priceVs200wmaPct", priceVs200wmaPct);
            return n;
        }
    }

    private record TrendContext(String intervalCode, String status, BigDecimal trendPct, int barsUsed) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("intervalCode", intervalCode);
            n.put("status", status);
            n.put("barsUsed", barsUsed);
            putDecimal(n, "trendPct", trendPct);
            return n;
        }
    }

    private static void putDecimal(ObjectNode n, String field, BigDecimal value) {
        if (value == null) {
            n.putNull(field);
        } else {
            n.put(field, value.setScale(4, RoundingMode.HALF_UP));
        }
    }
}
