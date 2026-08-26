package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerResellerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.repos.CheckerResellerPricingRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A reseller manages the checker (BECE/WASSCE) prices they show on their own
 * storefront — the CheckerResellerPricing equivalent of whatever
 * ResellerPricing controller endpoints you have for data bundles.
 */
@Slf4j
@RestController
@RequestMapping("/api/reseller/checker-pricing")
@RequiredArgsConstructor
public class ResellerCheckerPricingController {

    private final CheckerResellerPricingRepository checkerResellerPricingRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<CheckerResellerPricing>> getMyPricing(
            @AuthenticationPrincipal UUID resellerId) {
        User reseller = findUserOrThrow(resellerId);
        log.info("[RESELLER-CHECKER-PRICING] Fetching pricing for resellerId={}", resellerId);
        return ResponseEntity.ok(checkerResellerPricingRepository.findByReseller(reseller));
    }

    @PostMapping
    public ResponseEntity<CheckerResellerPricing> setPricing(
            @AuthenticationPrincipal UUID resellerId,
            @RequestBody SetCheckerPricingRequest request) {
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
        return ResponseEntity.ok(saved);
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
