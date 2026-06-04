package com.agora.dto.market;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class KlineSubscribeRequest {

    @NotBlank
    private String symbol;

    @NotBlank
    private String intervalCode;

    /** SPOT / FUTURES（預設 SPOT） */
    private String marketType = "SPOT";
}
