package com.databundleHum.OnetBundleHub.dtos;
 
import lombok.Builder;
import lombok.Data;
 
/**
 * Response for GET /api/v1/reseller/store/share
 *
 * The frontend uses storeUrl and referralUrl to build the share flyout.
 * QR code generation always happens client-side (no backend involvement).
 */
@Data
@Builder
public class StoreShareResponse {
    /** Effective display name of the store (storeName or fullName fallback). */
    private String storeName;
    /** URL-safe slug, e.g. "kwame-data". */
    private String storeSlug;
    /** Full store URL, e.g. "https://www.datapackk.shop/store/kwame-data". */
    private String storeUrl;
    /** Full referral URL, e.g. "https://www.datapackk.shop/ref/kwame-data". */
    private String referralUrl;
}
