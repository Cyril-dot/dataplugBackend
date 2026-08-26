package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestStorefrontCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.services.ResellerStorefrontService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Checker (BECE/WASSCE) purchase through a reseller's public storefront.
 *
 * Path convention (/api/v1/store/{slug}/...) matches what's implied by the
 * existing bundle storefront DTOs' Javadoc — adjust if your actual
 * ResellerStorefrontController uses a different base path.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/store/{slug}/checker-order")
@RequiredArgsConstructor
public class StorefrontCheckerController {

    private final ResellerStorefrontService resellerStorefrontService;

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
            @AuthenticationPrincipal UUID customerId,
            @Valid @RequestBody CheckerWalletRequest request) {
        log.info("[STOREFRONT-CHECKER] POST /store/{}/checker-order/wallet: customerId={} phone={} examType={}",
                slug, customerId, request.getPhoneNumber(), request.getExamType());
        return ResponseEntity.ok(
                resellerStorefrontService.placeWalletStorefrontCheckerOrder(slug, customerId, request));
    }
}
