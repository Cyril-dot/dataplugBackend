package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.response.CheckerResellerPricingResponse;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerResellerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.repos.CheckerResellerPricingRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A reseller manages the checker (BECE/WASSCE) prices they show on their own
 * storefront — the CheckerResellerPricing equivalent of whatever
 * ResellerPricing controller endpoints you have for data bundles.
 *
 * ── ✅ FIXED: was @AuthenticationPrincipal UUID resellerId ──────────────
 * Same bug as CheckerController — JwtAuthFilter's principal is a
 * UserPrincipal record, not a raw UUID, so @AuthenticationPrincipal UUID
 * silently resolved to null on every call. Fixed to the currentResellerId()
 * pattern already proven working in WalletController/OrderController.
 *
 * ── ✅ FIXED: was returning raw CheckerResellerPricing entities ──────────
 * See CheckerResellerPricingResponse's Javadoc — the entity's nested User
 * reseller field was serializing in full, including passwordHash.
 */
@Slf4j
@RestController
@RequestMapping("/api/reseller/checker-pricing")
@RequiredArgsConstructor
public class ResellerCheckerPricingController {

    private final CheckerResellerPricingRepository checkerResellerPricingRepository;
    private final UserRepository userRepository;

    private UUID currentResellerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    private CheckerResellerPricingResponse toResponse(CheckerResellerPricing p) {
        return CheckerResellerPricingResponse.builder()
                .id(p.getId())
                .examType(p.getExamType().name())
                .sellingPriceGhc(p.getSellingPriceGhc())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    @GetMapping
    public ResponseEntity<List<CheckerResellerPricingResponse>> getMyPricing() {
        UUID resellerId = currentResellerId();
        User reseller = findUserOrThrow(resellerId);
        log.info("[RESELLER-CHECKER-PRICING] Fetching pricing for resellerId={}", resellerId);
        List<CheckerResellerPricingResponse> response = checkerResellerPricingRepository
                .findByReseller(reseller).stream().map(this::toResponse).toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<CheckerResellerPricingResponse> setPricing(
            @RequestBody SetCheckerPricingRequest request) {
        UUID resellerId = currentResellerId();
        User reseller = findUserOrThrow(resellerId);
        log.info("[RESELLER-CHECKER-PRICING] Setting pricing: resellerId={} examType={} sellingPrice={}",
                resellerId, request.getExamType(), request.getSellingPriceGhc());

        CheckerResellerPricing pricing = checkerResellerPricingRepository
                .findByResellerAndExamType(reseller, request.getExamType())
                .orElse(CheckerResellerPricing.builder()
                        .reseller(reseller)
                        .examType(request.getExamType())
                        .build());

        pricing.setSellingPriceGhc(request.getSellingPriceGhc());

        CheckerResellerPricing saved = checkerResellerPricingRepository.save(pricing);
        log.info("[RESELLER-CHECKER-PRICING] ✔ Saved: id={} examType={}", saved.getId(), saved.getExamType());
        return ResponseEntity.ok(toResponse(saved));
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    @Data
    public static class SetCheckerPricingRequest {
        @NotNull
        private CheckerPricing.ExamType examType;
        @NotNull
        private BigDecimal sellingPriceGhc;
    }
}
