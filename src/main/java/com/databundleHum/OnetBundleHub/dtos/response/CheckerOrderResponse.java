package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A checker order as returned to the customer — used for the immediate
 * wallet-purchase response, the guest post-payment status lookup, and the
 * checker purchase history list.
 *
 * serial/pin/examDate/resultsLink are only populated once status = COMPLETED.
 */
@Data
@Builder
public class CheckerOrderResponse {
    private Long id;
    private String phoneNumber;
    private String examType;
    private BigDecimal priceGhc;
    private String paymentMethod;
    private String gatewayRef;
    private String status;
    private String serial;
    private String pin;
    private String examDate;
    private String resultsLink;
    private String failureReason;
    private boolean guest;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
