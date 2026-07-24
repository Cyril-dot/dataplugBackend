package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.PaystackConfig;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * All Paystack interactions — transaction initialisation, verification,
 * and webhook HMAC-SHA512 signature validation.
 *
 * Paystack amounts are always in the smallest currency unit (pesewas for GHS).
 * This service converts GHS → pesewas and back transparently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackService {

    private static final String HMAC_ALGO = "HmacSHA512";
    /** 1 GHS = 100 pesewas */
    private static final BigDecimal PESEWAS_PER_GHS = new BigDecimal("100");

    private final WebClient paystackWebClient;   // injected by name from PaystackConfig
    private final PaystackConfig paystackConfig;

    // ── Reference generation ──────────────────────────────────────────────────

    /**
     * Generate a unique Paystack transaction reference suffix.
     *
     * NOTE: OrderService prefixes this with the site domain, producing
     * references like "databaygh.shop-A1B2C3D4E5F6A1B2". The old "DBH-"
     * prefix was removed so the site domain is the only prefix present.
     */
    public String generateReference() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    // ── Initiate transaction ──────────────────────────────────────────────────

    /**
     * Initialise a Paystack transaction and return the authorisation URL / reference.
     * Frontend uses the reference to open the Paystack popup (inline JS SDK).
     *
     * @param email       Customer email (required by Paystack; use platform email for guests)
     * @param amountGhc   Amount in GHS
     * @param reference   Pre-generated reference
     * @param metadata    Optional metadata map stored against the transaction on Paystack
     * @return            Paystack initialisation response as a raw Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> initiateTransaction(String email, BigDecimal amountGhc,
                                                   String reference, Map<String, Object> metadata) {
        long amountPesewas = toSmallestUnit(amountGhc);

        Map<String, Object> payload = Map.of(
                "email",     email,
                "amount",    amountPesewas,
                "reference", reference,
                "currency",  "GHS",
                "metadata",  metadata != null ? metadata : Map.of()
        );

        log.info("Paystack initiate: ref={} email={} amountPesewas={}", reference, email, amountPesewas);

        try {
            Map<String, Object> response = paystackWebClient.post()
                    .uri("/transaction/initialize")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                throw new UpstreamApiException("Paystack initialisation failed for ref: " + reference);
            }

            log.info("Paystack transaction initialised: ref={}", reference);
            return (Map<String, Object>) response.get("data");

        } catch (WebClientResponseException ex) {
            log.error("Paystack HTTP error during initiate: status={} body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new UpstreamApiException("Paystack error: " + ex.getMessage());
        }
    }

    // ── Verify transaction ────────────────────────────────────────────────────

    /**
     * Verify a Paystack transaction by reference.
     * Used for manual top-up verification and as a fallback if the webhook was missed.
     *
     * @return  The full Paystack transaction data map on success
     * @throws  UpstreamApiException if the transaction is not successful
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyTransaction(String reference) {
        log.info("Paystack verify: ref={}", reference);

        try {
            Map<String, Object> response = paystackWebClient.get()
                    .uri("/transaction/verify/{reference}", reference)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                throw new UpstreamApiException("Paystack verification returned non-success for ref: " + reference);
            }

            Map<String, Object> data   = (Map<String, Object>) response.get("data");
            String txStatus = (String) data.get("status");

            if (!"success".equalsIgnoreCase(txStatus)) {
                throw new UpstreamApiException(
                        "Paystack transaction not successful. Status: " + txStatus + " ref: " + reference);
            }

            log.info("Paystack transaction verified successfully: ref={}", reference);
            return data;

        } catch (WebClientResponseException ex) {
            log.error("Paystack HTTP error during verify: status={} body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new UpstreamApiException("Paystack verify error: " + ex.getMessage());
        }
    }

    /**
     * Extract the GHS amount from a verified Paystack transaction data map.
     */
    public BigDecimal extractAmountGhc(Map<String, Object> txData) {
        Object raw = txData.get("amount");
        long pesewas = raw instanceof Integer i ? i.longValue() : ((Number) raw).longValue();
        return BigDecimal.valueOf(pesewas).divide(PESEWAS_PER_GHS, 2, RoundingMode.HALF_UP);
    }

    // ── Webhook signature ─────────────────────────────────────────────────────

    /**
     * Validate the X-Paystack-Signature header using HMAC-SHA512.
     * Must be called before processing any webhook event.
     *
     * @param rawBody   Raw request body bytes — MUST be the original bytes, not parsed JSON
     * @param signature Value of X-Paystack-Signature header
     * @return true if the signature matches
     */
    public boolean isWebhookSignatureValid(byte[] rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    paystackConfig.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(rawBody);
            String hex = HexFormat.of().formatHex(computed);
            boolean valid = hex.equalsIgnoreCase(signature);
            if (!valid) {
                log.warn("Paystack webhook signature mismatch. Expected={} Got={}", hex, signature);
            }
            return valid;
        } catch (Exception ex) {
            log.error("HMAC-SHA512 computation failed", ex);
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert GHS to pesewas (Paystack's required unit). */
    public long toSmallestUnit(BigDecimal amountGhc) {
        return amountGhc.multiply(PESEWAS_PER_GHS).setScale(0, RoundingMode.HALF_UP).longValue();
    }
}