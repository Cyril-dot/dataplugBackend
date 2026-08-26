package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.entity.Order;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facade over the DataPrimo Provider API.
 *
 * API reference : https://dataprimo.org/api/v1
 * Authentication: Bearer token — Authorization: Bearer {apiKey}
 *
 * Documented endpoints:
 *   GET  /account          — account/balance info, also lists recent orders
 *   GET  /catalog           — available products (network/product IDs, pricing)
 *   POST /orders             — place an order. Requires "Idempotency-Key" header.
 *                               Success = HTTP 201/202, body: {success, data:
 *                               {id, reference, status, amount, currency, createdAt}}
 *   GET  /orders/{orderId}   — poll order status: "pending" | "processing" |
 *                               "completed" | "delivered" | "failed" | "cancelled"
 *
 * ── DELIVERY CONFIRMATION ─────────────────────────────────────────────────
 * HTTP 201/202 + data.id only confirms DataPrimo ACCEPTED the order — it is
 * explicitly NOT delivery confirmation. Documented lifecycle:
 *
 *   PENDING → PROCESSING → COMPLETED → DELIVERED
 *                  (or)
 *           → FAILED / CANCELLED
 *
 * checkDeliveryStatus() polls GET /orders/{orderId} for every order still
 * PENDING in our DB and promotes it once DataPrimo reports a terminal
 * status. Unlike Big Dreams' shared get_transactions feed (a "50 most
 * recent" page you had to search through), DataPrimo gives a per-order GET
 * endpoint — no risk of an order scrolling out of view.
 *
 * NOTE on "completed" vs "delivered": both are treated as our internal
 * COMPLETED status, since that's the meaningful signal for this use case
 * (bundle landed on the recipient's SIM). Revisit if a real response for a
 * bundle purchase distinguishes them with different practical meaning.
 *
 * WEBHOOKS: mentioned as available "if enabled" but no payload shape,
 * signature scheme, or config instructions are documented anywhere. Polling
 * only until that's confirmed — do not guess a webhook shape.
 *
 * ── CATALOG-DRIVEN PRODUCT IDS ────────────────────────────────────────────
 * DataPrimo has no fixed/predictable network vocabulary to hardcode — the
 * only source of truth is GET /catalog for this account. Callers of
 * purchase() must resolve (network, capacityGb) → (productId, dataprimoNetwork)
 * ahead of time (see PlatformSettings.dataprimoProductId / dataprimoNetwork).
 *
 * IMPORTANT — set in application.properties:
 *   dataprimo.api-key=dp_live_your_key_here
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPrimoService {

    private static final int  MAX_RETRIES     = 3;
    private static final long RETRY_DELAY_MS  = 2_000L;

    @Qualifier("dataPrimoWebClient")
    private final WebClient       dataPrimoWebClient;
    private final OrderRepository orderRepository;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    // ── Reference / idempotency key ───────────────────────────────────────────

    public String buildIdempotencyKey(Order order) {
        String key = "order-" + order.getId();
        log.debug("[DATAPRIMO] Idempotency key for orderId={}: {}", order.getId(), key);
        return key;
    }

    // ── Account ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchAccount() {
        log.info("[DATAPRIMO] Fetching account info");
        try {
            String rawBody = dataPrimoWebClient.get()
                    .uri("/account")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATAPRIMO] GET /account raw body: [{}]", rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATAPRIMO] /account returned empty body");
                return Map.of();
            }
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception ex) {
            log.warn("[DATAPRIMO] /account fetch failed — type={} error={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    // ── Catalog ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchCatalog() {
        log.info("[DATAPRIMO] Fetching catalog");
        try {
            String rawBody = dataPrimoWebClient.get()
                    .uri("/catalog")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATAPRIMO] GET /catalog raw body: [{}]", rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATAPRIMO] /catalog returned empty body");
                return List.of();
            }

            Object parsed = objectMapper.readValue(rawBody, Object.class);
            List<Map<String, Object>> products = new ArrayList<>();

            if (parsed instanceof List) {
                for (Object o : (List<Object>) parsed) {
                    if (o instanceof Map) products.add((Map<String, Object>) o);
                }
            } else if (parsed instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) parsed;
                Object data = map.getOrDefault("data", map.get("products"));
                if (data instanceof List) {
                    for (Object o : (List<Object>) data) {
                        if (o instanceof Map) products.add((Map<String, Object>) o);
                    }
                } else if (data instanceof Map) {
                    Object nested = ((Map<String, Object>) data).get("products");
                    if (nested instanceof List) {
                        for (Object o : (List<Object>) nested) {
                            if (o instanceof Map) products.add((Map<String, Object>) o);
                        }
                    }
                }
            }

            if (products.isEmpty()) {
                log.warn("[DATAPRIMO] /catalog: could not locate a product array in the response — " +
                        "check the raw body logged above and adjust fetchCatalog() parsing");
            } else {
                log.info("[DATAPRIMO] /catalog: {} product(s) parsed", products.size());
            }
            return products;

        } catch (Exception ex) {
            log.warn("[DATAPRIMO] /catalog fetch failed — type={} error={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    // ── Purchase ───────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purchase(Order order, String productId, String network) {
        long startMs = System.currentTimeMillis();
        String idempotencyKey = buildIdempotencyKey(order);
        String clientReference = idempotencyKey;

        log.info("[DATAPRIMO] ═══════════════════════════════════════════════════");
        log.info("[DATAPRIMO] PURCHASE START");
        log.info("[DATAPRIMO]   orderId         = {}", order.getId());
        log.info("[DATAPRIMO]   recipient       = {}", order.getPhoneNumber());
        log.info("[DATAPRIMO]   productId       = {}", productId);
        log.info("[DATAPRIMO]   network         = {}", network);
        log.info("[DATAPRIMO]   idempotencyKey  = {}", idempotencyKey);
        log.info("[DATAPRIMO]   timestamp       = {}", Instant.now());
        log.info("[DATAPRIMO] ═══════════════════════════════════════════════════");

        if (productId == null || productId.isBlank() || network == null || network.isBlank()) {
            log.error("[DATAPRIMO] Missing productId/network for orderId={} — aborting before HTTP call",
                    order.getId());
            throw new UpstreamApiException(
                    "DataPrimo purchase called without a resolved productId/network for orderId="
                            + order.getId() + " — resolve these from fetchCatalog() first.");
        }

        Map<String, Object> recipient = Map.of("phone", order.getPhoneNumber());
        Map<String, Object> payload = Map.of(
                "productId",       productId,
                "network",         network,
                "clientReference", clientReference,
                "recipient",       recipient
        );

        log.info("[DATAPRIMO] Payload → productId={} network={} clientReference={} recipient.phone={}",
                productId, network, clientReference, order.getPhoneNumber());

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long attemptStart = System.currentTimeMillis();
            log.info("[DATAPRIMO] ─────────────────────────────────────────────────");
            log.info("[DATAPRIMO] Attempt {}/{} — orderId={}", attempt, MAX_RETRIES, order.getId());

            try {
                String rawBody = dataPrimoWebClient.post()
                        .uri("/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Content-Type", "application/json")
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                long durationMs = System.currentTimeMillis() - attemptStart;
                log.info("[DATAPRIMO] Response in {}ms — orderId={}", durationMs, order.getId());
                log.info("[DATAPRIMO] Raw body: [{}]", rawBody);

                if (rawBody == null || rawBody.isBlank()) {
                    throw new UpstreamApiException(
                            "DataPrimo returned empty body for orderId=" + order.getId()
                                    + " (attempt " + attempt + "/" + MAX_RETRIES + ")");
                }

                Map<String, Object> response;
                try {
                    //noinspection unchecked
                    response = objectMapper.readValue(rawBody, Map.class);
                } catch (Exception parseEx) {
                    throw new UpstreamApiException(
                            "DataPrimo returned unparseable body for orderId=" + order.getId()
                                    + ": " + rawBody);
                }

                if (!Boolean.TRUE.equals(response.get("success"))) {
                    throw new UpstreamApiException(
                            "DataPrimo response success=false for orderId=" + order.getId()
                                    + " — body: " + rawBody);
                }

                //noinspection unchecked
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                if (data == null) {
                    throw new UpstreamApiException(
                            "DataPrimo response missing 'data' field for orderId=" + order.getId()
                                    + " — body: " + rawBody);
                }

                Object dpOrderId   = data.get("id");
                Object dpReference = data.get("reference");
                Object dpStatus    = data.get("status");

                if (dpOrderId == null || dpOrderId.toString().isBlank()) {
                    throw new UpstreamApiException(
                            "DataPrimo returned 2xx but data.id is missing/blank for orderId="
                                    + order.getId() + " — treating as failed per DataPrimo guidance. Body: "
                                    + rawBody);
                }

                log.info("[DATAPRIMO] ✔ Order accepted:");
                log.info("[DATAPRIMO]   orderId         = {}", order.getId());
                log.info("[DATAPRIMO]   DataPrimo id    = {}", dpOrderId);
                log.info("[DATAPRIMO]   reference       = {}", dpReference);
                log.info("[DATAPRIMO]   initial status  = {} (not yet delivery-confirmed)", dpStatus);
                log.info("[DATAPRIMO]   total elapsed   = {}ms", System.currentTimeMillis() - startMs);

                order.setDataprimoOrderId(dpOrderId.toString());
                order.setDataprimoReference(dpReference != null ? dpReference.toString()
                        : clientReference);
                order.setStatus(Order.OrderStatus.PENDING);
                orderRepository.save(order);

                log.info("[DATAPRIMO] Order saved as PENDING (awaiting delivery confirmation via poller) " +
                        "— orderId={}", order.getId());
                log.info("[DATAPRIMO] ═══════════════════════════════════════════════════");
                return;

            } catch (WebClientResponseException ex) {
                lastException = ex;
                log.warn("[DATAPRIMO] HTTP {} on attempt {}/{} — orderId={} body=\"{}\"",
                        ex.getStatusCode(), attempt, MAX_RETRIES, order.getId(),
                        ex.getResponseBodyAsString());

                if (ex.getStatusCode().value() == 401) {
                    log.error("[DATAPRIMO] 401 Unauthorized — check dataprimo.api-key");
                    break;
                }
                if (ex.getStatusCode().is4xxClientError()) {
                    log.error("[DATAPRIMO] Non-recoverable 4xx rejection (HTTP {}) — orderId={} — not retrying",
                            ex.getStatusCode(), order.getId());
                    order.setStatus(Order.OrderStatus.FAILED);
                    orderRepository.save(order);
                    throw new UpstreamApiException(
                            "DataPrimo order rejected: HTTP " + ex.getStatusCode()
                                    + " — " + ex.getResponseBodyAsString());
                }
            } catch (UpstreamApiException ex) {
                lastException = ex;
                log.warn("[DATAPRIMO] API error on attempt {}/{} — orderId={} error={}",
                        attempt, MAX_RETRIES, order.getId(), ex.getMessage());
            } catch (Exception ex) {
                lastException = ex;
                log.warn("[DATAPRIMO] Unexpected error on attempt {}/{} — orderId={} type={} error={}",
                        attempt, MAX_RETRIES, order.getId(),
                        ex.getClass().getSimpleName(), ex.getMessage());
            }

            if (attempt < MAX_RETRIES) {
                log.info("[DATAPRIMO] Waiting {}ms before retry (same Idempotency-Key={}) — orderId={}",
                        RETRY_DELAY_MS, idempotencyKey, order.getId());
                sleepQuietly(RETRY_DELAY_MS);
            }
        }

        log.error("[DATAPRIMO] PURCHASE FAILED — ALL {} RETRIES EXHAUSTED — orderId={}",
                MAX_RETRIES, order.getId());
        order.setStatus(Order.OrderStatus.FAILED);
        orderRepository.save(order);

        throw new UpstreamApiException(
                "DataPrimo bundle purchase failed after " + MAX_RETRIES
                        + " attempts. OrderId=" + order.getId(), lastException);
    }

    // ── Delivery confirmation poller ──────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void checkDeliveryStatus() {
        List<Order> pendingOrders = orderRepository.findByStatus(Order.OrderStatus.PENDING);

        List<Order> dataPrimoPending = pendingOrders.stream()
                .filter(o -> o.getDataprimoOrderId() != null && !o.getDataprimoOrderId().isBlank())
                .toList();

        if (dataPrimoPending.isEmpty()) {
            log.debug("[DATAPRIMO] Delivery poller: no PENDING DataPrimo orders");
            return;
        }

        log.info("[DATAPRIMO] Delivery poller: checking {} PENDING order(s)", dataPrimoPending.size());

        for (Order order : dataPrimoPending) {
            String status = fetchOrderStatus(order.getDataprimoOrderId());
            if (status == null) {
                log.debug("[DATAPRIMO] orderId={} dpOrderId={} — status check failed/unavailable this cycle, still PENDING",
                        order.getId(), order.getDataprimoOrderId());
                continue;
            }

            switch (status.toLowerCase()) {
                case "completed", "delivered" -> {
                    order.setStatus(Order.OrderStatus.COMPLETED);
                    orderRepository.save(order);
                    log.info("[DATAPRIMO] ✔ Order confirmed DELIVERED — orderId={} dpOrderId={} dpStatus={}",
                            order.getId(), order.getDataprimoOrderId(), status);
                }
                case "failed", "cancelled" -> {
                    order.setStatus(Order.OrderStatus.FAILED);
                    orderRepository.save(order);
                    log.warn("[DATAPRIMO] ✘ Order confirmed FAILED upstream — orderId={} dpOrderId={} dpStatus={} (refund needed)",
                            order.getId(), order.getDataprimoOrderId(), status);
                    // TODO: trigger wallet refund here if OrderService doesn't already
                    // handle FAILED transitions elsewhere.
                }
                default -> log.debug("[DATAPRIMO] orderId={} dpOrderId={} status still '{}' (pending/processing) — waiting",
                        order.getId(), order.getDataprimoOrderId(), status);
            }
        }

        log.info("[DATAPRIMO] Delivery poller: cycle complete");
    }

    @SuppressWarnings("unchecked")
    private String fetchOrderStatus(String dataPrimoOrderId) {
        try {
            String rawBody = dataPrimoWebClient.get()
                    .uri("/orders/{orderId}", dataPrimoOrderId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("[DATAPRIMO] GET /orders/{} raw body: [{}]", dataPrimoOrderId, rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATAPRIMO] /orders/{} returned empty body", dataPrimoOrderId);
                return null;
            }

            Map<String, Object> response = objectMapper.readValue(rawBody, Map.class);
            if (!Boolean.TRUE.equals(response.get("success"))) {
                log.warn("[DATAPRIMO] /orders/{} non-success — response={}", dataPrimoOrderId, response);
                return null;
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                log.warn("[DATAPRIMO] /orders/{} missing 'data' field — response={}", dataPrimoOrderId, response);
                return null;
            }

            Object status = data.get("status");
            log.debug("[DATAPRIMO] /orders/{} status={}", dataPrimoOrderId, status);
            return status != null ? status.toString() : null;

        } catch (WebClientResponseException ex) {
            log.warn("[DATAPRIMO] /orders/{} HTTP {} — {}",
                    dataPrimoOrderId, ex.getStatusCode(), ex.getResponseBodyAsString());
            return null;
        } catch (Exception ex) {
            log.warn("[DATAPRIMO] /orders/{} status check failed — type={} error={}",
                    dataPrimoOrderId, ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    // ── Catalog → order-field resolution helper (best-effort, unverified) ─────

    public String extractProductId(Map<String, Object> catalogEntry) {
        Object id = catalogEntry.getOrDefault("id", catalogEntry.get("productId"));
        return id != null ? id.toString() : null;
    }

    public String extractNetwork(Map<String, Object> catalogEntry) {
        Object network = catalogEntry.get("network");
        return network != null ? network.toString() : null;
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
