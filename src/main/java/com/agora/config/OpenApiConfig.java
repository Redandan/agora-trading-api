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
    public OpenAPI agoraMarketOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agora Market API")
                        .description("RESTful API documentation for Agora Market - A comprehensive e-commerce platform")
                        .version("1.0")
                        .contact(new Contact()
                                .name("Purr Tech LLC")
                                .email("admin@PURRTECHLLC.COM")))
                .servers(Arrays.asList(
                        new Server()
                                .url("https://agoramarketapi.purrtechllc.com/api")
                                .description("Production API Server")
                ));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("agora-market-api")
                .packagesToScan("com.agora.controller")
                .packagesToExclude("org.telegram")
                .build();
    }
} 