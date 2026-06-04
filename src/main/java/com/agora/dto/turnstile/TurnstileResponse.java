package com.agora.dto.turnstile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnstileResponse {
    private boolean success;
    private String challengeTs;
    private String hostname;
    private List<String> errorCodes;
    private String action;
    private String cdata;
    
    @JsonProperty("challenge_ts")
    public String getChallengeTs() {
        return challengeTs;
    }
    
    @JsonProperty("error-codes")
    public List<String> getErrorCodes() {
        return errorCodes;
    }
}
