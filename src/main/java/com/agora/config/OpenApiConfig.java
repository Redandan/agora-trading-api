package com.agora.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agoraTradingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agora Trading API")
                        .description("Trading service API documentation for MCP and internal trading operations")
                        .version("1.0")
                        .contact(new Contact()
                                .name("Purr Tech LLC")
                                .email("admin@PURRTECHLLC.COM")))
                .servers(Arrays.asList(
                        new Server()
                                .url("https://agoratrading.purrtechllc.com/api/trading")
                                .description("Trading API Server")
                ));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("agora-trading-api")
                .packagesToScan("com.agora.mcp")
                .packagesToExclude("org.telegram")
                .build();
    }
} 
