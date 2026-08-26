package com.databundleHum.OnetBundleHub.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient + credentials for the Korapay Provider API.
 *
 * Set in application.properties:
 *   korapay.secret-key=sk_live_your_key_here   (or sk_test_... for sandbox)
 *   korapay.base-url=https://api.korapay.com    (optional, defaults below)
 */
@Slf4j
@Getter
@Configuration
public class KorapayConfig {

    @Value("${korapay.secret-key}")
    private String secretKey;

    @Value("${korapay.base-url:https://api.korapay.com}")
    private String baseUrl;

    @Bean
    public WebClient korapayWebClient() {
        log.info("[KORAPAY-CONFIG] Building korapayWebClient — baseUrl={} secretKeyPrefix={}",
                baseUrl,
                secretKey != null ? secretKey.substring(0, Math.min(10, secretKey.length())) + "..." : "NULL");

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
