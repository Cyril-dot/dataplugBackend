package com.databundleHum.OnetBundleHub.dtos;

import lombok.Builder;
import lombok.Data;

/**
 * Response for POST /api/v1/affiliate/activate
 */
@Data
@Builder
public class AffiliateActivateResponse {
    /** The user's unique affiliate code, e.g. "A3KP9WZQ". */
    private String  affiliateCode;
    /** Full referral URL, e.g. "https://www.datapackk.shop/a/A3KP9WZQ". */
    private String  referralUrl;
    /** Whether the affiliate programme is currently active. */
    private boolean active;
}
