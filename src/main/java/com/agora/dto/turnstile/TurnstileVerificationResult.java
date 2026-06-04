package com.agora.dto.turnstile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnstileVerificationResult {
    private boolean success;
    private String challengeTs;
    private String hostname;
    private String action;
    private String cdata;
}
