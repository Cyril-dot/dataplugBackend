package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Public-safe checker pricing — examType + publicPriceGhc only. Deliberately
 * excludes resellerPriceGhc and dataBossHubCategory, mirroring how
 * PricingResponse (bundles) never exposes wholesale cost on the public
 * route. Returned by GET /api/checkers/pricing — the endpoint the
 * checker/buy frontend page needs before it can show a real price instead
 * of a guessed one.
 */
@Data
@Builder
public class CheckerPublicPricingResponse {
    private String examType;
    private BigDecimal publicPriceGhc;
}
