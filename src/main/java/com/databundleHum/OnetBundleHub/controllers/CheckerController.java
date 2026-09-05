package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerPublicPricingResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.CheckerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Result-checker (BECE/WASSCE) purchase endpoints — guest and wallet flows.
 *
 * ── ✅ FIXED: was @AuthenticationPrincipal UUID userId ──────────────────
 * JwtAuthFilter sets a UserPrincipal record as the Authentication
 * principal (see its Javadoc), not a raw UUID — a declared parameter type
 * of UUID never matched the runtime principal type, so
 * @AuthenticationPrincipal silently resolved to null on every call here.
 * Every wallet checker purchase and history lookup was broken. Fixed to
 * use the same currentUserId() SecurityContextHolder pattern already
 * proven working in WalletController and OrderController.
 */
@Slf4j
@RestController
@RequestMapping("/api/checkers")
@RequiredArgsConstructor
public class CheckerController {

    private final CheckerService checkerService;

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    // ── Public pricing ────────────────────────────────────────────────────────

    @GetMapping("/pricing")
    public ResponseEntity<List<CheckerPublicPricingResponse>> getPublicPricing() {
        log.info("[CHECKER-CONTROLLER] GET /pricing (public)");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(checkerService.getPublicPricing());
    }

    // ── Guest flow ────────────────────────────────────────────────────────────

    @PostMapping("/guest/initiate")
    public ResponseEntity<InitiateCheckerOrderResponse> initiateGuestOrder(
            @Valid @RequestBody InitiateGuestCheckerOrderRequest request) {
        log.info("[CHECKER-CONTROLLER] POST /guest/initiate: phone={} examType={}",
                request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(checkerService.initiateGuestCheckerOrder(request));
    }

    @GetMapping("/guest/status")
    public ResponseEntity<CheckerOrderResponse> getGuestOrderStatus(@RequestParam String reference) {
        log.info("[CHECKER-CONTROLLER] GET /guest/status: reference={}", reference);
        // FIX: order status is exactly the kind of response a browser/CDN/
        // intermediate cache will happily reuse on repeat polling if not
        // told otherwise — which would show a stale PENDING forever even
        // after the backend genuinely moves to COMPLETED. No caching is
        // ever correct for this endpoint.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(checkerService.getCheckerOrderStatusByRef(reference));
    }

    // ── Wallet flow ───────────────────────────────────────────────────────────

    @PostMapping("/wallet/purchase")
    public ResponseEntity<CheckerOrderResponse> purchaseWithWallet(
            @Valid @RequestBody CheckerWalletRequest request) {
        UUID userId = currentUserId();
        log.info("[CHECKER-CONTROLLER] POST /wallet/purchase: userId={} phone={} examType={}",
                userId, request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(checkerService.purchaseCheckerWallet(userId, request));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<Page<CheckerOrderResponse>> getHistory(Pageable pageable) {
        UUID userId = currentUserId();
        log.info("[CHECKER-CONTROLLER] GET /history: userId={}", userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(checkerService.getCheckerHistory(userId, pageable));
    }
}
