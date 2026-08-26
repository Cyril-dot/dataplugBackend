package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * "Everything about a store in one call" — used by the reseller's own
 * dashboard (to manage their store) and by admin (to inspect any store by
 * slug). Combines branding, both product-type price lists, and financial
 * aggregates that were previously only reachable by hitting several
 * different endpoints separately.
 *
 * Distinct from StorefrontResponse (the PUBLIC customer-facing storefront
 * view) — this is the private, owner/admin-facing management view, and
 * includes financial data that must never be exposed publicly.
 */
@Data
@Builder
public class StoreOverviewResponse {

    // ── Identity / branding ──────────────────────────────────────────────────

    private String storeSlug;
    private String storeName;
    private String storeTagline;
    private String storeLogoUrl;
    private String themeColour;
    private String whatsappNumber;
    private String instagramHandle;
    private String bannerImageUrl;
    private String welcomeMessage;
    private String buttonStyle;
    private String storeTheme;
    private String status;

    // ── Pricing ───────────────────────────────────────────────────────────────

    private List<BundlePriceItem> bundlePricing;
    private List<CheckerPriceItem> checkerPricing;

    // ── Financials ────────────────────────────────────────────────────────────

    private BigDecimal totalRevenueGhc;
    private BigDecimal totalCostGhc;
    private BigDecimal totalProfitGhc;
    private BigDecimal profitPaidGhc;
    private BigDecimal availableProfitGhc;

    @Data
    @Builder
    public static class BundlePriceItem {
        private String network;
        private BigDecimal capacityGb;
        private BigDecimal costPriceGhc;
        private BigDecimal sellingPriceGhc;
    }

    @Data
    @Builder
    public static class CheckerPriceItem {
        private String examType;
        private BigDecimal costPriceGhc;
        private BigDecimal sellingPriceGhc;
    }
}
