package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "external.thegraph")
public record TheGraphProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("5zvR82QoaXYFyDEKLZ9t6v9adgnptxYpKpSbxtgVENFV") String uniswapV3SubgraphId
) {}
