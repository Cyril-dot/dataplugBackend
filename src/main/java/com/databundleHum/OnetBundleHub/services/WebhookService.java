package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Processes inbound Korapay webhook events.
 *
 * Called from WebhookController AFTER HMAC-SHA256 signature has been
 * validated (Korapay signs only the "data" object of the payload — see
 * KorapayService.isWebhookSignatureValid).
 *
 * Supported events:
 *   charge.success   → WALLET_TOPUP | GUEST_ORDER | CHECKER_ORDER | RESELLER_FEE
 *   charge.failed    → mark order FAILED
 *   transfer.success → (Phase 4) automated payout confirmation
 *   transfer.failed  → (Phase 4) automated payout failure
 *   refund.success   → (not yet wired) refund confirmation
 *   refund.failed    → (not yet wired) refund failure
 *
 * ── CHECKER FEATURE (2026-08-26) ──────────────────────────────────────────
 * Added CHECKER_ORDER routing to handleChargeSuccess() alongside the
 * existing WALLET_TOPUP / GUEST_ORDER branches — calls
 * checkerService.fulfilCheckerKorapayOrder(reference).
 *
 * NOTE: WebhookController implements this exact same routing logic inline
 * rather than delegating to this service (a pre-existing duplication, not
 * introduced by this change — see earlier discussion). Keep both in sync
 * if you touch one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    /** Must match OrderService.PROCESSING_CHARGE_RATE — used only as a fallback. */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    private final OrderService   orderService;
    private final CheckerService checkerService;
    private final UserRepository userRepository;
    private final ObjectMapper   objectMapper;

    public void handle(JsonNode root) {
        String event = root.path("event").asText();
        log.info("Korapay webhook received: event={}", event);

        switch (event) {
            case "charge.success"   -> handleChargeSuccess(root.path("data"));
            case "charge.failed"    -> handleChargeFailed(root.path("data"));
            case "transfer.success" -> handleTransferSuccess(root.path("data"));
            case "transfer.failed"  -> handleTransferFailed(root.path("data"));
            case "refund.success", "refund.failed" ->
                    log.info("Korapay refund event received (not yet wired): event={} ref={}",
                            event, root.path("data").path("reference").asText());
            default -> log.debug("Unhandled Korapay event: {}", event);
        }
    }

    private void handleChargeSuccess(JsonNode data) {
        String reference = data.path("reference").asText();
        String type      = data.path("metadata").path("type").asText("");

        log.info("charge.success: ref={} type={}", reference, type);

        switch (type) {
            case "WALLET_TOPUP" -> {
                String userIdStr = data.path("metadata").path("userId").asText("");
                UUID userId = parseUserId(userIdStr, reference);
                if (userId == null) return;

                BigDecimal chargedAmountGhc = extractAmountGhc(data);
                BigDecimal baseAmountGhc    = extractBaseAmountGhc(data, chargedAmountGhc, reference);

                orderService.processTopUpWebhook(userId, baseAmountGhc, reference);
            }

            case "GUEST_ORDER" ->
                    orderService.fulfilKorapayOrder(reference);

            case "CHECKER_ORDER" ->
                    checkerService.fulfilCheckerKorapayOrder(reference);

            case "RESELLER_FEE" ->
                    log.info("Reseller registration fee confirmed via Korapay: ref={}", reference);

            default -> {
                log.warn("charge.success with unknown metadata.type='{}' ref={}", type, reference);
                if (type.isBlank()) {
                    orderService.fulfilKorapayOrder(reference);
                }
            }
        }
    }

    private void handleChargeFailed(JsonNode data) {
        String reference = data.path("reference").asText();
        log.warn("charge.failed: ref={}", reference);
    }

    private void handleTransferSuccess(JsonNode data) {
        String reference = data.path("reference").asText();
        log.info("transfer.success: ref={} (Phase 4 — not yet implemented)", reference);
    }

    private void handleTransferFailed(JsonNode data) {
        String reference = data.path("reference").asText();
        log.warn("transfer.failed: ref={} (Phase 4 — not yet implemented)", reference);
    }

    private BigDecimal extractAmountGhc(JsonNode data) {
        JsonNode amountNode = data.path("amount");
        if (amountNode.isNumber()) {
            return amountNode.decimalValue().setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(amountNode.asText("0")).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal extractBaseAmountGhc(JsonNode data, BigDecimal chargedAmountGhc, String reference) {
        JsonNode baseNode = data.path("metadata").path("baseAmountGhc");
        if (!baseNode.isMissingNode() && !baseNode.asText("").isBlank()) {
            try {
                return new BigDecimal(baseNode.asText()).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                log.error("Unparseable metadata.baseAmountGhc='{}' ref={} — falling back",
                        baseNode.asText(), reference);
            }
        } else {
            log.warn("metadata.baseAmountGhc missing ref={} — falling back to back-calculating " +
                    "from charged amount", reference);
        }

        return chargedAmountGhc
                .divide(BigDecimal.ONE.add(PROCESSING_CHARGE_RATE), 2, RoundingMode.HALF_UP);
    }

    private UUID parseUserId(String userIdStr, String reference) {
        try {
            return UUID.fromString(userIdStr);
        } catch (IllegalArgumentException ex) {
            log.error("Invalid userId in Korapay metadata: userId='{}' ref={}", userIdStr, reference);
            return null;
        }
    }
}
