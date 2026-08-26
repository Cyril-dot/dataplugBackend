package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.services.CheckerService;
import com.databundleHum.OnetBundleHub.services.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhook", description = "Korapay webhook receiver")
public class WebhookController {

    private final OrderService orderService;
    private final CheckerService checkerService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${korapay.secret-key}")
    private String korapaySecretKey;

    /**
     * Must match OrderService.PROCESSING_CHARGE_RATE / CheckerService's
     * matching constant. Only used as a FALLBACK to back a base amount out
     * of the raw charged amount if "baseAmountGhc" is ever missing from
     * metadata — the normal path always uses the metadata value directly.
     */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    /**
     * ── CHECKER FEATURE (2026-08-26) ─────────────────────────────────────────
     * Added a "CHECKER_ORDER" branch to the routing switch below, alongside
     * the existing WALLET_TOPUP / GUEST_ORDER handling. CHECKER_ORDER
     * webhooks call checkerService.fulfilCheckerKorapayOrder(reference)
     * instead of orderService.fulfilKorapayOrder(reference) — everything
     * else about signature validation and payload parsing is unchanged.
     */
    @PostMapping("/korapay")
    @Operation(summary = "Korapay webhook — charge.success handler")
    public ResponseEntity<Void> handleKorapay(
            @RequestHeader(value = "x-korapay-signature", required = false) String signature,
            @RequestBody String rawBody) {

        log.info("[WEBHOOK] ✅ Request received at /api/webhooks/korapay");
        log.info("[WEBHOOK] Signature header present: {}", signature != null ? "YES (length=" + signature.length() + ")" : "NO - NULL");
        log.info("[WEBHOOK] Raw body length: {} chars", rawBody != null ? rawBody.length() : 0);

        JsonNode root = parsePayload(rawBody);
        if (root == null) {
            log.warn("[WEBHOOK] ❌ Body was not valid JSON — returning 400");
            return ResponseEntity.badRequest().build();
        }

        JsonNode dataNode = root.get("data");
        if (dataNode == null || dataNode.isMissingNode()) {
            log.warn("[WEBHOOK] ❌ 'data' field missing from payload — returning 400");
            return ResponseEntity.badRequest().build();
        }

        log.info("[WEBHOOK] Starting signature validation...");
        boolean signatureValid = isValidSignature(dataNode, signature);
        log.info("[WEBHOOK] Signature valid: {}", signatureValid);

        if (!signatureValid) {
            log.warn("[WEBHOOK] ❌ Signature validation FAILED — returning 401");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("[WEBHOOK] ✅ Signature validation passed");

        String event = root.path("event").asText();
        log.info("[WEBHOOK] Event type: {}", event);

        if (!"charge.success".equals(event)) {
            log.info("[WEBHOOK] Ignoring non-charge event: {} — returning 200", event);
            return ResponseEntity.ok().build();
        }

        Map<String, Object> data = MAPPER.convertValue(dataNode, Map.class);
        log.info("[WEBHOOK] Data block keys: {}", data.keySet());

        String reference = (String) data.get("reference");
        log.info("[WEBHOOK] Reference: {}", reference);
        if (reference == null || reference.isBlank()) {
            log.warn("[WEBHOOK] ❌ Reference is null or blank — returning 400");
            return ResponseEntity.badRequest().build();
        }

        Map<String, Object> meta = extractMeta(data);
        log.info("[WEBHOOK] Metadata present: {}", meta != null ? "YES, keys=" + meta.keySet() : "NO - NULL");
        String type = meta != null ? (String) meta.get("type") : null;
        log.info("[WEBHOOK] Transaction type from metadata: {}", type);

        Object rawAmount = data.get("amount");
        log.info("[WEBHOOK] Raw amount from Korapay: {}", rawAmount);

        log.info("[WEBHOOK] Routing: ref={} type={}", reference, type);
        try {
            if ("WALLET_TOPUP".equals(type)) {
                String userIdStr = (String) meta.get("userId");
                log.info("[WEBHOOK] userId from metadata: {}", userIdStr);
                UUID userId = UUID.fromString(userIdStr);

                BigDecimal chargedAmountGhc = extractAmountGhc(data);
                BigDecimal baseAmountGhc = extractBaseAmountGhc(meta, chargedAmountGhc, reference);

                log.info("[WEBHOOK] Processing WALLET_TOPUP: userId={} chargedAmount=GHS{} " +
                                "creditAmount=GHS{} ref={}",
                        userId, chargedAmountGhc, baseAmountGhc, reference);
                orderService.processTopUpWebhook(userId, baseAmountGhc, reference);
                log.info("[WEBHOOK] ✅ WALLET_TOPUP credited successfully: userId={} amount=GHS{} ref={}",
                        userId, baseAmountGhc, reference);

            } else if ("GUEST_ORDER".equals(type)) {
                log.info("[WEBHOOK] Processing GUEST_ORDER: ref={}", reference);
                orderService.fulfilKorapayOrder(reference);
                log.info("[WEBHOOK] ✅ GUEST_ORDER fulfilled: ref={}", reference);

            } else if ("CHECKER_ORDER".equals(type)) {
                log.info("[WEBHOOK] Processing CHECKER_ORDER: ref={}", reference);
                checkerService.fulfilCheckerKorapayOrder(reference);
                log.info("[WEBHOOK] ✅ CHECKER_ORDER fulfilled: ref={}", reference);

            } else {
                log.warn("[WEBHOOK] ⚠️ Unknown or null transaction type='{}' ref={} — ignoring", type, reference);
                log.warn("[WEBHOOK] Full metadata dump: {}", meta);
            }
        } catch (Exception ex) {
            log.error("[WEBHOOK] ❌ Processing error: ref={} type={} error={}", reference, type, ex.getMessage(), ex);
        }

        log.info("[WEBHOOK] ✅ Returning 200 to Korapay for ref={}", reference);
        return ResponseEntity.ok().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isValidSignature(JsonNode dataNode, String signature) {
        if (signature == null || signature.isBlank()) {
            log.warn("[WEBHOOK-SIG] ❌ Signature is null or blank");
            return false;
        }
        try {
            String dataJson = MAPPER.writeValueAsString(dataNode);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    korapaySecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            byte[] hash = mac.doFinal(dataJson.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);

            boolean match = computed.equalsIgnoreCase(signature);
            log.info("[WEBHOOK-SIG] HMAC match: {}", match);
            return match;

        } catch (Exception ex) {
            log.error("[WEBHOOK-SIG] ❌ Exception during signature validation: {}", ex.getMessage(), ex);
            return false;
        }
    }

    private JsonNode parsePayload(String rawBody) {
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception ex) {
            log.error("[WEBHOOK] ❌ Failed to parse raw body as JSON: {}", ex.getMessage(), ex);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMeta(Map<String, Object> data) {
        Object meta = data.get("metadata");
        return (meta instanceof Map) ? (Map<String, Object>) meta : null;
    }

    private BigDecimal extractAmountGhc(Map<String, Object> data) {
        Object raw = data.get("amount");
        if (raw == null) {
            log.warn("[WEBHOOK] Amount is null, defaulting to 0");
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal extractBaseAmountGhc(Map<String, Object> meta,
                                            BigDecimal chargedAmountGhc,
                                            String reference) {
        Object rawBase = meta != null ? meta.get("baseAmountGhc") : null;

        if (rawBase != null) {
            try {
                return new BigDecimal(rawBase.toString()).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                log.error("[WEBHOOK] ❌ Unparseable baseAmountGhc='{}' ref={} — falling back",
                        rawBase, reference);
            }
        } else {
            log.warn("[WEBHOOK] ⚠️ metadata.baseAmountGhc missing ref={} — falling back to " +
                    "back-calculating from charged amount", reference);
        }

        return chargedAmountGhc
                .divide(BigDecimal.ONE.add(PROCESSING_CHARGE_RATE), 2, RoundingMode.HALF_UP);
    }
}
