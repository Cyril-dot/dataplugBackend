package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.PaystackConfig;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Paystack checkout integration.
 *
 * Paystack amounts are sent in the smallest currency unit: pesewas for GHS.
 * Fulfilment is only allowed after a successful server-side verification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackService {

    private static final String HMAC_ALGO = "HmacSHA512";

    private final WebClient paystackWebClient;
    private final PaystackConfig paystackConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateReference() {
        String ref = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.debug("[PAYSTACK] Generated reference suffix: {}", ref);
        return ref;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> initiateTransaction(String email, String customerName, BigDecimal amountGhc,
                                                    String reference, String redirectUrl,
                                                    Map<String, Object> metadata) {
        long amountPesewas = amountGhc
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("amount", amountPesewas);
        payload.put("currency", "GHS");
        payload.put("reference", reference);
        payload.put("callback_url", redirectUrl == null ? "" : redirectUrl);
        payload.put("metadata", metadata == null ? Map.of() : metadata);

        log.info("[PAYSTACK] Initialize: ref={} email={} amountGhc={} amountPesewas={} callbackUrl={}",
                reference, email, amountGhc, amountPesewas, redirectUrl);

        try {
            Map<String, Object> response = paystackWebClient.post()
                    .uri("/transaction/initialize")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("[PAYSTACK] Initialize response: ref={} response={}", reference, response);
            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                throw new UpstreamApiException("Paystack initialization failed for ref: " + reference);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null || data.get("authorization_url") == null) {
                throw new UpstreamApiException("Paystack did not return an authorization URL for ref: " + reference);
            }
            // Existing checker/storefront DTOs still call this field checkoutUrl.
            // Keeping the alias avoids a breaking API change while the provider changes.
            data.put("checkout_url", data.get("authorization_url"));
            log.info("[PAYSTACK] Transaction initialized: ref={} authorizationUrl={}",
                    reference, data.get("authorization_url"));
            return data;
        } catch (WebClientResponseException ex) {
            log.error("[PAYSTACK] HTTP error during initialize: status={} body={} ref={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), reference);
            throw new UpstreamApiException("Paystack: " + extractPaystackErrorMessage(ex));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyTransaction(String reference) {
        log.info("[PAYSTACK] Verify: ref={}", reference);
        try {
            Map<String, Object> response = paystackWebClient.get()
                    .uri("/transaction/verify/{reference}", reference)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("[PAYSTACK] Verify response: ref={} response={}", reference, response);
            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                throw new UpstreamApiException("Paystack verification returned an unsuccessful API response for ref: " + reference);
            }
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String transactionStatus = data == null ? null : String.valueOf(data.get("status"));
            if (!"success".equalsIgnoreCase(transactionStatus)) {
                log.warn("[PAYSTACK] Transaction not successful: ref={} status={}", reference, transactionStatus);
                throw new UpstreamApiException("Paystack transaction not successful. Status: "
                        + transactionStatus + " ref: " + reference);
            }
            if (data.get("reference") != null && !reference.equals(String.valueOf(data.get("reference")))) {
                throw new UpstreamApiException("Paystack reference mismatch for ref: " + reference);
            }
            log.info("[PAYSTACK] Transaction verified successfully: ref={}", reference);
            return data;
        } catch (WebClientResponseException ex) {
            log.error("[PAYSTACK] HTTP error during verify: status={} body={} ref={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), reference);
            throw new UpstreamApiException("Paystack: " + extractPaystackErrorMessage(ex));
        }
    }

    /** Requests a full refund for a settled Paystack transaction. */
    @SuppressWarnings("unchecked")
    public RefundInitiation initiateFullRefund(String paymentReference, String refundReference, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transaction", paymentReference);
        payload.put("customer_note", reason);
        payload.put("merchant_note", reason);

        log.info("[PAYSTACK] Initiating full refund: paymentReference={} refundReference={}",
                paymentReference, refundReference);
        try {
            Map<String, Object> response = paystackWebClient.post()
                    .uri("/refund")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
                Object message = response == null ? "empty response" : response.get("message");
                throw new UpstreamApiException("Paystack refund request was rejected: " + message);
            }
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String status = data == null || data.get("status") == null
                    ? "pending" : String.valueOf(data.get("status")).toLowerCase();
            log.info("[PAYSTACK] Refund initiated: paymentReference={} refundReference={} status={}",
                    paymentReference, refundReference, status);
            return new RefundInitiation(refundReference, status);
        } catch (WebClientResponseException ex) {
            log.error("[PAYSTACK] HTTP error during refund: status={} body={} paymentReference={} refundReference={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString(), paymentReference, refundReference);
            throw new UpstreamApiException("Paystack refund: " + extractPaystackErrorMessage(ex));
        }
    }

    public record RefundInitiation(String reference, String status) {}

    /** Converts Paystack's pesewa amount back to GHS for existing wallet logic. */
    public BigDecimal extractAmountGhc(Map<String, Object> transactionData) {
        Object raw = transactionData.get("amount");
        BigDecimal pesewas = raw instanceof Number n
                ? new BigDecimal(n.toString())
                : new BigDecimal(String.valueOf(raw));
        BigDecimal result = pesewas.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        log.debug("[PAYSTACK] extractAmountGhc: rawPesewas={} resultGhc={}", raw, result);
        return result;
    }

    /** Paystack signs the complete raw webhook body with HMAC-SHA512. */
    public boolean isWebhookSignatureValid(byte[] rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(paystackConfig.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(rawBody);
            boolean valid = MessageDigest.isEqual(
                    HexFormat.of().formatHex(computed).getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            log.info("[PAYSTACK-SIG] HMAC-SHA512 valid={}", valid);
            return valid;
        } catch (Exception ex) {
            log.error("[PAYSTACK-SIG] HMAC-SHA512 validation failed", ex);
            return false;
        }
    }

    private String extractPaystackErrorMessage(WebClientResponseException ex) {
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            String message = body.path("message").asText(null);
            if (message != null && !message.isBlank()) return message;
        } catch (Exception parseEx) {
            log.warn("[PAYSTACK] Could not parse error response body: {}", ex.getResponseBodyAsString());
        }
        return ex.getMessage();
    }
}
