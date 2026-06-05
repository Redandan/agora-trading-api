package com.agora.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Configuration
@Profile("local-smoke")
public class LocalSmokeSchedulingConfig {

    @PostConstruct
    void logSchedulingDisabled() {
        log.info("[LocalSmoke] Scheduling disabled for local-smoke profile");
    }
}
