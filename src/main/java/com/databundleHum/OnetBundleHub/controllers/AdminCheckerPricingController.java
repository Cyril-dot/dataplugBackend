package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.repos.CheckerPricingRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin management of platform-wide checker (BECE/WASSCE) pricing — the
 * CheckerPricing equivalent of whatever PlatformSettings admin endpoints
 * you have for data bundles (not shown to me, so this is a standalone
 * controller; fold it into an existing PlatformSettings admin controller
 * if you'd rather keep bundle and checker pricing management together).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/checker-pricing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")  // ✅ FIXED: was hasRole('ADMIN') — role is ROLE_SUPER_ADMIN
public class AdminCheckerPricingController {

    private final CheckerPricingRepository checkerPricingRepository;

    @GetMapping
    public ResponseEntity<List<CheckerPricing>> getAll() {
        log.info("[ADMIN-CHECKER-PRICING] Fetching all checker pricing rows");
        return ResponseEntity.ok(checkerPricingRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<CheckerPricing> createOrUpdate(@RequestBody UpsertCheckerPricingRequest request) {
        log.info("[ADMIN-CHECKER-PRICING] Upserting: examType={} publicPrice={} resellerPrice={} category={}",
                request.getExamType(), request.getPublicPriceGhc(), request.getResellerPriceGhc(),
                request.getDataBossHubCategory());

        CheckerPricing pricing = checkerPricingRepository
                .findByExamTypeAndActiveTrue(request.getExamType())
                .orElse(CheckerPricing.builder().examType(request.getExamType()).build());

        pricing.setPublicPriceGhc(request.getPublicPriceGhc());
        pricing.setResellerPriceGhc(request.getResellerPriceGhc());
        pricing.setDataBossHubCategory(request.getDataBossHubCategory());
        pricing.setActive(request.getActive() != null ? request.getActive() : true);

        CheckerPricing saved = checkerPricingRepository.save(pricing);
        log.info("[ADMIN-CHECKER-PRICING] ✔ Saved: id={} examType={}", saved.getId(), saved.getExamType());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        log.info("[ADMIN-CHECKER-PRICING] Deactivating: id={}", id);
        CheckerPricing pricing = checkerPricingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Checker pricing not found: " + id));
        pricing.setActive(false);
        checkerPricingRepository.save(pricing);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class UpsertCheckerPricingRequest {
        @NotNull
        private CheckerPricing.ExamType examType;
        @NotNull
        private BigDecimal publicPriceGhc;
        @NotNull
        private BigDecimal resellerPriceGhc;
        private String dataBossHubCategory;
        private Boolean active;
    }
}
