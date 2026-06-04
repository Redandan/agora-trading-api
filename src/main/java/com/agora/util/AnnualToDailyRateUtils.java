package com.agora.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.MathContext;

public class AnnualToDailyRateUtils {
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal MIN_RATE = new BigDecimal("0.00000001"); // 最小利率 0.000001%

    /**
     * 計算日利率
     * @param annualRate 年利率（例如：0.1 表示 10%）
     * @return 日利率
     * @throws IllegalArgumentException 如果年利率小於等於0或大於等於1
     */
    public static BigDecimal calculateDailyRate(BigDecimal annualRate) {
        // 參數驗證
        if (annualRate == null) {
            throw new IllegalArgumentException("年利率不能為空");
        }
        if (annualRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("年利率必須大於0");
        }
        if (annualRate.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException("年利率必須小於1");
        }

        // 使用泰勒級數展開計算自然對數
        BigDecimal base = ONE.add(annualRate);
        BigDecimal lnBase = calculateLn(base);
        
        // 計算日利率
        BigDecimal exponent = ONE.divide(DAYS_IN_YEAR, MC);
        BigDecimal power = lnBase.multiply(exponent, MC);
        BigDecimal dailyRate = calculateExp(power).subtract(ONE, MC);

        // 確保結果不小於最小利率
        return dailyRate.max(MIN_RATE);
    }

    /**
     * 使用泰勒級數計算自然對數
     */
    private static BigDecimal calculateLn(BigDecimal x) {
        if (x.compareTo(ONE) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal result = BigDecimal.ZERO;
        BigDecimal term = x.subtract(ONE);
        BigDecimal power = term;
        int n = 1;
        BigDecimal lastResult;

        do {
            lastResult = result;
            if (n % 2 == 1) {
                result = result.add(power.divide(new BigDecimal(n), MC));
            } else {
                result = result.subtract(power.divide(new BigDecimal(n), MC));
            }
            power = power.multiply(term, MC);
            n++;
        } while (result.subtract(lastResult).abs().compareTo(MIN_RATE) > 0);

        return result;
    }

    /**
     * 使用泰勒級數計算指數函數
     */
    private static BigDecimal calculateExp(BigDecimal x) {
        BigDecimal result = ONE;
        BigDecimal term = ONE;
        int n = 1;
        BigDecimal lastResult;

        do {
            lastResult = result;
            term = term.multiply(x, MC).divide(new BigDecimal(n), MC);
            result = result.add(term, MC);
            n++;
        } while (term.abs().compareTo(MIN_RATE) > 0);

        return result;
    }

    public static void main(String[] args) {
        // 測試不同年利率
        BigDecimal[] testRates = {
            new BigDecimal("0.05"),  // 5%
            new BigDecimal("0.1"),   // 10%
            new BigDecimal("0.15"),  // 15%
            new BigDecimal("0.2")    // 20%
        };

        for (BigDecimal rate : testRates) {
            BigDecimal dailyRate = calculateDailyRate(rate);
            System.out.printf("年利率 %.2f%% 的日利率約為 %.8f%%%n", 
                rate.multiply(new BigDecimal("100")),
                dailyRate.multiply(new BigDecimal("100")));
        }
    }
}
