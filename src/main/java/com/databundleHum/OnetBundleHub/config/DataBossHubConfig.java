package com.databundleHum.OnetBundleHub.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient + credentials for the DataBossHub delivery-checker API
 * (bbhubportal.com). Used ONLY for the phone-delivery corroboration
 * feature — see DataBossHubService's Javadoc for why this is a manual
 * admin diagnostic tool, not an automated order-status source of truth.
 *
 * Set in application.properties:
 *   databosshub.api-key=your_api_key_here
 *   databosshub.base-url=https://bbhubportal.com/api/v1   (optional, defaults below)
 */
@Slf4j
@Getter
@Configuration
public class DataBossHubConfig {

    @Value("${databosshub.api-key}")
    private String apiKey;

    @Value("${databosshub.base-url:https://bbhubportal.com/api/v1}")
    private String baseUrl;

    @Bean(name = "dataBossHubWebClient")
    public WebClient dataBossHubWebClient() {
        log.info("[DATABOSSHUB-CONFIG] Building dataBossHubWebClient — baseUrl={} apiKeyPrefix={}",
                baseUrl,
                apiKey != null ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..." : "NULL");

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-API-KEY", apiKey)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
