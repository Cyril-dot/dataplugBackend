package com.databundleHum.OnetBundleHub.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Getter
@Configuration
public class PaystackConfig {

    @Value("${paystack.secret-key}")
    private String secretKey;

    @Value("${paystack.base-url:https://api.paystack.co}")
    private String baseUrl;

    @Bean
    public WebClient paystackWebClient() {
        log.info("[PAYSTACK-CONFIG] Building paystackWebClient: baseUrl={} secretKeyPresent={}",
                baseUrl, secretKey != null && !secretKey.isBlank());
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
