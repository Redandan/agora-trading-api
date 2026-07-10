package com.agora.service.backtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingViewGoldenTruthVerifierTest {

    @TempDir
    Path tempDir;

    private final TradingViewGoldenTruthVerifier verifier = new TradingViewGoldenTruthVerifier();

    @Test
    void missingGoldenTruthFailsClosed() {
        TradingViewGoldenTruthVerifier.VerificationResult result = verifier.verify("", List.of());

        assertThat(result.status()).isEqualTo("GOLDEN_TRUTH_UNAVAILABLE");
        assertThat(result.exactParity()).isFalse();
        assertThat(result.blocker()).isEqualTo("GOLDEN_TRUTH_PATH_NOT_CONFIGURED");
    }

    @Test
    void exactCsvBuyPointsAndNnWithinTolerancePass() throws Exception {
        Path csv = tempDir.resolve("golden.csv");
        Files.writeString(csv, "time,reason,label,qty,nn_output\n"
                + "2026-01-01T00:00:00,TRADINGVIEW_AI_BUY_SIGNAL,AI buy,5000,0.3810000\n"
                + "2026-01-02T00:00:00,TRADINGVIEW_RELATIVE_LOW,Relative low,1000,0.4000000\n");
        List<TradingViewGoldenTruthVerifier.Intent> actual = List.of(
                intent("2026-01-01T00:00:00", "TRADINGVIEW_AI_BUY_SIGNAL", "AI buy", "5000.0", 0.3810004),
                intent("2026-01-02T00:00:00", "TRADINGVIEW_RELATIVE_LOW", "Relative low", "1000", 0.4000002));

        TradingViewGoldenTruthVerifier.VerificationResult result = verifier.verify(csv.toString(), actual);

        assertThat(result.status()).isEqualTo("PASS_EXACT_PARITY");
        assertThat(result.exactParity()).isTrue();
        assertThat(result.missingIntentCount()).isZero();
        assertThat(result.extraIntentCount()).isZero();
        assertThat(result.nnCompared()).isTrue();
        assertThat(result.maxNnError()).isLessThanOrEqualTo(1e-6);
        assertThat(result.goldenSha256()).hasSize(64);
    }

    @Test
    void extraIntentOrNnDriftFailsParity() throws Exception {
        Path csv = tempDir.resolve("golden.csv");
        Files.writeString(csv, "time,reason,label,qty,nn_output\n"
                + "2026-01-01T00:00:00,TRADINGVIEW_AI_BUY_SIGNAL,AI buy,5000,0.381\n");
        List<TradingViewGoldenTruthVerifier.Intent> actual = List.of(
                intent("2026-01-01T00:00:00", "TRADINGVIEW_AI_BUY_SIGNAL", "AI buy", "5000", 0.39),
                intent("2026-01-02T00:00:00", "TRADINGVIEW_RELATIVE_LOW", "Relative low", "1000", 0.4));

        TradingViewGoldenTruthVerifier.VerificationResult result = verifier.verify(csv.toString(), actual);

        assertThat(result.status()).isEqualTo("FAIL_PARITY_MISMATCH");
        assertThat(result.exactParity()).isFalse();
        assertThat(result.extraIntentCount()).isEqualTo(1);
        assertThat(result.maxNnError()).isNotFinite();
    }

    private TradingViewGoldenTruthVerifier.Intent intent(String time, String reason, String label,
                                                         String quantity, Double nn) {
        return new TradingViewGoldenTruthVerifier.Intent(
                LocalDateTime.parse(time), reason, label, new BigDecimal(quantity), nn);
    }
}
