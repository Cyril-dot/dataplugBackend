package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import com.databundleHum.OnetBundleHub.entity.Order;
import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EmmaDataKing Developer API integration (https://emmadataking.com/agent/developer).
 *
 * The active data-bundle provider since the October 2026 cutover
 * (AppConfig#isEmmaProvider, env DATA_PROVIDER=emma; set it to "bigdreams"
 * to roll fulfilment back to BigDreamsDataService).
 *
 * API shape (all calls carry the X-API-Key header, set on the
 * "emmaDataKingWebClient" bean in AppConfig):
 *   POST /api/v1/purchase          {network, capacity, phoneNumber, ref}
 *   GET  /api/v1/order-status/{ref}
 *   GET  /api/v1/packages          catalogue grouped by network code
 *   GET  /api/v1/balance
 *
 * Every purchase sends our own idempotency reference, "datapack-{orderId}",
 * which the status endpoint accepts in place of the provider's transaction
 * code — the same convention Big Dreams used, so pending orders reconcile
 * through one 60-second poller regardless of provider.
 *
 * The /packages catalogue is grouped by provider network codes that span
 * several product families per carrier (e.g. MTN = YELLO + EXPRESS_YELLO,
 * Telecel = TELECEL + EXPRESS_TELECEL, AirtelTigo = at + AT_PREMIUM +
 * EXPRESS_AT). A size is not stocked by every family (Telecel 5GB exists
 * only as EXPRESS_TELECEL), so each order is resolved at purchase time to
 * the cheapest in-stock package across the carrier's families.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmmaDataKingService {

    @Qualifier("emmaDataKingWebClient")
    private final WebClient emmaDataKingWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderRepository orderRepository;
    private final WalletService walletService;
    private final AppConfig appConfig;

    /** Provider network-code families per platform network, cheapest-first preference order. */
    private static final Map<PlatformSettings.Network, List<String>> NETWORK_FAMILIES = Map.of(
            PlatformSettings.Network.MTN, List.of("YELLO", "EXPRESS_YELLO"),
            PlatformSettings.Network.TELECEL, List.of("TELECEL", "EXPRESS_TELECEL"),
            PlatformSettings.Network.AIRTELTIGO, List.of("at", "AT_PREMIUM", "EXPRESS_AT"));

    private static final long PACKAGES_CACHE_TTL_MS = 60_000L;
    private volatile Map<String, List<PackageOption>> packagesCache = Map.of();
    private volatile long packagesCacheAt = 0L;

    // ══════════════════════════════════════════════════════════════════════
    // Catalogue
    // ══════════════════════════════════════════════════════════════════════

    public record PackageOption(String networkCode, long packageId, String capacityValue,
                                BigDecimal price, boolean inStock) {}

    /** GET /api/v1/packages — full catalogue, cached for 60s to respect the 60 req/min quota. */
    public Map<String, List<PackageOption>> getPackages() {
        long now = System.currentTimeMillis();
        if (!packagesCache.isEmpty() && now - packagesCacheAt < PACKAGES_CACHE_TTL_MS) {
            return packagesCache;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (!packagesCache.isEmpty() && now - packagesCacheAt < PACKAGES_CACHE_TTL_MS) {
                return packagesCache;
            }
            JsonNode data = getForData("/api/v1/packages", "get_packages");
            Map<String, List<PackageOption>> parsed = new LinkedHashMap<>();
            data.fields().forEachRemaining(entry -> {
                List<PackageOption> options = new ArrayList<>();
                for (JsonNode pkg : entry.getValue()) {
                    BigDecimal price = decimal(pkg, "price");
                    String capacityValue = text(pkg, "capacity_value");
                    if (price == null || capacityValue == null) continue;
                    options.add(new PackageOption(
                            entry.getKey(),
                            pkg.path("package_id").asLong(0L),
                            capacityValue,
                            price,
                            pkg.path("in_stock").asBoolean(false)));
                }
                parsed.put(entry.getKey(), options);
            });
            packagesCache = parsed;
            packagesCacheAt = now;
            log.info("[EMMA] Catalogue refreshed: {} network groups", parsed.size());
            return parsed;
        }
    }

    /**
     * Resolves an order's network + size to the cheapest in-stock Emma
     * package across that carrier's families.
     *
     * @throws UpstreamApiException when no family stocks the size right now
     *                              (the caller's failure path marks the order
     *                              FAILED and refunds the customer).
     */
    public PackageOption resolvePackage(PlatformSettings.Network network, BigDecimal capacityGb) {
        List<String> families = NETWORK_FAMILIES.getOrDefault(network, List.of());
        Map<String, List<PackageOption>> catalogue = getPackages();
        PackageOption best = null;
        for (String family : families) {
            for (PackageOption option : catalogue.getOrDefault(family, List.of())) {
                if (!option.inStock()) continue;
                BigDecimal optionGb;
                try {
                    optionGb = new BigDecimal(option.capacityValue());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (optionGb.compareTo(capacityGb) != 0) continue;
                if (best == null || option.price().compareTo(best.price()) < 0) {
                    best = option;
                }
            }
        }
        if (best == null) {
            throw new UpstreamApiException(
                    "This bundle size is currently unavailable. Please try another size or check back shortly.");
        }
        return best;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Purchase + status + balance
    // ══════════════════════════════════════════════════════════════════════

    public record PurchaseResult(String providerId, String transactionCode, String status,
                                 BigDecimal amount, BigDecimal remainingBalance) {}

    /** POST /api/v1/purchase — debit the Emma wallet and place the bundle order. */
    public PurchaseResult purchase(String networkCode, String capacityValue, String phoneNumber, String ref) {
        log.info("[EMMA] purchase: network={} capacity={} phone={} ref={}", networkCode, capacityValue, phoneNumber, ref);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("network", networkCode);
        body.put("capacity", capacityValue);
        body.put("phoneNumber", phoneNumber);
        body.put("ref", ref);

        JsonNode data = postForData("/api/v1/purchase", body, "purchase");
        PurchaseResult result = new PurchaseResult(
                text(data, "id"),
                text(data, "transaction_code"),
                text(data, "status"),
                decimal(data, "amount"),
                decimal(data, "remaining_balance"));
        log.info("[EMMA] ✔ purchase accepted: ref={} providerId={} status={} amount={} remainingBalance={}",
                ref, result.providerId(), result.status(), result.amount(), result.remainingBalance());
        return result;
    }

    /** Resolves + purchases the bundle for a paid platform order. */
    public PurchaseResult buyForOrder(Order order) {
        PackageOption option = resolvePackage(order.getNetwork(), order.getCapacityGb());
        return purchase(option.networkCode(), option.capacityValue(),
                order.getPhoneNumber(), "datapack-" + order.getId());
    }

    public record StatusResult(String status, String failureReason) {}

    /** GET /api/v1/order-status/{ref} — accepts our custom ref. */
    public StatusResult checkStatus(String ref) {
        JsonNode data = getForData("/api/v1/order-status/" + ref, "order_status");
        String reason = text(data, "failure_reason");
        if (reason == null) reason = text(data, "reason");
        return new StatusResult(text(data, "status"), reason);
    }

    public record BalanceResult(BigDecimal balance, String currency, String accountName, String email) {}

    /** GET /api/v1/balance. */
    public BalanceResult checkBalance() {
        JsonNode data = getForData("/api/v1/balance", "check_balance");
        return new BalanceResult(
                decimal(data, "balance"),
                text(data, "currency"),
                text(data, "account_name"),
                text(data, "email"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Reconciliation — mirrors BigDreamsDataService.reconcilePendingOrders
    // ══════════════════════════════════════════════════════════════════════

    /** Poll accepted Emma orders every 60s (status quota is 200 req/min). */
    @Scheduled(fixedDelay = 60_000L)
    public void reconcilePendingOrders() {
        if (!appConfig.isEmmaProvider()) return;
        List<Order> pending = orderRepository.findByStatus(Order.OrderStatus.PENDING).stream()
                .filter(o -> o.getDbhReference() != null && o.getDbhReference().startsWith("datapack-"))
                .toList();
        for (Order order : pending) {
            try {
                StatusResult result = checkStatus(order.getDbhReference());
                String status = normalizeStatus(result.status());
                if (isSuccessfulStatus(status)) {
                    markProviderStatus(order, Order.OrderStatus.COMPLETED);
                    log.info("[EMMA] Order completed: localOrderId={} ref={} providerStatus={}",
                            order.getId(), order.getDbhReference(), status);
                } else if (isFailedStatus(status)) {
                    markProviderFailure(order, result.failureReason());
                    log.warn("[EMMA] Order failed: localOrderId={} reason={}",
                            order.getId(), result.failureReason());
                }
            } catch (UpstreamApiException ex) {
                log.warn("[EMMA] Status check unavailable: localOrderId={} ref={} error={}",
                        order.getId(), order.getDbhReference(), ex.getMessage());
            }
        }
    }

    @Transactional
    protected void markProviderStatus(Order order, Order.OrderStatus status) {
        order.setStatus(status);
        orderRepository.save(order);
    }

    /**
     * The provider can accept an order first and reject it later. Keep that
     * reason on the order and refund the platform wallet for wallet-funded
     * purchases; the synchronous rejection path is handled by OrderService.
     */
    @Transactional
    protected void markProviderFailure(Order order, String failureReason) {
        if (order.getStatus() != Order.OrderStatus.PENDING) return;
        order.setStatus(Order.OrderStatus.FAILED);
        order.setFailureReason(failureReason == null || failureReason.isBlank()
                ? "Provider rejected the order without a reason."
                : failureReason.substring(0, Math.min(500, failureReason.length())));
        orderRepository.save(order);

        if (order.getPaymentMethod() == Order.PaymentMethod.WALLET && order.getUser() != null) {
            walletService.credit(order.getUser().getId(), order.getCostPriceGhc(), TransactionType.REFUND,
                    "Refund: provider failed bundle delivery for order #" + order.getId(), null);
        }
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase().replace('-', '_').replace(' ', '_');
    }

    private boolean isSuccessfulStatus(String status) {
        return status.equals("completed") || status.equals("complete")
                || status.equals("delivered") || status.equals("success")
                || status.equals("successful") || status.equals("successful_delivery")
                || status.equals("delivery_successful") || status.equals("done");
    }

    private boolean isFailedStatus(String status) {
        return status.equals("failed") || status.equals("failure")
                || status.equals("cancelled") || status.equals("canceled")
                || status.equals("rejected") || status.equals("declined")
                || status.equals("refunded");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Webhook events (invoked by EmmaWebhookController after HMAC verification)
    // ══════════════════════════════════════════════════════════════════════

    /** order.completed — flip a still-pending order to COMPLETED. Idempotent. */
    @Transactional
    public void handleWebhookDelivered(String reference) {
        for (Order order : ordersByReference(reference)) {
            if (order.getStatus() != Order.OrderStatus.PENDING) {
                log.info("[EMMA] Webhook delivered ignored: order {} already {}",
                        order.getId(), order.getStatus());
                continue;
            }
            markProviderStatus(order, Order.OrderStatus.COMPLETED);
            log.info("[EMMA] Webhook: order completed localOrderId={} ref={}", order.getId(), reference);
        }
    }

    /** order.failed / order.refunded — fail + refund via the shared failure path. Idempotent. */
    @Transactional
    public void handleWebhookFailed(String reference, String failureReason) {
        for (Order order : ordersByReference(reference)) {
            markProviderFailure(order, failureReason);
            log.warn("[EMMA] Webhook: order failed localOrderId={} ref={} reason={}",
                    order.getId(), reference, failureReason);
        }
    }

    private List<Order> ordersByReference(String reference) {
        if (reference == null || reference.isBlank()) return List.of();
        List<Order> orders = orderRepository.findByDbhReference(reference);
        if (orders.isEmpty()) {
            log.warn("[EMMA] Webhook for unknown reference: {}", reference);
        }
        return orders;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Shared request/response plumbing (mirrors BigDreamsDataService)
    // ══════════════════════════════════════════════════════════════════════

    private JsonNode postForData(String path, Map<String, Object> body, String actionForLogging) {
        try {
            String rawBody = emmaDataKingWebClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return unwrap(rawBody, actionForLogging);
        } catch (WebClientResponseException ex) {
            throw providerError(actionForLogging, ex);
        } catch (UpstreamApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[EMMA] Unexpected error on action={} — type={} error={}",
                    actionForLogging, ex.getClass().getSimpleName(), ex.getMessage());
            throw new UpstreamApiException(
                    "EmmaDataKing call failed for action=" + actionForLogging + ": " + ex.getMessage());
        }
    }

    private JsonNode getForData(String path, String actionForLogging) {
        try {
            String rawBody = emmaDataKingWebClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return unwrap(rawBody, actionForLogging);
        } catch (WebClientResponseException ex) {
            throw providerError(actionForLogging, ex);
        } catch (UpstreamApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[EMMA] Unexpected error on action={} — type={} error={}",
                    actionForLogging, ex.getClass().getSimpleName(), ex.getMessage());
            throw new UpstreamApiException(
                    "EmmaDataKing call failed for action=" + actionForLogging + ": " + ex.getMessage());
        }
    }

    /** Emma wraps payloads as {status:"success", data:{…}}; anything else is a rejection. */
    private JsonNode unwrap(String rawBody, String actionForLogging) {
        log.info("[EMMA] {} raw response: [{}]", actionForLogging, rawBody);
        if (rawBody == null || rawBody.isBlank()) {
            throw new UpstreamApiException(
                    "EmmaDataKing returned an empty response for action=" + actionForLogging);
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!"success".equalsIgnoreCase(text(root, "status"))) {
                String message = text(root, "message");
                String error = text(root, "error");
                throw new UpstreamApiException(
                        "EmmaDataKing rejected action=" + actionForLogging
                                + " — error=" + error + " message=" + message);
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new UpstreamApiException(
                        "EmmaDataKing response for action=" + actionForLogging + " had no data object");
            }
            return data;
        } catch (UpstreamApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new UpstreamApiException(
                    "EmmaDataKing returned a malformed response for action=" + actionForLogging);
        }
    }

    /** HTTP errors (4xx/5xx) — surface the provider's own message when the body carries one. */
    private UpstreamApiException providerError(String actionForLogging, WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        log.error("[EMMA] HTTP {} on action={}: {}", ex.getStatusCode(), actionForLogging, body);
        String providerMessage = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            providerMessage = text(root, "message");
            if (providerMessage == null) providerMessage = text(root, "error");
        } catch (Exception ignored) {
            // Non-JSON error body — fall through to the generic message.
        }
        return new UpstreamApiException(providerMessage != null && !providerMessage.isBlank()
                ? providerMessage
                : "EmmaDataKing HTTP " + ex.getStatusCode() + " for action=" + actionForLogging);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        return new BigDecimal(v.asText());
    }
}
