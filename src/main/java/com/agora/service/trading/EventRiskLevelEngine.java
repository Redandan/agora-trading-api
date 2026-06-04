package com.agora.service.trading;

import com.agora.config.properties.EventRiskControlProperties;
import com.agora.model.TgNotificationLog;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.TgTradingNotificationClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventRiskLevelEngine {

    public enum RiskLevel {
        R0, R1, R2, R3;

        public boolean atLeast(RiskLevel other) {
            return ordinal() >= other.ordinal();
        }
    }

    private final MarketIndicatorHistoryRepository indicatorRepo;
    private final TgNotificationLogRepository tgNotificationLogRepo;
    private final TgTradingNotificationClassifier tgClassifier;
    private final EventRiskControlProperties properties;

    public Snapshot evaluate(String symbol) {
        String sym = normalizeSymbol(symbol);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Score score = new Score();
        Map<String, Object> inputs = new LinkedHashMap<>();

        evaluatePriceMove(sym, "1h", 3.0, 5.0, 3.0, 4.0, 20, 35, score, inputs);
        evaluatePriceMove(sym, "4h", 5.0, 8.0, 3.0, Double.MAX_VALUE, 20, 35, score, inputs);
        evaluatePriceMove(sym, "24h", 6.0, 10.0, Double.MAX_VALUE, Double.MAX_VALUE, 25, 40, score, inputs);
        evaluateSqi(sym, score, inputs);
        evaluateMarketSignals(sym, now, score, inputs);

        int capped = Math.max(0, Math.min(100, score.value));
        return new Snapshot(sym, capped, levelFor(capped), List.copyOf(score.reasons), inputs, now);
    }

    public String render(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Event Risk Control Status (#517 Phase A) ===\n");
        sb.append("symbol=").append(snapshot.symbol()).append("\n");
        sb.append("generatedAtUtc=").append(snapshot.generatedAtUtc()).append("\n");
        sb.append("enabled=").append(properties.enabled()).append("\n");
        sb.append("blockNewEntries=").append(properties.blockNewEntries()).append("\n");
        sb.append("riskLevel=").append(snapshot.level()).append("\n");
        sb.append("riskScore=").append(snapshot.score()).append("\n");
        sb.append("reasons=").append(snapshot.reasons().isEmpty()
                ? "none"
                : String.join(" | ", snapshot.reasons())).append("\n");
        sb.append("inputs=").append(snapshot.inputs()).append("\n");
        sb.append("policy=");
        if (!properties.enabled()) {
            sb.append("disabled");
        } else if (!properties.blockNewEntries()) {
            sb.append("observe_only");
        } else if (snapshot.level().atLeast(RiskLevel.R3)) {
            sb.append("R3 blocks new entries except explicit R3 allowlist");
        } else if (snapshot.level().atLeast(RiskLevel.R2)) {
            sb.append("R2 blocks new entries except explicit R2/R3 allowlist or strategy config override");
        } else {
            sb.append("new entries allowed");
        }
        return sb.toString();
    }

    private void evaluatePriceMove(String symbol, String window,
                                   double warnPct, double critPct,
                                   double warnAtr, double critAtr,
                                   int warnScore, int critScore,
                                   Score score, Map<String, Object> inputs) {
        Double change = latest(symbol, "btc_change_pct_" + window);
        Double atr = latest(symbol, "btc_atr_units_" + window);
        inputs.put("btc_change_pct_" + window, change);
        inputs.put("btc_atr_units_" + window, atr);
        boolean critical = (change != null && Math.abs(change) >= critPct)
                || (atr != null && atr >= critAtr);
        boolean warn = (change != null && Math.abs(change) >= warnPct)
                || (atr != null && atr >= warnAtr);
        if (critical) {
            score.add(critScore, "volatility_" + window + "_critical(change="
                    + fmt(change) + ",atr=" + fmt(atr) + ")");
        } else if (warn) {
            score.add(warnScore, "volatility_" + window + "_warn(change="
                    + fmt(change) + ",atr=" + fmt(atr) + ")");
        }
    }

    private void evaluateSqi(String symbol, Score score, Map<String, Object> inputs) {
        Double sqi = latest(symbol, "sqi");
        inputs.put("sqi", sqi);
        if (sqi == null) return;
        if (sqi >= 75) {
            score.add(25, "short_squeeze_sqi_critical=" + fmt(sqi));
        } else if (sqi >= 40) {
            score.add(15, "short_squeeze_sqi_warn=" + fmt(sqi));
        }
    }

    private void evaluateMarketSignals(String symbol, LocalDateTime now,
                                       Score score, Map<String, Object> inputs) {
        LocalDateTime from = now.minusHours(properties.tgWindowHours());
        List<TgNotificationLog> logs = tgNotificationLogRepo.search(
                from, null, null, null, null, PageRequest.of(0, 100));
        Map<String, Long> routes = new LinkedHashMap<>();
        int marketSignals = 0;
        boolean severeExternalRisk = false;
        for (TgNotificationLog log : logs) {
            if (!matchesSymbol(log, symbol)) continue;
            if (tgClassifier.classify(log.getMessage(), log.getSource(), log.getLevel())
                    != TgTradingNotificationClassifier.Bucket.MARKET_SIGNAL) {
                continue;
            }
            marketSignals++;
            String route = tgClassifier.routingKey(log.getMessage(), log.getSource(), log.getLevel());
            routes.merge(route, 1L, Long::sum);
            String text = ((log.getMessage() != null ? log.getMessage() : "") + " "
                    + (log.getLevel() != null ? log.getLevel() : ""))
                    .toLowerCase(Locale.ROOT);
            if ((route.contains("polymarket") || route.contains("macro") || route.contains("market-flip"))
                    && (text.contains("critical") || text.contains("extreme")
                    || text.contains("do_not_add") || text.contains("危急") || text.contains("極端"))) {
                severeExternalRisk = true;
            }
        }

        inputs.put("market_signal_count_" + properties.tgWindowHours() + "h", marketSignals);
        inputs.put("market_signal_routes", routes);
        if (marketSignals >= 10) {
            score.add(20, "market_signal_cluster=" + marketSignals);
        } else if (marketSignals >= 3) {
            score.add(10, "market_signal_watch=" + marketSignals);
        }
        long flip = routes.getOrDefault("market-signal:market-flip", 0L);
        boolean external = routes.containsKey("market-signal:polymarket")
                || routes.containsKey("market-signal:macro");
        if (flip >= 3 || (flip > 0 && external)) {
            score.add(25, "market_flip_external_confluence");
        }
        if (routes.size() >= 2) {
            score.add(10, "market_signal_route_confluence=" + routes.size());
        }
        if (severeExternalRisk) {
            score.add(40, "severe_external_market_signal");
        } else if (external) {
            score.add(20, "external_market_risk_signal");
        }
    }

    private Double latest(String symbol, String indicator) {
        try {
            return indicatorRepo.findTopCleanBySymbolAndIndicator(symbol, indicator)
                    .map(row -> row.getValue().doubleValue())
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[EventRisk] latest indicator failed {}/{}: {}", symbol, indicator, e.getMessage());
            return null;
        }
    }

    private RiskLevel levelFor(int score) {
        if (score >= 75) return RiskLevel.R3;
        if (score >= 50) return RiskLevel.R2;
        if (score >= 25) return RiskLevel.R1;
        return RiskLevel.R0;
    }

    private boolean matchesSymbol(TgNotificationLog log, String symbol) {
        if (symbol == null || symbol.isBlank()) return true;
        if (log.getSymbol() != null && symbol.equalsIgnoreCase(log.getSymbol())) return true;
        String message = log.getMessage();
        if (message == null) return false;
        String upper = message.toUpperCase(Locale.ROOT);
        String normalized = symbol.toUpperCase(Locale.ROOT);
        String base = normalized.endsWith("USDT") ? normalized.substring(0, normalized.length() - 4) : normalized;
        return upper.contains(normalized) || (!base.isBlank() && upper.contains(base));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String fmt(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static class Score {
        private int value;
        private final List<String> reasons = new ArrayList<>();

        private void add(int points, String reason) {
            value += points;
            reasons.add(reason + "(+" + points + ")");
        }
    }

    public record Snapshot(
            String symbol,
            int score,
            RiskLevel level,
            List<String> reasons,
            Map<String, Object> inputs,
            LocalDateTime generatedAtUtc
    ) {
    }
}
