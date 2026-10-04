package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.repos.CheckerOrderRepository;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.repos.WalletTopUpRepository;
import com.databundleHum.OnetBundleHub.services.CheckerService;
import com.databundleHum.OnetBundleHub.services.OrderService;
import com.databundleHum.OnetBundleHub.services.PaystackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhook", description = "Paystack webhook receiver")
public class WebhookController {

    private final OrderService orderService;
    private final CheckerService checkerService;
    private final PaystackService paystackService;
    private final CheckerOrderRepository checkerOrderRepository;
    private final WalletTopUpRepository walletTopUpRepository;
    private final OrderRepository orderRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Paystack webhook endpoint. /korapay remains as a compatibility alias so
     * an already-configured old webhook URL fails safely during migration.
     * Configure Paystack to call /api/webhooks/paystack.
     */
    @PostMapping({"/paystack", "/korapay"})
    @Operation(summary = "Paystack webhook — charge.success handler")
    public ResponseEntity<Void> handlePaystack(
            @RequestHeader(value = "x-paystack-signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        log.info("[PAYSTACK-WEBHOOK] Request received: bodyLength={}", rawBody == null ? 0 : rawBody.length);

        JsonNode root = parsePayload(rawBody);
        if (root == null) return ResponseEntity.badRequest().build();

        if (!paystackService.isWebhookSignatureValid(rawBody, signature)) {
            log.warn("[PAYSTACK-WEBHOOK] Signature validation failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String event = root.path("event").asText();
        JsonNode data = root.path("data");
        log.info("[PAYSTACK-WEBHOOK] Event={} dataFields={}", event, data.fieldNames().hasNext());

        // Paystack may retry all events. Only charge.success can fulfil an order.
        if (!"charge.success".equals(event)) {
            log.info("[PAYSTACK-WEBHOOK] Ignoring event={} and returning 200", event);
            return ResponseEntity.ok().build();
        }

        String reference = data.path("reference").asText(null);
        if (reference == null || reference.isBlank()) {
            log.warn("[PAYSTACK-WEBHOOK] charge.success missing reference");
            return ResponseEntity.badRequest().build();
        }

        String type = resolveTransactionType(reference);
        log.info("[PAYSTACK-WEBHOOK] charge.success ref={} type={}", reference, type);
        try {
            switch (type) {
                case "CHECKER_ORDER" -> checkerService.fulfilCheckerKorapayOrder(reference);
                case "WALLET_TOPUP" -> orderService.processTopUpWebhook(reference);
                case "GUEST_ORDER" -> orderService.fulfilKorapayOrder(reference);
                default -> log.warn("[PAYSTACK-WEBHOOK] Reference matched no known order: {}", reference);
            }
        } catch (Exception ex) {
            // A non-2xx makes Paystack retry the signed webhook, which is useful
            // for transient provider/database failures and preserves idempotency.
            log.error("[PAYSTACK-WEBHOOK] Processing failed: ref={} type={} error={}",
                    reference, type, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }

    private String resolveTransactionType(String reference) {
        if (checkerOrderRepository.findByGatewayRef(reference).isPresent()) return "CHECKER_ORDER";
        if (walletTopUpRepository.findByGatewayRef(reference).isPresent()) return "WALLET_TOPUP";
        if (orderRepository.findByPaystackRef(reference).isPresent()) return "GUEST_ORDER";
        return "UNKNOWN";
    }

    private JsonNode parsePayload(byte[] rawBody) {
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception ex) {
            log.error("[PAYSTACK-WEBHOOK] Invalid JSON body: {}", ex.getMessage());
            return null;
        }
    }
}
