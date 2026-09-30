package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.AdminResellerResponse;
import com.databundleHum.OnetBundleHub.dtos.response.*;
import com.databundleHum.OnetBundleHub.entity.*;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.AdminService;
import com.databundleHum.OnetBundleHub.services.UnverifiedRecipientService;
import com.databundleHum.OnetBundleHub.entity.UnverifiedRecipient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.util.List;

/**
 * Super-Admin REST controller.
 *
 * <p>All endpoints require {@code ROLE_SUPER_ADMIN}.
 * The authenticated admin's ID is extracted from the JWT subject via
 * {@link SecurityContextHolder} — no custom annotation needed.
 *
 * <p>Base path: {@code /api/v1/admin}
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")   // ✅ FIXED: was hasRole('ADMIN') — role is ROLE_SUPER_ADMIN
@Tag(name = "Admin", description = "Super-admin management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final AdminService adminService;
    private final UnverifiedRecipientService unverifiedRecipientService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the current admin's UUID from the JWT subject.
     * Spring Security stores the subject (UUID string) as the principal name.
     */
    private UUID currentAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    @GetMapping("/unverified-recipients")
    @Operation(summary = "List deduplicated failed recipient numbers for a day")
    public ResponseEntity<List<UnverifiedRecipient>> getUnverifiedRecipients(
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(unverifiedRecipientService.daily(date == null ? LocalDate.now() : date));
    }

    @GetMapping("/unverified-recipients.csv")
    @Operation(summary = "Download daily failed recipient CSV")
    public ResponseEntity<byte[]> downloadUnverifiedRecipients(
            @RequestParam(required = false) LocalDate date) {
        LocalDate reportDate = date == null ? LocalDate.now() : date;
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=unverified-recipients-" + reportDate + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(unverifiedRecipientService.csv(reportDate));
    }

    @PostMapping("/unverified-recipients/{id}/submit")
    public ResponseEntity<UnverifiedRecipient> submitUnverifiedRecipient(@PathVariable Long id) {
        return ResponseEntity.ok(unverifiedRecipientService.submit(id));
    }

    @PostMapping("/unverified-recipients/{id}/verify")
    public ResponseEntity<UnverifiedRecipient> verifyUnverifiedRecipient(@PathVariable Long id) {
        return ResponseEntity.ok(unverifiedRecipientService.markVerified(id, currentAdminId().toString()));
    }

    @PostMapping("/unverified-recipients/{id}/notify-account")
    public ResponseEntity<Void> notifyFailedRecipientAccount(@PathVariable Long id) {
        unverifiedRecipientService.notifySourceAccount(id);
        return ResponseEntity.noContent().build();
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    @Operation(summary = "Get platform KPI dashboard")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dashboard fetched"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<AdminDashboardResponse> getDashboard() {
        log.info("[ADMIN] Dashboard requested by adminId={}", currentAdminId());
        AdminDashboardResponse response = adminService.getDashboard();
        log.debug("[ADMIN] Dashboard response: {}", response);
        return ResponseEntity.ok(response);
    }

    // ── User Management ───────────────────────────────────────────────────────

    @GetMapping("/users")
    @Operation(summary = "List all users (paginated)")
    public ResponseEntity<Page<AdminUserResponse>> getUsers(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size")            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field")           @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction")       @RequestParam(defaultValue = "DESC") Sort.Direction direction) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        log.info("[ADMIN] List users requested by adminId={} page={} size={}", currentAdminId(), page, size);
        return ResponseEntity.ok(adminService.getUsers(pageable));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get a single user by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable UUID userId) {
        log.info("[ADMIN] Get user userId={} requested by adminId={}", userId, currentAdminId());
        return ResponseEntity.ok(adminService.getUser(userId));
    }

    @PostMapping("/users/{userId}/wallet-adjustment")
    @Operation(summary = "Credit or debit a user's wallet with an audited reason")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Wallet adjusted"),
            @ApiResponse(responseCode = "400", description = "Invalid adjustment or insufficient wallet balance"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<AdminUserResponse> adjustUserWallet(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminWalletAdjustmentRequest request) {
        UUID adminId = currentAdminId();
        log.info("[ADMIN] Wallet adjustment requested: adminId={} userId={} action={} amount={}",
                adminId, userId, request.getAction(), request.getAmount());
        return ResponseEntity.ok(adminService.adjustUserWallet(adminId, userId, request));
    }

    @PatchMapping("/users/{userId}/active")
    @Operation(summary = "Activate or deactivate a user account")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User status updated"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<AdminUserResponse> setUserActive(
            @PathVariable UUID userId,
            @RequestParam @NotNull Boolean active) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Set user active={} for userId={} by adminId={}", active, userId, adminId);
        AdminUserResponse response = adminService.setUserActive(adminId, userId, active);
        log.info("[ADMIN] User userId={} active={} updated successfully by adminId={}", userId, active, adminId);
        return ResponseEntity.ok(response);
    }

    // ── Reseller Management ───────────────────────────────────────────────────

    @GetMapping("/resellers")
    @Operation(summary = "List all reseller profiles (paginated)")
    public ResponseEntity<Page<AdminResellerResponse>> getResellers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ResellerProfile.ResellerStatus status) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        log.info("[ADMIN] List resellers requested by adminId={} status={} page={}", currentAdminId(), status, page);

        Page<AdminResellerResponse> result = (status != null)
                ? adminService.getResellersByStatus(status, pageable)
                : adminService.getResellers(pageable);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/resellers/{profileId}/approve")
    @Operation(summary = "Approve a reseller application")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Application approved"),
            @ApiResponse(responseCode = "400", description = "Application not in PENDING state"),
            @ApiResponse(responseCode = "404", description = "Profile not found")
    })
    public ResponseEntity<AdminResellerResponse> approveReseller(
            @PathVariable UUID profileId,
            @Valid @RequestBody AdminResellerActionRequest request) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Approve reseller profileId={} by adminId={}", profileId, adminId);
        AdminResellerResponse response = adminService.approveReseller(adminId, profileId, request);
        log.info("[ADMIN] Reseller profileId={} APPROVED by adminId={}", profileId, adminId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resellers/{profileId}/reject")
    @Operation(summary = "Reject a reseller application (auto-refunds GHS 20 fee)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Application rejected and fee refunded"),
            @ApiResponse(responseCode = "400", description = "Application not in PENDING state"),
            @ApiResponse(responseCode = "404", description = "Profile not found")
    })
    public ResponseEntity<AdminResellerResponse> rejectReseller(
            @PathVariable UUID profileId,
            @Valid @RequestBody AdminResellerActionRequest request) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Reject reseller profileId={} by adminId={} reason={}", profileId, adminId, request.getNote());
        AdminResellerResponse response = adminService.rejectReseller(adminId, profileId, request);
        log.info("[ADMIN] Reseller profileId={} REJECTED by adminId={}", profileId, adminId);
        return ResponseEntity.ok(response);
    }

    // ── Payout Management ─────────────────────────────────────────────────────

    @GetMapping("/payouts")
    @Operation(summary = "List payout requests (paginated, optionally filtered by status)")
    public ResponseEntity<Page<PayoutResponse>> getPayouts(
            @RequestParam(required = false) Payout.PayoutStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        log.info("[ADMIN] List payouts requested by adminId={} status={}", currentAdminId(), status);
        return ResponseEntity.ok(adminService.getPayouts(status, pageable));
    }

    @PostMapping("/payouts/{payoutId}/pay")
    @Operation(summary = "Mark a payout as PAID (deducts from reseller wallet)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payout marked as PAID"),
            @ApiResponse(responseCode = "400", description = "Payout not in PENDING or PROCESSING state"),
            @ApiResponse(responseCode = "404", description = "Payout not found")
    })
    public ResponseEntity<PayoutResponse> markPayoutPaid(
            @PathVariable Long payoutId,
            @Valid @RequestBody AdminPayoutPaidRequest request) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Mark payout PAID payoutId={} by adminId={}", payoutId, adminId);
        PayoutResponse response = adminService.markPayoutPaid(adminId, payoutId, request);
        log.info("[ADMIN] Payout payoutId={} marked PAID by adminId={}", payoutId, adminId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payouts/{payoutId}/reject")
    @Operation(summary = "Reject a payout request (no wallet deduction)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payout rejected"),
            @ApiResponse(responseCode = "400", description = "Payout not in PENDING state"),
            @ApiResponse(responseCode = "404", description = "Payout not found")
    })
    public ResponseEntity<PayoutResponse> rejectPayout(
            @PathVariable Long payoutId,
            @Valid @RequestBody AdminPayoutRejectRequest request) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Reject payout payoutId={} by adminId={} reason={}", payoutId, adminId, request.getReason());
        PayoutResponse response = adminService.rejectPayout(adminId, payoutId, request);
        log.info("[ADMIN] Payout payoutId={} REJECTED by adminId={}", payoutId, adminId);
        return ResponseEntity.ok(response);
    }

    // ── Platform Pricing ──────────────────────────────────────────────────────

    @GetMapping("/pricing")
    @Operation(summary = "List platform pricing table (public + reseller prices per network/GB)")
    public ResponseEntity<Page<AdminPricingResponse>> getPricingTable(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("network", "capacityGb"));
        log.info("[ADMIN] Pricing table requested by adminId={}", currentAdminId());
        return ResponseEntity.ok(adminService.getPricingTable(pageable));
    }

    @PutMapping("/pricing")
    @Operation(summary = "Create or update a pricing row (resellerPrice must be < publicPrice)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pricing row upserted"),
            @ApiResponse(responseCode = "400", description = "Reseller price >= public price")
    })
    public ResponseEntity<AdminPricingResponse> upsertPricing(
            @Valid @RequestBody AdminPricingRequest request) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Upsert pricing by adminId={} network={} gb={} public={} reseller={}",
                adminId, request.getNetwork(), request.getCapacityGb(),
                request.getPublicPriceGhc(), request.getResellerPriceGhc());
        AdminPricingResponse response = adminService.upsertPricing(adminId, request);
        log.info("[ADMIN] Pricing upserted id={} by adminId={}", response.getId(), adminId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/pricing/{settingsId}/active")
    @Operation(summary = "Enable or disable a bundle offering")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bundle toggled"),
            @ApiResponse(responseCode = "404", description = "Pricing row not found")
    })
    public ResponseEntity<AdminPricingResponse> toggleBundleActive(
            @PathVariable Long settingsId,
            @RequestParam @NotNull Boolean active) {

        UUID adminId = currentAdminId();
        log.info("[ADMIN] Toggle bundle settingsId={} active={} by adminId={}", settingsId, active, adminId);
        AdminPricingResponse response = adminService.toggleBundleActive(adminId, settingsId, active);
        log.info("[ADMIN] Bundle settingsId={} active={} updated by adminId={}", settingsId, active, adminId);
        return ResponseEntity.ok(response);
    }

    // ── All Orders / All Transactions ─────────────────────────────────────────

    @GetMapping("/orders")
    @Operation(summary = "List all orders across the platform (paginated)")
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction,
            @RequestParam(defaultValue = "ALL") String filter) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        log.info("[ADMIN] All-orders requested by adminId={} page={} size={} filter={}",
                currentAdminId(), page, size, filter);
        return ResponseEntity.ok(adminService.getAllOrders(pageable, filter));
    }

    @GetMapping("/transactions")
    @Operation(summary = "List all wallet transactions across the platform (paginated)")
    public ResponseEntity<Page<WalletTransactionResponse>> getAllTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        log.info("[ADMIN] All-transactions requested by adminId={} page={} size={}", currentAdminId(), page, size);
        return ResponseEntity.ok(adminService.getAllTransactions(pageable));
    }

    @PostMapping("/resellers/backfill-slugs")
    public ResponseEntity<Map<String, Object>> backfillSlugs() {
        int updated = adminService.backfillMissingSlugs();
        return ResponseEntity.ok(Map.of(
                "message", "Backfill complete",
                "profilesUpdated", updated
        ));
    }
}
