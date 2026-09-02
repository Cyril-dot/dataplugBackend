package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.response.StoreOverviewResponse;
import com.databundleHum.OnetBundleHub.entity.ResellerProfile;
import com.databundleHum.OnetBundleHub.repos.ResellerProfileRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.services.ResellerStorefrontService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * "Everything about a store in one call" — the reseller's own dashboard
 * view, plus an admin-facing lookup-by-slug variant. Both delegate to
 * ResellerStorefrontService.getStoreOverview(slug) — the self-view endpoint
 * just resolves the caller's own slug first.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StoreOverviewController {

    private final ResellerStorefrontService resellerStorefrontService;
    private final ResellerProfileRepository resellerProfileRepository;

    /**
     * GET /api/reseller/store/overview
     * The authenticated reseller's own store, in full.
     */
    @GetMapping("/api/reseller/store/overview")
    public ResponseEntity<StoreOverviewResponse> getMyStoreOverview(
            @AuthenticationPrincipal UUID resellerId) {
        log.info("[STORE-OVERVIEW] Self-view requested: resellerId={}", resellerId);

        ResellerProfile profile = resellerProfileRepository.findByUser_Id(resellerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No reseller profile found for userId=" + resellerId));

        return ResponseEntity.ok(resellerStorefrontService.getStoreOverview(profile.getStoreSlug()));
    }

    /**
     * GET /api/admin/stores/{slug}/overview
     * Admin inspection of any store by slug.
     */
    @GetMapping("/api/admin/stores/{slug}/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StoreOverviewResponse> getStoreOverviewAdmin(@PathVariable String slug) {
        log.info("[STORE-OVERVIEW] Admin lookup requested: slug={}", slug);
        return ResponseEntity.ok(resellerStorefrontService.getStoreOverview(slug));
    }
}