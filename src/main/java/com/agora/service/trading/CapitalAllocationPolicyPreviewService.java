package com.agora.service.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class CapitalAllocationPolicyPreviewService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final BigDecimal SCORE_BUY_RESERVE_PCT = new BigDecimal("0.30");
    private static final BigDecimal PANIC_BUY_RESERVE_PCT = new BigDecimal("0.40");
    private static final BigDecimal MIN_KEEP_LIQUID_USDT = new BigDecimal("50.00");
    private static final BigDecimal MICRO_ADD_NOTIONAL = new BigDecimal("5.00");

    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String preview(String symbol) {
        CapitalAllocationSnapshot snapshot = snapshot(symbol);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "previewCapitalAllocationPolicy");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram behavior changed.");
        root.put("generatedAtUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("symbol", snapshot.symbol());
        putMoney(root, "freeUsdt", snapshot.freeUsdt());
        putMoney(root, "spotNonUsdtValue", snapshot.spotNonUsdtValue());
        putMoney(root, "tradingAccountObservedUsd", snapshot.tradingAccountObservedUsd());
        putMoney(root, "earnFlexibleUsdt", snapshot.earnFlexibleUsdt());
        putMoney(root, "totalObservedCapitalUsdt", snapshot.totalObservedCapitalUsdt());
        putMoney(root, "autoTradeCostExposureUsdt", snapshot.autoTradeCostExposureUsdt());
        putMoney(root, "autoTradeMarketValueUsdt", snapshot.autoTradeMarketValueUsdt());
        putMoney(root, "gridActualExposureUsdt", snapshot.gridActualExposureUsdt());
        putMoney(root, "gridMaxExposureUsdt", snapshot.gridMaxExposureUsdt());
        putMoney(root, "gridRemainingCapitalClaimUsdt", snapshot.gridRemainingCapitalClaimUsdt());
        putMoney(root, "currentTradingExposureUsdt", snapshot.currentTradingExposureUsdt());
        root.put("currentTradingExposurePctOfObservedTotal", pct(snapshot.currentTradingExposureUsdt(), snapshot.totalObservedCapitalUsdt()));
        root.put("currentTradingExposurePctOfTradingAccount", pct(snapshot.currentTradingExposureUsdt(), snapshot.tradingAccountObservedUsd()));
        putMoney(root, "scoreBuyReserveTargetUsdt", snapshot.scoreBuyReserveTargetUsdt());
        putMoney(root, "panicBuyReserveTargetUsdt", snapshot.panicBuyReserveTargetUsdt());
        putMoney(root, "minKeepLiquidUsdt", snapshot.minKeepLiquidUsdt());
        putMoney(root, "liquidAfterReserveUsdt", snapshot.liquidAfterReserveUsdt());
        putMoney(root, "scoreBuyRedeemNeededUsdt", snapshot.scoreBuyRedeemNeededUsdt());
        putMoney(root, "scoreBuyRedeemableFromEarnUsdt", snapshot.scoreBuyRedeemableFromEarnUsdt());
        putMoney(root, "deployableAfterPlannedRedeemUsdt", snapshot.deployableAfterPlannedRedeemUsdt());
        root.put("microAddNotionalUsdt", snapshot.microAddNotionalUsdt().toPlainString());
        root.put("microAddCapacityCount", snapshot.microAddCapacityCount());
        root.put("missedOpportunityDueToCapitalSegmentation", snapshot.missedOpportunityDueToCapitalSegmentation());
        root.put("recommendedPolicy", snapshot.recommendedPolicy());
        root.set("warnings", stringArray(snapshot.warnings()));
        root.set("blockers", stringArray(snapshot.blockers()));
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("earnRedeemed", false);
        root.put("fundMoved", false);
        return write(root);
    }

    @Transactional(readOnly = true)
    public CapitalAllocationSnapshot snapshot(String symbol) {
        String sym = normalizeSymbol(symbol);
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        Capital capital = readCapital(warnings);
        Exposure exposure = readExposure(sym, warnings);
        BigDecimal observedTotal = capital.tradingAccountUsd()
                .add(capital.earnFlexibleUsdt());
        BigDecimal currentTradingExposure = exposure.autoTradeCost()
                .add(exposure.gridActualExposure());
        BigDecimal maxGridClaim = exposure.gridMaxExposure()
                .subtract(exposure.gridActualExposure())
                .max(BigDecimal.ZERO);

        BigDecimal scoreBuyReserveTarget = observedTotal.multiply(SCORE_BUY_RESERVE_PCT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal panicReserveTarget = observedTotal.multiply(PANIC_BUY_RESERVE_PCT)
                .setScale(2, RoundingMode.DOWN);
        BigDecimal liquidAfterReserve = capital.freeUsdt().subtract(MIN_KEEP_LIQUID_USDT).max(BigDecimal.ZERO);
        BigDecimal scoreBuyRedeemNeeded = scoreBuyReserveTarget.subtract(liquidAfterReserve).max(BigDecimal.ZERO);
        BigDecimal redeemableForScoreBuy = scoreBuyRedeemNeeded.min(capital.earnFlexibleUsdt());
        BigDecimal deployableAfterPlannedRedeem = capital.freeUsdt().add(redeemableForScoreBuy);
        BigDecimal microAddCapacity = deployableAfterPlannedRedeem.subtract(MIN_KEEP_LIQUID_USDT)
                .max(BigDecimal.ZERO)
                .divide(MICRO_ADD_NOTIONAL, 0, RoundingMode.DOWN);

        boolean capitalSegmentationMissRisk = capital.earnFlexibleUsdt().compareTo(capital.freeUsdt()) > 0
                && capital.freeUsdt().compareTo(scoreBuyReserveTarget) < 0;
        if (capitalSegmentationMissRisk) {
            warnings.add("CAPITAL_SEGMENTATION_MISS_RISK: most capital is in Earn and not counted as deployable trading reserve.");
        }
        if (scoreBuyRedeemNeeded.signum() > 0) {
            warnings.add("SCORE_BUY_RESERVE_REQUIRES_EARN_REDEEM_PREVIEW_ONLY:" + money(scoreBuyRedeemNeeded));
        }
        if (capital.earnFlexibleUsdt().signum() > 0) {
            warnings.add("EARN_CAPITAL_VISIBLE_NOT_AUTO_REDEEMED");
        }

        return new CapitalAllocationSnapshot(
                sym,
                capital.freeUsdt(),
                capital.spotNonUsdtUsd(),
                capital.tradingAccountUsd(),
                capital.earnFlexibleUsdt(),
                observedTotal,
                exposure.autoTradeCost(),
                exposure.autoTradeMarketValue(),
                exposure.gridActualExposure(),
                exposure.gridMaxExposure(),
                maxGridClaim,
                currentTradingExposure,
                scoreBuyReserveTarget,
                panicReserveTarget,
                MIN_KEEP_LIQUID_USDT,
                liquidAfterReserve,
                scoreBuyRedeemNeeded,
                redeemableForScoreBuy,
                deployableAfterPlannedRedeem,
                MICRO_ADD_NOTIONAL,
                microAddCapacity.intValue(),
                capitalSegmentationMissRisk,
                recommendedPolicy(capitalSegmentationMissRisk, scoreBuyRedeemNeeded, microAddCapacity),
                List.copyOf(warnings),
                List.copyOf(blockers));
    }

    private Capital readCapital(List<String> warnings) {
        BigDecimal freeUsdt = BigDecimal.ZERO;
        BigDecimal nonUsdt = BigDecimal.ZERO;
        try {
            for (OkxTradingService.SpotHolding h : okxTradingService.getSpotHoldings()) {
                BigDecimal eqUsd = h.eqUsd == null ? BigDecimal.ZERO : h.eqUsd;
                if ("USDT".equalsIgnoreCase(h.ccy)) {
                    freeUsdt = h.availBal == null ? BigDecimal.ZERO : h.availBal;
                } else {
                    nonUsdt = nonUsdt.add(eqUsd);
                }
            }
        } catch (Exception e) {
            warnings.add("TRADING_ACCOUNT_READ_FAILED:" + e.getMessage());
        }
        BigDecimal earn = BigDecimal.ZERO;
        try {
            earn = okxEarnService.getBalance("USDT").stream()
                    .map(OkxEarnService.EarnBalance::amt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception e) {
            warnings.add("EARN_READ_FAILED:" + e.getMessage());
        }
        return new Capital(freeUsdt, nonUsdt, freeUsdt.add(nonUsdt), earn);
    }

    private Exposure readExposure(String symbol, List<String> warnings) {
        BigDecimal autoCost = BigDecimal.ZERO;
        BigDecimal autoMarket = BigDecimal.ZERO;
        Map<String, BigDecimal> priceCache = new HashMap<>();
        try {
            for (BtLiveSignal p : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
                BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
                BigDecimal qty = p.getTradedQty();
                if (entry != null && qty != null) {
                    autoCost = autoCost.add(entry.multiply(qty));
                    BigDecimal mark = lastPrice(p.getSymbol(), priceCache);
                    if (mark != null) {
                        autoMarket = autoMarket.add(mark.multiply(qty));
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("AUTO_EXPOSURE_READ_FAILED:" + e.getMessage());
        }

        BigDecimal gridActual = BigDecimal.ZERO;
        BigDecimal gridMax = BigDecimal.ZERO;
        try {
            for (BtGrid grid : gridRepository.findByEnabledTrueAndClosedAtIsNull()) {
                gridMax = gridMax.add(grid.getPerLevelUsdt().multiply(BigDecimal.valueOf(grid.getGridCount())));
                for (BtGridLevel level : gridLevelRepository.findByGridIdAndStatusIn(
                        grid.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"))) {
                    if (level.getFilledPrice() != null && level.getFilledQty() != null) {
                        gridActual = gridActual.add(level.getFilledPrice().multiply(level.getFilledQty()));
                    } else if (symbol.equalsIgnoreCase(grid.getSymbol()) && level.getFilledQty() != null) {
                        BigDecimal mark = lastPrice(symbol, priceCache);
                        if (mark != null) {
                            gridActual = gridActual.add(mark.multiply(level.getFilledQty()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("GRID_EXPOSURE_READ_FAILED:" + e.getMessage());
        }
        return new Exposure(autoCost, autoMarket, gridActual, gridMax);
    }

    private BigDecimal lastPrice(String symbol, Map<String, BigDecimal> cache) {
        if (symbol == null || symbol.isBlank()) return null;
        return cache.computeIfAbsent(symbol, s -> {
            try {
                return okxTradingService.getLastPrice(s);
            } catch (Exception ignored) {
                return null;
            }
        });
    }

    private String recommendedPolicy(boolean capitalSegmentationMissRisk,
                                     BigDecimal redeemNeeded,
                                     BigDecimal microAddCapacity) {
        if (!capitalSegmentationMissRisk) {
            return "OK: liquid trading reserve is broadly aligned with observed capital.";
        }
        if (microAddCapacity.signum() <= 0) {
            return "CREATE_SCORE_BUY_RESERVE: preview redeem from Earn before next panic-bottom window; keep auto-redeem disabled until explicitly approved.";
        }
        return "ENABLE_MICRO_ADD_POLICY_PREVIEW: capital exists, but write path must use reserve buckets instead of free-USDT-only sizing.";
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static void putMoney(ObjectNode node, String key, BigDecimal value) {
        node.put(key, money(value));
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static double pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) return 0.0;
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.stream().distinct().forEach(arr::add);
        return arr;
    }

    private String write(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return root.toString();
        }
    }

    private record Capital(BigDecimal freeUsdt,
                           BigDecimal spotNonUsdtUsd,
                           BigDecimal tradingAccountUsd,
                           BigDecimal earnFlexibleUsdt) {
    }

    private record Exposure(BigDecimal autoTradeCost,
                            BigDecimal autoTradeMarketValue,
                            BigDecimal gridActualExposure,
                            BigDecimal gridMaxExposure) {
    }

    public record CapitalAllocationSnapshot(String symbol,
                                            BigDecimal freeUsdt,
                                            BigDecimal spotNonUsdtValue,
                                            BigDecimal tradingAccountObservedUsd,
                                            BigDecimal earnFlexibleUsdt,
                                            BigDecimal totalObservedCapitalUsdt,
                                            BigDecimal autoTradeCostExposureUsdt,
                                            BigDecimal autoTradeMarketValueUsdt,
                                            BigDecimal gridActualExposureUsdt,
                                            BigDecimal gridMaxExposureUsdt,
                                            BigDecimal gridRemainingCapitalClaimUsdt,
                                            BigDecimal currentTradingExposureUsdt,
                                            BigDecimal scoreBuyReserveTargetUsdt,
                                            BigDecimal panicBuyReserveTargetUsdt,
                                            BigDecimal minKeepLiquidUsdt,
                                            BigDecimal liquidAfterReserveUsdt,
                                            BigDecimal scoreBuyRedeemNeededUsdt,
                                            BigDecimal scoreBuyRedeemableFromEarnUsdt,
                                            BigDecimal deployableAfterPlannedRedeemUsdt,
                                            BigDecimal microAddNotionalUsdt,
                                            int microAddCapacityCount,
                                            boolean missedOpportunityDueToCapitalSegmentation,
                                            String recommendedPolicy,
                                            List<String> warnings,
                                            List<String> blockers) {
        public boolean requiresEarnReserveTopUp() {
            return scoreBuyRedeemNeededUsdt != null && scoreBuyRedeemNeededUsdt.signum() > 0;
        }
    }
}
