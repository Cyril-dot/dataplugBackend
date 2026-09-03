package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestStorefrontCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.ResellerStorefrontService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Checker (BECE/WASSCE) purchase through a reseller's public storefront.
 *
 * Path convention (/api/v1/store/{slug}/...) matches what's implied by the
 * existing bundle storefront DTOs' Javadoc — adjust if your actual
 * ResellerStorefrontController uses a different base path.
 *
 * ── ✅ FIXED: was @AuthenticationPrincipal UUID customerId ───────────────
 * Same bug as CheckerController — the JWT principal is a UserPrincipal
 * record, not a raw UUID, so @AuthenticationPrincipal UUID silently
 * resolved to null. Fixed to the currentCustomerId() pattern already
 * proven working in WalletController/OrderController.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/store/{slug}/checker-order")
@RequiredArgsConstructor
public class StorefrontCheckerController {

    private final ResellerStorefrontService resellerStorefrontService;

    private UUID currentCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    @PostMapping("/guest")
    public ResponseEntity<InitiateCheckerOrderResponse> initiateGuestCheckerOrder(
            @PathVariable String slug,
            @Valid @RequestBody InitiateGuestStorefrontCheckerOrderRequest request) {
        log.info("[STOREFRONT-CHECKER] POST /store/{}/checker-order/guest: phone={} examType={}",
                slug, request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(
                resellerStorefrontService.initiateGuestStorefrontCheckerOrder(slug, request));
    }

    @PostMapping("/wallet")
    public ResponseEntity<CheckerOrderResponse> purchaseWithWallet(
            @PathVariable String slug,
            @Valid @RequestBody CheckerWalletRequest request) {
        UUID customerId = currentCustomerId();
        log.info("[STOREFRONT-CHECKER] POST /store/{}/checker-order/wallet: customerId={} phone={} examType={}",
                slug, customerId, request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(
                resellerStorefrontService.placeWalletStorefrontCheckerOrder(slug, customerId, request));
    }
}
