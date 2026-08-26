package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.KorapayConfig;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * All Korapay interactions — charge initialisation (Checkout Redirect),
 * verification, and webhook HMAC-SHA256 signature validation.
 *
 * IMPORTANT: unlike Paystack, Korapay amounts are expressed in the
 * major currency unit (e.g. GHS, not pesewas). Do NOT multiply by 100.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KorapayService {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final WebClient korapayWebClient;   // injected by name from KorapayConfig
    private final KorapayConfig korapayConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Reference generation ──────────────────────────────────────────────────

    public String generateReference() {
        String ref = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.debug("[KORAPAY] Generated reference suffix: {}", ref);
        return ref;
    }

    // ── Initiate transaction ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> initiateTransaction(String email, String customerName, BigDecimal amountGhc,
                                                     String reference, String redirectUrl,
                                                     Map<String, Object> metadata) {

        Map<String, Object> customer = Map.of(
                "email", email,
                "name",  customerName != null ? customerName : email
        );

        Map<String, Object> payload = Map.of(
                "amount",       amountGhc,          // major unit, no conversion
                "currency",     "GHS",
                "reference",    reference,
                "redirect_url", redirectUrl != null ? redirectUrl : "",
                "customer",     customer,
                "metadata",     metadata != null ? metadata : Map.of()
        );

        log.info("[KORAPAY] Initiate: ref={} email={} customerName={} amountGhc={} redirectUrl={}",
                reference, email, customerName, amountGhc, redirectUrl);

        try {
            Map<String, Object> response = korapayWebClient.post()
                    .uri("/merchant/api/v1/charges/initialize")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("[KORAPAY] Initiate raw response: {}", response);

            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                log.error("[KORAPAY] Initialisation failed — ref={} response={}", reference, response);
                throw new UpstreamApiException("Korapay initialisation failed for ref: " + reference);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            log.info("[KORAPAY] ✔ Transaction initialised: ref={} checkoutUrl={}",
                    reference, data != null ? data.get("checkout_url") : null);
            return data;

        } catch (WebClientResponseException ex) {
            log.error("[KORAPAY] HTTP error during initiate: status={} body={} ref={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), reference);
            throw new UpstreamApiException("Korapay error: " + ex.getMessage());
        }
    }

    // ── Verify transaction ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyTransaction(String reference) {
        log.info("[KORAPAY] Verify: ref={}", reference);

        try {
            Map<String, Object> response = korapayWebClient.get()
                    .uri("/merchant/api/v1/charges/{reference}", reference)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("[KORAPAY] Verify raw response: ref={} response={}", reference, response);

            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                throw new UpstreamApiException("Korapay verification returned non-success for ref: " + reference);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String txStatus = (String) data.get("status");

            if (!"success".equalsIgnoreCase(txStatus)) {
                log.warn("[KORAPAY] Transaction not successful — ref={} status={}", reference, txStatus);
                throw new UpstreamApiException(
                        "Korapay transaction not successful. Status: " + txStatus + " ref: " + reference);
            }

            log.info("[KORAPAY] ✔ Transaction verified successfully: ref={}", reference);
            return data;

        } catch (WebClientResponseException ex) {
            log.error("[KORAPAY] HTTP error during verify: status={} body={} ref={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), reference);
            throw new UpstreamApiException("Korapay verify error: " + ex.getMessage());
        }
    }

    public BigDecimal extractAmountGhc(Map<String, Object> txData) {
        Object raw = txData.get("amount");
        BigDecimal result = (raw instanceof Number n)
                ? new BigDecimal(n.toString())
                : new BigDecimal(String.valueOf(raw));
        log.debug("[KORAPAY] extractAmountGhc: raw={} result={}", raw, result);
        return result;
    }

    // ── Webhook signature ─────────────────────────────────────────────────────

    public boolean isWebhookSignatureValid(byte[] rawBody, String signature) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                log.warn("[KORAPAY-SIG] Webhook payload missing 'data' object — cannot verify signature");
                return false;
            }

            String dataJson = objectMapper.writeValueAsString(dataNode);

            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    korapayConfig.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(dataJson.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(computed);

            boolean valid = hex.equalsIgnoreCase(signature);
            log.info("[KORAPAY-SIG] Signature check — valid={}", valid);
            if (!valid) {
                log.warn("[KORAPAY-SIG] Mismatch. Expected={} Got={}", hex, signature);
            }
            return valid;
        } catch (Exception ex) {
            log.error("[KORAPAY-SIG] HMAC-SHA256 computation failed", ex);
            return false;
        }
    }
}
