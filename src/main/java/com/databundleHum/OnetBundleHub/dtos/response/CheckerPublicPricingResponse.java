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
    /**
     * False when there are zero unused CheckerStock rows for this exam
     * type right now. The frontend uses this to grey out / hide the option
     * before a customer pays for something we can't actually deliver —
     * previously a customer could pay for an out-of-stock exam type and
     * only find out after paying, when their order failed and got refunded.
     */
    private boolean inStock;
}
