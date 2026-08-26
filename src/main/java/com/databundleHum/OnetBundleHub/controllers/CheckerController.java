package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.services.CheckerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Result-checker (BECE/WASSCE) purchase endpoints — guest and wallet flows.
 *
 * NOTE: @AuthenticationPrincipal is assumed to resolve to a UUID user id,
 * matching the pattern implied elsewhere in this codebase (OrderController
 * wasn't available to confirm the exact security annotation used there —
 * adjust the wallet endpoints below to match your actual auth principal
 * type/extraction if it differs, e.g. a custom UserDetails wrapper).
 */
@Slf4j
@RestController
@RequestMapping("/api/checkers")
@RequiredArgsConstructor
public class CheckerController {

    private final CheckerService checkerService;

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
        return ResponseEntity.ok(checkerService.getCheckerOrderStatusByRef(reference));
    }

    // ── Wallet flow ───────────────────────────────────────────────────────────

    @PostMapping("/wallet/purchase")
    public ResponseEntity<CheckerOrderResponse> purchaseWithWallet(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CheckerWalletRequest request) {
        log.info("[CHECKER-CONTROLLER] POST /wallet/purchase: userId={} phone={} examType={}",
                userId, request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(checkerService.purchaseCheckerWallet(userId, request));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<Page<CheckerOrderResponse>> getHistory(
            @AuthenticationPrincipal UUID userId,
            Pageable pageable) {
        log.info("[CHECKER-CONTROLLER] GET /history: userId={}", userId);
        return ResponseEntity.ok(checkerService.getCheckerHistory(userId, pageable));
    }
}
