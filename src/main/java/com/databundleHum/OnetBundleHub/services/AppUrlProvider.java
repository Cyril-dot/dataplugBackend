package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Provides the application base URL for building referral links, store URLs,
 * and redirect targets.
 *
 * The canonical site URL is hard-coded in AppConfig so referral links do not
 * depend on an environment setting that may not be accessible during a domain change.
 *
 * Used by:
 *   - AffiliateService        (referral URL construction)
 *   - ResellerServiceImpl     (store URL + referral URL construction)
 *   - ResellerStorefrontService
 *   - AffiliateRedirectController
 */
@Component
@RequiredArgsConstructor
public class AppUrlProvider {

    private final AppConfig appConfig;

    /**
     * Returns the base URL with no trailing slash.
     * e.g. "https://www.datapackk.shop"
     */
    public String getBaseUrl() {
        String baseUrl = appConfig.getAppBaseUrl();
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    /**
     * Build the full affiliate referral URL for a given code.
     * e.g. "https://www.datapackk.shop/a/A3KP9WZQ"
     */
    public String buildAffiliateUrl(String affiliateCode) {
        return getBaseUrl() + "/a/" + affiliateCode;
    }

    /**
     * Build the full reseller store URL for a given slug.
     * e.g. "https://www.datapackk.shop/store/kwame-data"
     */
    public String buildStoreUrl(String storeSlug) {
        return getBaseUrl() + "/store/" + storeSlug;
    }

    /**
     * Build the reseller referral link (for attracting sub-customers).
     * e.g. "https://www.datapackk.shop/ref/kwame-data"
     */
    public String buildResellerReferralUrl(String storeSlug) {
        return getBaseUrl() + "/ref/" + storeSlug;
    }
}
