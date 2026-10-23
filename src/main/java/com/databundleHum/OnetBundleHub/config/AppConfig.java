package com.databundleHum.OnetBundleHub.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * General application configuration.
 *
 * Big Dreams Data Developer API:
 *   Base URL : https://qrzjkrkawcdoaggblvjc.supabase.co/functions/v1/developer-api
 *
 * All operations are performed via POST to the same endpoint, differentiated
 * by the "action" field in the JSON body:
 *   place_order      — purchase and send a data bundle
 *   check_balance    — retrieve wallet balance
 *   get_bundles      — list available bundles
 *   get_transactions — paginated transaction history
 *
 * Authentication : x-api-key header on every request (key starts with bh_live_).
 *
 * Network values accepted by the API:
 *   mtn      — MTN data bundles
 *   telecel  — Telecel data bundles
 *   ishare   — AirtelTigo iShare / AT_PREMIUM
 *
 * Note: AT_BIGTIME is not yet listed in the Big Dreams Data catalogue.
 *       Orders for AT_BIGTIME will be rejected by the API until support is added.
 */
@Configuration
@EnableAsync
@EnableScheduling
@Getter
public class AppConfig {

    // ── Big Dreams Data ───────────────────────────────────────────────────────

    /**
     * API key issued by the Big Dreams Data dashboard (Profile → API tab).
     * Must start with bh_live_. Supply via environment variable BIGDREAMS_API_KEY
     * in production — never hard-code or commit this value.
     */
    @Value("${bigdreams.api-key}")
    private String bigDreamsApiKey;

    /**
     * Base URL for all Big Dreams Data API calls.
     * Defaults to the live Supabase endpoint; override via BIGDREAMS_BASE_URL
     * environment variable if the provider migrates to a custom domain.
     */
    @Value("${bigdreams.base-url:https://qrzjkrkawcdoaggblvjc.supabase.co/functions/v1/developer-api}")
    private String bigDreamsBaseUrl;

    // ── EmmaDataKing ────────────────────────────────────────────────────────

    /**
     * API key issued by the EmmaDataKing developer portal
     * (emmadataking.com/agent/developer — keys start with edk_live_).
     * Supply via environment variable EMMADATAKING_API_KEY in production —
     * never hard-code or commit this value.
     */
    @Value("${emmadataking.api-key:}")
    private String emmaDataKingApiKey;

    /**
     * Base URL for all EmmaDataKing API calls.
     * Override via EMMADATAKING_BASE_URL if the provider migrates domains.
     */
    @Value("${emmadataking.base-url:https://emmadataking.com}")
    private String emmaDataKingBaseUrl;

    /**
     * Webhook signing secret issued by the EmmaDataKing developer portal
     * (starts with whsec_). Supply via environment variable
     * EMMADATAKING_WEBHOOK_SECRET in production.
     */
    @Value("${emmadataking.webhook-secret:}")
    private String emmaWebhookSecret;

    /**
     * Active data-bundle provider: "emma" (default since the Oct 2026
     * cutover) or "bigdreams" (rollback). Set via the DATA_PROVIDER
     * environment variable. Both provisioning paths and both reconcilers
     * consult this switch, so a rollback is a single env change + restart.
     */
    @Value("${data.provider:emma}")
    private String dataProvider;

    /** True when EmmaDataKing is the active fulfilment provider. */
    public boolean isEmmaProvider() {
        return !"bigdreams".equalsIgnoreCase(dataProvider);
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    /** Canonical public site URL used in referral links and non-browser redirects. */
    private String appBaseUrl = "https://www.datapackk.shop";

    // ── WebClient bean ────────────────────────────────────────────────────────

    /**
     * Shared WebClient for all Big Dreams Data API calls.
     *
     * The x-api-key header is set as a default and sent automatically on
     * every request. Note the lowercase header name required by the API.
     *
     * All requests target the single POST endpoint at the base URL; the
     * URI path is intentionally left empty here — callers use .post()
     * with no additional path segment.
     */
    @Bean(name = "bigDreamsWebClient")
    public WebClient bigDreamsWebClient() {
        return WebClient.builder()
                .baseUrl(bigDreamsBaseUrl)
                .defaultHeader("x-api-key",     bigDreamsApiKey)
                .defaultHeader("Content-Type",  "application/json")
                .defaultHeader("Accept",        "application/json")
                .build();
    }

    /**
     * Shared WebClient for all EmmaDataKing Data API calls.
     *
     * The X-API-Key header is set as a default and sent automatically on
     * every request. Callers address the versioned paths
     * (/api/v1/purchase, /api/v1/order-status/{ref}, /api/v1/packages,
     * /api/v1/balance) against this base URL.
     */
    @Bean(name = "emmaDataKingWebClient")
    public WebClient emmaDataKingWebClient() {
        return WebClient.builder()
                .baseUrl(emmaDataKingBaseUrl)
                .defaultHeader("X-API-Key",     emmaDataKingApiKey)
                .defaultHeader("Content-Type",  "application/json")
                .defaultHeader("Accept",        "application/json")
                .build();
    }
}
