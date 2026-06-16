package com.agora.infra.bot;

import com.agora.config.TradingInternalApiProperties;
import com.agora.dto.internalapi.TradingReportResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Hidden
@RestController
@RequestMapping("/trading/internal/reports")
@RequiredArgsConstructor
public class InternalTradingReportController {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final TradingReportFacade tradingReportFacade;
    private final TradingInternalApiProperties properties;

    @GetMapping("/current")
    public ResponseEntity<?> current(@RequestHeader(value = INTERNAL_API_KEY_HEADER, required = false) String apiKey) {
        if (!isAuthorized(apiKey)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(new TradingReportResponse("current", tradingReportFacade.currentSituation()));
    }

    @GetMapping("/analysis")
    public ResponseEntity<?> analysis(@RequestHeader(value = INTERNAL_API_KEY_HEADER, required = false) String apiKey) {
        if (!isAuthorized(apiKey)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(new TradingReportResponse("analysis", tradingReportFacade.marketAnalysis()));
    }

    @GetMapping("/weekly")
    public ResponseEntity<?> weekly(@RequestHeader(value = INTERNAL_API_KEY_HEADER, required = false) String apiKey) {
        if (!isAuthorized(apiKey)) {
            return unauthorizedResponse();
        }
        return ResponseEntity.ok(new TradingReportResponse("weekly", tradingReportFacade.weeklyReport()));
    }

    private boolean isAuthorized(String apiKey) {
        if (!properties.isConfigured() || apiKey == null || apiKey.isBlank()) {
            return false;
        }
        byte[] expected = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        byte[] actual = apiKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", Map.of(
                        "code", "INVALID_INTERNAL_API_KEY",
                        "message", "Valid internal API key is required"
                )));
    }
}
