package com.agora.service.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the retained production verifier, not a copy of its matching expressions. */
class RuntimeLogRecoveryTest {
    @TempDir Path directory;
    private static final String WARN = "2026-10-05T05:05:01.550Z WARN 953746 --- c.a.s.market.BinanceWsKlineService : [BinanceWS] Reconnecting (1/5) in 5s: SPOT BTCUSDT@kline_1d reason=connection lost\n";
    private static final String RECOVERED = "2026-10-05T05:05:07.363Z INFO 953746 --- c.a.s.market.BinanceWsKlineService : [BinanceWS] Connected(reconnect): SPOT BTCUSDT@kline_1d\n";

    @Test void realWarningWithLaterMatchingRecoveryPasses() throws Exception { verify(WARN + RECOVERED, true, Map.of()); }
    @Test void stillDisconnectedOrRecoveryBeforeLastWarningFails() throws Exception {
        verify(WARN, false, Map.of()); verify(RECOVERED + WARN, false, Map.of());
        verify(WARN + RECOVERED + WARN, false, Map.of());
    }
    @Test void differentMarketSymbolOrIntervalCannotProveRecovery() throws Exception {
        for (String recovery : List.of(RECOVERED.replace("SPOT", "FUTURES"), RECOVERED.replace("BTCUSDT", "ETHUSDT"),
                RECOVERED.replace("kline_1d", "kline_1h"))) verify(WARN + recovery, false, Map.of());
    }
    @Test void tooFrequentReconnectsUnknownWarningsErrorsAndOrdersStillFail() throws Exception {
        verify(WARN + RECOVERED, false, Map.of("MAX_BINANCE_WS_RECONNECT_WARN", "0"));
        verify(WARN + RECOVERED + "2026-10-05T05:06:00Z WARN service : unknown warning\n", false, Map.of());
        verify(WARN + RECOVERED + "2026-10-05T05:06:00Z ERROR service : failure\n", false, Map.of());
        verify(WARN + RECOVERED + "2026-10-05T05:06:00Z INFO service : OKX order submitted\n", false, Map.of());
    }
    private void verify(String log, boolean success, Map<String,String> options) throws Exception {
        Path fixture = Files.createTempDirectory(directory, "case");
        Files.writeString(fixture.resolve("app.port"), "8085\n");
        Path runLog = Files.writeString(fixture.resolve("run.log"), log);
        Path output = fixture.resolve("output.log");
        String bash = System.getProperty("os.name").startsWith("Windows") ? "C:/Program Files/Git/bin/bash.exe" : "/bin/bash";
        var builder = new ProcessBuilder(bash, "scripts/check_server_runtime_log.sh");
        builder.environment().put("APP_DIR", fixture.toString().replace('\\', '/'));
        builder.environment().put("RUN_LOG_FILE", runLog.toString().replace('\\', '/'));
        for (String name : List.of("ALLOW_UNKNOWN_WARN", "ALLOW_RUNTIME_ERROR", "ALLOW_HIGH_RISK_LOG")) builder.environment().put(name, "0");
        builder.environment().putAll(options);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        var process = builder.start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "runtime log fixture timed out");
        assertEquals(success, process.exitValue() == 0, Files.readString(output));
    }
}
