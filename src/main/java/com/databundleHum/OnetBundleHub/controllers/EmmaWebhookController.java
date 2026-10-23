package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import com.databundleHum.OnetBundleHub.services.EmmaDataKingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * EmmaDataKing webhook receiver — POST /api/webhooks/emma
 * (the /api/webhooks/** path is already permitAll in SecurityConfig;
 * authenticity comes from the HMAC signature, not from a session).
 *
 * Emma signs every event with HMAC-SHA256 over the raw request body using
 * the account's API Secret (whsec_…), sent redundantly in three headers:
 * X-EDK-Signature, X-Signature (sha256=…) and X-Webhook-Signature. Any one
 * matching header authenticates the event. Events handled:
 *   order.completed → order marked COMPLETED
 *   order.failed    → order marked FAILED (+ wallet refund)
 *   order.refunded  → order marked FAILED (+ wallet refund)
 * checker.* / afa.* events are acknowledged (200) and ignored — those
 * products are not fulfilled through Emma. The 60-second status poller in
 * EmmaDataKingService remains the backstop for any missed delivery.
 *
 * The shared secret is supplied via EMMADATAKING_WEBHOOK_SECRET. While it
 * is unset the endpoint refuses every call (401) rather than accept
 * unauthenticated status changes.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhook", description = "EmmaDataKing webhook receiver")
public class EmmaWebhookController {

    private final EmmaDataKingService emmaDataKingService;
    private final AppConfig appConfig;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/emma")
    @Operation(summary = "EmmaDataKing webhook — order.completed / order.failed / order.refunded")
    public ResponseEntity<Void> handleEmma(
            @RequestHeader(value = "X-EDK-Signature", required = false) String edkSignature,
            @RequestHeader(value = "X-Signature", required = false) String xSignature,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String webhookSignature,
            @RequestBody byte[] rawBody) {

        String secret = appConfig.getEmmaWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("[EMMA-WEBHOOK] Rejected: EMMADATAKING_WEBHOOK_SECRET is not configured");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!signatureValid(secret, rawBody, edkSignature, xSignature, webhookSignature)) {
            log.warn("[EMMA-WEBHOOK] Rejected: signature mismatch (bodyLength={})", rawBody.length);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception ex) {
            log.warn("[EMMA-WEBHOOK] Signed but unparseable body: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }

        String event = text(root, "event");
        if (event == null) event = text(root, "event_type");
        JsonNode data = root.path("data");
        String reference = text(data, "reference");
        if (reference == null) reference = text(data, "transaction_code");
        String failureReason = text(data, "failure_reason");
        if (failureReason == null) failureReason = text(data, "message");

        log.info("[EMMA-WEBHOOK] Event received: event={} reference={}", event, reference);
        if (event == null || reference == null) {
            // Test pings and malformed events land here — acknowledge so
            // Emma does not retry, but change nothing.
            return ResponseEntity.ok().build();
        }

        switch (event) {
            case "order.completed" -> emmaDataKingService.handleWebhookDelivered(reference);
            case "order.failed", "order.refunded" ->
                    emmaDataKingService.handleWebhookFailed(reference, failureReason);
            default -> log.info("[EMMA-WEBHOOK] Ignored event type: {}", event);
        }
        return ResponseEntity.ok().build();
    }

    /** Constant-time comparison of the expected HMAC against any of Emma's three signature headers. */
    private boolean signatureValid(String secret, byte[] rawBody,
                                   String edkSignature, String xSignature, String webhookSignature) {
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (Exception ex) {
            log.error("[EMMA-WEBHOOK] HMAC computation failed: {}", ex.getMessage());
            return false;
        }
        for (String candidate : new String[]{edkSignature, xSignature, webhookSignature}) {
            if (candidate == null || candidate.isBlank()) continue;
            String normalised = candidate.trim();
            if (normalised.startsWith("sha256=")) {
                normalised = normalised.substring("sha256=".length());
            }
            if (MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    normalised.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
