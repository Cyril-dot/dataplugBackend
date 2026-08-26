package com.databundleHum.OnetBundleHub.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient + credentials for the DataPrimo Provider API.
 *
 * Bean name "dataPrimoWebClient" — must match the @Qualifier used in
 * DataPrimoService.
 *
 * Set in application.properties:
 *   dataprimo.api-key=dp_live_your_key_here   (or dp_test_... for sandbox)
 *   dataprimo.base-url=https://dataprimo.org/api/v1   (optional, defaults below)
 */
@Slf4j
@Getter
@Configuration
public class DataPrimoConfig {

    @Value("${dataprimo.api-key}")
    private String apiKey;

    @Value("${dataprimo.base-url:https://dataprimo.org/api/v1}")
    private String baseUrl;

    @Bean(name = "dataPrimoWebClient")
    public WebClient dataPrimoWebClient() {
        log.info("[DATAPRIMO-CONFIG] Building dataPrimoWebClient — baseUrl={} apiKeyPrefix={}",
                baseUrl,
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) + "..." : "NULL");

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
