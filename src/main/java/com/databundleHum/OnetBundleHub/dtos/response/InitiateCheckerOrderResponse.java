package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Returned immediately after a guest checker order is initiated — before
 * payment. Credentials are NOT included here; the customer is redirected to
 * Korapay's checkoutUrl, pays, and the webhook fulfils the order. Use
 * CheckerController's status-by-reference endpoint after redirect to fetch
 * the delivered credentials.
 */
@Data
@Builder
public class InitiateCheckerOrderResponse {
    private String gatewayRef;
    private String checkoutUrl;
    private BigDecimal amountGhc;
    private String phoneNumber;
    private String examType;
}
