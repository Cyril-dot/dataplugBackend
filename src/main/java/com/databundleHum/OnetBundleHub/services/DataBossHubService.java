package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.response.PhoneDeliveryCheckResponse;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facade over the DataBossHub API (bbhubportal.com).
 *
 * Two DISTINCT capabilities live in this one service, serving different
 * purposes — do not conflate them:
 *
 * 1. PHONE-DELIVERY CORROBORATION (checkPhoneDelivery) — a manual, human-
 *    facing diagnostic signal for data-bundle orders. See that method's
 *    Javadoc. NOT used for checker purchases.
 *
 * 2. RESULT-CHECKER PURCHASE (fetchCheckerPrice / fetchAvailableSlots /
 *    buyCheckerSlot / fetchMyPurchasedSlots) — the REAL, transactional path
 *    for buying BECE/WASSCE checker PINs. This is what CheckerService uses.
 *
 * ── Checker purchase model — read before using ──────────────────────────
 * Unlike DataPrimo's data bundles (buy any quantity of a fixed productId),
 * DataBossHub's checkers are a FINITE POOL of numbered slots:
 *   - GET /checker/slots lists currently available slots (id, category,
 *     your tier price) — credentials are hidden until purchase.
 *   - POST /checker/buy/{id} claims ONE SPECIFIC slot by id, deducts your
 *     DataBossHub account's tier price, and returns full credentials
 *     (serial, PIN, exam date, results link) in the same response —
 *     synchronous, no polling needed.
 *
 * Because slots are finite and shared across however many buyers hit this
 * API, two concurrent purchases can race for the same slot id. A 404/409/
 * "already taken"-shaped failure on buyCheckerSlot is NOT necessarily a
 * hard failure — CheckerService retries by re-fetching the slot list and
 * trying a different id. This service exposes the raw building blocks;
 * the retry orchestration lives in CheckerService.
 *
 * ── UNCONFIRMED RESPONSE SHAPES ────────────────────────────────────────────
 * The endpoint LIST and PURPOSE (from DataBossHub's own docs) are solid:
 *   GET  /checker/price
 *   GET  /checker/slots?category=...
 *   GET  /checker/my-slots
 *   POST /checker/buy/{id}
 * But exact JSON field names in each response are NOT shown in the docs
 * supplied — only prose descriptions ("Each shows your tier price and
 * category", "serial, PIN, exam date, and results link"). Every parsing
 * method below tries several plausible field-name candidates and logs the
 * full raw body at INFO level specifically so the first real call reveals
 * the true shape — inspect those logs and tighten the candidate lists
 * in extractCheckerFields() once confirmed.
 *
 * IMPORTANT — set in application.properties:
 *   databosshub.api-key=your_api_key_here
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataBossHubService {

    @Qualifier("dataBossHubWebClient")
    private final WebClient dataBossHubWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ══════════════════════════════════════════════════════════════════════
    // 1. PHONE-DELIVERY CORROBORATION (data-bundle diagnostic tool — unchanged)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Check whether a given phone number has a recent recorded data-bundle
     * delivery. PHONE-level, not order-level — see class Javadoc. Never
     * throws; returns a best-effort "unavailable" response on any failure
     * so a failed corroboration check never blocks an admin's UI.
     */
    public PhoneDeliveryCheckResponse checkPhoneDelivery(String phone) {
        log.info("[DATABOSSHUB] Checking phone delivery: phone={}", phone);

        if (phone == null || phone.isBlank()) {
            log.warn("[DATABOSSHUB] checkPhoneDelivery called with blank phone — skipping call");
            return unavailableResponse(phone, "No phone number provided");
        }

        try {
            Map<String, Object> requestBody = Map.of("phone", phone);

            String rawBody = dataBossHubWebClient.post()
                    .uri("/checker/check")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATABOSSHUB] /checker/check raw response for phone={}: [{}]", phone, rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATABOSSHUB] /checker/check returned empty body for phone={}", phone);
                return unavailableResponse(phone, "Empty response from DataBossHub");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(rawBody, Map.class);

            PhoneDeliveryCheckResponse result = PhoneDeliveryCheckResponse.builder()
                    .phone(asString(data.get("phone"), phone))
                    .status(asString(data.get("status"), "unknown"))
                    .latestOrder(asString(data.get("latest_order"), null))
                    .lastDelivery(asString(data.get("last_delivery"), null))
                    .deliveryCount(asInt(data.get("delivery_count")))
                    .message(asString(data.get("message"), null))
                    .build();

            log.info("[DATABOSSHUB] ✔ Delivery check result: phone={} status={} deliveryCount={} latestOrder={}",
                    phone, result.getStatus(), result.getDeliveryCount(), result.getLatestOrder());

            return result;

        } catch (WebClientResponseException ex) {
            log.error("[DATABOSSHUB] HTTP {} checking phone={}: {}",
                    ex.getStatusCode(), phone, ex.getResponseBodyAsString());
            return unavailableResponse(phone, "DataBossHub HTTP error: " + ex.getStatusCode());
        } catch (Exception ex) {
            log.error("[DATABOSSHUB] Unexpected error checking phone={} — type={} error={}",
                    phone, ex.getClass().getSimpleName(), ex.getMessage());
            return unavailableResponse(phone, "DataBossHub check failed: " + ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. RESULT-CHECKER PURCHASE — the real transactional path
    // ══════════════════════════════════════════════════════════════════════

    /**
     * GET /checker/price — your tier price and all tier prices for reference.
     * Returned as a raw Map — see class Javadoc on unconfirmed shape.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchCheckerPrice() {
        log.info("[DATABOSSHUB] Fetching checker price tiers");
        try {
            String rawBody = dataBossHubWebClient.get()
                    .uri("/checker/price")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATABOSSHUB] GET /checker/price raw body: [{}]", rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATABOSSHUB] /checker/price returned empty body");
                return Map.of();
            }
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception ex) {
            log.warn("[DATABOSSHUB] /checker/price fetch failed — type={} error={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    /**
     * GET /checker/slots?category=... — list available (unclaimed) checker
     * slots, optionally filtered by category (e.g. "BECE"). Credentials are
     * NOT included — only id/category/price, per DataBossHub's own docs
     * ("No credentials exposed — hidden until purchase").
     *
     * @param category exact category string to filter by (pass the
     *                  CheckerPricing.dataBossHubCategory value for the
     *                  exam type you want, NOT the Java enum name directly
     *                  unless you've confirmed they match) — null/blank
     *                  for no filter.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchAvailableSlots(String category) {
        log.info("[DATABOSSHUB] Fetching available checker slots — category={}", category);
        try {
            String rawBody = dataBossHubWebClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/checker/slots");
                        if (category != null && !category.isBlank()) {
                            b.queryParam("category", category);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATABOSSHUB] GET /checker/slots raw body (category={}): [{}]", category, rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATABOSSHUB] /checker/slots returned empty body");
                return List.of();
            }

            Object parsed = objectMapper.readValue(rawBody, Object.class);
            List<Map<String, Object>> slots = extractListFromResponse(parsed);

            log.info("[DATABOSSHUB] /checker/slots: {} slot(s) available (category={})",
                    slots.size(), category);
            return slots;

        } catch (Exception ex) {
            log.warn("[DATABOSSHUB] /checker/slots fetch failed — category={} type={} error={}",
                    category, ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * GET /checker/my-slots — list checkers already purchased by this
     * account, with serial/PIN/exam date/results link. Useful for admin
     * reconciliation against our own checker_orders table.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchMyPurchasedSlots() {
        log.info("[DATABOSSHUB] Fetching my purchased checker slots");
        try {
            String rawBody = dataBossHubWebClient.get()
                    .uri("/checker/my-slots")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATABOSSHUB] GET /checker/my-slots raw body: [{}]", rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[DATABOSSHUB] /checker/my-slots returned empty body");
                return List.of();
            }

            Object parsed = objectMapper.readValue(rawBody, Object.class);
            return extractListFromResponse(parsed);

        } catch (Exception ex) {
            log.warn("[DATABOSSHUB] /checker/my-slots fetch failed — type={} error={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * POST /checker/buy/{id} — claim a specific checker slot. Deducts the
     * tier price from our DataBossHub account balance (NOT the end
     * customer's wallet on our platform — that's a separate transaction
     * CheckerService handles before ever calling this). Returns the full
     * credentials synchronously on success.
     *
     * Throws UpstreamApiException on any failure — including the slot
     * having already been claimed by someone else (contention). The caller
     * (CheckerService) is responsible for deciding whether to retry with a
     * different slot id; this method does not retry internally, since it
     * has no way to pick a different id itself.
     *
     * @return raw credential fields as a Map — see class Javadoc on
     *         unconfirmed exact key names; CheckerService extracts via
     *         extractCheckerFields() below rather than assuming keys directly.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buyCheckerSlot(String slotId) {
        log.info("[DATABOSSHUB] Buying checker slot: slotId={}", slotId);

        try {
            String rawBody = dataBossHubWebClient.post()
                    .uri("/checker/buy/{id}", slotId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[DATABOSSHUB] POST /checker/buy/{} raw response: [{}]", slotId, rawBody);

            if (rawBody == null || rawBody.isBlank()) {
                throw new UpstreamApiException(
                        "DataBossHub returned empty body buying checker slotId=" + slotId);
            }

            Map<String, Object> response = objectMapper.readValue(rawBody, Map.class);

            if (response.containsKey("success") && !Boolean.TRUE.equals(response.get("success"))) {
                throw new UpstreamApiException(
                        "DataBossHub checker purchase rejected for slotId=" + slotId
                                + " — body: " + rawBody);
            }

            Object data = response.getOrDefault("data", response);
            if (!(data instanceof Map)) {
                throw new UpstreamApiException(
                        "DataBossHub checker purchase response has no usable data object for slotId="
                                + slotId + " — body: " + rawBody);
            }

            log.info("[DATABOSSHUB] ✔ Checker slot purchased: slotId={}", slotId);
            return (Map<String, Object>) data;

        } catch (WebClientResponseException ex) {
            log.warn("[DATABOSSHUB] HTTP {} buying checker slotId={}: {}",
                    ex.getStatusCode(), slotId, ex.getResponseBodyAsString());
            throw new UpstreamApiException(
                    "DataBossHub checker purchase failed: HTTP " + ex.getStatusCode()
                            + " for slotId=" + slotId + " — " + ex.getResponseBodyAsString());
        } catch (UpstreamApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[DATABOSSHUB] Unexpected error buying checker slotId={} — type={} error={}",
                    slotId, ex.getClass().getSimpleName(), ex.getMessage());
            throw new UpstreamApiException(
                    "DataBossHub checker purchase failed for slotId=" + slotId + ": " + ex.getMessage());
        }
    }

    // ── Field extraction helpers (defensive — multiple candidate keys) ────────

    /**
     * Extracts a checker slot's id from a /checker/slots list entry.
     * Tries common id-field candidates since the exact key is unconfirmed.
     */
    public String extractSlotId(Map<String, Object> slotEntry) {
        Object id = firstNonNull(slotEntry, "id", "slot_id", "slotId");
        return id != null ? id.toString() : null;
    }

    public String extractSlotCategory(Map<String, Object> slotEntry) {
        Object category = firstNonNull(slotEntry, "category", "exam_type", "type");
        return category != null ? category.toString() : null;
    }

    /**
     * Extracts the delivered credentials from a /checker/buy/{id} response's
     * data object. Tries several plausible field-name candidates for each
     * value — verify against a real response and trim this down once
     * confirmed.
     */
    public CheckerCredentials extractCheckerFields(Map<String, Object> data) {
        String serial = firstNonNullString(data, "serial", "serial_number", "serialNumber");
        String pin = firstNonNullString(data, "pin", "checker_pin", "checkerPin");
        String examDate = firstNonNullString(data, "exam_date", "examDate", "exam_year", "year");
        String resultsLink = firstNonNullString(data, "results_link", "resultsLink", "check_url", "checkUrl", "url");

        log.info("[DATABOSSHUB] Extracted checker credentials — serial={} pin={} examDate={} resultsLink={}",
                serial, pin != null ? "[present]" : "null", examDate, resultsLink);

        return new CheckerCredentials(serial, pin, examDate, resultsLink);
    }

    public record CheckerCredentials(String serial, String pin, String examDate, String resultsLink) {}

    // ── Generic helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractListFromResponse(Object parsed) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (parsed instanceof List) {
            for (Object o : (List<Object>) parsed) {
                if (o instanceof Map) result.add((Map<String, Object>) o);
            }
        } else if (parsed instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) parsed;
            Object data = map.getOrDefault("data", map.get("slots"));
            if (data instanceof List) {
                for (Object o : (List<Object>) data) {
                    if (o instanceof Map) result.add((Map<String, Object>) o);
                }
            }
        }

        if (result.isEmpty()) {
            log.warn("[DATABOSSHUB] Could not locate a list in response — check raw body logged above " +
                    "and adjust extractListFromResponse()");
        }
        return result;
    }

    private Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null) return v;
        }
        return null;
    }

    private String firstNonNullString(Map<String, Object> map, String... keys) {
        Object v = firstNonNull(map, keys);
        return v != null ? v.toString() : null;
    }

    private PhoneDeliveryCheckResponse unavailableResponse(String phone, String message) {
        return PhoneDeliveryCheckResponse.builder()
                .phone(phone)
                .status("unavailable")
                .deliveryCount(null)
                .message(message)
                .build();
    }

    private String asString(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
