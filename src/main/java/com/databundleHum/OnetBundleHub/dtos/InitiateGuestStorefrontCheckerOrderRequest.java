package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for POST /api/v1/store/{slug}/checker-order/guest
 */
@Data
public class InitiateGuestStorefrontCheckerOrderRequest {
    @NotBlank
    private String phoneNumber;
    @NotNull
    private CheckerPricing.ExamType examType;
}
