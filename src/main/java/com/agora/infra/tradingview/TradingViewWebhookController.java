package com.agora.infra.tradingview;

import com.agora.dto.tradingview.TradingViewWebhookResponse;
import com.agora.service.tradingview.TradingViewWebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TradingViewWebhookController {

    private final TradingViewWebhookService service;

    @PostMapping("/tradingview/webhook")
    public ResponseEntity<TradingViewWebhookResponse> receive(@RequestBody JsonNode payload,
                                                              HttpServletRequest request) {
        TradingViewWebhookService.HandlingResult result = service.handle(payload, request.getRemoteAddr());
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
