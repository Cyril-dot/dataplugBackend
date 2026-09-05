package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.repos.CheckerOrderRepository;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.repos.WalletTopUpRepository;
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
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhook", description = "Korapay webhook receiver")
public class WebhookController {

    private final OrderService orderService;
    private final CheckerService checkerService;
    private final CheckerOrderRepository checkerOrderRepository;
    private final WalletTopUpRepository walletTopUpRepository;
    private final OrderRepository orderRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${korapay.secret-key}")
    private String korapaySecretKey;

    /**
     * ── ✅ FIXED: routing no longer depends on webhook metadata ──────────────
     * Confirmed via live Railway logs that Korapay's actual charge.success
     * payload does not include a "metadata" key in its data block at all —
     * "Data block keys: [reference, payment_reference, currency, amount,
     * fee, payment_method, status]". The old code read
     * data.get("metadata").get("type") to decide whether this was a
     * WALLET_TOPUP / CHECKER_ORDER / GUEST_ORDER — that was always null in
     * production, so every webhook fell through to the "unknown type,
     * ignoring" branch and nothing ever got fulfilled or credited
     * automatically. This affected ALL Korapay-funded flows, not just
     * wallet top-ups.
     *
     * Routing is now determined by looking the reference up against each
     * table directly — CheckerOrder, WalletTopUp, then Order (bundle) — and
     * calling the matching fulfilment method, none of which need anything
     * beyond the reference itself (they already look up everything else
     * internally, which is exactly why this fix is possible with zero
     * changes to the actual fulfilment logic in each service).
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

        String type = resolveTransactionType(reference);
        log.info("[WEBHOOK] Routing: ref={} type={} (resolved via DB lookup, not metadata)", reference, type);

        try {
            switch (type) {
                case "CHECKER_ORDER" -> {
                    log.info("[WEBHOOK] Processing CHECKER_ORDER: ref={}", reference);
                    checkerService.fulfilCheckerKorapayOrder(reference);
                    log.info("[WEBHOOK] ✅ CHECKER_ORDER fulfilled: ref={}", reference);
                }
                case "WALLET_TOPUP" -> {
                    log.info("[WEBHOOK] Processing WALLET_TOPUP: ref={}", reference);
                    orderService.processTopUpWebhook(reference);
                    log.info("[WEBHOOK] ✅ WALLET_TOPUP credited: ref={}", reference);
                }
                case "GUEST_ORDER" -> {
                    log.info("[WEBHOOK] Processing GUEST_ORDER: ref={}", reference);
                    orderService.fulfilKorapayOrder(reference);
                    log.info("[WEBHOOK] ✅ GUEST_ORDER fulfilled: ref={}", reference);
                }
                default -> log.warn("[WEBHOOK] ⚠️ Reference matched no known table: ref={} — ignoring", reference);
            }
        } catch (Exception ex) {
            log.error("[WEBHOOK] ❌ Processing error: ref={} type={} error={}", reference, type, ex.getMessage(), ex);
        }

        log.info("[WEBHOOK] ✅ Returning 200 to Korapay for ref={}", reference);
        return ResponseEntity.ok().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Checked in this order since it's roughly most-to-least frequent in practice — order has no functional significance. */
    private String resolveTransactionType(String reference) {
        if (checkerOrderRepository.findByGatewayRef(reference).isPresent()) return "CHECKER_ORDER";
        if (walletTopUpRepository.findByGatewayRef(reference).isPresent()) return "WALLET_TOPUP";
        if (orderRepository.findByPaystackRef(reference).isPresent()) return "GUEST_ORDER";
        return "UNKNOWN";
    }

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
}
