package com.databundleHum.OnetBundleHub.dtos;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for GET /api/v1/store/{slug}
 *
 * Public endpoint — no authentication required.
 * Contains the store's branding/customization data and the lists of
 * bundles AND checkers the reseller has priced at their custom selling
 * prices.
 *
 * All new fields are nullable — resellers who haven't filled them in
 * simply won't have the corresponding UI elements rendered on the storefront.
 *
 * ── CHECKER FEATURE (2026-08-26) ──────────────────────────────────────────
 * Added `checkers` alongside the existing `bundles` list. Empty list (not
 * null) if the reseller hasn't priced any checker exam types — frontend
 * should hide the checkers section when empty, same convention as an empty
 * bundles list.
 */
@Data
@Builder
public class StorefrontResponse {

    // ── Identity ──────────────────────────────────────────────────────────────

    private String           storeSlug;

    // ── Existing branding fields ──────────────────────────────────────────────

    private String           storeName;
    private String           storeTagline;
    private String           storeLogoUrl;
    /** Hex accent colour, e.g. "#1A73E8". Used for the storefront header/buttons. */
    private String           themeColour;

    // ── Customization fields ────────────────────────────────────────────────

    private String           whatsappNumber;
    private String           instagramHandle;
    private String           bannerImageUrl;
    private String           welcomeMessage;
    private String           buttonStyle;
    private String           storeTheme;

    // ── Bundle list ───────────────────────────────────────────────────────────

    private List<BundleItem> bundles;

    // ── Checker list (NEW) ──────────────────────────────────────────────────

    private List<CheckerItem> checkers;

    @Data
    @Builder
    public static class BundleItem {
        private String     network;
        private BigDecimal capacityGb;
        /** The reseller's custom selling price — what the customer pays. */
        private BigDecimal sellingPriceGhc;
    }

    @Data
    @Builder
    public static class CheckerItem {
        /** "BECE" | "WASSCE" | future exam types. */
        private String     examType;
        /** The reseller's custom selling price — what the customer pays. */
        private BigDecimal sellingPriceGhc;
    }
}
