package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Reseller checker pricing, safe for JSON — no nested User.
 *
 * ── ✅ FIXED: was returning raw CheckerResellerPricing entities ──────────
 * The entity has a @ManyToOne User reseller field. Returning entities
 * directly from ResellerCheckerPricingController meant that field (already
 * loaded, not a lazy proxy, since findByReseller(reseller) is called with
 * an already-fetched User) serialized in full — including passwordHash.
 * See User.passwordHash's own @JsonIgnore for the defense-in-depth half of
 * this fix; this DTO is the specific fix for this endpoint.
 */
@Data
@Builder
public class CheckerResellerPricingResponse {
    private Long id;
    private String examType;
    private BigDecimal sellingPriceGhc;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
