package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.entity.CheckerOrder;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerStock;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.repos.CheckerOrderRepository;
import com.databundleHum.OnetBundleHub.repos.CheckerPricingRepository;
import com.databundleHum.OnetBundleHub.repos.CheckerStockRepository;
import com.databundleHum.OnetBundleHub.security.ConflictException;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.BigDreamsDataService;
import com.databundleHum.OnetBundleHub.services.CheckerService;
import com.databundleHum.OnetBundleHub.services.DataBossHubService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin management of result-checker STOCK — the inventory
 * CheckerService.provisionFromStock() draws from on every customer
 * purchase (see that method's Javadoc for why the old live-DataBossHub
 * purchase flow was replaced with this).
 *
 * Three ways stock gets added, all landing in the same CheckerStock table:
 *   1. Manual paste  — admin already bought codes elsewhere, pastes them in.
 *   2. DataBossHub    — one click buys N slots from DataBossHub live and
 *                       stores the results (reuses DataBossHubService,
 *                       previously called directly from CheckerService).
 *   3. Big Dreams Data — one click buys N checkers from Big Dreams Data live
 *                       and stores the results (BigDreamsDataService).
 *
 * Base path intentionally matches the sibling AdminCheckerPricingController
 * (/api/admin/checker-pricing) rather than AdminController's /api/v1/admin
 * prefix — both prefixes are independently protected with ROLE_SUPER_ADMIN
 * in SecurityConfig, this just follows its closest relative's convention.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/admin/checker-stock")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCheckerStockController {

    private final CheckerStockRepository   checkerStockRepository;
    private final CheckerPricingRepository checkerPricingRepository;
    private final CheckerOrderRepository   checkerOrderRepository;
    private final CheckerService           checkerService;
    private final DataBossHubService       dataBossHubService;
    private final BigDreamsDataService     bigDreamsDataService;

    private UUID currentAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/checker-stock/summary
     * Available/used counts for every exam type — the number that actually
     * matters day-to-day ("do we need to restock BECE?").
     */
    @GetMapping("/summary")
    public ResponseEntity<List<StockSummary>> getSummary() {
        log.info("[ADMIN-CHECKER-STOCK] Fetching stock summary");

        List<StockSummary> summary = new ArrayList<>();
        for (CheckerPricing.ExamType examType : CheckerPricing.ExamType.values()) {
            long available = checkerStockRepository.countByExamTypeAndUsedFalse(examType);
            long used = checkerStockRepository.countByExamTypeAndUsedTrue(examType);
            summary.add(new StockSummary(examType, available, used));
        }

        log.info("[ADMIN-CHECKER-STOCK] Summary: {}", summary);
        return ResponseEntity.ok(summary);
    }

    public record StockSummary(CheckerPricing.ExamType examType, long available, long used) {}

    // ── Stuck orders (paid but never provisioned) ───────────────────────────────

    /**
     * GET /api/admin/checker-stock/stuck-orders
     * Orders where payment cleared (VERIFIED) but no code was ever handed
     * out — customer paid, sees "Pending" forever, and nothing else in the
     * system was coming back to fix it before this endpoint existed. A
     * background sweep now retries these automatically every 5 minutes
     * (see CheckerService.reconcileStuckOrders), but this lets you find and
     * fix one immediately rather than waiting on the sweep.
     */
    @GetMapping("/stuck-orders")
    public ResponseEntity<List<CheckerOrder>> getStuckOrders() {
        List<CheckerOrder> stuck = checkerService.findStuckOrders();
        log.info("[ADMIN-CHECKER-STOCK] Found {} stuck (VERIFIED) checker order(s)", stuck.size());
        return ResponseEntity.ok(stuck);
    }

    /**
     * POST /api/admin/checker-stock/stuck-orders/{orderId}/retry
     * Re-runs provisioning for one stuck order right now. If stock is
     * available for that exam type it completes immediately and the
     * customer can see their code the moment you check the order again. If
     * stock is still empty it fails again with the same "out of stock"
     * reason — restock first, then retry.
     */
    @PostMapping("/stuck-orders/{orderId}/retry")
    public ResponseEntity<CheckerOrderResponse> retryStuckOrder(@PathVariable Long orderId) {
        log.info("[ADMIN-CHECKER-STOCK] Manual retry requested: orderId={}", orderId);
        CheckerOrderResponse response = checkerService.retryStuckOrder(orderId);
        log.info("[ADMIN-CHECKER-STOCK] Retry result: orderId={} status={}", orderId, response.getStatus());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/admin/checker-stock/stuck-orders/{orderId}/complete-manually
     * Forces a specific order straight to COMPLETED using a code you supply
     * directly in the request, bypassing the stock queue entirely. Use this
     * when you need to hand a specific customer a specific code right now —
     * e.g. one you already have on hand outside the automated stock table —
     * rather than drawing whatever's next in the queue.
     */
    @PostMapping("/stuck-orders/{orderId}/complete-manually")
    public ResponseEntity<CheckerOrderResponse> completeOrderManually(
            @PathVariable Long orderId, @Valid @RequestBody ManualCompleteRequest request) {
        log.info("[ADMIN-CHECKER-STOCK] Manual completion requested: orderId={}", orderId);
        CheckerOrderResponse response = checkerService.manuallyCompleteOrder(
                orderId, request.getSerial(), request.getPin(), request.getExamDate(), request.getResultsLink());
        log.info("[ADMIN-CHECKER-STOCK] ✔ Manually completed: orderId={}", orderId);
        return ResponseEntity.ok(response);
    }

    @Data
    public static class ManualCompleteRequest {
        @NotNull
        private String serial;
        @NotNull
        private String pin;
        private String examDate;
        private String resultsLink;
    }

    /**
     * GET /api/admin/checker-stock?examType=BECE&used=false&page=0&size=20
     * Browse individual stock rows — mainly for spotting a specific serial
     * or confirming a restock actually landed.
     */
    @GetMapping
    public ResponseEntity<Page<CheckerStock>> list(
            @RequestParam CheckerPricing.ExamType examType,
            @RequestParam(required = false) Boolean used,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("[ADMIN-CHECKER-STOCK] Listing: examType={} used={} page={} size={}", examType, used, page, size);
        Pageable pageable = PageRequest.of(page, size);

        Page<CheckerStock> result = used == null
                ? checkerStockRepository.findByExamTypeOrderByCreatedAtDesc(examType, pageable)
                : checkerStockRepository.findByExamTypeAndUsedOrderByCreatedAtDesc(examType, used, pageable);

        return ResponseEntity.ok(result);
    }

    // ── Manual upload ─────────────────────────────────────────────────────────

    /**
     * POST /api/admin/checker-stock/manual
     * Admin already bought codes elsewhere (their own DataBossHub/Big
     * Dreams dashboard, or any other source) — paste serial+pin pairs in
     * directly. Every row is inserted as source=MANUAL regardless of where
     * the admin actually got it from.
     */
    @PostMapping("/manual")
    public ResponseEntity<List<CheckerStock>> uploadManual(@Valid @RequestBody ManualUploadRequest request) {
        log.info("[ADMIN-CHECKER-STOCK] Manual upload: examType={} count={}",
                request.getExamType(), request.getCodes().size());

        UUID adminId = currentAdminId();
        List<CheckerStock> saved = new ArrayList<>();

        for (ManualCode code : request.getCodes()) {
            CheckerStock stock = CheckerStock.builder()
                    .examType(request.getExamType())
                    .serial(code.getSerial())
                    .pin(code.getPin())
                    .examDate(code.getExamDate())
                    .resultsLink(code.getResultsLink())
                    .source(CheckerStock.StockSource.MANUAL)
                    .addedByAdminId(adminId)
                    .used(false)
                    .build();
            saved.add(checkerStockRepository.save(stock));
        }

        log.info("[ADMIN-CHECKER-STOCK] ✔ Manually added {} code(s) for examType={} by adminId={}",
                saved.size(), request.getExamType(), adminId);
        return ResponseEntity.ok(saved);
    }

    @Data
    public static class ManualUploadRequest {
        @NotNull
        private CheckerPricing.ExamType examType;
        @NotEmpty
        private List<ManualCode> codes;
    }

    @Data
    public static class ManualCode {
        @NotNull
        private String serial;
        @NotNull
        private String pin;
        private String examDate;
        private String resultsLink;
    }

    // ── Restock via DataBossHub ─────────────────────────────────────────────────

    /**
     * POST /api/admin/checker-stock/restock/databosshub
     * Buys `quantity` checkers from DataBossHub live, right now, and adds
     * each successfully purchased one to stock. Stops early (rather than
     * failing the whole batch) if DataBossHub runs out of slots or a
     * purchase attempt fails — partial success is reported back, not
     * silently swallowed or thrown away.
     *
     * Requires CheckerPricing.dataBossHubCategory to be configured for the
     * requested exam type (see AdminCheckerPricingController).
     */
    @PostMapping("/restock/databosshub")
    public ResponseEntity<RestockResult> restockFromDataBossHub(@Valid @RequestBody RestockRequest request) {
        log.info("[ADMIN-CHECKER-STOCK] Restock via DataBossHub: examType={} quantity={}",
                request.getExamType(), request.getQuantity());

        CheckerPricing pricing = checkerPricingRepository.findByExamTypeAndActiveTrue(request.getExamType())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active CheckerPricing for examType=" + request.getExamType()));

        String category = pricing.getDataBossHubCategory();
        if (category == null || category.isBlank()) {
            throw new ConflictException(
                    "CheckerPricing for examType=" + request.getExamType()
                            + " has no dataBossHubCategory configured — set it before restocking from DataBossHub.");
        }

        UUID adminId = currentAdminId();
        List<CheckerStock> added = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < request.getQuantity(); i++) {
            List<Map<String, Object>> slots = dataBossHubService.fetchAvailableSlots(category);
            if (slots.isEmpty()) {
                log.warn("[ADMIN-CHECKER-STOCK] DataBossHub has no more slots for category={} — stopping at {}/{}",
                        category, added.size(), request.getQuantity());
                failures.add("No more DataBossHub slots available for category=" + category);
                break;
            }

            String slotId = dataBossHubService.extractSlotId(slots.get(0));
            if (slotId == null) {
                failures.add("Could not extract slot id from DataBossHub response");
                break;
            }

            try {
                Map<String, Object> credentialsRaw = dataBossHubService.buyCheckerSlot(slotId);
                DataBossHubService.CheckerCredentials creds = dataBossHubService.extractCheckerFields(credentialsRaw);

                if (creds.serial() == null || creds.pin() == null) {
                    failures.add("DataBossHub slotId=" + slotId + " returned no serial/pin");
                    continue;
                }

                CheckerStock stock = checkerStockRepository.save(CheckerStock.builder()
                        .examType(request.getExamType())
                        .serial(creds.serial())
                        .pin(creds.pin())
                        .examDate(creds.examDate())
                        .resultsLink(creds.resultsLink())
                        .source(CheckerStock.StockSource.DATABOSSHUB)
                        .addedByAdminId(adminId)
                        .used(false)
                        .build());
                added.add(stock);

            } catch (UpstreamApiException ex) {
                log.warn("[ADMIN-CHECKER-STOCK] DataBossHub purchase failed for slotId={}: {}",
                        slotId, ex.getMessage());
                failures.add("slotId=" + slotId + ": " + ex.getMessage());
            }
        }

        log.info("[ADMIN-CHECKER-STOCK] ✔ DataBossHub restock complete: examType={} added={}/{} failures={} adminId={}",
                request.getExamType(), added.size(), request.getQuantity(), failures.size(), adminId);

        return ResponseEntity.ok(new RestockResult(added.size(), request.getQuantity(), failures, added));
    }

    // ── Restock via Big Dreams Data ─────────────────────────────────────────────

    /**
     * POST /api/admin/checker-stock/restock/bigdreams
     * Buys `quantity` checkers from Big Dreams Data live, right now, and
     * adds each successfully purchased one to stock. Same partial-success
     * reporting as the DataBossHub variant above.
     */
    @PostMapping("/restock/bigdreams")
    public ResponseEntity<RestockResult> restockFromBigDreams(@Valid @RequestBody RestockRequest request) {
        log.info("[ADMIN-CHECKER-STOCK] Restock via Big Dreams Data: examType={} quantity={}",
                request.getExamType(), request.getQuantity());

        String productType = request.getExamType().name().toLowerCase();
        UUID adminId = currentAdminId();
        List<CheckerStock> added = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < request.getQuantity(); i++) {
            try {
                BigDreamsDataService.BuyCheckerResult result = bigDreamsDataService.buyResultChecker(productType);

                CheckerStock stock = checkerStockRepository.save(CheckerStock.builder()
                        .examType(request.getExamType())
                        .serial(result.serial())
                        .pin(result.pin())
                        .source(CheckerStock.StockSource.BIGDREAMS)
                        .addedByAdminId(adminId)
                        .used(false)
                        .build());
                added.add(stock);

            } catch (UpstreamApiException ex) {
                log.warn("[ADMIN-CHECKER-STOCK] Big Dreams Data purchase {}/{} failed: {}",
                        i + 1, request.getQuantity(), ex.getMessage());
                failures.add(ex.getMessage());
                // Keep trying remaining quantity — a single failed purchase
                // (e.g. transient error) shouldn't abandon the whole batch
                // the way an empty slot list does for DataBossHub.
            }
        }

        log.info("[ADMIN-CHECKER-STOCK] ✔ Big Dreams Data restock complete: examType={} added={}/{} failures={} adminId={}",
                request.getExamType(), added.size(), request.getQuantity(), failures.size(), adminId);

        return ResponseEntity.ok(new RestockResult(added.size(), request.getQuantity(), failures, added));
    }

    @Data
    public static class RestockRequest {
        @NotNull
        private CheckerPricing.ExamType examType;
        @NotNull
        @Min(1)
        private Integer quantity;
    }

    public record RestockResult(int added, int requested, List<String> failures, List<CheckerStock> stock) {}

    // ── Delete (mistake correction — unused rows only) ──────────────────────────

    /**
     * DELETE /api/admin/checker-stock/{id}
     * Removes a stock row that hasn't been sold yet — e.g. a manually
     * pasted code that turns out to be a typo. Refuses to delete anything
     * already handed to a customer (used=true); that's order history now,
     * not inventory.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("[ADMIN-CHECKER-STOCK] Delete requested: id={}", id);

        CheckerStock stock = checkerStockRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Checker stock not found: " + id));

        if (stock.isUsed()) {
            throw new ConflictException(
                    "Cannot delete stock id=" + id + " — already sold on checkerOrderId="
                            + stock.getCheckerOrderId());
        }

        checkerStockRepository.delete(stock);
        log.info("[ADMIN-CHECKER-STOCK] ✔ Deleted unused stock: id={}", id);
        return ResponseEntity.noContent().build();
    }
}
