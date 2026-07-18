package com.agora.config;

import com.agora.service.trading.evidence.okx.ExactTradeFillCollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/** Default-off, one-shot, read-and-append-only collector. It owns no scheduler or trading call. */
@Component
@ConditionalOnProperty(name = "trading.evidence.okx.exact-fill-one-shot-enabled",
        havingValue = "true", matchIfMissing = false)
@AsyncStartup("explicitly enabled exact-fill read-and-append one-shot")
@RequiredArgsConstructor
public class ExactTradeFillOneShotRunner implements ApplicationRunner {
    private final OkxEvidenceProperties properties;
    private final ExactTradeFillCollectionService collectionService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isCollectorEnabled() || !properties.isAuthenticatedIngestionEnabled()) {
            throw new IllegalStateException("EXACT_FILL_ONE_SHOT_REQUIRES_BOTH_EXPLICIT_EVIDENCE_GATES");
        }
        collectionService.collect(new ExactTradeFillCollectionService.Request(
                properties.getExactFillRunId(), properties.getAccountRefHash(), properties.getInstrumentId(),
                properties.getInstrumentType(), properties.getExactFillPageLimit(),
                properties.getExactFillMaxPages(), properties.getExactFillEffectiveFrom(),
                properties.getExactFillBindings().stream().collect(
                Collectors.toUnmodifiableMap(OkxEvidenceProperties.ExactFillBinding::getOrderId,
                        b -> new ExactTradeFillCollectionService.FillBinding(b.getCohortId(),
                                b.getRuntimeDecisionId(), b.getLiveSignalId(), b.getOrderCreatedAt(),
                                b.isOcoRequired(), b.getIntendedChildOrderId(),
                                b.getActualChildOrderId())))));
    }
}
